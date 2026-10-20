const assert=require('node:assert/strict');

module.exports=async({setup,settle,report})=>{
  const path=require('node:path');
  for(const orientation of ['landscape','portrait']){
    const c=await setup(orientation,'cobalt-sage');
    const root=c.page.locator('#o3-home-concept');
    const state=()=>c.page.evaluate(()=>window.__savedState.privateContent);
    const back=()=>c.ui.locator('.ar-header [data-reader-action="back"]').click();
    const snap=async name=>{await c.page.evaluate(()=>document.fonts.ready);await c.page.screenshot({path:path.join(__dirname,'screenshots/library-v1',name+'-'+orientation+'.png'),clip:{x:0,y:0,width:c.width,height:c.height}});};
    const menu=async id=>{
      const card=c.app.locator(`.o3-doc[data-doc="${id}"]`);await card.scrollIntoViewIfNeeded();const b=await card.boundingBox();
      await c.page.mouse.move(b.x+b.width*.5,b.y+b.height*.4);await c.page.mouse.down();await c.page.waitForTimeout(570);await c.page.mouse.up();
      await c.app.locator('.al-layer').waitFor({state:'visible'});
      assert.equal(await c.ui.isVisible(),false);
    };
    await back();await menu('p1');
    assert.deepEqual(await c.app.locator('.al-panel [data-library-op]').evaluateAll(bs=>bs.map(b=>b.dataset.libraryOp)),['favorite','rename','duplicate','move','export','trash']);
    await snap('annota-file-menu');
    await c.app.locator('[data-library-op="favorite"]').click();assert((await state()).docs.find(d=>d.id==='p1').favorite);
    await menu('p1');await c.app.locator('[data-library-op="rename"]').click();
    await c.app.locator('[name="document-name"]').fill('我的论文 <复读>');await c.app.locator('[data-library-op="save-name"]').click();
    assert.equal(await c.app.locator('[data-doc="p1"] .o3-doc-name').innerText(),'我的论文 <复读>');
    await c.app.locator('[data-doc="p1"]').click();assert.equal(await c.ui.locator('.ar-title').innerText(),'我的论文 <复读>');await back();
    await menu('p1');await c.app.locator('[data-library-op="duplicate"]').click();
    const duplicate=(await state()).docs.find(d=>d.id.startsWith('d-'));assert.equal(duplicate.sourceId,'p1');
    await c.app.locator(`[data-doc="${duplicate.id}"]`).click();assert.equal((await state()).reader.order.length,11);await back();
    await c.app.locator('[data-action="new-folder"]').click();await c.app.locator('[name="name"]').fill('阅读');await c.app.locator('[data-action="create"]').click();
    const folder=(await state()).folders[0];await menu(duplicate.id);await c.app.locator('[data-library-op="move"]').click();await c.app.locator(`[data-move-folder="${folder.id}"]`).click();
    assert.equal((await state()).docs.find(d=>d.id===duplicate.id).folder,folder.id);
    await c.page.evaluate(()=>{document.getElementById('o3-home-concept').addEventListener('annota:export-document',e=>{window.__nativeExport=e.detail;e.preventDefault();},{once:true});});
    await menu('p1');await c.app.locator('[data-library-op="export"]').click();
    assert.deepEqual(await c.page.evaluate(()=>window.__nativeExport),{documentId:'p1',name:'我的论文 <复读>',format:'pdf',includeAnnotations:true,userInitiated:true});
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').addEventListener('annota:export-ready',e=>{window.__exportReady=e.detail;const frame=document.querySelector('iframe[data-annota-export]');frame.contentWindow.print=()=>{window.__printed=true;};},{once:true}));
    await menu('p1');await c.app.locator('[data-library-op="export"]').click();await c.page.waitForFunction(()=>window.__printed===true);
    assert.equal(await c.page.evaluate(()=>window.__exportReady.pages),11);
    assert.equal(await c.page.locator('iframe[data-annota-export]').evaluate(f=>f.contentDocument.querySelectorAll('.annota-export-page').length),11);
    await c.page.locator('iframe[data-annota-export]').evaluate(f=>f.remove());
    for(const pattern of ['blank','dots','grid','lined']){
      for(const color of ['white','gray','black']){
        await c.app.locator('[data-action="new-note"]').click();
        await c.app.locator(`[data-paper-style="${pattern}"]`).click();await c.app.locator(`[data-paper-color="${color}"]`).click();
        if(pattern==='grid'&&color==='black')await snap('annota-quick-note');
        await c.app.locator('[data-library-op="create-note"]').click();await settle(c.page);
        const saved=await state(),note=saved.docs[0];assert.equal(note.paperStyle,pattern);assert.equal(note.paperColor,color);
        assert.equal(saved.reader.documentId,note.id);assert.equal(saved.reader.order[0],{blank:0,dots:-2,grid:-3,lined:-1}[pattern]);
        const paper=c.ui.locator('.ar-paper').first();assert.equal(await paper.getAttribute('data-paper'),pattern);
        assert.equal(await paper.evaluate(p=>getComputedStyle(p).backgroundColor),{white:'rgb(255, 255, 255)',gray:'rgb(229, 229, 227)',black:'rgb(35, 36, 38)'}[color]);
        if(pattern==='grid'&&color==='black'){
          await c.ui.locator('[data-tool="pen"]').click();await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
          const b=await c.ui.locator('canvas').first().boundingBox();await c.page.mouse.move(b.x+b.width*.25,b.y+b.height*.35);await c.page.mouse.down();await c.page.mouse.move(b.x+b.width*.65,b.y+b.height*.4,{steps:8});await c.page.mouse.up();
          assert.equal((await state()).reader.strokes.length,1);assert.equal((await state()).reader.strokes[0].color.toUpperCase(),'#F4F4F1');
          await snap('annota-black-grid-note');
          await back();await menu(note.id);await c.app.locator('[data-library-op="duplicate"]').click();
          const copy=(await state()).docs[0];await c.app.locator(`[data-doc="${copy.id}"]`).click();assert.equal((await state()).reader.strokes.length,1);
        }
        await back();
      }
    }
    await menu(duplicate.id);await c.app.locator('[data-library-op="trash"]').click();assert((await state()).docs.find(d=>d.id===duplicate.id).trash);
    await c.app.locator('[data-action="trash"]').click();await menu(duplicate.id);await c.app.locator('[data-library-op="restore"]').click();assert.equal((await state()).docs.find(d=>d.id===duplicate.id).trash,false);
    const saved=await state();saved.collection='all';
    await c.page.evaluate(s=>window.dispatchEvent(new CustomEvent('openai:set_globals',{detail:{globals:{widgetState:{privateContent:s}}}})),saved);
    assert.equal(await c.app.locator('[data-doc="p1"] .o3-doc-name').innerText(),'我的论文 <复读>');
    assert.equal((await state()).docs.length,16);
    assert(Buffer.byteLength(JSON.stringify(saved))<16000);
    assert.deepEqual(c.errors,[]);
    report.push({orientation,passed:'long press and dismissal; favorite, rename, duplicate and move; native export event and 11-page system-print fallback; all 12 paper/color combinations open in reader; visible ink on black paper; independent copied ink; trash/restore; restored metadata and compact state'});
    await c.page.close();
  }
};
