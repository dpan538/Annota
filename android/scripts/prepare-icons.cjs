const fs=require('node:fs');const path=require('node:path');
const {packages}=require('../../design/browser-runtime.cjs');
const lucide=require(path.join(packages,'lucide'));
const names=['Search','House','Star','Settings2','MessageSquare','Funnel','Trash2','Languages','Plus','FileUp','FolderPlus','Folder','Palette','PenTool','Hand','Info','Type','Check','ChevronLeft','ChevronRight','LayoutList','Columns2','PenLine','Eraser','Ruler','Image','Shapes','FilePlus2','BookOpenText','BookA','Ellipsis','Keyboard','Undo2','Redo2','RefreshCw','Download','Archive','ExternalLink'];
const root=path.resolve(__dirname,'..');const data=Object.fromEntries(names.map(name=>{if(!lucide.icons[name])throw new Error(name);return[name,lucide.icons[name]]}));
fs.writeFileSync(path.join(root,'app/src/main/assets/lucide-icons.json'),JSON.stringify(data));
fs.copyFileSync(path.join(packages,'lucide/LICENSE'),path.join(root,'app/src/main/assets/LUCIDE-LICENSE.txt'));
console.log('Copied the design runtime Lucide glyphs and ISC license');
