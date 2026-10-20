const path=require('node:path');
const {createRequire}=require('node:module');
const resolve=process.env.ANNOTA_NODE_MODULES?createRequire(path.join(path.resolve(process.env.ANNOTA_NODE_MODULES),'annota-runtime.cjs')):require;
module.exports={
  chromium:resolve('playwright').chromium,
  packages:path.dirname(path.dirname(resolve.resolve('lucide/package.json'))),
  executablePath:process.env.ANNOTA_CHROMIUM_EXECUTABLE||undefined
};
