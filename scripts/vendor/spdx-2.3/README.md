# SPDX 2.3 schema attribution

`spdx-schema.json` and `LICENSE` are verbatim files from the
[SPDX specification project](https://github.com/spdx/spdx-spec), authored by the
SPDX specification contributors. They are distributed under
[Creative Commons Attribution 3.0 Unported](https://creativecommons.org/licenses/by/3.0/);
the complete license is retained in `LICENSE`. No endorsement of this Android
project by the SPDX project is implied.

The source is the SPDX `v2.3` tag's resolved commit
[`aadf3b0b8dbbabdb4d880b0fc714255fea436ff7`](https://github.com/spdx/spdx-spec/tree/aadf3b0b8dbbabdb4d880b0fc714255fea436ff7).
The annotated tag is `f7f7bce5511a23fe3c9d8a1edca0d870a7d0bea5`.
These object and checksum comparisons establish byte identity, not a publisher
signature or project license clearance.

| Upstream path | Git blob | SHA-256 | Bytes |
| --- | --- | --- | --- |
| [schemas/spdx-schema.json](https://github.com/spdx/spdx-spec/blob/aadf3b0b8dbbabdb4d880b0fc714255fea436ff7/schemas/spdx-schema.json) | `ee61e6686e885f8139c132647fd0b4f483b8fb81` | `239208b7ac287b3cf5d9a9af23f9d69863971102a5e1587a27a398b43490b89b` | 45,312 |
| [LICENSE](https://github.com/spdx/spdx-spec/blob/aadf3b0b8dbbabdb4d880b0fc714255fea436ff7/LICENSE) | `44a22d370bba8d13c7dd7449d71b40ea8842788e` | `017e38491cccbd2bdb6da0a32a33db9ec245b5dab30fdcd09f2c742c975e5b35` | 18,508 |

The schema uses JSON Schema Draft 7 and contains no external schema references.
`scripts/runtime_sbom.py` verifies its exact SHA-256 before local validation.
The schema's license is separate from SPDX's `CC0-1.0` document-data designation;
neither designation grants rights to redistribute the inventoried software.
