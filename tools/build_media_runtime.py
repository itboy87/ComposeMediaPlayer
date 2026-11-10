#!/usr/bin/env python3
"""Build and stage the relocatable native media runtime (macOS and Windows)."""
import argparse, hashlib, json, os, pathlib, platform, shutil, subprocess, sys
from download_native import download
ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'mediaplayer/src/jvmMain/native/shared'
CACHE = ROOT / 'mediaplayer/build/native-sdk'
VERSION = '1.28.8'
PINS = {
 'runtime': ('gstreamer-1.0-1.28.8-universal.pkg', 'd371ab04a7632713626836c2fc234e863cc98484c74c473eba99b0fbd9b3a3fe'),
 'devel': ('gstreamer-1.0-devel-1.28.8-universal.pkg', 'e6d380bc17be7eccf13e0154567d4718fc9321da822408c7ca0cd808b3d4a3f7'),
}
PLUGINS = {'coreelements','playback','adaptivedemux2','isomp4','app','videoconvertscale','audioconvert','audioresample','audioparsers','audiofx','volume','videoparsersbad','libav','typefindfunctions','soup','osxaudio','wasapi2','autodetect','subparse'}
def run(*args): subprocess.run(list(map(str,args)), check=True)
def fingerprint():
 h=hashlib.sha256()
 for p in sorted(list(SOURCE.rglob('*')) + list((ROOT/'tools').glob('*native*')) + [pathlib.Path(__file__)], key=lambda p:p.relative_to(ROOT).as_posix()):
  if p.is_file(): h.update(p.relative_to(ROOT).as_posix().encode()); h.update(p.read_bytes() if p.suffix in {'.ttf','.otf'} else p.read_bytes().replace(b'\r\n',b'\n'))
 return h.hexdigest()
def manifest(output, target):
 lines=['abi=1',f'platform={target}',f'source={fingerprint()}',f'gstreamer={VERSION}']
 for p in sorted(output.rglob('*')):
  if p.is_file() and p.name != 'bundle.properties': lines.append(f'sha256.{p.relative_to(output).as_posix()}={hashlib.sha256(p.read_bytes()).hexdigest()}')
 (output/'bundle.properties').write_text('\n'.join(lines)+'\n')
def sdk_mac():
 sdk=CACHE/'sdk'
 if (sdk/'.complete').exists(): return sdk
 for kind,(filename,digest) in PINS.items():
  package=download(f'https://gstreamer.freedesktop.org/data/pkg/osx/{VERSION}/{filename}',CACHE/f'{kind}.pkg',digest)
  expanded=CACHE/f'{kind}-expanded'
  if not expanded.exists(): run('pkgutil','--expand-full',package,expanded)
  for payload in expanded.glob('*.pkg/Payload'):
   if payload.is_dir():
    for base,dirs,files in os.walk(payload):
     relative=pathlib.Path(base).relative_to(payload); dest=sdk/relative
     if dest.is_symlink(): dest.unlink()
     dest.mkdir(parents=True,exist_ok=True)
     for name in files:
      source=pathlib.Path(base)/name; target=dest/name
      if name.endswith('.a'): continue
      if target.exists() or target.is_symlink(): target.unlink()
      if source.is_symlink(): target.symlink_to(os.readlink(source))
      else: shutil.copy2(source,target)
 (sdk/'.complete').touch()
 return sdk

def build_mac(arch, output, sdk):
 target='darwin-aarch64' if arch=='arm64' else 'darwin-x86-64'
 out=output/target
 if out.exists(): shutil.rmtree(out)
 out.mkdir(parents=True,exist_ok=True)
 java=pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home'],text=True).strip())
 includes=[sdk/'include/gstreamer-1.0',sdk/'include/glib-2.0',sdk/'lib/glib-2.0/include',sdk/'include',java/'include',java/'include/darwin']
 lib=out/'libNativeMediaRuntime.dylib'
 run('clang++','-std=c++17','-O2','-dynamiclib','-arch',arch,'-mmacosx-version-min=14.0',*[f'-I{p}' for p in includes],SOURCE/'NativeMediaRuntime.cpp',SOURCE/'WidevineHost.cpp',f'-L{sdk}/lib','-lgstreamer-1.0','-lgstapp-1.0','-lgstvideo-1.0','-lgstaudio-1.0','-lgstbase-1.0','-lgobject-2.0','-lglib-2.0','-lsoup-3.0','-lass','-o',lib)
 copied={}
 def stage(original,destination):
  original=original.resolve()
  if destination.exists(): return
  destination.parent.mkdir(parents=True,exist_ok=True)
  run('lipo',original,'-thin',arch,'-output',destination)
  # Retain LC_CODE_SIGNATURE until relocation. Older codesign removal leaves
  # alignment padding at the end of __LINKEDIT that install_name_tool rejects.
  # All staged libraries are signed again after their load commands are final.
 def deps(file):
  if file in copied: return
  copied[file]=True
  changes=[]
  for line in subprocess.check_output(['otool','-L',str(file)],text=True).splitlines()[1:]:
   dependency=line.strip().split(' (')[0]
   if dependency.startswith(('/usr/lib/','/System/')): continue
   if dependency==str(file): continue
   name=pathlib.Path(dependency).name
   candidate=sdk/'lib'/name
   if not candidate.exists():
    if name==file.name: continue
    raise RuntimeError(f'Unresolved native dependency: {name}')
   dest=out/'lib'/name
   if not dest.exists(): stage(candidate,dest)
   replacement='@loader_path/'+('../lib/' if file.parent.name=='plugins' else ('lib/' if file.parent==out else ''))+name
   changes+=['-change',dependency,replacement]
   deps(dest)
  run('install_name_tool',*changes,'-id','@rpath/'+file.name,file)
 for plugin in sorted(PLUGINS):
  original=sdk/'lib/gstreamer-1.0'/f'libgst{plugin}.dylib'
  if original.exists():
   destination=out/'plugins'/original.name; stage(original,destination); deps(destination)
 deps(lib)
 for file in copied: run('codesign','--force','--sign','-',file)
 shutil.copytree(SOURCE/'fonts',out/'fonts',dirs_exist_ok=True)
 shutil.copytree(SOURCE/'vendor',out/'notices/chromium-cdm-interface',dirs_exist_ok=True,ignore=shutil.ignore_patterns('*.h'))
 for notices in [sdk/'share/licenses',sdk/'share/doc']:
  if notices.exists(): shutil.copytree(notices,out/'notices'/notices.name,dirs_exist_ok=True,symlinks=False)
 shutil.copy2(SOURCE/'THIRD_PARTY.md',out/'notices/THIRD_PARTY.md')
 manifest(out,target)
 return out

