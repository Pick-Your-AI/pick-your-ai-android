#!/usr/bin/env python3
"""Inspect the packaged synthetic preview's manifest and backup declarations.

This is a static APK check, not installed/OEM backup, TLS or release acceptance.
The normal entry point requires the existing exact-source runtime inventory.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android:"
PACKAGE = "ai.pickyour.android.preview"
APK_PATH = "preview/build/outputs/apk/debug/preview-debug-unsigned.apk"
RULE_PATH = "res/xml/backup_rules.xml"
DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root",
           "device_file", "device_database", "device_sharedpref"}


class PolicyFailure(ValueError):
    """Only fixed diagnostic codes escape; never APK strings or tool stderr."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise PolicyFailure(code)


def sha256(path: Path) -> str:
    require(path.is_file() and not path.is_symlink(), "INPUT_NOT_REGULAR")
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def command(arguments: list[str], cwd: Path | None = None, *, read_stderr: bool = False) -> str:
    # SDK output can include arbitrary compiled strings. Retain only its digest,
    # and keep capture on disk with a command deadline and a post-run byte limit.
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as error:
        try:
            result = subprocess.run(arguments, cwd=cwd, stdout=error if read_stderr else output,
                                    stderr=output if read_stderr else error,
                                    timeout=30, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise PolicyFailure("TOOL_UNAVAILABLE_OR_TIMEOUT") from None
        require(result.returncode == 0 and error.tell() == 0, "TOOL_FAILED")
        require(output.tell() <= 2 * 1024 * 1024, "TOOL_OUTPUT_LIMIT")
        output.seek(0)
        try:
            return output.read().decode("utf-8", errors="strict")
        except UnicodeError:
            raise PolicyFailure("TOOL_OUTPUT_ENCODING") from None


def aapt2_version(aapt2: Path) -> str:
    # The pinned SDK writes a successful version to stderr. Only this command
    # reads that stream; binary inspection still rejects any stderr output.
    version = command([str(aapt2), "version"], read_stderr=True).strip()
    require(re.fullmatch(r"Android Asset Packaging Tool \(aapt\) [0-9]+(?:\.[0-9]+)+-[0-9]+", version)
            is not None, "TOOL_VERSION_FORMAT")
    return version


@dataclass
class Element:
    name: str
    attributes: dict[str, str] = field(default_factory=dict)
    children: list["Element"] = field(default_factory=list)


def xmltree(text: str) -> Element:
    """Parse the pinned AAPT2 tree grammar; unknown/truncated lines fail closed."""
    roots: list[Element] = []
    stack: list[tuple[int, Element]] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        require("\t" not in line, "XML_TREE_FORMAT")
        level = len(line) - len(line.lstrip(" "))
        body = line[level:]
        if re.fullmatch(r"N: android=http://schemas\.android\.com/apk/res/android \(line=\d+\)", body):
            require(not roots, "XML_NAMESPACE_POSITION")
            continue
        match = re.fullmatch(r"E: ([a-z][a-z0-9-]*) \(line=\d+\)", body)
        if match:
            while stack and stack[-1][0] >= level:
                stack.pop()
            node = Element(match[1])
            (stack[-1][1].children if stack else roots).append(node)
            stack.append((level, node))
            continue
        match = re.fullmatch(r"A: ([^=() ]+)(?:\(0x[0-9a-f]{8}\))?=(.+)", body)
        require(match is not None and bool(stack) and level > stack[-1][0], "XML_TREE_FORMAT")
        name = match[1].replace(ANDROID, "android:")
        require(name not in stack[-1][1].attributes, "XML_ATTRIBUTE_DUPLICATE")
        value = match[2]
        if value.startswith('"'):
            # AAPT2 includes the raw spelling for these strings. Both copies
            # must agree; newline or ambiguous display output is not accepted.
            quoted = re.fullmatch(r'("(?:[^"\\]|\\.)*") \(Raw: ("(?:[^"\\]|\\.)*")\)', value)
            require(quoted is not None, "XML_STRING_FORMAT")
            try:
                decoded, raw = json.loads(quoted[1]), json.loads(quoted[2])
            except ValueError:
                raise PolicyFailure("XML_STRING_FORMAT") from None
            require(decoded == raw, "XML_RAW_VALUE_MISMATCH")
            value = decoded
        else:
            require(re.fullmatch(r"(?:true|false|[0-9]+|@0x[0-9a-f]{8})", value) is not None,
                    "XML_VALUE_FORMAT")
        stack[-1][1].attributes[name] = value
    require(len(roots) == 1, "XML_ROOT_COUNT")
    return roots[0]


def manifest_policy(root: Element) -> str:
    require(root.name == "manifest", "MANIFEST_ROOT")
    require(set(root.attributes) <= {"package", "android:versionCode", "android:versionName",
            "android:compileSdkVersion", "android:compileSdkVersionCodename",
            "platformBuildVersionCode", "platformBuildVersionName"}, "MANIFEST_ATTRIBUTE")
    require(root.attributes.get("package") == PACKAGE, "PACKAGE_IDENTITY")
    require(root.attributes.get("android:versionName") == "0.0.1-synthetic" and
            root.attributes.get("android:versionCode") == "1", "PREVIEW_VERSION")
    # An exact structural allowlist rejects all permission forms, instrumentation,
    # providers, receivers, services, aliases, URI grants and alternate entry points.
    require(sorted(n.name for n in root.children) == ["application", "uses-sdk"], "MANIFEST_ENTRY_POINTS")
    sdk = next(n for n in root.children if n.name == "uses-sdk")
    require(sdk.attributes == {"android:minSdkVersion": "26", "android:targetSdkVersion": "35"}
            and not sdk.children, "SDK_BOUNDARY")
    app = next(n for n in root.children if n.name == "application")
    require(set(app.attributes) <= {"android:theme", "android:label", "android:icon",
            "android:debuggable", "android:allowBackup", "android:supportsRtl",
            "android:extractNativeLibs", "android:fullBackupContent", "android:usesCleartextTraffic",
            "android:dataExtractionRules"}, "APPLICATION_ATTRIBUTE")
    for name, expected in {"android:debuggable": "true", "android:allowBackup": "false",
                           "android:fullBackupContent": "false", "android:usesCleartextTraffic": "false"}.items():
        require(app.attributes.get(name) == expected, "APPLICATION_POLICY")
    resource = app.attributes.get("android:dataExtractionRules", "")
    require(re.fullmatch(r"@0x[0-9a-f]{8}", resource) is not None, "BACKUP_RESOURCE_REFERENCE")
    require(len(app.children) == 1 and app.children[0].name == "activity", "APPLICATION_ENTRY_POINTS")
    activity = app.children[0]
    require(activity.attributes == {"android:name": f"{PACKAGE}.PreviewActivity", "android:exported": "true",
                                    "android:launchMode": "1"},
            "ACTIVITY_POLICY")
    require(len(activity.children) == 1 and activity.children[0].name == "intent-filter", "INTENT_FILTERS")
    intent = activity.children[0]
    require(not intent.attributes and sorted(n.name for n in intent.children) == ["action", "category"],
            "INTENT_FILTERS")
    for node in intent.children:
        expected = "android.intent.action.MAIN" if node.name == "action" else "android.intent.category.LAUNCHER"
        require(node.attributes == {"android:name": expected} and not node.children, "LAUNCHER_POLICY")
    return resource[1:]


def backup_resource_policy(table: str, resource: str) -> None:
    # Match the referenced ID, not merely the presence of a safe-looking file.
    # Duplicate headers (including a spoof in a raw string) and resource aliases
    # cannot satisfy this single default-file entry requirement.
    lines = table.splitlines()
    starts = [i for i, line in enumerate(lines)
              if re.fullmatch(rf"    resource {re.escape(resource)} xml/backup_rules", line)]
    require(len(starts) == 1, "BACKUP_RESOURCE_IDENTITY")
    index = starts[0] + 1
    end = index
    while end < len(lines) and not re.match(r"(?:Package |  type |    resource )", lines[end]):
        end += 1
    require(lines[index:end] == [f"      () (file) {RULE_PATH} type=XML"], "BACKUP_RESOURCE_CONFIGURATION")


def backup_policy(root: Element) -> None:
    require(root.name == "data-extraction-rules" and not root.attributes, "BACKUP_ROOT")
    require(sorted(n.name for n in root.children) == ["cloud-backup", "device-transfer"], "BACKUP_TRANSPORTS")
    for transport in root.children:
        require(not transport.attributes and len(transport.children) == len(DOMAINS), "BACKUP_EXCLUSIONS")
        seen: set[str] = set()
        for exclusion in transport.children:
            require(exclusion.name == "exclude" and not exclusion.children and
                    set(exclusion.attributes) == {"domain", "path"} and
                    exclusion.attributes["path"] == ".", "BACKUP_EXCLUSIONS")
            domain = exclusion.attributes["domain"]
            require(domain in DOMAINS and domain not in seen, "BACKUP_EXCLUSIONS")
            seen.add(domain)
        require(seen == DOMAINS, "BACKUP_EXCLUSIONS")


def inspect_apk(apk: Path, aapt2: Path) -> dict[str, object]:
    before = sha256(apk)
    try:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)) and "AndroidManifest.xml" in names and RULE_PATH in names,
                    "APK_ENTRIES")
    except (OSError, zipfile.BadZipFile):
        raise PolicyFailure("APK_UNREADABLE") from None
    manifest = command([str(aapt2), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"])
    resource = manifest_policy(xmltree(manifest))
    table = command([str(aapt2), "dump", "resources", str(apk)])
    backup_resource_policy(table, resource)
    backup = command([str(aapt2), "dump", "xmltree", str(apk), "--file", RULE_PATH])
    backup_policy(xmltree(backup))
    require(sha256(apk) == before, "APK_CHANGED_DURING_INSPECTION")
    return {"sha256": before, "bytes": apk.stat().st_size, "package": PACKAGE,
            "permission_count": 0, "exported_activities": [f"{PACKAGE}.PreviewActivity"],
            "cleartext_traffic": False, "allow_backup": False, "full_backup_content": False,
            "backup_resource": resource, "excluded_domains_per_transport": sorted(DOMAINS),
            "observations_sha256": {name: hashlib.sha256(value.encode()).hexdigest()
                                    for name, value in [("manifest", manifest), ("resources", table), ("backup", backup)]}}


def source_identity(root: Path) -> dict[str, str]:
    # One resolved commit supplies both fields; a separate current-tree check
    # rejects a tracked edit instead of attributing it to the committed source.
    fields = command(["git", "show", "-s", "--format=%H%n%T", "HEAD"], root).strip().splitlines()
    require(len(fields) == 2 and all(re.fullmatch(r"[0-9a-f]{40}", value) for value in fields),
            "SOURCE_IDENTITY_INVALID")
    require(not command(["git", "status", "--porcelain", "--untracked-files=all"], root).strip(), "SOURCE_CHANGED")
    return {"commit": fields[0], "tree": fields[1]}


def check(root: Path, aapt2: Path) -> dict[str, object]:
    inventory_path = root / "build/inventory/runtime-inventory.json"
    inventory_hash = sha256(inventory_path)
    inventory = json.loads(inventory_path.read_text())
    source = source_identity(root)
    require(inventory.get("schema_version") == 1 and inventory.get("scope") == "resolved-runtime-classpaths" and
            inventory["source"]["commit"] == source["commit"] and inventory["source"]["tree"] == source["tree"],
            "INVENTORY_SOURCE_MISMATCH")
    matches = [x for x in inventory["outputs"] if x.get("kind") == "unsigned-debug-preview"]
    require(len(matches) == 1 and matches[0].get("file") == APK_PATH, "INVENTORY_APK_IDENTITY")
    expected = matches[0]
    require(sha256(root / APK_PATH) == expected.get("sha256") and
            (root / APK_PATH).stat().st_size == expected.get("bytes"), "INVENTORY_APK_MISMATCH")
    inspection = inspect_apk(root / APK_PATH, aapt2)
    tool = {"sha256": sha256(aapt2), "version": aapt2_version(aapt2)}
    require(inspection["sha256"] == expected["sha256"] and sha256(inventory_path) == inventory_hash,
            "INVENTORY_CHANGED_DURING_INSPECTION")
    # A passing binary observation is not a receipt for a branch switch or edit
    # that occurred while AAPT2 was running. Keep this fence after tool work.
    require(source_identity(root) == source, "SOURCE_CHANGED_DURING_INSPECTION")
    return {"schema_version": 1, "status": "passed", "scope": "packaged-synthetic-preview-policy",
            "source": source, "runtime_inventory_sha256": inventory_hash, "aapt2": tool,
            "apk": inspection, "limitations": [
                "Static declarations in the unsigned debug APK; no release or installed execution.",
                "No OEM backup/transfer, actual network, credential, logging or device security acceptance.",
                "External browser, explicit diagnostic sharing and OS input/accessibility services remain separate boundaries."]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt2", type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / "build/inventory/preview-policy.json"
    # An unsuccessful current inspection must not leave an older passing receipt.
    output.unlink(missing_ok=True)
    try:
        receipt = check(ROOT, args.aapt2)
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", dir=output.parent, delete=False) as temporary:
            json.dump(receipt, temporary, indent=2, sort_keys=True)
            temporary.write("\n")
            temporary_path = Path(temporary.name)
        temporary_path.replace(output)
    except (PolicyFailure, OSError, ValueError, KeyError, TypeError):
        print("Packaged preview policy failed; no passing receipt retained.")
        return 1
    print("PASS: exact inventoried APK retains the synthetic preview manifest and backup policy.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
