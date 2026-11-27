#!/usr/bin/env python3
"""Unit tests for scripts/verify_r8_output.py."""

from __future__ import annotations

import sys
import struct
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_r8_output as vr8  # noqa: E402


def _class_bytes(name: str, parent: str = "java.lang.Object", interfaces: tuple[str, ...] = ()) -> bytes:
    """Minimal class header with a real JVM constant pool and inheritance entries."""
    constants = bytearray()
    indices = []
    for index, value in enumerate((name, parent, *interfaces)):
        encoded = value.replace(".", "/").encode()
        constants.extend(b"\x01" + struct.pack(">H", len(encoded)) + encoded)
        constants.extend(b"\x07" + struct.pack(">H", index * 2 + 1))
        indices.append(index * 2 + 2)
    header = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 65, len(indices) * 2 + 1)
    inheritance = struct.pack(">HHHH", 0x21, indices[0], indices[1], len(interfaces))
    return header + constants + inheritance + struct.pack(f">{len(interfaces)}H", *indices[2:])


def _valid_mapping_lines() -> list[str]:
    lines = [
        "org.yaml.snakeyaml.Yaml -> org.yaml.snakeyaml.a:",
        "org.yaml.snakeyaml.LoaderOptions -> org.yaml.snakeyaml.b:",
        "org.yaml.snakeyaml.TypeDescription -> org.yaml.snakeyaml.c:",
        "org.yaml.snakeyaml.constructor.SafeConstructor -> org.yaml.snakeyaml.constructor.a:",
        "org.yaml.snakeyaml.internal.Logger -> org.yaml.snakeyaml.internal.a:",
        *(f"{name} -> {name}:" for name in vr8.EXACT_NAME_CLASSES),
        "app.androidrik.rikkahub.Screen$Chat -> app.androidrik.rikkahub.Screen$Chat:",
    ]
    for idx, prefix in enumerate(vr8.SEALED_PREFIXES):
        if prefix == "app.androidrik.rikkahub.Screen$":
            continue
        lines.append(f"{prefix}SubA -> {prefix}SubA:")
        lines.append(f"{prefix}SubA$$serializer -> s{idx}a:")
        lines.append(f"{prefix}SubB -> {prefix}SubB:")
    return lines


