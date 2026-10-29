"""Build Light Novel Library with JDK 17 and official Android SDK tools (Python 3.10+)."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT')))
parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME'))
parser.add_argument('--build-tools', default='34.0.0')
parser.add_argument('--signing-dir', type=Path, default=ROOT / '.signing')
parser.add_argument('--out', type=Path, default=ROOT / 'dist')
parser.add_argument('--debug', action='store_true')
args = parser.parse_args()
if not args.sdk or not args.jdk:
    parser.error('Set ANDROID_HOME and JAVA_HOME, or supply --sdk and --jdk.')
SDK, JAVA = Path(args.sdk).resolve(), Path(args.jdk).resolve()
args.signing_dir = args.signing_dir.resolve()
args.out = args.out.resolve()
TOOLS = SDK / 'build-tools' / args.build_tools
BUILD = ROOT / 'app' / 'build' / 'manual'
BUILD.mkdir(parents=True, exist_ok=True)
WINDOWS = os.name == 'nt'
env = os.environ.copy()
env['JAVA_HOME'] = str(JAVA)
env['JAVA_TOOL_OPTIONS'] = '-Dfile.encoding=UTF-8 -Duser.language=en'
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')


def executable(folder, name, batch=False):
    return folder / (name + ('.bat' if batch else '.exe') if WINDOWS else name)


def run(*arguments):
    result = subprocess.run([str(a) for a in arguments], cwd=ROOT, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            encoding='utf-8', errors='replace',
                            creationflags=subprocess.CREATE_NO_WINDOW if WINDOWS else 0)
    if result.stdout.strip():
        print(result.stdout, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)


for name in ['classes', 'generated', 'dex']:
    target = (BUILD / name).resolve()
    if not target.is_relative_to(BUILD.resolve()):
        raise ValueError('Build cleanup target is outside the project build folder')
    if target.exists():
        shutil.rmtree(target)
    target.mkdir()

run(sys.executable, ROOT / 'tools/sync_notices.py')
platform = SDK / 'platforms' / 'android-35' / 'android.jar'
run(executable(TOOLS, 'aapt2'), 'compile', '--dir', ROOT / 'app/src/main/res', '-o', BUILD / 'resources.zip')
manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
manifest = manifest.replace('<manifest xmlns:', '<manifest package="com.xiahu.reader" xmlns:', 1)
if args.debug:
    manifest = manifest.replace('<application ', '<application android:debuggable="true" ', 1)
(BUILD / 'AndroidManifest.xml').write_text(manifest, encoding='utf-8')
run(executable(TOOLS, 'aapt2'), 'link', '-o', BUILD / 'resources.apk', '-I', platform,
    '--manifest', BUILD / 'AndroidManifest.xml', '--java', BUILD / 'generated',
    '--min-sdk-version', '26', '--target-sdk-version', '35', '--version-code', '15',
    '--version-name', '0.8.1', BUILD / 'resources.zip')
sources = list((ROOT / 'app/src/main/java').rglob('*.java')) + list((BUILD / 'generated').rglob('*.java'))
run(executable(JAVA / 'bin', 'javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-bootclasspath', str(TOOLS / 'core-lambda-stubs.jar') + os.pathsep + str(platform),
    '-d', BUILD / 'classes', *sources)
with zipfile.ZipFile(BUILD / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in (BUILD / 'classes').rglob('*.class'):
        archive.write(file, file.relative_to(BUILD / 'classes').as_posix())
run(executable(TOOLS, 'd8', True), '--release', '--min-api', '26', '--lib', platform,
    '--output', BUILD / 'dex', BUILD / 'classes.jar')
shutil.copyfile(BUILD / 'resources.apk', BUILD / 'unsigned.apk')
with zipfile.ZipFile(BUILD / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    for file in (BUILD / 'dex').glob('*.dex'):
        archive.write(file, file.name)
    for directory, prefix in [(ROOT / 'app/src/main/assets', 'assets'), (ROOT / 'app/src/main/jniLibs', 'lib')]:
        for file in directory.rglob('*'):
            if file.is_file():
                archive.write(file, prefix + '/' + file.relative_to(directory).as_posix())
run(executable(TOOLS, 'zipalign'), '-f', '4', BUILD / 'unsigned.apk', BUILD / 'aligned.apk')
args.signing_dir.mkdir(parents=True, exist_ok=True)
config = args.signing_dir / 'reader-signing.json'
if not config.exists():
    config.write_text(json.dumps({'password': secrets.token_urlsafe(24)}), encoding='utf-8')
env['SHIYE_BUILD_PASSWORD'] = json.loads(config.read_text(encoding='utf-8'))['password']
keystore = args.signing_dir / 'reader-release.jks'
if not keystore.exists():
    run(executable(JAVA / 'bin', 'keytool'), '-genkeypair', '-keystore', keystore,
        '-storepass:env', 'SHIYE_BUILD_PASSWORD', '-keypass:env', 'SHIYE_BUILD_PASSWORD',
        '-alias', 'shiyereader', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=Shiye Reader, O=Personal Reader, C=CN', '-storetype', 'JKS')
args.out.mkdir(parents=True, exist_ok=True)
apk = args.out / ('LightNovelLibrary-0.8.1-debug.apk' if args.debug else 'LightNovelLibrary-0.8.1.apk')
run(executable(TOOLS, 'apksigner', True), 'sign', '--ks', keystore, '--ks-key-alias', 'shiyereader',
    '--ks-pass', 'env:SHIYE_BUILD_PASSWORD', '--key-pass', 'env:SHIYE_BUILD_PASSWORD',
    '--out', apk, BUILD / 'aligned.apk')
run(executable(TOOLS, 'apksigner', True), 'verify', '--verbose', apk)
print('Built:', apk, 'bytes=', apk.stat().st_size)
