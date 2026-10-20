const fs=require('node:fs/promises');
const path=require('node:path');
const {chromium,executablePath}=require('../design/browser-runtime.cjs');
(async()=>{
  const dir=process.argv[2]?path.resolve(process.argv[2]):path.resolve(__dirname,'../branding/logo-concepts');
  const browser=await chromium.launch({executablePath,headless:true});
  try{
    const page=await browser.newPage({viewport:{width:1024,height:1024}});
    await page.route('**/*',r=>r.abort());
    for(const name of (await fs.readdir(dir)).filter(p=>p.endsWith('.svg'))){
      const svg=await fs.readFile(path.join(dir,name),'utf8');
      await page.setContent('<style>html,body{margin:0;background:#fff}svg{display:block;width:1024px;height:1024px}</style>'+svg);
      await page.screenshot({path:path.join(dir,name.replace('.svg','.png'))});
    }
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
