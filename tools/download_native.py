#!/usr/bin/env python3
"""Resumable, checksum-verified SDK downloads; never installs system software."""
import concurrent.futures
import hashlib
import pathlib
import time
import urllib.request


def download(url, destination, digest, size=None):
    destination = pathlib.Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and hashlib.file_digest(destination.open('rb'), 'sha256').hexdigest() == digest:
        return destination
    if size is None:
        with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=30) as response:
            size = int(response.headers['Content-Length'])
    chunk = 8 * 1024 * 1024
    parts = destination.with_name(destination.name + '.parts')
    parts.mkdir(exist_ok=True)
    # Existing partial curl downloads are prefixes of the same version-pinned resource.
    if destination.exists():
        with destination.open('rb') as existing:
            for offset in range(0, size, chunk):
                data = existing.read(min(chunk, size - offset))
                if len(data) != min(chunk, size - offset):
                    break
                part = parts / str(offset)
                if not part.exists():
                    part.write_bytes(data)

    def fetch(offset):
        end = min(size, offset + chunk) - 1
        part = parts / str(offset)
        if part.exists() and part.stat().st_size == end - offset + 1:
            return part
        for attempt in range(8):
            try:
                request = urllib.request.Request(url, headers={'Range': f'bytes={offset}-{end}'})
                with urllib.request.urlopen(request, timeout=60) as response:
                    if response.status != 206 or not response.headers.get('Content-Range', '').startswith(f'bytes {offset}-'):
                        raise RuntimeError('Server did not honor the byte range')
                    data = response.read(end - offset + 2)
                    if len(data) != end - offset + 1:
                        raise RuntimeError('Incomplete SDK range')
                    part.write_bytes(data)
                    print(f'{destination.name}: {end + 1}/{size}', flush=True)
                    return part
            except Exception:
                if attempt == 7:
                    raise
                time.sleep(min(10, attempt + 1))

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        files = list(executor.map(fetch, range(0, size, chunk)))
    temporary = destination.with_name(destination.name + '.complete')
    with temporary.open('wb') as output:
        for part in files:
            output.write(part.read_bytes())
    with temporary.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != digest:
        temporary.unlink()
        raise RuntimeError(f'SDK checksum mismatch for {destination.name}')
    temporary.replace(destination)
    return destination


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('url')
    parser.add_argument('destination')
    parser.add_argument('sha256')
    parser.add_argument('--size', type=int)
    args = parser.parse_args()
    download(args.url, args.destination, args.sha256, args.size)
