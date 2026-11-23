/**
 * 判断字符串是否为 JSON
 * 判断传入值是否为合法的 JSON 对象或 JSON 数组字符串。
 * @param {unknown} value 待判断的值
 * @returns {boolean} 是否为合法 JSON 字符串
 */
function is_json( value ) {
    if ( typeof value !== 'string' ) { return false; }
    const content = value.trim();
    if ( content === '' || !['{', '['].includes( content[0] ) ) { return false; }
    try {
        JSON.parse( content );
        return true;
    }catch ( error ) {
        return false;
    }
}
/**
 * 判断值是否为空
 * 支持空字符串、零值、布尔值、数组、普通对象、Map 及 Set。
 * @param {unknown} value 待判断的值
 * @returns {boolean} 是否为空
 */
function empty( value ) {
    if ( value === undefined || value === null || value === false ) { return true; }
    if ( value === 0 || value === 0n || value === '' || value === '0' ) { return true; }
    if ( Array.isArray( value ) ) { return value.length === 0; }
    if ( value instanceof Map || value instanceof Set ) { return value.size === 0; }
    if ( Object.prototype.toString.call( value ) === '[object Object]' ) {
        return Object.keys( value ).length === 0;
    }
    return false;
}
/**
 * 生成 UUID
 * 优先使用浏览器原生 UUID 方法，否则生成符合 RFC 4122 格式的 UUID v4。
 * @returns {string} UUID 字符串
 */
function uuid() {
    if ( typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function' ) {
        return crypto.randomUUID();
    }
    const bytes = new Uint8Array( 16 );
    if ( typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function' ) {
        crypto.getRandomValues( bytes );
    }else {
        for ( let index = 0; index < bytes.length; index++ ) {
            bytes[index] = Math.floor( Math.random() * 256 );
        }
    }
    bytes[6] = ( bytes[6] & 0x0f ) | 0x40;
    bytes[8] = ( bytes[8] & 0x3f ) | 0x80;
    const value = Array.from( bytes, ( byte ) => byte.toString( 16 ).padStart( 2, '0' ) ).join( '' );
    return `${value.slice( 0, 8 )}-${value.slice( 8, 12 )}-${value.slice( 12, 16 )}-${value.slice( 16, 20 )}-${value.slice( 20 )}`;
}
/**
 * 判断字符串是否为 UUID
 * 判断传入值是否符合标准的 8-4-4-4-12 UUID 格式。
 * @param {unknown} value 待判断的值
 * @returns {boolean} 是否为 UUID 字符串
 */
function is_uuid( value ) {
    if ( typeof value !== 'string' ) { return false; }
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test( value );
}
/**
 * 获取本地存储数据
 * 读取并还原通过 storageSet 保存的数据，存储不可用或键不存在时返回默认值。
 * @param {string} key 存储键名
 * @param {unknown} defaultValue 键不存在时返回的默认值
 * @returns {unknown} 已保存的数据或默认值
 */
function storageGet( key, defaultValue = null ) {
    if ( typeof key !== 'string' || key === '' ) { return defaultValue; }
    let value = null;
    try {
        value = localStorage.getItem( key );
    }catch ( error ) {
        return defaultValue;
    }
    if ( value === null ) { return defaultValue; }
    const prefix = '__core_json__:';
    if ( value.startsWith( prefix ) ) {
        try {
            return JSON.parse( value.slice( prefix.length ) );
        }catch ( error ) {
            return defaultValue;
        }
    }
    return value;
}
/**
 * 保存本地存储数据
 * 所有值统一使用带标识的 JSON 保存，字符串不会被误判为对象或内部标识。
 * @param {string} key 存储键名
 * @param {unknown} value 待保存的数据
 * @returns {boolean} 是否成功写入持久化存储
 */
function storageSet( key, value ) {
    if ( typeof key !== 'string' || key === '' ) { return false; }
    if ( ['undefined', 'function', 'symbol'].includes( typeof value ) ) { return false; }
    let storageValue;
    try {
        // toJSON 可能返回 undefined；此时不能写入不可还原的数据。
        const serialized = JSON.stringify( value );
        if ( serialized === undefined ) { return false; }
        storageValue = `__core_json__:${serialized}`;
    }catch ( error ) {
        return false;
    }
    try {
        localStorage.setItem( key, storageValue );
        return true;
    }catch ( error ) {
        return false;
    }
}
/**
 * 删除本地存储数据
 * 根据键名清除对应的持久化存储数据。
 * @param {string} key 存储键名
 * @returns {boolean} 是否成功从持久化存储删除
 */