class VerifyR8OutputTest(unittest.TestCase):
    def test_inventory_tracks_inheritance_interfaces_and_generated_classes(self) -> None:
        roots = ({prefix[:-1] for prefix in vr8.SEALED_PREFIXES}
                 | {"app.androidrik.ai.ui.PartMetadata"} | set(vr8.EXACT_NAME_CLASSES))
        roots.add("app.androidrik.NewlyRegisteredFamily")
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            keep_file = repo / "app/src/main/keepRules/serialization.keep"
            keep_file.parent.mkdir(parents=True)
            keep_file.write_text("-keep class * extends app.androidrik.NewlyRegisteredFamily { *; }\n")
            compiled = repo / "app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes"
            compiled.mkdir(parents=True)
            inputs = {name: _class_bytes(name) for name in roots}
            nested = "app.androidrik.ai.provider.ProviderSetting$Account$Nested"
            inputs[nested] = _class_bytes(nested, "app.androidrik.ai.provider.ProviderSetting")
            inputs[nested + "$$serializer"] = _class_bytes(nested + "$$serializer")
            metadata = "app.androidrik.ai.ui.MetadataOutsideRootFile"
            inputs[metadata] = _class_bytes(metadata, interfaces=("app.androidrik.ai.ui.PartMetadata",))
            binder = "app.androidrik.rikkahub.phoneuse.IPhoneUseDisplayService$_Parcel"
            javac = repo / "app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
            javac.mkdir(parents=True)
            (javac / "Parcel.class").write_bytes(_class_bytes(binder))
            inputs["app.androidrik.Unrelated"] = _class_bytes("app.androidrik.Unrelated")
            for name, data in inputs.items():
                (compiled / (name + ".class")).write_bytes(data)
            expected = vr8.expected_release_classes(repo)
            self.assertEqual(roots | {nested, nested + "$$serializer", metadata, binder}, expected)

    def test_inventory_rejects_missing_release_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            keep_file = Path(directory) / "app/src/main/keepRules/serialization.keep"
            keep_file.parent.mkdir(parents=True)
            keep_file.write_text("")
            with self.assertRaisesRegex(ValueError, "Missing pre-R8"):
                vr8.expected_release_classes(Path(directory))

    def test_detects_class_absent_from_mapping(self) -> None:
        expected = {"app.androidrik.ai.provider.ProviderSetting$Account$OAuth"}
        errors = vr8.verify_mapping_lines(_valid_mapping_lines(), expected)
        self.assertTrue(any("absent" in error and "OAuth" in error for error in errors))

    def test_checks_nested_subclasses_and_missing_serializers(self) -> None:
        lines = _valid_mapping_lines() + [
            "app.androidrik.ai.provider.ProviderSetting$Account$OAuth -> R8$$REMOVED$$CLASS$$1:",
        ]
        expected = {"app.androidrik.ai.provider.ProviderSetting$Account$OAuth$$serializer"}
        errors = vr8.verify_mapping_lines(lines, expected)
        self.assertTrue(any("OAuth" in error and "R8$$REMOVED" in error for error in errors))
        self.assertTrue(any("OAuth$$serializer" in error and "absent" in error for error in errors))

    def test_valid_configuration_and_mapping_pass(self) -> None:
        cfg = "\n".join(vr8.EXPECTED_KEEP_FILES) + "\n-keeppackagenames org.yaml.snakeyaml,org.yaml.snakeyaml.**\n"
        self.assertEqual([], vr8.verify_configuration(cfg))
        self.assertEqual([], vr8.verify_mapping_lines(_valid_mapping_lines()))

    def test_detects_missing_keep_file_in_configuration(self) -> None:
        cfg = "diagnostics-and-navigation.keep\nipc-and-reflection.keep\nthird-party.keep\n-keeppackagenames org.yaml.snakeyaml\n"
        errs = vr8.verify_configuration(cfg)
        self.assertTrue(any("serialization.keep" in e for e in errs), errs)

    def test_detects_repackaged_snakeyaml_into_default_package(self) -> None:
        lines = [
            "org.yaml.snakeyaml.TypeDescription -> td0:" if l.startswith("org.yaml.snakeyaml.TypeDescription ") else l
            for l in _valid_mapping_lines()
        ]
        errs = vr8.verify_mapping_lines(lines)
        self.assertTrue(any("TypeDescription" in e for e in errs), errs)

    def test_detects_obfuscated_screen_route(self) -> None:
        lines = [
            "app.androidrik.rikkahub.Screen$Chat -> crc:" if l.startswith("app.androidrik.rikkahub.Screen$Chat ") else l
            for l in _valid_mapping_lines()
        ]
        errs = vr8.verify_mapping_lines(lines)
        self.assertTrue(any("Screen$Chat" in e for e in errs), errs)

    def test_detects_removed_sealed_subclass_and_horizontal_merging(self) -> None:
        lines = _valid_mapping_lines()
        lines.append("app.androidrik.rikkahub.data.ai.tools.local.LocalToolOption$PhoneUse -> R8$$REMOVED$$CLASS$$99:")
        lines.append("app.androidrik.ai.ui.UIMessagePart$Text -> merged0:")
        lines.append("app.androidrik.ai.ui.UIMessagePart$Image -> merged0:")
        errs = vr8.verify_mapping_lines(lines)
        self.assertTrue(any("LocalToolOption$PhoneUse" in e for e in errs), errs)
        self.assertTrue(any("类合并冲突" in e for e in errs), errs)


if __name__ == "__main__":
    unittest.main()
