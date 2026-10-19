#!/usr/bin/env python3
"""Validate the reviewed Android intake register and explain blocked proposals.

This offline profile has no approved integration and no accepting promotion path.
It reads two bounded local JSON documents; it does not fetch or publish data.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from urllib.parse import urlsplit


DEFAULT_REGISTER = (
    Path(__file__).resolve().parents[1]
    / "docs/source-use/android-source-use-v1.json"
)
USES = (
    "automated_retrieval", "commercial_display", "offline_caching",
    "retention", "redistribution", "export",
)
MATERIALS = {
    "models-dev": "repository-compilation",
    "modelfit": "licensed-dataset",
    "llmfit": "repository-data",
    "openrouter": "service-information",
    "mcp-registry": "registry-metadata",
    "hugging-face": "public-factual-identifiers",
    "artificial-analysis": "site-and-platform-data",
    "benchlm": "owned-service-outputs",
    "harness-bench": "paper-v2",
    "gpus-io": "service-data",
}
RESEARCH_REVISION = "2dd26314de99f8e4c7f12d63215f95f1c10d1646"
RESEARCH_INPUTS = {
    "rights": (
        "research/market/rights-validation.md",
        "2fb0aa8d4c51448f111dbc82a8123206646f61c6", 24965,
        "83b031b721205d6f88d4d27b5bbc003045c524372aea7127b2e9627fae556873",
    ),
    "source_policy": (
        "research/T0-configuration/catalog-data-sources.md",
        "c862112312d45bbe13b9e9261b8f83ee581cafa6", 76248,
        "42dc339caf0eb4c77b480e1e1be3bf84bea9bf983b585878623d5d23cc311bb7",
    ),
    "integrations": (
        "research/market/integrations.json",
        "159766a48d7ec4460cbdc327dd5033d9f1b137a7", 36320,
        "2fd9692555a41518fc372a541923a0285766b74709a5e5d8f657d862ebd8b5ad",
    ),
}
RECEIPT = (
    "https://github.com/Pick-Your-AI/pick-your-ai-research/issues/60"
    "#issuecomment-6248616771"
)
RECORD_KEYS = {
    "id", "source_id", "label", "material_id", "material_scope",
    "excluded_materials", "primary_references", "inventory_observed_on",
    "material_review_observed_on", "research_record_sha256",
    "research_integration_status", "ingestion_path", "uses",
}
INGESTION_PATHS = {
    "conditional_source_basis", "manual_research_only",
    "separate_agreement_required", "excluded_for_product",
}


class InvalidInput(ValueError):
    """Contains a fixed public code, never input bytes or exception details."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise InvalidInput(code)


def exact_keys(value: object, keys: set[str], code: str) -> None:
    require(type(value) is dict and set(value) == keys, code)


def public_text(value: object, maximum: int = 2048) -> None:
    require(
        type(value) is str and 0 < len(value) <= maximum
        and all(ord(character) >= 32 and not 0xD800 <= ord(character) <= 0xDFFF
                for character in value),
        "invalid_register_text",
    )


def texts(value: object, maximum: int = 12) -> None:
    require(type(value) is list and 0 < len(value) <= maximum,
            "invalid_register_list")
    for item in value:
        public_text(item)


def iso_date(value: object) -> datetime.date:
    require(type(value) is str and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value),
            "invalid_observation_date")
    try:
        return datetime.date.fromisoformat(value)
    except ValueError:
        raise InvalidInput("invalid_observation_date") from None


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate_json_key")
        result[key] = value
    return result


def reject_constant(_: str) -> None:
    raise InvalidInput("nonfinite_json_number")


