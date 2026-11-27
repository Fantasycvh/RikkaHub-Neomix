#!/usr/bin/env python3
"""Unit tests for scripts/verify_r8_output.py."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_r8_output as vr8  # noqa: E402


def _valid_mapping_lines() -> list[str]:
    lines = [
        "org.yaml.snakeyaml.Yaml -> org.yaml.snakeyaml.a:",
        "org.yaml.snakeyaml.LoaderOptions -> org.yaml.snakeyaml.b:",
        "org.yaml.snakeyaml.TypeDescription -> org.yaml.snakeyaml.c:",
        "org.yaml.snakeyaml.constructor.SafeConstructor -> org.yaml.snakeyaml.constructor.a:",
        "org.yaml.snakeyaml.internal.Logger -> org.yaml.snakeyaml.internal.a:",
        "app.androidrik.rikkahub.phoneuse.PhoneUseDisplayService -> app.androidrik.rikkahub.phoneuse.PhoneUseDisplayService:",
        "app.androidrik.rikkahub.phoneuse.IPhoneUseDisplayService -> app.androidrik.rikkahub.phoneuse.IPhoneUseDisplayService:",
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
