'use strict';
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = name => ICONS[name] || ICONS['file-code'];
const uid = () => globalThis.crypto?.randomUUID?.() || 'p'+Date.now().toString(36)+Math.random().toString(36).slice(2);
const DEFAULT_CODE = "const app = document.querySelector( 'main#app' );\napp.classList.add( 'center' );\napp.style.width = '100vw';\napp.style.height = '100vh';\napp.innerHTML = `\n    <h5>Hello World!</h5>\n`;\n";
const textFile = text => ({kind:'text', text});
function project(name, category='', code=DEFAULT_CODE) {
 return {id:uid(),name,category,width:'360dp',height:'80%',location:'center',cornerRadius:20,backdropTransparency:40,margins:{top:'0dp',bottom:'0dp',left:'0dp',right:'0dp'},folders:[],files:{'main.js':textFile(code)}};
}
let state, baseline, revision, accessToken = '', view='editor', activeId, activeFile='main.js', openFiles=['main.js'], tabMemory=new Map(), positions=new Map(), currentFolder='', collapsed=new Set(), query='', category='', dirty=false, saveTimer, saveChain=Promise.resolve();
let undo=new Map(), redo=new Map(), toastTimer;
const projectNow=()=>state.projects.find(p=>p.id===activeId);
const filesNow=()=>view==='templates'?state.templates:projectNow()?.files || {};
const foldersNow=()=>view==='templates'?state.templateFolders:projectNow()?.folders || [];
const scopeKey=()=>view==='templates'?'templates':activeId;
const fileKey=()=>scopeKey()+'::'+activeFile;
const humanSize=n=>n<1024?n+' B':n<1048576?(n/1024).toFixed(1)+' KB':(n/1048576).toFixed(1)+' MB';
const fileBytes=f=>f.kind==='text'?new TextEncoder().encode(f.text):Uint8Array.from(atob(f.data.split(',')[1]),c=>c.charCodeAt(0));
const baseName=path=>path.split('/').pop();
const fileIcon=name=>/\.(png|jpe?g|gif|webp|svg|ico)$/i.test(name)?'file-image':/\.(js|mjs|json|html|css|xml)$/i.test(name)?'file-code':'file-text';
function toast(message){$('#toast').textContent=message;$('#toast').classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),2600)}
function setStatus(text,error=false){const el=$('#saveStatus');if(el){el.innerHTML=`<i class="status-dot" style="${error?'background:#d48176':''}"></i>${esc(text)}`}}
/** 会话令牌仅驻留当前页面，不把手机文件写入浏览器持久存储。 */
async function api(action, body={}, binary=false) {
 const response=await fetch('/api/'+action,{method:'POST',headers:{'Content-Type':'application/json','X-SView-Editor':'1','Authorization':'Bearer '+accessToken},body:JSON.stringify(body)});
 if(!response.ok){let message='连接失败';try{message=(await response.json()).error||message}catch{}throw Error(message)}
 return binary?response.blob():response.json();
}
const configKeys=['id','name','category','width','height','location','margins','cornerRadius','backdropTransparency','variables'];
const configOf=p=>Object.fromEntries(configKeys.map(k=>[k,p[k]]));
const equal=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
/** 仅发送变化的文件，图片等未编辑资源不随代码自动保存重复传输。 */
function changes(before,after) {
 const operations=[];
 if(!equal(before.categories,after.categories))operations.push({type:'categories',value:after.categories});
 for(const p of after.projects){const old=before.projects.find(x=>x.id===p.id);if(!old||!equal(configOf(old),configOf(p)))operations.push({type:'config',value:configOf(p)})}
 function files(id,oldFiles,newFiles,oldFolders,newFolders){
  for(const path of newFolders)if(!oldFolders.includes(path))operations.push({type:'mkdir',id,path});
  for(const [path,value] of Object.entries(newFiles))if(!equal(oldFiles[path],value))operations.push({type:'write',id,path,value});
  for(const path of Object.keys(oldFiles))if(!Object.hasOwn(newFiles,path))operations.push({type:'delete',id,path});
  for(const path of [...oldFolders].sort((a,b)=>b.length-a.length))if(!newFolders.includes(path))operations.push({type:'delete',id,path});
 }
 files(null,before.templates,after.templates,before.templateFolders,after.templateFolders);
 for(const p of after.projects){const old=before.projects.find(x=>x.id===p.id);files(p.id,old?.files||{},p.files,old?.folders||[],p.folders)}
 for(const p of before.projects)if(!after.projects.some(x=>x.id===p.id))operations.push({type:'deleteProject',id:p.id});
 return operations;
}
function markDirty(){dirty=true;setStatus('保存中…');clearTimeout(saveTimer);saveTimer=setTimeout(()=>save().catch(()=>{}),650)}
function save(){
 clearTimeout(saveTimer);
 saveChain=saveChain.catch(()=>{}).then(async()=>{
  if(!dirty)return;
  const snapshot=structuredClone(state),operations=changes(baseline,snapshot);
  if(operations.length){
   const result=await api('save',{revision,operations});revision=result.revision;
   // 重命名后的远程资源仍留在手机，下次操作从新路径读取。
   for(const op of operations.filter(op=>op.type==='write'&&op.value.kind==='remote')){
    const old=op.value.sourcePath;
    const target=op.id===null?snapshot.templates:snapshot.projects.find(p=>p.id===op.id)?.files;
    if(target?.[op.path])target[op.path].sourcePath=op.path;
    const current=op.id===null?state.templates:state.projects.find(p=>p.id===op.id)?.files;
    for(const value of Object.values(current||{}))if(value.kind==='remote'&&value.sourcePath===old)value.sourcePath=op.path;
   }
  }
  baseline=snapshot;dirty=!equal(state,snapshot);setStatus(dirty?'待保存':'已保存');
 }).catch(error=>{dirty=true;setStatus('未保存',true);toast(error.message);throw error});
 return saveChain;
}
async function startWorkspace(){
 state=await api('state');revision=state.revision;baseline=structuredClone(state);
 TEMPLATES=Object.fromEntries(state.protectedTemplates.map(path=>[path,true]));
 activeId=state.projects[0]?.id;view=activeId?'editor':'projects';dirty=false;render();
}
function login(){
 $('#app').innerHTML='<main class="login"><h1>S View</h1><p>局域网编辑器</p><form id="loginForm"><label class="field">访问密码<input id="password" type="password" autocomplete="current-password" required maxlength="128"></label><button class="btn primary" type="submit">连接</button><p id="loginError" role="alert"></p></form></main>';
 $('#loginForm').onsubmit=async event=>{event.preventDefault();const button=event.target.querySelector('button');button.disabled=true;try{accessToken=(await api('login',{password:$('#password').value})).token;await startWorkspace()}catch(error){$('#loginError').textContent=error.message;button.disabled=false}};
}
function rememberEditor(){const el=$('#code');if(el)positions.set(fileKey(),{start:el.selectionStart,end:el.selectionEnd,top:el.scrollTop,left:el.scrollLeft})}
function rememberWorkspace(){rememberEditor();if(view==='editor'||view==='templates')tabMemory.set(scopeKey(),{activeFile,openFiles:[...openFiles]})}
function switchProject(id){rememberWorkspace();activeId=id;view='editor';const mem=tabMemory.get(id);activeFile=mem?.activeFile||'main.js';openFiles=mem?.openFiles||['main.js'];currentFolder='';render()}
function selectFile(path){rememberEditor();currentFolder=path.includes('/')?path.slice(0,path.lastIndexOf('/')):'';activeFile=path;if(!openFiles.includes(path))openFiles.push(path);renderEditor();renderTree()}
function openTemplates(){rememberWorkspace();view='templates';const mem=tabMemory.get('templates');activeFile=mem?.activeFile||'index.html';openFiles=mem?.openFiles||['index.html'];currentFolder='';render()}
const ib=(action,name,title,extra='')=>`<button type="button" class="icon-btn" data-action="${action}" title="${title}" aria-label="${title}" ${extra}>${icon(name)}</button>`;
function render(){
 const isDashboard=view==='projects', isTemplates=view==='templates', isDatabase=view==='database', isIcons=view==='icons';
 $('#app').innerHTML=`<aside class="rail"><svg class="mark" viewBox="0 0 276 260" role="img" aria-label="S View"><path d="${MARK}"/></svg><button class="rail-button ${!isTemplates&&!isDatabase&&!isIcons?'active':''}" data-action="projects" title="项目" aria-label="项目">${icon('folders')}</button><button class="rail-button ${isTemplates?'active':''}" data-action="templates" title="模板文件" aria-label="模板文件">${icon('file-code')}</button><button class="rail-button ${isDatabase?'active':''}" data-action="database" title="共享数据库" aria-label="共享数据库">${icon('database')}</button><button class="rail-button ${isIcons?'active':''}" data-action="icon-gallery" title="图标库" aria-label="图标库">${icon('grid')}</button><div class="rail-spacer"></div><button class="rail-button" data-action="about" title="关于工作区" aria-label="关于工作区">${icon('info')}</button><span class="rail-bottom">S VIEW</span></aside>
 <main class="shell"><header class="topbar"><button class="icon-btn mobile-tree" data-action="tree" title="文件" aria-label="文件">${icon('panel-left')}</button><span class="brand-name">S View</span><span class="top-slash">/</span><span class="top-title">局域网编辑器</span><div class="top-actions"><span class="saved" id="saveStatus"><i class="status-dot"></i>${dirty?'待保存':'已保存'}</span>${isDashboard||isDatabase||isIcons?'':isTemplates?`<button class="btn" data-action="download-templates">${icon('download')} 下载模板</button>`:`<button class="btn" data-action="settings">${icon('settings-2')}<span class="optional">项目设置</span></button><button class="btn primary" data-action="run">${icon('play')} 运行</button>`}</div></header>
 ${isIcons?'<section class="dashboard icon-page" id="iconPage"></section>':isDatabase?'<section class="dashboard database-page" id="databasePage"></section>':isDashboard?'<section class="dashboard" id="dashboard"></section>':`<section class="workspace"><aside class="explorer"><div class="project-select"><label>${isTemplates?'GLOBAL TEMPLATES':'WORKSPACE'}</label>${isTemplates?'<div style="font-size:16px;font-weight:600;padding:8px 0">模板文件</div>':`<select id="projectSelect" aria-label="当前项目">${state.projects.map(p=>`<option value="${p.id}" ${p.id===activeId?'selected':''}>${esc(p.name)}</option>`).join('')}</select>`}</div><div class="section-cap"><span><button data-action="root-folder" title="返回根目录" style="padding:0;font-size:11px;color:inherit">文件</button></span>${ib('new-file','file-plus','新建文件')}${ib('new-folder','folder-plus','新建文件夹')}${ib('upload','import','上传文件')}</div><div class="tree" id="tree"></div><div class="explorer-foot">${icon(isTemplates?'layers':'folder-open')}<span>${isTemplates?'所有项目共用':'项目文件独立保存'}</span></div></aside><section class="editor-region" id="editor"></section></section>`}</main>`;
 if(isIcons)renderIconGallery();else if(isDatabase)renderDatabase();else if(isDashboard)renderDashboard();else{renderTree();renderEditor()}
}
function renderTree(){
 const files=filesNow(), allFolders=new Set(foldersNow());
 for(const path of [...Object.keys(files),...foldersNow().map(path=>path+'/')]){const parts=path.split('/');parts.pop();while(parts.length){allFolders.add(parts.join('/'));parts.pop()}}
 const list=[...allFolders].map(path=>({path,folder:true})).concat(Object.keys(files).map(path=>({path,folder:false})));
 const children=parent=>list.filter(x=>(x.path.includes('/')?x.path.slice(0,x.path.lastIndexOf('/')):'')===parent).sort((a,b)=>Number(b.folder)-Number(a.folder)||a.path.localeCompare(b.path,'zh-CN',{numeric:true}));
 const walk=(parent,depth)=>children(parent).map(x=>{
  const isProtected=view==='templates'&&Object.hasOwn(TEMPLATES,x.path)||view==='editor'&&x.path==='main.js';
  const actions=x.folder?ib('delete-folder','trash','删除文件夹',`data-path="${esc(x.path)}"`):`${ib('rename-file','pencil','重命名',`data-path="${esc(x.path)}" ${isProtected?'disabled':''}`)}${ib('delete-file','trash','删除文件',`data-path="${esc(x.path)}" ${isProtected?'disabled':''}`)}`;
  return `<div class="tree-row ${x.folder?'folder':''} ${!x.folder&&activeFile===x.path?'selected':''}" style="padding-left:${depth*12+3}px"><button class="tree-name" data-action="${x.folder?'folder':'file'}" data-path="${esc(x.path)}">${icon(x.folder?(collapsed.has(x.path)?'folder':'folder-open'):fileIcon(x.path))}<span>${esc(baseName(x.path))}</span></button><div class="tree-action" style="display:flex;width:auto">${actions}</div></div>${x.folder&&!collapsed.has(x.path)?walk(x.path,depth+1):''}`;
 }).join('');
 $('#tree').innerHTML=walk('',0)||'<div class="empty" style="padding:30px 10px">暂无文件</div>';
}
function language(path){return ({js:'JavaScript',mjs:'JavaScript',json:'JSON',css:'CSS',html:'HTML',svg:'SVG',md:'Markdown',txt:'Text'})[path.split('.').pop().toLowerCase()]||'Text'}
function highlighted(text){
 if(text.length>100000)return esc(text);
 const re=/(\/\*[\s\S]*?\*\/|\/\/[^\n]*|<!--[\s\S]*?-->)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|\b(const|let|var|function|return|if|else|for|while|new|async|await|true|false|null|import|from|export|class|throw|try|catch)\b|\b(\d+(?:\.\d+)?)\b/g;
 let result='',last=0,m;while((m=re.exec(text))){result+=esc(text.slice(last,m.index))+`<span class="tok-${m[1]?'comment':m[2]?'string':m[3]?'keyword':'number'}">${esc(m[0])}</span>`;last=re.lastIndex}return result+esc(text.slice(last))+'\n';
}
function renderEditor(){
 openFiles=openFiles.filter(path=>filesNow()[path]);if(activeFile&&!filesNow()[activeFile])activeFile=openFiles[0]||Object.keys(filesNow())[0];if(activeFile&&!openFiles.includes(activeFile))openFiles.push(activeFile);
 const file=filesNow()[activeFile], name=activeFile||'未打开文件';
 $('#editor').innerHTML=`<div class="tabs">${openFiles.map(path=>`<button class="file-tab ${path===activeFile?'active':''}" data-action="file" data-path="${esc(path)}">${icon(fileIcon(path))}<span>${esc(baseName(path))}</span><span class="tab-close" role="button" tabindex="0" data-action="close-tab" data-path="${esc(path)}" aria-label="关闭 ${esc(path)}">×</span></button>`).join('')}${view==='editor'?`<div class="tab-tools"><button class="btn variable-trigger" data-action="project-variables">${icon('variable')} 全局变量 <span class="count-badge">${projectNow()?.variables?.length||0}</span></button></div>`:''}</div><div class="editor-toolbar">${icon(view==='templates'?'layers':'folder')}<span>${esc(view==='templates'?'模板文件':projectNow()?.name)}</span>${icon('chevron-right')}<span>${esc(name)}</span><div class="tools">${ib('undo','undo-2','撤销')}${ib('redo','redo-2','重做')}${ib('save','save','保存（⌘/Ctrl S）')}${ib('download-file','download','下载当前文件',file?'':'disabled')}</div></div>${!file?'<div class="binary">选择或新建一个文件</div>':file.kind==='text'?`<div class="code-wrap"><div class="gutter" id="gutter"></div><div class="code-scroll"><pre class="highlight" id="highlight" aria-hidden="true"></pre><textarea id="code" class="code-input" aria-label="代码编辑器" spellcheck="false" autocapitalize="off" autocomplete="off" wrap="off"></textarea></div></div>`:`<div class="binary">${file.kind==='binary'&&file.mime?.startsWith('image/')?`<img src="${esc(file.data)}" alt="${esc(name)}">`:icon('file-text')}<p>${esc(baseName(name))} · ${humanSize(file.kind==='remote'?file.size:fileBytes(file).length)}</p><button class="btn" data-action="download-file">${icon('download')} 下载文件</button></div>`}<div class="editor-status"><span id="cursorStatus">行 1，列 1</span><span>${language(name)}</span><span>UTF-8</span><span>LF</span></div>`;
 const el=$('#code');if(!el)return;
 el.value=file.text;el.setSelectionRange(0,0);updateHighlight();const pos=positions.get(fileKey());if(pos){el.setSelectionRange(pos.start,pos.end);el.scrollTop=pos.top;el.scrollLeft=pos.left;syncScroll()}
 el.addEventListener('input',()=>commitText(el.value));el.addEventListener('keydown',editorKey);el.addEventListener('scroll',syncScroll);el.addEventListener('click',cursorStatus);el.addEventListener('keyup',cursorStatus);el.addEventListener('select',cursorStatus);
 cursorStatus();
}
function updateHighlight(){const el=$('#code');if(!el)return;$('#highlight').innerHTML=highlighted(el.value);$('#gutter').textContent=Array.from({length:el.value.split('\n').length},(_,i)=>i+1).join('\n');syncScroll();cursorStatus()}
function syncScroll(){const el=$('#code');if(!el)return;$('#highlight').style.transform=`translate(${-el.scrollLeft}px,${-el.scrollTop}px)`;$('#gutter').scrollTop=el.scrollTop}
function cursorStatus(){const el=$('#code');if(!el)return;const prefix=el.value.slice(0,el.selectionStart);$('#cursorStatus').textContent=`行 ${prefix.split('\n').length}，列 ${prefix.length-prefix.lastIndexOf('\n')}`}
function commitText(text,history=true){const file=filesNow()[activeFile];if(!file||file.kind!=='text')return;if(file.text!==text){if(history){const k=fileKey(),h=undo.get(k)||[];h.push({text:file.text,pos:positions.get(k)?.start??$('#code').selectionStart});while(h.length>80||h.reduce((n,v)=>n+v.text.length,0)>1000000)h.shift();undo.set(k,h);redo.set(k,[])}file.text=text;if(projectNow()&&view!=='templates')projectNow().updated=Date.now();markDirty()}updateHighlight();rememberEditor()}
function historyMove(back){const k=fileKey(),from=(back?undo:redo).get(k)||[],to=(back?redo:undo).get(k)||[];if(!from.length)return;const el=$('#code');if(!el)return;to.push({text:el.value,pos:el.selectionStart});(back?redo:undo).set(k,to);const entry=from.pop();el.value=entry.text;el.setSelectionRange(entry.pos,entry.pos);commitText(entry.text,false);el.focus()}
function editorKey(e){const el=e.currentTarget;if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='z'){e.preventDefault();historyMove(!e.shiftKey);return}if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='y'){e.preventDefault();historyMove(false);return}if(e.key!=='Tab'&&e.key!=='Enter')return;e.preventDefault();const start=el.selectionStart,end=el.selectionEnd,prefix=el.value.slice(0,start).split('\n').pop(),indent=prefix.match(/^[\t ]*/)[0];let insertion=e.key==='Tab'?'    ':'\n'+indent;let caret=insertion.length;
 if(e.key==='Enter'&&/[{[(]\s*$/.test(prefix)){insertion+='    ';caret=insertion.length;const pairs={'{':'}','[':']','(':')'};if(el.value.slice(end).trimStart().startsWith(pairs[prefix.trimEnd().slice(-1)]))insertion+='\n'+indent}
 el.setRangeText(insertion,start,end,'end');el.setSelectionRange(start+caret,start+caret);commitText(el.value);
}
function renderDashboard(){const filtered=state.projects.filter(p=>(!category||p.category===category)&&p.name.toLowerCase().includes(query.toLowerCase()));$('#dashboard').innerHTML=`<div class="dashboard-head"><div><div class="eyebrow">YOUR WORKSPACE</div><h1>你的项目 <span style="color:#adb5c2;font-size:16px;font-weight:400;margin-left:8px">${state.projects.length}</span></h1></div><div class="dashboard-tools"><label class="searchbox">${icon('search')}<input id="projectSearch" value="${esc(query)}" placeholder="搜索项目" aria-label="搜索项目"></label><button class="btn" data-action="categories">${icon('folders')} 分类</button><button class="btn primary" data-action="new-project">${icon('plus')} 新建项目</button></div></div><div class="category-tabs">${['',...state.categories].map(c=>`<button data-action="category" data-category="${esc(c)}" class="${category===c?'active':''}">${esc(c||'全部')}</button>`).join('')}</div><div class="project-grid">${filtered.map(p=>`<article class="project-card"><button class="card-art" style="background:${/^#[0-9a-f]{6}$/i.test(p.colorCss)?p.colorCss:'#eaf0ff'};width:100%" data-action="open-project" data-id="${p.id}" aria-label="编辑 ${esc(p.name)}">${icon(p.icon||'code')}</button><div class="card-title"><span>${esc(p.name)}</span>${ib('project-settings','settings-2','项目设置',`data-id="${p.id}"`)}</div><div class="card-meta">${esc(p.category||'未分类')} <span style="margin:0 7px;color:#d1d6df">/</span> ${Object.keys(p.files).length} 个文件</div><div class="card-actions"><button data-action="open-project" data-id="${p.id}">${icon('code')} 编辑项目</button><div class="card-icon-actions">${ib('export-project','download','下载项目 ZIP',`data-id="${p.id}"`)}${ib('delete-project','trash','删除项目',`data-id="${p.id}"`)}</div></div></article>`).join('')}</div>${!filtered.length?`<div class="empty">${icon('folders')}没有找到项目</div>`:''}`}
function modal(title,content,submit,onSubmit,label='保存'){
 $('#modalBody').innerHTML=`<header class="modal-head"><h2>${title}</h2>${ib('close-modal','x','关闭')}</header><div class="modal-content">${content}<div class="form-error" id="formError" role="alert"></div></div><footer class="modal-foot"><button type="button" class="btn" data-action="close-modal">取消</button>${submit?`<button class="btn primary" type="submit">${label}</button>`:''}</footer>`;
 $('#modalForm').onsubmit=async e=>{e.preventDefault();try{await onSubmit(new FormData(e.target));$('#modal').close()}catch(err){$('#formError').textContent=err.message||'操作失败'}};
 if(!$('#modal').open)$('#modal').showModal();
}
const field=(label,name,value,options={})=>`<label class="field ${options.full?'full':''}">${label}${options.select?`<select name="${name}">${options.select.map(([v,t])=>`<option value="${esc(v)}" ${v===value?'selected':''}>${esc(t)}</option>`).join('')}</select>`:`<input name="${name}" value="${esc(value)}" type="${options.type||'text'}" ${options.required?'required':''} ${options.min!==undefined?`min="${options.min}"`:''} ${options.max!==undefined?`max="${options.max}"`:''} ${options.type==='number'?'step="any"':''}>`}</label>`;
function settings(id,newProject=false){const p=newProject?project(''):state.projects.find(p=>p.id===id);if(!p)return;
 modal(newProject?'新建项目':'项目设置',`<div class="form-section">${icon('folder')} 基本信息</div><div class="fields">${field('名称','name',p.name,{required:true})}${field('分类','category',p.category,{select:[['','未分类'],...state.categories.map(c=>[c,c])]})}</div><div class="form-section">${icon('monitor')} 运行窗口</div><div class="fields">${field('宽度','width',p.width)}${field('高度','height',p.height)}${field('窗口位置','location',p.location,{full:true,select:[['center','完全居中'],['top','上居中'],['bottom','下居中'],['topLeft','左上角'],['topRight','右上角'],['bottomLeft','左下角'],['bottomRight','右下角']]})}${['top','bottom','left','right'].map((k,i)=>field(['上边距','下边距','左边距','右边距'][i],k,p.margins[k])).join('')}${field('圆角（dp）','cornerRadius',p.cornerRadius,{type:'number',min:0})}${field('遮罩透明度（%）','backdropTransparency',p.backdropTransparency,{type:'number',min:0,max:100})}</div>`,true,async data=>{
 const next=Object.fromEntries(data);next.name=next.name.trim();if(!next.name)throw Error('请输入项目名称');
 for(const k of ['width','height','top','bottom','left','right']){if(!/^\d+(?:\.\d+)?(?:dp|px|%)$/.test(next[k])||(['width','height'].includes(k)&&parseFloat(next[k])<=0))throw Error('尺寸请填写数值和单位，例如 360dp、80%')}
 next.cornerRadius=Number(next.cornerRadius);next.backdropTransparency=Number(next.backdropTransparency);if(!Number.isFinite(next.cornerRadius)||next.cornerRadius<0||!Number.isFinite(next.backdropTransparency)||next.backdropTransparency<0||next.backdropTransparency>100)throw Error('请检查圆角和透明度');
 next.margins={top:next.top,bottom:next.bottom,left:next.left,right:next.right};for(const k of ['top','bottom','left','right'])delete next[k];Object.assign(p,next,{updated:Date.now()});if(newProject&&!state.projects.some(x=>x.id===p.id))state.projects.push(p)
 markDirty();await save();if(newProject)switchProject(p.id);else render();toast(newProject?'项目已创建':'项目设置已保存');
 },newProject?'创建项目':'保存');
}
function validatePath(path){path=path.trim();if(!path||path.startsWith('/')||path.endsWith('/')||path.split('/').some(v=>!v||v==='.'||v==='..'||v==='__proto__'||v==='constructor'||v==='prototype')||/[\\\x00-\x1f:*?"<>|]/.test(path))throw Error('请输入有效的相对路径');return path}
function pathExists(path){return Object.hasOwn(filesNow(),path)||foldersNow().some(p=>p===path||p.startsWith(path+'/'))||Object.keys(filesNow()).some(p=>p.startsWith(path+'/'))}
function checkParents(path){if(path.split('/').slice(0,-1).some((_,i,a)=>Object.hasOwn(filesNow(),a.slice(0,i+1).join('/'))))throw Error('上级路径是文件')}
function newEntry(folder){modal(folder?'新建文件夹':'新建文件',field(folder?'文件夹路径':'文件路径','path',currentFolder?currentFolder+'/':'',{full:true,required:true}),true,data=>{
 const path=validatePath(data.get('path'));if(pathExists(path))throw Error('名称已存在');checkParents(path);
 if(folder)foldersNow().push(path);else filesNow()[path]=textFile('');markDirty();if(folder){currentFolder=path;collapsed.delete(path);renderTree()}else selectFile(path);toast(folder?'文件夹已创建':'文件已创建');
 },'创建')}
function confirmDialog(title,message,action){modal(title,`<p class="confirm-text">${esc(message)}</p>`,true,()=>action(),'删除');$('#modal button[type=submit]').className='btn danger'}
function protectedFile(path){return view==='templates'&&Object.hasOwn(TEMPLATES,path)||view==='editor'&&path==='main.js'}
function deleteFile(path){if(protectedFile(path))return;confirmDialog('删除文件',`确定删除「${path}」？`,()=>{delete filesNow()[path];undo.delete(scopeKey()+'::'+path);redo.delete(scopeKey()+'::'+path);markDirty();renderTree();renderEditor();toast('文件已删除')})}
function deleteFolder(path){const content=Object.keys(filesNow()).filter(f=>f.startsWith(path+'/'));if(content.some(protectedFile)){toast('文件夹中包含必需文件');return}confirmDialog('删除文件夹',`「${path}」及其中 ${content.length} 个文件将被删除。`,()=>{content.forEach(f=>delete filesNow()[f]);const folders=foldersNow();for(let i=folders.length-1;i>=0;i--)if(folders[i]===path||folders[i].startsWith(path+'/'))folders.splice(i,1);if(currentFolder===path||currentFolder.startsWith(path+'/'))currentFolder='';markDirty();renderTree();renderEditor();toast('文件夹已删除')})}
function renameFile(path){if(protectedFile(path))return;modal('重命名文件',field('文件路径','path',path,{full:true,required:true}),true,data=>{const next=validatePath(data.get('path'));if(next===path)return;if(pathExists(next))throw Error('名称已存在');checkParents(next);filesNow()[next]=filesNow()[path];delete filesNow()[path];openFiles=openFiles.map(f=>f===path?next:f);if(activeFile===path)activeFile=next;markDirty();renderTree();renderEditor();toast('文件已重命名')})}
function download(blob,name){const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000)}
async function downloadFile(){
 const file=filesNow()[activeFile];if(!file)return;
 try{const blob=file.kind==='remote'?await api('file',{id:view==='templates'?null:activeId,path:activeFile},true):new Blob([fileBytes(file)],{type:'application/octet-stream'});download(blob,baseName(activeFile));toast('已开始下载')}catch(error){toast(error.message)}
}
async function exportProject(id){try{await save();download(await api('export',{id},true),(state.projects.find(p=>p.id===id)?.name||'项目')+'.zip')}catch(error){toast(error.message)}}
function uploadFiles(){const context={templates:view==='templates',id:activeId,folder:currentFolder};$('#upload').value='';$('#upload').onchange=async e=>{
 const p=state.projects.find(p=>p.id===context.id),files=context.templates?state.templates:p?.files;if(!files)return;let count=0;
 for(const file of e.target.files){if(file.size>16*1024*1024){toast('单个文件不能超过 16 MB');continue}let path=(context.folder?context.folder+'/':'')+file.name;try{path=validatePath(path)}catch{toast('已跳过无效文件名');continue}
 if(files[path]){let i=2,original=path;const dot=path.lastIndexOf('.');while(files[path])path=dot>path.lastIndexOf('/')?original.slice(0,dot)+' ('+i+++')'+original.slice(dot):original+' ('+i+++')'}
 const text=/\.(js|mjs|cjs|css|html?|json|txt|md|svg|xml|csv|yaml|yml)$/i.test(path)||file.type.startsWith('text/');
 try{files[path]=text?textFile(await file.text()):{kind:'binary',mime:file.type||'application/octet-stream',data:await new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=reject;r.readAsDataURL(file)})};markDirty();await save();count++}catch(error){toast(error.message||'无法读取 '+file.name);if(context.templates===(view==='templates')&&(context.templates||activeId===context.id))renderTree();return}
 }
 if(context.templates===(view==='templates')&&(context.templates||activeId===context.id))renderTree();toast(`已上传 ${count} 个文件`);
 };$('#upload').click()}
function manageCategories(){modal('管理分类',`<div id="categoryList">${state.categories.map(c=>`<div class="category-item"><span>${esc(c)}</span>${ib('delete-category','trash','删除分类',`data-category="${esc(c)}"`)}</div>`).join('')}</div><div class="category-add"><input name="name" placeholder="新分类名称" aria-label="新分类名称" required><button class="btn blue" type="submit">${icon('plus')} 添加</button></div>`,false,data=>{const name=data.get('name').trim();if(!name)throw Error('请输入分类名称');if(state.categories.includes(name))throw Error('分类已存在');state.categories.push(name);markDirty();if(view==='projects')renderDashboard();toast('分类已添加')});}
const actions={
 projects(){rememberWorkspace();view='projects';render()},templates:openTemplates,
 'open-project':el=>switchProject(el.dataset.id),'new-project':()=>settings(null,true),settings:()=>settings(activeId),'project-settings':el=>settings(el.dataset.id),
 category(el){category=el.dataset.category;renderDashboard()},categories:manageCategories,
 'delete-category'(el){const c=el.dataset.category;state.categories=state.categories.filter(x=>x!==c);state.projects.forEach(p=>{if(p.category===c)p.category=''});if(category===c)category='';markDirty();if(view==='projects')renderDashboard();manageCategories()},
 'delete-project'(el){const p=state.projects.find(x=>x.id===el.dataset.id);if(!p)return;confirmDialog('删除项目',`确定删除「${p.name}」及其所有文件？`,()=>{state.projects=state.projects.filter(x=>x.id!==p.id);if(activeId===p.id)activeId=state.projects[0]?.id;markDirty();view='projects';render();toast('项目已删除')})},
 file:el=>selectFile(el.dataset.path),folder(el){const path=el.dataset.path;currentFolder=path;if(collapsed.has(path))collapsed.delete(path);else collapsed.add(path);renderTree()},
 'root-folder'(){currentFolder='';toast('已选择根目录')},'new-file':()=>newEntry(false),'new-folder':()=>newEntry(true),upload:uploadFiles,
 'delete-file':el=>deleteFile(el.dataset.path),'delete-folder':el=>deleteFolder(el.dataset.path),'rename-file':el=>renameFile(el.dataset.path),
 'close-tab'(el){const path=el.dataset.path;openFiles=openFiles.filter(x=>x!==path);if(activeFile===path)activeFile=openFiles.at(-1);renderEditor();renderTree()},
 'download-file':downloadFile,'export-project':el=>exportProject(el.dataset.id),async 'download-templates'(){try{await save();download(await api('export',{id:null},true),'S-View-templates.zip')}catch(error){toast(error.message)}},
 save(){save().then(()=>toast('已保存到手机')).catch(()=>{})},undo:()=>historyMove(true),redo:()=>historyMove(false),
 async run(){const id=activeId;try{await save();await api('run',{id});toast('已在手机运行')}catch(error){toast(error.message)}},
 'close-modal':()=>$('#modal').close(),tree(){document.querySelector('.explorer')?.classList.toggle('mobile-open')},
 about(){modal('关于工作区',`<p class="confirm-text">S View 网页编辑器</p><p class="muted-note">修改自动保存到手机。运行按钮会在手机上打开当前项目。</p><p class="muted-note">请保持手机应用在前台；离开应用或停止服务后连接将断开。</p>`,false,()=>{})}
};
document.addEventListener('click',e=>{const el=e.target.closest('[data-action]');if(!el||el.disabled)return;e.stopPropagation();const action=actions[el.dataset.action];if(action)try{Promise.resolve(action(el)).catch(err=>toast(err.message||'操作失败'))}catch(err){toast(err.message||'操作失败')}});
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='s'){e.preventDefault();actions.save()}if((e.key==='Enter'||e.key===' ')&&e.target.matches('.tab-close')){e.preventDefault();actions['close-tab'](e.target)}});
document.addEventListener('change',e=>{if(e.target.id==='projectSelect')switchProject(e.target.value)});
document.addEventListener('input',e=>{if(e.target.id==='projectSearch'){const pos=e.target.selectionStart;query=e.target.value;renderDashboard();$('#projectSearch').focus();$('#projectSearch').setSelectionRange(pos,pos)}});
window.addEventListener('beforeunload',e=>{if(dirty){save();e.preventDefault();e.returnValue=''}});
login();
