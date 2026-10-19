#!/usr/bin/env python3
"""Exercise the policy against real AAPT2-packaged, disposable negative fixtures."""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
import struct
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from check_preview_policy import ANDROID, PACKAGE, PolicyFailure, command, inspect_apk, require, sha256

ROOT = Path(__file__).resolve().parents[1]
NS = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", NS[1:-1])


def change_framework_attributes(apk: Path, *, replacement: int | None = None,
                                swap_backup_attributes: bool = False) -> None:
    """Change only an ID or record order in this disposable compiled fixture."""
    require((replacement is not None) != swap_backup_attributes, "FIXTURE_ATTRIBUTE_OPERATION")
    with zipfile.ZipFile(apk) as archive:
        entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
    manifest = bytearray(next(data for entry, data in entries if entry.filename == "AndroidManifest.xml"))
    require(len(manifest) >= 8, "FIXTURE_XML_HEADER")
    kind, header, size = struct.unpack_from("<HHI", manifest)
    require(kind == 0x0003 and header == 8 and size == len(manifest), "FIXTURE_XML_HEADER")
    offset, changed = header, 0
    resource_ids: list[int] = []
    while offset < len(manifest):
        require(offset + 8 <= len(manifest), "FIXTURE_XML_CHUNK")
        kind, header, size = struct.unpack_from("<HHI", manifest, offset)
        require(8 <= header <= size and offset + size <= len(manifest), "FIXTURE_XML_CHUNK")
        if kind == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
            require(header == 8 and (size - header) % 4 == 0, "FIXTURE_RESOURCE_MAP")
            resource_ids = list(struct.unpack_from(f"<{(size - header) // 4}I", manifest, offset + header))
            for index in range(offset + header, offset + size, 4):
                if replacement is not None and struct.unpack_from("<I", manifest, index)[0] == 0x01010280:
                    struct.pack_into("<I", manifest, index, replacement)
                    changed += 1
        elif kind == 0x0102 and swap_backup_attributes:  # RES_XML_START_ELEMENT_TYPE
            require(header == 16 and size >= header + 20, "FIXTURE_ELEMENT_HEADER")
            start, width, count = struct.unpack_from("<HHH", manifest, offset + header + 8)
            require(start >= 20 and width == 20 and header + start + count * width <= size,
                    "FIXTURE_ATTRIBUTE_RECORDS")
            positions: dict[int, int] = {}
            for index in range(count):
                position = offset + header + start + index * width
                name_index = struct.unpack_from("<I", manifest, position + 4)[0]
                resource_id = resource_ids[name_index] if name_index < len(resource_ids) else 0
                if resource_id in {0x01010280, 0x010104eb}:
                    require(resource_id not in positions, "FIXTURE_ATTRIBUTE_IDENTITY")
                    positions[resource_id] = position
            if len(positions) == 2:
                first, second = positions[0x01010280], positions[0x010104eb]
                require(first < second, "FIXTURE_ATTRIBUTE_ORDER")
                saved = manifest[first:first + width]
                manifest[first:first + width] = manifest[second:second + width]
                manifest[second:second + width] = saved
                changed += 1
        offset += size
    require(changed == 1, "FIXTURE_ATTRIBUTE_IDENTITY")
    rewritten = apk.with_suffix(".remapped.apk")
    with zipfile.ZipFile(rewritten, "w") as archive:
        for entry, data in entries:
            archive.writestr(entry, manifest if entry.filename == "AndroidManifest.xml" else data)
    rewritten.replace(apk)


