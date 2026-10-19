#!/usr/bin/env python3
"""Bind the finite public preview configuration to the exact inventoried APK."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile
from check_preview_policy import APK_PATH, PolicyFailure, require, sha256, source_identity

ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = "preview/src/main/assets/client-environment.json"
MEMBER = "assets/client-environment.json"
MAX_BYTES = 4096
EXPECTED = {
    "configuration_version": "pyai.android.environment.v1", "environment": "preview",
    "application_id": "ai.pickyour.android.preview", "application_version": "0.0.1-synthetic",
    "application_version_code": 1, "api_base_url": None, "api_version": None,
    "contract_version": "1.0.0", "catalog_schema_version": "1.0.0",
    "catalog_id": "catalog.fixture.1", "catalog_revision": 1,
    "capabilities": ["local_drafts", "synthetic_catalog"],
}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "CONFIGURATION_DUPLICATE_FIELD")
        result[key] = value
    return result


def validate(data: bytes) -> dict:
    require(0 < len(data) <= MAX_BYTES, "CONFIGURATION_SIZE")
    try:
        value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=unique_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        # Serialized structural equality distinguishes true/1 and 1.0/1 too.
        require(json.dumps(value, sort_keys=True, allow_nan=False) == json.dumps(EXPECTED, sort_keys=True),
                "CONFIGURATION_PROFILE_MISMATCH")
    except (UnicodeError, ValueError, RecursionError, TypeError):
        raise PolicyFailure("CONFIGURATION_INVALID") from None
    return dict(EXPECTED)


def source_bytes(root: Path) -> bytes:
    source = root / SOURCE_PATH
    require(source.is_file() and not source.is_symlink(), "CONFIGURATION_SOURCE_MISSING")
    with source.open("rb") as stream:
        data = stream.read(MAX_BYTES + 1)
    validate(data)
    return data


def inspect_member(apk: Path, expected: bytes) -> None:
    with zipfile.ZipFile(apk) as package:
        entries = [entry for entry in package.infolist() if entry.filename == MEMBER]
        require(len(entries) == 1, "CONFIGURATION_MEMBER_COUNT")
        entry = entries[0]
        require(not entry.is_dir() and 0 < entry.file_size <= MAX_BYTES and not entry.flag_bits & 1,
                "CONFIGURATION_MEMBER_INVALID")
        with package.open(entry) as source:
            data = source.read(MAX_BYTES + 1)
        require(data == expected, "CONFIGURATION_PACKAGED_BYTES_MISMATCH")
        validate(data)


def check(root: Path) -> dict:
    source = source_identity(root)
    expected = source_bytes(root)
    inventory_path = root / "build/inventory/runtime-inventory.json"
    inventory_hash = sha256(inventory_path)
    inventory = json.loads(inventory_path.read_text())
    require(inventory.get("schema_version") == 1 and inventory.get("scope") == "resolved-runtime-classpaths"
            and inventory.get("source", {}).get("commit") == source["commit"]
            and inventory.get("source", {}).get("tree") == source["tree"], "CONFIGURATION_INVENTORY_SOURCE_MISMATCH")
    outputs = [item for item in inventory["outputs"] if item.get("kind") == "unsigned-debug-preview"]
    require(len(outputs) == 1 and outputs[0].get("file") == APK_PATH, "CONFIGURATION_APK_IDENTITY")
    apk = root / APK_PATH
    before = sha256(apk)
    require(before == outputs[0].get("sha256") and apk.stat().st_size == outputs[0].get("bytes"),
            "CONFIGURATION_APK_MISMATCH")
    inspect_member(apk, expected)
    require(sha256(apk) == before and sha256(inventory_path) == inventory_hash and
            source_identity(root) == source and source_bytes(root) == expected, "CONFIGURATION_CHANGED_DURING_CHECK")
    return {"schema_version": 1, "status": "passed", "source": source,
            "scope": "packaged-public-preview-configuration", "apk_sha256": before,
            "runtime_inventory_sha256": inventory_hash,
            "configuration_sha256": hashlib.sha256(expected).hexdigest(),
            "configuration_bytes": len(expected), "profile": EXPECTED,
            "limitations": ["Finite non-secret configuration only; the existing secret scanner remains required.",
                            "No staging/production endpoint, installed-device, authentication or release acceptance."]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-only", action="store_true")
    args = parser.parse_args()
    output = ROOT / "build/inventory/environment-configuration.json"
    if not args.source_only:
        output.unlink(missing_ok=True)
    try:
        if args.source_only:
            source_bytes(ROOT)
        else:
            receipt = check(ROOT)
            output.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(mode="w", dir=output.parent, delete=False) as temporary:
                json.dump(receipt, temporary, indent=2, sort_keys=True)
                temporary.write("\n")
                temporary_path = Path(temporary.name)
            temporary_path.replace(output)
    except (PolicyFailure, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError):
        print("Environment configuration rejected; no input values or passing receipt retained.")
        return 1
    print("PASS: finite source configuration." if args.source_only else
          "PASS: exact inventoried APK contains the finite non-secret preview configuration.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
