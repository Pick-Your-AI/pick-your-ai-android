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


def remap_allow_backup(apk: Path, replacement: int) -> None:
    """Change only one framework ID in this disposable compiled fixture."""
    with zipfile.ZipFile(apk) as archive:
        entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
    manifest = bytearray(next(data for entry, data in entries if entry.filename == "AndroidManifest.xml"))
    require(len(manifest) >= 8, "FIXTURE_XML_HEADER")
    kind, header, size = struct.unpack_from("<HHI", manifest)
    require(kind == 0x0003 and header == 8 and size == len(manifest), "FIXTURE_XML_HEADER")
    offset, changed = header, 0
    while offset < len(manifest):
        require(offset + 8 <= len(manifest), "FIXTURE_XML_CHUNK")
        kind, header, size = struct.unpack_from("<HHI", manifest, offset)
        require(8 <= header <= size and offset + size <= len(manifest), "FIXTURE_XML_CHUNK")
        if kind == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
            require(header == 8 and (size - header) % 4 == 0, "FIXTURE_RESOURCE_MAP")
            for index in range(offset + header, offset + size, 4):
                if struct.unpack_from("<I", manifest, index)[0] == 0x01010280:
                    struct.pack_into("<I", manifest, index, replacement)
                    changed += 1
        offset += size
    require(changed == 1, "FIXTURE_ATTRIBUTE_IDENTITY")
    rewritten = apk.with_suffix(".remapped.apk")
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
             ("mismatched-framework-attribute", "XML_ATTRIBUTE_RESOURCE_ID")]
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
                remap_allow_backup(apk, 0 if name == "unmapped-framework-attribute" else 0x01010281)
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
    print("PASS: adopted policy and seven real packaged negative cases reached their exact expected boundary.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
