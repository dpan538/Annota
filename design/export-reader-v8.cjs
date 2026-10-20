const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium,packages,executablePath}=require('./browser-runtime.cjs');
const output = path.join(__dirname, process.env.ANNOTA_LIBRARY_ONLY?'screenshots/library-v1':process.env.ANNOTA_GUIDE_ONLY?'screenshots/onboarding-v1':'screenshots/reader-v8');
const settle = page => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));

async function main() {
  const fragment = await fs.readFile(path.join(__dirname, 'home-ui.fragment.html'), 'utf8');
  assert(Buffer.byteLength(fragment) < 4_000_000);
  const lucide = await fs.readFile(path.join(packages, 'lucide/dist/umd/lucide.min.js'), 'utf8');
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({
    executablePath,
    headless: true
  });
  const report = [];
  async function setup(orientation, palette, size = 68, language = 'zh', reader) {
    const width = orientation === 'landscape' ? 3408 : 2272, height = orientation === 'landscape' ? 2272 : 3408;
    const page = await browser.newPage({ viewport: { width, height }, colorScheme: 'light', reducedMotion: 'reduce' });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', route => route.abort());
    const saved = { privateContent: { version: 'o3-home-v1', palette, buttonSize: size, language, reader } };
    await page.setContent(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>html,body{margin:0;padding:0}#o3-home-concept{width:${width}px}#o3-home-concept .o3-frame-portrait{max-width:none!important}</style></head><body><script>${lucide}</script><script>window.openai={widgetState:${JSON.stringify(saved)},setWidgetState:async value=>window.__savedState=structuredClone(value)};</script>${fragment}</body></html>`);
    await page.evaluate(async orientation => {
      document.querySelectorAll('[data-variant]').forEach(s => s.hidden = s.querySelector('[data-view]').dataset.view !== orientation);
      await document.fonts.ready;
    }, orientation);
    const app = page.locator(`[data-view="${orientation}"]`), ui = app.locator('.ar-reader');
    if (!reader?.open) await app.locator('[data-doc="p1"]').click();
    await settle(page);
    await ui.locator('.ar-page-image').first().evaluate(img => img.decode());
    await page.waitForFunction(()=>document.getElementById('o3-home-concept').dataset.textReady==='true');
    await page.waitForFunction(()=>document.getElementById('o3-home-concept').dataset.guideReady==='true');
    return { page, app, ui, width, height, errors };
  }
  async function check(c, fit = true) {
    await settle(c.page);
    const layout = await c.ui.evaluate(surface => {
      const app = surface.closest('[data-view]'), scale = app.getBoundingClientRect().width / app.offsetWidth;
      const viewport = surface.querySelector('.ar-viewport').getBoundingClientRect();
      const controls = [...surface.querySelectorAll('button')].filter(b => !b.closest('[inert]') && b.getClientRects().length && getComputedStyle(b).visibility !== 'hidden');
      const toolbar = [...surface.querySelectorAll('.ar-header button,.ar-toolbar button')].filter(b => b.getClientRects().length);
      const bounds = surface.getBoundingClientRect();
      const bad = controls.filter(b => { const r = b.getBoundingClientRect(), minimum = b.closest('.ar-header') ? 33.9 : b.closest('.ar-capsule,.ar-dictionary,.ar-context-menu,.ar-selection-menu,.ar-guide-page') ? 29.9 : 59.9; return r.width / scale < minimum || r.height / scale < minimum || r.left < bounds.left - 1 || r.right > bounds.right + 1; }).map(b => b.outerHTML.slice(0, 140));
      const overlaps = [];
      for (let i = 0; i < toolbar.length; i++) for (let j = i + 1; j < toolbar.length; j++) {
        const a = toolbar[i].getBoundingClientRect(), b = toolbar[j].getBoundingClientRect();
        if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1) overlaps.push([toolbar[i].getAttribute('aria-label'), toolbar[j].getAttribute('aria-label')]);
      }
      const paper = surface.querySelector('.ar-page-window').getBoundingClientRect();
      const groups = ['.ar-navigation','.ar-toolbar','.ar-header-right'].map(selector => surface.querySelector(selector).getBoundingClientRect());
      return {
        bad, overlaps, allControlsTop: toolbar.every(b => b.getBoundingClientRect().bottom <= viewport.top + 1),
        paperFits: paper.left >= viewport.left - 1 && paper.right <= viewport.right + 1 && paper.top >= viewport.top - 1 && paper.bottom <= viewport.bottom + 1,
        rightOrder: [...surface.querySelectorAll('.ar-header-right button')].map(b => b.dataset.readerAction),
        toolOrder: [...surface.querySelectorAll('.ar-toolgroup button')].map(b => b.dataset.tool),
        oneRow: Math.max(...toolbar.map(b=>b.getBoundingClientRect().top))-Math.min(...toolbar.map(b=>b.getBoundingClientRect().top))<1,
        headerHeight: surface.querySelector('.ar-header').getBoundingClientRect().height/scale,
        toolbarTop: Math.min(...toolbar.map(b=>b.getBoundingClientRect().top-bounds.top))/scale,
        chromeColor: getComputedStyle(surface.querySelector('.ar-header')).backgroundColor,
        canvasColor: getComputedStyle(surface.querySelector('.ar-workspace')).backgroundColor,
        paperColor: getComputedStyle(surface.querySelector('.ar-paper')).backgroundColor,
        readingIcon: surface.querySelector('[data-reader-action="focus"] svg').getAttribute('data-lucide'),
        dictionaryIcon: surface.querySelector('[data-reader-action="dictionary"] svg').getAttribute('data-lucide'),
        iconSizes: toolbar.map(b=>b.querySelector('svg').getBoundingClientRect().width/scale),
        groupGaps: [(groups[1].left-groups[0].right)/scale,(groups[2].left-groups[1].right)/scale],
        toolsCentered: Math.abs((groups[1].left+groups[1].right)/2-(bounds.left+bounds.right)/2)<1,
        localFileVisible: [...surface.querySelectorAll('[data-reader-label="local"]')].some(e => e.getClientRects().length)
      };
    });
    assert.deepEqual(layout.bad, []); assert.deepEqual(layout.overlaps, []);
    assert(layout.allControlsTop);assert(layout.oneRow);assert(layout.headerHeight>=61&&layout.headerHeight<63);
    assert(layout.toolbarTop>=23.9); if (fit) assert(layout.paperFits);
    assert.equal(layout.canvasColor,'rgb(244, 244, 244)');assert.equal(layout.paperColor,'rgb(255, 255, 255)');
    assert.equal(layout.readingIcon,'book-open-text');assert.equal(layout.dictionaryIcon,'book-a');
    assert(layout.toolsCentered);assert(layout.groupGaps.every(gap=>gap>=20));assert(layout.iconSizes.every(size=>Math.abs(size-20)<.1));
    assert.deepEqual(layout.rightOrder, ['add-page', 'focus', 'dictionary', 'more']);
    assert.deepEqual(layout.toolOrder, ['pen','eraser','ruler','image','shape','hand']);
    assert.equal(layout.localFileVisible, false);
    assert.equal(await c.ui.locator('.ar-footer,.ar-toolrail').count(), 0);
    assert.deepEqual(c.errors, []);
    return layout;
  }
  async function shot(c, name) {
    await settle(c.page); await c.page.mouse.move(0,0);
    await c.page.evaluate(()=>document.fonts.ready);
    await c.ui.locator('.ar-page-image').evaluateAll(imgs=>Promise.all(imgs.filter(img=>img.getClientRects().length&&getComputedStyle(img).visibility!=='hidden').map(img=>img.decode())));
    await c.page.screenshot({ path: path.join(output, name + '.png'), clip: { x:0,y:0,width:c.width,height:c.height } });
  }
  async function stroke(c) {
    const box = await c.ui.locator('canvas').first().boundingBox();
    await c.page.mouse.move(box.x+box.width*.25,box.y+box.height*.5);
    await c.page.mouse.down();
    await c.page.mouse.move(box.x+box.width*.65,box.y+box.height*.5,{steps:18});
    await c.page.mouse.up();
  }
  try {
    if(process.env.ANNOTA_LIBRARY_ONLY){
      await require('./verify-library.cjs')({setup,check,shot,settle,report});
      await fs.writeFile(path.join(output,'checks.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({passed:report.length,output},null,2));return;
    }
    if(process.env.ANNOTA_GUIDE_ONLY){
      await require('./verify-guide.cjs')({setup,check,shot,settle,report});
      await fs.writeFile(path.join(output,'checks.json'),JSON.stringify(report,null,2));
      console.log(JSON.stringify({passed:report.length,output},null,2));return;
    }
    if(!process.env.ANNOTA_DETAILS_ONLY&&!process.env.ANNOTA_TEXT_ONLY){
    for (const palette of ['pine','graphite-yellow','cobalt-sage','black-kraft']) {
      for (const orientation of ['landscape','portrait']) {
        const c = await setup(orientation,palette);
        const layout = await check(c);
        await shot(c, `annota-${palette}-reader-${orientation}`);
        await c.ui.locator('.ar-header [data-reader-action="more"]').click();
        assert.equal(await c.ui.locator('[data-reader-label="local"]').isVisible(), true);
        if (palette === 'cobalt-sage') await shot(c, `annota-${palette}-reader-${orientation}-info`);
        await c.ui.locator('.ar-more [data-reader-action="close-popover"]').click();
        await c.ui.locator('.ar-header [data-reader-action="pages"]').click();
        assert.equal(await c.ui.locator('.ar-pages').isVisible(),true);
        await c.ui.locator('[data-page="11"]').click();
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.page),11);
        await c.ui.locator('[data-page="1"]').click();
        if (palette==='cobalt-sage') await shot(c,`annota-cobalt-sage-reader-${orientation}-sidebar`);
        await c.ui.locator('.ar-header [data-reader-action="pages"]').click();
        await c.ui.locator('[data-tool="pen"]').click();
        await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
        await stroke(c);
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.strokes.length),1);
        await c.ui.locator('[data-tool="hand"]').click();
        const handBox=await c.ui.locator('canvas').first().boundingBox();
        await c.page.mouse.move(handBox.x+handBox.width*.5,handBox.y+handBox.height*.45);
        await c.page.mouse.down();
        await c.page.mouse.move(handBox.x+handBox.width*.5,handBox.y+handBox.height*.55,{steps:10});
        await c.page.mouse.up();
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.strokes.length),1);
        await c.ui.locator('.ar-header [data-reader-action="focus"]').click();
        assert.equal(await c.ui.locator('.ar-toolbar').isVisible(),false);
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.tool),'hand');
        if (palette==='cobalt-sage') await shot(c,`annota-cobalt-sage-reader-${orientation}-reading`);
        await c.ui.locator('.ar-header [data-reader-action="focus"]').click();
        await c.ui.locator('.ar-header [data-reader-action="columns"]').click();
        await c.ui.locator('[data-columns="2"]').click();
        await settle(c.page);
        assert.deepEqual(await c.ui.locator('.ar-page-image:visible').evaluateAll(imgs=>imgs.map(img=>img.dataset.page)),['1','2']);
        if(palette==='cobalt-sage'){
          await shot(c,`annota-cobalt-sage-reader-${orientation}-spread`);
          await c.ui.locator('.ar-header [data-reader-action="pages"]').click();
          await shot(c,`annota-cobalt-sage-reader-${orientation}-spread-sidebar`);
          await c.ui.locator('.ar-header [data-reader-action="pages"]').click();
        }
        await c.page.keyboard.press('ArrowRight');
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.page),3);
        assert.deepEqual(await c.ui.locator('.ar-page-image:visible').evaluateAll(imgs=>imgs.map(img=>img.dataset.page)),['3','4']);
        await c.page.keyboard.press('ArrowLeft');
        await c.ui.locator('.ar-header [data-reader-action="columns"]').click();
        await c.ui.locator('[data-columns="1"]').click();
        await c.page.keyboard.press('ArrowLeft');
        await c.ui.locator('.ar-header [data-reader-action="add-page"]').click();
        await c.ui.locator('[data-add-paper="-2"]').click();
        assert.equal(await c.ui.locator('.ar-page-image').first().isVisible(),false);
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.order.length),12);
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.order[1]),-2);
        await c.ui.locator('[data-tool="shape"]').click();
        await c.ui.locator('[data-shape="rect"]').click();
        await stroke(c);
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.strokes.at(-1).tool),'shape');
        await c.ui.locator('.ar-header [data-reader-action="back"]').click();
        assert.equal(await c.app.locator('.o3-main').isVisible(),true);
        await c.app.locator('[data-doc="p1"]').click();
        assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.page),2);
        assert.deepEqual(c.errors,[]);
        report.push({palette,orientation,layout,flow:'sidebar, ink, hand disables writing, reading mode, column sequence, insert page, shapes, library return passed'});
        await c.page.close();
      }
    }
    for (const size of [60,76]) for (const orientation of ['landscape','portrait']) {
      const c = await setup(orientation,'cobalt-sage',size,'en');
      await check(c);
      await c.page.close();
    }
    const c = await setup('landscape','cobalt-sage');
    await c.ui.locator('[data-tool="pen"]').click();
    await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
    await stroke(c);
    await c.ui.locator('.ar-header [data-reader-action="more"]').click();
    await c.ui.locator('.ar-more [data-reader-action="undo"]').click();
    assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.strokes.length),0);
    await c.ui.locator('.ar-more [data-reader-action="redo"]').click();
    assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.strokes.length),1);
    await c.ui.locator('.ar-more [data-reader-action="close-popover"]').click();
    await c.ui.locator('[data-tool="pen"]').click();
    await shot(c,'annota-cobalt-sage-reader-pen-options');
    await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
    await c.ui.locator('.ar-header [data-reader-action="more"]').click();
    await c.ui.locator('[data-fit="width"]').click();
    await settle(c.page);
    assert(await c.ui.locator('.ar-viewport').evaluate(v=>v.scrollHeight>v.clientHeight));
    await c.ui.locator('.ar-viewport').evaluate(v=>v.scrollTop=150);
    await shot(c,'annota-cobalt-sage-reader-landscape-width');
    await c.ui.locator('[data-tool="hand"]').click();
    const handBox=await c.ui.locator('canvas').first().boundingBox(),startX=handBox.x+handBox.width*.6,startY=handBox.y+handBox.height*.4;
    await c.page.mouse.move(startX,startY);await c.page.mouse.down();await c.page.mouse.move(startX-220,startY,{steps:12});await c.page.mouse.up();
    assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.reader.page),2);
    const snapshot=await c.page.evaluate(()=>window.__savedState.privateContent.reader);
    assert(Buffer.byteLength(JSON.stringify(snapshot))<12000);
    await c.page.close();
    const restored=await setup('portrait','cobalt-sage',68,'zh',snapshot);
    assert.equal(await restored.page.evaluate(()=>document.querySelector('[data-view="portrait"] [data-tool="hand"]').getAttribute('aria-pressed')),'true');
    await restored.page.close();
    const functional=await setup('landscape','cobalt-sage');
    const state=()=>functional.page.evaluate(()=>window.__savedState.privateContent.reader);
    async function dragPdf(points,index=0){
      const box=await functional.ui.locator('canvas').nth(index).boundingBox();
      await functional.page.mouse.move(box.x+points[0][0]/612*box.width,box.y+points[0][1]/792*box.height);
      await functional.page.mouse.down();
      for(const [x,y] of points.slice(1))await functional.page.mouse.move(box.x+x/612*box.width,box.y+y/792*box.height,{steps:8});
      await functional.page.mouse.up();
    }
    await functional.ui.locator('[data-tool="pen"]').click();
    await functional.ui.locator('.ar-pen-options [data-ink="mark"]').click();
    await functional.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
    await dragPdf([[82,378],[72,389],[80,400],[70,412],[80,426]]);
    assert.equal((await state()).strokes.length,1);
    assert((await state()).strokes[0].points.length>5);
    await functional.ui.locator('[data-tool="ruler"]').click();
    assert((await state()).ruler.visible);
    await dragPdf([[306,440],[306,500]]);
    assert(Math.abs((await state()).ruler.y-500)<2);
    const r=(await state()).ruler;
    await dragPdf([[r.x+263,r.y],[r.x+240*Math.cos(-Math.PI/18),r.y+240*Math.sin(-Math.PI/18)]]);
    assert.equal((await state()).ruler.angle,-10);
    const moved=(await state()).ruler,a=moved.angle*Math.PI/180,v=[Math.cos(a),Math.sin(a)],n=[-v[1],v[0]];
    const along=(d,edge)=>[moved.x+d*v[0]+edge*n[0],moved.y+d*v[1]+edge*n[1]];
    await dragPdf([along(-180,-34),along(180,-40)]);
    const line=(await state()).strokes.at(-1);
    assert.equal(line.tool,'ruler');assert.equal(line.points.length,2);
    assert(Math.abs((line.points[1][0]-line.points[0][0])*v[1]-(line.points[1][1]-line.points[0][1])*v[0])<.01);
    await shot(functional,'annota-cobalt-sage-reader-pen-ruler');
    await functional.ui.locator('.ar-header [data-reader-action="more"]').click();
    await functional.ui.locator('[data-reader-action="undo"]').click();
    assert.equal((await state()).strokes.length,1);
    await functional.ui.locator('[data-reader-action="redo"]').click();
    assert.equal((await state()).strokes.length,2);
    await functional.ui.locator('.ar-more [data-reader-action="close-popover"]').click();
    await functional.ui.locator('[data-tool="ruler"]').click();
    await functional.ui.locator('[data-tool="eraser"]').click();
    const midpoint=[(line.points[0][0]+line.points[1][0])/2,(line.points[0][1]+line.points[1][1])/2];
    await dragPdf([midpoint,[midpoint[0]+1,midpoint[1]]]);
    assert.equal((await state()).strokes.length,1);
    await functional.ui.locator('[data-tool="pen"]').click();
    await functional.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
    await functional.ui.locator('.ar-header [data-reader-action="columns"]').click();
    await functional.ui.locator('[data-columns="2"]').click();
    await settle(functional.page);
    await dragPdf([[100,400],[140,410],[180,400]],1);
    assert.equal((await state()).strokes.at(-1).page,2);
    assert.equal((await state()).activePage,2);
    await functional.ui.locator('.ar-header [data-reader-action="pages"]').click();
    await functional.ui.locator('.ar-page-grid [data-page="11"]').click();
    assert.equal(await functional.ui.locator('.ar-page-window:visible').count(),1);
    await shot(functional,'annota-cobalt-sage-reader-last-spread');
    await functional.ui.locator('.ar-page-grid [data-page="1"]').click();
    await functional.ui.locator('.ar-header [data-reader-action="pages"]').click();
    await functional.ui.locator('.ar-header [data-reader-action="columns"]').click();
    await functional.ui.locator('[data-columns="1"]').click();
    await functional.page.evaluate(()=>document.querySelector('#o3-home-concept').addEventListener('annota:pick-image',event=>{window.__nativeImageRequest=event.detail;event.preventDefault();},{once:true}));
    await functional.ui.locator('[data-tool="image"]').click();
    assert.equal(await functional.page.evaluate(()=>window.__nativeImageRequest.mediaType),'image');
    const chooserPromise=functional.page.waitForEvent('filechooser');
    await functional.ui.locator('[data-tool="image"]').click();
    const chooser=await chooserPromise;
    assert.equal(chooser.isMultiple(),false);
    await chooser.setFiles(path.join(__dirname,'assets/attention/page-01.webp'));
    await functional.page.waitForFunction(()=>document.querySelector('[data-view="landscape"] .ar-reader').dataset.imageCount==='1');
    assert.equal(await functional.ui.locator('.ar-images').count(),0);
    await functional.ui.locator('.ar-header [data-reader-action="back"]').click();
    await functional.app.locator('[data-action="settings"]').click();
    await functional.app.locator('[data-settings-tab="about"]').click();
    await functional.page.evaluate(()=>document.querySelector('#o3-home-concept').addEventListener('annota:check-update',()=>window.__updateRequested=true));
    await functional.app.locator('[data-action="check-update"]').click();
    assert.equal(await functional.page.evaluate(()=>window.__updateRequested),true);
    assert.match(await functional.app.locator('.o3-toast').innerText(),/尚未接入/);
    await functional.app.locator('.o3-toast').evaluate(el=>el.hidden=true);
    await shot(functional,'annota-cobalt-sage-about-update');
    assert.deepEqual(functional.errors,[]);
    report.push({functional:'Freehand ink; ruler drag, rotation and edge-constrained stroke; undo/redo; erasing a line at its midpoint; right-page ink; final odd page; native picker hook, file chooser and image callback; About update hook passed'});
    await functional.page.close();
    }
    if(!process.env.ANNOTA_TEXT_ONLY)await require('./verify-reader-details.cjs')({setup,check,shot,settle,report});
    await require('./verify-reader-selection.cjs')({setup,check,shot,settle,report});
    await fs.writeFile(path.join(output,process.env.ANNOTA_TEXT_ONLY?'text-checks.json':process.env.ANNOTA_DETAILS_ONLY?'details-checks.json':'checks.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify({passed:report.length,sizes:[60,68,76],output},null,2));
  } finally { await browser.close(); }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
