#!/usr/bin/env python3
"""Prepare and qualify one source-built TalkBack APK; never install or enable it."""
from __future__ import annotations

import argparse
from contextlib import ExitStack, contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import stat
import struct
import subprocess
import time
import uuid
import zipfile

MANIFEST = "scripts/talkback_build_prerequisite.json"
INIT_SCRIPT = "scripts/talkback_dependency_observation.gradle"
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
COMMAND_OUTPUT_LIMIT = 16 * 1024 * 1024
COMMAND_TIMEOUT = 120
OBSERVATION_BYTES = 64 * 1024
SPECIFICATION_BYTES = 1024 * 1024
DEPENDENCY_BYTES = 16 * 1024 * 1024
RECEIPT_BYTES = 1024 * 1024


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def blob(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data,
                        usedforsecurity=False).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bounded_run(argv, *, cwd, env, check, stdout, stderr, timeout):
    """Drain both pipes with one byte/deadline budget; own a POSIX process group."""
    require(stdout == stderr == subprocess.PIPE, "Both command streams must be bounded")
    deadline = time.monotonic() + timeout
    process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=stdout, stderr=stderr,
                               start_new_session=True, bufsize=0)
    buffers = [bytearray(), bytearray()]
    total = 0
    try:
        with selectors.DefaultSelector() as selector:
            for stream, buffer in zip((process.stdout, process.stderr), buffers):
                os.set_blocking(stream.fileno(), False)
                selector.register(stream, selectors.EVENT_READ, buffer)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(argv, timeout)
                for key, _ in selector.select(remaining):
                    # Read at most one byte beyond the remaining aggregate budget.
                    chunk = os.read(key.fd, min(65536, COMMAND_OUTPUT_LIMIT - total + 1))
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    total += len(chunk)
                    require(total <= COMMAND_OUTPUT_LIMIT, "Command output exceeds its bound")
                    key.data.extend(chunk)
        process.wait(timeout=max(0, deadline - time.monotonic()))
        result = subprocess.CompletedProcess(argv, process.returncode,
                                             bytes(buffers[0]), bytes(buffers[1]))
        if check:
            result.check_returncode()
        return result
    except subprocess.TimeoutExpired as error:
        raise subprocess.TimeoutExpired(argv, timeout, output=bytes(buffers[0]) or None,
                                        stderr=bytes(buffers[1]) or None) from error
    finally:
        # Includes descendants retaining a pipe after their parent exits. Never use
        # communicate() or an unbounded wait during rejection/timeout cleanup.
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        finally:
            try:
                process.wait(timeout=0.2)
            except subprocess.TimeoutExpired:
                pass
            finally:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                finally:
                    process.stdout.close()
                    process.stderr.close()
                    process.wait(timeout=5)


def run(argv: list[str], *, cwd: Path | None = None, include_stderr: bool = False) -> bytes:
    environment = dict(os.environ, GIT_NO_REPLACE_OBJECTS="1", GIT_NO_LAZY_FETCH="1",
                       GIT_OPTIONAL_LOCKS="0")
    result = bounded_run(argv, cwd=cwd, env=environment, check=True,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=COMMAND_TIMEOUT)
    output = result.stdout + result.stderr if include_stderr else result.stdout
    return output


@contextmanager
def output_directory(path: Path, *, create: bool = False):
    """Walk from / using directory descriptors, rejecting every linked component."""
    path = path.absolute()
    require(".." not in path.parts, "Output directory must not contain parent traversal")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open(path.anchor, flags)
    try:
        for index, component in enumerate(path.parts[1:], 1):
            if create and index == len(path.parts) - 1:
                os.mkdir(component, mode=0o700, dir_fd=descriptor)
            child = os.open(component, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        yield descriptor
    finally:
        os.close(descriptor)


def read_observation(path: Path, limit: int) -> bytes:
    """Read one ordinary, unlinked file through verified ancestors, at most limit+1."""
    with output_directory(path.parent) as parent:
        descriptor = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        try:
            info = os.fstat(descriptor)
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
                    "Observation must be an ordinary unlinked file")
            require(info.st_size <= limit, "Observation exceeds its byte bound")
            chunks, remaining = [], limit + 1
            while remaining:
                chunk = os.read(descriptor, min(65536, remaining))
                if not chunk:
                    break
                chunks.append(chunk)
                remaining -= len(chunk)
            require(remaining > 0, "Observation exceeds its byte bound")
            return b"".join(chunks)
        finally:
            os.close(descriptor)


