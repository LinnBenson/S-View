'use strict';
/** 纯文本补全：只分析有限长度的源码，不执行用户表达式或读取其对象。 */
globalThis.EditorCompletionEngine = (() => {
 const words = text => text.split(/\s+/).filter(Boolean);
 const keywords = words('async await break case catch class const continue debugger default delete do else export extends false finally for function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while with yield');
 const constructors = words('Object Function Boolean Symbol Error AggregateError EvalError RangeError ReferenceError SyntaxError TypeError URIError Number BigInt Math Date String RegExp Array Int8Array Uint8Array Uint8ClampedArray Int16Array Uint16Array Int32Array Uint32Array BigInt64Array BigUint64Array Float32Array Float64Array Map Set WeakMap WeakSet ArrayBuffer SharedArrayBuffer DataView Atomics JSON Promise Reflect Proxy Intl WeakRef FinalizationRegistry Iterator');
 const webTypes = words('URL URLSearchParams Headers Request Response FormData Blob File FileReader TextEncoder TextDecoder AbortController AbortSignal Event CustomEvent EventTarget Node Element HTMLElement Document DOMTokenList CSSStyleDeclaration Storage NodeList HTMLCollection MutationObserver IntersectionObserver ResizeObserver WebSocket Worker MessageChannel ReadableStream WritableStream TransformStream CompressionStream DecompressionStream DOMParser XMLSerializer Image Audio Notification');
 const globals = words('console document window globalThis navigator location history performance crypto localStorage sessionStorage fetch setTimeout clearTimeout setInterval clearInterval requestAnimationFrame cancelAnimationFrame queueMicrotask structuredClone atob btoa parseInt parseFloat isNaN isFinite encodeURI decodeURI encodeURIComponent decodeURIComponent addEventListener removeEventListener dispatchEvent getComputedStyle matchMedia alert confirm prompt postMessage');
 const signatures = {querySelector:'selector',querySelectorAll:'selector',getElementById:'id',createElement:'tagName',addEventListener:'type, listener, options',removeEventListener:'type, listener, options',setAttribute:'name, value',getAttribute:'name',fetch:'url, options',setTimeout:'callback, delay',setInterval:'callback, delay',map:'callback, thisArg',filter:'callback, thisArg',forEach:'callback, thisArg',reduce:'callback, initialValue',find:'callback',includes:'value, fromIndex',slice:'start, end',splice:'start, deleteCount, ...items',replace:'pattern, replacement',split:'separator, limit',stringify:'value, replacer, space',parse:'text, reviver',setItem:'key, value',getItem:'key',removeItem:'key'};
 const events = words('click dblclick contextmenu input beforeinput change submit reset focus blur focusin focusout keydown keyup pointerdown pointerup pointermove pointerenter pointerleave pointercancel mousedown mouseup mousemove mouseenter mouseleave touchstart touchmove touchend wheel scroll resize load error DOMContentLoaded visibilitychange animationend transitionend dragstart dragover drop paste copy cut compositionstart compositionend message online offline popstate hashchange');
 const snippets = [
  ['if','if (|) {\n    \n}','条件分支'],['for','for (const item of |) {\n    \n}','遍历集合'],
  ['function','function |() {\n    \n}','函数声明'],['try','try {\n    |\n} catch (error) {\n    console.error(error);\n}','捕获异常'],
  ['async','async function |() {\n    \n}','异步函数']
 ];
 const item = (label,detail='关键词',insert=label,params=null) => ({label,detail,insert,params});

 /** 注释和字符串屏蔽后保留位置；模板字符串的 ${...} 内恢复 JS 分析。 */
 function lex(text, css=false) {
  let mode='code', quote='', escaped=false, regexClass=false, templateDepth=[], depth=0, clean='';
  for(let i=0;i<text.length;i++){
   const c=text[i],n=text[i+1];
   if(mode==='regex'){
    if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c==='[')regexClass=true;else if(c===']')regexClass=false;else if(c==='/'&&!regexClass)mode='code';
    clean+=' ';continue;
   }
   if(mode==='line'){if(c==='\n')mode='code';clean+=c==='\n'?'\n':' ';continue}
   if(mode==='comment'){if(c==='*'&&n==='/'){clean+='  ';i++;mode='code'}else clean+=c==='\n'?'\n':' ';continue}
   if(mode==='string'){
    if(escaped){escaped=false;clean+=' ';continue}
    if(c==='\\'){escaped=true;clean+=' ';continue}
    if(c===quote){mode='code';clean+=' ';continue}
    if(quote==='`'&&c==='$'&&n==='{'){templateDepth.push(depth);depth++;mode='code';clean+='  ';i++;continue}
    clean+=c==='\n'?'\n':' ';continue;
   }
   if(c==='/'&&n==='*'){mode='comment';clean+='  ';i++;continue}
   if(!css&&c==='/'&&n==='/'){mode='line';clean+='  ';i++;continue}
   if(!css&&c==='/'&&/(?:^|[=(:,!&|?;\[{]|\breturn)\s*$/.test(clean)){mode='regex';regexClass=false;clean+=' ';continue}
   if(c==='"'||c==="'"||(!css&&c==='`')){mode='string';quote=c;clean+=' ';continue}
   if(c==='{')depth++;
   if(c==='}'){
    depth--;
    if(templateDepth.length&&depth===templateDepth.at(-1)){templateDepth.pop();mode='string';quote='`'}
   }
   clean+=c;
  }
  return {mode,clean,quote};
 }

 /** HTML 仅在 style、script 和 style 属性内启用相应语言。 */
 function region(text,pos,path){
  const ext=path.split('.').pop().toLowerCase(),before=text.slice(0,pos);
  if(['js','mjs','cjs'].includes(ext))return {language:'js',start:0,text:before};
  if(ext==='css')return {language:'css',start:0,text:before};
  if(!['html','htm','svg'].includes(ext))return null;
  let match,block=null;const tags=/<\/?(style|script)\b[^>]*>|<!--[\s\S]*?(?:-->|$)/gi;
  while((match=tags.exec(before))){
   if(match[0].startsWith('<!--'))continue;
   if(!block&&!match[0].startsWith('</'))block={language:match[1].toLowerCase()==='style'?'css':'js',start:tags.lastIndex};
   else if(block&&match[0].startsWith('</')&&match[1].toLowerCase()===(block.language==='css'?'style':'script'))block=null;
  }
  if(block)return {...block,text:before.slice(block.start)};
  const tag=before.lastIndexOf('<');
  if(tag<0||before.lastIndexOf('>')>tag)return null;
  const attribute=/(?:^|\s)style\s*=\s*(["'])([^]*?)$/i.exec(before.slice(tag));
  if(attribute&&!attribute[2].includes(attribute[1]))return {language:'css',inline:true,start:pos-attribute[2].length,text:attribute[2]};
  return null;
 }

 function create(css,sview,environment=globalThis){
  const groups=new Map(), cache=new Map();
  /** 只枚举白名单原生对象的描述符，不触发 getter。 */
  function members(value,detail,inherit=true){
   const result=new Map();
   for(let level=0;value&&level<8;level++,value=inherit?Object.getPrototypeOf(value):null){
    if(value===Function.prototype)break;
    for(const [name,descriptor] of Object.entries(Object.getOwnPropertyDescriptors(value))){
     if(!/^[A-Za-z_$][\w$]*$/.test(name)||name==='constructor'||name.startsWith('__')||result.has(name))continue;
     const method=typeof descriptor.value==='function';
     result.set(name,item(name,detail+(method?'方法':'属性'),name,method?(signatures[name]||'…'):null));
    }
   }
   return [...result.values()];
  }
  for(const name of [...constructors,...webTypes]){
   const value=environment[name];if(!value)continue;
   groups.set(name,members(value,name+' · ',false));
   if(value.prototype)groups.set(name+'.prototype',members(value.prototype,name+' · '));
  }
  if(environment.Intl){
   for(const [name,descriptor] of Object.entries(Object.getOwnPropertyDescriptors(environment.Intl))){
    if(typeof descriptor.value==='function'){groups.set('Intl.'+name,members(descriptor.value,'Intl · ',false));if(descriptor.value.prototype)groups.set('Intl.'+name+'.prototype',members(descriptor.value.prototype,'Intl · '))}
   }
  }
  for(const name of words('console document navigator location history performance crypto')){
   if(environment[name])groups.set(name,members(environment[name],name+' · '));
  }
  for(const name of ['localStorage','sessionStorage'])groups.set(name,groups.get('Storage.prototype')||[]);
  groups.set('document.body',groups.get('HTMLElement.prototype')||[]);
  groups.set('document.documentElement',groups.get('HTMLElement.prototype')||[]);
  const globalItems=[...keywords.map(name=>item(name)),...constructors.map(name=>item(name,'标准对象')),...webTypes.map(name=>item(name,'浏览器对象')),...globals.map(name=>item(name,'浏览器接口',name,signatures[name]||null)),item('sview','S View 脚本接口')];
  for(const [name,insert,detail] of snippets)globalItems.push({...item(name+' (…)','代码片段 · '+detail,insert),match:name});
  for(const name of ['window','globalThis'])groups.set(name,globalItems.filter(entry=>entry.detail!=='关键词'&&!entry.detail.startsWith('代码片段')));
  for(const [path,entry] of Object.entries(sview)){
   const parts=path.split('.');
   for(let i=1;i<parts.length;i++){
    const group=parts.slice(0,i).join('.'),name=parts[i],last=i===parts.length-1;
    if(!last&&name.includes('('))continue;
    if(!groups.has(group))groups.set(group,[]);
    const list=groups.get(group);
    if(!list.some(x=>x.label===name))list.push(item(name,last?entry.detail:'S View 接口分组',name,last?entry.params:null));
   }
  }
  const requestGroup='sview.network.request()';
  if(!groups.has(requestGroup))groups.set(requestGroup,[]);
  for(const name of words('method query header headers ua cookie timeout response download'))groups.get(requestGroup).push(item(name,'请求配置',name,'value'));
  const cssNames=Object.keys(css.properties);
  const styleItems=cssNames.map(name=>item(name.replace(/-([a-z])/g,(_,c)=>c.toUpperCase()),'CSS 属性 · '+name));
  function identifiers(text){
   if(cache.has(text))return cache.get(text);
   const clean=lex(text).clean,names=[...new Set(clean.match(/\b[A-Za-z_$][\w$]*\b/g)||[])].slice(0,2000);
   const types=new Map();
   const declarations=/\b(?:const|let|var)\s+([\w$]+)\s*=\s*([^;\n]+)/g;let m;
   while((m=declarations.exec(text))){
    if(!/^(?:const|let|var)\b/.test(clean.slice(m.index)))continue;
    const expr=m[2].trim();let type;
    if(expr.startsWith('['))type='Array';else if(/^["'`]/.test(expr))type='String';
    else if(/^new\s+([\w$]+)/.test(expr))type=/^new\s+([\w$]+)/.exec(expr)[1];
    else if(/\b(?:querySelectorAll|getElementsBy\w+)\s*\(/.test(expr))type='NodeList';
    else if(/\b(?:querySelector|getElementById|createElement)\s*\(/.test(expr))type='HTMLElement';
    if(type)types.set(m[1],type);
   }
   const value={names,types};cache.clear();cache.set(text,value);return value;
  }
  function receiverItems(receiver,text){
   if(/(?:^|\.)style$/.test(receiver))return [...styleItems,...(groups.get('CSSStyleDeclaration.prototype')||[])];
   receiver=receiver.replace(/^(?:window|globalThis)\./,'');
   if(groups.has(receiver))return groups.get(receiver);
   const normalized=receiver.replace(/\([^()]*\)/g,'()');
   if(normalized.startsWith(requestGroup))return groups.get(requestGroup);
   if(/\.(?:querySelector|getElementById|createElement)\([^]*\)$/.test(receiver))return groups.get('HTMLElement.prototype')||[];
   if(/\.(?:querySelectorAll|getElementsBy\w+)\([^]*\)$/.test(receiver))return groups.get('NodeList.prototype')||[];
   if(/\.(?:classList)$/.test(receiver))return groups.get('DOMTokenList.prototype')||[];
   const type=identifiers(text).types.get(receiver);
   if(type)return groups.get(type+'.prototype')||[];
   if(/^['"`]/.test(receiver))return groups.get('String.prototype')||[];
   if(/^\[/.test(receiver))return groups.get('Array.prototype')||[];
   return [];
  }
  function cssValues(property,fullText,definition=css.properties[property]){
   const values=definition?.values||[];
   const list=[...values,...words('inherit initial unset revert revert-layer var()')];
   const syntax=definition?.syntax||'';
   if(/length|percentage|size|position/.test(syntax))list.push(...words('0 auto 100% 1px 1rem 1em 100vw 100vh 100dvh calc() min() max() clamp()'));
   if(/color/.test(syntax)||/color|background|border|shadow/.test(property))list.push(...words('transparent currentColor #000000 #ffffff rgb() rgba() hsl() hsla() oklch() color-mix()'));
   if(/image|background/.test(property))list.push(...words('url() linear-gradient() radial-gradient() conic-gradient()'));
   return [...new Set(list)].map(value=>item(value,'CSS 属性值'));
  }
  function cssContext(r,fullText){
   const scanned=lex(r.text,true);if(scanned.mode==='comment')return null;
   const text=r.text,clean=scanned.clean;
   const blocks=[];let boundary=0;
   for(let i=0;i<clean.length;i++){
    if(clean[i]==='{'){blocks.push(clean.slice(boundary,i).trim());boundary=i+1}
    else if(clean[i]==='}'){blocks.pop();boundary=i+1}
    else if(clean[i]===';')boundary=i+1;
   }
   const rule=(blocks.at(-1)||'').match(/^@[\w-]+/)?.[0];
   const properties=rule&&Object.keys(css.descriptors?.[rule]||{}).length?css.descriptors[rule]:css.properties;
   const declarationBlock=r.inline||(blocks.length&&!/^@(?:media|supports|container|layer|keyframes|scope|starting-style)\b/i.test(blocks.at(-1)));
   const variable=/var\(\s*(--[\w-]*)?$/.exec(text);
   if(variable)return {prefix:variable[1]||'',list:[...new Set(fullText.match(/--[A-Za-z_][\w-]*(?=\s*:)/g)||[])].map(name=>item(name,'当前文件的自定义属性'))};
   if(scanned.mode==='string')return null;
   const prefix=(text.match(/[\w@:-]*$/)||[''])[0];
   const tail=clean.slice(Math.max(clean.lastIndexOf('{'),clean.lastIndexOf(';'),clean.lastIndexOf('}'))+1);
   const declaration=/^\s*([\w-]+)\s*:\s*([^]*)$/.exec(tail);
   if(declaration&&declarationBlock){
    const valuePrefix=(text.match(/[\w#%-]*$/)||[''])[0];
    const unit=/\d([a-zA-Z]+)$/.exec(valuePrefix);
    if(unit)return {prefix:unit[1],list:css.units.map(name=>item(name,'CSS 单位'))};
    return {prefix:valuePrefix,list:cssValues(declaration[1],fullText,properties[declaration[1]])};
   }
   if(prefix.startsWith('@'))return {prefix,list:css.rules.map(name=>item(name,'CSS 规则'))};
   if(declarationBlock)return {prefix,list:Object.keys(properties).map(name=>item(name,properties[name].syntax,name+': ',null))};
   const pseudo=(text.match(/:{1,2}[\w-]*$/)||[])[0];
   if(pseudo)return {prefix:pseudo,list:css.selectors.filter(name=>name.startsWith(':')).map(name=>item(name.replace(/\([^]*$/,'()'),'CSS 选择器'))};
   return {prefix,list:[...css.rules.map(name=>item(name,'CSS 规则')),...words('html body div span a p button input textarea select form label main section article header footer nav ul ol li img video canvas svg h1 h2 h3 table tr td th').map(name=>item(name,'元素选择器'))]};
  }
  /** 候选只覆盖当前词前缀；列表有界，长文件禁用自动分析。 */
  function suggest(text,pos,path,manual=false){
   if(text.length>200000||pos>text.length)return null;
   const r=region(text,pos,path);if(!r)return null;
   let context;
   if(r.language==='css')context=cssContext(r,text);
   else {
    const scanned=lex(r.text),prefix=(r.text.match(/[\w$]*$/)||[''])[0];
    if(scanned.mode==='string'){
     const style=/\.style\.([\w]+)\s*=\s*['"]([\w-]*)$/.exec(r.text);
     const event=/\b(?:addEventListener|removeEventListener)\(\s*['"]([\w]*)$/.exec(r.text);
     if(style)context={prefix:style[2],list:cssValues(style[1].replace(/[A-Z]/g,c=>'-'+c.toLowerCase()),text)};
     else if(event)context={prefix:event[1],list:events.map(name=>item(name,'浏览器事件'))};
     else return null;
    }else if(scanned.mode!=='code')return null;
    else {
     const head=r.text.slice(0,r.text.length-prefix.length);
     if(head.endsWith('.')){
      const receiver=(head.slice(0,-1).match(/(?:[\w$]+(?:\.[\w$]+|\([^;\n]*?\))*)$|(?:\[[^\n]*\]|['"][^\n]*['"])$/)||[''])[0];
      context={prefix,list:receiverItems(receiver,text)};
     }else context={prefix,list:[...globalItems,...identifiers(text).names.map(name=>item(name,'当前文件的标识符'))]};
    }
   }
   if(!context||(!manual&&!context.prefix&&!/[.:]$/.test(r.text)))return null;
   const prefix=context.prefix.toLowerCase(),seen=new Set();
   const list=context.list.filter(entry=>{
    if(!(entry.match||entry.label).toLowerCase().startsWith(prefix)||seen.has(entry.label))return false;
    seen.add(entry.label);return true;
   }).sort((a,b)=>Number(b.label.startsWith(context.prefix))-Number(a.label.startsWith(context.prefix))||a.label.localeCompare(b.label)).slice(0,80);
   return list.length?{start:pos-context.prefix.length,end:pos,items:list}:null;
  }
  /** 括号和冒号已有时不重复插入；片段的竖线仅用作光标标记。 */
  function insertion(entry,following=''){
   let text=entry.insert,caret;
   if(entry.params!==null&&!text.includes('(')&&!/^\s*\(/.test(following))text+='()';
   if(text.endsWith(': ')&&/^\s*:/.test(following))text=text.slice(0,-2);
   if(text.endsWith('()')&&following.startsWith('('))text=text.slice(0,-2);
   const marker=text.indexOf('|');
   if(marker>=0){caret=marker;text=text.slice(0,marker)+text.slice(marker+1)}
   else caret=text.endsWith('()')?text.length-1:text.length;
   return {text,caret};
  }
  return {suggest,insertion};
 }
 return {create,region,lex};
})();
