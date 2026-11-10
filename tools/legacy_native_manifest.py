#!/usr/bin/env python3
"""Source identity for the original AVFoundation/Media Foundation/Linux JNI artifacts."""
import argparse,hashlib,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1]
def digest(platform):
 base=ROOT/'mediaplayer/src/jvmMain/native'/platform
 h=hashlib.sha256(pathlib.Path(__file__).read_bytes().replace(b'\r\n',b'\n'))
 for p in sorted(base.rglob('*'), key=lambda p:p.relative_to(base).as_posix()):
  if p.is_file() and p.suffix in {'.cpp','.h','.c','.swift','.txt','.sh','.bat'} and not any(part.startswith('build') for part in p.relative_to(base).parts[:-1]):
   h.update(p.relative_to(base).as_posix().encode());h.update(p.read_bytes().replace(b'\r\n',b'\n'))
 return h.hexdigest()
parser=argparse.ArgumentParser();parser.add_argument('--write',choices=['macos','windows','linux']);parser.add_argument('--verify',action='store_true');parser.add_argument('directory',type=pathlib.Path);args=parser.parse_args()
if args.directory.exists():
 for folder in args.directory.iterdir():
  if not folder.is_dir():continue
  platform='macos' if folder.name.startswith('darwin-') else 'windows' if folder.name.startswith('win32-') else 'linux' if folder.name.startswith('linux-') else None
  if platform is None:continue
  expected=digest(platform);stamp=folder/'source.sha256'
  if args.write==platform:stamp.write_text(expected+'\n')
  elif args.verify and (not stamp.is_file() or stamp.read_text().strip()!=expected):raise SystemExit('Legacy native prebuilt is missing or has a stale source fingerprint: '+folder.name)
