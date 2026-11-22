'use strict';
// 项目变量使用配置修订号；共享数据使用逐项原值校验。
let databaseEntries=[], databaseLoading=false, databaseError='', databaseQuery='';
const variableTypes=[['string','文本'],['number','数字'],['boolean','布尔值'],['array','数组'],['object','对象']];
const typeLabel=type=>variableTypes.find(item=>item[0]===type)?.[1]||type;
function variableRows(entries,scope){
 return entries.map((entry,index)=>`<div class="variable-row"><div class="variable-name"><strong>${esc(entry.name)}</strong><span class="type-badge">${esc(typeLabel(entry.type))}</span></div><code class="variable-preview">${esc(entry.value)}</code><div class="variable-actions">${ib('edit-variable','pencil','编辑 '+esc(entry.name),`data-scope="${scope}" data-index="${index}"`)}${ib('remove-variable','trash','删除 '+esc(entry.name),`data-scope="${scope}" data-index="${index}"`)}</div></div>`).join('');
}
function projectVariables(){
 const p=projectNow();if(!p)return;
 rememberEditor();
 modal('项目全局变量',`<div class="variable-heading"><span class="muted-note">${esc(p.name)}</span><button type="button" class="btn blue" data-action="add-variable" data-scope="project">${icon('plus')} 添加变量</button></div><div class="variable-list">${variableRows(p.variables||[],'project')||'<div class="empty">暂无全局变量</div>'}</div>`,false,()=>{});
 $('#modal').classList.add('variable-modal');
}
async function loadDatabase(){
 databaseLoading=true;databaseError='';if(view==='database')renderDatabase();
 try{databaseEntries=(await api('database/list')).entries}catch(error){databaseError=error.message}
 finally{databaseLoading=false;if(view==='database')renderDatabase()}
}
async function openDatabase(){rememberWorkspace();await save();view='database';render();await loadDatabase()}
function renderDatabase(){
 const target=$('#databasePage');if(!target)return;
 const filtered=databaseEntries.map((entry,index)=>({entry,index})).filter(({entry})=>entry.name.toLowerCase().includes(databaseQuery.toLowerCase()));
 target.innerHTML=`<div class="dashboard-head"><div><div class="eyebrow">所有项目共用</div><h1>共享数据库 <span class="heading-count">${databaseEntries.length}</span></h1></div><div class="dashboard-tools"><label class="searchbox">${icon('search')}<input id="databaseSearch" value="${esc(databaseQuery)}" placeholder="搜索变量" aria-label="搜索共享变量"></label><button class="btn" data-action="refresh-database" ${databaseLoading?'disabled':''}>${icon('refresh-cw')} 刷新</button><button class="btn primary" data-action="add-variable" data-scope="database" ${databaseLoading||databaseError?'disabled':''}>${icon('plus')} 添加变量</button></div></div><div class="database-panel"><div class="database-columns"><span>名称 / 类型</span><span>值</span><span>操作</span></div>${databaseError?`<div class="empty form-error">${esc(databaseError)}</div>`:databaseLoading?'<div class="empty">正在读取…</div>':filtered.length?filtered.map(({entry,index})=>variableRows([entry],'database').replaceAll('data-index="0"',`data-index="${index}"`)).join(''):'<div class="empty">'+icon('database')+(databaseQuery?'没有匹配的变量':'暂无共享变量')+'</div>'}</div>`;
}
function variableValueField(type,value){
 return type==='boolean'?field('值','value',value,{full:true,select:[['true','true'],['false','false']]}):`<label class="field full">值<textarea name="value" rows="${type==='object'||type==='array'?8:4}" spellcheck="false" aria-label="变量值">${esc(value)}</textarea></label>`;
}
function validateVariable(data,scope){
 const entry={name:String(data.get('name')).trim(),type:String(data.get('type')),value:String(data.get('value'))};
 if(!entry.name)throw Error('请输入名称');
 if(scope==='project'&&!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(entry.name))throw Error('变量名须为有效的 JavaScript 标识符');
 if(entry.type==='number'&&(!entry.value.trim()||!Number.isFinite(Number(entry.value))))throw Error('请输入有效数字');
 if(entry.type==='array'||entry.type==='object'){
  let parsed;try{parsed=JSON.parse(entry.value)}catch{throw Error('请输入有效 JSON')}
  if(entry.type==='array'?!Array.isArray(parsed):parsed===null||Array.isArray(parsed)||typeof parsed!=='object')throw Error('值与所选类型不一致');
 }
 return entry;
}
async function saveProjectVariables(p,next){
 await save();const previous=p.variables;p.variables=next;markDirty();
 try{await save()}catch(error){p.variables=previous;dirty=!equal(state,baseline);setStatus(dirty?'待保存':'已保存');throw error}
 if(view==='editor'&&activeId===p.id)renderEditor();
}
function editVariable(scope,index){
 const p=scope==='project'?projectNow():null;
 const previous=index===undefined?null:structuredClone((p?p.variables:databaseEntries)[index]);
 const entry=previous||{name:'',type:'string',value:''};
 modal(previous?'编辑变量':'添加变量',`<div class="fields">${field(scope==='project'?'变量名':'键名','name',entry.name,{required:true})}${field('类型','type',entry.type,{select:variableTypes})}<div id="variableValue" class="full">${variableValueField(entry.type,entry.value)}</div></div>`,true,async data=>{
  const value=validateVariable(data,scope);
  if(scope==='project'){
   const next=[...(p.variables||[])];if(previous)next[index]=value;else next.push(value);
   if(new Set(next.map(item=>item.name)).size!==next.length)throw Error('变量名已存在');
   await saveProjectVariables(p,next);
  }else{
   databaseEntries=(await api('database/edit',{value,previous})).entries;renderDatabase();
  }
  toast('变量已保存');if(scope==='project')requestAnimationFrame(projectVariables);
 });
 $('#modal').classList.add('variable-modal');
 $('#modal select[name="type"]').onchange=e=>{
  const type=e.target.value,defaults={string:'',number:'0',boolean:'true',array:'[]',object:'{}'};
  $('#variableValue').innerHTML=variableValueField(type,defaults[type]);
 };
}
function removeVariable(scope,index){
 const p=scope==='project'?projectNow():null,entry=structuredClone((p?p.variables:databaseEntries)[index]);
 confirmDialog('删除变量',`确定删除「${entry.name}」？`,async()=>{
  if(p){await saveProjectVariables(p,p.variables.filter((_,i)=>i!==index));requestAnimationFrame(projectVariables)}
  else{databaseEntries=(await api('database/remove',{previous:entry})).entries;renderDatabase()}
  toast('变量已删除');
 });
}
Object.assign(actions,{
 database:openDatabase,'refresh-database':loadDatabase,'project-variables':projectVariables,
 'add-variable':el=>editVariable(el.dataset.scope),
 'edit-variable':el=>editVariable(el.dataset.scope,Number(el.dataset.index)),
 'remove-variable':el=>removeVariable(el.dataset.scope,Number(el.dataset.index))
});
document.addEventListener('input',e=>{
 if(e.target.id==='databaseSearch'){const position=e.target.selectionStart;databaseQuery=e.target.value;renderDatabase();$('#databaseSearch').focus();$('#databaseSearch').setSelectionRange(position,position)}
});
$('#modal').addEventListener('close',()=>$('#modal').classList.remove('variable-modal'));
