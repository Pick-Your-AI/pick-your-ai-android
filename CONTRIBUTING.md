# Contributing

Start from the existing fully qualified planning issue linked in the README. Read its acceptance criteria and decision-record path, reconcile prerequisites, confirm the live assignee and receiving reviewer, and inspect overlapping PRs. Do not infer implementation assignment from repository creation or research ownership.

Use a scoped branch, signed commits and a pull request. Obtain independent receiving review and satisfy repository rules before merge. Include the canonical issue, changed behavior or guidance, evidence, limitations and required handoffs. Do not close a work package solely because a PR merged; reconcile every acceptance criterion with receiving review and evidence.

Run `git diff --check`, check relative Markdown links, and manually review scope and canonical references. For core/contract changes, follow [CORE_FOUNDATION.md](docs/CORE_FOUNDATION.md): verify the frozen import, run the Python reference suite and `./gradlew --no-daemon :core:check :core:assemble`. Never claim installed Android or production validation from JVM, reference or documentation checks.

The [pinned secret-scanning check](docs/SECRET_SCANNING.md) runs against the exact
checkout and its reachable history. Review sanitized findings without copying
matched values into public comments; successful pattern detection is one security
check and does not establish dependency, credential-rotation or release acceptance.

Keep local build, simulator/emulator, physical device, store and production results distinct. Only perform release, provisioning, spending or outreach when the authority actually granted permits it. Read the organization [code of conduct](https://github.com/Pick-Your-AI/.github/blob/main/CODE_OF_CONDUCT.md).

For preview/toolchain changes, also run the opt-in unsigned Android build and lint
commands in [ANDROID_PREVIEW.md](docs/ANDROID_PREVIEW.md). Keep SDK/signing material
out of source; the preview creates no signing key and proves no installation.
