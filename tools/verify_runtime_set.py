#!/usr/bin/env python3
import argparse,hashlib,pathlib
from build_media_runtime import fingerprint
parser=argparse.ArgumentParser()
parser.add_argument('roots',type=pathlib.Path,nargs='+')
parser.add_argument('--require-all',action='store_true')
args=parser.parse_args()
found=set()
for root in args.roots:
 if not root.exists():continue
 for bundle in root.iterdir():
  if not bundle.is_dir():continue
  values=dict(line.split('=',1) for line in (bundle/'bundle.properties').read_text().splitlines() if '=' in line)
  if values.get('source')!=fingerprint() or values.get('abi')!='1' or values.get('platform')!=bundle.name:raise SystemExit('Native runtime source/ABI/platform mismatch')
  for key,value in values.items():
   if key.startswith('sha256.'):
    name=key[7:]
    if '..' in pathlib.PurePosixPath(name).parts or name.startswith('/') or '\\' in name:raise SystemExit('Invalid bundle path')
    if hashlib.sha256((bundle/name).read_bytes()).hexdigest()!=value:raise SystemExit('Native runtime checksum mismatch')
  found.add(bundle.name)
if args.require_all and not {'darwin-aarch64','darwin-x86-64','win32-arm64','win32-x86-64'}.issubset(found):raise SystemExit('Publication requires all four verified desktop runtime bundles')
