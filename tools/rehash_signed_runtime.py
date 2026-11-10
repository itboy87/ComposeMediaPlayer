#!/usr/bin/env python3
"""Refresh only binary hashes after downstream signing, before signing the outer app."""
import argparse,hashlib,pathlib
parser=argparse.ArgumentParser();parser.add_argument('bundle',type=pathlib.Path);args=parser.parse_args()
manifest=args.bundle/'bundle.properties'
lines=[]
for line in manifest.read_text().splitlines():
 if line.startswith('sha256.'):
  key,_=line.split('=',1);name=key[7:]
  if '..' in pathlib.PurePosixPath(name).parts or name.startswith('/') or '\\' in name:raise SystemExit('Invalid bundle path')
  line=key+'='+hashlib.sha256((args.bundle/name).read_bytes()).hexdigest()
 lines.append(line)
manifest.write_text('\n'.join(lines)+'\n')
