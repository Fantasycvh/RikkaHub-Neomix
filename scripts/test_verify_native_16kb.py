import struct
import tempfile
import unittest
from pathlib import Path
import zipfile

import verify_native_16kb as gate


def elf(*, machine=183, align=16384, needed=(), exports=(), defined=True):
    """Small loadable-layout fixture with PT_DYNAMIC and a real SHT_DYNSYM."""
    b = bytearray(4096)
    b[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
    struct.pack_into('<HHIQQQIHHHHHH', b, 16, 3, machine, 1, 0, 64, 1024, 0, 64, 56, 2, 64, 3, 0)
    struct.pack_into('<IIQQQQQQ', b, 64, 1, 5, 0, 0, 0, len(b), len(b), align)
    strings = bytearray(b'\0')
    offsets = {}
    for name in (*needed, *exports):
        offsets[name] = len(strings)
        strings.extend(name.encode() + b'\0')
    b[512:512+len(strings)] = strings
    tags = [(1, offsets[n]) for n in needed] + [(5, 512), (10, len(strings)), (0, 0)]
    struct.pack_into('<IIQQQQQQ', b, 120, 2, 6, 256, 256, 0, len(tags)*16, len(tags)*16, 8)
    for i, (tag, val) in enumerate(tags):
        struct.pack_into('<qQ', b, 256+i*16, tag, val)
    for i, name in enumerate(exports, 1):
        struct.pack_into('<IBBHQQ', b, 768+i*24, offsets[name], 0x12, 0, 1 if defined else 0, 2048, 4)
    struct.pack_into('<IIQQQQIIQQ', b, 1088, 0, 11, 2, 768, 768, (len(exports)+1)*24, 2, 0, 8, 24)
    struct.pack_into('<IIQQQQIIQQ', b, 1152, 0, 3, 2, 512, 512, len(strings), 0, 0, 1, 0)
    return bytes(b)


def arm64_package():
    return {
        ('arm64-v8a', gate.TEX): elf(needed=('libc.so',), exports=gate.JNI[gate.TEX]),
        ('arm64-v8a', gate.BRIDGE): elf(needed=('libmupdf_java.so',), exports=gate.JNI[gate.BRIDGE]),
        ('arm64-v8a', 'libmupdf_java.so'): elf(),
    }


class ElfTest(unittest.TestCase):
    def test_valid_dynamic_and_defined_symbols(self):
        info = gate.parse_elf(elf(needed=('libc.so',), exports=('jni',)))
        self.assertEqual(info.needed, {'libc.so'})
        self.assertIn('jni', info.exports)

    def test_alignment(self):
        with self.assertRaisesRegex(gate.InvalidElf, 'aligned'):
            gate.parse_elf(elf(align=4096))
        b = bytearray(elf())
        struct.pack_into('<Q', b, 80, 4096)  # PT_LOAD p_vaddr
        with self.assertRaisesRegex(gate.InvalidElf, 'aligned'):
            gate.parse_elf(b)
        with self.assertRaises(gate.InvalidElf):
            gate.parse_elf(elf(align=24576))

    def test_needed_path_rejected(self):
        for name in ('../libfoo.so', '/tmp/libfoo.so', ''):
            with self.subTest(name=name), self.assertRaises(gate.InvalidElf):
                gate.parse_elf(elf(needed=(name,)))

    def test_jni_must_be_defined_export(self):
        for b in (elf(), elf(exports=gate.JNI[gate.TEX], defined=False)):
            with self.assertRaisesRegex(gate.InvalidElf, 'JNI'):
                gate.check_library('arm64-v8a', gate.TEX, b)
        gate.check_library('arm64-v8a', gate.TEX, elf(exports=gate.JNI[gate.TEX]))

    def test_hidden_or_data_symbol_is_not_a_jni_function(self):
        for field, value in ((772 + 24, 0x11), (773 + 24, 2)):
            b = bytearray(elf(exports=('jni',)))
            b[field] = value
            self.assertNotIn('jni', gate.parse_elf(b).exports)

    def test_machine(self):
        with self.assertRaisesRegex(gate.InvalidElf, 'machine'):
            gate.check_library('x86_64', 'libfoo.so', elf())

    def test_truncated_headers_and_tables(self):
        b = elf()
        for size in (0, 4, 63, 100, 500, 800, 1100, 4095):
            with self.subTest(size=size), self.assertRaises(gate.InvalidElf):
                gate.parse_elf(b[:size])

    def test_no_load_segment(self):
        b = bytearray(elf())
        struct.pack_into('<I', b, 64, 4)
        with self.assertRaisesRegex(gate.InvalidElf, 'no PT_LOAD'):
            gate.parse_elf(b)

    def test_invalid_dynamic_string_pointer(self):
        b = bytearray(elf(needed=('libc.so',)))
        struct.pack_into('<Q', b, 280, 100000)  # DT_STRTAB
        with self.assertRaises(gate.InvalidElf):
            gate.parse_elf(b)


class PackageTest(unittest.TestCase):
    def verify(self, files):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, 'test.apk')
            with zipfile.ZipFile(path, 'w') as z:
                for (abi, name), b in files.items():
                    z.writestr(f'lib/{abi}/{name}', b)
            return gate.verify_apk(path)[1]

    def test_complete_arm64(self):
        self.assertEqual(self.verify(arm64_package()), [])

    def test_x86_only_does_not_require_arm64(self):
        self.assertEqual(self.verify({('x86_64', 'libfoo.so'): elf(machine=62)}), [])

    def test_universal_dependencies_are_per_abi(self):
        files = arm64_package()
        files['x86_64', 'libfoo.so'] = elf(machine=62, needed=('libmupdf_java.so',))
        self.assertTrue(any('x86_64/libfoo.so: missing' in e for e in self.verify(files)))

    def test_missing_versioned_and_private_system_dependencies(self):
        for needed in ('libfoo.so.78', 'libart.so'):
            errors = self.verify({('x86_64', 'libfoo.so'): elf(machine=62, needed=(needed,))})
            self.assertTrue(any('missing same-ABI' in e for e in errors))

    def test_missing_tikz_and_unsupported_abi(self):
        files = arm64_package()
        del files['arm64-v8a', gate.TEX]
        self.assertTrue(any('missing required' in e for e in self.verify(files)))
        errors = self.verify({('x86_64', gate.TEX): elf(machine=62, exports=gate.JNI[gate.TEX])})
        self.assertTrue(any('unsupported TikZ' in e for e in errors))

    def test_graphite_must_be_static(self):
        files = arm64_package()
        files['arm64-v8a', gate.TEX] = elf(needed=(gate.GRAPHITE,), exports=gate.JNI[gate.TEX])
        self.assertTrue(any('missing same-ABI' in e for e in self.verify(files)))
        files['arm64-v8a', gate.GRAPHITE] = elf()
        self.assertTrue(any('statically linked' in e for e in self.verify(files)))
        self.assertTrue(any('still dynamically' in e for e in self.verify(files)))

    def test_empty_and_corrupt_native_package(self):
        self.assertTrue(self.verify({}))
        self.assertTrue(self.verify({('x86_64', 'libfoo.so'): b'not ELF'}))

    def test_duplicate_native_entry(self):
        import warnings
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, 'test.apk')
            with zipfile.ZipFile(path, 'w') as z, warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                for _ in range(2):
                    z.writestr('lib/x86_64/libfoo.so', elf(machine=62))
            self.assertTrue(any('duplicate' in e for e in gate.verify_apk(path)[1]))


if __name__ == '__main__':
    unittest.main()
