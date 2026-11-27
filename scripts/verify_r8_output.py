#!/usr/bin/env python3
"""Verify Release R8 outputs (configuration.txt & mapping.txt) against RikkaHub R8 invariants.

Checks performed from pre-R8 Release class files and a streaming mapping scan:
1. All `app/src/main/keepRules/*.keep` files were merged into `configuration.txt`.
2. SnakeYAML classes retain the `org.yaml.snakeyaml` package prefix in `mapping.txt`
   (prevents `Class.getPackage().getName()` NPE in `SkillFrontmatterParser`).
3. `Screen$` route classes and Shizuku IPC classes retain exact names and are not removed.
4. Every compiled sealed family member (including nested/interface implementations),
   generated Companion and serializer is present and live in `mapping.txt`.
5. Kept business classes retain names and are not horizontally merged.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import struct
from typing import Iterable

REMOVED_MARKER = "R8$$REMOVED$$CLASS$$"

EXPECTED_KEEP_FILES = (
    "diagnostics-and-navigation.keep",
    "ipc-and-reflection.keep",
    "serialization.keep",
    "third-party.keep",
)

SNAKEYAML_REQUIRED_LIVE_CLASSES = (
    "org.yaml.snakeyaml.Yaml",
    "org.yaml.snakeyaml.LoaderOptions",
    "org.yaml.snakeyaml.TypeDescription",
    "org.yaml.snakeyaml.constructor.SafeConstructor",
    "org.yaml.snakeyaml.internal.Logger",
)

EXACT_NAME_CLASSES = (
    "app.androidrik.rikkahub.RouteActivity",
    "app.androidrik.rikkahub.ShortcutEntryActivity",
    "app.androidrik.rikkahub.LauncherRikkaHubActivity",
    "app.androidrik.rikkahub.LauncherChatActivity",
    "app.androidrik.rikkahub.LauncherNotesActivity",
    "app.androidrik.rikkahub.LauncherAssistantActivity",
    "app.androidrik.rikkahub.phoneuse.PhoneUseDisplayService",
    "app.androidrik.rikkahub.phoneuse.IPhoneUseDisplayService",
)

SEALED_PREFIXES = (
    "app.androidrik.ai.core.InputSchema$",
    "app.androidrik.ai.provider.BuiltInTools$",
    "app.androidrik.ai.provider.CatalogFetchResult$",
    "app.androidrik.ai.provider.ProviderSetting$",
    "app.androidrik.ai.ui.StreamChunk$",
    "app.androidrik.ai.ui.UIMessageAnnotation$",
    "app.androidrik.ai.ui.ToolApprovalState$",
    "app.androidrik.ai.ui.UIMessagePart$",
    "app.androidrik.rikkahub.Screen$",
    "app.androidrik.rikkahub.data.model.PromptInjection$",
    "app.androidrik.rikkahub.data.model.Avatar$",
    "app.androidrik.rikkahub.data.ai.mcp.McpServerConfig$",
    "app.androidrik.rikkahub.data.ai.tools.local.LocalToolOption$",
    "app.androidrik.common.android.LogEntry$",
    "app.androidrik.common.http.ProviderProxy$",
    "app.androidrik.mediagen.model.MediaGenerationInput$",
    "app.androidrik.mediagen.provider.MediaGenerationProviderSetting$",
    "app.androidrik.search.SearchServiceOptions$",
    "app.androidrik.asr.ASRProviderSetting$",
    "app.androidrik.tts.provider.TTSProviderSetting$",
)

SYNTHETIC_INNER_RE = re.compile(
    r"(\$\$ExternalSynthetic|\$\$InternalSynthetic|\$WhenMappings|\$Companion$|\$\$serializer$|\$[0-9]+)"
)


def verify_configuration(config_text: str, keep_files: Iterable[str] = EXPECTED_KEEP_FILES) -> list[str]:
    errors: list[str] = []
    for keep_name in keep_files:
        if keep_name not in config_text:
            errors.append(f"configuration.txt 未包含 `{keep_name}`（AGP keepRules 可能未生效）")
    if "-keeppackagenames org.yaml.snakeyaml" not in config_text:
        errors.append("configuration.txt 缺少 `-keeppackagenames org.yaml.snakeyaml`")
    return errors


def class_identity(data: bytes) -> tuple[str, tuple[str, ...]]:
    """Read class/super/interface names from a pre-R8 JVM class file."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid JVM class file")
    count = struct.unpack_from(">H", data, 8)[0]
    pool: dict[int, str | int] = {}
    offset, index = 10, 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            pool[index] = data[offset:offset + size].decode("utf-8", errors="replace")
            offset += size
        elif tag == 7:
            pool[index] = struct.unpack_from(">H", data, offset)[0]
            offset += 2
        else:
            offset += sizes[tag]
        index += 2 if tag in (5, 6) else 1
    _, this_class, superclass, interface_count = struct.unpack_from(">HHHH", data, offset)
    interfaces = struct.unpack_from(f">{interface_count}H", data, offset + 8)

    def name(entry: int) -> str:
        return str(pool[pool[entry]]).replace("/", ".")

    parents = tuple(name(entry) for entry in (superclass, *interfaces) if entry)
    return name(this_class), parents