def read_json(path: Path, limit: int) -> tuple[bytes, object]:
    # Nonblocking open prevents an unexpected FIFO from hanging the offline gate.
    # Same-descriptor fstat and no-follow avoid a final-component symlink race.
    require(hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_NONBLOCK"),
            "unsupported_file_safety")
    descriptor = None
    try:
        descriptor = os.open(
            path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            | getattr(os, "O_CLOEXEC", 0),
        )
        before = os.fstat(descriptor)
        require(stat.S_ISREG(before.st_mode), "input_not_regular")
        require(0 < before.st_size <= limit, "input_size_limit")
        chunks = []
        total = 0
        while total <= limit:
            chunk = os.read(descriptor, min(65536, limit + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
        after = os.fstat(descriptor)
        require(total <= limit, "input_size_limit")
        require(
            total == before.st_size == after.st_size
            and before.st_mtime_ns == after.st_mtime_ns,
            "input_changed_during_read",
        )
        raw = b"".join(chunks)
    except OSError:
        raise InvalidInput("input_unreadable") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=unique_object, parse_constant=reject_constant,
        )
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise InvalidInput("invalid_json") from None
    except ValueError as error:
        if isinstance(error, InvalidInput):
            raise
        raise InvalidInput("invalid_json") from None
    return raw, value


def expected_disposition(record_id: str, use: str) -> str:
    """Keep v1's reviewed exclusions separate from raw historical yes/no cells."""
    if use in {"offline_caching", "retention"}:
        return "not_reviewed"
    if record_id in {"openrouter", "artificial-analysis", "benchlm", "gpus-io"}:
        return "excluded"
    if record_id == "hugging-face" and use in {"redistribution", "export"}:
        return "excluded"
    if record_id == "harness-bench" and use == "automated_retrieval":
        return "excluded"
    return "conditional_basis"


def validate_register(register: object) -> dict[str, dict]:
    exact_keys(register, {
        "profile", "register_version", "purpose", "research", "android_scope",
        "intended_uses", "promotion_profile", "records", "change_handling",
    }, "invalid_register_fields")
    require(register["profile"] == "android-source-use/v1"
            and type(register["register_version"]) is int
            and register["register_version"] == 1,
            "unsupported_register_version")
    require(register["promotion_profile"] == "no_approved_sources",
            "unsupported_promotion_profile")
    require(register["intended_uses"] == list(USES), "invalid_intended_uses")
    public_text(register["purpose"])
    texts(register["change_handling"])
    research = register["research"]
    exact_keys(research, {"repository", "revision", "inputs", "receiving_receipt"},
               "invalid_research_fields")
    require(research["repository"] == "Pick-Your-AI/pick-your-ai-research"
            and research["revision"] == RESEARCH_REVISION
            and research["receiving_receipt"] == RECEIPT,
            "unreviewed_research_pin")
    exact_keys(research["inputs"], set(RESEARCH_INPUTS), "invalid_research_inputs")
    for name, (path, blob, size, digest) in RESEARCH_INPUTS.items():
        item = research["inputs"][name]
        exact_keys(item, {"path", "git_blob", "bytes", "sha256"},
                   "invalid_research_input_fields")
        require(type(item["bytes"]) is int and item == {
            "path": path, "git_blob": blob, "bytes": size, "sha256": digest,
        }, "unreviewed_research_pin")
    scope = register["android_scope"]
    exact_keys(scope, {"issue", "accepted_revision", "record", "current_source"},
               "invalid_android_scope_fields")
    require(type(scope["issue"]) is int and scope["issue"] == 24
            and scope["accepted_revision"] == "93bebf787a091fe4858c1c70fd07cabefe17170f"
            and scope["record"] == "docs/delivery/android/work-packages/01.02.md"
            and scope["current_source"] == "554e1984e8923c5f098887a01f64be9e87ce3961",
            "unreviewed_android_scope")
    records = register["records"]
    require(type(records) is list and len(records) == len(MATERIALS),
            "invalid_record_inventory")
    indexed = {}
    for record in records:
        exact_keys(record, RECORD_KEYS, "invalid_record_fields")
        record_id = record["id"]
        require(type(record_id) is str and record_id in MATERIALS,
                "unknown_register_source")
        source_id = "src-" + record_id
        require(record["source_id"] == source_id and source_id not in indexed,
                "ambiguous_register_source")
        require(record["material_id"] == MATERIALS[record_id],
                "unreviewed_register_material")
        require(record["research_integration_status"] == "Not approved for integration",
                "unsupported_integration_status")
        require(type(record["ingestion_path"]) is str
                and record["ingestion_path"] in INGESTION_PATHS,
                "invalid_ingestion_path")
        for name in ("label", "material_scope"):
            public_text(record[name])
        for name in ("excluded_materials", "primary_references"):
            texts(record[name])
        for reference in record["primary_references"]:
            try:
                url = urlsplit(reference)
                require(url.scheme == "https" and bool(url.hostname)
                        and url.username is None and url.password is None
                        and url.port in {None, 443}, "invalid_primary_reference")
            except ValueError as error:
                if isinstance(error, InvalidInput):
                    raise
                raise InvalidInput("invalid_primary_reference") from None
        observed = iso_date(record["inventory_observed_on"])
        reviewed = iso_date(record["material_review_observed_on"])
        require(observed <= reviewed and reviewed == datetime.date(2026, 10, 18),
                "unreviewed_observation_date")
        require(type(record["research_record_sha256"]) is str
                and re.fullmatch(r"[0-9a-f]{64}", record["research_record_sha256"]),
                "invalid_research_record_pin")
        exact_keys(record["uses"], set(USES), "incomplete_use_matrix")
        for use in USES:
            decision = record["uses"][use]
            exact_keys(decision, {"disposition", "research_value", "basis", "conditions"},
                       "invalid_use_decision_fields")
            require(decision["disposition"] == expected_disposition(record_id, use),
                    "unreviewed_use_disposition")
            require(type(decision["research_value"]) is str
                    and decision["research_value"] in {
                        "yes", "no", "paper_only", "unrepresented_android_use",
                    }, "invalid_research_value")
            basis = decision["basis"]
            exact_keys(basis, {"input", "section", "record", "use"},
                       "invalid_use_basis_fields")
            require(type(basis["input"]) is str and basis["input"] in RESEARCH_INPUTS,
                    "unknown_research_basis")
            for name in ("section", "record", "use"):
                public_text(basis[name])
            texts(decision["conditions"])
        indexed[source_id] = record
    return indexed


def check_proposal(proposal: object, digest: str, records: dict[str, dict]) -> dict:
    exact_keys(proposal, {
        "profile", "register_sha256", "source_id", "material_id", "uses",
    }, "invalid_proposal_fields")
    require(proposal["profile"] == "android-source-promotion-proposal/v1",
            "unsupported_proposal_version")
    require(type(proposal["register_sha256"]) is str
            and re.fullmatch(r"[0-9a-f]{64}", proposal["register_sha256"]),
            "invalid_register_pin")
    for name in ("source_id", "material_id"):
        require(type(proposal[name]) is str
                and re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", proposal[name])
                and len(proposal[name]) <= 80, "invalid_proposal_identity")
    uses = proposal["uses"]
    require(type(uses) is list and 0 < len(uses) <= len(USES)
            and all(type(use) is str and use in USES for use in uses)
            and len(set(uses)) == len(uses), "invalid_proposal_uses")
    result = {
        "profile": "android-source-promotion-check/v1",
        "register_sha256": digest, "result": "blocked", "promoted": False,
        "reason_codes": [], "uses": [],
    }
    reasons = result["reason_codes"]
    if proposal["register_sha256"] != digest:
        reasons.append("stale_register")
        return result
    record = records.get(proposal["source_id"])
    if record is None:
        reasons.append("unknown_source")
        return result
    if record["material_id"] != proposal["material_id"]:
        reasons.append("unreviewed_material")
        return result
    reasons.append("integration_not_approved")
    reason_for = {
        "excluded": "use_excluded",
        "not_reviewed": "android_use_not_reviewed",
        "conditional_basis": "use_conditions_not_received",
    }
    for use in uses:
        disposition = record["uses"][use]["disposition"]
        result["uses"].append({
            "use": use, "disposition": disposition, "reason": reason_for[disposition],
        })
    return result


class SafeParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise InvalidInput("invalid_arguments")


def main(argv: list[str] | None = None) -> int:
    parser = SafeParser(description=__doc__)
    parser.add_argument("--register", type=Path, default=DEFAULT_REGISTER)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check-register", action="store_true")
    modes.add_argument("--proposal", type=Path)
    try:
        args = parser.parse_args(argv)
        raw, register = read_json(args.register, 256 * 1024)
        records = validate_register(register)
        digest = hashlib.sha256(raw).hexdigest()
        if args.check_register:
            result = {
                "profile": "android-source-register-check/v1",
                "register_sha256": digest, "result": "register_consistent",
                "promoted": False, "sources": len(records),
                "use_decisions": len(records) * len(USES),
            }
            exit_code = 0
        else:
            _, proposal = read_json(args.proposal, 8 * 1024)
            result = check_proposal(proposal, digest, records)
            exit_code = 1
    except InvalidInput as error:
        result = {"result": "invalid_input", "promoted": False, "reason": str(error)}
        exit_code = 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=True))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
