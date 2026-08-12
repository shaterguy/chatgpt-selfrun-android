#!/usr/bin/env python3
from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

DROP = {"META-INF/version-control-info.textproto"}
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
ALIGN = 4
EXTRA_ID = 0xCAFE


def canonicalize(src: Path, dst: Path) -> None:
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", allowZip64=True) as zout:
        infos = [info for info in zin.infolist() if info.filename not in DROP]
        for old in sorted(infos, key=lambda info: info.filename):
            data = zin.read(old.filename)
            info = zipfile.ZipInfo(old.filename, FIXED_TIME)
            info.compress_type = old.compress_type
            info.comment = b""
            info.create_system = 0
            info.create_version = 20
            info.extract_version = max(20, old.extract_version)
            info.flag_bits = 0
            info.internal_attr = 0
            info.external_attr = 0x10 if old.is_dir() else 0
            info.extra = b""
            if old.compress_type == zipfile.ZIP_STORED and not old.is_dir():
                position = zout.fp.tell()
                name_len = len(info.filename.encode("utf-8"))
                data_offset_without_extra = position + 30 + name_len
                needed = (-data_offset_without_extra) % ALIGN
                if needed:
                    info.extra = struct.pack("<HH", EXTRA_ID, needed) + (b"\0" * needed)
            if old.compress_type == zipfile.ZIP_STORED:
                zout.writestr(info, data)
            else:
                zout.writestr(info, data, compress_type=old.compress_type, compresslevel=9)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: canonicalize_apk.py <input.apk> <output.apk>")
    canonicalize(Path(sys.argv[1]), Path(sys.argv[2]))
