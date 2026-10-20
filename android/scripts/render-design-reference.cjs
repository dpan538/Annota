const fs=require('node:fs/promises');
const path=require('node:path');
const {chromium,packages,executablePath}=require('../../design/browser-runtime.cjs');
(async()=>{
 const native=path.resolve(__dirname,'..'),design=path.resolve(native,'../design');
 const fragment=await fs.readFile(path.join(design,'home-ui.fragment.html'),'utf8');
 const manifest=JSON.parse(await fs.readFile(path.join(design,'assets/misans/sources.json'),'utf8'));
 const fonts=(await Promise.all(manifest.chunks.map(async f=>'@font-face{font-family:"MiSans VF";font-style:normal;font-weight:150 700;src:url(data:font/woff2;base64,'+(await fs.readFile(path.join(design,'assets/misans',f.file))).toString('base64')+') format("woff2");unicode-range:'+f.range+';}'))).join('');
 const lucide=await fs.readFile(path.join(packages,'lucide/dist/umd/lucide.min.js'),'utf8');
 const browser=await chromium.launch({executablePath});
 const records=[];
 for(const palette of ['pine','cobalt-sage'])for(const orientation of ['landscape','portrait']) {
  const width=orientation==='landscape'?1800:1200,height=orientation==='landscape'?1200:1800;
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
  await page.route('**/*',r=>r.abort());
  const state={privateContent:{version:'o3-home-v1',librarySeedVersion:2,palette,docs:[{id:'guide',name:'Annota 使用指南',kind:'note',cover:'guide',date:'2026-10-20',sourceId:'guide'}],folders:[],buttonSize:68,screen:'library'}};
  await page.setContent('<!doctype html><meta charset="utf-8"><style>html,body{margin:0;padding:0}#o3-home-concept{width:'+width+'px}#o3-home-concept .o3-frame-portrait{max-width:none!important}'+fonts+'</style><script>'+lucide+'</script><script>window.openai={widgetState:'+JSON.stringify(state)+',setWidgetState:async()=>{}};</script>'+fragment);
  await page.evaluate(orientation=>document.querySelectorAll('[data-variant]').forEach(s=>s.hidden=s.querySelector('[data-view]').dataset.view!==orientation),orientation);
  await page.evaluate(async()=>{await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))});
  const app=page.locator('[data-view="'+orientation+'"]');
  const geometry=await app.evaluate(root=>{const r=root.getBoundingClientRect(),s=r.width/root.offsetWidth;const keys={sidebar:'.o3-side',brand:'.o3-brand strong',search:'[data-action="search"]',home:'[data-action="home"]',favorites:'[data-action="favorites"]',title:'.o3-heading h1',createNote:'[data-action="new-note"]',createImport:'[data-action="import"]',createFolder:'[data-action="new-folder"]',notesTitle:'.o3-note-title',thumbnail:'.o3-thumb',noteName:'.o3-doc-name',settings:'[data-action="settings"]',feedback:'[data-action="feedback"]'};return Object.fromEntries(Object.entries(keys).map(([k,v])=>{const e=root.querySelector(v).getBoundingClientRect();return[k,{x:(e.x-r.x)/s,y:(e.y-r.y)/s,width:e.width/s,height:e.height/s}]}))});
  await app.screenshot({path:path.join(native,'verification','reference-'+palette+'-'+orientation+'.png')});
  records.push({palette,orientation,geometry});await page.close();
 }
 await browser.close();await fs.writeFile(path.join(native,'verification','reference-layout.json'),JSON.stringify(records,null,2));console.log('Rendered approved-source references with original MiSans and same one-guide library');
})().catch(e=>{console.error(e);process.exitCode=1});