def receipt_bytes(value: dict) -> bytes:
    result = bytearray()
    for chunk in json.JSONEncoder(sort_keys=True, indent=2).iterencode(value):
        encoded = chunk.encode()
        require(len(result) + len(encoded) + 1 <= RECEIPT_BYTES, "Receipt exceeds its byte bound")
        result.extend(encoded)
    return bytes(result) + b"\n"


def observer_hash(project: Path) -> str:
    # This offline slice does not deliver the external Gradle observer. Fail before
    # creating output or changing upstream source until it is integrated and tracked.
    try:
        data = read_observation(project / INIT_SCRIPT, SPECIFICATION_BYTES)
        entries = git(project, "ls-tree", "-z", "HEAD", "--", INIT_SCRIPT).split(b"\0")
        require(len(entries) == 2 and not entries[1], "Missing tracked observer")
        header, name = entries[0].split(b"\t", 1)
        mode, kind, expected = header.decode().split()
        require(name.decode() == INIT_SCRIPT and mode in {"100644", "100755"}
                and kind == "blob" and blob(data) == expected, "Observer differs from tracked source")
    except (OSError, ValueError):
        raise ValueError("Required tracked dependency observer is unavailable; integrate it before preparation or recording") from None
    return hashlib.sha256(data).hexdigest()


def dependency_object(pairs: list[tuple[str, object]]) -> dict:
    """Do not publish raw members hidden by JSON's default last-value decoding."""
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate dependency observation member")
        result[key] = value
    return result


def prepare_wrapper(directory: int, wrapper: dict[str, bytes]) -> None:
    """Create the complete wrapper exclusively before permitting any source edits."""
    with ExitStack() as stack:
        directories = {"": directory}
        for path, data in wrapper.items():
            parts = PurePosixPath("wrapper/" + path).parts
            require(path == "/".join(parts[1:]) and ".." not in parts and not path.startswith("/"),
                    "Invalid wrapper output path")
            parent = ""
            for component in parts[:-1]:
                current = parent + "/" + component
                if current not in directories:
                    os.mkdir(component, mode=0o700, dir_fd=directories[parent])
                    child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                    dir_fd=directories[parent])
                    stack.callback(os.close, child)
                    directories[current] = child
                parent = current
            descriptor = os.open(parts[-1], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=directories[parent])
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
                os.fchmod(stream.fileno(), 0o755 if path == "gradlew" else 0o644)


def publish_files(directory: Path, contents: dict[str, bytes | Path], *, create=False,
                  expected_hashes: dict[str, str] | None = None) -> None:
    """Stage ordinary files privately, then publish exclusively in insertion order."""
    with output_directory(directory, create=create) as destination:
        for name in contents:
            require(name not in {"", ".", ".."} and "/" not in name, "Invalid output name")
            try:
                os.stat(name, dir_fd=destination, follow_symlinks=False)
            except FileNotFoundError:
                continue
            raise ValueError("A prerequisite output already exists")
        staging = ".record-" + uuid.uuid4().hex
        os.mkdir(staging, mode=0o700, dir_fd=destination)
        with ExitStack() as stack:
            stage = os.open(staging, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=destination)
            stack.callback(os.rmdir, staging, dir_fd=destination)
            stack.callback(os.close, stage)
            for name, data in contents.items():
                descriptor = os.open(name, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                     0o600, dir_fd=stage)
                stack.callback(os.unlink, name, dir_fd=stage)
                with os.fdopen(descriptor, "w+b") as stream:
                    if isinstance(data, Path):
                        digest = (expected_hashes or {}).get(name) or sha256_file(data)
                        with data.open("rb") as source:
                            shutil.copyfileobj(source, stream, length=65536)
                        stream.seek(0)
                        copied = hashlib.file_digest(stream, "sha256").hexdigest()
                        require(copied == digest, "Copied artifact bytes differ")
                    else:
                        stream.write(data)
            # link() never replaces an existing destination, including dangling
            # symlinks. The receipt is last and is published only as a complete file.
            for name in contents:
                os.link(name, name, src_dir_fd=stage, dst_dir_fd=destination,
                        follow_symlinks=False)


