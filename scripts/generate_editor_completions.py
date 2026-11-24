#!/usr/bin/env python3
"""从 MDN 数据和项目接口帮助生成离线补全字典，不引入运行时依赖。"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/src/main/assets/lan-editor'


def generate_css(folder):
    """递归展开 CSS 值语法中的关键词，保留属性语法供候选详情展示。"""
    def read(name):
        return json.loads((folder / 'css' / (name + '.json')).read_text())
    properties, syntaxes = read('properties'), read('syntaxes')
    def values(syntax, seen=None):
        seen = set() if seen is None else seen
        result = set()
        for ref in re.findall(r'<([^>]+)>', syntax):
            name = ref.split(' [')[0].strip("'")
            if name.endswith('()'):
                result.add(name)
                continue
            if name not in seen:
                target = syntaxes.get(name) or properties.get(name)
                if target:
                    result.update(values(target['syntax'], seen | {name}))
        text = re.sub(r'<[^>]+>', '', syntax)
        result.update(re.findall(r'(?<![\w-])[a-zA-Z][a-zA-Z0-9-]*(?:\(\))?', re.sub(r'([\w-]+)\([^)]*\)', r'\1()', text)))
        return sorted(result)
    result = {}
    for name, prop in properties.items():
        if prop.get('status') == 'obsolete' or name.startswith('-'):
            continue
        result[name] = {'syntax': prop['syntax'], 'values': values(prop['syntax'])}
    descriptors = {rule: {name: {'syntax': value['syntax'], 'values': values(value['syntax'])}
                              for name, value in entry.get('descriptors', {}).items() if value.get('status') != 'obsolete'}
                       for rule, entry in read('at-rules').items()}
    data = {'descriptors': descriptors, 'properties': result, 'selectors': list(read('selectors')), 'rules': list(read('at-rules')), 'units': list(read('units'))}
    (DEST / 'completion-css.json').write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n')
    (DEST / 'LICENSE-MDN-DATA.txt').write_bytes((folder / 'LICENSE').read_bytes())
    commit = (folder / 'commit.txt').read_text().strip()
    (DEST / '补全数据来源.md').write_text(f'# 补全数据来源\n\nCSS 数据来自 https://github.com/mdn/data ，版本 {commit}，遵循同目录 LICENSE-MDN-DATA.txt 中的 CC0 许可。\n\n仅保留属性语法、值关键词、选择器、规则和单位，排除已废弃和厂商前缀属性。再生成时运行 `python3 scripts/generate_editor_completions.py --mdn <mdn-data目录>`；目录需包含 css 数据、LICENSE 和记录提交号的 commit.txt。\n\nS View 候选来自应用内接口帮助；不指定 --mdn 时仅更新 S View 字典。\n')
    print('CSS 属性：', len(result))


def generate_sview():
    """复用接口名称、简述和参数名；处理帮助中共用模板的两组接口。"""
    entries = {}
    for path in (ROOT / 'app/src/main/java/io/todu/sview/ui/screens').glob('*Help*.kt'):
        text = path.read_text()
        matches = list(re.finditer(r'name\s*=\s*"(sview\.[\w.()]+)"', text))
        for index, match in enumerate(matches):
            block = text[match.end():matches[index+1].start() if index+1 < len(matches) else len(text)]
            brief = re.search(r'brief\s*=\s*"([^"\n]*)"', block)
            params = re.findall(r'InterfaceParameter\("([^"\n]+)"', block.split('resultType')[0])
            names = list(dict.fromkeys(p.split('.')[0] for p in params if '(' not in p and ' / ' not in p))
            entries[match[1]] = {'detail': brief[1] if brief else '脚本接口', 'params': ', '.join(names)}
        if path.name == 'StorageHelp.kt':
            matches = list(re.finditer(r'storageEntry\(\s*"(\w+)"\s*,\s*"([^"]+)"', text))
            for index, match in enumerate(matches):
                block = text[match.end():matches[index+1].start() if index+1 < len(matches) else len(text)]
                params = ['path'] + re.findall(r'InterfaceParameter\("([^"]+)"', block)
                entries['sview.storage.' + match[1]] = {'detail': match[2], 'params': ', '.join(params)}
    # 共用帮助模板的接口名称由 Kotlin 条件表达式生成。
    for name, detail in [('chatServer', '启动聊天服务器'), ('uploadServer', '启动上传服务器')]:
        entries['sview.network.' + name] = {'detail': detail, 'params': 'options'}
    (DEST / 'completion-sview.json').write_text(json.dumps(entries, ensure_ascii=False, separators=(',', ':')) + '\n')
    print('S View 接口：', len(entries))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mdn', type=Path)
    args = parser.parse_args()
    if args.mdn:
        generate_css(args.mdn)
    generate_sview()
