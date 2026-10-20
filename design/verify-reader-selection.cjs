const assert = require('node:assert/strict');

module.exports = async ({setup, check, shot, settle, report}) => {
  for (const orientation of ['landscape', 'portrait']) {
    const c = await setup(orientation, 'cobalt-sage');
    const header = action => c.ui.locator(`.ar-header [data-reader-action="${action}"]`).click();
    const selectTitle = async () => {
      const box = await c.ui.locator('.ar-text-line').first().boundingBox();
      await c.page.mouse.dblclick(box.x + box.width * .18, box.y + box.height * .5);
      await settle(c.page);
      assert.equal(await c.page.evaluate(()=>window.getSelection().toString().trim()), 'Attention');
      assert(await c.ui.locator('.ar-selection-menu').isVisible());
    };
    assert.equal(await c.ui.locator('.ar-text-layer').first().getAttribute('data-source'),'1');
    await selectTitle();
    await check(c);
    await shot(c, `annota-cobalt-sage-${orientation}-text-selection`);
    await c.page.evaluate(()=>{
      const original=document.execCommand.bind(document);
      document.execCommand=(command,...args)=>{
        if(command==='copy')window.__copyText=document.activeElement.value;
        const success=original(command,...args);
        if(command==='copy')window.__copySuccess=success;
        return success;
      };
    });
    await c.ui.locator('[data-text-action="copy"]').click();
    assert.equal(await c.page.evaluate(()=>window.__copyText),'Attention');
    assert.equal(await c.page.evaluate(()=>window.__copySuccess),true);
    assert.match(await c.ui.locator('.ar-reader-toast').innerText(),/已复制/);
    await selectTitle();
    await c.ui.locator('[data-text-action="translate"]').click();
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/注意力/);
    assert.equal(await c.ui.locator('.ar-dictionary h2').innerText(),'翻译');
    for (const mode of ['keyboard','handwriting']) {
      const button=c.ui.locator(`[data-dict-input="${mode}"]`);
      assert.equal(await button.innerText(),'');
      assert((await button.getAttribute('aria-label')).length>0);
      assert.equal(await button.locator('svg').count(),1);
    }
    await c.ui.locator('.ar-reader-toast').evaluate(e=>e.hidden=true);
    await shot(c, `annota-cobalt-sage-${orientation}-selection-translation`);
    await c.ui.locator('.ar-dictionary [data-reader-action="close-popover"]').click();
    // The native host receives exactly the selected text and must acknowledge success.
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').addEventListener('annota:copy-text',e=>{
      window.__nativeCopy=e.detail;e.preventDefault();
    },{once:true}));
    await selectTitle();
    await c.ui.locator('[data-text-action="copy"]').click();
    assert.equal(await c.page.evaluate(()=>window.__nativeCopy.text),'Attention');
    await c.page.evaluate(()=>document.getElementById('o3-home-concept').dispatchEvent(new CustomEvent('annota:copy-result',{detail:{requestId:window.__nativeCopy.requestId,success:false}})));
    assert.match(await c.ui.locator('.ar-reader-toast').innerText(),/复制未完成/);
    // A complete sentence is not silently replaced by a single-word definition.
    await c.ui.locator('.ar-text-layer').first().evaluate(layer=>{
      const line=[...layer.children].find(e=>e.textContent.includes('dominant sequence'));
      const range=document.createRange();range.selectNodeContents(line);
      const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
      window.__sentence=selection.toString().trim();
    });
    await settle(c.page);
    await c.ui.locator('[data-text-action="translate"]').click();
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/暂不支持整句翻译/);
    assert.equal(await c.ui.locator('.ar-dict-query').inputValue(),await c.page.evaluate(()=>window.__sentence));
    await c.ui.locator('.ar-dict-query').press('Enter');
    assert.equal(await c.ui.locator('.ar-dict-query').inputValue(),await c.page.evaluate(()=>window.__sentence));
    assert.match(await c.ui.locator('.ar-dict-result').innerText(),/暂不支持整句翻译/);
    await c.ui.locator('.ar-dictionary [data-reader-action="close-popover"]').click();
    // Read mode retains text selection; handwriting mode does not select text.
    await header('focus');
    await selectTitle();
    await c.page.keyboard.press('Escape');
    assert(!(await c.ui.locator('.ar-selection-menu').isVisible()));
    assert(await c.ui.evaluate(e=>e.classList.contains('ar-focused')));
    await header('focus');
    await c.ui.locator('[data-tool="pen"]').click();
    assert.equal(await c.ui.locator('.ar-text-layer').first().getAttribute('data-enabled'),'false');
    await c.ui.locator('.ar-pen-options [data-reader-action="close-popover"]').click();
    await c.ui.locator('[data-tool="hand"]').click();
    await header('columns');await c.ui.locator('[data-columns="2"]').click();
    await settle(c.page);
    // Both pages carry their own source text. Selection in the right page stays there.
    assert.equal(await c.ui.locator('.ar-text-layer').nth(1).getAttribute('data-source'),'2');
    await c.ui.locator('.ar-text-layer').nth(1).evaluate(layer=>{
      const range=document.createRange();range.selectNodeContents(layer.children[0]);
      const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
    });
    await settle(c.page);
    assert(await c.ui.locator('.ar-selection-menu').isVisible());
    await check(c);
    await header('pages');
    await c.ui.locator('.ar-page-grid [data-page="11"]').click();
    assert(!(await c.ui.locator('.ar-selection-menu').isVisible()));
    assert.equal(await c.ui.locator('.ar-text-layer').first().getAttribute('data-source'),'11');
    assert((await c.ui.locator('.ar-text-layer').first().innerText()).includes('Vinyals'));
    assert.deepEqual(c.errors,[]);
    report.push({selection:orientation,passed:'real PDF text extraction, mouse word selection, browser copy fallback, native copy acknowledgement/error, offline word lookup, explicit sentence limit, icon-only IME controls, read/write separation, right page and final page text'});
    await c.page.close();
  }
};
