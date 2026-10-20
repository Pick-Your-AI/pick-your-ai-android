"""Reproduce two frozen-rule observations from an exact private Research closure.

The existing offline Git repository supplies reviewed bytes, never a moving
worktree or network import. Default mode compares the complete retained corpus.
Only --write replaces the generated fixture. No evaluator or field is activated.
"""
import argparse
import ast
import base64
from copy import deepcopy
import gzip
import hashlib
import importlib
import importlib.metadata
import io
from itertools import product
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "05ec3efec6b29316eef9bc036c42678e4d225ce7"
PROFILE = "android-frozen-support-rules/0.1"
DESTINATION = ROOT / "core/src/test/resources/evidence/rules/frozen-support-cases.json.gz"
PINS = {'contracts/v1/baseline/core-ruleset.json': {'blob': 'd768d7121e1e29519da5ee25823e0e85ad3dcb07',
                                             'bytes': 63614,
                                             'sha256': 'e60e43c0abc3c5d927e10c578a39fdedfa76e00fce6242e2db3a46e4b1da907e'},
 'contracts/v1/examples/build.json': {'blob': '8309dfa05518fe86b86cae790299977473942eb4',
                                      'bytes': 14681,
                                      'sha256': '26762208769a8cf1012b4f33b1c1608556d275187ce21117d0b9632cdf951864'},
 'contracts/v1/examples/catalog.json': {'blob': '062af97f42f32dc4358748f6c45ac35b602a3d2b',
                                        'bytes': 23827,
                                        'sha256': '2e9120db823faef3e1e330a7d2e8067b3ab064328e2faed699d15cf41880fbf8'},
 'contracts/v1/reference/evaluate.py': {'blob': '0e01df6ada663b41019ffb7825290b47b142fa38',
                                        'bytes': 10946,
                                        'sha256': '092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a'},
 'contracts/v1/schemas/build.schema.json': {'blob': '2b173755b1665dea3d9cddf0d33062cf1adc94b5',
                                            'bytes': 5121,
                                            'sha256': '1beacb0b01fcdea0b8b47357cdf2e99d80a10b8a4218aaac9e3b6af55391b42a'},
 'contracts/v1/schemas/catalog-item.schema.json': {'blob': 'd4576e9044b5675a06d1d0d922afd129682fd501',
                                                   'bytes': 45569,
                                                   'sha256': '81c960c6e7dfe046634062e3f9ce5a1089b1d88adbd9fd45d9a67dcfc0339a25'},
 'contracts/v1/schemas/catalog-snapshot.schema.json': {'blob': '88af389f4f2b72e38f5c25e4cabd73df2d6c6225',
                                                       'bytes': 760,
                                                       'sha256': 'a5af428386768b698d280a18bf99c0e52364ee397a2075685213b3d0762693a4'},
 'contracts/v1/schemas/common.schema.json': {'blob': 'fc55e2aa32a9cd63a16ee1ea90709b6ca733f6b5',
                                             'bytes': 2490,
                                             'sha256': '0342ce2f1114e7276fc7f6a08ff5b86e8727caadd83ae4f443de9a7e1431c43c'},
 'contracts/v1/schemas/component-registry.json': {'blob': '56dd96f98ff04130d7672e87eeb9deaba2cb934b',
                                                  'bytes': 5428,
                                                  'sha256': 'cd60070e912a60504f605f2fd9f86c64520160cce7f4184c8ea200209c6b1918'},
 'contracts/v1/schemas/cost-estimate.schema.json': {'blob': '1031d53c3b9d71fcfa28f9054792f83f0155d9cb',
                                                    'bytes': 3763,
                                                    'sha256': 'ef7cf8c0492022c1a8a1f971e49eb86dfad5b66ce6a4b2cf72a965fc9ba81259'},
 'contracts/v1/schemas/dependency-graph.schema.json': {'blob': '8bb30e002ccb13339e8e3061c89e8d5cf4df3b5e',
                                                       'bytes': 3668,
                                                       'sha256': 'fb6a8bb42a9424d4ff2b520301f816c247789a3d70d1124afea1a8dc5ed5bd75'},
 'contracts/v1/schemas/deployment-manifest.schema.json': {'blob': '5290ae6a40e9a957e8055b440a041f9c3becd98e',
                                                          'bytes': 2798,
                                                          'sha256': '86bc716c906aca1299d9129eec08abec44378c26b5a97317d6310afe84d6b374'},
 'contracts/v1/schemas/evaluation-report.schema.json': {'blob': '77875a71a6b7440d5228a1b9880c2651c5bae20f',
                                                        'bytes': 3972,
                                                        'sha256': '78935ec78848de7655bd972851aa0357c4426f6870fc028ba81739c85040359b'},
 'contracts/v1/schemas/event.schema.json': {'blob': 'b15b739c8a2e4db2d973a3f5f6ef1eab616bb3dc',
                                            'bytes': 3506,
                                            'sha256': 'e169ad8d2f7417580ef63a5098f8ec54dd514af3ce609c59bdb7780b89d6039a'},
 'contracts/v1/schemas/pricebook.schema.json': {'blob': 'fc6e3e74eda33325f68e6e820b3b2700dbda9a73',
                                                'bytes': 2586,
                                                'sha256': '169e1a48847bee8b6af4b266a55bcebe317b5655dd6abb7718e06331b1df6288'},
 'contracts/v1/schemas/quality-evaluation-run.schema.json': {'blob': '8dd515b03e07916115a0864a8347968d6dd0dc20',
                                                             'bytes': 2588,
                                                             'sha256': 'ed6b3f60805428e3788ce27fa224fb3d5008998f0637f24723bf65eea546a959'},
 'contracts/v1/schemas/resource-estimate.schema.json': {'blob': '9dbafa69f59f8667a4e124ec34d8df17606c8f2e',
                                                        'bytes': 2713,
                                                        'sha256': '39ab5d061f9021ce14e1f987717441cff04d1a40f76db5582d343d599a5d876c'},
 'contracts/v1/schemas/ruleset.schema.json': {'blob': 'dfbbc2284925b101fe56eb03adf1b2c6530505d7',
                                              'bytes': 8726,
                                              'sha256': 'b251e49774d7e6a34522555adefd40f56f5e647a8a2b4a69a0be0a4618b1a8d3'},
 'contracts/v1/schemas/selection.schema.json': {'blob': '9a66cceb442c50c93c6e214b760a357146fa3c69',
                                                'bytes': 27630,
                                                'sha256': 'f8f189c6555fec41e616c32f081d06fab9f966b0fa4891ddca55580f577d51a8'},
 'requirements-research.txt': {'blob': 'a6f85bdba9db94df4cde41f00f6ce315763f1e29',
                               'bytes': 213,
                               'sha256': '6877fcf07694e92c6804bc13964dc71c22955c4a7aacb8ffa54bd8c68ecdd963'},
 'scripts/validate_research.py': {'blob': 'b7807ec733b56adfa03e1ea17e1529090f20c267',
                                  'bytes': 12293,
                                  'sha256': '79f264b13186d3f5061716a309c47bd2c0a8c20be72cadd838919be86d2244cf'},
 'tests/fixtures/support-membership-binding.schema.json': {'blob': '25abca5a36571ca707d70bfb95e10983ffea9a57',
                                                           'bytes': 8212,
                                                           'sha256': '5f7af7c503058c82d0956b69d8900752b860cb1c222c1aa94bb153464f7ce1a0'},
 'tests/fixtures/t0-answer-states.schema.json': {'blob': '91aab5e6bae86f3b7dfb8e504a65c02502c6defb',
                                                 'bytes': 1370,
                                                 'sha256': '51e18c1b5694daf905aee539712dd0a9e46e78be0ca8e438d758628d88357e0d'},
 'tests/frozen_support_rule_adapter_prototype.py': {'blob': '1268cdec6194298eee4ab8047dc5a032eaf988bb',
                                                    'bytes': 12067,
                                                    'sha256': '8c91e817767bcbe855b34acf9e5b5461bef250cda211b4840c0c71840b18b643'},
 'tests/selection_quantifier_prototype.py': {'blob': '922cc7a882cc2cccbe122e98127253d71c12205f',
                                             'bytes': 4953,
                                             'sha256': '2db82645589d292ac17c89413bae340187ddad7b7751c9dc5b670d1713b677f7'},
 'tests/support_membership_binding_prototype.py': {'blob': '77f9771f89bca803448698cdef931c69748a27f3',
                                                   'bytes': 10442,
                                                   'sha256': '66fdc043089216705cda53540f5bfff1b3f840e13ee5eca2aef5488e07b405d8'},
 'tests/support_membership_prototype.py': {'blob': '4406ccc9de150aa2955abce6173025c350e17da2',
                                           'bytes': 6146,
                                           'sha256': 'a2120d738889253b5bf558827abc22c4de0be99dc6caa3ebee89551e4817a185'},
 'tests/t0_answer_state_prototype.py': {'blob': '01a713cc7021a6f42e9b7b3850b334527904f652',
                                        'bytes': 13218,
                                        'sha256': '722fa8af1e3453413f2cd3a65161fbf9102ea1c1d57725e3dcd03c6167cd5631'},
 'tests/t0_known_value_schema.py': {'blob': '2691fac7def1590c8e4a4ad20258cd630f206d98',
                                    'bytes': 6426,
                                    'sha256': '1d7878298f4c3c3b8de5a2ade13a8c3b2f408d52b79df2d082e8102153da99dd'},
 'tests/test_frozen_support_rule_adapter.py': {'blob': 'a2521eb49f9616a0b832449ce1c6f69510dc529d',
                                               'bytes': 22212,
                                               'sha256': 'd5eca21cf14a6f295e0b28c7f9e69162a4191aaa3a6377f28a1a942763269013'}}