def aapt2_version(executable: Path) -> str:
    # Official AAPT2 VersionCommand writes its successful version to stderr.
    output = run([str(executable), "version"], include_stderr=True)
    require(len(output) <= 256, "AAPT2 version output exceeds its bound")
    value = output.decode("ascii").strip()
    require(re.fullmatch(r"Android Asset Packaging Tool \(aapt\) 2\.[0-9]{1,3}-[0-9]{1,16}", value)
            is not None, "Missing or malformed AAPT2 version observation")
    return value


def git(root: Path, *arguments: str) -> bytes:
    return run(["git", "-C", str(root), *arguments])


def identity(root: Path) -> dict[str, str]:
    require(not git(root, "for-each-ref", "--format=%(refname)", "refs/replace"),
            "Git replacement objects are not admitted")
    config = git(root, "config", "--local", "--list").decode()
    require(not re.search(r"(?im)^(extensions\.partialclone|remote\..*\.promisor)=", config),
            "Promisor source checkouts are not admitted")
    head = git(root, "rev-parse", "--verify", "HEAD").decode().strip()
    tree = git(root, "rev-parse", "--verify", "HEAD^{tree}").decode().strip()
    require(bool(HEX40.fullmatch(head) and HEX40.fullmatch(tree)), "Invalid Git identity")
    return {"commit": head, "tree": tree}


def verify_tracked(root: Path, overrides: dict[str, str] | None = None) -> int:
    """Hash each actual tracked file against the native HEAD map, including its mode."""
    overrides = overrides or {}
    entries = git(root, "ls-tree", "-rz", "--full-tree", "HEAD").split(b"\0")
    seen = set()
    for entry in entries:
        if not entry:
            continue
        header, raw_path = entry.split(b"\t", 1)
        mode, kind, native = header.decode().split()
        path = raw_path.decode("utf-8")
        require(mode in {"100644", "100755"} and kind == "blob",
                "Only ordinary tracked source files are admitted")
        target = root / path
        require(target.is_file() and not target.is_symlink(), "Tracked source is missing or linked")
        require(target.resolve().is_relative_to(root.resolve()), "Tracked source escapes its checkout")
        actual_mode = "100755" if target.stat().st_mode & 0o111 else "100644"
        require(actual_mode == mode, "Tracked source mode changed: " + path)
        require(blob(target.read_bytes()) == overrides.get(path, native),
                "Tracked source bytes changed: " + path)
        seen.add(path)
    require(seen and set(overrides) <= seen, "An override is not an existing tracked source file")
    return len(seen)


def replace_once(data: bytes, old: str, new: str) -> bytes:
    before, after = old.encode(), new.encode()
    require(data.count(before) == 1, "An exact overlay anchor is missing or duplicated")
    return data.replace(before, after, 1)


def overlay(data: bytes, specification: dict) -> bytes:
    require(blob(data) == specification["before_blob"], "Unexpected upstream overlay input")
    require(hashlib.sha256(data).hexdigest() == specification["before_sha256"],
            "Upstream overlay SHA-256 differs")
    result = data
    for old, new in specification["replacements"]:
        result = replace_once(result, old, new)
    require(blob(result) == specification["after_blob"], "Overlay output blob differs")
    require(hashlib.sha256(result).hexdigest() == specification["after_sha256"],
            "Overlay output SHA-256 differs")
    return result


def isolated_roots(project: Path, upstream: Path, work: Path) -> None:
    roots = [project.resolve(), upstream.resolve(), work.resolve()]
    for index, root in enumerate(roots):
        for other in roots[index + 1:]:
            require(not root.is_relative_to(other) and not other.is_relative_to(root),
                    "Project, upstream and output roots must be separate")


def expected_wrapper(project: Path, manifest: dict) -> dict[str, bytes]:
    wrapper = {}
    for path, expected in manifest["wrapper_blobs"].items():
        data = read_observation(project / path, SPECIFICATION_BYTES)
        require(blob(data) == expected, "The retained project wrapper differs")
        wrapper[path] = data
    properties_path = "gradle/wrapper/gradle-wrapper.properties"
    properties = wrapper[properties_path]
    properties = replace_once(properties,
        "https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip",
        "https\\://services.gradle.org/distributions/gradle-" + manifest["tools"]["gradle"] + "-bin.zip")
    properties = replace_once(properties,
        "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6",
        manifest["tools"]["gradle_sha256"])
    wrapper[properties_path] = properties
    return wrapper


