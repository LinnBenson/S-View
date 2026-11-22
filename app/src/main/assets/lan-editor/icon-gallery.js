'use strict';
// 按需加载内置完整图标库，不增加项目编辑页的启动开销。
let iconLibraryPromise, iconCatalog=[], iconQuery='';
function loadIconLibrary(){
 if(!iconLibraryPromise)iconLibraryPromise=new Promise((resolve,reject)=>{
  const script=document.createElement('script');script.src='/lucide.js';
  script.onload=()=>{
   const names=new Set();
   iconCatalog=Object.entries(lucide.icons).map(([key,node])=>({name:key.replace(/([A-Za-z])(\d)/g,'$1-$2').replace(/([a-z0-9])([A-Z])/g,'$1-$2').replace(/([A-Z])([A-Z][a-z])/g,'$1-$2').toLowerCase(),node})).filter(entry=>{if(names.has(entry.name))return false;names.add(entry.name);return true}).sort((a,b)=>a.name.localeCompare(b.name));resolve();
  };
  script.onerror=()=>{script.remove();iconLibraryPromise=null;reject(Error('图标加载失败，请重试'))};document.head.append(script);
 });
 return iconLibraryPromise;
}
async function openIconGallery(){
 rememberWorkspace();await save();view='icons';render();
 try{await loadIconLibrary();if(view==='icons')renderIconGallery()}
 catch(error){if(view==='icons')$('#iconGrid').innerHTML=`<div class="empty">${esc(error.message)}<button class="btn" data-action="icon-gallery">重试</button></div>`}
}
function renderIconGallery(){
 const page=$('#iconPage');if(!page)return;
 page.innerHTML=`<div class="dashboard-head"><div><div class="eyebrow">LUCIDE</div><h1>图标库 <span class="heading-count" id="iconCount"></span></h1></div><label class="searchbox">${icon('search')}<input id="iconSearch" aria-label="搜索图标" placeholder="搜索图标名称" value="${esc(iconQuery)}"></label></div><div class="icon-grid" id="iconGrid"></div>`;
 renderIconGrid();
}
function renderIconGrid(){
 const grid=$('#iconGrid');if(!grid)return;
 if(!iconCatalog.length){grid.innerHTML='<div class="empty">正在加载…</div>';return}
 const query=iconQuery.trim().toLowerCase(),entries=iconCatalog.filter(item=>item.name.includes(query));
 $('#iconCount').textContent=query?`${entries.length} / ${iconCatalog.length}`:iconCatalog.length;
 // 连续展示全部结果，离屏卡片由浏览器跳过绘制，不分页。
 const fragment=document.createDocumentFragment();
 for(const entry of entries){
  const button=document.createElement('button');button.className='icon-tile';button.type='button';button.dataset.action='copy-icon';button.dataset.name=entry.name;button.title='复制 '+entry.name;button.setAttribute('aria-label','复制 '+entry.name);
  button.append(lucide.createElement(entry.node,{'aria-hidden':'true'}));const label=document.createElement('span');label.textContent=entry.name;button.append(label);fragment.append(button);
 }
 grid.replaceChildren(fragment);if(!entries.length)grid.innerHTML='<div class="empty">没有匹配的图标</div>';
}
async function copyIconName(name){
 // 局域网 HTTP 页面也支持复制，不依赖安全上下文的剪贴板 API。
 try{if(navigator.clipboard&&window.isSecureContext){await navigator.clipboard.writeText(name);toast('已复制 '+name);return}}catch{}
 const previous=document.activeElement,input=document.createElement('textarea');input.value=name;input.style.cssText='position:fixed;left:-9999px;top:0';document.body.append(input);input.select();
 let copied=false;try{copied=document.execCommand('copy')}finally{input.remove();previous?.focus()}
 if(!copied)throw Error('复制失败，请重试');toast('已复制 '+name);
}
Object.assign(actions,{'icon-gallery':openIconGallery,'copy-icon':el=>copyIconName(el.dataset.name)});
document.addEventListener('input',event=>{if(event.target.id==='iconSearch'){iconQuery=event.target.value;renderIconGrid()}});
