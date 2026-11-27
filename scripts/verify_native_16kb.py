#!/usr/bin/env python3
"""Check ELF64 alignment, same-ABI dependencies and the APK's TikZ contract.

Source-tree mode checks prebuilts only; --apk additionally checks package closure.
Uses program headers for DT_NEEDED and defined dynamic symbols for JNI exports.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
import struct
import sys
import zipfile

PAGE = 16384
MACHINES = {"arm64-v8a": 183, "x86_64": 62}
# Public Android native libraries used by this app (not private platform libraries).
SYSTEM_LIBS = frozenset({
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
    "libjnigraphics.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so",
    "libOpenSLES.so", "libmediandk.so", "libvulkan.so", "libaaudio.so",
    "libnativewindow.so", "libstdc++.so",
})
TEX = "libtex_poc.so"
BRIDGE = "libpdf2svg_bridge.so"
GRAPHITE = "libgraphite2.so"
JNI = {
    TEX: {"Java_app_androidrik_rikkahub_tikz_TectonicNative_compile",
          "Java_app_androidrik_rikkahub_tikz_TectonicNative_lastError"},
    BRIDGE: {"Java_app_androidrik_rikkahub_tikz_MupdfSvgBridge_nativePdfToSvg"},
}


class InvalidElf(ValueError):
    pass


@dataclass(frozen=True)
class ElfInfo:
    machine: int
    needed: frozenset[str]
    exports: frozenset[str]


def parse_elf(data: bytes) -> ElfInfo:
    def region(offset, size):
        if offset < 0 or size < 0 or offset + size > len(data):
            raise InvalidElf(f"truncated ELF range {offset}+{size}")
        return data[offset:offset + size]

    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:7] != b"\x02\x01\x01":
        raise InvalidElf("expected a little-endian ELF64 v1 header")

    def unpack(fmt, offset):
        return struct.unpack("<" + fmt, region(offset, struct.calcsize("<" + fmt)))

    typ, machine, version, _, phoff, shoff, _, ehsize, phsize, phnum, shsize, shnum, _ = unpack("HHIQQQIHHHHHH", 16)
    if typ not in (2, 3) or version != 1 or ehsize != 64 or phsize != 56 or not phnum or phnum == 0xffff:
        raise InvalidElf("invalid or unsupported ELF/program header")
    region(phoff, phsize * phnum)
    loads, dynamics = [], []
    for i in range(phnum):
        pt, flags, offset, va, _, filesz, memsz, align = unpack("IIQQQQQQ", phoff + i * phsize)
        if pt in (1, 2):
            region(offset, filesz)
        if pt == 1:
            if filesz > memsz:
                raise InvalidElf("PT_LOAD filesz exceeds memsz")
            if align < PAGE or align & (align - 1) or offset % PAGE != va % PAGE:
                raise InvalidElf(f"PT_LOAD is not 16 KB aligned: align={align:#x}, offset={offset:#x}, vaddr={va:#x}")
            if offset % align != va % align:
                raise InvalidElf("PT_LOAD violates its declared alignment")
            loads.append((va, offset, filesz))
        elif pt == 2:
            dynamics.append((offset, filesz))
    if not loads:
        raise InvalidElf("ELF has no PT_LOAD")

    def virtual_region(va, size):
        for start, offset, filesz in loads:
            if start <= va and va + size <= start + filesz:
                return region(offset + va - start, size)
        raise InvalidElf("dynamic string table is outside file-backed PT_LOAD")

    def string(table, offset):
        if offset >= len(table):
            raise InvalidElf("string offset outside table")
        end = table.find(b"\0", offset)
        if end < 0:
            raise InvalidElf("unterminated ELF string")
        try:
            return table[offset:end].decode("utf-8")
        except UnicodeDecodeError as e:
            raise InvalidElf("invalid ELF string encoding") from e

    needed = set()
    if len(dynamics) > 1:
        raise InvalidElf("multiple PT_DYNAMIC segments")
    for offset, size in dynamics:
        if size % 16:
            raise InvalidElf("invalid PT_DYNAMIC size")
        tags = {}
        terminated = False
        for pos in range(offset, offset + size, 16):
            tag, val = unpack("qQ", pos)
            if tag == 0:
                terminated = True
                break
            tags.setdefault(tag, []).append(val)
        if not terminated:
            raise InvalidElf("PT_DYNAMIC has no DT_NULL")
        if 1 in tags:
            if len(tags.get(5, [])) != 1 or len(tags.get(10, [])) != 1:
                raise InvalidElf("DT_NEEDED without a unique string table")
            table = virtual_region(tags[5][0], tags[10][0])
            needed = {string(table, pos) for pos in tags[1]}
            if any(not n or "/" in n for n in needed):
                raise InvalidElf(f"invalid DT_NEEDED names: {sorted(needed)}")

    exports = set()
    if shnum:
        if not shoff or shsize != 64:
            raise InvalidElf("invalid section header table")
        region(shoff, shsize * shnum)
        sections = [unpack("IIQQQQIIQQ", shoff + i * shsize) for i in range(shnum)]
        for sh in sections:
            if sh[1] != 11:  # SHT_DYNSYM
                continue
            offset, size, link, entry = sh[4], sh[5], sh[6], sh[9]
            if entry != 24 or size % entry or link >= shnum or sections[link][1] != 3:
                raise InvalidElf("invalid dynamic symbol table")
            region(offset, size)
            table = region(sections[link][4], sections[link][5])
            for pos in range(offset, offset + size, entry):
                name, info, other, index, _, _ = unpack("IBBHQQ", pos)
                symbol = string(table, name)
                if index and info >> 4 in (1, 2) and other & 3 in (0, 3) and info & 15 in (2, 10):
                    exports.add(symbol)
    elif shoff:
        raise InvalidElf("unsupported extended section count")
    return ElfInfo(machine, frozenset(needed), frozenset(exports))


def check_library(abi: str, name: str, data: bytes) -> ElfInfo:
    info = parse_elf(data)
    if info.machine != MACHINES[abi]:
        raise InvalidElf(f"machine {info.machine} does not match {abi}")
    missing = JNI.get(name, set()) - info.exports
    if missing:
        raise InvalidElf(f"missing defined JNI exports: {sorted(missing)}")
    return info


def check_package(libraries: dict[tuple[str, str], ElfInfo]) -> list[str]:
    errors = []
    for (abi, name), info in libraries.items():
        for needed in sorted(info.needed):
            if needed not in SYSTEM_LIBS and (abi, needed) not in libraries:
                errors.append(f"{abi}/{name}: missing same-ABI dependency {needed}")
    if any(abi == "arm64-v8a" for abi, _ in libraries):
        for name in (TEX, BRIDGE):
            if ("arm64-v8a", name) not in libraries:
                errors.append(f"arm64-v8a: missing required {name}")
        if ("arm64-v8a", GRAPHITE) in libraries:
            errors.append("arm64-v8a: Graphite2 must be statically linked, not packaged separately")
        tex = libraries.get(("arm64-v8a", TEX))
        if tex and any(n.startswith('libgraphite2.so') for n in tex.needed):
            errors.append("arm64-v8a: TeX still dynamically depends on Graphite2")
    for name in (TEX, BRIDGE):
        if ("x86_64", name) in libraries:
            errors.append(f"x86_64: unsupported TikZ library {name}")
    return errors


def verify_apk(path: Path) -> tuple[int, list[str]]:
    errors, libraries, seen = [], {}, set()
    with zipfile.ZipFile(path) as apk:
        for item in apk.infolist():
            parts = item.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or parts[1] not in MACHINES or not parts[2].endswith(".so"):
                continue
            key = (parts[1], parts[2])
            if key in seen:
                errors.append(f"duplicate ZIP entry {item.filename}")
                continue
            seen.add(key)
            try:
                libraries[key] = check_library(*key, apk.read(item))
            except InvalidElf as e:
                errors.append(f"{item.filename}: {e}")
    if not seen:
        errors.append("APK has no supported native libraries")
    errors.extend(check_package(libraries))
    return len(seen), errors


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    errors = []
    try:
        if args.apk:
            count, errors = verify_apk(args.apk)
        else:
            files = sorted(args.root.glob("*/src/main/jniLibs/**/*.so"))
            counts = Counter()
            for p in files:
                if p.parent.name not in MACHINES:
                    errors.append(f"{p}: unexpected ABI")
                    continue
                counts[p.parent.name] += 1
                try:
                    check_library(p.parent.name, p.name, p.read_bytes())
                except InvalidElf as e:
                    errors.append(f"{p}: {e}")
            count = sum(counts.values())
            if not count:
                errors.append("No native libraries found")
    except (OSError, ValueError, zipfile.BadZipFile, struct.error) as e:
        errors.append(str(e))
        count = 0
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(f"Native 16 KB: {count} libraries, {len(errors)} errors ({args.apk or 'source prebuilts only'})")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