def verify_copied_wrapper(project: Path, work: Path, manifest: dict,
                          recorded: dict) -> dict[str, str]:
    expected = expected_wrapper(project, manifest)
    digests = {path: hashlib.sha256(data).hexdigest() for path, data in expected.items()}
    require(recorded == digests, "The saved wrapper identities differ from the pinned transformation")
    for path, data in expected.items():
        copied = work / "wrapper" / path
        require(copied.is_file() and not copied.is_symlink(), "A copied wrapper file is missing or linked")
        require(read_observation(copied, SPECIFICATION_BYTES) == data,
                "Copied wrapper bytes differ from the pinned transformation")
    return digests


def prepare(project: Path, upstream: Path, work: Path, expected_project: str) -> None:
    isolated_roots(project, upstream, work)
    require(bool(HEX40.fullmatch(expected_project)), "An exact project commit is required")
    manifest_bytes = read_observation(project / MANIFEST, SPECIFICATION_BYTES)
    manifest = json.loads(manifest_bytes)
    require(manifest["schema"] == "talkback-build-prerequisite/v1", "Unsupported prerequisite")
    project_identity, upstream_identity = identity(project), identity(upstream)
    require(project_identity["commit"] == expected_project, "Project checkout differs")
    require(upstream_identity == {key: manifest["upstream"][key] for key in ("commit", "tree")},
            "Official TalkBack checkout differs")
    init_sha256 = observer_hash(project)
    for root in (project, upstream):
        require(not git(root, "status", "--porcelain", "--untracked-files=all"),
                "Preparation requires a clean source checkout")
        verify_tracked(root)
    planned = {path: overlay(read_observation(upstream / path, SPECIFICATION_BYTES), specification)
               for path, specification in manifest["overlays"].items()}
    wrapper = expected_wrapper(project, manifest)
    with output_directory(work, create=True) as directory:
        created = os.fstat(directory)
        os.mkdir("artifacts", mode=0o700, dir_fd=directory)
        prepare_wrapper(directory, wrapper)
        with output_directory(work) as current:
            observed = os.fstat(current)
            require((observed.st_dev, observed.st_ino) == (created.st_dev, created.st_ino),
                    "Preparation output directory changed")
        try:
            os.stat("prepared.json", dir_fd=directory, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise ValueError("A prerequisite output already exists")
    # All output ancestors and wrapper destinations have passed exclusive creation.
    # Open every overlay without truncation and verify it again before changing any.
    with ExitStack() as stack:
        streams = []
        for path, data in planned.items():
            parent = stack.enter_context(output_directory((upstream / path).parent))
            descriptor = os.open(Path(path).name, os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK,
                                 dir_fd=parent)
            stream = stack.enter_context(os.fdopen(descriptor, "r+b"))
            info = os.fstat(stream.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
                    "Overlay source must be an ordinary unlinked file")
            require(blob(stream.read(SPECIFICATION_BYTES + 1)) == manifest["overlays"][path]["before_blob"],
                    "Overlay source changed before preparation")
            streams.append((stream, data))
        for stream, data in streams:
            stream.seek(0)
            stream.write(data)
            stream.truncate()
    overrides = {path: value["after_blob"] for path, value in manifest["overlays"].items()}
    upstream_files = verify_tracked(upstream, overrides)
    project_files = verify_tracked(project)
    state = {
        "schema": manifest["schema"], "project": project_identity, "upstream": upstream_identity,
        "manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
        "init_sha256": init_sha256,
        "overlay_blobs": overrides, "upstream_tracked_files": upstream_files,
        "project_tracked_files": project_files,
        "copied_wrapper_sha256": {path: hashlib.sha256(data).hexdigest() for path, data in wrapper.items()}
    }
    verify_copied_wrapper(project, work, manifest, state["copied_wrapper_sha256"])
    publish_files(work, {"prepared.json": receipt_bytes(state)})
    print("Prepared the pinned TalkBack source and isolated x86_64 build tools")


def parse_xmltree(text: str, *, normalize_android: bool = True) -> dict:
    """Parse AAPT2's element/attribute hierarchy, without flattening service boundaries."""
    roots, stack = [], []
    for line in text.splitlines():
        element = re.match(r"^(\s*)E: ([A-Za-z0-9_-]+)(?:\s|$)", line)
        if element:
            depth = len(element[1])
            node = {"name": element[2], "attributes": {}, "children": []}
            while stack and stack[-1][0] >= depth:
                stack.pop()
            (stack[-1][1]["children"] if stack else roots).append(node)
            stack.append((depth, node))
            continue
        attribute = re.match(r"^(\s*)A: ([^=(\s]+)(?:\([^)]*\))?=(.*)$", line)
        if attribute:
            require(bool(stack) and len(attribute[1]) > stack[-1][0], "Orphan manifest attribute")
            attributes = stack[-1][1]["attributes"]
            key = attribute[2]
            android_namespace = "http://schemas.android.com/apk/res/android:"
            if normalize_android and key.startswith(android_namespace):
                key = "android:" + key[len(android_namespace):]
            require(key not in attributes, "Duplicate manifest attribute")
            attributes[key] = attribute[3]
    require(len(roots) == 1 and roots[0]["name"] == "manifest", "Missing unique manifest root")
    return roots[0]


def literal(attributes: dict[str, str], key: str) -> str | None:
    value = attributes.get(key, "")
    matched = re.match(r'^"([^"]*)"(?:\s|$)', value)
    return matched[1] if matched else None


def manifest_version_diagnostic(text: str, expected: dict) -> dict:
    """Describe only fixed version keys; never retain raw XML or arbitrary values."""
    result = {"schema": "talkback-manifest-version-failure/v1", "qualified": False,
              "expected_version": expected["version_name"], "parsed": False, "attributes": []}
    if len(text.encode()) > 16 * 1024 * 1024:
        return result
    try:
        attributes = parse_xmltree(text, normalize_android=False)["attributes"]
    except ValueError:
        return result
    result["parsed"] = True
    for key in ("android:versionName", "http://schemas.android.com/apk/res/android:versionName"):
        raw = attributes.get(key, "")
        value = literal(attributes, key)
        # Only the public upstream release/timestamp vocabulary may be disclosed.
        safe = value is not None and len(value) <= 96 and re.fullmatch(
            r"TfPu_release_[0-9_]+-(?:prerequisite_[0-9a-f]{12}|[0-9_]{16})", value) is not None
        shape = ("literal" if value is not None else "typed" if raw.startswith("(type ")
                 else "reference" if raw.startswith("@") else "other" if raw else "absent")
        result["attributes"].append({"key": key, "present": key in attributes,
                                     "representation": shape,
                                     "matches_expected": value == expected["version_name"],
                                     "observed_version": value if safe else None})
    return result


def validate_manifest(text: str, expected: dict) -> None:
    root = parse_xmltree(text)
    require(literal(root["attributes"], "package") == expected["package"], "APK package differs")
    require(literal(root["attributes"], "android:versionName") == expected["version_name"],
            "APK version differs from the fixed upstream overlay")
    applications = [node for node in root["children"] if node["name"] == "application"]
    require(len(applications) == 1, "APK application is not unique")
    application = applications[0]
    require(literal(application["attributes"], "android:name") == expected["application"],
            "APK application class differs")
    services = [node for node in application["children"]
                if node["name"] == "service"
                and literal(node["attributes"], "android:name") == expected["service"]]
    require(len(services) == 1, "TalkBack service is missing or duplicated")
    service = services[0]
    require(literal(service["attributes"], "android:permission")
            == "android.permission.BIND_ACCESSIBILITY_SERVICE", "Service permission differs")
    require(service["attributes"].get("android:exported") in
            {"(type 0x12)0xffffffff", "true"}, "TalkBack service is not explicitly exported")
    actions = [literal(action["attributes"], "android:name")
               for intent in service["children"] if intent["name"] == "intent-filter"
               for action in intent["children"] if action["name"] == "action"]
    require("android.accessibilityservice.AccessibilityService" in actions,
            "TalkBack accessibility action is absent")
    metadata = [node for node in service["children"] if node["name"] == "meta-data"
                and literal(node["attributes"], "android:name") == "android.accessibilityservice"]
    require(len(metadata) == 1, "TalkBack accessibility configuration is absent")
    reference = re.fullmatch(r"@0x([0-9a-fA-F]{8})",
                             metadata[0]["attributes"].get("android:resource", ""))
    require(reference is not None and int(reference[1], 16) != 0,
            "TalkBack accessibility configuration is not a nonzero resource reference")


def inspect_apk(path: Path, manifest: dict) -> list[dict]:
    limits, expected = manifest["limits"], manifest["expected"]
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= limits["apk_bytes"],
            "APK is missing, linked or outside the byte bound")
    natives = []
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) <= limits["zip_entries"], "APK entry count exceeds the bound")
        names = [entry.filename for entry in entries]
        require(len(set(names)) == len(names), "APK has duplicate entry names")
        require("AndroidManifest.xml" in names and "classes.dex" in names, "APK code or manifest is absent")
        require(sum(entry.file_size for entry in entries) <= limits["uncompressed_bytes"],
                "APK uncompressed size exceeds the bound")
        for entry in entries:
            parts = PurePosixPath(entry.filename).parts
            require(parts and not entry.filename.startswith("/") and ".." not in parts
                    and "\\" not in entry.filename, "Unsafe APK entry path")
            require(not stat.S_ISLNK(entry.external_attr >> 16), "APK entry is a symlink")
            require(entry.file_size <= limits["entry_bytes"], "APK entry exceeds the byte bound")
            if entry.is_dir() or not entry.filename.startswith("lib/"):
                continue
            require(entry.filename == "/".join(parts), "APK native library path is not canonical")
            require(len(parts) == 3 and parts[1] == expected["abi"] and parts[2].endswith(".so"),
                    "APK contains a foreign or malformed native library")
            data = archive.read(entry)
            require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01",
                    "Native library is not a little-endian ELF64 image")
            require(struct.unpack_from("<HH", data, 16) == (3, 62),
                    "Native library is not an x86_64 shared object")
            natives.append({"path": entry.filename, "bytes": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()})
    require(set(expected["native_libraries"]) <= {Path(item["path"]).name for item in natives},
            "One or more source-built braille libraries are absent")
    return sorted(natives, key=lambda item: item["path"])