def change_compiled_value_to_string(apk: Path, attribute_id: int, expected_type: int) -> None:
    """Preserve a disposable value's displayed text while changing its binary kind."""
    with zipfile.ZipFile(apk) as archive:
        entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
    manifest = bytearray(next(data for entry, data in entries if entry.filename == "AndroidManifest.xml"))
    require(len(manifest) >= 8, "FIXTURE_XML_HEADER")
    kind, header, size = struct.unpack_from("<HHI", manifest)
    require(kind == 0x0003 and header == 8 and size == len(manifest), "FIXTURE_XML_HEADER")
    offset, target, pool = header, None, None
    resource_ids: list[int] = []
    while offset < len(manifest):
        require(offset + 8 <= len(manifest), "FIXTURE_XML_CHUNK")
        kind, header, size = struct.unpack_from("<HHI", manifest, offset)
        require(8 <= header <= size and offset + size <= len(manifest), "FIXTURE_XML_CHUNK")
        if kind == 0x0001:  # RES_STRING_POOL_TYPE
            require(pool is None and header == 28, "FIXTURE_STRING_POOL")
            pool = (offset, header, size)
        elif kind == 0x0180:
            require(header == 8 and (size - header) % 4 == 0, "FIXTURE_RESOURCE_MAP")
            resource_ids = list(struct.unpack_from(f"<{(size - header) // 4}I", manifest, offset + header))
        elif kind == 0x0102:
            require(header == 16 and size >= header + 20, "FIXTURE_ELEMENT_HEADER")
            start, width, count = struct.unpack_from("<HHH", manifest, offset + header + 8)
            require(start >= 20 and width == 20 and header + start + count * width <= size,
                    "FIXTURE_ATTRIBUTE_RECORDS")
            for index in range(count):
                position = offset + header + start + index * width
                name_index = struct.unpack_from("<I", manifest, position + 4)[0]
                if name_index < len(resource_ids) and resource_ids[name_index] == attribute_id:
                    require(target is None, "FIXTURE_ATTRIBUTE_IDENTITY")
                    target = position
        offset += size
    require(target is not None and pool is not None, "FIXTURE_ATTRIBUTE_IDENTITY")
    require(manifest[target + 15] == expected_type, "FIXTURE_ATTRIBUTE_VALUE_KIND")
    value = struct.unpack_from("<I", manifest, target + 16)[0]
    if expected_type == 0x01:  # TYPE_REFERENCE
        text = f"@0x{value:08x}"
    elif expected_type == 0x12:  # TYPE_INT_BOOLEAN
        text = "true" if value else "false"
    else:
        require(expected_type == 0x10, "FIXTURE_ATTRIBUTE_VALUE_KIND")  # TYPE_INT_DEC
        text = str(value)

    offset, header, size = pool
    count, styles, flags, start, style_start = struct.unpack_from("<IIIII", manifest, offset + 8)
    require(styles == 0 and style_start == 0 and start == header + count * 4 and start <= size,
            "FIXTURE_STRING_POOL")
    # These fixed ASCII values use the short length form in either pool encoding.
    require(len(text) < 128, "FIXTURE_STRING_LENGTH")
    encoded = (bytes([len(text), len(text)]) + text.encode("ascii") + b"\0" if flags & 0x100 else
               struct.pack("<H", len(text)) + text.encode("utf-16-le") + b"\0\0")
    encoded += b"\0" * (-len(encoded) % 4)
    replacement = bytearray(manifest[offset:offset + header])
    struct.pack_into("<I", replacement, 8, count + 1)
    struct.pack_into("<I", replacement, 16, flags & ~1)  # Appending does not retain sorted order.
    struct.pack_into("<I", replacement, 20, start + 4)
    replacement += manifest[offset + header:offset + start]
    replacement += struct.pack("<I", size - start)
    replacement += manifest[offset + start:offset + size] + encoded
    struct.pack_into("<I", replacement, 4, len(replacement))
    struct.pack_into("<I", manifest, target + 8, count)  # Matching raw string spelling.
    manifest[target + 15] = 0x03  # TYPE_STRING, not the original compiled scalar/reference.
    struct.pack_into("<I", manifest, target + 16, count)
    manifest = manifest[:offset] + replacement + manifest[offset + size:]
    struct.pack_into("<I", manifest, 4, len(manifest))
    rewritten = apk.with_suffix(".string-value.apk")
    with zipfile.ZipFile(rewritten, "w") as archive:
        for entry, data in entries:
            archive.writestr(entry, manifest if entry.filename == "AndroidManifest.xml" else data)
    rewritten.replace(apk)


