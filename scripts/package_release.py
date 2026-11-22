#!/usr/bin/env python3
"""对已构建的双架构 APK 正式签名，并生成仅含 APK 与校验和的压缩包。"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import zipfile


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1]
    tools = sorted((args.sdk / 'build-tools').iterdir(), key=lambda p: tuple(int(x) for x in p.name.split('.') if x.isdigit()))[-1]
    destination = args.output / f'S-View-{args.version}'
    destination.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for architecture in ['arm64-v8a', 'armeabi-v7a']:
        apk = source / 'app/build/outputs/apk/release' / f'app-{architecture}-release-unsigned.apk'
        # 能力声明由 Play 服务动态读取，正式包裁剪后仍必须保留两端的值。
        resources = subprocess.run([str(tools / 'aapt2'), 'dump', 'resources', str(apk)],
                                   check=True, capture_output=True, text=True).stdout
        for capability in ['android_wear_capabilities', 'sview_phone_v1', 'sview_watch_v1']:
            if capability not in resources:
                raise RuntimeError(f'安装包缺少手表连接资源：{capability}')
        output = destination / f'S-View-{args.version}-{architecture}.apk'
        subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', str(args.keystore), '--ks-key-alias', args.alias,
                        '--ks-pass', f'file:{args.password_file}', '--v4-signing-enabled', 'false', '--out', str(output), str(apk)], check=True)
        subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(output)], check=True)
        artifacts.append(output)
    checksums = destination / 'SHA256SUMS.txt'
    checksums.write_text(''.join(f'{digest(path)}  {path.name}\n' for path in artifacts))
    with zipfile.ZipFile(args.output / f'S-View-{args.version}.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in [checksums, *artifacts]:
            archive.write(path, path.name)
    print(f'已输出：{destination}')


if __name__ == '__main__':
    main()
