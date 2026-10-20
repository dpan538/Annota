// Export the authored guide using the same offline rendering as the app preview.
const fs=require('node:fs/promises');
const path=require('node:path');
const assert=require('node:assert/strict');
const {chromium,packages,executablePath}=require('./browser-runtime.cjs');

(async()=>{
  const output=path.resolve(__dirname,'../output/pdf');
  const images=path.join(output,'annota-guide-pages');
  await fs.mkdir(images,{recursive:true});
  const browser=await chromium.launch({executablePath});
  try{
    const page=await browser.newPage({viewport:{width:1024,height:768}});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.route('**/*',r=>r.abort());
    const fragment=await fs.readFile(path.join(__dirname,'home-ui.fragment.html'),'utf8');
    const lucide=await fs.readFile(path.join(packages,'lucide/dist/umd/lucide.min.js'),'utf8');
    await page.setContent('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"></head><body><script>'+lucide+'</script><script>window.openai={widgetState:{},setWidgetState:async()=>{}};</script>'+fragment+'</body></html>');
    await page.waitForFunction(()=>document.getElementById('o3-home-concept').dataset.guideReady==='true');
    await page.evaluate(()=>{
      const root=document.getElementById('o3-home-concept');
      root.addEventListener('annota:export-ready',e=>{
        window.__guideExport=e.detail;
        document.querySelector('iframe[data-annota-export]').contentWindow.print=()=>{};
      },{once:true});
      root.dispatchEvent(new CustomEvent('annota:export-preview',{detail:{documentId:'guide'}}));
    });
    await page.waitForFunction(()=>window.__guideExport?.pages===5);
    const html=await page.locator('iframe[data-annota-export]').evaluate(f=>f.contentDocument.documentElement.outerHTML);
    const printPage=await browser.newPage({viewport:{width:900,height:600},deviceScaleFactor:3});
    printPage.on('pageerror',e=>errors.push(e.message));
    await printPage.route('**/*',r=>r.abort());
    await printPage.setContent('<!doctype html>'+html);
    await printPage.evaluate(async()=>{void document.body.offsetHeight;await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
    assert.equal(await printPage.locator('.annota-export-page').count(),5);
    assert.equal(await printPage.locator('i[data-lucide]').count(),0);
    assert.equal(await printPage.evaluate(()=>document.fonts.check('20px "Annota Guide Hand"','有话就告诉我们')),true);
    const layout=await printPage.locator('.annota-export-page > .ar-guide-page').evaluateAll(pages=>pages.map(p=>{
      const r=p.getBoundingClientRect(),footer=p.querySelector(':scope > footer').getBoundingClientRect();
      const outside=[...p.children].filter(e=>e.tagName!=='FOOTER').filter(e=>{const b=e.getBoundingClientRect();return b.right>r.right+.5||b.bottom>footer.top-2;}).map(e=>e.tagName);
      return {size:[r.width,r.height],outside,footerInside:footer.bottom<=r.bottom};
    }));
    assert.deepEqual(layout,Array(5).fill({size:[900,600],outside:[],footerInside:true}));
    await printPage.pdf({path:path.join(output,'Annota-使用指南-横版五页.pdf'),printBackground:true,preferCSSPageSize:true,displayHeaderFooter:false,tagged:true});
    for(let i=0;i<5;i++)await printPage.locator('.annota-export-page').nth(i).screenshot({path:path.join(images,'annota-guide-'+String(i+1).padStart(2,'0')+'.png'),animations:'disabled'});
    assert.deepEqual(errors,[]);
    console.log(JSON.stringify({pdf:path.join(output,'Annota-使用指南-横版五页.pdf'),pages:5,imageSize:[2700,1800],images},null,2));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