function storageRemove( key ) {
    if ( typeof key !== 'string' || key === '' ) { return false; }
    try {
        localStorage.removeItem( key );
        return true;
    }catch ( error ) {
        return false;
    }
}
/**
 * 设置或插入元素内容
 * @param {string} selector 选择器
 * @param {string} html HTML 内容
 * @param {'all'|'top'|'bottom'} mode 填充方式
 */
function content( selector, html, mode = 'all' ) {
    if ( !['all', 'top', 'bottom'].includes( mode ) ) {
        throw new Error( '不支持的填充方式' );
    }
    document.querySelectorAll( selector ).forEach( element => {
        if ( mode === 'top' ) {
            element.insertAdjacentHTML( 'afterbegin', html );
        }else if (mode === 'bottom') {
            element.insertAdjacentHTML( 'beforeend', html );
        }else {
            element.innerHTML = html;
        }
    });
    if ( typeof globalThis.lucide?.createIcons === 'function' ) {
        globalThis.lucide.createIcons();
    }
}
/**
 * 判断是否有匹配元素包含指定类名
 * @returns {boolean} 任意一个包含则返回 true
 */
function hasClass( selector, className ) {
    return Array.from( document.querySelectorAll( selector ) )
        .some( element => element.classList.contains( className ));
}
/**
 * 替换全部类名，传入空字符串可清空
 * @param {string} selector 选择器
 * @param {string} classNames 类名字符串
 */
function setClass( selector, classNames ) {
    document.querySelectorAll( selector ).forEach( element => {
        element.setAttribute( 'class', classNames );
    });
}
/**
 * 添加类名，多个类名用空格分隔
 * @param {string} selector 选择器
 * @param {string} classNames 类名字符串
 */
function addClass( selector, classNames ) {
    const names = classNames.trim().split( /\s+/ ).filter( Boolean );
    document.querySelectorAll( selector ).forEach( element => {
        element.classList.add( ...names );
    });
}
/**
 * 移除指定类名，多个类名用空格分隔
 * @param {string} selector 选择器
 * @param {string} classNames 类名字符串
 */
function removeClass( selector, classNames ) {
    const names = classNames.trim().split( /\s+/ ).filter( Boolean );
    document.querySelectorAll( selector ).forEach( element => {
        element.classList.remove( ...names );
    });
}
/**
 * 设置元素样式
 * @param {string} selector 选择器
 * @param {string} property 样式属性
 * @param {string|null} value 样式值，传入 null 可移除
 */
function setStyle( selector, property, value ) {
    document.querySelectorAll( selector ).forEach( element => {
        if ( value === null ) {
            element.style.removeProperty( property );
        } else {
            element.style.setProperty( property, String( value ) );
        }
    });
}
/**
 * 设置元素属性
 * @param {string} selector 选择器
 * @param {string} name 属性名
 * @param {string|null} value 属性值，传入 null 可移除
 */
function setAttr( selector, name, value ) {
    document.querySelectorAll( selector ).forEach( element => {
        if ( value === null ) {
            element.removeAttribute( name );
        } else {
            element.setAttribute( name, String( value ) );
        }
    });
}
/**
 * 移除元素
 * @param {string} selector 选择器
 */
function remove( selector ) {
    document.querySelectorAll( selector ).forEach( element => {
        element.remove();
    });
}
/**
 * 获取第一个匹配元素的内部 HTML
 * @returns {string|null} 未找到元素时返回 null
 */
function getHtml( selector ) {
    const element = document.querySelector( selector );
    return element ? element.innerHTML : null;
}
/**
 * 滚动元素到底部
 * @param {string} selector 选择器
 */
function scrollBottom( selector ) {
    document.querySelectorAll( selector ).forEach( element => {
        const box = element === document.body || element === document.documentElement ? document.scrollingElement : element;
        box?.scrollTo({
            top: box.scrollHeight,
            behavior: 'smooth'
        });
    });
}
/**
 * 滚动元素到顶部
 * @param {string} selector 选择器
 */
function scrollTop( selector ) {
    document.querySelectorAll( selector ).forEach( element => {
        const box = element === document.body || element === document.documentElement ? document.scrollingElement : element;
        box?.scrollTo({
            top: 0,
            behavior: 'smooth'
        });
    });
}
