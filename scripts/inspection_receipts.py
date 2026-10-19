"""Publish the two fixed APK inspection receipts in a stable, trusted checkout."""
from __future__ import annotations

import json
from pathlib import Path
import stat
import tempfile


RECEIPT_NAMES = frozenset({"preview-policy.json", "environment-configuration.json"})


def receipt_destination(root: Path, name: str) -> Path:
    """Admit existing path entries without following output-directory aliases."""
    if name not in RECEIPT_NAMES:
        raise ValueError("INSPECTION_RECEIPT_NAME")
    if not stat.S_ISDIR(root.lstat().st_mode):
        raise ValueError("INSPECTION_RECEIPT_ROOT")
    for directory in (root / "build", root / "build/inventory"):
        try:
            entry = directory.lstat()
        except FileNotFoundError:
            break
        if not stat.S_ISDIR(entry.st_mode):
            raise ValueError("INSPECTION_RECEIPT_DIRECTORY")
    output = root / "build/inventory" / name
    try:
        entry = output.lstat()
    except FileNotFoundError:
        return output
    if not stat.S_ISREG(entry.st_mode) or entry.st_nlink != 1:
        raise ValueError("INSPECTION_RECEIPT_FILE")
    return output


def retire_receipt(root: Path, name: str) -> None:
    """An admitted stale receipt cannot represent a failed current inspection."""
    receipt_destination(root, name).unlink(missing_ok=True)


def publish_receipt(root: Path, name: str, receipt: dict) -> None:
    """Replace an admitted receipt only after its complete JSON has been written."""
    output = receipt_destination(root, name)
    output.parent.mkdir(parents=True, exist_ok=True)
    receipt_destination(root, name)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent,
                                         prefix=f".{name}.", delete=False) as temporary:
            temporary_path = Path(temporary.name)
            json.dump(receipt, temporary, indent=2, sort_keys=True)
            temporary.write("\n")
        receipt_destination(root, name)
        temporary_path.replace(output)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
