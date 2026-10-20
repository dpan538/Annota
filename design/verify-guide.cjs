const assert=require('node:assert/strict');

module.exports=async({setup,check,shot,settle,report})=>{
  for(const orientation of ['landscape','portrait']){
    const c=await setup(orientation,'cobalt-sage');
    const header=action=>c.ui.locator(`.ar-header [data-reader-action="${action}"]`).click();
    const state=()=>c.page.evaluate(()=>window.__savedState.privateContent);
    await header('back');
    assert.deepEqual(await c.app.locator('.o3-doc-grid [data-doc]').evaluateAll(es=>es.map(e=>e.dataset.doc)),['p1','guide']);
    assert(!(await c.app.locator('.o3-folder-section').isVisible()));
    await shot(c,`annota-first-library-${orientation}`);
    await c.app.locator('[data-doc="guide"]').click();
    assert.equal((await state()).reader.documentId,'guide');
    assert.equal((await state()).reader.order.length,5);
    assert.equal(await c.ui.locator('.ar-title').innerText(),'Annota 使用指南');
    for(let page=1;page<=5;page++){
      await header('pages');await c.ui.locator(`.ar-page-grid [data-page="${page}"]`).click();await header('pages');await settle(c.page);
      const article=c.ui.locator('.ar-guide-page').first();
      assert.equal(await article.getAttribute('data-guide-page'),String(page));
      const layout=await article.evaluate(e=>{
        const b=e.getBoundingClientRect(),footer=e.querySelector(':scope > footer').getBoundingClientRect();
        const bad=[...e.children].filter(x=>x.tagName!=='FOOTER').filter(x=>x.getBoundingClientRect().bottom>footer.top-10).map(x=>x.tagName+':'+x.textContent.slice(0,36)+' gap='+Math.round(footer.top-x.getBoundingClientRect().bottom));
        const outside=[...e.querySelectorAll('h1,h2,h3,p,button')].filter(x=>{const r=x.getBoundingClientRect();return r.left<b.left||r.right>b.right+1;}).map(x=>x.textContent.slice(0,36));
        return {bad,outside,unresolvedIcons:e.querySelectorAll('i[data-lucide]').length,brokenImages:[...e.querySelectorAll('[data-guide-pdf]')].filter(i=>!i.complete||!i.naturalWidth).length,ratio:Math.round(b.width/b.height*100)/100};
      });
      await shot(c,`annota-guide-${orientation}-${String(page).padStart(2,'0')}`);
      assert.deepEqual(layout,{bad:[],outside:[],unresolvedIcons:0,brokenImages:0,ratio:1.5},`${orientation} page ${page}: ${JSON.stringify(layout)}`);
      if(page===3){
        const overflow=await article.locator('.g-pen-sample').evaluateAll(cards=>cards.flatMap(card=>{const b=card.getBoundingClientRect();return [...card.querySelectorAll('h3,p,svg.g-stroke')].filter(e=>{const r=e.getBoundingClientRect();return r.left<b.left-2||r.right>b.right+2||r.top<b.top-2||r.bottom>b.bottom+2;}).map(e=>e.textContent||e.getAttribute('aria-label'));}));
        assert.deepEqual(overflow,[],'pen names, descriptions and samples stay inside all four cards');
      }
      await check(c);
    }
    const config=require('./project.config.json');
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').addEventListener('annota:open-external',e=>{window.__projectLink=e.detail;e.preventDefault();}));
    await c.ui.locator('[data-guide-action="github"]').click();
    if(config.repository)assert.equal(await c.page.evaluate(()=>window.__projectLink.url),'https://github.com/'+config.repository);
    else assert.match(await c.app.locator('.o3-toast').innerText(),/即将公布/);
    await c.app.locator('.o3-toast').evaluate(e=>e.hidden=true);
    await c.ui.locator('[data-guide-action="updates"]').click();
    assert(!(await c.ui.isVisible()));
    assert(await c.app.locator('[data-settings-panel="about"]').isVisible());
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').addEventListener('annota:check-update',e=>{window.__update=e.detail;e.preventDefault();}));
    await c.app.locator('[data-action="check-update"]').click();
    assert.equal(await c.page.evaluate(()=>window.__update.provider),'github-releases');
    assert.equal(await c.page.evaluate(()=>window.__update.repository),config.repository);
    await c.app.locator('[data-action="home"]').click();await c.app.locator('[data-doc="guide"]').click();
    assert.equal((await state()).reader.page,5);
    if(orientation==='landscape'){
      // A guide export keeps all five horizontal pages and the embedded artwork/font.
      await c.page.evaluate(()=>{
        const root=document.getElementById('o3-home-concept');
        root.addEventListener('annota:export-ready',e=>{window.__guideExport=e.detail;document.querySelector('iframe[data-annota-export]').contentWindow.print=()=>{window.__guidePrinted=true;};},{once:true});
        root.dispatchEvent(new CustomEvent('annota:export-preview',{detail:{documentId:'guide'}}));
      });
      await c.page.waitForFunction(()=>window.__guidePrinted===true);
      assert.equal(await c.page.evaluate(()=>window.__guideExport.pages),5);
      const exported=await c.page.locator('iframe[data-annota-export]').evaluate(f=>{
        const d=f.contentDocument,pages=[...d.querySelectorAll('.annota-export-page')];
        return {pages:pages.length,unresolved:d.querySelectorAll('i[data-lucide]').length,broken:[...d.images].filter(i=>!i.complete||!i.naturalWidth).length,sizes:pages.map(p=>[p.firstElementChild.offsetWidth,p.firstElementChild.offsetHeight]),font:d.fonts.check('20px "Annota Guide Hand"')};
      });
      assert.deepEqual(exported,{pages:5,unresolved:0,broken:0,sizes:Array(5).fill([900,600]),font:true});
      await c.page.locator('iframe[data-annota-export]').evaluate(f=>f.remove());
    }
    await c.ui.locator('[data-guide-action="feedback"]').click();
    assert(await c.app.locator('.o3-dialog[data-modal="feedback"]').isVisible());
    await c.app.locator('[name="feedback"]').fill('试读指南后的建议');
    await c.app.locator('[name="feedback-contact"]').fill('reader@example.test');
    await c.page.evaluate(()=>{
      const root=document.getElementById('o3-home-concept');window.__feedbackExternal=false;
      root.addEventListener('annota:submit-feedback',e=>{window.__feedback=e.detail;},{once:true});
      root.addEventListener('annota:open-external',()=>window.__feedbackExternal=true,{once:true});
    });
    await c.app.locator('[data-action="save-feedback"]').click();
    assert.deepEqual(await c.page.evaluate(()=>window.__feedback),{provider:'in-app',endpoint:null,body:'试读指南后的建议',contact:'reader@example.test',userInitiated:true});
    assert.equal(await c.page.evaluate(()=>window.__feedbackExternal),false);
    assert.match(await c.app.locator('.o3-toast').innerText(),/草稿.*尚未发送/);
    assert.equal((await state()).feedback,'试读指南后的建议');
    await c.app.locator('.o3-toast').evaluate(e=>e.hidden=true);
    await c.app.locator('[data-doc="guide"]').click();
    await header('pages');await c.ui.locator('.ar-page-grid [data-page="4"]').click();await header('pages');
    await c.ui.locator('[data-guide-action="appearance"]').click();
    assert(await c.app.locator('[data-settings-panel="appearance"]').isVisible());
    await c.app.locator('[data-action="home"]').click();await c.app.locator('[data-doc="guide"]').click();
    await header('pages');await c.ui.locator('.ar-page-grid [data-page="3"]').click();await header('pages');
    for(const tool of ['pen','pencil','fountain','highlight']){
      await c.ui.locator(`[data-guide-tool="${tool}"]`).click();
      assert.equal((await state()).reader.tool,tool);
      assert(await c.ui.locator('.ar-pen-options').isVisible());
      await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
      await c.ui.locator('[data-tool="hand"]').click();
    }
    await c.ui.locator('[data-guide-tool="pen"]').click();await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
    const box=await c.ui.locator('[data-guide-practice]').first().boundingBox();
    await c.page.mouse.move(box.x+box.width*.2,box.y+box.height*.6);await c.page.mouse.down();await c.page.mouse.move(box.x+box.width*.7,box.y+box.height*.65,{steps:8});await c.page.mouse.up();
    assert.equal((await state()).reader.strokes.length,1);
    assert.deepEqual((await state()).reader.pageSize,[900,600]);
    await c.ui.locator('[data-tool="hand"]').click();
    await header('more');await c.ui.locator('[data-reader-action="favorite"]').click();await c.ui.locator('.ar-more [data-reader-action="close-popover"]').click();
    await header('back');await c.app.locator('[data-action="favorites"]').click();
    assert.deepEqual(await c.app.locator('.o3-doc-grid [data-doc]').evaluateAll(es=>es.map(e=>e.dataset.doc)),['guide']);
    await c.app.locator('[data-action="home"]').click();await c.app.locator('[data-doc="p1"]').click();
    assert.equal((await state()).reader.order.length,11);
    assert.equal((await state()).reader.strokes.length,0);
    await header('back');await c.app.locator('[data-doc="guide"]').click();
    assert.equal((await state()).reader.page,3);
    assert.equal((await state()).reader.strokes.length,1);
    assert.equal((await state()).reader.documents.p1.order.length,11);
    const saved=await state();
    await c.page.evaluate(saved=>window.dispatchEvent(new CustomEvent('openai:set_globals',{detail:{globals:{widgetState:{privateContent:saved}}}})),saved);
    await settle(c.page);
    assert.equal(await c.ui.locator('.ar-guide-page').first().getAttribute('data-guide-page'),'3');
    await header('more');
    assert.equal(await c.ui.locator('[data-reader-action="favorite"]').getAttribute('aria-pressed'),'true');
    await c.ui.locator('.ar-more [data-reader-action="close-popover"]').click();
    assert(Buffer.byteLength(JSON.stringify(saved))<16000);
    await header('back');
    assert.equal(await c.app.locator('.o3-doc-grid [data-doc]').count(),2);
    await c.app.locator('[data-doc="guide"]').click();await header('pages');await c.ui.locator('.ar-page-grid [data-page="1"]').click();await header('pages');
    const paper=await c.ui.locator('canvas').first().boundingBox();
    await c.page.mouse.move(paper.x+paper.width*750/900,paper.y+paper.height*12/600);await c.page.mouse.down();await c.page.mouse.move(paper.x+paper.width*150/900,paper.y+paper.height*12/600,{steps:10});await c.page.mouse.up();
    assert.equal((await state()).reader.page,2);
    await header('pages');await c.ui.locator('.ar-page-grid [data-page="1"]').click();await header('pages');
    await c.ui.locator('[data-guide-jump="2"]').first().click();assert.equal((await state()).reader.page,2);
    await header('pages');await c.ui.locator('.ar-page-grid [data-page="1"]').click();await header('pages');
    await c.ui.locator('[data-guide-action="sample"]:visible').click();
    assert.equal((await state()).reader.documentId,'p1');
    // Upgrades and later launches do not recreate a guide deleted by the user.
    const deleted=await state();deleted.docs=deleted.docs.filter(d=>d.id!=='guide');deleted.reader.open=false;
    await c.page.evaluate(saved=>window.dispatchEvent(new CustomEvent('openai:set_globals',{detail:{globals:{widgetState:{privateContent:saved}}}})),deleted);
    assert.equal(await c.app.locator('.o3-doc-grid [data-doc="guide"]').count(),0);
    await c.app.locator('[data-action="home"]').click();
    const afterDelete=await state();
    await c.page.evaluate(saved=>window.dispatchEvent(new CustomEvent('openai:set_globals',{detail:{globals:{widgetState:{privateContent:saved}}}})),afterDelete);
    assert.equal(await c.app.locator('.o3-doc-grid [data-doc]').count(),1);
    // The prototype migration removes known placeholders while preserving user content.
    const legacy=await state();delete legacy.librarySeedVersion;
    legacy.docs.push({id:'n1',name:'论文精读 · 手写摘记',kind:'note',cover:'note',favorite:false,trash:false,date:'2026-10-20T10:00'});
    legacy.docs.push({id:'user-note',name:'我的真实笔记',kind:'note',cover:'generic',favorite:false,trash:false,date:'2026-10-20T11:00'});
    await c.page.evaluate(saved=>window.dispatchEvent(new CustomEvent('openai:set_globals',{detail:{globals:{widgetState:{privateContent:saved}}}})),legacy);
    assert.equal(await c.app.locator('.o3-doc-grid [data-doc="n1"]').count(),0);
    assert.equal(await c.app.locator('.o3-doc-grid [data-doc="guide"]').count(),1);
    assert.equal(await c.app.locator('.o3-doc-grid [data-doc="user-note"]').count(),1);
    assert.deepEqual(c.errors,[]);
    report.push({orientation,passed:'two seeded files, five illustrated guide pages without overflow, embedded PDF illustrations, four working pen samples, guide links, GitHub placeholder, update event, in-app feedback contract and honest draft fallback, independent document ink/page state, favorites, state restore and no duplicate seeds'});
    await c.page.close();
  }
};
