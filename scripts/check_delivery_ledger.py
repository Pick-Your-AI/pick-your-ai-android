#!/usr/bin/env python3
"""Validate ledger integrity; --issues additionally compares exact live issue criteria.

Read-only: never calls GitHub, changes issue state, or interprets checks as acceptance.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'Pick-Your-AI/pick-your-ai-android'
BASE = f'https://github.com/{REPOSITORY}'
EXTERNAL_KINDS = {
    'release_authority', 'device_evidence', 'owner_acceptance', 'shared_handoff',
    'source_rights', 'source_evidence', 'contract_acceptance', 'publisher_access',
    'user_evidence', 'signing_custody', 'operational_evidence', 'domain_control',
}

# Preserve the first reviewed documentary outcome and its exact receipts. The
# additional source-defined outcomes below do not authorize other completion.
DESIGN_23_REVISION = 'e86e8bd7645234e12b9887f1e6a0148a2d52073e'
DESIGN_23_PATH = 'docs/delivery/android/work-packages/14.01.md'
DESIGN_23_RECORD = {'repository': REPOSITORY, 'path': DESIGN_23_PATH}
DESIGN_23_ACCEPTANCE = {
    'scope': 'planned_data_inventory_and_mitigation_handoff',
    'evidence_ref': 'pr-279',
    'decision_revision': DESIGN_23_REVISION,
    'decision_url': f'{BASE}/blob/{DESIGN_23_REVISION}/docs/decisions/0001-android-23-acceptance-record.md',
    'record_url': f'{BASE}/blob/{DESIGN_23_REVISION}/{DESIGN_23_PATH}',
    'receiving_reviews': [
        f'{BASE}/pull/279#pullrequestreview-5562297375',
        f'{BASE}/pull/279#pullrequestreview-5562386507',
    ],
    'source_review_revision': '8162a5571147874635d3139e73a171a1077a6d2b',
    'historical_acceptance_record': {
        'repository': 'Pick-Your-AI/pickyour.ai', 'path': DESIGN_23_PATH,
    },
}
DESIGN_23_CRITERIA = [
    'df30567f84cf670a382658c134de05c36d1de247d71f1597f1f3410e399bc8f8',
    '4cc396aeec72b467fa8300a28f11638007a6644498982e90b94a57c13e0122ef',
    'f4804f53f5566bc5b20ec73034ac19bbe3870f1a06adc523ab8195c29f8b9c5f',
]
COMPLETE_DESIGN_RELATION = 'criterion_complete_reviewed_design_outcome'

REVIEWED_DESIGNS = {
    23: {
        'record': DESIGN_23_RECORD,
        'acceptance': DESIGN_23_ACCEPTANCE,
        'criteria': DESIGN_23_CRITERIA,
        'artifact_url': DESIGN_23_ACCEPTANCE['record_url'],
    },
}

# Signed #297 delivers only these three additional decision records. The shared
# evidence entry identifies their ADR; each outcome still has its own exact
# record, criterion hashes, scope and historical placement.
FOUNDATION_REVISION = '93bebf787a091fe4858c1c70fd07cabefe17170f'
FOUNDATION_DECISION = f'{BASE}/blob/{FOUNDATION_REVISION}/docs/decisions/0002-android-foundation-records.md'
FOUNDATION_REVIEWS = [
    f'{BASE}/pull/291#pullrequestreview-5562594206',
    f'{BASE}/pull/291#pullrequestreview-5562605533',
    f'{BASE}/pull/297#pullrequestreview-5562661670',
]


def foundation_design(path, scope, criterion_hashes):
    """Build a source-defined receipt; never derive acceptance from ledger input."""
    return {
        'record': {'repository': REPOSITORY, 'path': path},
        'acceptance': {
            'scope': scope,
            'evidence_ref': 'pr-297',
            'decision_revision': FOUNDATION_REVISION,
            'decision_url': FOUNDATION_DECISION,
            'record_url': f'{BASE}/blob/{FOUNDATION_REVISION}/{path}',
            'receiving_reviews': FOUNDATION_REVIEWS,
            'source_review_revision': '43ad25781210ad0f7578203626ac823d814cf385',
            'historical_acceptance_record': {
                'repository': 'Pick-Your-AI/pickyour.ai', 'path': path,
            },
        },
        'criteria': criterion_hashes,
        'artifact_url': FOUNDATION_DECISION,
    }


REVIEWED_DESIGNS.update({
    20: foundation_design(
        'docs/delivery/android/work-packages/01.01.md',
        'android_users_and_primary_journey_decision', [
            'dc58a23cb9732c5bf2556342f52250d9e099d913b4e27fe9004d6b7ca362f26c',
            'aa791f6ae7af9fd3a01b475257b79bff2a7463752b2b4a8e76ae241ed419442e',
            'd29ff8ea1323e5421fa999bd670d15316c9a4a1706f9fb479d263a6ca26c1f4c',
        ]),
    24: foundation_design(
        'docs/delivery/android/work-packages/01.02.md',
        'android_mvp_and_phased_capability_decision', [
            '835380a6476cb5a140a94eb5644e551ba49c5b7aeff435209589ab3ac904102d',
            'ae8d63e6b78fd37c8739d3343b3522688dfa4635b32593a04a91ba1a644e9b34',
            '25e9cb0571a398455fb474057f419c405d3dd6a378fbca238735d607f0c434e4',
        ]),
    34: foundation_design(
        'docs/delivery/android/work-packages/01.03.md',
        'android_native_repository_and_module_decision', [
            '5d2ef9ad52e7c85cebe2a491bae176f458428fc81c3bd1bbea55b6a868143af1',
            '3ef15900a9c57ffafc2f782a6ea1e38c6437941185cc738f1b95277017d8bdc8',
            'e7376b8e0a66f0149028e64b2d394763885599cf1bf943a1cfc5ea7df1e6c8c2',
        ]),
})


# Signed #322 delivers this applicability decision only. Account/service/runtime
# implementation and every other literal criterion retain their own acceptance.
DESIGN_25_REVISION = '68e52dd9c79cefe97af65e8fd1e86e4b72d2c42c'
DESIGN_25_PATH = 'docs/delivery/android/work-packages/01.05.md'
DESIGN_25_DECISION = f'{BASE}/blob/{DESIGN_25_REVISION}/docs/decisions/0003-android-25-acceptance-record.md'
DESIGN_25_ACCEPTANCE = {
    'scope': 'android_guest_accounts_revenue_and_optional_services_decision',
    'evidence_ref': 'pr-322',
    'decision_revision': DESIGN_25_REVISION,
    'decision_url': DESIGN_25_DECISION,
    'record_url': f'{BASE}/blob/{DESIGN_25_REVISION}/{DESIGN_25_PATH}',
    'receiving_reviews': [
        f'{BASE}/pull/313#pullrequestreview-5562991296',
        f'{BASE}/pull/313#pullrequestreview-5562993559',
        f'{BASE}/pull/322#pullrequestreview-5563048963',
    ],
    'source_review_revision': 'fb153a9a3be4b75e645fc231283632bb451ed171',
    'historical_acceptance_record': {
        'repository': 'Pick-Your-AI/pickyour.ai', 'path': DESIGN_25_PATH,
    },
}
REVIEWED_DESIGNS[25] = {
    'record': {'repository': REPOSITORY, 'path': DESIGN_25_PATH},
    'acceptance': DESIGN_25_ACCEPTANCE,
    'criteria': [
        '53c649d8164864a7f4f58e65d3284a8578d4ac711bde3e0850d601694908b188',
        'c8d1f07fa4680f4b3dfaedb5f4ad9256c7efafe56d7ac0ea724292e85410a54d',
        '84bf23c76dd6c46f1ce3ddb371fd3aff625a46494c2825714738452cf78aa329',
    ],
    'artifact_url': DESIGN_25_DECISION,
}


# Signed #318 delivers selected qualification requirements and review methods.
# Device, resource, source-rights, operating and release results remain separate.
DESIGN_47_REVISION = 'b6935a8ee7843831f90b0219c8794071557c71d4'
DESIGN_47_PATH = 'docs/delivery/android/work-packages/01.06.md'
DESIGN_47_DECISION = f'{BASE}/blob/{DESIGN_47_REVISION}/docs/decisions/0004-android-47-acceptance-record.md'
DESIGN_47_ACCEPTANCE = {
    'scope': 'android_acceptance_and_ownership_baseline_decision',
    'evidence_ref': 'pr-318',
    'decision_revision': DESIGN_47_REVISION,
    'decision_url': DESIGN_47_DECISION,
    'record_url': f'{BASE}/blob/{DESIGN_47_REVISION}/{DESIGN_47_PATH}',
    'baseline_url': f'{BASE}/blob/{DESIGN_47_REVISION}/docs/ACCEPTANCE_BASELINE.md',
    'receiving_reviews': [
        f'{BASE}/pull/318#pullrequestreview-5563013669',
        f'{BASE}/pull/318#pullrequestreview-5563018910',
        f'{BASE}/pull/318#pullrequestreview-5563074154',
        f'{BASE}/pull/318#pullrequestreview-5563081505',
    ],
    'source_review_revision': '8800b02a8068902773652b553d52749ce8a8e167',
    'historical_acceptance_record': {
        'repository': 'Pick-Your-AI/pickyour.ai', 'path': DESIGN_47_PATH,
    },
}
REVIEWED_DESIGNS[47] = {
    'record': {'repository': REPOSITORY, 'path': DESIGN_47_PATH},
    'acceptance': DESIGN_47_ACCEPTANCE,
    'criteria': [
        'a3a18d7099e6a93d467f427faa9c826c92dde14f7548a217cad5feb619afde8a',
        '3a3c7a2613998f2bf036cf6cb1bed70fbfc0bc09e46a943bad682f218d6f4ea6',
        '0b4be01e81cc371f7c8f6a8fc6245b0bde780d259700e11a23d8e97dd4a61b48',
    ],
    'artifact_url': DESIGN_47_DECISION,
}


# Signed #347 delivers the hosting and shared-service decision with its explicit
# open operating gate. It does not accept a live operator, endpoint or restore.
DESIGN_35_REVISION = '4c753bd69f3857d9b15051be86078948e61074ac'
DESIGN_35_PATH = 'docs/delivery/android/work-packages/01.04.md'
DESIGN_35_DECISION = f'{BASE}/blob/{DESIGN_35_REVISION}/docs/decisions/0005-android-35-acceptance-record.md'
DESIGN_35_ACCEPTANCE = {
    'scope': 'android_service_hosting_and_explicit_operating_gate_decision',
    'evidence_ref': 'pr-347',
    'decision_revision': DESIGN_35_REVISION,
    'decision_url': DESIGN_35_DECISION,
    'record_url': f'{BASE}/blob/{DESIGN_35_REVISION}/{DESIGN_35_PATH}',
    'baseline_url': f'{BASE}/blob/{DESIGN_35_REVISION}/docs/SERVICE_BASELINE.md',
    'receiving_reviews': [
        f'{BASE}/pull/344#pullrequestreview-5563320716',
        f'{BASE}/pull/344#pullrequestreview-5563316183',
        f'{BASE}/pull/351#pullrequestreview-5563388193',
        f'{BASE}/pull/347#pullrequestreview-5563464371',
    ],
    'source_review_revision': '0a04903a89203ac199617fe3051453e0c5a997a6',
    'historical_acceptance_record': {
        'repository': 'Pick-Your-AI/pickyour.ai', 'path': DESIGN_35_PATH,
    },
}
REVIEWED_DESIGNS[35] = {
    'record': {'repository': REPOSITORY, 'path': DESIGN_35_PATH},
    'acceptance': DESIGN_35_ACCEPTANCE,
    'criteria': [
        '2c4f0e64a78baf55954f341e6dbdcf2d4719512f928bb3d808e542116a771475',
        'aae1f0146dc5a4bfd7d9c2840990b7e9441b57f4a56efefa601fa085442e5a57',
        '69ca9177c58548856a51c8e1fda18e251452a17f00613cfed158f9e0699bb8fe',
    ],
    'artifact_url': DESIGN_35_DECISION,
}


# Version 7 receives only the already delivered #26 local-model implementation.
# These literals are reviewed source authority, never inferred from ledger input,
# checkbox state, an arbitrary approval flag or a successful check. Observation
# revisions and original artifact identities stay separate from the record delivery.
COMPLETE_IMPLEMENTATION_RELATION = 'criterion_complete_reviewed_implementation_outcome'
LEDGER_PURPOSE = 'Living evidence index: 21 reviewed documentary/design criteria for Android #20/#23/#24/#25/#34/#35/#47 and 3 reviewed local-model implementation criteria for #26 are complete; 417 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies.'
IMPLEMENTATION_26_EVIDENCE = {'pr-407': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/407',
            'observed_state': 'merged',
            'revision': '5cb0729889dd56f8e14af356f0547683d1c511a2',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/5cb0729889dd56f8e14af356f0547683d1c511a2/docs/LOCAL_SNAPSHOT_ARCHIVE.md',
            'scope_limit': 'Delivered local draft/projection/private archive with the ordinary-install '
                           'checkpoint integration correction. Actual JVM and simulated Android model '
                           'evidence; no installed-device, physical power-loss, shared-wire, source-rights '
                           'or release acceptance.'},
 'pr-418': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/418',
            'observed_state': 'merged',
            'revision': '785a9330f4260892ea28a9bc5045372b01ba6717',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785a9330f4260892ea28a9bc5045372b01ba6717/docs/delivery/android/work-packages/03.02.md',
            'scope_limit': 'Delivered #26 local-model criterion/receiving record and ADR 0007 only. The '
                           'distinct implementation acceptance consumes the separately pinned #407 model and '
                           'actual reviews; this documentation delivery alone implements no behavior and '
                           'changes no other issue.'}}
IMPLEMENTATION_26_ACCEPTANCE = {'scope': 'android_local_draft_projection_and_private_immutable_archive',
 'classification': 'reviewed_implementation_outcome',
 'evidence_refs': ['pr-407', 'pr-418'],
 'implementation_revision': '5cb0729889dd56f8e14af356f0547683d1c511a2',
 'implementation_tree': '8db4534ce21d177540e83510cd5f343af712a5db',
 'implementation_observation': {'execution_revision': '5cb0729889dd56f8e14af356f0547683d1c511a2',
                                'execution_tree': '8db4534ce21d177540e83510cd5f343af712a5db',
                                'runtime_scope': 'JVM filesystem and process tests; simulated Android; '
                                                 'unsigned build/lint',
                                'main_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39153908679',
                                'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39153908537',
                                'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/407#issuecomment-6253592295',
                                'core_cases': 2679,
                                'simulated_android_cases': 222,
                                'model_cases': 50,
                                'original_artifacts': [{'id': '12145339801',
                                                        'bytes': 910,
                                                        'sha256': 'a838e07d502d6d97020df57cdd51b8b47f8d19c337e706e44aa2f54109457e7f'},
                                                       {'id': '12146034260',
                                                        'bytes': 2911428,
                                                        'sha256': 'c8acf7d9985aa112945058cb681ebf6c6541dad5dd7397fc6ec4495c7c6287e5'},
                                                       {'id': '12146327399',
                                                        'bytes': 1012828,
                                                        'sha256': '156e3263a0c18008a77b94ee1c0cba0e1d5f8c820c0ee5a18e9b0ba32a1b8abc'},
                                                       {'id': '12146945389',
                                                        'bytes': 3598077,
                                                        'sha256': '756338ddb9c007201d6a8c1938397afc576639ca20389d10e29718e4e1a7c555'}],
                                'unsigned_apk': {'bytes': 2890733,
                                                 'sha256': '3bfc738aa8cce849eda17bd7f4409ef88e2c7637649eeb92a4821b21d1b06f7e'},
                                'outcome': 'passed_in_recorded_scope; no skipped required cases'},
 'record_revision': '785a9330f4260892ea28a9bc5045372b01ba6717',
 'record_tree': '02bd681dded3f1e21e5a3dbbe2a4d48a55ca6acf',
 'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785a9330f4260892ea28a9bc5045372b01ba6717/docs/delivery/android/work-packages/03.02.md',
 'record_blob': 'db60ec73b41df4f4be8f12af1d52f4833c65293b',
 'decision_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785a9330f4260892ea28a9bc5045372b01ba6717/docs/decisions/0007-android-26-acceptance-record.md',
 'decision_blob': '34c3248aeb9e942ff1ef339cba82ea5c87761fe1',
 'source_review_revision': '8133e29b9da5d0843ee6c682786ec8c9df0f91a2',
 'source_reviews': ['https://github.com/Pick-Your-AI/pick-your-ai-android/pull/414#pullrequestreview-5564414746',
                    'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/418#pullrequestreview-5564531065',
                    'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/418#pullrequestreview-5564567253'],
 'record_observation': {'execution_revision': '785a9330f4260892ea28a9bc5045372b01ba6717',
                        'execution_tree': '02bd681dded3f1e21e5a3dbbe2a4d48a55ca6acf',
                        'runtime_scope': 'JVM and simulated Android checks of the delivered documentation '
                                         'composition; packaged-policy controls',
                        'main_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39158315808',
                        'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39158315963',
                        'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/418#issuecomment-6254157043',
                        'core_cases': 2881,
                        'simulated_android_cases': 222,
                        'model_cases': 50,
                        'original_artifacts': [{'id': '12148360350',
                                                'bytes': 2922352,
                                                'sha256': 'b629ef778e811f5a08f81816cb2d497d6150a02807403c7e7c9c9a8baaf1a726'},
                                               {'id': '12148077055',
                                                'bytes': 3645297,
                                                'sha256': 'e6538acd67007fe5583534e1f48cbf499f5291e1fd1135909822b5c90d7cb2a7'},
                                               {'id': '12148036844',
                                                'bytes': 1058972,
                                                'sha256': 'fdbd3e83109115d42cefd49d1b56b3486883f417f1d362d788286cccb06185de'},
                                               {'id': '12147477324',
                                                'bytes': 910,
                                                'sha256': '406c3932c6136d3ef21e2ab4160ecdbdc01c39e4ec4f392319655aae8666465b'}],
                        'unsigned_apk': {'bytes': 2901849,
                                         'sha256': '49f13bcdef015dfea5a08848d53ba95e884a300f4bcf4abbe679c97623fac264'},
                        'outcome': 'passed_in_recorded_scope; no skipped required cases'},
 'receiving_reviews': [{'role': 'affected_iOS_and_local_model',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26#issuecomment-6253612135',
                        'reviewed_revision': '3a61d9c025c552838d4e9345a4dcdf7b63bee526',
                        'disposition': 'accept_three_local_model_criteria_and_four_subtasks_with_recorded_limits'},
                       {'role': 'central_contract_and_saved_build',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26#issuecomment-6253664529',
                        'reviewed_revision': '3a61d9c025c552838d4e9345a4dcdf7b63bee526',
                        'disposition': 'accept_explicit_frozen_projection_private_archive_and_narrow_placement_with_constraints'},
                       {'role': 'coordinating_affected_web_and_Android_model',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26#issuecomment-6253699903',
                        'reviewed_revision': '3a61d9c025c552838d4e9345a4dcdf7b63bee526',
                        'disposition': 'accept_three_local_model_criteria_four_subtasks_and_ADR_0007_with_recorded_limits'}],
 'receiving_attribution': 'Actual delegated in-session technical decisions; not absent named-assignee '
                          'replies, new native execution or operating commitments.',
 'consumed_prerequisites': [{'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21',
                             'scope': 'exact_available_output_intake_only',
                             'issue_state_at_receiving': 'open',
                             'whole_issue_completion': 'not_claimed',
                             'research_revision': '9769fcd9ea380b1211669b2753181c7782444178',
                             'frozen_package': 'ai-builder-contract-v1/1.0.0',
                             'import_manifest_sha256': 'ef6d5801eec10f3169e2bf84653e7026b33088e305a987991ec52faadcd7f554',
                             'upstream_sha256sums_sha256': '0587a133436268bc03684ad61ff29f3bd1a12eb83db8a497eabdbb8fd8803e15',
                             'pattern_revision': 'ee3f64f90b1da38bdbda1d4e8d5c561477881254',
                             'numeric_revision': '29e176c46ea613fe0098b12a786af771a36b1a38',
                             'numeric_tree': 'd23426108616e9c8108a681e634c38ec2a758261',
                             'numeric_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/326#issuecomment-6250961297',
                             'outputs': 'Unchanged frozen hashes/schemas, bounded strict reader, fixed '
                                        'offline validator, canonical kinds, exact decimal/numeric nodes, '
                                        'LF/search semantics and explicit failure/local-revision admission.'},
                            {'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34',
                             'scope': 'accepted_native_repository_and_canonical_local_store_boundary',
                             'revision': '93bebf787a091fe4858c1c70fd07cabefe17170f',
                             'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/93bebf787a091fe4858c1c70fd07cabefe17170f/docs/delivery/android/work-packages/01.03.md'}],
 'implemented_subtasks': ['Define draft state for incomplete selections, unanswered objectives and local '
                          'editing metadata.',
                          'Specify snapshot conversion with semantic cardinality, reference integrity, pins '
                          'and revision identity.',
                          'Define edit/undo/autosave without mutating a saved immutable revision.',
                          'Specify draft/snapshot migrations that retain input unsupported by a newer '
                          'version.'],
 'outcome_boundaries': ['Nullable/partial durable editing and local formats 1–5 remain separate from '
                        'unchanged frozen Build 1.0.0.',
                        'Explicit typed identity, foundation/cardinality/references and immutable pins are '
                        'required; free text and unknown inputs never fabricate facts.',
                        'Complete retained-only acknowledgment binds the exact durable source/review; '
                        'pending form values and stale authority cannot publish a snapshot.',
                        'Created is memory-only; Archived is a separate complete-bundle transaction with '
                        'source/archive CAS and explicit uncertain recovery.',
                        'The private archive retains the exact current envelope and represented typed '
                        'history; a legacy_source_hash does not promise every predecessor serialization.',
                        'Mathematical revision aliases reserve one Build identity; different complete bundle '
                        'bytes conflict, including retained unreferenced bundles.',
                        'Source edits, restore and deletion preserve independent archive bytes; managed '
                        'publication is disjoint from the whole library root.',
                        'Malformed, unsupported, missing, conflicting and uncertain states remain explicit, '
                        'without guessed repair or saved/compatible/ready claims.',
                        'No creation/removal UI, shared wire/service, evaluation replay, source-rights, '
                        'installed-device/physical power-loss, release or whole #21 acceptance is recorded.'],
 'historical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                  'path': 'docs/delivery/android/work-packages/03.02.md'},
 'preserved_prior_ledger': {'revision': '785a9330f4260892ea28a9bc5045372b01ba6717',
                            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785a9330f4260892ea28a9bc5045372b01ba6717/docs/delivery/criterion-evidence.json',
                            'blob': '3b09af77f17b72785ec8aaef9e8fb38caedf09c8',
                            'sha256': '28f2ecb0ff81d630cbfeca29b68d07b501719b5edcdac81e1aa3a4a9e54700f1',
                            'other_issue_records_sha256': 'b865df9204bf36788c3304750f21b6211b9eca048f1c4f818b5a4aee08afa338',
                            'earlier_evidence_sha256': '097b08ba3e346eda96f21010314321d22f6145c21169b27bc7b8034cfa9e6cf6'}}
REVIEWED_IMPLEMENTATIONS = {
    26: {
        'record': {'repository': 'Pick-Your-AI/pick-your-ai-android', 'path': 'docs/delivery/android/work-packages/03.02.md'},
        'acceptance': IMPLEMENTATION_26_ACCEPTANCE,
        'criteria': [
            '0d9fd65079fefafd62227d018cad0f7c47d9fc8466194a53e3f1c68848a0f5b3',
            '23301b03f1fafb6b051bdd4a9448f7afeb160e06ecb463aa9a7b22f2bb990552',
            'ddf9e739476fabb455cd8273cdff3b065cbafb4aebdfe340119c936f2c37e513',
        ],
    },
}

# Preserve the prior v6 snapshot at signed 785a, including all 128 other issue
# objects, 21 earlier evidence objects and the original #26 partial-history data.
# A subsequent amendment needs deliberate source review; a matching live capture
# does not grant completion, relocation or permission to rewrite old observations.
PRIOR_26_ISSUE_SHA256 = 'fd6d29a632f364c9c3897d72be29e946c27aeb55ca4c757d02bff9da231cdc33'
PRIOR_26_REMAINING = 'Review proposed draft history, then implement durable persistence and explicit valid-snapshot transition.'
OTHER_ISSUE_RECORDS_SHA256 = 'b865df9204bf36788c3304750f21b6211b9eca048f1c4f818b5a4aee08afa338'
EARLIER_EVIDENCE_SHA256 = '097b08ba3e346eda96f21010314321d22f6145c21169b27bc7b8034cfa9e6cf6'
PRESERVED_METADATA_KEYS = ('observed_on', 'repository', 'implementation_baseline', 'source')
PRESERVED_METADATA_SHA256 = '81a7ffc4531f20237aef4e61123b4fb3e6deaef42b88a595b3cb7e05280dde21'
# The 121 ordinary coordinates exclude the seven designs and only #26.
ORDINARY_RECORD_COORDINATES_SHA256 = '96798dfc85d041cda2435a5db192e260b0b54c7187f07e30733b1cb95773cbb9'


def json_digest(value):
    """Canonical JSON identity also distinguishes integers, floats and booleans."""
    encoded = json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)
    return hashlib.sha256(encoded.encode('utf-8')).hexdigest()


def read_json(path):
    """Read one unambiguous JSON value without accepting non-finite numbers."""
    def unique_members(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('duplicate JSON member')
            result[key] = value
        return result

    def reject_constant(_value):
        raise ValueError('non-finite JSON number')

    def finite_float(value):
        parsed = float(value)
        if not math.isfinite(parsed):
            raise ValueError('non-finite JSON number')
        return parsed

    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_members,
                      parse_constant=reject_constant, parse_float=finite_float)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def source_criteria(body):
    require('## Acceptance criteria' in body, 'missing source acceptance section')
    section = body.split('## Acceptance criteria', 1)[1].split('\n## ', 1)[0]
    return re.findall(r'^- \[[ xX]\] (.+)$', section, re.M)


def validate(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 7, 'unsupported schema version')
    require(ledger['repository'] == REPOSITORY, 'wrong repository')
    require(ledger['purpose'] == LEDGER_PURPOSE, 'unsupported ledger completion summary')
    evidence = ledger['evidence']
    require(bool(evidence), 'missing evidence catalog')
    for key, artifact in evidence.items():
        require(re.fullmatch(r'pr-[0-9]+', key) is not None, 'invalid evidence ID')
        require(artifact['pr_url'] == f'{BASE}/pull/{key[3:]}', 'invalid PR URL')
        require(artifact['observed_state'] in {'merged', 'open', 'draft'}, 'invalid PR state')
        require(re.fullmatch(r'[0-9a-f]{40}', artifact['revision']) is not None, 'unpinned artifact')
        require(artifact['artifact_url'].startswith(f"{BASE}/blob/{artifact['revision']}/"), 'artifact revision mismatch')
        require(bool(artifact['scope_limit'].strip()), 'missing evidence limitation')
    issues = ledger['issues']
    numbers = [issue['number'] for issue in issues]
    require(all(type(number) is int for number in numbers), 'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)), 'expected unique ordered issue numbers 1–129')
    require(sum(len(issue['criteria']) for issue in issues) == 441, 'expected 441 criteria')
    source_by_number = None
    if source is not None:
        require(isinstance(source, list), 'source must be a gh issue-list array')
        require(all(type(issue['number']) is int for issue in source), 'source issue numbers must be integers')
        source_by_number = {issue['number']: issue for issue in source}
        require(len(source) == len(source_by_number), 'duplicate source issue')
        require(set(source_by_number) == set(numbers), 'live issue inventory differs; reconcile explicitly')
    for issue in issues:
        number = issue['number']
        require(issue['url'] == f'{BASE}/issues/{number}', f'wrong issue URL: {number}')
        record = issue['canonical_acceptance_record']
        accepted_design = REVIEWED_DESIGNS.get(number)
        accepted_implementation = REVIEWED_IMPLEMENTATIONS.get(number)
        require(accepted_implementation or 'accepted_implementation_outcome' not in issue,
                f'unsupported implementation acceptance: {number}')
        if accepted_design:
            expected = accepted_design['acceptance']
            require(record == accepted_design['record'], f'unreviewed acceptance placement: {number}')
            require(issue.get('accepted_design_outcome') == expected,
                    f'missing or changed reviewed design acceptance: {number}')
            require([c['text_sha256'] for c in issue['criteria']] == accepted_design['criteria'],
                    f'reviewed design criterion set changed: {number}')
            receipt = evidence.get(expected['evidence_ref'], {})
            require(receipt.get('observed_state') == 'merged' and
                    receipt.get('revision') == expected['decision_revision'] and
                    receipt.get('artifact_url') == accepted_design['artifact_url'],
                    f'accepted design receipt is not the reviewed merged artifact: {number}')
        elif accepted_implementation:
            expected = accepted_implementation['acceptance']
            require('accepted_design_outcome' not in issue, f'unsupported design acceptance: {number}')
            require(record == accepted_implementation['record'], f'unreviewed acceptance placement: {number}')
            require(json_digest(issue.get('accepted_implementation_outcome')) == json_digest(expected),
                    f'missing or changed reviewed implementation acceptance: {number}')
            require([c['text_sha256'] for c in issue['criteria']] == accepted_implementation['criteria'],
                    f'reviewed implementation criterion set changed: {number}')
            for evidence_ref in expected['evidence_refs']:
                require(json_digest(evidence.get(evidence_ref)) == json_digest(IMPLEMENTATION_26_EVIDENCE[evidence_ref]),
                        f'implementation receipt is not the reviewed merged artifact: {number}')
        else:
            require('accepted_design_outcome' not in issue, f'unsupported design acceptance: {number}')
            require(record['repository'] == 'Pick-Your-AI/pickyour.ai', f'acceptance repository moved: {number}')
        require(re.fullmatch(r'docs/delivery/android/(program|epics/[0-9]{2}|work-packages/[0-9]{2}\.[0-9]{2})\.md', record['path']) is not None, f'invalid acceptance path: {number}')
        require(bool(issue['criteria']), f'empty issue: {number}')
        for index, criterion in enumerate(issue['criteria'], 1):
            key = f'{number}.{index}'
            require(criterion['id'] == key, f'criterion identity/order mismatch: {key}')
            text = criterion['text']
            require(isinstance(text, str) and bool(text.strip()), f'empty criterion: {key}')
            require(criterion['text_sha256'] == hashlib.sha256(text.encode()).hexdigest(), f'criterion text digest mismatch: {key}')
            if accepted_design:
                require(criterion['status'] == 'complete' and
                        criterion['completion_evidence'] == [accepted_design['acceptance']['evidence_ref']],
                        f'missing reviewed design completion evidence: {key}')
                require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                        f'completed criterion still has unfinished requirements: {key}')
                require(criterion['evidence_relation'] == COMPLETE_DESIGN_RELATION,
                        f'unsupported design completion attribution: {key}')
            elif accepted_implementation:
                require(criterion['status'] == 'complete' and
                        criterion['completion_evidence'] == accepted_implementation['acceptance']['evidence_refs'],
                        f'missing reviewed implementation completion evidence: {key}')
                require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                        f'completed criterion still has unfinished requirements: {key}')
                require(criterion['evidence_relation'] == COMPLETE_IMPLEMENTATION_RELATION,
                        f'unsupported implementation completion attribution: {key}')
            else:
                require(criterion['status'] == 'incomplete' and criterion['completion_evidence'] == [], f'unreviewed completion claim: {key}')
                require(bool(criterion['remaining_engineering'].strip()), f'missing remaining work: {key}')
                require(criterion['evidence_relation'] in {'issue_context_only; inspect artifact scope before attributing any criterion result', 'criterion_partial_results_with_issue_context'}, f'unsupported evidence attribution: {key}')
            refs = criterion['partial_evidence_refs']
            require(len(refs) == len(set(refs)) and set(refs) <= set(evidence), f'unknown/duplicate evidence reference: {key}')
            results = criterion.get('partial_results', [])
            require(bool(results) == (criterion['evidence_relation'] == 'criterion_partial_results_with_issue_context'), f'partial attribution without results: {key}')
            for result in results:
                require(bool(result['evidence_refs']) and set(result['evidence_refs']) <= set(refs), f'unlinked partial result: {key}')
                require(bool(result['description'].strip()) and bool(result['observation_basis'].strip()), f'missing partial result scope: {key}')
                require(bool(result['reproduction_commands']) and all(isinstance(cmd, str) and cmd.strip() for cmd in result['reproduction_commands']), f'missing reproduction command: {key}')
            for prerequisite in criterion['external_prerequisites']:
                require(prerequisite['kind'] in EXTERNAL_KINDS and bool(prerequisite['requirement'].strip()), f'invalid external prerequisite: {key}')
        if source_by_number is not None:
            original = source_by_number[number]
            require(issue['title'] == original['title'], f'live title changed: {number}')
            require([c['text'] for c in issue['criteria']] == source_criteria(original['body']), f'live criteria differ: {number}')
            paths = re.findall(r'`(docs/delivery/android/[^`\n]+\.md)`', original['body'])
            require(record['path'] in paths, f'live acceptance path differs: {number}')

    ordinary_coordinates = [
        {'number': issue['number'],
         'repository': issue['canonical_acceptance_record']['repository'],
         'path': issue['canonical_acceptance_record']['path']}
        for issue in issues if issue['number'] not in REVIEWED_DESIGNS
        and issue['number'] not in REVIEWED_IMPLEMENTATIONS
    ]
    coordinate_bytes = json.dumps(ordinary_coordinates, sort_keys=True,
                                  separators=(',', ':')).encode('utf-8')
    require(hashlib.sha256(coordinate_bytes).hexdigest() == ORDINARY_RECORD_COORDINATES_SHA256,
            'unreviewed acceptance coordinates; reconcile placement explicitly')

    require(json_digest([issue for issue in issues if issue['number'] != 26]) == OTHER_ISSUE_RECORDS_SHA256,
            'unreviewed change to another issue record; preserve prior v6 history')
    earlier_evidence = {key: value for key, value in evidence.items() if key not in IMPLEMENTATION_26_EVIDENCE}
    require(json_digest(earlier_evidence) == EARLIER_EVIDENCE_SHA256,
            'unreviewed change to earlier evidence; preserve prior v6 history')
    require(json_digest({key: ledger[key] for key in PRESERVED_METADATA_KEYS}) == PRESERVED_METADATA_SHA256,
            'unreviewed change to historical ledger metadata')
    # Undo only this reviewed amendment's allowed fields, then compare the entire
    # original issue object. This preserves pr-138 and every earlier local field.
    previous_model = dict(issues[25])
    previous_model.pop('accepted_implementation_outcome')
    previous_model['canonical_acceptance_record'] = IMPLEMENTATION_26_ACCEPTANCE['historical_acceptance_record']
    previous_model['criteria'] = [
        dict(criterion, status='incomplete', completion_evidence=[],
             remaining_engineering=PRIOR_26_REMAINING, external_prerequisites=[],
             evidence_relation='issue_context_only; inspect artifact scope before attributing any criterion result')
        for criterion in previous_model['criteria']
    ]
    require(json_digest(previous_model) == PRIOR_26_ISSUE_SHA256,
            'unreviewed change to prior #26 context or partial history')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ledger', type=Path, default=ROOT / 'docs/delivery/criterion-evidence.json')
    parser.add_argument('--issues', type=Path, help='fresh gh issue list --state all JSON for authoritative text/path comparison')
    args = parser.parse_args()
    try:
        source = read_json(args.issues) if args.issues else None
        validate(read_json(args.ledger), source)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Delivery ledger check failed: {error}', file=sys.stderr)
        return 1
    print('PASS: 129 unique issue URLs, 417 incomplete criteria, 21 reviewed design criteria, 3 reviewed implementation criteria, pinned evidence and canonical paths' +
          ('; exact source criteria matched.' if source is not None else '; live source comparison NOT performed.'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