AUTO = object()
FACTS = (None, False, True)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, allow_nan=False, separators=(",", ":"))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode("utf-8")


def inputs(repository):
    if sys.version_info[:2] != (3, 12):
        raise ValueError("The reference requires Python 3.12")
    result = {}
    for path, pin in PINS.items():
        raw = subprocess.check_output(["git", "-C", str(repository), "show", f"{COMMIT}:{path}"])
        blob = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
        if len(raw) != pin["bytes"] or digest(raw) != pin["sha256"] or blob != pin["blob"]:
            raise ValueError(f"Reference identity mismatch: {path}")
        result[path] = raw
    # Verify the exact installed environment before any prototype import.
    for line in result["requirements-research.txt"].decode().splitlines():
        if not line or line.startswith("#"):
            continue
        name, version = line.split("==")
        if importlib.metadata.version(name) != version:
            raise ValueError(f"Reference dependency mismatch: {name}")
    # Every local static import must resolve inside the explicitly copied closure.
    for path, raw in result.items():
        if not path.endswith(".py"):
            continue
        for node in ast.walk(ast.parse(raw)):
            names = ([node.module] if isinstance(node, ast.ImportFrom) and node.module else
                     [alias.name for alias in node.names] if isinstance(node, ast.Import) else [])
            for name in names:
                if name.split(".")[0] not in {"contracts", "scripts", "tests"}:
                    continue
                direct = name.replace(".", "/") + ".py"
                imported_children = [name.replace(".", "/") + "/" + a.name + ".py"
                                     for a in node.names] if isinstance(node, ast.ImportFrom) else []
                if direct not in result and not all(child in result for child in imported_children):
                    raise ValueError(f"Unpinned project import in {path}: {name}")
    return result