def expected_release_classes(repo_root: pathlib.Path) -> set[str]:
    """Derive the inheritance graph from this build's Kotlin and Java/AIDL inputs."""
    classes: dict[str, tuple[str, ...]] = {}
    for module in repo_root.iterdir():
        directories = (
            module / "build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes",
            module / "build/intermediates/javac/release/compileReleaseJavaWithJavac/classes",
        )
        for compiled in directories:
            if not compiled.is_dir():
                continue
            for path in compiled.rglob("*.class"):
                name, parents = class_identity(path.read_bytes())
                classes[name] = parents
    roots = {prefix[:-1] for prefix in SEALED_PREFIXES}
    roots.add("app.androidrik.ai.ui.PartMetadata")
    keep_file = repo_root / "app/src/main/keepRules/serialization.keep"
    roots.update(re.findall(r"(?m)^-keep class \* extends ([\w.$]+)", keep_file.read_text(encoding="utf-8")))
    roots.update(EXACT_NAME_CLASSES)
    missing = roots - classes.keys()
    if missing:
        raise ValueError(f"Missing pre-R8 Release classes: {sorted(missing)}")
    expected = set(roots)
    expected.update(name for name in classes
                    if name.startswith("app.androidrik.rikkahub.phoneuse.IPhoneUseDisplayService$"))
    while True:
        descendants = {name for name, parents in classes.items() if expected.intersection(parents)}
        added = descendants - expected
        if not added:
            break
        expected.update(added)
    # Synthetic serializers/companions are not subclasses. Include each generated input explicitly.
    expected.update({name + suffix for name in tuple(expected)
                     for suffix in ("$Companion", "$$serializer") if name + suffix in classes})
    return expected


