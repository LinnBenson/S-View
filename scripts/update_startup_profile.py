#!/usr/bin/env python3
"""将正式包首页采样转换为源码名称的启动规则；输入必须来自同一次构建。"""
import argparse
import re
from pathlib import Path


def descriptor(name):
    """把 R8 映射中的 Java 类型转换为 ART 描述符。"""
    dimensions = 0
    while name.endswith('[]'):
        dimensions += 1
        name = name[:-2]
    primitive = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C',
                 'short': 'S', 'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}
    return '[' * dimensions + primitive.get(name, 'L' + name.replace('.', '/') + ';')


def generate(profile, mapping):
    """仅收集应用首页路径，不把框架已有基线或网页引擎加入启动布局。"""
    classes = {}
    methods = {}
    current = None
    for line in mapping.splitlines():
        match = re.match(r'^(\S+) -> (\S+):$', line)
        if match:
            original, current = match.groups()
            classes.setdefault(current, set()).add(original)
            continue
        match = re.match(r'^\s+(?:\d+:\d+:)?(\S+) ([^ (]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)$', line)
        if match and current:
            result, name, arguments, short = match.groups()
            # 内联来源有独立限定名，不能当作当前类的方法。
            if '.' in name:
                continue
            signature = name + '(' + ''.join(descriptor(a) for a in arguments.split(',') if a) + ')' + descriptor(result)
            methods.setdefault((current, original, short), set()).add(signature)
    rules = set()
    for line in profile.splitlines():
        match = re.match(r'^[HSP]*L([^;]+);(?:->([^ (]+)\(.*)?$', line)
        if not match:
            continue
        short, method = match.groups()
        short = short.replace('/', '.')
        for original in classes.get(short, {short}):
            allowed = original == 'io.todu.sview.MainActivity' or any(
                original.startswith(prefix) for prefix in [
                    'io.todu.sview.data.', 'io.todu.sview.ui.theme.',
                    'io.todu.sview.ui.screens.HomeScreenKt',
                    'io.todu.sview.ui.components.ComponentsKt',
                    'io.todu.sview.ui.components.ProjectIcon',
                    'io.todu.sview.ui.components.Reorder'])
            if not allowed or '$$' in original:
                continue
            owner = descriptor(original)
            rules.add(owner)
            if method:
                for signature in methods.get((short, original, method), ()):
                    rules.add('HSP' + owner + '->' + signature)
    return sorted(rules)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rules = generate(args.profile.read_text(), args.mapping.read_text())
    if not rules:
        raise SystemExit('未找到应用启动规则，请检查采样与映射文件')
    args.output.write_text('# 正式包首页采样，经 R8 映射还原；更新方式见架构文档。\n' + '\n'.join(rules) + '\n')
    args.output.with_name('startup-prof.txt').write_text(args.output.read_text())
    print(f'生成 {len(rules)} 条规则')


if __name__ == '__main__':
    main()
