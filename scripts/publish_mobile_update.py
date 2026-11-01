#!/usr/bin/env python3
"""Publish a built versioned mobile APK to the paired-device update channel."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]
RELEASE_ROOT = Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'jarvis' / 'mobile-updates'


def publish(build_dir, release_root, notes=''):
    metadata = json.loads((build_dir / 'output-metadata.json').read_text())
    if metadata['applicationId'] != 'com.jarvis.os' or len(metadata['elements']) != 1:
        raise ValueError('Publish one JARVIS APK only')
    element = metadata['elements'][0]
    name = element['outputFile']
    if Path(name).name != name or not name.endswith('.apk'):
        raise ValueError('Invalid APK path')
    apk = build_dir / name
    if not apk.is_file() or not 0 < apk.stat().st_size <= 200_000_000:
        raise ValueError('APK missing or too large')
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    version = int(element['versionCode'])
    release_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    current = release_root / 'current.json'
    if current.exists():
        previous = json.loads(current.read_text())
        if version < previous['version_code'] or (version == previous['version_code'] and previous['sha256'] != digest):
            raise ValueError('Increment Android versionCode before publishing a changed APK')
    filename = f'jarvis-{version}-{digest[:12]}.apk'
    destination = release_root / filename
    if not destination.exists():
        fd, temporary = tempfile.mkstemp(prefix='.apk-', dir=release_root)
        os.close(fd)
        try:
            shutil.copyfile(apk, temporary)
            Path(temporary).chmod(0o600)
            os.replace(temporary, destination)
        finally:
            Path(temporary).unlink(missing_ok=True)
    data = {'package': 'com.jarvis.os', 'version_code': version, 'version_name': element['versionName'],
            'sha256': digest, 'size': apk.stat().st_size, 'file': filename, 'notes': notes,
            'apk_path': '/v1/mobile-release/apk'}
    fd, temporary = tempfile.mkstemp(prefix='.release-', dir=release_root)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream)
        os.replace(temporary, current)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, default=ROOT / 'mobile/app/build/outputs/apk/debug')
    parser.add_argument('--release-root', type=Path, default=RELEASE_ROOT)
    parser.add_argument('--notes', default='New JARVIS mobile features and fixes')
    args = parser.parse_args()
    result = publish(args.build_dir, args.release_root, args.notes)
    print(f"JARVIS {result['version_name']} published to the authenticated phone update channel.")


if __name__ == '__main__':
    main()
