#!/usr/bin/env python3
"""Prepare, attest and publish an APK without relying on GitHub's default branch."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = 'taylormadearmy/u1-slicer-for-android'
CERT = '4e043ca86110b5f68a40600d3efe94574b092f15e305ba18efb4b30023742b45'
TOOLS = Path('D:/Android/Sdk/build-tools/35.0.0')


def run(*args):
    return subprocess.check_output([str(a) for a in args], cwd=ROOT, text=True).strip()


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def clean_source():
    # Untracked local audit/device files are allowed; tracked source changes are not.
    if run('git', 'status', '--porcelain', '--untracked-files=no'):
        raise ValueError('Commit all tracked changes before preparing or publishing.')
    return run('git', 'rev-parse', 'HEAD')


def inspect_apk(apk, version):
    signature = run(TOOLS / 'apksigner.bat', 'verify', '--print-certs', apk)
    if re.findall(r'certificate SHA-256 digest: ([0-9a-f]+)', signature) != [CERT]:
        raise ValueError('APK signing certificate changed or has unexpected signers.')
    manifest = run(TOOLS / 'aapt.exe', 'dump', 'badging', apk)
    identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", manifest)
    if not identity or identity[1] != 'com.u1.slicer.orca' or identity[3] != version:
        raise ValueError('APK package/version does not match the requested release.')
    xml = run(TOOLS / 'aapt.exe', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
    if re.search(r'android:(?:debuggable|testOnly).*0xffffffff', xml):
        raise ValueError('Release APK is debuggable or testOnly.')
    run(sys.executable, ROOT / 'scripts/validate-native-page-size.py', apk,
        '--aapt', TOOLS / 'aapt.exe')
    return int(identity[2])


def validate_install(candidate, check):
    if check.get('apkSha256') != candidate['apkSha256']:
        raise ValueError('Installer evidence belongs to another APK.')
    if check.get('result') != 'passed' or check.get('playProtectEnabled') is not True:
        raise ValueError('A warning-free install with Play Protect enabled is required.')
    if check.get('method') not in ('browser', 'files') or not check.get('device'):
        raise ValueError('Record a real browser/Files installer check, not ADB.')
    evidence = Path(check['evidence'])
    if not evidence.is_file() or digest(evidence) != check.get('evidenceSha256'):
        raise ValueError('Installer evidence is missing or has changed.')


def verify_tag(expected, actual):
    if actual != expected:
        raise ValueError(f'Release tag points to {actual}, expected {expected}. Leave draft unpublished.')


def prepare(args, directory):
    source = clean_source()
    if directory.exists():
        raise ValueError('Use a new version; candidate evidence must not be overwritten.')
    run(ROOT / 'gradlew.bat', 'clean', 'assembleRelease', '--no-daemon', '--console=plain')
    if clean_source() != source:
        raise ValueError('Source changed while building.')
    apk = directory / f'u1-slicer-orca-v{args.version}.apk'
    apk.parent.mkdir(parents=True)
    apk.write_bytes((ROOT / 'app/build/outputs/apk/release/app-release.apk').read_bytes())
    code = inspect_apk(apk, args.version)
    save(directory / 'candidate.json', dict(version=args.version, versionCode=code,
         sourceCommit=source, apk=str(apk), apkSha256=digest(apk), certificateSha256=CERT))
    print(f'Prepared {apk}. Next: perform and record the installer check on these exact bytes.')


def record_install(args, directory):
    candidate = json.loads((directory / 'candidate.json').read_text())
    if digest(candidate['apk']) != args.apk_sha256 or args.apk_sha256 != candidate['apkSha256']:
        raise ValueError('Supply the SHA-256 of the exact APK you installed.')
    evidence = Path(args.evidence).resolve()
    check = dict(apkSha256=args.apk_sha256, device=args.device, method=args.method,
                 result='passed', playProtectEnabled=args.play_protect_enabled,
                 evidence=str(evidence), evidenceSha256=digest(evidence),
                 checkedAt=datetime.now(timezone.utc).isoformat())
    validate_install(candidate, check)
    save(directory / 'installer-check.json', check)


def publish(args, directory):
    candidate = json.loads((directory / 'candidate.json').read_text())
    if candidate['version'] != args.version or clean_source() != candidate['sourceCommit']:
        raise ValueError('Candidate does not match this version and current committed source.')
    apk = Path(candidate['apk'])
    if digest(apk) != candidate['apkSha256']:
        raise ValueError('Candidate APK changed after preparation.')
    inspect_apk(apk, args.version)
    validate_install(candidate, json.loads((directory / 'installer-check.json').read_text()))
    if run('gh', 'api', 'user', '--jq', '.login') != 'taylormadearmy':
        raise ValueError('Authenticate as taylormadearmy before publishing.')
    notes = Path(args.notes).resolve()
    if not notes.is_file():
        raise ValueError('Release notes file missing.')
    tag = 'v' + args.version
    if run('git', 'ls-remote', '--tags', 'origin', 'refs/tags/' + tag):
        raise ValueError('Public tag already exists; never reuse a release version.')
    # This fails if the source commit has not been pushed; do not create a remote tag first.
    run('gh', 'api', f'repos/{REPO}/commits/{candidate["sourceCommit"]}', '--jq', '.sha')
    run('git', 'tag', tag, candidate['sourceCommit'])
    run('git', 'push', 'origin', 'refs/tags/' + tag)
    actual = run('gh', 'api', f'repos/{REPO}/commits/{tag}', '--jq', '.sha')
    verify_tag(candidate['sourceCommit'], actual)
    run('gh', 'release', 'create', tag, apk, '--repo', REPO, '--draft', '--verify-tag',
        '--target', candidate['sourceCommit'], '--title', tag, '--notes-file', notes)
    download = directory / 'downloaded'
    run('gh', 'release', 'download', tag, '--repo', REPO, '--pattern', apk.name, '--dir', download)
    if digest(download / apk.name) != candidate['apkSha256']:
        raise ValueError('Uploaded APK differs. Leave draft unpublished.')
    run('gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--latest')
    release = json.loads(run('gh', 'release', 'view', tag, '--repo', REPO,
                             '--json', 'url,isDraft,assets'))
    if release['isDraft'] or not any(a['name'] == apk.name and a['state'] == 'uploaded' for a in release['assets']):
        raise ValueError('Release publication/asset verification failed.')
    save(directory / 'published.json', dict(candidate=candidate, release=release, tagCommit=actual))
    print(release['url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'record-install', 'publish'])
    parser.add_argument('--version', required=True, help='Version without v prefix')
    parser.add_argument('--notes')
    parser.add_argument('--device')
    parser.add_argument('--method', choices=['browser', 'files'])
    parser.add_argument('--evidence', help='Screenshot or recording of completed installer check')
    parser.add_argument('--apk-sha256')
    parser.add_argument('--play-protect-enabled', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version):
        parser.error('Version must be N.N.N')
    if args.action == 'record-install' and not all([args.device, args.method, args.evidence, args.apk_sha256]):
        parser.error('record-install needs --device, --method, --evidence and --apk-sha256')
    if args.action == 'publish' and not args.notes:
        parser.error('publish needs --notes')
    directory = ROOT / '.audit/releases' / args.version
    {'prepare': prepare, 'record-install': record_install, 'publish': publish}[args.action](args, directory)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f'Release blocked: {error}')