def validate_dependencies(receipt: dict, manifest: dict) -> None:
    require(isinstance(receipt, dict) and set(receipt) == {
        "schema", "build_completed", "preapproved_checksums", "gradle", "android_modules",
        "configurations", "components", "artifacts"}, "Dependency observation fields differ")
    require(receipt.get("schema") == "talkback-dependency-observation/v1"
            and receipt.get("build_completed") is True
            and receipt.get("preapproved_checksums") is False, "Dependency build observation differs")
    require(receipt.get("gradle") == manifest["tools"]["gradle"], "Observed Gradle version differs")
    modules = receipt.get("android_modules", {})
    require(isinstance(modules, dict) and bool(modules), "No Android module observations")
    for module in modules.values():
        require(module == {"compile_sdk": manifest["tools"]["compile_sdk"],
                           "build_tools": manifest["tools"]["build_tools"]}, "Android tool version differs")
    configurations = receipt["configurations"]
    require(isinstance(configurations, list) and 0 < len(configurations) <= 20000
            and all(isinstance(item, str) and len(item) <= 4096
                    and re.fullmatch(r":[^|\r\n]*\|[01]\|[^|\r\n]+", item) is not None
                    for item in configurations)
            and len(set(configurations)) == len(configurations), "Dependency configurations differ")
    components = receipt.get("components", [])
    require(isinstance(components, list) and 0 < len(components) <= 20000
            and all(isinstance(item, str) for item in components)
            and len(set(components)) == len(components), "Dependency component identities differ")
    component_ids = set(components)
    expected_components = set(manifest["expected"]["dependency_components"])
    require(expected_components <= component_ids,
            "Pinned dependencies missing from observed graph: "
            + ", ".join(sorted(expected_components - component_ids)))
    artifacts = receipt.get("artifacts", [])
    require(isinstance(artifacts, list) and 0 < len(artifacts) <= manifest["limits"]["dependency_artifacts"],
            "Dependency artifact observations are absent or over the bound")
    seen, artifact_components = set(), set()
    for artifact in artifacts:
        require(isinstance(artifact, dict) and set(artifact) == {"component", "name", "bytes", "sha256"},
                "Dependency artifact fields differ")
        component = artifact.get("component", "")
        require(isinstance(component, str) and component.count(":") == 2
                and not re.search(r"[+\[\](),]|latest\.|SNAPSHOT", component, re.I),
                "A dependency does not have an exact observed version")
        require(component in component_ids, "An artifact component is absent from the observed graph")
        require(bool(HEX64.fullmatch(artifact.get("sha256", "")))
                and type(artifact.get("bytes")) is int and artifact["bytes"] > 0,
                "An external artifact hash or length is missing")
        name = artifact.get("name", "")
        require(isinstance(name, str) and name == Path(name).name and "\\" not in name and bool(name),
                "An external artifact name is invalid")
        key = (component, name, artifact["sha256"])
        require(key not in seen, "An external artifact observation is duplicated")
        seen.add(key)
        artifact_components.add(component)
    require(expected_components <= artifact_components,
            "Pinned dependencies missing observed artifact hashes: "
            + ", ".join(sorted(expected_components - artifact_components)))


