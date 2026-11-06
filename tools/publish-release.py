#!/usr/bin/env python3
"""Publish a prepared HongguoTV release, generate OTA update.json, and advance the Downloader bootstrap entry.
Run with --publish to execute on GitHub, or without --publish to validate and generate local release metadata.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

REPO = 'Lxontw/hongguoTV'
BOOTSTRAP_TAG = 'v0.9.0'
BOOTSTRAP_ASSET = 'HongguoTV.apk'
EXPECTED_PACKAGE = 'com.hongguotv.nativeapp'
EXPECTED_CERT_SHA256 = '99eb01056b352a8ee5ee9cc15cd31ad9cec8ade44bf2b28f58632eaec5805490'


def run(*arguments):
    return subprocess.check_output(arguments, text=True).strip()


def gh(*arguments):
    return run('gh', *arguments)


def find_build_tool(tool_name: str) -> str:
    found = shutil.which(tool_name)
    if found:
        return found
    sdk_candidates = [
        os.environ.get('ANDROID_HOME'),
        os.environ.get('ANDROID_SDK_ROOT'),
        str(Path.home() / 'Android' / 'Sdk'),
        str(Path.home() / '.cache' / 'hongguotv' / 'android-sdk'),
    ]
    for sdk in filter(None, sdk_candidates):
        bt_dir = Path(sdk) / 'build-tools'
        if bt_dir.is_dir():
            for version_dir in sorted(bt_dir.iterdir(), reverse=True):
                candidate = version_dir / tool_name
                if candidate.is_file() and os.access(candidate, os.X_OK):
                    return str(candidate)
    raise RuntimeError(f"Cannot find Android SDK build-tool '{tool_name}'. Please set ANDROID_HOME.")


def inspect_apk(apk_path: Path, expected_cert: str) -> dict:
    apksigner = find_build_tool('apksigner')
    aapt = find_build_tool('aapt')

    cert_out = run(apksigner, 'verify', '--print-certs', str(apk_path))
    cert_matches = re.findall(r'SHA-256 digest:\s*([0-9a-fA-F]{64})', cert_out)
    if not cert_matches:
        raise RuntimeError(f"Could not extract certificate SHA-256 from {apk_path}")
    cert_sha256 = cert_matches[0].lower()
    if expected_cert and cert_sha256 != expected_cert.lower():
        raise RuntimeError(
            f"APK signature mismatch!\n"
            f"  Expected SHA-256: {expected_cert.lower()}\n"
            f"  Actual SHA-256:   {cert_sha256}\n"
            f"Mismatched signature will break in-app OTA updates on TV."
        )

    badging = run(aapt, 'dump', 'badging', str(apk_path))
    pkg_match = re.search(
        r"package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']+)'",
        badging,
    )
    if not pkg_match:
        raise RuntimeError(f"Could not parse package info from aapt badging of {apk_path}")
    sdk_match = re.search(r"sdkVersion:'(\d+)'", badging)
    if not sdk_match:
        raise RuntimeError(f"Could not parse minSdkVersion from aapt badging of {apk_path}")

    return {
        'packageName': pkg_match.group(1),
        'versionCode': int(pkg_match.group(2)),
        'versionName': pkg_match.group(3),
        'minSdk': int(sdk_match.group(1)),
        'certSha256': cert_sha256,
    }


def main():
    parser = argparse.ArgumentParser(
        description="Validate APK signature, generate OTA update.json, and publish HongguoTV release."
    )
    parser.add_argument('--apk', required=True, type=Path, help="Path to the signed APK file")
    parser.add_argument('--version', default=None, help="Expected version name (optional; verified against APK)")
    parser.add_argument('--notes', required=True, type=Path, help="Path to release notes markdown")
    parser.add_argument('--expected-cert', default=EXPECTED_CERT_SHA256, help="Expected signer SHA-256 hex digest")
    parser.add_argument('--publish', action='store_true', help="Perform actual publish on GitHub")
    args = parser.parse_args()

    apk_path = args.apk.resolve()
    notes_path = args.notes.resolve()
    assert apk_path.is_file(), f"APK file not found: {apk_path}"
    assert notes_path.is_file(), f"Notes file not found: {notes_path}"

    meta = inspect_apk(apk_path, args.expected_cert)
    assert meta['packageName'] == EXPECTED_PACKAGE, (
        f"Unexpected packageName: {meta['packageName']} (expected {EXPECTED_PACKAGE})"
    )
    version = meta['versionName']
    if args.version and args.version != version:
        raise RuntimeError(f"--version ({args.version}) does not match APK versionName ({version})")

    tag = f"v{version}"
    assert re.fullmatch(r'(?:kotlin-)?v[0-9][0-9A-Za-z.+-]{0,80}', tag), f"Invalid release tag: {tag}"

    out_dir = apk_path.parent
    ota_apk_name = f"hongguotv-kotlin-{version}-android8.apk"
    ota_apk_path = out_dir / ota_apk_name
    bootstrap_apk_path = out_dir / BOOTSTRAP_ASSET

    if apk_path != ota_apk_path:
        shutil.copy2(apk_path, ota_apk_path)
    if apk_path != bootstrap_apk_path:
        shutil.copy2(apk_path, bootstrap_apk_path)

    apk_bytes = ota_apk_path.read_bytes()
    sha256 = hashlib.sha256(apk_bytes).hexdigest()
    size = len(apk_bytes)
    notes_text = notes_path.read_text(encoding='utf-8').strip()

    update_manifest = {
        'schema': 1,
        'packageName': meta['packageName'],
        'versionCode': meta['versionCode'],
        'versionName': version,
        'minSdk': meta['minSdk'],
        'tag': tag,
        'apk': {
            'name': ota_apk_name,
            'size': size,
            'sha256': sha256,
        },
        'notes': notes_text[:1500],
    }
    update_json_path = out_dir / 'update.json'
    update_json_path.write_text(json.dumps(update_manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    checksum_files = [bootstrap_apk_path, ota_apk_path, update_json_path]
    sha256sums_path = out_dir / 'SHA256SUMS'
    sha256sums_path.write_text(
        ''.join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in checksum_files),
        encoding='utf-8',
    )

    print(f"Target Repo:      {REPO}")
    print(f"Release Tag:      {tag}")
    print(f"Package Name:     {meta['packageName']}")
    print(f"Version:          {version} (versionCode={meta['versionCode']}, minSdk={meta['minSdk']})")
    print(f"Signer SHA-256:   {meta['certSha256']} (VERIFIED)")
    print(f"OTA APK Asset:    {ota_apk_path.name} ({size:,} bytes)")
    print(f"Bootstrap Asset:  {bootstrap_apk_path.name}")
    print(f"APK SHA-256:      {sha256}")
    print(f"Generated:        {update_json_path}")
    print(f"Generated:        {sha256sums_path}")

    if not args.publish:
        print("\nDry-run & local artifact generation complete. Run with --publish to publish to GitHub.")
        return

    existing = None
    try:
        existing = gh('release', 'view', tag, '--repo', REPO)
    except subprocess.CalledProcessError:
        pass

    assets = [str(bootstrap_apk_path), str(ota_apk_path), str(update_json_path), str(sha256sums_path)]
    if not existing:
        print(f"\nCreating release {tag}...")
        gh('release', 'create', tag,
           *assets,
           '--repo', REPO,
           '--title', f'HongguoTV v{version} (Kotlin TV)',
           '--notes-file', str(notes_path),
           '--latest')
    else:
        print(f"\nRelease {tag} already exists. Uploading assets and marking as latest...")
        gh('release', 'upload', tag, *assets, '--repo', REPO, '--clobber')
        gh('release', 'edit', tag, '--repo', REPO, '--latest', '--notes-file', str(notes_path))

    if tag != BOOTSTRAP_TAG:
        print(f"Advancing Downloader bootstrap asset in {BOOTSTRAP_TAG}...")
        gh('release', 'upload', BOOTSTRAP_TAG, f"{str(bootstrap_apk_path)}#{BOOTSTRAP_ASSET}", '--repo', REPO, '--clobber')

    print("\nRelease publish successfully finished!")
    print(f"Release URL:   https://github.com/{REPO}/releases/tag/{tag}")
    print(f"OTA JSON URL:  https://github.com/{REPO}/releases/latest/download/update.json")
    print(f"Bootstrap URL: https://github.com/{REPO}/releases/download/{BOOTSTRAP_TAG}/{BOOTSTRAP_ASSET}")


if __name__ == '__main__':
    main()
