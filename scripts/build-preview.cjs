const fs=require('node:fs/promises');
const path=require('node:path');
const {packages}=require('../design/browser-runtime.cjs');
(async()=>{
  const root=path.resolve(__dirname,'..');
  const fragment=await fs.readFile(path.join(root,'design/home-ui.fragment.html'),'utf8');
  if(!fragment.includes('ANNOTA_READER_START'))throw new Error('Prepare local PDF assets and run design/build-reader.py first.');
  const lucide=await fs.readFile(path.join(packages,'lucide/dist/umd/lucide.min.js'),'utf8');
  const before='<script>let saved=null;try{saved=JSON.parse(localStorage.getItem("annota-design-preview")||"null")}catch{}window.openai={widgetState:saved||{},setWidgetState:async value=>{window.openai.widgetState=value;try{localStorage.setItem("annota-design-preview",JSON.stringify(value))}catch{}}};</script>';
  const after='<script>const variants=[...document.querySelectorAll("[data-variant]")];const orient=()=>variants.forEach(v=>v.hidden=v.querySelector("[data-view]")?.dataset.view!==(innerWidth>=760?"landscape":"portrait"));orient();addEventListener("resize",orient);</script>';
  const html='<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; style-src \'unsafe-inline\'; img-src data: blob:; font-src data:; connect-src \'none\'; frame-src about:;"><title>Annota · Design preview</title><style>html,body{margin:0;background:#fff}#o3-home-concept{max-width:1280px;margin:auto}</style></head><body><script>'+lucide+'</script>'+before+fragment+after+'</body></html>';
  await fs.mkdir(path.join(root,'preview'),{recursive:true});
  await fs.writeFile(path.join(root,'preview/index.html'),html);
  console.log('Built preview/index.html. The preview is local, with no network access.');
})().catch(e=>{console.error(e);process.exitCode=1;});
