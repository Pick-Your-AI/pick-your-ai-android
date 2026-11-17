# Python reference dependency integrity

The Python 3.12 reference and CI tools use the unchanged versions in
[`requirements-contracts.txt`](../requirements-contracts.txt). The corresponding
[`requirements-contracts.lock`](../requirements-contracts.lock) pins SHA-256
digests for the official, non-yanked wheels published for those exact versions.
The initial digest set was retrieved from each package's version-specific
`https://pypi.org/pypi/<name>/<version>/json` response on 2026-11-17.

Install in an isolated environment with:

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install --require-hashes --only-binary=:all: --force-reinstall -r requirements-contracts.lock
.venv/bin/python -m unittest discover -s scripts/tests -p 'test_python_dependency_lock.py' -v
```

Both CI dependency installs and the worktree setup use that lock. Every direct
and transitive package must have an exact version and matching artifact hash.
Forced reinstallation also verifies the selected wheels when the environment
already contains matching package versions; pip otherwise trusts installed bytes.
Binary-only installation excludes source distributions and their additional
build dependencies. A platform without a compatible listed wheel fails instead
of starting an unpinned source build. The full wheel set preserves the packages'
published platform choices; it does not claim that every host was tested.

The regression suite uses actual pip in a temporary directory with its index,
cache and user configuration disabled. It installs an original synthetic wheel,
then rejects a different wheel with the same package name/version and verifies
that the original installed bytes remain unchanged. It separately rejects a
transitive dependency without a hash. A separate temporary-venv control restores
locally altered installed bytes from the approved wheel, then rejects a substituted
wheel without removing the accepted installation. These offline controls demonstrate package
admission; they are not a vulnerability scan, publisher-authenticity proof,
license review or Android runtime result.

For an intentional dependency update, review the new exact version and its
official wheel metadata, then update the version list and lock together. Do not
add a hash from a failed download merely to make an installation pass. Review
unexpected wheel additions/removals and retain the new version-specific source
links in the PR. Run the lock controls, a clean hash-enforced installation and
the existing reference/CI suites before delivery. Hashes establish consistency
with the reviewed initial artifact set; they do not independently establish that
an upstream artifact is safe.

Historical acceptance records retain their original commands for their recorded
source; use these current instructions for the current checkout.

This is partial progress for [Android #69](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/69).
Release notices, monitored vulnerability intake, release-linked inventory and
distributable security-update acceptance retain their separate requirements.
