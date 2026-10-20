const assert = require('node:assert/strict');

module.exports = async ({setup, check, shot, settle, report}) => {
  for (const orientation of ['landscape','portrait']) {
    const c = await setup(orientation,'cobalt-sage');
    const state = () => c.page.evaluate(()=>window.__savedState.privateContent.reader);
    const click = selector => c.ui.locator(selector).click();
    const header = action => click(`.ar-header [data-reader-action="${action}"]`);
    const close = selector => click(`${selector} [data-reader-action="close-popover"]`);
    const draw = async (points) => {
      const box = await c.ui.locator('canvas').first().boundingBox();
      await c.page.mouse.move(box.x+box.width*points[0][0]/612, box.y+box.height*points[0][1]/792);
      await c.page.mouse.down();
      for (const [x,y] of points.slice(1))
        await c.page.mouse.move(box.x+box.width*x/612, box.y+box.height*y/792,{steps:8});
      await c.page.mouse.up();
      await settle(c.page);
    };
    const undo = async () => { await header('more'); await click('.ar-more [data-reader-action="undo"]'); await close('.ar-more'); };
    const context = async (page=1) => { await c.ui.locator(`.ar-page-grid [data-page="${page}"]`).click({button:'right'}); };
    // Capsules overlap the paper without changing its bounding box.
    const paperBefore = await c.ui.locator('.ar-page-window').first().boundingBox();
    for (const tool of ['pen','eraser','shape']) {
      await click(`[data-tool="${tool}"]`);
      const capsule = c.ui.locator(`[data-popover="${tool}"]`);
      assert(await capsule.isVisible());
      assert.deepEqual(await c.ui.locator('.ar-page-window').first().boundingBox(),paperBefore);
      await check(c);
      await shot(c,`annota-cobalt-sage-${orientation}-${tool}-capsule`);
    }
    await header('pages');
    await click('[data-tool="shape"]');
    assert(await c.ui.locator('[data-popover="shape"]').isVisible());
    assert(await c.ui.locator('[data-popover="shape"]').evaluate(e=>{
      const b=e.getBoundingClientRect();
      return [...e.querySelectorAll('button')].every(x=>{const r=x.getBoundingClientRect();return r.left>=b.left&&r.right<=b.right+1;});
    }));
    await header('pages');
    await click('[data-tool="shape"]');
    await click('[data-shape="triangle"]');
    await click('[data-shape-fill]');
    await draw([[160,440],[410,620]]);
    assert.equal((await state()).strokes.at(-1).shape,'triangle');
    assert.equal((await state()).strokes.at(-1).filled,true);
    await undo();
    await click('[data-tool="pen"]');
    await click('[data-pen-type="fountain"]');
    await click('.ar-pen-options [data-width="3"]');
    await close('.ar-pen-options');
    await draw([[140,450],[450,450]]);
    assert.equal((await state()).strokes[0].tool,'fountain');
    // Local eraser splits a stroke; undo restores its original geometry.
    const original=(await state()).strokes;
    await click('[data-tool="eraser"]');
    await c.ui.locator('[data-eraser-mode]').selectOption('partial');
    await draw([[300,449],[300,452]]);
    assert((await state()).strokes.length>=2);
    await undo();
    assert.deepEqual((await state()).strokes,original);
    // Duplicate/rotation/destructive confirmation, all undoable.
    await header('pages');
    const thumb=await c.ui.locator('.ar-page-grid [data-page="1"]').boundingBox();
    await c.page.mouse.move(thumb.x+thumb.width/2,thumb.y+thumb.height/2);
    await c.page.mouse.down();await c.page.waitForTimeout(520);await c.page.mouse.up();
    assert(await c.ui.locator('.ar-context-menu').isVisible());
    assert(await c.ui.locator('[data-page-op="template"]').isDisabled());
    await shot(c,`annota-cobalt-sage-${orientation}-page-menu`);
    await click('[data-page-op="duplicate"]');
    assert.equal((await state()).order.length,12);
    assert.equal((await state()).order[1],1);
    assert((await state()).strokes.some(s=>s.page===2));
    await context(2);await click('[data-page-op="rotate"]');
    assert.equal((await state()).rotations[2],90);
    await context(2);await click('[data-page-op="clear"]');
    assert(await c.ui.locator('.ar-context-confirm').isVisible());
    await click('[data-page-confirm]');
    assert(!(await state()).strokes.some(s=>s.page===2));
    assert.equal((await state()).order[1],1);
    await undo();
    assert((await state()).strokes.some(s=>s.page===2));
    await context(2);await click('[data-page-op="delete"]');await click('[data-page-confirm]');
    assert.equal((await state()).order.length,11);
    await undo();assert.equal((await state()).order.length,12);
    // Add at the end of the thumbnail list, not after the active PDF page.
    await click('.ar-add-thumbnail');await click('[data-add-paper="-2"]');
    assert.equal((await state()).page,13);
    assert.equal((await state()).order.at(-1),-2);
    await context(13);await click('[data-page-op="template"]');await settle(c.page);
    assert(await c.ui.locator('.ar-context-menu').evaluate(e=>e.getBoundingClientRect().bottom<=e.closest('.ar-workspace').getBoundingClientRect().bottom));
    await click('[data-page-template="-1"]');
    assert.equal((await state()).order.at(-1),-1);
    await c.ui.locator('.ar-page-grid').evaluate(el=>el.scrollTop=el.scrollHeight);
    await shot(c,`annota-cobalt-sage-${orientation}-sidebar-add`);
    await c.page.waitForTimeout(810);
    await click('.ar-page-grid [data-page="1"]');
    await header('pages');
    // English and Chinese use the same offline glossary, unknown is explicit.
    await header('dictionary');
    await c.ui.locator('.ar-dict-query').fill('attention');
    await c.ui.locator('.ar-dict-query').press('Enter');
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/注意力/);
    assert.doesNotMatch(await c.ui.locator('.ar-dict-result').innerText(),/按相关性/);
    assert.equal(await c.ui.locator('.ar-dict-result h3').evaluate(e=>getComputedStyle(e).fontSize),'20px');
    assert.deepEqual(await c.ui.locator('.ar-dict-query').evaluate(e=>[getComputedStyle(e).fontSize,getComputedStyle(e).fontWeight]),['16px','400']);
    assert.equal(await c.page.evaluate(()=>window.__savedState.privateContent.search),'');
    await c.ui.locator('.ar-dictionary h2').click();
    await shot(c,`annota-cobalt-sage-${orientation}-dictionary`);
    for(const query of ['attenti','attenton','attnetion','注意']){
      await c.ui.locator('.ar-dict-query').fill(query);
      assert(await c.ui.locator('.ar-dict-match[data-lookup="attention"]').isVisible(),query);
    }
    assert.deepEqual(await c.ui.locator('.ar-dict-match').first().evaluate(e=>[getComputedStyle(e).fontSize,getComputedStyle(e).fontWeight,e.offsetHeight]),['14px','400',42]);
    await c.ui.locator('.ar-dictionary h2').click();
    await shot(c,`annota-cobalt-sage-${orientation}-dictionary-fuzzy`);
    await click('.ar-dict-match[data-lookup="attention"]');
    assert.match(await c.ui.locator('.ar-dict-result h3').innerText(),/attention/);
    await c.ui.locator('.ar-dict-query').fill('注意力');
    await c.ui.locator('.ar-dict-query').press('Enter');
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/attention/);
    await c.ui.locator('.ar-dict-query').fill('unlisted-example');
    await c.ui.locator('.ar-dict-query').press('Enter');
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/未收录/);
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').addEventListener('annota:request-text-input',e=>{window.__inputRequest=e.detail;e.preventDefault();}));
    await click('[data-dict-input="handwriting"]');
    assert.equal(await c.page.evaluate(()=>window.__inputRequest.mode),'handwriting');
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').dispatchEvent(new CustomEvent('annota:text-input-result',{detail:{field:'dictionary',text:'embedding'}})));
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/嵌入/);
    await shot(c,`annota-cobalt-sage-${orientation}-dictionary-handwriting`);
    // Bridge returns are text-only and stale lookups cannot replace newer input.
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').addEventListener('annota:dictionary-query',e=>{window.__query=e.detail;e.preventDefault();}));
    await c.ui.locator('.ar-dict-query').fill('book');
    await c.ui.locator('.ar-dict-query').press('Enter');
    assert.equal(await c.page.evaluate(()=>window.__query.offlineOnly),true);
    await c.page.evaluate(()=>{const root=document.getElementById('o3-home-concept');root.dispatchEvent(new CustomEvent('annota:dictionary-result',{detail:{requestId:window.__query.requestId,entries:[{word:'book',meaning:'书',detail:'<img src=x onerror=alert(1)>'}]}}));});
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/书/);
    assert.equal(await c.ui.locator('.ar-dict-result img').count(),0);
    await c.ui.locator('.ar-dict-query').fill('new-query');
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').dispatchEvent(new CustomEvent('annota:dictionary-result',{detail:{requestId:window.__query.requestId,entries:[{word:'stale',meaning:'old'}]}})));
    assert.doesNotMatch(await c.ui.locator('.ar-dict-result').innerText(),/stale/);
    await close('.ar-dictionary');
    assert.deepEqual(c.errors,[]);
    report.push({details:orientation,passed:'floating capsules, partial erase/undo, long press, duplicate/rotate/clear/delete/undo, append/template, bilingual offline glossary, IME and offline dictionary contracts'});
    await c.page.close();
  }
  const c=await setup('landscape','cobalt-sage');
  const state=()=>c.page.evaluate(()=>window.__savedState.privateContent.reader);
  await c.ui.locator('[data-tool="pen"]').click();
  await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
  // Browser-synthetic pointer tests cover application logic; not physical hardware.
  await c.ui.locator('canvas').first().evaluate(canvas=>{
    const r=canvas.getBoundingClientRect();
    canvas.setPointerCapture=()=>{};canvas.hasPointerCapture=()=>false;
    const send=(type,id,kind,x,y,pressure=.5)=>canvas.dispatchEvent(new PointerEvent(type,{bubbles:true,pointerId:id,pointerType:kind,button:0,buttons:type==='pointerup'?0:1,clientX:r.left+r.width*x,clientY:r.top+r.height*y,pressure}));
    send('pointerdown',1,'pen',.2,.5,.1);
    send('pointerdown',2,'touch',.5,.6);
    send('pointermove',2,'touch',.8,.6);
    send('pointercancel',2,'touch',.8,.6);
    send('pointermove',1,'pen',.4,.5,.9);
    send('pointerup',1,'pen',.4,.5,.9);
    send('pointerdown',3,'pen',.2,.6,.2);
    send('pointermove',3,'pen',.5,.6,.7);
    send('pointercancel',3,'pen',.5,.6,.7);
  });
  assert.equal((await state()).strokes.length,1);
  assert((await state()).strokes[0].points.some(p=>p[2]===.1));
  assert((await state()).strokes[0].points.some(p=>p[2]===.9));
  assert.equal((await state()).page,1);
  for(const model of ['smart-pen','smart-pen-2','focus-pen','focus-pen-pro','redmi-smart-pen']){
    await c.page.evaluate(model=>{
      const root=document.getElementById('o3-home-concept');
      root.dispatchEvent(new CustomEvent('annota:stylus-capabilities',{detail:{model,barrelButton:true,doubleTap:model==='focus-pen-pro',haptics:model==='focus-pen-pro'}}));
      root.dispatchEvent(new CustomEvent('annota:stylus-action',{detail:{action:'barrel-down'}}));
    },model);
    assert.equal((await state()).tool,'eraser');
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').dispatchEvent(new CustomEvent('annota:stylus-action',{detail:{action:'barrel-up'}})));
    assert.equal((await state()).tool,'pen');
  }
  await c.page.evaluate(()=>{const r=document.getElementById('o3-home-concept');window.__haptics=0;r.addEventListener('annota:stylus-haptic',()=>window.__haptics++);r.dispatchEvent(new CustomEvent('annota:stylus-capabilities',{detail:{model:'focus-pen-pro',doubleTap:true,haptics:true}}));r.dispatchEvent(new CustomEvent('annota:stylus-action',{detail:{action:'double-tap'}}));});
  assert.equal((await state()).tool,'eraser');
  assert.equal(await c.page.evaluate(()=>window.__haptics),1);
  // Two-finger undo / three-finger redo, independently of ink.
  await c.page.waitForTimeout(510);
  async function fingers(count){
    await c.ui.locator('canvas').first().evaluate((canvas,count)=>{
      const r=canvas.getBoundingClientRect();
      for(const type of ['pointerdown','pointerup'])for(let i=0;i<count;i++)
        canvas.dispatchEvent(new PointerEvent(type,{bubbles:true,pointerId:20+i,pointerType:'touch',button:0,buttons:type==='pointerup'?0:1,clientX:r.left+r.width*.4+i*15,clientY:r.top+r.height*.6}));
    },count);
  }
  await fingers(2);assert.equal((await state()).strokes.length,0);
  await fingers(3);assert.equal((await state()).strokes.length,1);
  assert(Buffer.byteLength(JSON.stringify(await c.page.evaluate(()=>window.__savedState)))<16000);
  assert.deepEqual(c.errors,[]);
  report.push({stylus:'synthetic pressure, palm cancellation isolation, canceled stroke discarded, five model bridge contracts, gated double tap/haptics, two/three finger undo/redo passed; physical hardware not tested'});
  await c.page.close();
  const dense=await setup('portrait','cobalt-sage',68,'zh',{
    open:true,page:1,order:[1,2,3,4,5,6,7,8,9,10,11],
    strokes:Array.from({length:16},(_,n)=>({page:1,tool:'pen',color:'#4460CE',width:4,points:Array.from({length:48},(_,i)=>[100+i*1.23456789,300+n*2.987654321+i,0.654321])}))
  });
  await dense.ui.locator('[data-tool="hand"]').click();
  assert(Buffer.byteLength(JSON.stringify(await dense.page.evaluate(()=>window.__savedState)))<16000);
  await dense.ui.locator('.ar-header [data-reader-action="back"]').click();
  await dense.app.locator('[data-action="search"]').click();
  await dense.app.locator('.o3-search-field input').fill('atention');
  assert.equal(await dense.app.locator('[data-doc="p1"]').count(),1);
  await dense.app.locator('.o3-search-field input').fill('使用 指南');
  assert.equal(await dense.app.locator('[data-doc="guide"]').count(),1);
  await dense.page.close();
};
