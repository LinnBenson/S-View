'use strict';
/** 补全浮层独立管理事件、加载和定位；文本提交复用编辑器的历史与保存逻辑。 */
globalThis.EditorCompletion = (() => {
 let current=null, loading=null;
 function load(){
  if(!loading)loading=Promise.all(['/completion-css.json','/completion-sview.json'].map(async url=>{
   const response=await fetch(url);if(!response.ok)throw new Error('补全数据加载失败');return response.json();
  })).then(([css,sview])=>EditorCompletionEngine.create(css,sview)).catch(error=>{loading=null;throw error});
  return loading;
 }
 function hide(){
  if(!current)return;
  clearTimeout(current.timer);current.generation++;current.result=null;
  current.popup.hidden=true;current.input.removeAttribute('aria-activedescendant');current.input.setAttribute('aria-expanded','false');
 }
 function detach(){
  if(!current)return;
  hide();current.abort.abort();current.popup.remove();current.mirror.remove();
  for(const name of ['aria-controls','aria-expanded','aria-autocomplete','aria-activedescendant'])current.input.removeAttribute(name);
  current=null;
 }
 /** 镜像只在显示候选时定位，保留制表符、字体和横向滚动的真实宽度。 */
 function position(session){
  const {input,mirror,popup}=session,style=getComputedStyle(input),rect=input.getBoundingClientRect();
  for(const name of ['font','lineHeight','letterSpacing','paddingTop','paddingRight','paddingBottom','paddingLeft','tabSize'])mirror.style[name]=style[name];
  mirror.style.width=input.clientWidth+'px';
  mirror.textContent=input.value.slice(0,input.selectionStart);
  const marker=document.createElement('span');marker.textContent='\u200b';mirror.append(marker);
  const local=marker.getBoundingClientRect(),base=mirror.getBoundingClientRect(),lineHeight=parseFloat(style.lineHeight)||25;
  const x=rect.left+local.left-base.left-input.scrollLeft,y=rect.top+local.top-base.top-input.scrollTop;
  if(y<rect.top||y>rect.bottom||x<rect.left||x>rect.right){hide();return}
  popup.style.maxHeight=Math.min(300,Math.max(100,innerHeight-24))+'px';
  popup.style.left=Math.max(8,Math.min(x,innerWidth-popup.offsetWidth-8))+'px';
  const below=y+lineHeight+4;
  popup.style.top=Math.max(8,below+popup.offsetHeight>innerHeight-8?y-popup.offsetHeight-4:below)+'px';
 }
 function select(index){
  const session=current;if(!session?.result)return;
  session.index=(index+session.result.items.length)%session.result.items.length;
  for(const [i,row] of [...session.list.children].entries())row.setAttribute('aria-selected',String(i===session.index));
  const row=session.list.children[session.index];row.scrollIntoView({block:'nearest'});
  const entry=session.result.items[session.index];
  session.detail.textContent=entry.detail+(entry.params!==null?' · '+entry.label+'('+entry.params+')':'');
  session.input.setAttribute('aria-activedescendant',row.id);
 }
 function show(session,result){
  session.result=result;session.list.replaceChildren();
  result.items.forEach((entry,index)=>{
   const row=document.createElement('div');row.id='completion-option-'+index;row.setAttribute('role','option');row.dataset.index=index;
   const label=document.createElement('span');label.textContent=entry.label;
   const type=document.createElement('small');type.textContent=entry.params!==null?'方法':entry.detail.startsWith('代码片段')?'片段':'';
   row.append(label,type);session.list.append(row);
  });
  session.popup.hidden=false;session.input.setAttribute('aria-expanded','true');select(0);position(session);
 }
 function accept(){
  const session=current;if(!session?.result)return false;
  const {input,result}=session;
  if(input.selectionStart!==result.end||input.selectionEnd!==result.end||input.value!==session.text){hide();return false}
  const entry=result.items[session.index],change=session.engine.insertion(entry,input.value.slice(result.end));
  const top=input.scrollTop,left=input.scrollLeft;
  session.before();input.setRangeText(change.text,result.start,result.end,'end');
  const caret=result.start+change.caret;input.setSelectionRange(caret,caret);
  input.scrollTop=top;input.scrollLeft=left;hide();session.commit(input.value);input.focus();return true;
 }
 async function update(manual=false){
  const session=current;if(!session||session.composing)return;
  const {input}=session;
  if(input.selectionStart!==input.selectionEnd||input.value.length>200000){hide();return}
  const text=input.value,pos=input.selectionStart,generation=++session.generation;
  if(!manual&&!/[\w$-]{2,}$|[.:][\w$-]*$/.test(text.slice(Math.max(0,pos-100),pos))){hide();return}
  try{
   const engine=await load();
   if(current!==session||generation!==session.generation||!input.isConnected||document.activeElement!==input||input.value!==text||input.selectionStart!==pos||input.selectionEnd!==pos)return;
   const result=engine.suggest(text,pos,session.path,manual);session.engine=engine;session.text=text;
   if(result)show(session,result);else hide();
  }catch(_){if(current===session){hide();if(manual)session.notify('补全数据暂时不可用，请重试')}}
 }
 function schedule(){
  if(!current)return;
  hide();const session=current;
  if(session.composing)return;
  session.timer=setTimeout(()=>{if(current===session)void update()},90);
 }
 function attach(input,path,before,commit,notify){
  detach();
  if(!/\.(?:js|mjs|cjs|css|html|htm|svg)$/i.test(path))return;
  const popup=document.createElement('div');popup.className='completion-popup';popup.hidden=true;
  const list=document.createElement('div');list.className='completion-list';list.id='editor-completions';list.setAttribute('role','listbox');list.setAttribute('aria-label','代码补全');
  const detail=document.createElement('div');detail.className='completion-detail';popup.append(list,detail);
  const mirror=document.createElement('div');mirror.className='completion-mirror';mirror.setAttribute('aria-hidden','true');
  document.body.append(popup,mirror);
  const abort=new AbortController(),options={signal:abort.signal};
  current={input,path,before,commit,notify,popup,list,detail,mirror,abort,generation:0,index:0,composing:false,result:null};
  input.setAttribute('aria-autocomplete','list');input.setAttribute('aria-controls',list.id);input.setAttribute('aria-expanded','false');
  input.addEventListener('input',schedule,options);
  input.addEventListener('compositionstart',()=>{hide();current.composing=true},options);
  input.addEventListener('compositionend',()=>{current.composing=false},options);
  for(const name of ['blur','pointerdown','scroll'])input.addEventListener(name,hide,options);
  input.addEventListener('select',()=>{if(current?.result&&(input.selectionStart!==current.result.end||input.selectionEnd!==current.result.end))hide()},options);
  window.addEventListener('resize',hide,options);
  popup.addEventListener('pointerdown',event=>{
   const row=event.target.closest('[data-index]');event.preventDefault();if(row){select(Number(row.dataset.index));accept()}
  },options);
 }
 function handleKey(event){
  if(!current||event.currentTarget!==current.input||event.isComposing||current.composing)return false;
  if(event.ctrlKey&&!event.altKey&&!event.metaKey&&event.code==='Space'){event.preventDefault();void update(true);return true}
  if(event.key==='Tab'||((event.ctrlKey||event.metaKey)&&event.code!=='Space')||(event.shiftKey&&event.key.startsWith('Arrow'))){hide();return false}
  if(!current.result){if(['Escape','ArrowLeft','ArrowRight','Home','End'].includes(event.key))hide();return false}
  if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();select(current.index+(event.key==='ArrowDown'?1:-1));return true}
  if(event.key==='Enter'){event.preventDefault();accept();return true}
  if(event.key==='Escape'){event.preventDefault();hide();return true}
  if(event.ctrlKey||event.metaKey||['ArrowLeft','ArrowRight','Home','End','PageUp','PageDown'].includes(event.key))hide();
  return false;
 }
 return {attach,detach,handleKey};
})();
