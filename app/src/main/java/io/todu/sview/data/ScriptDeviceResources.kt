package io.todu.sview.data

/** 设备路径只在 img 上映射；链接保留原始路径，由点击事件交给原生打开。 */
object ScriptDeviceResources {
    const val SCRIPT = """
        const deviceOrigin = location.origin;
        function deviceImageUrl(value) {
            const path = String(value);
            return path.startsWith('device:/') ? deviceOrigin + '/__sview_device?path=' + encodeURIComponent(path) : value;
        }
        const imageSrc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
        Object.defineProperty(HTMLImageElement.prototype, 'src', {
            ...imageSrc, set(value) { imageSrc.set.call(this, deviceImageUrl(value)); }
        });
        const originalAttribute = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function(name, value) {
            if (this instanceof HTMLImageElement && String(name).toLowerCase() === 'src') value = deviceImageUrl(value);
            return originalAttribute.call(this, name, value);
        };
        function mapDeviceImages(root) {
            const map = image => {
                const path = image.getAttribute('src');
                if (path && path.startsWith('device:/')) image.setAttribute('src', path);
            };
            if (root instanceof HTMLImageElement) map(root);
            if (root.querySelectorAll) root.querySelectorAll('img[src]').forEach(map);
        }
        const deviceObserver = new MutationObserver(records => {
            const roots = new Set();
            for (const record of records) {
                if (record.type === 'attributes') {
                    // 属性变化只检查当前图片，不重复搜索其子树。
                    if (record.target instanceof HTMLImageElement) {
                        const path = record.target.getAttribute('src');
                        if (path && path.startsWith('device:/')) record.target.setAttribute('src', path);
                    }
                } else for (const node of record.addedNodes) {
                    if (node.nodeType === 1 && node.isConnected) roots.add(node);
                }
            }
            for (const root of roots) {
                // 父节点已覆盖的新增子树只扫描一次，保留 innerHTML 的图片映射。
                let parent = root.parentNode;
                while (parent && !roots.has(parent)) parent = parent.parentNode;
                if (!parent) mapDeviceImages(root);
            }
        });
        deviceObserver.observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] });
        mapDeviceImages(document);
        document.addEventListener('click', event => {
            if (event.defaultPrevented || event.button !== 0) return;
            const anchor = event.composedPath().find(node => node instanceof HTMLAnchorElement);
            const path = anchor && anchor.getAttribute('href');
            if (!path || !path.startsWith('device:/')) return;
            event.preventDefault();
            call('resource.openDevice', { path }).catch(error => reportInterfaceError(error, 'resource.openDevice', 'interface'));
        });
        addEventListener('pagehide', () => deviceObserver.disconnect());
        addEventListener('pageshow', event => {
            if (event.persisted) {
                deviceObserver.observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] });
                mapDeviceImages(document);
            }
        });
    """
}