def generate(reference, raw):
    build_original = raw["contracts/v1/examples/build.json"]
    catalog_original = raw["contracts/v1/examples/catalog.json"]
    baseline_build = json.loads(build_original)
    baseline_catalog = json.loads(catalog_original)
    rules = reference.RULE_IDS
    qualified = dict.fromkeys(reference.ELIGIBILITY, True)
    documents, rows, adapters = {}, [], {}

    def retain(value):
        key = digest(value)
        item = {"bytes": len(value), "sha256": key, "base64": base64.b64encode(value).decode("ascii")}
        if key in documents and documents[key] != item:
            raise ValueError("Input digest collision")
        documents[key] = item
        return key

    def selection(build, kind):
        return next(value for value in build["selections"] if value["kind"] == kind)

    def item(catalog, kind):
        return next(value for value in catalog["items"] if value["kind"] == kind)

    def pair():
        return deepcopy(baseline_build), deepcopy(baseline_catalog)

    def add(name, *, build=None, catalog=None, build_raw=None, catalog_raw=None, rule=rules[0],
            unsupported=(), exhaustive=None, complete=None, eligible=AUTO, checked=None,
            scope=AUTO, cohort_id="cohort.synthetic.support", cohort_version="0.1.0", error=None):
        b = build_raw if build_raw is not None else encoded(baseline_build if build is None else build)
        c = catalog_raw if catalog_raw is not None else encoded(baseline_catalog if catalog is None else catalog)
        evidence = {"cohort_id": cohort_id, "cohort_version": cohort_version, "scope": None,
                    "unsupported": None if unsupported is None else list(unsupported),
                    "exhaustive": exhaustive, "requested_complete": complete}
        invocation = {"build": retain(b), "catalog": retain(c), "rule": rule,
                      "evidence": evidence, "eligible_inputs": qualified if eligible is AUTO else eligible,
                      "cohort_checked_only": checked}
        phase = "construct"
        try:
            key = (digest(b), digest(c))
            if key not in adapters:
                adapters[key] = reference.FrozenSupportRuleAdapter(b, c)
            adapter = adapters[key]
            phase = "evaluate"
            evidence["scope"] = adapter.support_scope(rule) if scope is AUTO else deepcopy(scope)
            normalized = reference.RuleEvidence(
                cohort_id, cohort_version, evidence["scope"],
                None if unsupported is None else frozenset(unsupported), exhaustive, complete,
            )
            expected = adapter.evaluate(rule, normalized, eligible_inputs=invocation["eligible_inputs"],
                                        cohort_checked_only=checked)
        except ValueError:
            if error != phase:
                raise
            expected = {"error": phase}
        else:
            if error is not None:
                raise ValueError(f"Expected {error} rejection was not observed: {name}")
        wire = canonical(invocation)
        rows.append({"id": name, "input_json": wire, "input_sha256": digest(wire.encode("ascii")),
                     "expected": expected})

    for rule in rules:
        add("original-" + rule, rule=rule, build_raw=build_original, catalog_raw=catalog_original)

    # Real support operands with independent negative/exhaustive/completeness/provenance facts.
    for rule, positive, other, key in (
            (rules[0], "safetensors", "gguf", "model_formats"),
            (rules[1], "fp16", "int8", "supported_precisions")):
        for index, (supported, unsupported, exhaustive, complete, checked) in enumerate(product(
                ((positive,), (other,), (positive, other)),
                (None, (), (positive,), (other,), (positive, other)), FACTS, FACTS, FACTS)):
            build, catalog = pair()
            item(catalog, "inference_engine")["spec"][key] = list(supported)
            add(f"cohort-{rule}-{index:04d}", build=build, catalog=catalog, rule=rule,
                unsupported=unsupported, exhaustive=exhaustive, complete=complete, checked=checked)

    for rule, markers, checked in product(rules, product(FACTS, repeat=4), FACTS):
        qualifications = dict(zip(reference.ELIGIBILITY, markers))
        name = "qualification-" + rule + "-" + "-".join(str(x) for x in (*markers, checked))
        add(name, rule=rule, exhaustive=True, complete=True, eligible=qualifications, checked=checked)

    for rule in rules:
        for missing in reference.ELIGIBILITY:
            add("omitted-" + rule + "-" + missing, rule=rule,
                eligible={key: value for key, value in qualified.items() if key != missing})
        add("all-qualification-unresolved-" + rule, rule=rule, eligible=None)
        for shape in ("api", "engine-disabled", "engine-missing", "mode-version", "mode-missing",
                      "unknown-mode-disabled", "model-disabled", "precision-missing",
                      "model-item-missing", "engine-item-missing", "model-version", "engine-version",
                      "model-kind", "engine-kind",
                      "snapshot-mismatch", "selection-order", "catalog-order", "mixed-witness"):
            build, catalog = pair()
            if shape == "api":
                item(catalog, "deployment_mode")["spec"]["mode"] = "api"
            if shape in {"engine-disabled", "unknown-mode-disabled"}:
                selection(build, "inference_engine")["enabled"] = False
            if shape == "engine-missing":
                build["selections"].remove(selection(build, "inference_engine"))
            if shape in {"mode-version", "unknown-mode-disabled"}:
                selection(build, "deployment_mode")["catalog_item_version"] = "missing"
            if shape == "mode-missing":
                catalog["items"].remove(item(catalog, "deployment_mode"))
            if shape == "model-disabled":
                selection(build, "model")["enabled"] = False
            if shape == "precision-missing":
                selection(build, "model")["settings"].pop("precision")
            if shape.endswith("item-missing"):
                catalog["items"].remove(item(catalog, "model" if shape.startswith("model") else "inference_engine"))
            if shape in {"model-version", "engine-version"}:
                selection(build, "model" if shape.startswith("model") else "inference_engine")["catalog_item_version"] = "missing"
            if shape in {"model-kind", "engine-kind"}:
                chosen = selection(build, "model" if shape.startswith("model") else "inference_engine")
                other = item(catalog, "inference_engine" if shape.startswith("model") else "model")
                chosen["catalog_item_id"] = other["id"]
                chosen["catalog_item_version"] = other["version"]
            if shape == "snapshot-mismatch":
                build["locks"]["catalog_snapshot_id"] = "catalog.other"
            if shape == "selection-order":
                build["selections"].reverse()
            if shape == "catalog-order":
                catalog["items"].reverse()
            if shape == "mixed-witness":
                item(catalog, "model")["spec"]["model_formats"] = ["onnx", "gguf", "safetensors"]
                item(catalog, "inference_engine")["spec"]["model_formats"] = ["safetensors", "gguf"]
            for applicability in FACTS:
                add(f"operand-{rule}-{shape}-{applicability}", build=build, catalog=catalog, rule=rule,
                    unsupported=("gguf",), complete=False, checked=True,
                    eligible=qualified | {"applies_when": applicability})

    adapter = reference.FrozenSupportRuleAdapter(encoded(baseline_build), encoded(baseline_catalog))
    for rule in rules:
        exact = adapter.support_scope(rule)
        mutations = {
            "selection": lambda value: value.update(selection_id="selection.other"),
            "catalog": lambda value: value.update(catalog_sha256="a" * 64),
            "item-id": lambda value: value["item"].update(id="item.other"),
            "item-version": lambda value: value["item"].update(version="other"),
            "item-kind": lambda value: value["item"].update(kind="model"),
            "property": lambda value: value.update(property="/spec/other"),
            "conditions": lambda value: value.update(conditions_sha256="b" * 64),
        }
        add("scope-missing-" + rule, rule=rule, scope=None, exhaustive=True, complete=True)
        for key, change in mutations.items():
            changed = deepcopy(exact)
            change(changed)
            add("scope-" + rule + "-" + key, rule=rule, scope=changed, exhaustive=True, complete=True)
        add("raw-whitespace-" + rule, rule=rule, build_raw=encoded(baseline_build) + b" ", scope=exact)
        add("raw-catalog-whitespace-" + rule, rule=rule, catalog_raw=encoded(baseline_catalog) + b" ", scope=exact)

    # Full-document and disabled-identity errors must precede inactive shortcuts.
    for case in ("duplicate-id", "duplicate-role", "duplicate-item", "duplicate-item-version",
                 "bad-disabled-shape", "empty-format", "missing-format", "empty-support",
                 "null-support", "duplicate-support", "unknown-precision", "bool-revision",
                 "float-revision", "extra-build"):
        build, catalog = pair()
        item(catalog, "deployment_mode")["spec"]["mode"] = "api"
        if case in {"duplicate-id", "duplicate-role"}:
            value = deepcopy(selection(build, "inference_engine"))
            value["enabled"] = False
            if case == "duplicate-role":
                value["id"] = "selection.other-engine"
            else:
                value["role"] = "backup"
            build["selections"].append(value)
        if case.startswith("duplicate-item"):
            value = deepcopy(item(catalog, "inference_engine"))
            if case.endswith("version"):
                value["version"] = "other"
            catalog["items"].append(value)
        if case == "bad-disabled-shape":
            selection(build, "model")["enabled"] = False
            selection(build, "model")["settings"]["precision"] = "unknown"
        if case == "empty-format":
            item(catalog, "model")["spec"]["model_formats"] = []
        if case == "missing-format":
            item(catalog, "model")["spec"].pop("model_formats")
        if case in {"empty-support", "null-support", "duplicate-support"}:
            item(catalog, "inference_engine")["spec"]["model_formats"] = {
                "empty-support": [], "null-support": None, "duplicate-support": ["gguf", "gguf"]}[case]
        if case == "unknown-precision":
            selection(build, "model")["settings"]["precision"] = "unknown"
        if case == "bool-revision":
            build["revision"] = True
        if case == "float-revision":
            build["revision"] = 1.0
        if case == "extra-build":
            build["not_a_frozen_property"] = 1
        add("preflight-" + case, build=build, catalog=catalog, error="construct")

    build, catalog = pair()
    item(catalog, "deployment_mode")["spec"]["mode"] = "api"
    for value in ("", " ", "\u0085", "\u001c", "\u3000"):
        add("blank-negative-" + str(len(rows)), build=build, catalog=catalog,
            unsupported=(value,), error="evaluate")
    for field in reference.ELIGIBILITY:
        for marker in (0, 1, "true", []):
            add("bad-qualification-" + str(len(rows)), build=build, catalog=catalog,
                eligible=qualified | {field: marker}, error="evaluate")
    add("extra-qualification", eligible=qualified | {"verified": True}, error="evaluate")
    for marker in (0, 1, "true", []):
        add("bad-exhaustive-" + str(len(rows)), exhaustive=marker, error="evaluate")
        add("bad-complete-" + str(len(rows)), complete=marker, error="evaluate")
        add("bad-checked-" + str(len(rows)), checked=marker, error="evaluate")
    for value in ("A.invalid", "ab", "cohort\n", "a" * 121, "cohort/other"):
        add("bad-cohort-id-" + str(len(rows)), cohort_id=value, error="evaluate")
    for value in ("", " ", "\u0085", "bad\nversion", "a" * 201, "\ud800"):
        add("bad-cohort-version-" + str(len(rows)), cohort_version=value, error="evaluate")
    for value in ("unknown", "verified", "synthetic.\U0001f600"):
        add("literal-negative-" + str(len(rows)), unsupported=(value,), checked=True)
    for value in ("rule.other", "", "rule.precision.engine\n"):
        add("unsupported-rule-" + str(len(rows)), rule=value, error="evaluate")

    # Preserve Python finite-binary64 intake, including rounding and underflow.
    tokens = ("0.0", "-0.0", "1.0", "5e-324", "4.9e-324", "4e-324", "1e-324",
              "1e-999999", "0e999999", "1.0000000000000001", "1.9999999999999999",
              "2.0000000000000001", "2.0000000000000004", "-1e-324", "-1e-323",
              "1e20", "1e23", "9007199254740993.0", "1e309")
    for field in ("temperature", "top_p", "max_output_tokens"):
        for token in tokens:
            build, _ = pair()
            selection(build, "model")["settings"][field] = "SYNTHETIC_NUMERIC_TOKEN"
            b = encoded(build).replace(b'"SYNTHETIC_NUMERIC_TOKEN"', token.encode("ascii"))
            # Explicit admission expectation comes from the reference shape, not native arithmetic.
            value = float(token)
            admitted = ((0 <= value <= 2) if field == "temperature" else
                        (0 < value <= 1) if field == "top_p" else
                        (value >= 1 and value != float("inf") and value.is_integer()))
            add(f"numeric-{field}-{token}", build_raw=b, error=None if admitted else "construct")
    for token in ("9007199254740993", "100000000000000000000000", "1.0", "1e0", "true"):
        build, _ = pair()
        build["revision"] = "SYNTHETIC_REVISION"
        b = encoded(build).replace(b'"SYNTHETIC_REVISION"', token.encode("ascii"))
        add("revision-token-" + token, build_raw=b, error=None if token.isdecimal() else "construct")

    for suffix, b in (
            ("duplicate-key", encoded(baseline_build).replace(b'"revision": 1', b'"revision": 1,"revision": 1', 1)),
            ("utf8", b"\xff" + encoded(baseline_build)),
            ("bom", b"\xef\xbb\xbf" + encoded(baseline_build)),
            ("trailing", encoded(baseline_build) + b"{}"),
            ("nonfinite", encoded(baseline_build).replace(b'"revision": 1', b'"revision": NaN', 1)),
            ("surrogate", encoded(baseline_build).replace(b'"name":', b'"name": "\\ud800", "original_name":', 1))):
        add("raw-" + suffix, build_raw=b, error="construct")

    # Wider frozen-v1 identities remain observed text. They cannot become a
    # normalized evidence scope merely by being read from a structurally valid file.
    for target in ("selection", "item"):
        build, catalog = pair()
        if target == "selection":
            selection(build, "inference_engine")["id"] += "\n"
        else:
            old = item(catalog, "inference_engine")["id"]
            item(catalog, "inference_engine")["id"] = old + "\n"
            selection(build, "inference_engine")["catalog_item_id"] = old + "\n"
        for rule in rules:
            add("legacy-observed-" + target + "-" + rule, build=build, catalog=catalog, rule=rule, scope=None)
            add("legacy-proposed-" + target + "-" + rule, build=build, catalog=catalog, rule=rule, error="evaluate")

    if len({row["id"] for row in rows}) != len(rows):
        raise ValueError("Duplicate reference case identity")
    return {"profile": PROFILE, "prototype_profile": reference.PROFILE, "research_commit": COMMIT,
            "sources": PINS, "documents": documents, "cases": rows,
            "counts": {"cases": len(rows), "documents": len(documents),
                       "construct_rejections": sum(row["expected"].get("error") == "construct" for row in rows),
                       "evaluation_rejections": sum(row["expected"].get("error") == "evaluate" for row in rows)}}