def verify_mapping_lines(lines: Iterable[str], expected_classes: Iterable[str] = ()) -> list[str]:
    errors: list[str] = []
    seen_snakeyaml: dict[str, str] = {}
    seen_exact: dict[str, str] = {}
    screen_count = 0
    expected = set(expected_classes)
    seen_expected: set[str] = set()
    # family_prefix -> {target_class: original_class}
    family_targets: dict[str, dict[str, str]] = {p: {} for p in SEALED_PREFIXES}

    for raw_line in lines:
        if not raw_line or raw_line[0].isspace() or raw_line[0] == "#":
            continue
        arrow = raw_line.find(" -> ")
        if arrow <= 0:
            continue
        orig = raw_line[:arrow]
        rhs = raw_line[arrow + 4 :].rstrip().rstrip(":")
        if orig in expected:
            seen_expected.add(orig)
            if rhs.startswith(REMOVED_MARKER):
                errors.append(f"Required Release class removed: {orig}")
            elif orig in {p[:-1] for p in SEALED_PREFIXES} or not SYNTHETIC_INNER_RE.search(orig):
                if rhs != orig:
                    errors.append(f"Required class name changed: {orig} -> {rhs}")

        if orig.startswith("org.yaml.snakeyaml.") and not SYNTHETIC_INNER_RE.search(orig):
            if orig in SNAKEYAML_REQUIRED_LIVE_CLASSES:
                seen_snakeyaml[orig] = rhs
            if not rhs.startswith(REMOVED_MARKER) and not rhs.startswith("org.yaml.snakeyaml."):
                errors.append(
                    f"SnakeYAML 类 `{orig}` 包名被破坏（映射为 `{rhs}`），会导致 Android Class.getPackage() 返回 null"
                )

        if orig in EXACT_NAME_CLASSES:
            seen_exact[orig] = rhs
            if rhs != orig:
                errors.append(f"入口/跨进程类 `{orig}` 未保留原名（映射为 `{rhs}`）")

        if orig.startswith("app.androidrik.rikkahub.Screen$") and not SYNTHETIC_INNER_RE.search(orig):
            screen_count += 1
            if rhs != orig:
                errors.append(f"路由类 `{orig}` 被混淆或移除为 `{rhs}`（会破坏 NavStats 与路由恢复）")

        for prefix in SEALED_PREFIXES:
            if orig.startswith(prefix):
                tail = orig[len(prefix) :]
                if not SYNTHETIC_INNER_RE.search(orig):
                    if rhs.startswith(REMOVED_MARKER):
                        errors.append(f"@Serializable sealed 子类 `{orig}` 被 R8 移除（{rhs}）")
                    else:
                        prev = family_targets[prefix].get(rhs)
                        if prev is not None and prev != orig:
                            errors.append(
                                f"@Serializable sealed 子类发生类合并冲突：`{prev}` 与 `{orig}` 均映射为 `{rhs}`"
                            )
                        family_targets[prefix][rhs] = orig
                elif tail.endswith("$$serializer"):
                    if rhs.startswith(REMOVED_MARKER):
                        errors.append(f"sealed 子类序列化器 `{orig}` 被 R8 移除（{rhs}）")
                break

    for cls in SNAKEYAML_REQUIRED_LIVE_CLASSES:
        rhs = seen_snakeyaml.get(cls)
        if rhs is None or rhs.startswith(REMOVED_MARKER):
            errors.append(f"mapping.txt 未找到存活的 `{cls}`（Skill YAML 解析链可能缺失）")

    for cls in EXACT_NAME_CLASSES:
        if cls not in seen_exact:
            errors.append(f"mapping.txt 未找到入口/跨进程类 `{cls}`")

    if screen_count == 0:
        errors.append("mapping.txt 未找到任何 `Screen$` 路由子类")

    for prefix, targets in family_targets.items():
        if not targets:
            errors.append(f"mapping.txt 未找到 sealed 家族 `{prefix}*` 的任何存活子类")

    errors.extend(f"Required Release class absent from mapping: {name}"
                  for name in sorted(expected - seen_expected))
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify Release R8 configuration.txt and mapping.txt")
    parser.add_argument(
        "--mapping-dir",
        type=pathlib.Path,
        default=pathlib.Path("app/build/outputs/mapping/release"),
        help="Directory containing configuration.txt and mapping.txt",
    )
    args = parser.parse_args(argv)

    config_path = args.mapping_dir / "configuration.txt"
    mapping_path = args.mapping_dir / "mapping.txt"

    if not config_path.is_file():
        print(f"ERROR: 找不到 {config_path}", file=sys.stderr)
        return 2
    if not mapping_path.is_file():
        print(f"ERROR: 找不到 {mapping_path}", file=sys.stderr)
        return 2

    errors: list[str] = []
    try:
        expected = expected_release_classes(pathlib.Path(__file__).resolve().parent.parent)
    except (OSError, ValueError, KeyError, struct.error) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 2
    errors.extend(verify_configuration(config_path.read_text(encoding="utf-8", errors="replace")))
    with mapping_path.open("r", encoding="utf-8", errors="replace") as fh:
        errors.extend(verify_mapping_lines(fh, expected))

    if errors:
        print(f"R8 产物校验失败（共 {len(errors)} 项）：", file=sys.stderr)
        for item in errors:
            print(f"  - {item}", file=sys.stderr)
        return 1

    print(f"OK: R8 产物校验通过（{len(expected)} required classes; {mapping_path}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