def check_guards(aapt2: Path, android_jar: Path) -> list[dict[str, object]]:
    base = ET.fromstring(f'''<manifest xmlns:android="{ANDROID[:-1]}" package="{PACKAGE}">
      <application android:label="Synthetic policy fixture" android:debuggable="true"
        android:allowBackup="false" android:fullBackupContent="false"
        android:usesCleartextTraffic="false" android:dataExtractionRules="@xml/backup_rules">
        <activity android:name="{PACKAGE}.PreviewActivity" android:exported="true" android:launchMode="singleTop">
          <intent-filter><action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
        </activity>
      </application></manifest>''')
    rules = ET.parse(ROOT / "preview/src/main/res/xml/backup_rules.xml").getroot()
    results = []
    cases = [("adopted-policy", None), ("added-network-permission", "MANIFEST_ENTRY_POINTS"),
             ("added-receiver", "APPLICATION_ENTRY_POINTS"), ("enabled-cleartext", "APPLICATION_POLICY"),
             ("incomplete-device-transfer", "BACKUP_EXCLUSIONS"),
             ("redirected-backup-resource", "BACKUP_RESOURCE_IDENTITY"),
             ("unmapped-framework-attribute", "XML_ATTRIBUTE_RESOURCE_ID"),
             ("mismatched-framework-attribute", "XML_ATTRIBUTE_RESOURCE_ID"),
             ("reordered-framework-attributes", "XML_ATTRIBUTE_RESOURCE_ORDER"),
             ("string-backup-resource", "XML_ATTRIBUTE_VALUE_KIND"),
             ("string-backup-boolean", "XML_ATTRIBUTE_VALUE_KIND"),
             ("string-launch-mode", "XML_ATTRIBUTE_VALUE_KIND")]
    for name, rejection in cases:
        manifest, backup = copy.deepcopy(base), copy.deepcopy(rules)
        app = manifest.find("application")
        if name == "added-network-permission":
            ET.SubElement(manifest, "uses-permission", {NS + "name": "android.permission.INTERNET"})
        elif name == "added-receiver":
            ET.SubElement(app, "receiver", {NS + "name": ".FixtureReceiver", NS + "exported": "true"})
        elif name == "enabled-cleartext":
            app.set(NS + "usesCleartextTraffic", "true")
        elif name == "incomplete-device-transfer":
            transport = backup.find("device-transfer")
            transport.remove(transport[-1])
        elif name == "redirected-backup-resource":
            app.set(NS + "dataExtractionRules", "@xml/other_rules")
        with tempfile.TemporaryDirectory(prefix="preview-policy-fixture-") as temporary:
            folder = Path(temporary)
            xml = folder / "res/xml"
            xml.mkdir(parents=True)
            ET.ElementTree(backup).write(xml / "backup_rules.xml", encoding="utf-8", xml_declaration=True)
            if name == "redirected-backup-resource":
                (xml / "other_rules.xml").write_text("<data-extraction-rules/>")
            ET.ElementTree(manifest).write(folder / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
            command([str(aapt2), "compile", "--dir", str(folder / "res"), "-o", str(folder / "resources.zip")])
            apk = folder / "fixture.apk"
            command([str(aapt2), "link", "-I", str(android_jar), "--manifest", str(folder / "AndroidManifest.xml"),
                     "--min-sdk-version", "26", "--target-sdk-version", "35", "--version-code", "1",
                     "--version-name", "0.0.1-synthetic", "-o", str(apk), str(folder / "resources.zip")])
            if name in {"unmapped-framework-attribute", "mismatched-framework-attribute"}:
                change_framework_attributes(apk, replacement=0 if name == "unmapped-framework-attribute" else 0x01010281)
            elif name == "reordered-framework-attributes":
                change_framework_attributes(apk, swap_backup_attributes=True)
            elif name in {"string-backup-resource", "string-backup-boolean", "string-launch-mode"}:
                attribute_id, expected_type = {
                    "string-backup-resource": (0x0101063e, 0x01),
                    "string-backup-boolean": (0x010104eb, 0x12),
                    "string-launch-mode": (0x0101001d, 0x10),
                }[name]
                change_compiled_value_to_string(apk, attribute_id, expected_type)
            observed = None
            try:
                inspect_apk(apk, aapt2)
            except PolicyFailure as failure:
                observed = str(failure)
            if observed != rejection:
                raise PolicyFailure("PACKAGED_NEGATIVE_CASE_DID_NOT_REACH_EXPECTED_BOUNDARY")
            results.append({"case": name, "status": "passed", "expected_rejection": rejection,
                            "observed_rejection": observed, "fixture_apk_sha256": sha256(apk)})
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / "build/inventory/preview-policy-guards.json"
    output.unlink(missing_ok=True)
    receipt = {"schema_version": 1, "scope": "disposable-aapt2-packaging-policy-fixtures",
               "source_commit": command(["git", "rev-parse", "HEAD"], ROOT).strip(),
               "aapt2_sha256": sha256(args.aapt2), "android_jar_sha256": sha256(args.android_jar),
               "cases": check_guards(args.aapt2, args.android_jar),
               "limitations": "Binary declaration fixtures only; no app execution, installed or release result."}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print("PASS: adopted policy and eleven real packaged negative cases reached their exact expected boundary.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