def build(repository):
    source = inputs(repository)
    with tempfile.TemporaryDirectory(prefix="android-frozen-support-reference-") as name:
        private = Path(name).resolve()
        for path, raw in source.items():
            target = private / path
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as stream:
                stream.write(raw)
        prefixes = ("contracts", "scripts", "tests")
        previous = {name: value for name, value in tuple(sys.modules.items())
                    if name.split(".")[0] in prefixes}
        for name in previous:
            del sys.modules[name]
        # Namespace shells contain no executed source; all imported modules are pinned above.
        for name in ("contracts", "contracts.v1", "contracts.v1.reference", "scripts", "tests"):
            module = types.ModuleType(name)
            module.__path__ = [str(private / name.replace(".", "/"))]
            sys.modules[name] = module
        try:
            reference = importlib.import_module("tests.frozen_support_rule_adapter_prototype")
            result = generate(reference, source)
            for path, expected in source.items():
                if (private / path).read_bytes() != expected:
                    raise ValueError("Private reference bytes changed during execution")
        finally:
            for name in tuple(sys.modules):
                if name.split(".")[0] in prefixes:
                    del sys.modules[name]
            sys.modules.update(previous)
    return (canonical(result) + "\n").encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-root", type=Path, required=True)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    raw = build(args.research_root)
    if args.write:
        DESTINATION.parent.mkdir(parents=True, exist_ok=True)
        output = io.BytesIO()
        with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0, compresslevel=9) as compressed:
            compressed.write(raw)
        DESTINATION.write_bytes(output.getvalue())
    elif gzip.decompress(DESTINATION.read_bytes()) != raw:
        raise ValueError("Frozen support fixture differs from the exact reference")
    counts = json.loads(raw)["counts"]
    print(f"{counts}; {len(PINS)} exact Research inputs; {len(raw)} canonical bytes; "
          f"SHA-256 {digest(raw)}; {DESTINATION.stat().st_size} gzip bytes; "
          f"SHA-256 {digest(DESTINATION.read_bytes())}")


if __name__ == "__main__":
    main()