def dependency_diagnostic(receipt: dict, manifest: dict) -> dict:
    """Allowlisted pin presence only: never publish raw dependency data or paths."""
    pins = manifest["expected"]["dependency_components"]
    components = receipt.get("components", [])
    artifacts = receipt.get("artifacts", [])
    require(isinstance(components, list) and len(components) <= 20000
            and isinstance(artifacts, list) and len(artifacts) <= 20000,
            "Dependency diagnostic exceeds its collection bound")
    rows = []
    for pin in pins:
        rows.append({"expected_component": pin,
                     "observed_component": pin in components,
                     "observed_artifact": any(isinstance(item, dict)
                                              and item.get("component") == pin
                                              for item in artifacts)})
    return {"schema": "talkback-dependency-failure/v1", "qualified": False,
            "component_count": len(components), "artifact_count": len(artifacts),
            "pins": rows}


def signature_certificate(output: str) -> str:
    certificates = re.findall(r"(?m)^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", output)
    require(len(certificates) == 1, "The APK must have one observed verified signer")
    return certificates[0]


def record(project: Path, upstream: Path, work: Path, sdk: Path, expected_project: str) -> None:
    isolated_roots(project, upstream, work)
    manifest_bytes = read_observation(project / MANIFEST, SPECIFICATION_BYTES)
    manifest = json.loads(manifest_bytes)
    state = json.loads(read_observation(work / "prepared.json", SPECIFICATION_BYTES))
    require(identity(project) == state["project"] and state["project"]["commit"] == expected_project,
            "Project identity changed during the build")
    require(identity(upstream) == state["upstream"]
            == {key: manifest["upstream"][key] for key in ("commit", "tree")},
            "Upstream identity changed during the build")
    require(hashlib.sha256(manifest_bytes).hexdigest() == state["manifest_sha256"]
            and observer_hash(project) == state["init_sha256"], "Build specification changed")
    overrides = {path: value["after_blob"] for path, value in manifest["overlays"].items()}
    require(overrides == state["overlay_blobs"], "The prepared overlay differs")
    require(verify_tracked(project) == state["project_tracked_files"], "Project file set changed")
    require(verify_tracked(upstream, overrides) == state["upstream_tracked_files"], "Upstream file set changed")
    wrapper_hashes = verify_copied_wrapper(project, work, manifest, state["copied_wrapper_sha256"])
    gradle_version = read_observation(work / "gradle-version.txt", OBSERVATION_BYTES).decode()
    java_version = read_observation(work / "java-version.txt", OBSERVATION_BYTES).decode()
    require("\nGradle " + manifest["tools"]["gradle"] + "\n" in "\n" + gradle_version,
            "Actual Gradle version output differs")
    require(bool(re.search(r'version "17(?:\.|")', java_version)), "JDK 17 was not observed")
    sdk_records = {}
    for directory in ("platforms/" + manifest["tools"]["compile_sdk"],
                      "build-tools/" + manifest["tools"]["build_tools"],
                      "ndk/" + manifest["tools"]["ndk"]):
        properties = read_observation(sdk / directory / "source.properties", OBSERVATION_BYTES).decode()
        revision = re.search(r"(?m)^Pkg\.Revision\s*=\s*(\S+)", properties)
        require(revision is not None, "SDK package revision is absent")
        sdk_records[directory] = {"revision": revision[1],
                                 "source_properties_sha256": hashlib.sha256(properties.encode()).hexdigest()}
    require(sdk_records["ndk/" + manifest["tools"]["ndk"]]["revision"] == manifest["tools"]["ndk"],
            "Installed NDK revision differs")
    require(sdk_records["build-tools/" + manifest["tools"]["build_tools"]]["revision"]
            == manifest["tools"]["build_tools"], "Installed build tools revision differs")
    dependencies_path = work / "dependencies.json"
    dependencies_bytes = read_observation(dependencies_path, DEPENDENCY_BYTES)
    dependencies_hash = hashlib.sha256(dependencies_bytes).hexdigest()
    dependencies = json.loads(dependencies_bytes, object_pairs_hook=dependency_object)
    try:
        validate_dependencies(dependencies, manifest)
    except ValueError as original:
        try:
            diagnostic = dependency_diagnostic(dependencies, manifest)
            diagnostic.update(project=state["project"], upstream=state["upstream"],
                              dependencies_sha256=dependencies_hash)
            publish_files(work / "diagnostics", {"dependency-failure.json":
                receipt_bytes(diagnostic)}, create=True)
        except Exception:
            original.add_note("The failure diagnostic could not be written")
        raise
    apks = list((upstream / "build/outputs/apk/phone/debug").glob("*.apk"))
    require(len(apks) == 1, "The phone debug build must produce exactly one APK")
    apk = apks[0]
    natives = inspect_apk(apk, manifest)
    aapt = sdk / "build-tools" / manifest["tools"]["build_tools"] / "aapt2"
    xmltree = run([str(aapt), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"]).decode()
    try:
        validate_manifest(xmltree, manifest["expected"])
    except ValueError as original:
        try:
            diagnostic = manifest_version_diagnostic(xmltree, manifest["expected"])
            diagnostic.update(project=state["project"], upstream=state["upstream"],
                              apk_sha256=sha256_file(apk))
            publish_files(work / "diagnostics", {"manifest-version-failure.json":
                receipt_bytes(diagnostic)}, create=True)
        except Exception:
            original.add_note("The failure diagnostic could not be written")
        raise
    aapt_version = aapt2_version(aapt)
    signer = sdk / "build-tools" / manifest["tools"]["build_tools"] / "apksigner"
    signing_output = run([str(signer), "verify", "--verbose", "--print-certs", str(apk)]).decode()
    certificate = signature_certificate(signing_output)
    output = work / "artifacts"
    target = output / "talkback-phone-x86_64.apk"
    digest = sha256_file(apk)
    receipt = {
        "schema": "talkback-build-prerequisite-result/v1",
        "project": state["project"], "upstream": state["upstream"],
        "manifest_sha256": state["manifest_sha256"], "overlay_blobs": overrides,
        "project_tracked_files": state["project_tracked_files"],
        "upstream_tracked_files": state["upstream_tracked_files"],
        "copied_wrapper_sha256": wrapper_hashes,
        "tools": {"java_output": java_version, "gradle_output": gradle_version,
                  "aapt2": aapt_version, "sdk_packages": sdk_records},
        "dependencies_sha256": dependencies_hash,
        "dependency_checksums_preapproved": False,
        "apk": {"name": target.name, "bytes": apk.stat().st_size, "sha256": digest,
                "package": manifest["expected"]["package"], "service": manifest["expected"]["service"],
                "native_libraries": natives,
                "signing": {"verified": True, "certificate_sha256": certificate,
                            "variant": "phoneDebug", "release_identity_claimed": False}},
        "scope": {"phone_debug_built": True, "x86_64_packaging_checked": True,
                  "service_declaration_checked": True, "installed": False, "service_enabled": False,
                  "speech_observed": False, "talkback_acceptance": False}
    }
    publish_files(output, {
        target.name: apk, "dependencies.json": dependencies_bytes,
        "manifest.xmltree.txt": xmltree.encode(), "signing.txt": signing_output.encode(),
        "receipt.json": receipt_bytes(receipt)
    }, expected_hashes={target.name: digest})
    print("Verified the source-built x86_64 APK prerequisite; no TalkBack service was run")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "record"))
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--expected-project", required=True)
    parser.add_argument("--sdk", type=Path)
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.project, args.upstream, args.work, args.expected_project)
    else:
        require(args.sdk is not None, "Recording requires the actual SDK path")
        record(args.project, args.upstream, args.work, args.sdk, args.expected_project)


if __name__ == "__main__":
    main()
