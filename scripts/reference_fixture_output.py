"""Write owned reference fixtures in a stable, trusted checkout.

Callers supply fixed repo-relative output names. Existing output ancestors and
destinations must not be aliases. A write replaces one regular file atomically;
this does not defend against concurrent replacement of checkout directories or
make separate checker invocations a transaction. Source-input admission remains
with each checker.
"""
from pathlib import Path
import stat
import tempfile


def output_path(root: Path, relative: Path) -> Path:
    if not relative.parts or relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Invalid generated output path')
    if not stat.S_ISDIR(root.lstat().st_mode):
        raise ValueError('Generated output root is not a directory')
    parent = root
    for part in relative.parts[:-1]:
        parent /= part
        try:
            mode = parent.lstat().st_mode
        except FileNotFoundError:
            break
        if not stat.S_ISDIR(mode):
            raise ValueError('Generated output ancestor is not a directory')
    target = root / relative
    try:
        info = target.lstat()
    except FileNotFoundError:
        return target
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise ValueError('Generated output is not a singly linked regular file')
    return target


def write_output(root: Path, relative: Path, content: str) -> None:
    target = output_path(root, relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    output_path(root, relative)
    temporary_path = None
    try:
        # Text mode retains the existing Path.write_text encoding/newline behavior.
        with tempfile.NamedTemporaryFile(mode='w', dir=target.parent,
                                         prefix=f'.{target.name}.', delete=False) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(content)
        temporary_path.chmod(0o644)
        temporary_path.replace(target)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
