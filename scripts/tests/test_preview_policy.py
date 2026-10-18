"""Fail-closed checks for the packaged synthetic-preview declaration boundary."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preview_policy", ROOT / "scripts/check_preview_policy.py")
policy = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = policy
spec.loader.exec_module(policy)
E = policy.Element


def manifest():
    return E("manifest", {"package": policy.PACKAGE, "android:versionCode": "1",
                          "android:versionName": "0.0.1-synthetic"}, [
        E("uses-sdk", {"android:minSdkVersion": "26", "android:targetSdkVersion": "35"}),
        E("application", {"android:debuggable": "true", "android:allowBackup": "false",
                          "android:fullBackupContent": "false", "android:usesCleartextTraffic": "false",
                          "android:dataExtractionRules": "@0x7f040000"}, [
            E("activity", {"android:name": policy.PACKAGE + ".PreviewActivity", "android:exported": "true",
                           "android:launchMode": "1"}, [
                E("intent-filter", {}, [E("action", {"android:name": "android.intent.action.MAIN"}),
                                        E("category", {"android:name": "android.intent.category.LAUNCHER"})])])])])


def backup():
    return E("data-extraction-rules", {}, [E(name, {}, [E("exclude", {"domain": d, "path": "."})
                for d in sorted(policy.DOMAINS)]) for name in ["cloud-backup", "device-transfer"]])


class PreviewPolicyTest(unittest.TestCase):
    def test_version_reads_only_the_sdk_output_stream_without_accepting_diagnostics(self):
        version = "Android Asset Packaging Tool (aapt) 2.19-11948202\n"
        with tempfile.TemporaryDirectory() as temporary:
            tool = Path(temporary) / "aapt2-fixture"
            for stdout, stderr, code, expected in [
                ("", version, 0, None),
                (version, "", 0, "TOOL_FAILED"),
                ("unexpected output", version, 0, "TOOL_FAILED"),
                ("", "private diagnostic sentinel", 0, "TOOL_VERSION_FORMAT"),
                ("", version + "unexpected diagnostic\n", 0, "TOOL_VERSION_FORMAT"),
                ("", version, 1, "TOOL_FAILED"),
            ]:
                tool.write_text(f"#!{sys.executable}\nimport sys\n"
                                f"sys.stdout.write({stdout!r})\nsys.stderr.write({stderr!r})\n"
                                f"sys.exit({code})\n")
                tool.chmod(0o700)
                with self.subTest(stdout=stdout, stderr=stderr, code=code):
                    if expected is None:
                        self.assertEqual(version.strip(), policy.aapt2_version(tool))
                        # Ordinary inspection must still fail on the same stderr.
                        with self.assertRaisesRegex(policy.PolicyFailure, "^TOOL_FAILED$"):
                            policy.command([str(tool), "dump", "resources", "fixture.apk"])
                    else:
                        with self.assertRaisesRegex(policy.PolicyFailure, "^" + expected + "$"):
                            policy.aapt2_version(tool)

    def test_adopted_policy_accepts(self):
        self.assertEqual("0x7f040000", policy.manifest_policy(manifest()))
        policy.backup_policy(backup())

    def test_every_permission_form_and_additional_root_component_is_rejected(self):
        for name in ["uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m", "permission",
                     "instrumentation", "queries", "uses-feature", "application"]:
            with self.subTest(name=name):
                root = manifest()
                root.children.append(E(name))
                with self.assertRaises(policy.PolicyFailure):
                    policy.manifest_policy(root)

    def test_additional_components_are_rejected_even_when_unexported(self):
        for name in ["activity", "activity-alias", "receiver", "service", "provider", "meta-data"]:
            root = manifest()
            root.children[1].children.append(E(name, {"android:exported": "false"}))
            with self.subTest(name=name), self.assertRaises(policy.PolicyFailure):
                policy.manifest_policy(root)

    def test_security_flags_cannot_be_removed_or_enabled(self):
        for key in ["android:allowBackup", "android:fullBackupContent", "android:usesCleartextTraffic"]:
            for value in [None, "true", "@0x7f040001"]:
                root = manifest()
                if value is None:
                    del root.children[1].attributes[key]
                else:
                    root.children[1].attributes[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(policy.PolicyFailure):
                    policy.manifest_policy(root)

    def test_unreviewed_application_overrides_and_manifest_identity_are_rejected(self):
        for key in ["android:networkSecurityConfig", "android:backupAgent", "android:permission",
                    "android:sharedUserId", "android:appComponentFactory"]:
            root = manifest()
            root.children[1].attributes[key] = "unexpected"
            with self.subTest(key=key), self.assertRaises(policy.PolicyFailure):
                policy.manifest_policy(root)
        root = manifest()
        root.attributes["package"] = "unexpected"
        with self.assertRaisesRegex(policy.PolicyFailure, "PACKAGE_IDENTITY"):
            policy.manifest_policy(root)

    def test_inbound_data_or_extra_action_cannot_join_the_launcher(self):
        for node in [E("data", {"android:scheme": "https"}), E("action", {"android:name": "android.intent.action.VIEW"})]:
            root = manifest()
            root.children[1].children[0].children[0].children.append(node)
            with self.assertRaises(policy.PolicyFailure):
                policy.manifest_policy(root)

    def test_each_transport_requires_every_distinct_domain_root(self):
        for index in [0, 1]:
            for mutation in ["missing", "duplicate", "narrow", "include"]:
                root = backup()
                rows = root.children[index].children
                if mutation == "missing":
                    rows.pop()
                elif mutation == "duplicate":
                    rows[-1] = copy.deepcopy(rows[0])
                elif mutation == "narrow":
                    rows[-1].attributes["path"] = "only-one-file"
                else:
                    rows[-1].name = "include"
                with self.subTest(index=index, mutation=mutation), self.assertRaises(policy.PolicyFailure):
                    policy.backup_policy(root)

    def test_missing_duplicate_or_attributed_transport_is_rejected(self):
        for mutation in ["missing", "duplicate", "attribute"]:
            root = backup()
            if mutation == "missing":
                root.children.pop()
            elif mutation == "duplicate":
                root.children[1] = copy.deepcopy(root.children[0])
            else:
                root.children[0].attributes["disableIfNoEncryptionCapabilities"] = "true"
            with self.assertRaises(policy.PolicyFailure):
                policy.backup_policy(root)

    def test_referenced_resource_requires_one_exact_default_file_not_alias_or_override(self):
        header = "    resource 0x7f040000 xml/backup_rules\n"
        entry = "      () (file) res/xml/backup_rules.xml type=XML\n"
        policy.backup_resource_policy("Binary APK\n" + header + entry, "0x7f040000")
        for value in [header + "      () @0x7f040001\n", header + entry.replace("backup_rules.xml", "other.xml"),
                      header + entry + "      (v35) (file) res/xml-v35/backup_rules.xml type=XML\n",
                      (header + entry) * 2, header + entry.replace("()", "(v31)")]:
            with self.subTest(value=value), self.assertRaises(policy.PolicyFailure):
                policy.backup_resource_policy(value, "0x7f040000")
        with self.assertRaises(policy.PolicyFailure):
            policy.backup_resource_policy(header + entry, "0x7f040001")

    def test_xmltree_preserves_structure_and_rejects_unknown_or_ambiguous_output(self):
        text = 'N: android=http://schemas.android.com/apk/res/android (line=2)\n  E: manifest (line=2)\n' + \
               '    A: package="safe" (Raw: "safe")\n      E: application (line=3)\n' + \
               '        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false\n'
        root = policy.xmltree(text)
        self.assertEqual("safe", root.attributes["package"])
        self.assertEqual("false", root.children[0].attributes["android:allowBackup"])
        for changed in [text.replace('Raw: "safe"', 'Raw: "different"'), text + "unparsed content\n",
                        text + '        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=true\n',
                        text + "E: manifest (line=2)\n", text.replace("false", "@null"), ""]:
            with self.subTest(changed=changed), self.assertRaises(policy.PolicyFailure):
                policy.xmltree(changed)

    def test_framework_attribute_ids_must_match_the_qualified_name(self):
        prefix = "E: application (line=1)\n  A: "
        valid = policy.ANDROID + "allowBackup(0x01010280)=false"
        self.assertEqual("false", policy.xmltree(prefix + valid).attributes["android:allowBackup"])
        for attribute in [
            valid.replace("(0x01010280)", ""),
            valid.replace("0x01010280", "0x00000000"),
            valid.replace("0x01010280", "0x01010281"),
            valid.replace("allowBackup", "fullBackupContent"),
            valid.replace(policy.ANDROID, "android:"),
            valid.replace(policy.ANDROID, "http://synthetic.invalid/android:"),
            'package(0x01010280)="synthetic" (Raw: "synthetic")',
            'domain(0x01010280)="root" (Raw: "root")',
        ]:
            with self.subTest(attribute=attribute), \
                 self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_RESOURCE_ID$"):
                policy.xmltree(prefix + attribute)

    def test_framework_attribute_ids_keep_lookup_order_within_each_element(self):
        first = "  A: " + policy.ANDROID + "allowBackup(0x01010280)=false\n"
        second = "  A: " + policy.ANDROID + "fullBackupContent(0x010104eb)=false\n"
        plain = '  A: label="synthetic" (Raw: "synthetic")\n'
        child = "  E: activity (line=2)\n    A: " + policy.ANDROID + 'name(0x01010003)="x" (Raw: "x")\n'
        # Unqualified zero-ID attributes and a child's separate lower IDs
        # must not reset or contaminate the parent's framework lookup order.
        valid = "E: application (line=1)\n" + first + plain + second + child
        self.assertEqual("false", policy.xmltree(valid).attributes["android:allowBackup"])
        with self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_RESOURCE_ORDER$"):
            policy.xmltree("E: application (line=1)\n" + second + plain + first)

    def test_reference_boolean_and_integer_spellings_cannot_be_quoted_strings(self):
        for name, value in [
            ("dataExtractionRules", "@0x7f040000"), ("theme", "@0x01030241"), ("icon", "@0x7f010000"),
            ("fullBackupContent", "false"), ("allowBackup", "false"), ("usesCleartextTraffic", "false"),
            ("debuggable", "true"), ("exported", "true"), ("supportsRtl", "true"),
            ("extractNativeLibs", "false"), ("minSdkVersion", "26"), ("targetSdkVersion", "35"),
            ("compileSdkVersion", "35"), ("versionCode", "1"), ("launchMode", "1"),
        ]:
            prefix = "E: application (line=1)\n  A: " + policy.ANDROID + name + \
                     "(" + policy.FRAMEWORK_ATTRIBUTE_IDS[name] + ")="
            with self.subTest(name=name):
                self.assertEqual(value, policy.xmltree(prefix + value).attributes["android:" + name])
                quoted = json.dumps(value)
                with self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_VALUE_KIND$"):
                    policy.xmltree(prefix + quoted + " (Raw: " + quoted + ")")

    def test_string_attributes_keep_their_kinds_and_explicit_label_reference(self):
        for name in ["name", "versionName", "compileSdkVersionCodename", "label"]:
            prefix = "E: application (line=1)\n  A: " + policy.ANDROID + name + \
                     "(" + policy.FRAMEWORK_ATTRIBUTE_IDS[name] + ")="
            self.assertEqual("1", policy.xmltree(prefix + '\"1\" (Raw: \"1\")').attributes["android:" + name])
            invalid = ["1", "true", "false"] + ([] if name == "label" else ["@0x7f040000"])
            for value in invalid:
                with self.subTest(name=name, value=value), \
                     self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_VALUE_KIND$"):
                    policy.xmltree(prefix + value)
            if name == "label":
                self.assertEqual("@0x7f040000", policy.xmltree(prefix + "@0x7f040000").attributes["android:label"])

    def test_inventory_must_identify_the_current_commit_tree_and_exact_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / policy.APK_PATH
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"synthetic identity fixture")
            inventory_path = root / "build/inventory/runtime-inventory.json"
            inventory_path.parent.mkdir(parents=True)
            inventory = {"schema_version": 1, "scope": "resolved-runtime-classpaths",
                         "source": {"commit": "a" * 40, "tree": "b" * 40},
                         "outputs": [{"kind": "unsigned-debug-preview", "file": policy.APK_PATH,
                                      "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes": apk.stat().st_size}]}
            for mutation, expected in [("commit", "INVENTORY_SOURCE_MISMATCH"),
                                       ("tree", "INVENTORY_SOURCE_MISMATCH"),
                                       ("hash", "INVENTORY_APK_MISMATCH"),
                                       ("size", "INVENTORY_APK_MISMATCH"),
                                       ("duplicate", "INVENTORY_APK_IDENTITY"),
                                       ("path", "INVENTORY_APK_IDENTITY")]:
                changed = copy.deepcopy(inventory)
                if mutation in {"commit", "tree"}:
                    changed["source"][mutation] = "c" * 40
                elif mutation == "hash":
                    changed["outputs"][0]["sha256"] = "0" * 64
                elif mutation == "size":
                    changed["outputs"][0]["bytes"] += 1
                elif mutation == "duplicate":
                    changed["outputs"].append(copy.deepcopy(changed["outputs"][0]))
                else:
                    changed["outputs"][0]["file"] = "different.apk"
                inventory_path.write_text(json.dumps(changed))
                with self.subTest(mutation=mutation), \
                     patch.object(policy, "command", side_effect=["a" * 40 + "\n" + "b" * 40, ""]), \
                     patch.object(policy, "inspect_apk") as inspect, \
                     self.assertRaisesRegex(policy.PolicyFailure, expected):
                    policy.check(root, root / "unused-tool")
                inspect.assert_not_called()

    def test_source_changes_during_inspection_cannot_receive_a_pass(self):
        for mutation in ["none", "tracked-file", "head"]:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                def git(*arguments):
                    return subprocess.run(["git", *arguments], cwd=root, check=True, timeout=30,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
                git("init", "--quiet")
                (root / ".gitignore").write_text("build/\n**/build/\n")
                tracked = root / "tracked.txt"
                tracked.write_text("original\n")
                aapt2 = root / "aapt2-fixture"
                aapt2.write_bytes(b"synthetic tool identity")
                git("add", "--all")
                def commit(message):
                    git("-c", "user.name=Policy test", "-c", "user.email=policy@example.invalid",
                        "-c", "commit.gpgsign=false", "commit", "--quiet", "--no-verify", "-am", message)
                commit("Synthetic source baseline")
                source = policy.source_identity(root)
                apk = root / policy.APK_PATH
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"unchanged synthetic artifact")
                apk_hash = policy.sha256(apk)
                inventory_path = root / "build/inventory/runtime-inventory.json"
                inventory_path.parent.mkdir(parents=True)
                inventory_path.write_text(json.dumps({"schema_version": 1, "scope": "resolved-runtime-classpaths",
                    "source": source, "outputs": [{"kind": "unsigned-debug-preview", "file": policy.APK_PATH,
                    "sha256": apk_hash, "bytes": apk.stat().st_size}]}))
                inventory_hash = policy.sha256(inventory_path)
                run_command = policy.command
                def commands(arguments, cwd=None, **options):
                    if arguments == [str(aapt2), "version"]:
                        return "Android Asset Packaging Tool (aapt) 2.19-11948202"
                    return run_command(arguments, cwd, **options)
                def inspect(*_):
                    if mutation != "none":
                        tracked.write_text("changed during inspection\n")
                        if mutation == "head":
                            commit("Synthetic concurrent source advance")
                    return {"sha256": apk_hash}
                with patch.object(policy, "command", side_effect=commands), \
                     patch.object(policy, "inspect_apk", side_effect=inspect):
                    if mutation == "none":
                        self.assertEqual("passed", policy.check(root, aapt2)["status"])
                    else:
                        expected = "SOURCE_CHANGED" if mutation == "tracked-file" else "SOURCE_CHANGED_DURING_INSPECTION"
                        with self.assertRaisesRegex(policy.PolicyFailure, "^" + expected + "$"):
                            policy.check(root, aapt2)
                self.assertEqual(apk_hash, policy.sha256(apk))
                self.assertEqual(inventory_hash, policy.sha256(inventory_path))

    def test_failed_current_check_removes_previous_pass_without_disclosing_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "build/inventory/preview-policy.json"
            output.parent.mkdir(parents=True)
            output.write_text('{"status":"passed","source":"old"}')
            with patch.object(policy, "ROOT", root), \
                 patch.object(sys, "argv", ["check", "--aapt2", "/unused"]), \
                 patch.object(policy, "check", side_effect=ValueError("private input sentinel")), \
                 patch("builtins.print") as printed:
                self.assertEqual(1, policy.main())
                self.assertFalse(output.exists())
                self.assertNotIn("sentinel", str(printed.call_args_list))


if __name__ == "__main__":
    unittest.main()