def build_windows(arch, output, sdk):
 import re
 target='win32-arm64' if arch in ('arm64','aarch64') else 'win32-x86-64'
 machine='arm64' if target.endswith('arm64') else 'x86_64'
 if sdk is None:
  digest={'x86_64':'e4c41ee6ee9c6c32a45d198463ec08d79bac3bd5fa58adc93d13fe4a08abc9d9','arm64':'52974e1717bb680272bd3db79a0bde231464eca54b6db83f0b3e57d202fe1600'}[machine]
  filename=f'gstreamer-1.0-msvc-{machine}-{VERSION}.exe'
  installer=download(f'https://gstreamer.freedesktop.org/data/pkg/windows/{VERSION}/msvc/{filename}',CACHE/filename,digest)
  sdk=(CACHE/f'sdk-{machine}').resolve()
  if not (sdk/'include/gstreamer-1.0/gst/gst.h').exists(): run(installer,'/VERYSILENT','/NORESTART','/CURRENTUSER','/TYPE=devel',f'/DIR={sdk}')
 build=ROOT/'mediaplayer/build'/f'media-{machine}'
 run('cmake','-S',SOURCE,'-B',build,'-A','ARM64' if machine=='arm64' else 'x64',f'-DGSTREAMER_ROOT={sdk}')
 run('cmake','--build',build,'--config','Release')
 out=output/target
 if out.exists():shutil.rmtree(out)
 out.mkdir(parents=True,exist_ok=True)
 for name in ['NativeMediaRuntime.dll','NativeMediaBootstrap.dll']: shutil.copy2(build/'Release'/name,out/name)
 available={p.name.lower():p for p in (sdk/'bin').glob('*.dll')}
 redist=os.environ.get('VCToolsRedistDir')
 if redist:
  for p in (pathlib.Path(redist)/('arm64' if machine=='arm64' else 'x64')).rglob('*.dll'): available.setdefault(p.name.lower(),p)
 visited=set()
 def deps(file):
  if file in visited:return
  visited.add(file)
  text=subprocess.check_output(['dumpbin','/DEPENDENTS',str(file)],text=True)
  for name in re.findall(r'^\s+([^\s]+\.dll)\s*$',text,re.M|re.I):
   original=available.get(name.lower())
   if not original and name.lower().startswith(('vcruntime','msvcp','concrt')): raise RuntimeError('Missing app-local MSVC runtime; configure VCToolsRedistDir')
   if original:
    destination=out/'lib'/original.name;destination.parent.mkdir(exist_ok=True)
    if not destination.exists():shutil.copy2(original,destination)
    deps(destination)
 for plugin in sorted(PLUGINS):
  original=sdk/'lib/gstreamer-1.0'/f'gst{plugin}.dll'
  if original.exists():
   destination=out/'plugins'/original.name;destination.parent.mkdir(exist_ok=True);shutil.copy2(original,destination);deps(destination)
 deps(out/'NativeMediaRuntime.dll');deps(out/'NativeMediaBootstrap.dll')
 shutil.copytree(SOURCE/'fonts',out/'fonts',dirs_exist_ok=True)
 for notices in [sdk/'share/licenses',sdk/'share/doc']:
  if notices.exists():shutil.copytree(notices,out/'notices'/notices.name,dirs_exist_ok=True)
 shutil.copytree(SOURCE/'vendor',out/'notices/chromium-cdm-interface',dirs_exist_ok=True,ignore=shutil.ignore_patterns('*.h'))
 shutil.copy2(SOURCE/'THIRD_PARTY.md',out/'notices/THIRD_PARTY.md')
 manifest(out,target);return out

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--arch',default='arm64' if platform.machine().lower() in ('arm64','aarch64') else 'x86_64');parser.add_argument('--output',type=pathlib.Path,default=ROOT/'mediaplayer/build/generated/runtime');parser.add_argument('--verify',type=pathlib.Path);parser.add_argument('--sdk',type=pathlib.Path)
 args=parser.parse_args()
 if args.verify:
  values=dict(line.split('=',1) for line in (args.verify/'bundle.properties').read_text().splitlines() if '=' in line)
  if values.get('source')!=fingerprint(): raise SystemExit('Prebuilt native runtime is stale: source fingerprint mismatch')
  for key,value in values.items():
   if key.startswith('sha256.') and hashlib.sha256((args.verify/key[7:]).read_bytes()).hexdigest()!=value: raise SystemExit('Prebuilt native runtime checksum mismatch')
 elif sys.platform=='darwin': print(build_mac(args.arch,args.output,args.sdk or sdk_mac()))
 elif sys.platform=='win32': print(build_windows(args.arch,args.output,args.sdk))
 else: raise SystemExit('The bundled runtime targets macOS and Windows')
