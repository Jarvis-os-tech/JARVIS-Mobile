#!/usr/bin/env python3
"""Publish a verified mobile build and switch the internet update channel last."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

from publish_mobile_update import ROOT, publish

REPOSITORY = 'Jarvis-os-tech/JARVIS-Mobile'


def run(*args, check=True):
    return subprocess.run(['gh', *args], check=check, capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, default=ROOT / 'mobile/app/build/outputs/apk/debug')
    parser.add_argument('--notes', default='Internet updates without a PC connection.')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='jarvis-release-') as directory:
        staging = Path(directory)
        release = publish(args.build_dir, staging, args.notes)
        tag = f"mobile-v{release['version_code']}"
        if run('release', 'view', tag, '--repo', REPOSITORY, check=False).returncode == 0:
            raise SystemExit('This version is already published. Increase versionCode before publishing again.')
        apk = staging / release.pop('file')
        release.pop('apk_path')
        release['apk_url'] = f'https://github.com/{REPOSITORY}/releases/download/{tag}/{apk.name}'
        manifest = staging / 'current.json'
        manifest.write_text(json.dumps(release, indent=2) + '\n')
        notes = staging / 'notes.md'
        notes.write_text(args.notes + '\n\nInstall the APK, then use Update JARVIS for future updates. Android asks you to approve installation.\n')
        # The immutable APK must exist before the stable manifest points to it.
        run('release', 'create', tag, str(apk), '--repo', REPOSITORY,
            '--title', f"JARVIS Mobile {release['version_name']}", '--notes-file', str(notes))
        if run('release', 'view', 'mobile-stable', '--repo', REPOSITORY, check=False).returncode:
            run('release', 'create', 'mobile-stable', '--repo', REPOSITORY,
                '--title', 'JARVIS Mobile update channel', '--notes', 'Latest verified mobile update manifest.', '--prerelease')
        run('release', 'upload', 'mobile-stable', str(manifest), '--clobber', '--repo', REPOSITORY)
        print(f"Published JARVIS Mobile {release['version_name']}: {release['apk_url']}")


if __name__ == '__main__':
    main()
