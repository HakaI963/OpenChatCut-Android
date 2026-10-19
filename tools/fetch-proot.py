#!/usr/bin/env python3
"""Resolve + download the newest Termux arm64 packages we need.
Out: <outdir>/proot.deb, <outdir>/talloc.deb (later unpacked by shell `ar`+tar).
"""
import gzip
import lzma
import re
import sys
from urllib.request import Request, urlopen

MIRRORS = ["https://packages.termux.dev/apt/termux-main"]
UA = {"User-Agent": "OpenChatCut-Android/0.1 (github.com/HakaI963/OpenChatCut-Android)"}


def fetch(url: str) -> bytes:
    req = Request(url, headers=UA)
    with urlopen(req, timeout=60) as r:
        return r.read()


def index_text() -> str:
    for base in MIRRORS:
        for name, opener in (
            ("Packages", lambda b: b.decode("utf-8", "replace")),
            ("Packages.gz", lambda b: gzip.decompress(b).decode("utf-8", "replace")),
            ("Packages.xz", lambda b: lzma.decompress(b).decode("utf-8", "replace")),
        ):
            try:
                return opener(fetch(f"{base}/dists/stable/main/binary-aarch64/{name}"))
            except Exception:
                continue
    raise SystemExit("could not load Termux arm64 index")


def main(out: str) -> None:
    index = index_text()
    picks = {}
    for entry in index.split("\n\n"):
        m = re.search(r"^Package: (.*)$", entry, re.M)
        if not m:
            continue
        pkg = m.group(1).strip()
        if pkg not in ("proot", "talloc"):
            continue
        fn = re.search(r"^Filename: (.*)$", entry, re.M)
        anc = re.search(r"^Architecture: (.+)$", entry, re.M)
        if not fn:
            continue
        if anc and anc.group(1).strip() not in ("aarch64", "all"):
            continue
        # keep the LAST (newest) entry for each package
        picks[pkg] = (fn.group(1).strip(), entry)
    import pathlib
    outdir = pathlib.Path(out)
    outdir.mkdir(parents=True, exist_ok=True)
    for pkg, (path, meta) in picks.items():
        url = f"{MIRRORS[0]}/{path}"
        data = fetch(url)
        (outdir / f"{pkg}.deb").write_bytes(data)
        ver = re.search(r"^Version: (.*)$", meta, re.M)
        print(f"{pkg} {ver.group(1) if ver else '?'} -> {url} ({len(data)} bytes)")
    if "proot" not in picks:
        raise SystemExit("proot package not found")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out/host")
