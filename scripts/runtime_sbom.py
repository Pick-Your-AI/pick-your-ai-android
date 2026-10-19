#!/usr/bin/env python3
"""Export a qualified runtime inventory as SPDX 2.3 without network access."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
DRIVER = "scripts/runtime_sbom.py"
SCHEMA = "scripts/vendor/spdx-2.3/spdx-schema.json"
SCHEMA_SHA256 = "239208b7ac287b3cf5d9a9af23f9d69863971102a5e1587a27a398b43490b89b"
INVENTORY = "build/inventory/runtime-inventory.json"
OUTPUT = "build/sbom/runtime.spdx.json"
MAX_INPUT_BYTES = 8 * 1024 * 1024
MAX_COMPONENTS = 4096
MAX_EDGES = 65536
PINS = {
    "build.gradle.kts", "settings.gradle.kts", "core/build.gradle.kts",
    "core/gradle.lockfile", "gradle/verification-metadata.xml",
    "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
    "scripts/runtime_inventory.init.gradle.kts",
}
PREVIEW_PINS = {"preview/build.gradle.kts", "preview/gradle.lockfile"}
OUTPUTS = {
    "core-jar": ("core/build/libs/core.jar", ":core", "runtimeClasspath", "LIBRARY"),
    "unsigned-debug-preview": (
        "preview/build/outputs/apk/debug/preview-debug-unsigned.apk",
        ":preview", "debugRuntimeClasspath", "APPLICATION",
    ),
}
NAMESPACE = "https://spdx.org/spdxdocs/pick-your-ai-android-runtime-"
LIMITATIONS = (
    "Resolved runtime inputs; package contents, reachability and release configuration are not established. "
    "POM declarations are unreviewed; inherited licenses, notices and distribution duties are not determined. "
    "Build plugins, test-only runtimes, SDK tools and a complete toolchain inventory are outside this graph. "
    "No vulnerability verdict, signing, installation, store delivery or receiving-owner acceptance is established."
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def keys(value: object, expected: set[str]) -> None:
    require(type(value) is dict and set(value) == expected, "Unexpected inventory object fields")


def array(value: object, maximum: int, minimum: int = 0) -> None:
    require(type(value) is list and minimum <= len(value) <= maximum, "Invalid inventory array")


def text(value: object, maximum: int = 4096, minimum: int = 1) -> None:
    require(type(value) is str and minimum <= len(value) <= maximum, "Invalid inventory text")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ValueError("Invalid inventory Unicode") from error


def hexadecimal(value: object, length: int = 64) -> None:
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{%d}" % length, value) is not None,
            "Invalid inventory digest")


def relative_path(value: object, basename: bool = False) -> None:
    text(value, 1024)
    require(not re.search(r"[\x00-\x1f\x7f\\]", value), "Unsafe inventory path")
    path = PurePosixPath(value)
    require(bool(path.parts) and not path.is_absolute() and str(path) == value and
            all(part not in (".", "..") for part in path.parts) and
            (not basename or len(path.parts) == 1), "Unsafe inventory path")


def component_identity(value: object) -> None:
    text(value, 1024)
    if value in ("project::core", "project::preview"):
        return
    require(len(value.split(":")) == 3 and all(value.split(":")) and
            not value.startswith("project:") and not re.search(r"[\s/\\\x00-\x1f\x7f]", value),
            "Unsupported resolved component identity")


def measured(value: dict) -> None:
    hexadecimal(value["sha256"])
    require(type(value["bytes"]) is int and 0 < value["bytes"] < 2**63,
            "Invalid artifact byte count")


def parse_inventory(data: bytes) -> dict:
    require(type(data) is bytes and 0 < len(data) <= MAX_INPUT_BYTES, "Inventory byte limit exceeded")

    def unique_pairs(pairs: list) -> dict:
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate inventory object key")
            result[key] = value
        return result

    def no_constant(_: str) -> None:
        raise ValueError("Non-finite inventory number")

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=unique_pairs, parse_constant=no_constant)
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise ValueError("Malformed inventory JSON") from error
    validate_inventory(value)
    return value


def validate_inventory(value: dict) -> None:
    keys(value, {"schema_version", "scope", "source", "toolchain", "outputs", "classpaths", "limitations"})
    require(type(value["schema_version"]) is int and value["schema_version"] == 1 and
            value["scope"] == "resolved-runtime-classpaths", "Unsupported inventory schema or scope")
    source = value["source"]
    keys(source, {"commit", "tree", "pins"})
    hexadecimal(source["commit"], 40)
    hexadecimal(source["tree"], 40)
    keys(value["toolchain"], {"gradle", "java"})
    for version in value["toolchain"].values():
        text(version, 128)
    array(value["limitations"], 32, 1)
    for limitation in value["limitations"]:
        text(limitation)
    array(value["outputs"], 2, 1)
    output_kinds = set()
    for output in value["outputs"]:
        keys(output, {"kind", "file", "sha256", "bytes"})
        text(output["kind"])
        require(output["kind"] in OUTPUTS and output["kind"] not in output_kinds,
                "Unknown or repeated runtime output")
        output_kinds.add(output["kind"])
        require(output["file"] == OUTPUTS[output["kind"]][0], "Unexpected runtime output path")
        measured(output)
    require("core-jar" in output_kinds, "Missing core runtime output")
    preview = "unsigned-debug-preview" in output_kinds
    expected_pins = PINS | (PREVIEW_PINS if preview else set())
    array(source["pins"], len(expected_pins), len(expected_pins))
    seen_pins = set()
    for pin in source["pins"]:
        keys(pin, {"file", "sha256"})
        relative_path(pin["file"])
        hexadecimal(pin["sha256"])
        require(pin["file"] not in seen_pins, "Repeated source pin")
        seen_pins.add(pin["file"])
    require(seen_pins == expected_pins, "Missing or unexpected source pin")

    array(value["classpaths"], 2 if preview else 1, 2 if preview else 1)
    scopes, poms, artifacts = set(), {}, {}
    edge_count = 0
    for scope in value["classpaths"]:
        keys(scope, {"project", "configuration", "components", "artifacts"})
        text(scope["project"])
        text(scope["configuration"])
        identity = (scope["project"], scope["configuration"])
        require(identity in {(OUTPUTS[k][1], OUTPUTS[k][2]) for k in output_kinds} and
                identity not in scopes, "Unknown or repeated runtime classpath")
        scopes.add(identity)
        array(scope["components"], MAX_COMPONENTS, 1)
        components = {}
        for component in scope["components"]:
            keys(component, {"component", "dependencies", "pom"})
            name = component["component"]
            component_identity(name)
            require(name not in components, "Repeated resolved component")
            components[name] = component
            pom = component["pom"]
            if name.startswith("project:"):
                require(pom is None and (preview or name == "project::core"), "Unexpected project component")
            else:
                keys(pom, {"sha256", "direct_license_declarations", "license_status", "parent",
                           "parent_license_inheritance"})
                hexadecimal(pom["sha256"])
                declarations = pom["direct_license_declarations"]
                array(declarations, 256)
                for declaration in declarations:
                    keys(declaration, {"name", "url", "distribution"})
                    for field in declaration.values():
                        if field is not None:
                            text(field)
                require(pom["license_status"] == ("declared-unreviewed" if declarations else "no-direct-declaration"),
                        "Inconsistent POM license status")
                if pom["parent"] is not None:
                    keys(pom["parent"], {"group", "module", "version"})
                    for field in pom["parent"].values():
                        if field is not None:
                            text(field)
                require(pom["parent_license_inheritance"] == ("no-parent" if pom["parent"] is None else "not-evaluated"),
                        "Inconsistent parent-license status")
                require(name not in poms or poms[name] == pom, "Conflicting POM evidence across classpaths")
                poms[name] = pom
            array(component["dependencies"], MAX_EDGES)
            edge_count += len(component["dependencies"])
            require(edge_count <= MAX_EDGES, "Runtime graph edge limit exceeded")
            for dependency in component["dependencies"]:
                keys(dependency, {"component", "constraint"})
                component_identity(dependency["component"])
                require(type(dependency["constraint"]) is bool, "Invalid dependency constraint flag")
        root = "project:" + scope["project"]
        require(root in components, "Missing runtime classpath root")
        for component in components.values():
            require(all(d["component"] in components for d in component["dependencies"]),
                    "Dangling runtime dependency")
        visited, pending = set(), [root]
        while pending:
            name = pending.pop()
            if name not in visited:
                visited.add(name)
                pending.extend(d["component"] for d in components[name]["dependencies"])
        require(visited == set(components), "Unreachable runtime component")
        array(scope["artifacts"], MAX_COMPONENTS * 4)
        seen_artifacts = set()
        for artifact in scope["artifacts"]:
            keys(artifact, {"component", "file", "sha256", "bytes"})
            component_identity(artifact["component"])
            require(artifact["component"] in components, "Orphaned runtime artifact")
            relative_path(artifact["file"], basename=True)
            measured(artifact)
            key = (artifact["component"], artifact["file"])
            require(key not in seen_artifacts, "Repeated runtime artifact")
            seen_artifacts.add(key)
            require(key not in artifacts or artifacts[key] == artifact,
                    "Conflicting runtime artifact evidence across classpaths")
            artifacts[key] = artifact
            if artifact["component"].startswith("project:"):
                require(artifact["component"] == "project::core", "Unexpected project runtime artifact")
                built = next(o for o in value["outputs"] if o["kind"] == "core-jar")
                require(artifact["file"] == PurePosixPath(built["file"]).name and
                        all(artifact[k] == built[k] for k in ("sha256", "bytes")),
                        "Project runtime artifact does not match its built output")


def spdx_id(*identity: str) -> str:
    return "SPDXRef-" + digest(canonical(identity).encode())


def package(identity: str, name: str, evidence: dict, purpose: str = "OTHER") -> dict:
    return {
        "SPDXID": identity, "name": name, "downloadLocation": "NOASSERTION",
        "filesAnalyzed": False, "licenseConcluded": "NOASSERTION", "licenseDeclared": "NOASSERTION",
        "copyrightText": "NOASSERTION", "supplier": "NOASSERTION", "primaryPackagePurpose": purpose,
        "sourceInfo": canonical(evidence),
    }


def document(inventory: dict, inventory_sha256: str, generator_sha256: str, created: str) -> dict:
    """Pure conversion; callers still need current-checkout and schema qualification."""
    validate_inventory(inventory)
    hexadecimal(inventory_sha256)
    hexadecimal(generator_sha256)
    require(re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", created) is not None, "Invalid creation timestamp")
    datetime.strptime(created, "%Y-%m-%dT%H:%M:%SZ")
    packages, relationships = [], []

    def relate(source: str, target: str, kind: str, comment: str = "") -> None:
        row = {"spdxElementId": source, "relatedSpdxElement": target, "relationshipType": kind}
        if comment:
            row["comment"] = comment
        relationships.append(row)

    for scope in inventory["classpaths"]:
        scope_key = (scope["project"], scope["configuration"])
        component_ids = {c["component"]: spdx_id("component", *scope_key, c["component"])
                         for c in scope["components"]}
        for component in scope["components"]:
            name = component["component"]
            evidence = {"kind": "resolved-component", "project": scope_key[0],
                        "configuration": scope_key[1], **component}
            item = package(component_ids[name], name, evidence)
            if not name.startswith("project:"):
                group, module, version = name.split(":")
                item.update(name=f"{group}:{module}", versionInfo=version,
                            licenseComments="Direct POM declarations are unreviewed metadata in sourceInfo; "
                                            "parent licenses and distribution duties are not determined.")
            packages.append(item)
            for dependency in component["dependencies"]:
                relate(item["SPDXID"], component_ids[dependency["component"]],
                       "OTHER" if dependency["constraint"] else "DEPENDS_ON",
                       "Gradle resolution constraint; not a runtime dependency." if dependency["constraint"] else "")
        root = component_ids["project:" + scope_key[0]]
        relate("SPDXRef-DOCUMENT", root, "DESCRIBES")
        for artifact in scope["artifacts"]:
            item = package(spdx_id("artifact", *scope_key, artifact["component"], artifact["file"]),
                           artifact["file"], {"kind": "resolved-artifact", "project": scope_key[0],
                                             "configuration": scope_key[1], **artifact}, "LIBRARY")
            item.update(packageFileName=artifact["file"],
                        checksums=[{"algorithm": "SHA256", "checksumValue": artifact["sha256"]}])
            packages.append(item)
            relate(component_ids[artifact["component"]], item["SPDXID"], "OTHER",
                   "Observed resolved runtime artifact; no packaging or reachability assertion.")
    for output in inventory["outputs"]:
        _, project, configuration, purpose = OUTPUTS[output["kind"]]
        item = package(spdx_id("output", output["kind"], output["file"]), PurePosixPath(output["file"]).name,
                       {"kind": "built-output", "output": output}, purpose)
        item.update(packageFileName=output["file"],
                    checksums=[{"algorithm": "SHA256", "checksumValue": output["sha256"]}])
        packages.append(item)
        relate("SPDXRef-DOCUMENT", item["SPDXID"], "DESCRIBES")
        relate(item["SPDXID"], spdx_id("component", project, configuration, "project:" + project), "OTHER",
               "Output recorded alongside this resolved runtime classpath; no packaged-content assertion.")
    result = {
        "spdxVersion": "SPDX-2.3", "dataLicense": "CC0-1.0", "SPDXID": "SPDXRef-DOCUMENT",
        "name": "pick-your-ai-android resolved runtime inventory " + inventory["source"]["commit"],
        "creationInfo": {"created": created, "creators": ["Tool: pick-your-ai-runtime-sbom-1"]},
        "comment": canonical({"inventory_sha256": inventory_sha256, "source": inventory["source"],
                              "toolchain": inventory["toolchain"], "inventory_limitations": inventory["limitations"],
                              "generator_sha256": generator_sha256, "spdx_schema_sha256": SCHEMA_SHA256,
                              "limitations": LIMITATIONS}),
        "packages": sorted(packages, key=lambda item: item["SPDXID"]),
        "relationships": sorted(relationships, key=canonical),
    }
    result["documentNamespace"] = NAMESPACE + digest(canonical(result).encode())
    return result


def validate_document(value: dict, schema_bytes: bytes) -> None:
    require(digest(schema_bytes) == SCHEMA_SHA256, "SPDX schema pin mismatch")
    # The pinned official schema has no external references. Validation is local.
    from jsonschema import Draft7Validator
    schema = json.loads(schema_bytes)
    Draft7Validator.check_schema(schema)
    errors = iter(Draft7Validator(schema).iter_errors(value))
    require(next(errors, None) is None, "Generated document does not satisfy SPDX 2.3 schema")
    content = {k: v for k, v in value.items() if k != "documentNamespace"}
    require(value.get("documentNamespace") == NAMESPACE + digest(canonical(content).encode()),
            "SPDX namespace does not identify this document")
    identifiers = [p["SPDXID"] for p in value["packages"]]
    require(len(identifiers) == len(set(identifiers)) and all(
        re.fullmatch(r"SPDXRef-[A-Za-z0-9.-]+", i) for i in identifiers), "Duplicate or invalid SPDX package identity")
    targets = set(identifiers) | {"SPDXRef-DOCUMENT"}
    for relation in value["relationships"]:
        require(relation["spdxElementId"] in targets and relation["relatedSpdxElement"] in targets,
                "Dangling SPDX relationship")
    for item in value["packages"]:
        require(item["filesAnalyzed"] is False and "packageVerificationCode" not in item and
                all(item[k] == "NOASSERTION" for k in ("downloadLocation", "licenseConcluded", "licenseDeclared",
                                                       "copyrightText", "supplier")),
                "Unsupported SPDX license, provenance or file-analysis assertion")


def regular_file(root: Path, relative: str) -> Path:
    relative_path(relative)
    current = root
    for part in PurePosixPath(relative).parts:
        current /= part
        require(not stat.S_ISLNK(current.lstat().st_mode), "Symlink in inventory evidence path")
    require(stat.S_ISREG(current.lstat().st_mode), "Inventory evidence is not a regular file")
    return current


def hash_file(path: Path, git_blob: bool = False) -> str:
    hasher = hashlib.sha1() if git_blob else hashlib.sha256()
    if git_blob:
        hasher.update(f"blob {path.stat().st_size}\0".encode())
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            hasher.update(block)
    return hasher.hexdigest()


def git(root: Path, *arguments: str) -> bytes:
    result = subprocess.run(["git", "--no-replace-objects", *arguments], cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=30)
    require(result.returncode == 0 and not result.stderr and len(result.stdout) <= 4 * 1024 * 1024,
            "SBOM source inspection failed")
    return result.stdout


def qualify_checkout(root: Path, inventory: dict) -> None:
    require(not git(root, "status", "--porcelain", "--untracked-files=all"), "SBOM requires a clean source checkout")
    for field, revision in (("commit", "HEAD"), ("tree", "HEAD^{tree}")):
        require(git(root, "rev-parse", revision).decode().strip() == inventory["source"][field],
                "Inventory source does not match this checkout")
    tracked = set()
    for entry in git(root, "ls-tree", "-rz", "--full-tree", "HEAD").split(b"\0"):
        if not entry:
            continue
        metadata, path_bytes = entry.split(b"\t", 1)
        mode, kind, blob = metadata.decode().split()
        require(kind == "blob" and mode in ("100644", "100755"), "Unsupported source tree entry")
        relative = path_bytes.decode("utf-8")
        tracked.add(relative)
        path = regular_file(root, relative)
        require(hash_file(path, git_blob=True) == blob, "Tracked source bytes changed since the inventory")
    require({DRIVER, SCHEMA} <= tracked, "SBOM driver or schema is absent from the recorded source")
    require(hash_file(regular_file(root, DRIVER)) == digest(Path(__file__).read_bytes()),
            "Executing SBOM driver differs from the recorded source")
    for pin in inventory["source"]["pins"]:
        require(hash_file(regular_file(root, pin["file"])) == pin["sha256"], "Inventory source pin mismatch")
    for output in inventory["outputs"]:
        path = regular_file(root, output["file"])
        require(path.stat().st_size == output["bytes"] and hash_file(path) == output["sha256"],
                "Built output differs from the inventory")


def prepare_output(root: Path) -> Path:
    # Only this owned generated path can be cleared. Never unlink a caller path.
    parent = root
    for part in ("build", "sbom"):
        parent /= part
        if not parent.exists() and not parent.is_symlink():
            parent.mkdir()
        require(stat.S_ISDIR(parent.lstat().st_mode), "Unsafe SBOM output directory")
    output = root / OUTPUT
    if output.exists() or output.is_symlink():
        info = output.lstat()
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, "Unsafe existing SBOM output")
        output.unlink()
    return output


def export(root: Path = ROOT, created: str | None = None) -> dict:
    root = root.resolve(strict=True)
    output = prepare_output(root)
    path = regular_file(root, INVENTORY)
    with path.open("rb") as stream:
        data = stream.read(MAX_INPUT_BYTES + 1)
    inventory = parse_inventory(data)
    qualify_checkout(root, inventory)
    schema = regular_file(root, SCHEMA).read_bytes()
    timestamp = created or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    result = document(inventory, digest(data), hash_file(regular_file(root, DRIVER)), timestamp)
    validate_document(result, schema)
    encoded = (json.dumps(result, indent=2, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n").encode()
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".runtime-spdx-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return result


def main() -> int:
    argparse.ArgumentParser(description=__doc__).parse_args()
    try:
        result = export()
    except (ValueError, OSError, ImportError, subprocess.SubprocessError) as error:
        print(f"Runtime SPDX qualification failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 1
    print(f"Verified SPDX 2.3 runtime document: {len(result['packages'])} packages, "
          f"{len(result['relationships'])} relationships; {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
