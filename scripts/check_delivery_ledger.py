#!/usr/bin/env python3
"""Validate ledger integrity; --issues additionally compares exact live issue criteria.

Read-only: never calls GitHub, changes issue state, or interprets checks as acceptance.
"""
import argparse
import copy
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


# Version 8 receives only the delivered #2 product-decision integration.
# These are fixed reviewed source receipts. Main CI observes the delivered tree;
# the D1–D8 desk demonstration remains a separately scoped decision outcome.
DESIGN_2_ACCEPTANCE = {'scope': 'android_integrated_product_scope_and_decision_outcome',
 'classification': 'reviewed_design_outcome',
 'evidence_ref': 'pr-412',
 'decision_revision': 'c0278410f5a8a90776df9de2f466103cba48f9f1',
 'decision_tree': '49fd5b522999f3042bfa5894320575b9ee96b144',
 'decision_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/c0278410f5a8a90776df9de2f466103cba48f9f1/docs/decisions/0006-android-2-acceptance-record.md',
 'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/c0278410f5a8a90776df9de2f466103cba48f9f1/docs/delivery/android/epics/01.md',
 'record': {'path': 'docs/delivery/android/epics/01.md',
            'blob': 'f2e1716e35354f12d116b8ee22844ab19eb4f219',
            'bytes': 24875,
            'sha256': '003fdebbdb81eb62bd8f707718ba83e52efdf0e765b6956022398940802fd7c3'},
 'decision': {'path': 'docs/decisions/0006-android-2-acceptance-record.md',
              'blob': '8d215f622e910adfa9720d4556213e21be9f8a8d',
              'bytes': 4208,
              'sha256': '634ee503eed6d25c8d55db306be34f90510a4c223e2ceaccabe3f8f76610985b'},
 'source_review_revision': 'a961c66e391c931e84ce8925eb78e42141abe18b',
 'receiving_reviews': [{'role': 'product_decision_authority_and_Android_technical_lead',
                        'identity': 'in_session_coordinating_agent_under_maintainer_delegation',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/404#pullrequestreview-5564615467',
                        'reviewed_revision': 'a961c66e391c931e84ce8925eb78e42141abe18b',
                        'disposition': 'accept_all_four_literal_decision_criteria_and_D1_to_D8_integration_with_recorded_limits'},
                       {'role': 'independent_Android_receiving_source_and_composition_review',
                        'identity': 'in_session_Android_agent_under_maintainer_delegation',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/412#pullrequestreview-5564841847',
                        'reviewed_revision': 'b59112e97d32f8af5bd873415dd39de4e8defa6f',
                        'disposition': 'accept_exact_nine_path_receiving_composition_including_four_unchanged_foundation_blobs'}],
 'decision_demonstration': {'source_baseline': '80f8c9f5a8e682874131a603002c44046583bbde',
                            'scope': 'completed_synthetic_desk_traversal_of_the_six_accepted_decisions',
                            'cases': ['D1', 'D2', 'D3', 'D4', 'D5', 'D6', 'D7', 'D8'],
                            'result': 'selected_local_outcomes_and_explicit_failure_recovery_conditional_authority_and_later_qualification_requirements',
                            'installed_or_physical_execution_claimed': False,
                            'production_service_or_operating_acceptance_claimed': False},
 'consumed_children': [{'issue': 20,
                        'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20',
                        'record_revision': '93bebf787a091fe4858c1c70fd07cabefe17170f',
                        'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/93bebf787a091fe4858c1c70fd07cabefe17170f/docs/delivery/android/work-packages/01.01.md',
                        'record_blob': 'ac8b72e51d03575634b7fab36821000ee0592777',
                        'accepted_design_evidence_ref': 'pr-297',
                        'issue_state_at_2026_10_18_2048_UTC': 'closed'},
                       {'issue': 24,
                        'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24',
                        'record_revision': '93bebf787a091fe4858c1c70fd07cabefe17170f',
                        'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/93bebf787a091fe4858c1c70fd07cabefe17170f/docs/delivery/android/work-packages/01.02.md',
                        'record_blob': '68df8f7e759dd416838bd43eed03cf9f98980fa4',
                        'accepted_design_evidence_ref': 'pr-297',
                        'issue_state_at_2026_10_18_2048_UTC': 'closed'},
                       {'issue': 34,
                        'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34',
                        'record_revision': '93bebf787a091fe4858c1c70fd07cabefe17170f',
                        'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/93bebf787a091fe4858c1c70fd07cabefe17170f/docs/delivery/android/work-packages/01.03.md',
                        'record_blob': '1c6486fd9a2e1497cb0645db05588ebb62239da4',
                        'accepted_design_evidence_ref': 'pr-297',
                        'issue_state_at_2026_10_18_2048_UTC': 'closed'},
                       {'issue': 35,
                        'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35',
                        'record_revision': '4c753bd69f3857d9b15051be86078948e61074ac',
                        'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/4c753bd69f3857d9b15051be86078948e61074ac/docs/delivery/android/work-packages/01.04.md',
                        'record_blob': 'cb3ba3e5d7892c90cd2507b0c18fa3f85a2b55c4',
                        'accepted_design_evidence_ref': 'pr-347',
                        'issue_state_at_2026_10_18_2048_UTC': 'closed'},
                       {'issue': 25,
                        'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/25',
                        'record_revision': '68e52dd9c79cefe97af65e8fd1e86e4b72d2c42c',
                        'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/68e52dd9c79cefe97af65e8fd1e86e4b72d2c42c/docs/delivery/android/work-packages/01.05.md',
                        'record_blob': '45a7653c92be9f26a4afd148826625436c4b6b40',
                        'accepted_design_evidence_ref': 'pr-322',
                        'issue_state_at_2026_10_18_2048_UTC': 'closed'},
                       {'issue': 47,
                        'issue_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47',
                        'record_revision': 'b6935a8ee7843831f90b0219c8794071557c71d4',
                        'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/b6935a8ee7843831f90b0219c8794071557c71d4/docs/delivery/android/work-packages/01.06.md',
                        'record_blob': '3748c6540f2dd431f38d52e3ecc272e846b07037',
                        'accepted_design_evidence_ref': 'pr-318',
                        'issue_state_at_2026_10_18_2048_UTC': 'closed'}],
 'accepted_handoff_scope': ['Six delivered Android decision children and their actual delegated receiving '
                            'reviews form one accepted input sequence.',
                            'Research #58 accepted MVP comparison and #66 bounded substitute observation '
                            'retain their pinned record and receiving limits.',
                            'Apple journey, platform and guest decisions and its affected-client service '
                            'review remain Apple decisions; no Android binary equivalence is inferred.',
                            'Website shared-service ownership and original frozen Research contract, '
                            'evidence, pricing, lifecycle and source-rights authority remain canonical.'],
 'record_observation': {'execution_revision': 'c0278410f5a8a90776df9de2f466103cba48f9f1',
                        'execution_tree': '49fd5b522999f3042bfa5894320575b9ee96b144',
                        'signature_verified': True,
                        'sole_preceding_main_parent': 'ced002ddb32ceaad2a2a2dcdf4fb438f2a1c0bc9',
                        'main_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39165208547',
                        'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39165208546',
                        'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/412#issuecomment-6255080145',
                        'runtime_scope': 'actual_default_main_JVM_and_simulated_Android_unsigned_build_and_packaged_policy_checks; '
                                         'separate_from_the_documented_desk_decision_demonstration',
                        'core_cases': 2933,
                        'simulated_android_cases': 265,
                        'required_report_programs': 17,
                        'policy_host_methods': 18,
                        'compiled_policy_controls': 12,
                        'scanner_reachable_commits': 167,
                        'original_artifacts': [{'kind': 'native',
                                                'id': 12150756217,
                                                'bytes': 2959884,
                                                'sha256': '6588ea77186ed4d897b378f1de83c91e3992f444902d88b2767cce0d9b22c498'},
                                               {'kind': 'runtime',
                                                'id': 12150039772,
                                                'bytes': 3704448,
                                                'sha256': '57e3da8410834f5ebcc1c46a09b980605e159d4f0a40e40ae87dc194c4178bab'},
                                               {'kind': 'core',
                                                'id': 12149823620,
                                                'bytes': 1105233,
                                                'sha256': '4fad4df795f2e2387f25081c56bce701f4c49007bf7375dddc696fc43eb77260'},
                                               {'kind': 'scanner',
                                                'id': 12149593973,
                                                'bytes': 941,
                                                'sha256': 'f806080a4e7a6c5589aa06d33b99bcc6a81160ca107545bc00fd1afb1155197f'}],
                        'unsigned_apk': {'bytes': 2931697,
                                         'sha256': '18e3483ff13736bcef1c493035454d5d5c74923ca25842ff77a99e247cfc92b6'},
                        'outcome': 'passed_in_recorded_scope; '
                                   'no_failed_errored_skipped_or_duplicate_required_case'},
 'outcome_boundaries': ['Decision preparation accountability and actual delegated review do not assign a '
                        'GitHub user, impersonate a human reviewer or create an ongoing operating '
                        'commitment.',
                        'D1–D8 demonstrate the product-decision integration; synthetic CI and the scenario '
                        'RAM input are not executed product journeys, model fit, compatibility or physical '
                        'witnesses.',
                        'Local guest planning and mandatory foundation/T6 remain selected; optional tiers '
                        'and conditional exchange retain explicit scope and unsupported-evidence outcomes.',
                        'Accounts, payments, telemetry, notifications, downloads, inference execution, '
                        'purchases and deployment are not activated by the foundation decision.',
                        'The shared service keeps its explicit operating gates; device, source-rights, '
                        'resource, accessibility and release qualification remain in their own work '
                        'packages.',
                        'No other issue criterion, dependency, Project value, assignment, frozen artifact or '
                        'cross-client authority is changed.'],
 'historical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                  'path': 'docs/delivery/android/epics/01.md'}}
DESIGN_2_EVIDENCE = {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/412',
 'observed_state': 'merged',
 'revision': 'c0278410f5a8a90776df9de2f466103cba48f9f1',
 'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/c0278410f5a8a90776df9de2f466103cba48f9f1/docs/decisions/0006-android-2-acceptance-record.md',
 'scope_limit': 'Delivered integrated Android product-decision record and ADR0006 within the nine-path '
                'guide/foundation/scanner receiver. Completion here is only the four #2 decision criteria; '
                'actual default-main JVM/simulated checks do not establish installed-device, live-service, '
                'operating, source-rights or release acceptance.'}
PRIOR_2_ISSUE_SHA256 = 'aba30d2b9450b5a6cf6bafa7cd974a89305f8ec894db0d0b4c70164b26835bb6'
PRIOR_2_REMAINING = ('Implement and validate this exact criterion in the selected scope; record negative/recovery cases and '
 'receiving evidence.')
PRIOR_2_EXTERNAL_PREREQUISITES = {'2.1': [{'kind': 'owner_acceptance',
          'requirement': 'Actual accountable owner and required receiving review for selected child outputs; '
                         'unassigned issues do not imply implementation ownership.'}],
 '2.2': [],
 '2.3': [],
 '2.4': [{'kind': 'shared_handoff',
          'requirement': 'Affected clients/research authorities must accept applicable shared outputs; no '
                         'unilateral contract changes.'}]}
REVIEWED_DESIGNS[2] = {
    'record': {'repository': REPOSITORY, 'path': 'docs/delivery/android/epics/01.md'},
    'acceptance': DESIGN_2_ACCEPTANCE,
    'criteria': ['e6668145b1a7b022783885f0f1a839f514b467a4516a10bfc6af36ff801042a6',
 '14997b25399709aa6dc2380fc29621dd8730a82e1c4f7def01f56e257b9bc7bf',
 '42fda215339d8d442cb1fef114233b2a6cce9867519f47c4642ca028c258db09',
 '2a08411645bf0ca56bf2edc5cd560342ef805b53c3853db5dca97b92db82df3e'],
    'artifact_url': DESIGN_2_ACCEPTANCE['decision_url'],
}


# Version 7 receives only the already delivered #26 local-model implementation.
# These literals are reviewed source authority, never inferred from ledger input,
# checkbox state, an arbitrary approval flag or a successful check. Observation
# revisions and original artifact identities stay separate from the record delivery.
COMPLETE_IMPLEMENTATION_RELATION = 'criterion_complete_reviewed_implementation_outcome'
LEDGER_PURPOSE = 'Living evidence index: 25 reviewed documentary/design criteria for Android #2/#20/#23/#24/#25/#34/#35/#47, 3 reviewed local-model implementation criteria for #26, the single reviewed import-integrity criterion 21.2 and 3 reviewed bounded support criteria for #33 are complete; 409 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies.'
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
# The save-policy correction supplements, without relabeling, the signed historical record.
IMPLEMENTATION_26_SAVE_POLICY_CORRECTION = {'source_revision': '945b6c51a6ac0096594a6605ac770b0dea12ee09',
 'source_tree': '108a28444775908626b8f56ad163d5948e9133cb',
 'source_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/commit/945b6c51a6ac0096594a6605ac770b0dea12ee09',
 'source_observed_state': 'unsigned_review_preparation',
 'record_path': 'docs/delivery/android/work-packages/03.02.md',
 'record_blob': 'e0d2d13f63cbdb361c8fc8ee517d7d2faa1c5dc8',
 'finding_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/418#issuecomment-6254224564',
 'disposition': 'accept_model_definition_without_claiming_an_implemented_autosave_scheduler',
 'current_preview_policy': 'explicit_guarded_Save_and_confirmed_domain_commands; '
                           'field_changes_and_Bundle_lifecycle_checkpoints_are_not_durable_autosave',
 'automatic_caller_contract': 'captured_complete_input_and_exact_current_source_authority; managed_liveness; '
                              'format_2_to_5_noop_and_declared_format_1_migration; append_only_edit_restore; '
                              'retain_pending_on_conflict_failure; '
                              'reconcile_uncertainty_before_retry_or_acknowledgment; '
                              'no_archived_write_authority',
 'scheduler_implementation': 'not_implemented_or_accepted',
 'remaining_interaction_issue': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/122',
 'remaining_interaction_scope': 'automatic_timing_queue_order_background_completion_retries_lifecycle_triggering_and_autosave_feedback_remain_separate; '
                                'no_scope_or_completion_change_to_122_or_storage_67',
 'historical_execution_relabelled': False,
 'new_native_execution_claimed': False,
 'receiving_reviews': [{'role': 'central_contract_and_saved_build',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26#issuecomment-6254828125',
                        'created_at': '2026-10-18T20:34:38Z',
                        'reviewed_revision': '945b6c51a6ac0096594a6605ac770b0dea12ee09',
                        'disposition': 'accept_bounded_model_policy_correction_and_exact_source_preservation_without_scheduler_or_new_execution'},
                       {'role': 'coordinating_affected_web_and_Android_model',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26#issuecomment-6254901761',
                        'created_at': '2026-10-18T20:41:47Z',
                        'reviewed_revision': '945b6c51a6ac0096594a6605ac770b0dea12ee09',
                        'disposition': 'accept_literal_model_definition_with_explicit_current_saves_and_scheduler_feedback_retained_under_122'}],
 'record_bytes': 30346,
 'record_sha256': 'aa65256ffb0968eefef449f702c0ac8022d316d946e93e0a605806c1045d9af5'}
IMPLEMENTATION_26_ACCEPTANCE['save_policy_correction'] = IMPLEMENTATION_26_SAVE_POLICY_CORRECTION

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
# The 120 ordinary coordinates exclude the eight designs and only #26.
ORDINARY_RECORD_COORDINATES_SHA256 = 'b7c0d2b1a30a75013cce5473a991cea4d47ce3732c3918530fdde1b888c2908a'



# One exact criterion may be accepted without promoting its issue or siblings.
COMPLETE_IMPORT_RELATION = 'criterion_complete_reviewed_import_integrity_outcome'
IMPORT_21_2_ID = '21.2'
IMPORT_21_2_EVIDENCE_REF = 'pr-430'
IMPORT_21_2_ACCEPTANCE = {'classification': 'reviewed_import_integrity_criterion',
 'criterion_id': '21.2',
 'criterion_text_sha256': 'd55fb9faa949130917f48f5a8e913cf87b55f26859ac1909f5ae712da5d055ba',
 'scope': 'exact_frozen_import_provenance_and_reproduced_integrity_only',
 'evidence_ref': 'pr-430',
 'source_review_revision': 'da3b095b51de209dba943f1bc0c478f9081de550',
 'source_review_tree': 'ceed506f4a03873486a02f185ddb3eb7efca2050',
 'receiving_reviews': [{'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/430#pullrequestreview-5565527306',
                        'reviewed_revision': 'da3b095b51de209dba943f1bc0c478f9081de550',
                        'role': 'coordinating_engineer_shared_schema_technical_receiver_under_express_delegation',
                        'disposition': 'accept_exact_literal_21.2_import_evidence_only',
                        'identity_scope': 'actual_in_session_shared_account_review_not_absent_assignee_or_separate_account_approval',
                        'state': 'COMMENTED',
                        'submitted_at': '2026-10-18T23:20:47Z'}],
 'upstream': {'repository': 'Pick-Your-AI/pick-your-ai-research',
              'accepted_pr': 44,
              'merge_commit': '9769fcd9ea380b1211669b2753181c7782444178',
              'root_tree': '6320941c0437b6f069272955afafe96b198817f3',
              'contract_subtree': 'b1c4eaa4037f2a1e02103849064a947e758a2b40',
              'contract_subtree_object_type': 'tree',
              'package': 'ai-builder-contract-v1',
              'contract_version': '1.0.0',
              'excluded_research_document': {'path': 'ADOPTION.md',
                                             'git_blob': 'ef8906b5d21fb28c67ece8c5eb9019d30dcd040b',
                                             'reason': 'Research receiving policy is documented by that '
                                                       'repository; Android records its own consumer '
                                                       'boundary.'}},
 'import_inventory': {'directory': 'contracts/v1',
                      'original_package_files': 43,
                      'pinned_metadata_files': 2,
                      'exact_import_leaves': 45,
                      'manifest_sha256': 'ef6d5801eec10f3169e2bf84653e7026b33088e305a987991ec52faadcd7f554',
                      'upstream_sums_sha256': '0587a133436268bc03684ad61ff29f3bd1a12eb83db8a497eabdbb8fd8803e15',
                      'complete_mapping_path': 'docs/evidence/frozen-import-21.2-20261018.json',
                      'archived_prompt_mapping': {'AGENTS.md': 'docs/IMPLEMENTATION_AGENTS.md',
                                                  'HANDOFF_PROMPT.md': 'docs/IMPLEMENTATION_HANDOFF.md'}},
 'import_execution': {'workflow_run': 39166674498,
                      'workflow_event': 'push',
                      'run_attempt': 1,
                      'core_job': 117790951872,
                      'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39166674498/job/117790951872',
                      'checkout': '40bd3d99dd7286f4b2fba0d6b17eb4e89b869657',
                      'positive_command': 'python scripts/check_contracts.py',
                      'verified_original_hashes': 43,
                      'verified_metadata_hashes': 2,
                      'unmodified_reference_cases': 21,
                      'reference_seconds': '0.672',
                      'negative_command': 'python -m unittest discover -s tests -v',
                      'negative_methods': ['test_modified_schema_fails_before_reference_execution',
                                           'test_modified_manifest_cannot_redefine_accepted_hashes',
                                           'test_missing_artifact_fails',
                                           'test_extra_schema_is_not_silently_bundled'],
                      'negative_seconds': '0.182',
                      'outcome': 'success',
                      'log_bytes': 39592,
                      'log_sha256': '82662a001afb0bbfc335ccbeb408fd26547290e654974fc523df5689f633e113',
                      'failure_semantics': 'Each negative invokes the real runner in an isolated copy, '
                                           'requires exit1 plus Contract integrity failure, and rejects '
                                           'evidence of the original21-case suite having executed.',
                      'evidence_audit': 'Original hosted decoded log and preserved exact source inspected; '
                                        'no project test rerun was performed for this document.'},
 'delivery': {'revision': '0765e3657a7d747b8f6f8f9a07e6117d3ff806f1',
              'tree': '4be11a32c5770e99d655a278d86511d7cfbf8d15',
              'parent': 'cbe6367175bd264463d397dc8a8705c84980ad8d',
              'signature_verified': True,
              'merged_at': '2026-10-19T00:54:46Z',
              'artifacts': [{'path': 'docs/CONTRACT_ADOPTION.md',
                             'mode': '100644',
                             'blob': 'fcddd79f200973d313693c10aecbcd4e54a48e22',
                             'bytes': 5003,
                             'sha256': 'c792c697919dad6a36b3178af0684d2e0a50b30ff0da95f17bb295e39ed84735',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/0765e3657a7d747b8f6f8f9a07e6117d3ff806f1/docs/CONTRACT_ADOPTION.md'},
                            {'path': 'docs/CONTRACT_IMPORT_EVIDENCE.md',
                             'mode': '100644',
                             'blob': '6c8ca9e99766eab7bbe4f09b7734d0c4187a6b99',
                             'bytes': 6566,
                             'sha256': 'ec28a9549c590b3118e1d36617f19a9bb310831fb7101324c60fda32fb3f0c7e',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/0765e3657a7d747b8f6f8f9a07e6117d3ff806f1/docs/CONTRACT_IMPORT_EVIDENCE.md'},
                            {'path': 'docs/CONTRACT_REVISION_HANDOFF.md',
                             'mode': '100644',
                             'blob': 'b2eda5319defc02a99179d97748e2fa8953e8f1f',
                             'bytes': 7046,
                             'sha256': '3a688e8e77b05ad79323e2421808b370557358229bbd3c1d05dad1c1817089a1',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/0765e3657a7d747b8f6f8f9a07e6117d3ff806f1/docs/CONTRACT_REVISION_HANDOFF.md'},
                            {'path': 'docs/evidence/frozen-import-21.2-20261018.json',
                             'mode': '100644',
                             'blob': '5794a15ae261e7fa94506fc8cf4705aea4b0ddb1',
                             'bytes': 20597,
                             'sha256': 'f3c69d25329f33cceb50978533213967d5124c64d36820e53c90407410bdce49',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/0765e3657a7d747b8f6f8f9a07e6117d3ff806f1/docs/evidence/frozen-import-21.2-20261018.json'}]},
 'default_verification': {'receipt_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/430#issuecomment-6257757921',
                          'receipt_created_at': '2026-10-19T01:32:43Z',
                          'primary': {'revision': '0765e3657a7d747b8f6f8f9a07e6117d3ff806f1',
                                      'tree': '4be11a32c5770e99d655a278d86511d7cfbf8d15',
                                      'event': 'push',
                                      'branch': 'main',
                                      'run': 39182899227,
                                      'overall_outcome': 'cancelled',
                                      'core_job': 117839252712,
                                      'core_outcome': 'success',
                                      'core_cases': 3994,
                                      'core_suites': 77,
                                      'preview_job': 117839253015,
                                      'preview_outcome': 'cancelled',
                                      'completed_preview_xml_reports': 0,
                                      'scanner_run': 39182899218,
                                      'scanner_job': 117839252717,
                                      'scanner_outcome': 'success',
                                      'scanner_named_controls': 17,
                                      'reachable_commits': 179,
                                      'core_artifact': {'id': 12156487938,
                                                        'bytes': 1245628,
                                                        'sha256': '167f67e18518a04df7ec934dcaf1bf9d9a80ca28de7f22e8770ddfe14a8ffd47'},
                                      'scanner_artifact': {'id': 12157016286,
                                                           'bytes': 943,
                                                           'sha256': '03bbc88720e398480c1890bd98476c7fae3188185716a50dab1eb81fea649619'}},
                          'following_documentation_only': {'revision': 'a6db68625bb3dc5003e163468e991cebb6635923',
                                                           'tree': '84c8f62c66f3bb665c8e6aa0435548e5a34ede93',
                                                           'parent': '0765e3657a7d747b8f6f8f9a07e6117d3ff806f1',
                                                           'source_change': 'four_CONTRIBUTING_lines_only_all_425_other_source_entries_unchanged',
                                                           'event': 'push',
                                                           'branch': 'main',
                                                           'run': 39183180872,
                                                           'overall_outcome': 'success',
                                                           'core_job': 117840368009,
                                                           'core_outcome': 'success',
                                                           'core_cases': 3994,
                                                           'core_suites': 77,
                                                           'preview_job': 117840368300,
                                                           'preview_outcome': 'success',
                                                           'preview_cases': 300,
                                                           'preview_suites': 25,
                                                           'scanner_run': 39183180932,
                                                           'scanner_job': 117840090421,
                                                           'scanner_outcome': 'success',
                                                           'scanner_named_controls': 17,
                                                           'reachable_commits': 180,
                                                           'core_artifact': {'id': 12157276774,
                                                                             'bytes': 1245475,
                                                                             'sha256': '7badaba5e3991a702dea1fafeb2d04f76036b7785ab693d52bb4c47817dfeed9'},
                                                           'preview_artifact': {'id': 12156758057,
                                                                                'bytes': 3020907,
                                                                                'sha256': 'bbf2f04b59715ce137175da06d9eee4c0f8f7ec63574e48bd4744cf0d270441a'},
                                                           'runtime_artifact': {'id': 12156952561,
                                                                                'bytes': 3823967,
                                                                                'sha256': '45d16a0ab3bd1144bcc0e09a072ff8e01dd3dd3b30dd9cc568ed81cdd37e42dc'},
                                                           'scanner_artifact': {'id': 12157031429,
                                                                                'bytes': 942,
                                                                                'sha256': 'db50594e836e9b9ad0e93be9d5e470cf9b1ce9bf032f88cfccde0adb6255da37'}},
                          'runtime_scope': 'original_reference_and_JVM_or_simulated_Android_receipts_not_installed_execution',
                          'cancelled_results_relabelled': False,
                          'new_execution_claimed_by_ledger': False},
 'canonical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                 'path': 'docs/delivery/android/work-packages/03.01.md'},
 'canonical_acceptance_record_relocated': False,
 'whole_issue_completion': False,
 'remaining_criteria': ['21.1', '21.3'],
 'outcome_boundaries': ['Only literal 21.2 import provenance and integrity reproduction are accepted.',
                        'Every other criterion, including 21.1, 21.3 and all of 32, 33, 48 and 49, retains '
                        'its prior status and evidence.',
                        'No network, persistence, user-import, generated-model, decimal-transport or '
                        'current-binding acceptance is inferred.',
                        'No successor contract adoption, installed device, recipient delivery, domain '
                        'evidence, production or release acceptance is inferred.',
                        'The canonical 03.01 website coordinate is unchanged; issue state, native '
                        'relationships, Projects and ownership are not modified by the checker.']}
IMPORT_21_2_EVIDENCE = {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/430',
 'observed_state': 'merged',
 'revision': '0765e3657a7d747b8f6f8f9a07e6117d3ff806f1',
 'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/0765e3657a7d747b8f6f8f9a07e6117d3ff806f1/docs/CONTRACT_IMPORT_EVIDENCE.md',
 'scope_limit': 'Exact delivered import supplement and actual literal 21.2 receiving review only. Original '
                '40bd import execution, 0765 cancelled overall/native with successful Core/scanner, and '
                'following documentation-only a6 successful default remain separately pinned. '
                'EvaluationReport source and broader 21.1/21.3, whole issue 21, 32/33/48/49, successor, '
                'installed, operating and release outcomes are not completed by this receipt.'}
PRIOR_21_ISSUE_SHA256 = 'eac8951ff123e43b22c2ac7bb20cac3eb8c7cef5697dd0d77d6d545de0a3e50d'
PRIOR_21_2_FIELDS = {'status': 'incomplete',
 'evidence_relation': 'issue_context_only; inspect artifact scope before attributing any criterion result',
 'remaining_engineering': 'Exercise frozen schemas and decimal semantics at each actual API and storage '
                          'boundary.',
 'external_prerequisites': [],
 'completion_evidence': []}
PRIOR_V8_PURPOSE = ('Living evidence index: 25 reviewed documentary/design criteria for Android '
 '#2/#20/#23/#24/#25/#34/#35/#47 and 3 reviewed local-model implementation criteria for #26 are complete; '
 '413 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native '
 'dependencies.')
PRIOR_V8_KEYS = ('schema_version',
 'observed_on',
 'repository',
 'implementation_baseline',
 'source',
 'purpose',
 'evidence',
 'issues')
PRIOR_V8_LEDGER_SHA256 = '5db8320d3f26f0ddf24e751e41489aaec344f997eb52d408a95449b4b6cabdae'


# Version 10 binds only the actually reviewed and delivered #33 support outcome.
# These fixed literals never infer acceptance from user-supplied status or role labels.
COMPLETE_SUPPORT_RELATION = 'criterion_complete_reviewed_bounded_support_outcome'
SUPPORT_33_NUMBER = 33
SUPPORT_33_EVIDENCE_REF = 'pr-494'
SUPPORT_33_ACCEPTANCE = {'classification': 'reviewed_bounded_support_outcome',
 'issue': 33,
 'scope': 'current_guest_local_preview_and_actual_controlled_in_session_public_intake',
 'criterion_ids': ['33.1', '33.2', '33.3'],
 'criterion_text_sha256': ['738db65159498b3ad4005a367a7f1f2eca13eccab76fb2800212d35e4ac887c8',
                           '7516937ea5a6fa0269e77f65538b2682c2ce6c7f77cf4202ed0e14b130071ead',
                           '9dddc416c7829a28224b85448ef3ec38ee7c67ad8e36eca72a569ca87211043d'],
 'evidence_ref': 'pr-494',
 'source_review_revision': 'eff1b2be9eba4e2e739910b9056f12e9a7b16f71',
 'source_review_tree': 'ff5f556a97f51e6b1cc206c4810f96b5a8c62e13',
 'source_review_signature': 'unsigned_review_preparation_not_signed_delivery',
 'receiving_reviews': [{'id': 5566090971,
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/494#pullrequestreview-5566090971',
                        'commit': 'eff1b2be9eba4e2e739910b9056f12e9a7b16f71',
                        'state': 'COMMENTED',
                        'submitted_at': '2026-10-19T01:32:16Z',
                        'body_bytes': 7675,
                        'body_sha256': '8da8e18c7475c419f415ce55f20037ce90100471a0a703cdab18f77000cc3ee8',
                        'role': 'support/operations',
                        'disposition': 'Actual current guest-local/in-session acceptance; no future duty '
                                       'appointment or installed/service/release claim.',
                        'identity_scope': 'actual_separate_in_session_reviewers_under_user_delegation_not_an_absent_assignee_or_separate_account_formal_approval'},
                       {'id': 5566084664,
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/494#pullrequestreview-5566084664',
                        'commit': 'eff1b2be9eba4e2e739910b9056f12e9a7b16f71',
                        'state': 'COMMENTED',
                        'submitted_at': '2026-10-19T01:30:41Z',
                        'body_bytes': 4494,
                        'body_sha256': '0b10fab6dc2f914a0bed93b71602af176d7d311db3363a884200a98a9d0f6eac',
                        'role': 'affected local-data/service',
                        'disposition': 'Actual current guest-local/in-session acceptance; no future duty '
                                       'appointment or installed/service/release claim.',
                        'identity_scope': 'actual_separate_in_session_reviewers_under_user_delegation_not_an_absent_assignee_or_separate_account_formal_approval'}],
 'canonical_acceptance_record': {'repository': 'Pick-Your-AI/pick-your-ai-android',
                                 'path': 'docs/delivery/android/work-packages/17.04.md'},
 'historical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                  'path': 'docs/delivery/android/work-packages/17.04.md'},
 'placement_decision': {'path': 'docs/decisions/0009-android-33-acceptance-record.md',
                        'revision': 'efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f',
                        'effective': 'reviewed_ordinary_signed_merge',
                        'only_issue': 33},
 'delivery': {'pr': 494,
              'revision': 'efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f',
              'tree': '45d80532ee9615d61b5cfdfc9007c2c71930112c',
              'parent': '1c51313d7499118a31b0d3a5af322aed5fa64a59',
              'signature_verified': True,
              'merged_at': '2026-10-19T04:40:30Z',
              'artifacts': [{'path': 'docs/LOCAL_DIAGNOSTICS.md',
                             'mode': '100644',
                             'blob': '570bf90c26b852317ad4d514bc97935592ce9b25',
                             'bytes': 6471,
                             'sha256': 'e46aef49c27d18adeabf82ca09672e2f34554cb0f4ca4929465ec95ca2c4f85b',
                             'original_review_blob': '570bf90c26b852317ad4d514bc97935592ce9b25',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f/docs/LOCAL_DIAGNOSTICS.md'},
                            {'path': 'docs/REPOSITORY_SCOPE.md',
                             'mode': '100644',
                             'blob': '592f20e90e39eb945c75e28e149a5080f2381f63',
                             'bytes': 9825,
                             'sha256': '67a807da703e159cb77ad5fd04134541184796de3809aa59ef1f9c22b14550f9',
                             'original_review_blob': '592f20e90e39eb945c75e28e149a5080f2381f63',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f/docs/REPOSITORY_SCOPE.md'},
                            {'path': 'docs/SUPPORT_HELP.md',
                             'mode': '100644',
                             'blob': '30e1135778340738a6d90c1519e3c6f16aa06355',
                             'bytes': 8176,
                             'sha256': 'e54f1a1f9a00e97e6ba51072104cf28648e1c1798fa7b4ed18191f7ad3de3897',
                             'original_review_blob': '774173ee112ed1c31a22e4515d3fb5904368f345',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f/docs/SUPPORT_HELP.md'},
                            {'path': 'docs/decisions/0009-android-33-acceptance-record.md',
                             'mode': '100644',
                             'blob': '3ac6cf909d8b774110a4156920853eef2e775291',
                             'bytes': 4309,
                             'sha256': 'e2bbfa558a0bb0fac0409a63719c9bcc47f3c35cfd07765cd171ba95c167ae64',
                             'original_review_blob': '3ac6cf909d8b774110a4156920853eef2e775291',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f/docs/decisions/0009-android-33-acceptance-record.md'},
                            {'path': 'docs/delivery/android/work-packages/17.04.md',
                             'mode': '100644',
                             'blob': '6e12067391e18b2cf62f943eb2fd5510360212f9',
                             'bytes': 14705,
                             'sha256': '5d095514628102527d1c84dcaec3831307dc669816ccd784ae6f2174654e33d2',
                             'original_review_blob': '6e12067391e18b2cf62f943eb2fd5510360212f9',
                             'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f/docs/delivery/android/work-packages/17.04.md'}],
              'support_help_composition': 'Only the already accepted515 catalog-constructor paragraph is '
                                          'added to original reviewed494 SUPPORT_HELP; complete inverse '
                                          'proofs preserve both sides. The other four reviewed documents '
                                          'are byte-identical.',
              'receipt': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/494#issuecomment-6260058131',
                          'id': 6260058131,
                          'created_at': '2026-10-19T05:02:38Z',
                          'body_bytes': 2913,
                          'body_sha256': '34f793711dfdb01d9985df889e0d4cb00d684bd614b886b3f48cff0ee24d0e0d'}},
 'implemented_subtasks': ['Define in-app and public help, bug-report and evidence-correction routes with '
                          'clear ownership and triage categories.',
                          'Implement a user-reviewable diagnostics package that redacts secrets/private '
                          'content and requires an explicit user send action.',
                          'Document support reproduction, escalation, evidence review and handling of '
                          'deletion/security requests.',
                          'Verify route delivery with approved test submissions and record an accurate '
                          'fallback when any channel is unavailable.'],
 'validation_methods': ['Submit an authorized synthetic support request and verify receipt and triage '
                        'without contacting real customers.',
                        'Generate diagnostics from seeded private records, inspect redaction preview and '
                        'confirm no network upload occurs before an explicit send action.'],
 'prerequisite': {'issue': 23,
                  'record_path': 'docs/delivery/android/work-packages/14.01.md',
                  'receiving_review_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/292#pullrequestreview-5562642177',
                  'observed_state': 'closed_completed',
                  'consumed_design_boundaries': ['F10/T08', 'F11/T07', 'B3/B7'],
                  'design_is_not_runtime_execution': True},
 'controlled_intake': {'reviewed_draft_bytes': 4751,
                       'reviewed_draft_sha256': '89f95619917f427f5c63762fe7b457db273e2671b5b0f2ab71cceab7fa87ae96',
                       'accepted_help_source': '710d337fed2004689993c0b8bc01f12de780b04c',
                       'ownership_notice_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/458#issuecomment-6257103121',
                       'combined_browser_receipt_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/33#issuecomment-6257323459',
                       'browser_submission_owner': 'root_coordinator',
                       'independent_receiving_owner': 'android_reviewer_in_session_CompleteDotTech_support_responsibility',
                       'explicit_browser_submissions_per_route': 1,
                       'connector_creation_substituted': False,
                       'private_data_or_attachments': False,
                       'installed_app_or_android_api_observed': False,
                       'routes': [{'kind': 'app-bug',
                                   'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/new?template=android-bug.yml',
                                   'issue': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/481',
                                   'number': 481,
                                   'created_at': '2026-10-19T00:32:29Z',
                                   'title': '[Android bug]: Controlled synthetic intake check for #33 — no '
                                            'product defect',
                                   'original_body_characters': 2181,
                                   'original_body_bytes': 2181,
                                   'original_body_sha256': '21e02dc0ec3532550e9dc8c143092c9045882c37e9336e17bb88a730f5e93fac',
                                   'all_seven_sections_exact': True,
                                   'triage': {'id': 6257197299,
                                              'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/481#issuecomment-6257197299',
                                              'created_at': '2026-10-19T00:38:46Z',
                                              'category': 'controlled Help/public-intake acceptance check',
                                              'disposition': 'received intact, no application defect or '
                                                             'code fix',
                                              'body_bytes': 1674,
                                              'body_sha256': 'c447c35ef9d055d28ef71621f8e51d8c508744df39db863db2b7fd860c058493'},
                                   'closed_at': '2026-10-19T00:39:13Z',
                                   'state': 'closed',
                                   'state_reason': 'completed',
                                   'diagnostic_field': 'blank_rendered_no_response'},
                                  {'kind': 'evidence-correction',
                                   'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/new?template=android-evidence-correction.yml',
                                   'issue': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/482',
                                   'number': 482,
                                   'created_at': '2026-10-19T00:34:50Z',
                                   'title': '[Android evidence correction]: Controlled synthetic '
                                            'uncertainty intake for #33',
                                   'original_body_characters': 2553,
                                   'original_body_bytes': 2553,
                                   'original_body_sha256': 'ba0990758edd3f97d24068a8d3701aa12a1594353382f03b087fac3047a71340',
                                   'all_seven_sections_exact': True,
                                   'triage': {'id': 6257199698,
                                              'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/482#issuecomment-6257199698',
                                              'created_at': '2026-10-19T00:39:00Z',
                                              'category': 'controlled evidence-correction intake, '
                                                          'synthetic uncertainty/presentation',
                                              'disposition': 'retain unknown; reject unsupported '
                                                             'compatibility promotion; no '
                                                             'source/price/rights rewrite or upstream '
                                                             'submission',
                                              'body_bytes': 2191,
                                              'body_sha256': '672aa713c72407e87407ecf1b1da01ed7e54efa44a884139973de7590a823989'},
                                   'closed_at': '2026-10-19T00:39:30Z',
                                   'state': 'closed',
                                   'state_reason': 'completed',
                                   'submitter_link_clarification': {'updated_at': '2026-10-19T00:37:21Z',
                                                                    'body_characters': 2848,
                                                                    'body_bytes': 2848,
                                                                    'body_sha256': '4b6ca3b5bc42a3984d1e0e512faa74a51e9f67fbda1c91065551cad65abb3eb1',
                                                                    'one_substitution_and_inverse_exact': True,
                                                                    'scope': "Submitter's bare Research "
                                                                             'references expanded to exact '
                                                                             'cross-repository Markdown '
                                                                             'links; not an app defect',
                                                                    'canonical_authorities': ['https://github.com/Pick-Your-AI/pick-your-ai-research/issues/41',
                                                                                              'https://github.com/Pick-Your-AI/pick-your-ai-research/issues/39',
                                                                                              'https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40',
                                                                                              'https://github.com/Pick-Your-AI/pick-your-ai-research/issues/60']}}],
                       'separate_api_history': [478, 479],
                       'separate_api_receipt_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/33#issuecomment-6257219390',
                       'preparation_history': ['Initial immediate view remained the composer; a subsequent '
                                               'read observed481 without another submission.',
                                               'One preparation call failed before executing. Documented '
                                               'recovery and fresh unchanged-form read preceded the one '
                                               'successful explicit submit; original stop is not '
                                               'relabeled.'],
                       'continuing_monitoring_or_sla_claimed': False,
                       'real_domain_record_changed': False},
 'diagnostic_and_help_boundary': {'finite_state_inputs': 2,
                                  'compiled_preview_fields': 10,
                                  'raw_private_input_accepted': False,
                                  'automatic_upload': False,
                                  'automatic_clipboard_write': False,
                                  'reviewed_text_equals_explicit_chooser_text': True,
                                  'seeded_saved_and_pending_private_content_excluded': True,
                                  'durable_file_hashes_preserved': True,
                                  'fresh_current_visible_resumed_one_use_consent_required': True,
                                  'unavailable_routes': ['direct_in_app_support_submission',
                                                         'confidential_private_reporting'],
                                  'missing_or_forbidden_handler': 'safe_destination_or_failure_is_retained_and_fresh_review_is_required_no_fallback_upload',
                                  'browser_launch_is_not_submission': True,
                                  'chooser_launch_is_not_recipient_receipt': True,
                                  'historical_help_revision': '2f1abddcc49d24ac4df9bceca59d65a59ae6ccb0',
                                  'historical_help_default_receipt_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/458#issuecomment-6256845394',
                                  'historical_core_cases': 3636,
                                  'historical_core_suites': 76,
                                  'historical_preview_cases': 292,
                                  'historical_preview_suites': 23,
                                  'original_eight_help_cases': ['localHelpAndActualBrowserIntentExcludeSavedAndPendingPrivateContent',
                                                                'eachPublicDestinationRequiresReviewAndOnlyOneExplicitFixedUriDispatch',
                                                                'cancelledDismissedAndReplacedHelpAndDestinationCallbacksCannotNavigate',
                                                                'missingOrForbiddenBrowserRetainsSafeDestinationAndNeedsFreshReview',
                                                                'helpDiagnosticsReusesTheExistingPreviewWithoutSharingOrRetainedNavigation',
                                                                'pauseAndRecreationRetireBothHelpReviewLevelsAndOldEntryButtons',
                                                                'closedHelpOwnerCannotOpenDiagnosticsOrDispatchRetainedPublicControls',
                                                                'narrowRtlLargeTextHelpAndDestinationKeepCompleteReachableActions'],
                                  'original_eight_diagnostic_cases': ['everyFiniteReportStateKeepsOnlyAllowlistedNonidentifyingFields',
                                                                      'actualReviewExcludesSeededPrivateDataAndSharesOnlyTheExactPreviewAfterConsent',
                                                                      'cancelledDismissedAndReplacedReportsCannotDispatchRetainedControls',
                                                                      'reportSnapshotStaysImmutableAndEveryNewShareRequiresFreshConsent',
                                                                      'unavailableChooserKeepsSafeFailureAndRequiresAnotherReviewedAttempt',
                                                                      'recreationAndClosedOwnersRetireReviewAndShareCallbacks',
                                                                      'pausedActivityRetiresReportAndCannotReviewUntilResumed',
                                                                      'narrowRtlLargeTextReportKeepsCompleteTextAndReachableShareControl'],
                                  'later_accepted_catalog_identity_cases': ['helpShowsTheActualLoadedBundledIdentityWithoutExportingIt',
                                                                            'differentAndUnavailableCatalogsNeverBorrowBundledLabelsOrPrivateMetadata',
                                                                            'helpCapturesLoadingOnceAndOnlyFreshHelpShowsTheCompletedCatalog'],
                                  'later_cases_not_relabelled_as_original_eight': True,
                                  'runtime_scope': 'source_and_original_simulated_Android_test_evidence_not_installed_browser_or_recipient_delivery'},
 'default_verification': {'primary': {'revision': 'efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f',
                                      'tree': '45d80532ee9615d61b5cfdfc9007c2c71930112c',
                                      'event': 'push',
                                      'branch': 'main',
                                      'run': 39200654761,
                                      'run_attempt': 1,
                                      'overall_outcome': 'cancelled',
                                      'core_job': 117892750220,
                                      'core_outcome': 'success',
                                      'core_cases': 4071,
                                      'core_suites': 85,
                                      'preview_job': 117892750488,
                                      'preview_outcome': 'cancelled',
                                      'available_passing_preview_cases': 356,
                                      'available_preview_xml_suites': 31,
                                      'completed_preview_qualification': False,
                                      'missing_preview_qualification': ['lint completion and report',
                                                                        'all27 hosted attendance guards',
                                                                        'ignored packaged-input guard',
                                                                        'runtime SPDX export',
                                                                        'packaged manifest/policy '
                                                                        'inspection and fixture guards'],
                                      'scanner_run': 39200654755,
                                      'scanner_job': 117892750218,
                                      'scanner_outcome': 'success',
                                      'scanner_named_controls': 17,
                                      'reachable_commits': 189,
                                      'artifacts': [{'id': 12164225293,
                                                     'bytes': 1327091,
                                                     'sha256': '64e9983efa549614200e41349165a10f4b0766708a21f61691b1d0b3f8738e9f'},
                                                    {'id': 12163882077,
                                                     'bytes': 3068657,
                                                     'sha256': '825d33bb12aa886b0673b21d3f03a593757a2ad7ac0b9d52c9b9b14270c6a8d2'},
                                                    {'id': 12163489297,
                                                     'bytes': 3924683,
                                                     'sha256': '4e6317dc76d1f08c47bfb20ddc5293ba58af69da0e9efb3d1128ad749e26dcc8'},
                                                    {'id': 12163935535,
                                                     'bytes': 945,
                                                     'sha256': '22c199775853d7914891b4dbfe2e78d6b5da00e259da34c7d633ce722af63bc0'}]},
                          'following_preserved_support_source': {'revision': '9729e18df3b439e5abec424b097bc9d8016274e6',
                                                                 'tree': '86ba64f126bd55fd507a9acf5f8de8ecc290fe3e',
                                                                 'parent': 'efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f',
                                                                 'signature_verified': True,
                                                                 'source_change': 'three_reviewed_projection_version_diagnostic_paths_all_five_support_documents_exact',
                                                                 'event': 'push',
                                                                 'branch': 'main',
                                                                 'run': 39201004245,
                                                                 'run_attempt': 1,
                                                                 'overall_outcome': 'success',
                                                                 'core_job': 117894097677,
                                                                 'core_outcome': 'success',
                                                                 'core_cases': 4153,
                                                                 'core_suites': 86,
                                                                 'preview_job': 117894097477,
                                                                 'preview_outcome': 'success',
                                                                 'preview_cases': 356,
                                                                 'preview_suites': 31,
                                                                 'preview_steps_success': 45,
                                                                 'preview_attendance_programs': 27,
                                                                 'core_source_pins': 8,
                                                                 'preview_source_pins': 10,
                                                                 'core_dependency_hashes': 21,
                                                                 'preview_dependency_hashes': 42,
                                                                 'conformance': {'tests': 260,
                                                                                 'wire_observations': 223,
                                                                                 'migration_observations': 34},
                                                                 'core_spdx': {'packages': 23,
                                                                               'relationships': 44},
                                                                 'preview_spdx': {'bytes': 85346,
                                                                                  'sha256': '6f07503f90f2cca5eb4bb3563eaae89eae5e02fe74f51a4a7c770c19c86e9979',
                                                                                  'packages': 48,
                                                                                  'relationships': 91},
                                                                 'lint_warnings': 18,
                                                                 'lint_errors': 0,
                                                                 'compiled_policy_controls': 12,
                                                                 'host_policy_controls': 18,
                                                                 'scanner_run': 39201004196,
                                                                 'scanner_job': 117893774398,
                                                                 'scanner_outcome': 'success',
                                                                 'scanner_named_controls': 17,
                                                                 'reachable_commits': 190,
                                                                 'scanner_findings': 0,
                                                                 'artifacts': [{'id': 12163618493,
                                                                                'bytes': 1333626,
                                                                                'sha256': '4c04b02429065cc90a9d4f9e3183edb3a11c4270bb6b5e263680f78d5f9eda98'},
                                                                               {'id': 12164515225,
                                                                                'bytes': 3092029,
                                                                                'sha256': '1882fac4e7167a4893695377c79404bcc6eeb8717d9992067b422a1e15d93f1d'},
                                                                               {'id': 12163892771,
                                                                                'bytes': 3937444,
                                                                                'sha256': 'a5dce264a506f9d94b07802f12f01da10211a8bc63bf99812db83ad270ac09a1'},
                                                                               {'id': 12163228356,
                                                                                'bytes': 944,
                                                                                'sha256': '558dd5fcfc095ec211062b446a41ff0cb87cbf050f5234029a864ba47bdf799b'}],
                                                                 'receipt': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/509#issuecomment-6260050638',
                                                                             'id': 6260050638,
                                                                             'created_at': '2026-10-19T05:02:02Z',
                                                                             'body_bytes': 4813,
                                                                             'body_sha256': '85d49db9a808152fa975997afd795ee097fc04f042bd5864bfab88e396900985'}},
                          'cancelled_results_relabelled': False,
                          'new_execution_claimed_by_ledger': False,
                          'audit_scope': 'original_artifact_and_source_inspection_with_only_XML_attendance_and_pure_inventory_SPDX_mapping_replay_no_product_run'},
 'outcome_boundaries': ['Only the three literal #33 current guest-local/in-session support outcomes are '
                        'received.',
                        'Public route delivery means the two actual browser submissions and separate '
                        'attributable triage; no future rota, monitoring hours or SLA is promised.',
                        'Private reporting and direct in-app submission stay unavailable; successful '
                        'chooser dispatch is not recipient delivery.',
                        'The synthetic evidence request retains unknown and supplies no source, price, '
                        'rights, contract or compatibility evidence.',
                        'No installed-device, physical accessibility, shared-service operation, production '
                        'or release outcome is completed.',
                        'All prior receipts including literal21.2 and every other criterion remain '
                        'unchanged; 21.1,21.3 and whole21/32/48/49 keep their own evidence and receiving '
                        'scope.',
                        'Ledger validation changes no live issue, assignee, native relationship or Project '
                        'field; ordinary reviewed ledger delivery and exact live reconciliation remain '
                        'separate.']}
SUPPORT_33_EVIDENCE = {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/494',
 'observed_state': 'merged',
 'revision': 'efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f',
 'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/efdf9b61d64c6d3864d92684bc8e7f83b3c0af4f/docs/delivery/android/work-packages/17.04.md',
 'scope_limit': 'Only the three reviewed current guest-local/in-session #33 support outcomes, effective '
                'ADR0009 placement, actual browser481/482 receipt and triage, and safe diagnostic '
                'boundary. Signed efdf delivery and its cancelled overall/native result remain distinct '
                'from the exact five-document-preserving successful9729 default. No installed recipient, '
                'private channel, future rota/SLA, shared-service, other criterion or release completion '
                'is inferred.'}
PRIOR_33_FIELDS = {'status': 'incomplete',
 'evidence_relation': 'issue_context_only; inspect artifact scope before attributing any criterion result',
 'remaining_engineering': 'Implement and validate this exact criterion in the selected scope; record '
                          'negative/recovery cases and receiving evidence.',
 'external_prerequisites': [],
 'completion_evidence': []}
PRIOR_V9_PURPOSE = ('Living evidence index: 25 reviewed documentary/design criteria for Android '
 '#2/#20/#23/#24/#25/#34/#35/#47, 3 reviewed local-model implementation criteria for #26 and the single '
 'reviewed import-integrity criterion 21.2 are complete; 412 criteria remain incomplete. This does not '
 'replace acceptance receipts, GitHub issue status or native dependencies.')
PRIOR_V9_KEYS = ('schema_version',
 'observed_on',
 'repository',
 'implementation_baseline',
 'source',
 'purpose',
 'evidence',
 'issues')
PRIOR_V9_LEDGER_SHA256 = '81314afc8892c8b6069fa6ccfdc35805f7feec3dbec3cf304eaccc392fa5c55b'
PRIOR_V9_LEDGER_FILE_SHA256 = '49fc9557d3fc383c355663c2a71cc5096a4e2dcca5b1ed91da7657e249d627c4'
PRIOR_V9_LEDGER_FILE_BYTES = 494218

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



def reconstruct_prior_v9(ledger):
    """Reverse only #33's fixed completion and placement fields, without authority.

    validate() first requires the exact source-defined support receipt. All other
    criterion, issue, evidence and metadata fields survive for the existing guards.
    """
    previous = copy.deepcopy(ledger)
    issue = previous['issues'][SUPPORT_33_NUMBER - 1]
    issue.pop('accepted_support_outcome')
    issue['canonical_acceptance_record'] = copy.deepcopy(
        SUPPORT_33_ACCEPTANCE['historical_acceptance_record'])
    for criterion in issue['criteria']:
        criterion.update(copy.deepcopy(PRIOR_33_FIELDS))
    previous['evidence'].pop(SUPPORT_33_EVIDENCE_REF)
    previous['schema_version'] = 9
    previous['purpose'] = PRIOR_V9_PURPOSE
    return previous


def reconstruct_prior_v8(ledger):
    """Reverse only the one source-reviewed amendment, never authorize a receipt.

    validate() checks its exact receipt and outcome before using this reconstruction.
    Unrelated issue, criterion, evidence and metadata values stay available to the
    original history guards. Extra finite input metadata retains the v8 CLI behavior.
    """
    previous = reconstruct_prior_v9(ledger)
    criterion = previous['issues'][20]['criteria'][1]
    criterion.pop('accepted_import_integrity_outcome')
    criterion.update(copy.deepcopy(PRIOR_21_2_FIELDS))
    previous['evidence'].pop(IMPORT_21_2_EVIDENCE_REF)
    previous['schema_version'] = 8
    previous['purpose'] = PRIOR_V8_PURPOSE
    return previous


def _validate_prior_v10(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 10, 'unsupported schema version')
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
        accepted_support = number == SUPPORT_33_NUMBER
        require(accepted_support or 'accepted_support_outcome' not in issue,
                f'unsupported support acceptance: {number}')
        require(accepted_implementation or 'accepted_implementation_outcome' not in issue,
                f'unsupported implementation acceptance: {number}')
        if accepted_design:
            expected = accepted_design['acceptance']
            require(record == accepted_design['record'], f'unreviewed acceptance placement: {number}')
            require(json_digest(issue.get('accepted_design_outcome')) == json_digest(expected),
                    f'missing or changed reviewed design acceptance: {number}')
            require([c['text_sha256'] for c in issue['criteria']] == accepted_design['criteria'],
                    f'reviewed design criterion set changed: {number}')
            receipt = evidence.get(expected['evidence_ref'], {})
            require(receipt.get('observed_state') == 'merged' and
                    receipt.get('revision') == expected['decision_revision'] and
                    receipt.get('artifact_url') == accepted_design['artifact_url'],
                    f'accepted design receipt is not the reviewed merged artifact: {number}')
            if number == 2:
                require(json_digest(receipt) == json_digest(DESIGN_2_EVIDENCE),
                        'integrated foundation receipt is not the exact reviewed delivery')
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
        elif accepted_support:
            require('accepted_design_outcome' not in issue, f'unsupported design acceptance: {number}')
            require(record == SUPPORT_33_ACCEPTANCE['canonical_acceptance_record'],
                    'unreviewed support acceptance placement: 33')
            require(json_digest(issue.get('accepted_support_outcome')) == json_digest(SUPPORT_33_ACCEPTANCE),
                    'missing or changed reviewed support acceptance: 33')
            require([c['id'] for c in issue['criteria']] == SUPPORT_33_ACCEPTANCE['criterion_ids'] and
                    [c['text_sha256'] for c in issue['criteria']] == SUPPORT_33_ACCEPTANCE['criterion_text_sha256'],
                    'reviewed support criterion set changed: 33')
            require(json_digest(evidence.get(SUPPORT_33_EVIDENCE_REF)) == json_digest(SUPPORT_33_EVIDENCE),
                    'support receipt is not the exact reviewed delivery: 33')
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
            require('accepted_support_outcome' not in criterion,
                    f'unsupported criterion-local support acceptance: {key}')
            accepted_import = key == IMPORT_21_2_ID
            require(accepted_import or 'accepted_import_integrity_outcome' not in criterion,
                    f'unsupported import-integrity acceptance: {key}')
            if accepted_import:
                require(criterion['text_sha256'] == IMPORT_21_2_ACCEPTANCE['criterion_text_sha256'],
                        'reviewed import-integrity criterion text changed: 21.2')
                require(json_digest(criterion.get('accepted_import_integrity_outcome')) ==
                        json_digest(IMPORT_21_2_ACCEPTANCE),
                        'missing or changed reviewed import-integrity acceptance: 21.2')
                require(json_digest(evidence.get(IMPORT_21_2_EVIDENCE_REF)) ==
                        json_digest(IMPORT_21_2_EVIDENCE),
                        'missing or changed reviewed import-integrity evidence: 21.2')
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
            elif accepted_support:
                require(criterion['status'] == 'complete' and
                        criterion['completion_evidence'] == [SUPPORT_33_EVIDENCE_REF],
                        f'missing reviewed support completion evidence: {key}')
                require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                        f'completed support criterion still has unfinished requirements: {key}')
                require(criterion['evidence_relation'] == COMPLETE_SUPPORT_RELATION,
                        f'unsupported support completion attribution: {key}')
            elif accepted_import:
                require(criterion['status'] == 'complete' and
                        criterion['completion_evidence'] == [IMPORT_21_2_EVIDENCE_REF],
                        'missing reviewed import-integrity completion evidence: 21.2')
                require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                        'completed import criterion still has unfinished requirements: 21.2')
                require(criterion['evidence_relation'] == COMPLETE_IMPORT_RELATION,
                        'unsupported import-integrity completion attribution: 21.2')
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

    # Recover v9 then v8 before the original v7/v6 and coordinate guards.
    # Only the two independently reviewed amendments' allowed fields are reversed.
    previous_v9 = reconstruct_prior_v9(ledger)
    previous_v8 = reconstruct_prior_v8(ledger)
    issues = previous_v8['issues']
    evidence = previous_v8['evidence']

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

    require(json_digest(previous_v8['issues'][20]) == PRIOR_21_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #21 context and partial history')

    # Reverse only the reviewed #2 completion/placement fields and compare
    # the entire prior object, including its distinct receiving prerequisites.
    # The existing v7 reconstruction of #26 and all v6 digests stay unchanged.
    previous_foundation = dict(issues[1])
    previous_foundation.pop('accepted_design_outcome')
    previous_foundation['canonical_acceptance_record'] = DESIGN_2_ACCEPTANCE['historical_acceptance_record']
    previous_foundation['criteria'] = [
        dict(criterion, status='incomplete', completion_evidence=[],
             remaining_engineering=PRIOR_2_REMAINING,
             external_prerequisites=PRIOR_2_EXTERNAL_PREREQUISITES[criterion['id']],
             evidence_relation='issue_context_only; inspect artifact scope before attributing any criterion result')
        for criterion in previous_foundation['criteria']
    ]
    require(json_digest(previous_foundation) == PRIOR_2_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #2 context and partial history')
    previous_issue_records = [previous_foundation if issue['number'] == 2 else issue
                              for issue in issues if issue['number'] != 26]
    require(json_digest(previous_issue_records) == OTHER_ISSUE_RECORDS_SHA256,
            'unreviewed change to another issue record; preserve prior v6 history')
    earlier_evidence = {key: value for key, value in evidence.items() if key not in IMPLEMENTATION_26_EVIDENCE and key != 'pr-412'}
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
    require(json_digest({key: previous_v8[key] for key in PRIOR_V8_KEYS}) == PRIOR_V8_LEDGER_SHA256,
            'unreviewed change to complete prior v8 ledger history')
    require(json_digest({key: previous_v9[key] for key in PRIOR_V9_KEYS}) == PRIOR_V9_LEDGER_SHA256,
            'unreviewed change to complete prior v9 ledger history')


# Actual implementation, record delivery and separately attributed observations.
IMPLEMENTATION_84_EVIDENCE = {'pr-512': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/512',
            'observed_state': 'merged',
            'revision': 'ea1bcd6d6a22d70d6118df1a2063e577938b27fc',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/ea1bcd6d6a22d70d6118df1a2063e577938b27fc/docs/RETAINED_SNAPSHOT_EVALUATION.md',
            'scope_limit': 'Delivered acknowledged private archive to local retained evaluator and '
                           'native read-only caller, with the separate 26-operator and 17-configuration '
                           'reference profiles. Exact original JVM/simulated Android evidence; no '
                           'qualified external estimates, shared wire/service, universal compatibility, '
                           'installed-device or release acceptance.'},
 'pr-528': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528',
            'observed_state': 'merged',
            'revision': 'f70aed520c8e04383bbf558bc2f29dd9a5765879',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/f70aed520c8e04383bbf558bc2f29dd9a5765879/docs/delivery/android/work-packages/09.01.md',
            'scope_limit': 'Delivered four-criterion/four-task local evaluator record and narrow ADR '
                           '0010 placement. This distinct implementation classification consumes '
                           'separately pinned #512 behavior, actual receiving decisions and original '
                           'observations, retaining partial f70 and qualified c052 identities '
                           'separately; documentation alone creates no evaluator behavior or another '
                           'issue completion.'}}
IMPLEMENTATION_84_ACCEPTANCE = {'scope': 'android_retained_snapshot_local_evaluation_and_reference_parity',
 'classification': 'reviewed_implementation_outcome',
 'profile': 'local-retained-frozen-v1-no-external-evidence/1',
 'evidence_refs': ['pr-512', 'pr-528'],
 'implementation_revision': 'ea1bcd6d6a22d70d6118df1a2063e577938b27fc',
 'implementation_tree': '8ff6955f8fbc5246e97e0d381150c88a2f8a8b01',
 'implementation_source_pins': [{'path': 'core/src/main/kotlin/ai/pickyour/core/importing/BoundedJsonReader.kt',
                                 'blob': 'bad5750b5b8dec5eea23d9298a9fcf4c683a3406'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/contracts/FrozenSchemaValidator.kt',
                                 'blob': 'd7e97182e40b05c3f83470bd69eb190e7dbd9178'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/drafts/DraftSnapshotProjection.kt',
                                 'blob': 'ea69212ccbb07350b82c7d59c45531769edf2da8'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/drafts/LocalSnapshotArchive.kt',
                                 'blob': 'd001950953d13233428e33da8c8e4d2e05ca7f7c'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/FrozenDiagnosticEvaluator.kt',
                                 'blob': 'eacf12e1bc4bbda40787ba2e13342133665d5404'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/ReferenceReportAdapter.kt',
                                 'blob': '740dcdc263ec4e2d7715ae5fd49ff7d10b4b804e'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/RetainedSnapshotEvaluation.kt',
                                 'blob': '038f319ca773757b66f0e3870857becc06b7e409'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/analysis/AnalysisCoordinator.kt',
                                 'blob': 'de5ebb9ed4b80e244f5925b51daf493b9d05f298'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/preview/PreviewSnapshotAnalysis.kt',
                                 'blob': '691e9b28f133330586b0695377c46ff125d9acd4'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/SnapshotEvaluationSection.kt',
                                 'blob': '18cbc478bd31d3040c9b2594da44a208b6b64264'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/RetainedConfigurationGoldenTest.kt',
                                 'blob': '28abe9bdf040e6393373d40a666c9ef8fc087767'},
                                {'path': 'core/src/test/resources/evaluation/configuration-cases.json.gz',
                                 'blob': 'e4e9699930d798fdaf54adb92d2769b7f9279619'},
                                {'path': 'scripts/check_configuration_fixtures.py',
                                 'blob': 'e07a7ee532f258d5bb372676a1aacc83c0115267'},
                                {'path': 'scripts/test_configuration_fixtures.py',
                                 'blob': 'd90c9b9cc0784ab0c9e910b496982a29bdda26b0'},
                                {'path': 'scripts/check_diagnostic_fixtures.py',
                                 'blob': '5fc314987765cd5032395315a7bef16c1c8a1ea6'},
                                {'path': '.github/workflows/core.yml',
                                 'blob': 'bb1ac7d16d0d2ea74401d06eaf822eb4e87c354c'}],
 'profile_integrity': {'import_manifest_sha256': 'ef6d5801eec10f3169e2bf84653e7026b33088e305a987991ec52faadcd7f554',
                       'upstream_sums_sha256': '0587a133436268bc03684ad61ff29f3bd1a12eb83db8a497eabdbb8fd8803e15',
                       'reference_sha256': '092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a',
                       'schema_descriptor_sha256': 'eaaf51a04c9bd0c7fce23e7ecc75724226e26b337cc1da04b88a59d2a912914f',
                       'evaluator_sha256': '5d01c0851f3731519869cb8a546d92de10a717c4a5236a557ff70091a1db8fb6',
                       'report_adapter_sha256': '50f1e1ab16d63fde5363d6aedd24e5fd6460b3de534d7b10c38a933777dd1821',
                       'integrity_scope': 'exact source descriptors; not evidence qualification, a '
                                          'publisher signature or compiled runtime identity'},
 'implementation_observation': {'execution_revision': 'ea1bcd6d6a22d70d6118df1a2063e577938b27fc',
                                'execution_tree': '8ff6955f8fbc5246e97e0d381150c88a2f8a8b01',
                                'runtime_scope': 'JVM filesystem and evaluator tests; simulated '
                                                 'Android; unsigned build and lint; original configured '
                                                 'scanner',
                                'main_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39196315800',
                                'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39196315788',
                                'jobs': {'core': '117879748807',
                                         'preview': '117879748668',
                                         'scanner': '117879748476'},
                                'core_cases': 4071,
                                'core_suites': 85,
                                'simulated_android_cases': 353,
                                'simulated_android_suites': 31,
                                'required_attendance_programs': 29,
                                'configuration_verifier_methods': 7,
                                'reachable_scanner_commits': 187,
                                'failures': 0,
                                'errors': 0,
                                'skips': 0,
                                'duplicate_cases': 0,
                                'original_artifacts': [{'id': '12162645279',
                                                        'name': 'Runtime',
                                                        'bytes': 3934864,
                                                        'sha256': 'ad2648f196a44cd0014a08c2b008d6a290b759584eb9ccae31701a4c6856d2d2'},
                                                       {'id': '12162201937',
                                                        'name': 'Core',
                                                        'bytes': 1326118,
                                                        'sha256': 'e1445b87628d1e137d8154584675595d7fc57bf413f117f67a4804aca8375558'},
                                                       {'id': '12161244687',
                                                        'name': 'Preview',
                                                        'bytes': 3089325,
                                                        'sha256': 'bb0a2970b6e92610bd29432b7667d44d6d00091401843ba93d2c31955164da2d'},
                                                       {'id': '12161458268',
                                                        'name': 'Scanner',
                                                        'bytes': 945,
                                                        'sha256': '54c3111a6c63375a6766eb38a83357debca2fc11fa0cf51b43734d0dfff57a1b'}],
                                'unsigned_apk': {'bytes': 3042645,
                                                 'sha256': '455dab5393e0a0db41f3c28bfa05fbd9b463b639a32074b50d1910ff544b4a7d'},
                                'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/512#issuecomment-6260214198',
                                'receipt_published_at': '2026-10-19T05:15:48Z',
                                'original_readback_proof': {'bytes': 377448,
                                                            'sha256': 'bf72067020eb296676f9b0bc7ce634085a2e922133c8376d605a3085a2799fe5'},
                                'attribution': "delivery specialist's original hosted execution and "
                                               'artifact qualification; later source reviewers did not '
                                               'rerun these suites',
                                'preserved_failures': 'Initial fixture-constructor compilation error, '
                                                      'local combined daemon/space failures, incomplete '
                                                      'original-download prefixes and approval-capacity '
                                                      'posting failures remain separately recorded; '
                                                      'none is a passing application execution.'},
 'golden_scope': {'original_operator_reports': 26,
                  'complete_configuration_reports': 17,
                  'findings_per_configuration': 45,
                  'configuration_gzip': {'bytes': 61832,
                                         'sha256': 'dedf5c284dba4c8078dd71f963307009671f9b5a9fd30e33e9a7728cb653a8ac'},
                  'decoded_configuration_json': {'bytes': 650864,
                                                 'sha256': '726a24737be63c92a00d8719094bb7f9844b72b56c6861c59fe1f73cbc96e0a3'},
                  'build_policy': 'omitted by the accepted projection; these configurations exercise '
                                  'reference defaults, not arbitrary policy overrides',
                  'decimal_scope': 'operator corpus covers supported exact decimal comparisons; '
                                   'adjacent configuration budgets remain Unknown without admitted '
                                   'CostEstimate',
                  'optional_scope': 'explicit disabled/enabled T3-T5 and their literal frozen '
                                    'predicates; core T6 rules are not removed',
                  'provenance_scope': 'original neutral wire/migration profile and reference '
                                      'observations are separate from Kotlin evaluator/configuration '
                                      'execution'},
 'record_source_review': {'source_revision': 'b2c1cad7ed40878eca09350fa630cabc62df97a1',
                          'signed_stage_revision': '550145d9a2b5dd0ef7309f0305e5b400da4066d5',
                          'source_tree': '13269b2a33cb6a699b6174e4638823aaf0499d2d',
                          'review': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/523#pullrequestreview-5567409458',
                          'receiving_reviews': ['https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528#pullrequestreview-5567611170',
                                                'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528#pullrequestreview-5567774671',
                                                'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528#pullrequestreview-5567868348',
                                                'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528#pullrequestreview-5568165357'],
                          'scope': 'reviewed four-document source and separately attributed '
                                   'accepted-main interaction/preservation; stage identity is not the '
                                   "record's main delivery"},
 'record_path': 'docs/delivery/android/work-packages/09.01.md',
 'record_blob': '7549f9958b04092f92f18aff27172f2ac869902c',
 'record_bytes': 42362,
 'decision_path': 'docs/decisions/0010-android-84-acceptance-record.md',
 'decision_blob': '75fbe705fdbf9df552fe6072216783f0efc37dd1',
 'decision_bytes': 5191,
 'receiving_reviews': [{'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/84#issuecomment-6260202471',
                        'created_at': '2026-10-19T05:14:49Z',
                        'role': 'actual_in_session_research_specialist_governed_compatibility_capacity_and_central_40_42_receiver',
                        'disposition': 'accept_with_constraints_all_four_criteria_and_four_tasks_for_the_named_local_profile_and_narrow_placement'},
                       {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/84#issuecomment-6260223696',
                        'created_at': '2026-10-19T05:16:36Z',
                        'role': 'actual_in_session_apple_specialist_affected_native_and_delegated_android_domain_receiver',
                        'disposition': 'accept_all_four_criteria_and_four_tasks_for_the_named_local_profile_and_narrow_placement'},
                       {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/84#issuecomment-6260204544',
                        'created_at': '2026-10-19T05:15:00Z',
                        'role': 'actual_in_session_coordinating_and_affected_website_placement_receiver',
                        'disposition': 'accept_the_complete_record_scoped_outcome_and_adr_0010_at_reviewed_delivery'}],
 'receiving_identity_scope': 'actual delegated in-session engineering decisions through the shared '
                             'account; not absent historical assignee replies, separate-account formal '
                             'approvals or new test executions',
 'implemented_subtasks': ['Implement the selected local-interpreter or service adapter behind a stable '
                          'evaluation interface, consuming the exact governed ruleset and graph '
                          'versions.',
                          'Bind every report to build identity/revision, catalog snapshot, ruleset and '
                          'relevant evidence identities; reject malformed or unsupported reports.',
                          'Preserve pass/fail/unknown/skipped and independent severity, numeric/decimal '
                          'semantics and unknown propagation across the Android boundary.',
                          'Run the shared conformance fixtures against Android and the authoritative '
                          'reference implementation, retaining expected outputs with contract '
                          'provenance.'],
 'consumed_prerequisites': [{'issue': 49,
                             'delivery_revision': '033ace652059bd78079bf133e6cabccd9465ecb1',
                             'receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49#issuecomment-6256698763',
                             'scope': '223 neutral wire / 34 private migration profile, original '
                                      'reference expectations and strict 260-case qualifier',
                             'limits': 'output-only intake; open semantic work and separate Manifest '
                                       'numeric diagnostic6254033978 are not completed'},
                            {'issue': 58,
                             'source_pins': ['de5ebb9ed4b80e244f5925b51daf493b9d05f298',
                                             '691e9b28f133330586b0695377c46ff125d9acd4'],
                             'owner_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/58#issuecomment-6252008356',
                             'scope': 'actual opaque coordinator, worker/delivery session and local '
                                      'retained-view typed cancellation boundary',
                             'limits': 'no whole application, remote-service, editor/catalog/repository '
                                       'or other caller completion'},
                            {'issue': 21,
                             'receiving_scope': '43-file frozen package and two manifests; bounded '
                                                'raw/schema/report/graph outputs at the implementation '
                                                'revision',
                             'corrections': ['https://github.com/Pick-Your-AI/pick-your-ai-android/pull/287',
                                             'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/326'],
                             'limits': 'output-only intake; no whole issue, resource/cost '
                                       'qualification, business semantics or successor-profile '
                                       'adoption'}],
 'profile_limits': ['The complete graph is schema-admitted and exactly bound before full reevaluation; '
                    'no edge pruning or graph-derived rule selection is implemented.',
                    'Every requested ResourceEstimate and CostEstimate is independently diagnosed and '
                    'withheld; NotSupplied is distinct. A retained Pricebook supplies no zero cost, '
                    'calibrated fit, completeness proof or budget pass.',
                    "Only the wrapper's own pinned local computation provides report provenance; "
                    'arbitrary decoded or structurally accepted reports cannot acquire retained-view '
                    'authority.',
                    'Full mathematical Build revision, raw bundle/source and every input/status/profile '
                    'digest are in actual coordinator identity; Long 0 is only the immutable-view '
                    'epoch.',
                    'Exact request, opaque ticket, fresh attempt and Activity/window/session must match '
                    'before visible delivery; foreign, ABA, cancelled, replaced or late results are '
                    'rejected.',
                    'Fixed frozen fixture time remains distinct from the actual observation time; '
                    'pass/fail/unknown/skipped and severity remain independent, and readiness is '
                    'blocked or needs_review.',
                    'Declared raw-number, Unicode, parser, collection, operator and deterministic '
                    'work-budget limits are explicit local refusals, not acceptance of every '
                    'schema-valid DSL expression.',
                    'Legitimate source deletion does not invalidate independently retained snapshots; '
                    'an older captured index gives historical read-only inspection, never current '
                    'archive CAS or pending-editor authority.',
                    'Private current-envelope bytes and represented history are retained; legacy '
                    'predecessor bytes, shared export/visibility/rights and original-format replay are '
                    'not invented.',
                    'No universal compatibility, qualified capacity/cost, shared service or wire, graph '
                    'optimization, automatic editor evaluation, creation/removal UI, installed-device, '
                    'physical durability or release acceptance is recorded.'],
 'historical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                  'path': 'docs/delivery/android/work-packages/09.01.md'},
 'preserved_prior_ledger': {'schema_version': 10,
                            'raw_sha256': 'a20b3b5f90a9ebf7723e57049777c391a473965cebf3d7d98db00daf8a0cd720',
                            'canonical_sha256': '92b905e8707c6f5dd3061eeca234a550a768ab02cb42eadca6dd7b7692f5eddb',
                            'other_issue_records': 128,
                            'earlier_evidence_records': 26,
                            'criterion_texts': 441},
 'record_revision': 'f70aed520c8e04383bbf558bc2f29dd9a5765879',
 'record_tree': '64df91b53e4f6c5c449c7a08da1993c65379c2a5',
 'record_delivery': {'signature_verified': True,
                     'parents': ['de668ae011193691d301b849c330604a8155929b'],
                     'merged_at': '2026-10-19T07:35:47Z',
                     'source_revision': '550145d9a2b5dd0ef7309f0305e5b400da4066d5',
                     'full_leaf_count': 508,
                     'reviewed_document_count': 4,
                     'other_accepted_entries': 504,
                     'deleted_entries': 0},
 'record_partial_observation': {'execution_revision': 'f70aed520c8e04383bbf558bc2f29dd9a5765879',
                                'execution_tree': '64df91b53e4f6c5c449c7a08da1993c65379c2a5',
                                'status': 'partial_original_observation_not_full_gate_acceptance',
                                'main_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39217011129',
                                'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39217011038',
                                'jobs': {'core': '117943607249',
                                         'preview': '117943607325',
                                         'scanner': '117943108968'},
                                'core_cases': 5150,
                                'core_suites': 93,
                                'simulated_android_cases': 388,
                                'simulated_android_suites': 34,
                                'reported_test_failures': 0,
                                'reported_test_errors': 0,
                                'reported_test_skips': 0,
                                'preview_job_conclusion': 'cancelled',
                                'cancelled_required_step': {'name': 'Reject ignored packaged preview '
                                                                    'assets',
                                                            'status': 'completed',
                                                            'conclusion': 'cancelled',
                                                            'number': 10,
                                                            'started_at': '2026-10-19T07:43:02Z',
                                                            'completed_at': '2026-10-19T07:43:47Z'},
                                'skipped_preview_steps': ['Export and validate the current runtime SPDX '
                                                          'document',
                                                          'Inspect the packaged synthetic preview '
                                                          'policy',
                                                          'Require synthetic scenario admission '
                                                          'regressions to execute',
                                                          'Require all simulated UI regressions to '
                                                          'execute',
                                                          'Require catalog snapshot callback regression '
                                                          'to execute',
                                                          'Require current catalog result-row '
                                                          'regressions to execute',
                                                          'Require local dialog authority regressions '
                                                          'to execute',
                                                          'Require incoming-entry recovery regressions '
                                                          'to execute',
                                                          'Require bounded component form recovery '
                                                          'regressions to execute',
                                                          'Require explicit component role and recovery '
                                                          'regressions to execute',
                                                          'Require explicit foundation selection '
                                                          'regressions to execute',
                                                          'Require core planning guidance regressions '
                                                          'to execute',
                                                          'Require explicit retrieval selection '
                                                          'regressions to execute',
                                                          'Require explicit agent selection regressions '
                                                          'to execute',
                                                          'Require explicit training selection '
                                                          'regressions to execute',
                                                          'Require core guardrail and evaluation '
                                                          'selection regressions to execute',
                                                          'Require complete recorded-price browser '
                                                          'regressions to execute',
                                                          'Require catalog comparison inspector '
                                                          'retirement regressions to execute',
                                                          'Require complete local revision comparison '
                                                          'regressions to execute',
                                                          'Require private diagnostic preview and '
                                                          'consent regressions to execute',
                                                          'Require current-draft summary and exact '
                                                          'navigation regressions to execute',
                                                          'Require local help and public-report consent '
                                                          'regressions to execute',
                                                          'Require explicit retained-revision pair '
                                                          'regressions to execute',
                                                          'Require typed local draft load recovery '
                                                          'regressions to execute',
                                                          'Require exact catalog pin recovery '
                                                          'regressions to execute',
                                                          'Require explicit saved-library comparison '
                                                          'regressions to execute',
                                                          'Require complete recorded catalog detail '
                                                          'regressions to execute',
                                                          'Require recorded findings selection context '
                                                          'regressions to execute',
                                                          'Require current-choice accessibility '
                                                          'regressions',
                                                          'Require real saved-snapshot inspection '
                                                          'regressions',
                                                          'Require catalog viewport restoration '
                                                          'regressions to execute'],
                                'missing_runtime_receipts': ['build/inventory/guard-ignored-preview-asset.json',
                                                             'build/sbom/runtime.spdx.json',
                                                             'build/inventory/preview-policy.json',
                                                             'build/inventory/preview-policy-guards.json'],
                                'original_artifacts': [{'id': '12170580266',
                                                        'name': 'Core',
                                                        'bytes': 1577924,
                                                        'sha256': '9fc9cc6c747ec955e9bc5a40e7445b79c70e8bf8fdb543b7ff3b92211ab53369'},
                                                       {'id': '12170511491',
                                                        'name': 'Preview',
                                                        'bytes': 3207547,
                                                        'sha256': 'e23664821a9293fc1401c46fd39c9a99a1a66e1e1dfd242b743a4fffc38df3ea'},
                                                       {'id': '12170252624',
                                                        'name': 'Runtime',
                                                        'bytes': 4229182,
                                                        'sha256': '4eb6ae9fdbc82a4470eefc9f3067293c31f2d00c54fec30a31c9882ced19d0dc'},
                                                       {'id': '12169498371',
                                                        'name': 'Scanner',
                                                        'bytes': 1009,
                                                        'sha256': 'eebd3609072b04416c205db5230d2354633dc7f9818535a43318ba76a04d561f'}],
                                'partial_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528#issuecomment-6262368693',
                                'receipt_published_at': '2026-10-19T07:50:37Z',
                                'original_readback_proof': {'bytes': 404331,
                                                            'sha256': '1b1a5a7d32abfec2c73204f128ed5185752fc0cf41370dc12eb948bc311e2b28'},
                                'scope': 'Completed original tests/build/lint and scanner portions '
                                         'only; canceled packaged-input and skipped subsequent guards '
                                         'are not whole-job success.'},
 'record_observation': {'execution_revision': 'c05238a37c61ee7b368b3ca72867645a667d6a74',
                        'execution_tree': '26065791aa29c73fc2328abfe4c63c3883027b5b',
                        'execution_parents': ['f70aed520c8e04383bbf558bc2f29dd9a5765879'],
                        'signature_verified': True,
                        'runtime_scope': 'Original JVM and simulated Android, unsigned build/lint and '
                                         'package-policy/runtime/receipt checks; no installed-device or '
                                         'release qualification.',
                        'main_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39217751072',
                        'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39217751058',
                        'attempt': 1,
                        'jobs': [{'id': '117945898584',
                                  'name': 'Unsigned synthetic Android preview',
                                  'run_id': '39217751072',
                                  'started_at': '2026-10-19T07:43:57Z',
                                  'completed_at': '2026-10-19T07:49:10Z',
                                  'conclusion': 'success',
                                  'successful_steps': 47},
                                 {'id': '117945898851',
                                  'name': 'Frozen contracts and JVM core',
                                  'run_id': '39217751072',
                                  'started_at': '2026-10-19T07:43:58Z',
                                  'completed_at': '2026-10-19T07:47:27Z',
                                  'conclusion': 'success',
                                  'successful_steps': 23},
                                 {'id': '117945512173',
                                  'name': 'Pinned secret scanning',
                                  'run_id': '39217751058',
                                  'started_at': '2026-10-19T07:42:51Z',
                                  'completed_at': '2026-10-19T07:43:04Z',
                                  'conclusion': 'success',
                                  'successful_steps': 7}],
                        'core_cases': 5150,
                        'core_suites': 93,
                        'simulated_android_cases': 388,
                        'simulated_android_suites': 34,
                        'failures': 0,
                        'errors': 0,
                        'skips': 0,
                        'duplicate_cases': 0,
                        'native_attendance_programs': 29,
                        'ledger_methods': 103,
                        'ledger_duration_seconds': {'core': '17.611', 'preview': '15.331'},
                        'ledger_observation_scope': 'Original aggregate and success dots; source '
                                                    'inventory has 103 declarations, not 103 '
                                                    'individually named current log results.',
                        'scanner_guard_methods': 28,
                        'reachable_scanner_commits': 208,
                        'raw_findings': 0,
                        'reviewed_findings': 0,
                        'unresolved_findings': 0,
                        'lint_warnings': 19,
                        'lint_errors': 0,
                        'conformance': {'tests': 260,
                                        'wire_observations': 223,
                                        'private_migration_observations': 34,
                                        'per_case_observations': 257},
                        'source_inventory': {'core_pins': 8,
                                             'core_dependencies': 21,
                                             'native_pins': 10,
                                             'native_dependencies': 42},
                        'spdx': {'core_packages': 23,
                                 'core_relationships': 44,
                                 'native_packages': 48,
                                 'native_relationships': 91},
                        'package_policy_cases': 12,
                        'host_policy_guards': 18,
                        'all_original_steps_concluded_success': True,
                        'original_artifacts': [{'id': '12170566882',
                                                'name': 'Core',
                                                'bytes': 1579880,
                                                'sha256': '485809fbf0a11e2f591fc2ea4298fe6338c33ea2e4ff0955c38401d42817492b'},
                                               {'id': '12170572664',
                                                'name': 'Preview',
                                                'bytes': 3207377,
                                                'sha256': 'fcef6a7b7e66be3b6819fba36e8dffa6bcc9cffea9bd20b059de4fd26a129880'},
                                               {'id': '12171055619',
                                                'name': 'Runtime',
                                                'bytes': 4241195,
                                                'sha256': '0ae71b97045afba23b62ae46812e082c56cce248bad88d2530ecee08106d6f8a'},
                                               {'id': '12169314855',
                                                'name': 'Scanner',
                                                'bytes': 1010,
                                                'sha256': 'd619334fb02e23bde529691010c82c8c9a56a7771434fe98ece5bf492fff103c'}],
                        'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/524#issuecomment-6262552726',
                        'receipt_published_at': '2026-10-19T08:02:08Z',
                        'original_qualification_proofs': {'core_sha256': 'a30959af29e940057ecb1a7ea470a34eedb22415cf9aa5a9b8ab3085457ac0f7',
                                                          'native_sha256': '18333dcbd00726e8b26610c3def1f7a14835f0cb8d17cbc7bf00b6410186d768',
                                                          'scanner_sha256': '8eede8b7523943fc3a6c8aba981d295fd42af5d9c34480f331b906d034cb1098',
                                                          'cross_job': {'bytes': 6402,
                                                                        'sha256': 'dda1b0f57e5ec0168d142065c2f57d7e50b56b435064100f4b053f3686067539'},
                                                          'independent_original_corroboration': {'bytes': 3660,
                                                                                                 'sha256': 'bbe4318a5c910c97ff4e8235ad38492d12da5a025332ed4c1200022dac2b1fad'}},
                        'attribution': 'Canonical #524 qualification and its independently attributed '
                                       'original-output readers; the #84 ledger author directly checked '
                                       'this public receipt, job conclusions and provider artifact '
                                       'metadata, without repeating the original ZIP audit or product '
                                       'runs.',
                        'reader_recovery_history': 'Original archive-wide suite-order assumption '
                                                   'stopped; exact case identities and within-suite '
                                                   'order were then verified. A scratch-only Core log '
                                                   'terminal newline was corrected against the original '
                                                   'API string. Neither was a product failure or '
                                                   'workflow rerun.'},
 'record_observation_reuse': {'record_revision': 'f70aed520c8e04383bbf558bc2f29dd9a5765879',
                              'record_tree': '64df91b53e4f6c5c449c7a08da1993c65379c2a5',
                              'observation_revision': 'c05238a37c61ee7b368b3ca72867645a667d6a74',
                              'observation_tree': '26065791aa29c73fc2328abfe4c63c3883027b5b',
                              'observation_parents': ['f70aed520c8e04383bbf558bc2f29dd9a5765879'],
                              'baseline': {'path': 'docs/ACCEPTANCE_BASELINE.md',
                                           'blob': '68f3b65923d87d64b11a627100a1f25669a9a313',
                                           'bytes': 29906,
                                           'sha256': '22ed7b2d6c9246e072f203f1a22b423bf10d41bfa780a055650215e5867cbfea'},
                              'map_encoding': 'Path-sorted objects with path/mode/type/sha; JSON '
                                              'sort_keys=True, ensure_ascii=True, '
                                              'separators=(comma,colon); ASCII, no newline.',
                              'full_leaf_count': 508,
                              'unchanged_leaf_count': 504,
                              'unchanged_leaf_map_bytes': 73673,
                              'unchanged_leaf_map_sha256': '7f60129309bd62afbe32c5f6ba36e95d30cdaba0470739bb70a8fe7a351f0b2a',
                              'changed_noncompiled_paths': [{'path': 'docs/delivery/README.md',
                                                             'record': {'path': 'docs/delivery/README.md',
                                                                        'mode': '100644',
                                                                        'type': 'blob',
                                                                        'sha': 'b939562ef3b77d19635c932b62471dc35d489f9d'},
                                                             'observation': {'path': 'docs/delivery/README.md',
                                                                             'mode': '100644',
                                                                             'type': 'blob',
                                                                             'sha': 'acee2ce63cd6b83a4a68923de1e5d6ab1211817e'}},
                                                            {'path': 'docs/delivery/criterion-evidence.json',
                                                             'record': {'path': 'docs/delivery/criterion-evidence.json',
                                                                        'mode': '100644',
                                                                        'type': 'blob',
                                                                        'sha': '1dd8decdfd04a313ac335929361e378262445d52'},
                                                             'observation': {'path': 'docs/delivery/criterion-evidence.json',
                                                                             'mode': '100644',
                                                                             'type': 'blob',
                                                                             'sha': '92fca42044e9844f53f3211edfb8f785384b065c'}},
                                                            {'path': 'scripts/check_delivery_ledger.py',
                                                             'record': {'path': 'scripts/check_delivery_ledger.py',
                                                                        'mode': '100644',
                                                                        'type': 'blob',
                                                                        'sha': '5d97d863637409329b315dc490dd798d0fb89ea9'},
                                                             'observation': {'path': 'scripts/check_delivery_ledger.py',
                                                                             'mode': '100644',
                                                                             'type': 'blob',
                                                                             'sha': '03e52fd8ed61e864c28e667daee57e1dc71a8cb6'}},
                                                            {'path': 'scripts/tests/test_delivery_ledger.py',
                                                             'record': {'path': 'scripts/tests/test_delivery_ledger.py',
                                                                        'mode': '100644',
                                                                        'type': 'blob',
                                                                        'sha': '16dbaabbfa82b75db9fb4a631514636314445834'},
                                                             'observation': {'path': 'scripts/tests/test_delivery_ledger.py',
                                                                             'mode': '100644',
                                                                             'type': 'blob',
                                                                             'sha': '7174a69212791de5ef4df31be837f678525aded6'}}],
                              'relation': 'separately_qualified_successor_for_exact_unchanged_compiled_native_and_profile_inputs',
                              'receiving_review': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528#issuecomment-6262570389',
                              'receiving_review_created_at': '2026-10-19T08:03:15Z',
                              'receiving_role': 'actual_in_session_research_specialist_source_profile_and_input_preservation_review',
                              'changed_input_scope': 'The two Python ledger programs require their own '
                                                     'current execution; no claim that every executable '
                                                     'file is identical.',
                              'qualification_scope': 'Original c052 native/build/package/runtime '
                                                     'observations stay at c052; scanner history and '
                                                     'artifact containers are not relabeled as f70.',
                              'preserved_partial_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/528#issuecomment-6262368693'},
 'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/f70aed520c8e04383bbf558bc2f29dd9a5765879/docs/delivery/android/work-packages/09.01.md',
 'decision_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/f70aed520c8e04383bbf558bc2f29dd9a5765879/docs/decisions/0010-android-84-acceptance-record.md',
 'record_sha256': '82a9625f43b4f368a337af5f7785a3e78d08c13037477d238ffeda249884267d',
 'decision_sha256': '1a591932667587fe787b167d4929cc30201a31c4e5c4c02b0e85a39919046186'}
PRIOR_84_ISSUE_SHA256 = 'c30bb0d2ee6332d8946a836f1fa3ab636ff434b66a9ef0c56d13af22259a2fd9'


# Exactly four implementation criteria are accepted for the named retained-input
# profile. This is a source-reviewed allowlist, not input-granted approval. The
# complete prior v10 validator below remains the historical authority for every
# earlier receipt, ordinary coordinate, criterion and metadata field.
PRIOR_V10_SCHEMA_VERSION = 10
PRIOR_V10_KEYS = PRIOR_V9_KEYS
PRIOR_V10_LEDGER_SHA256 = '92b905e8707c6f5dd3061eeca234a550a768ab02cb42eadca6dd7b7692f5eddb'
PRIOR_84_REMAINING = 'Implement and validate this exact criterion in the selected scope; record negative/recovery cases and receiving evidence.'
IMPLEMENTATION_84_CRITERIA = [
    'befeefe7905770eee96b97e0732b0914d5fe48b7acc2ce3cb96a7c8bb819745e',
    '6d324bf2339e7995202f07997a5485c0cab5fa42e4ddbdc0ab903d42b2e29204',
    'e3b03cbc85c024fac4281ad7dc6c1a20ec976409f09ade97c27aec77b873ecbc',
    '93396b5357f520d48404a065d16d0c3aaccfe21e3a69d72330667bbede8350fd',
]
PRIOR_V10_PURPOSE = 'Living evidence index: 25 reviewed documentary/design criteria for Android #2/#20/#23/#24/#25/#34/#35/#47, 3 reviewed local-model implementation criteria for #26, the single reviewed import-integrity criterion 21.2 and 3 reviewed bounded support criteria for #33 are complete; 409 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies.'
LEDGER_PURPOSE_V11 = 'Living evidence index: 25 reviewed documentary/design criteria for Android #2/#20/#23/#24/#25/#34/#35/#47, 7 reviewed implementation criteria for #26/#84, the single reviewed import-integrity criterion 21.2 and 3 reviewed bounded support criteria for #33 are complete; 405 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies.'


def reconstruct_prior_v10(ledger):
    """Reverse only the reviewed #84 amendment; this never grants acceptance.

    validate() admits the literal fixed outcome and both exact evidence entries
    first. All prior issue/criterion fields remain in the returned copy, including
    unexpected fields, so the historical validators can reject any alteration.
    """
    previous = copy.deepcopy(ledger)
    issue = previous['issues'][83]
    issue.pop('accepted_implementation_outcome')
    issue['canonical_acceptance_record'] = copy.deepcopy(
        IMPLEMENTATION_84_ACCEPTANCE['historical_acceptance_record'])
    for criterion in issue['criteria']:
        criterion.update(status='incomplete', completion_evidence=[],
                         remaining_engineering=PRIOR_84_REMAINING,
                         external_prerequisites=[],
                         evidence_relation='issue_context_only; inspect artifact scope before attributing any criterion result')
    for evidence_ref in IMPLEMENTATION_84_EVIDENCE:
        previous['evidence'].pop(evidence_ref)
    previous['schema_version'] = PRIOR_V10_SCHEMA_VERSION
    previous['purpose'] = PRIOR_V10_PURPOSE
    return previous


def _validate_prior_v11(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 11,
            'unsupported schema version')
    require(ledger['purpose'] == LEDGER_PURPOSE_V11,
            'unsupported ledger completion summary')
    issues = ledger['issues']
    numbers = [issue['number'] for issue in issues]
    require(all(type(number) is int for number in numbers),
            'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)),
            'expected unique ordered issue numbers 1–129')
    issue = issues[83]
    require('accepted_design_outcome' not in issue, 'unsupported design acceptance: 84')
    require('accepted_support_outcome' not in issue, 'unsupported support acceptance: 84')
    require(issue['canonical_acceptance_record'] == {
        'repository': REPOSITORY, 'path': IMPLEMENTATION_84_ACCEPTANCE['record_path']},
        'unreviewed acceptance coordinates or placement: 84')
    require(json_digest(issue.get('accepted_implementation_outcome')) ==
            json_digest(IMPLEMENTATION_84_ACCEPTANCE),
            'missing or changed reviewed implementation acceptance: 84')
    require([c['text_sha256'] for c in issue['criteria']] == IMPLEMENTATION_84_CRITERIA,
            'reviewed implementation criterion set changed: 84')
    for evidence_ref, expected in IMPLEMENTATION_84_EVIDENCE.items():
        require(json_digest(ledger['evidence'].get(evidence_ref)) == json_digest(expected),
                'implementation receipt is not the reviewed merged artifact: 84')
    for index, criterion in enumerate(issue['criteria'], 1):
        key = f'84.{index}'
        require(criterion['id'] == key, f'criterion identity/order mismatch: {key}')
        require(criterion['status'] == 'complete' and
                criterion['completion_evidence'] == IMPLEMENTATION_84_ACCEPTANCE['evidence_refs'],
                f'missing reviewed implementation completion evidence: {key}')
        require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                f'completed criterion still has unfinished requirements: {key}')
        require(criterion['evidence_relation'] == COMPLETE_IMPLEMENTATION_RELATION,
                f'unsupported implementation completion attribution: {key}')

    previous_v10 = reconstruct_prior_v10(ledger)
    # Preserve all original diagnostic branches and exact v8/v7/v6 history checks.
    # They run before the additional whole-v10 digest, retaining useful failures
    # for a changed ordinary issue, receipt, source text or historical metadata.
    _validate_prior_v10(previous_v10, source)
    require(json_digest(previous_v10['issues'][83]) == PRIOR_84_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #84 context and partial history')
    require(json_digest({key: previous_v10[key] for key in PRIOR_V10_KEYS}) ==
            PRIOR_V10_LEDGER_SHA256,
            'unreviewed change to complete prior v10 ledger history')



# Actual reviewed #96 delivery; all earlier validators above retain their bodies.
IMPLEMENTATION_96_ACCEPTANCE = {'scope': 'android_stored_draft_slot_empty_candidate_picker',
 'classification': 'reviewed_implementation_outcome',
 'profile': 'local-stored-draft-slot-empty-diagnostics/1',
 'evidence_refs': ['pr-553'],
 'implementation_revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
 'implementation_tree': '8d4dec5720c64560e7b08032dc6efb4db9575dad',
 'implementation_source': {'revision': 'fd1a62ce1db00bf0690a481314caa93b2ad3f59b',
                           'tree': '8d4dec5720c64560e7b08032dc6efb4db9575dad',
                           'parents': ['4c2d6afcb4680e47671bcf8646bc5e0224caba1a',
                                       '517025db301785f8e3689abdeeb47fc8d09b9afc'],
                           'signature_verified': False,
                           'transport': 'unsigned Git Data source; separately reviewed and '
                                        'normally delivered'},
 'implementation_source_pins': [{'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/CompatibleOptions.kt',
                                 'mode': '100644',
                                 'blob': '2896964bf9f7fcf2caefe1eaf093b79950366483',
                                 'bytes': 17521,
                                 'sha256': '1c9a2c8931275f4fe6717496658d739513dbb2ffebdbd11df677a7ce5cb05883'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/CompatibleOptionsCoordinator.kt',
                                 'mode': '100644',
                                 'blob': '0e0b8efe052dd708ea6fd84a2f8395f5bcdd11fe',
                                 'bytes': 2688,
                                 'sha256': 'f3d8c7cb98fbcedfa2968004739b0b92510c5af1d17a246505437cba6ffb2bbf'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptions.kt',
                                 'mode': '100644',
                                 'blob': '198f7ef0b7e8b0108b2af8a62aba48db5a6fd3c7',
                                 'bytes': 19468,
                                 'sha256': '7980a966004004792bf5901f4dd85f4a49f02042b14fc008e71ec320c110b78a'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsCoordinator.kt',
                                 'mode': '100644',
                                 'blob': 'b7aea964933c2c948dd23a0ce389b21774d20161',
                                 'bytes': 2422,
                                 'sha256': '5460f3dbb2328ca060fb90c32052a34d1b51e889cee6bce0c6855126954cbe5d'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/preview/PreviewCompatibleOptions.kt',
                                 'mode': '100644',
                                 'blob': '7c2956f57c350a35c346bef0d6d91be1d3d5b70e',
                                 'bytes': 4142,
                                 'sha256': '72a30a0b22198ce594303eb4632c565c56a8815debec967e5edddae5dc41431b'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                 'mode': '100644',
                                 'blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                 'bytes': 29342,
                                 'sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/CompatibleOptionsAbsentLockCausalReplayTest.kt',
                                 'mode': '100644',
                                 'blob': 'abe7f60a70022f01b8a95ce53a05efa7b8db49b2',
                                 'bytes': 6100,
                                 'sha256': '09c524fb6b4ad0cb01972d80d5e7f656699f3da535986c0abde6a61eb59dd992'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/CatalogFixtureData.kt',
                                 'mode': '100644',
                                 'blob': '202bc3b147c1e7bd9f6da288e3e60ec1fa2674f6',
                                 'bytes': 2321,
                                 'sha256': '542a5ca1960a5fad281773777cd3df9e421c5a965f262390da2e20f2eba356ac'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/CompatibleOptionsPicker.kt',
                                 'mode': '100644',
                                 'blob': '846f7c89229718c5756f792946d1e6dc0b4c475d',
                                 'bytes': 14787,
                                 'sha256': 'e59b3cd1421e51403b0234e50e160e06c9af0561423048f409b09b7c5c36a515'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/SelectionEditSection.kt',
                                 'mode': '100644',
                                 'blob': '952746f4d1a788c3fba00be6bab0999fcf2e1ff7',
                                 'bytes': 32998,
                                 'sha256': 'c9daf91b60f2d9f5f343343da47a65adf061767d254a39f0abc2338b15e1f0cd'},
                                {'path': 'preview/src/main/res/values/compatible_options_strings.xml',
                                 'mode': '100644',
                                 'blob': '0420be6561cd33790f1cace3d2c3ff6f08375409',
                                 'bytes': 3740,
                                 'sha256': '5bccebe9bef4a791a3644dbc462b13070885e4112b5615454d62259fc126c23c'},
                                {'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                 'mode': '100644',
                                 'blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                 'bytes': 24751,
                                 'sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/preview/PreviewCatalogBrowser.kt',
                                 'mode': '100644',
                                 'blob': '2ec6efc1bf08354e6596d96bbdd79db4a992d888',
                                 'bytes': 5858,
                                 'sha256': 'b778377e7c70e988f9b45f3f246641aedb469818cbdc2f6f8dafd1a7e01fb8a5'},
                                {'path': 'docs/COMPATIBLE_OPTIONS.md',
                                 'mode': '100644',
                                 'blob': 'bd96c872ecbee13e29f5767fbd510906ebafd753',
                                 'bytes': 18865,
                                 'sha256': '6040f3a9d2134b6b0432e3d60ccb7c1c81ff840cfe30bf252fd23acc5b161469'},
                                {'path': 'docs/decisions/0010-android-84-acceptance-record.md',
                                 'mode': '100644',
                                 'blob': '75fbe705fdbf9df552fe6072216783f0efc37dd1',
                                 'bytes': 5191,
                                 'sha256': '1a591932667587fe787b167d4929cc30201a31c4e5c4c02b0e85a39919046186'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/PreviewActivity.kt',
                                 'mode': '100644',
                                 'blob': 'e5806f1e8ef322da25fd3148d0064ca64155b311',
                                 'bytes': 89061,
                                 'sha256': 'b0b15d72a467c753e3eb4a5f366c551537a5bf8475affb1f864a54229ec837c4'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/FrozenDiagnosticEvaluator.kt',
                                 'mode': '100644',
                                 'blob': 'f66cb21d610597366d22caa4a1b4eecdef2b19cd',
                                 'bytes': 20604,
                                 'sha256': 'b0bc63de8d6d4fed412344c4cb58864248782edf74aa9fb0fa75df9f3e80667f'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/RetainedSnapshotEvaluation.kt',
                                 'mode': '100644',
                                 'blob': 'b2e0ebcb95e8cbb06fbe5ec07cd2970eee8f7e22',
                                 'bytes': 18911,
                                 'sha256': 'f9a71074fc8f6d9cf7c670df920dae5badb44773036de1c809e447d9890af986'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/CompatibleOptionsTest.kt',
                                 'mode': '100644',
                                 'blob': 'a9c77981332e28cc92764721ad9f8b16c7a04eac',
                                 'bytes': 22759,
                                 'sha256': '85b8225d2318240feac6ebc4f8f6a03fa86a8aa0d2f596aeaf9973f96238b995'},
                                {'path': 'docs/RETAINED_SNAPSHOT_EVALUATION.md',
                                 'mode': '100644',
                                 'blob': '456a07ccb93de27ab207d0fe12011dfdf7524e21',
                                 'bytes': 24889,
                                 'sha256': '22568710e1d7f1f37906f7e5e70af51f284732c58b37b5dcb5025e33fa7b39a9'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/ReferenceReportAdapter.kt',
                                 'mode': '100644',
                                 'blob': 'd6460a52217e1555e69d5f9feff91bf7de58ef62',
                                 'bytes': 6403,
                                 'sha256': 'd39e266490ffb7814b0bb2cc05a108d60206f036a3be7beb6c340b29723fa525'},
                                {'path': 'scripts/check_diagnostic_fixtures.py',
                                 'mode': '100644',
                                 'blob': 'e9e4672cbdc081ee9d5e6d9ceec8924e601b8088',
                                 'bytes': 5494,
                                 'sha256': '4fad9920852d1aaa91a2c578aff98aa39bf5ea31638e7ac4e0a50bb2ffb3f425'},
                                {'path': '.github/workflows/core.yml',
                                 'mode': '100644',
                                 'blob': '12f40292ee514c2e16d4d224a2bb6ee86aa296e7',
                                 'bytes': 127311,
                                 'sha256': '070c505963c6d3b67e869234c8636d4fd031d434c2013474ffbc38d1e2f54834'}],
 'source_review': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/553#pullrequestreview-5573484375',
                   'created_at': '2026-10-19T14:57:43Z',
                   'source_revision': 'fd1a62ce1db00bf0690a481314caa93b2ad3f59b',
                   'state': 'COMMENTED',
                   'body_bytes': 6184,
                   'body_sha256': '81f70b099a4b65850b090462e6790304cb9736cf2dbf361ae73ac270ca85df25',
                   'scope': 'Complete current source/record receiving continuation, including '
                            'accepted main and exact profile pins; not a distinct-account approval '
                            'or new product execution.'},
 'profile_integrity': {'interpreter_sha256': 'b0bc63de8d6d4fed412344c4cb58864248782edf74aa9fb0fa75df9f3e80667f',
                       'report_receiver_sha256': 'd39e266490ffb7814b0bb2cc05a108d60206f036a3be7beb6c340b29723fa525',
                       'descriptor': {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/RetainedSnapshotEvaluation.kt',
                                      'mode': '100644',
                                      'blob': 'b2e0ebcb95e8cbb06fbe5ec07cd2970eee8f7e22',
                                      'bytes': 18911,
                                      'sha256': 'f9a71074fc8f6d9cf7c670df920dae5badb44773036de1c809e447d9890af986'},
                       'scope': 'Actual delivered implementation hashes; historical a6/d0/899/9db '
                                'and older report hashes remain their original observations. '
                                'Private candidate evaluation does not consume new '
                                'graph/price/capacity authority.'},
 'record_revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
 'record_tree': '8d4dec5720c64560e7b08032dc6efb4db9575dad',
 'record_path': 'docs/delivery/android/work-packages/07.02.md',
 'record_blob': '890b882b195f5d0b9c07a0e25ab54911f22e8e38',
 'record_bytes': 31867,
 'record_sha256': '7ea1ac9ff3aee360e1724b89f0ed2417aa052f49cece9fbd180dcfabbf8267a1',
 'decision_path': 'docs/decisions/0013-android-96-acceptance-record.md',
 'decision_blob': 'a85c43a2f2f44a49b0d5496c1dbec9ceae622c17',
 'decision_bytes': 4532,
 'decision_sha256': '49e459c063274efa0a3df0daff93116a1a32d0d42fd97350d42aac909501bac5',
 'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/8bedd80dcc0b3cdd9756785c57cef0e54211c63b/docs/delivery/android/work-packages/07.02.md',
 'decision_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/8bedd80dcc0b3cdd9756785c57cef0e54211c63b/docs/decisions/0013-android-96-acceptance-record.md',
 'historical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                  'path': 'docs/delivery/android/work-packages/07.02.md'},
 'placement_scope': 'Actual signed delivery makes the reviewed ADR0013 effective only for Android '
                    '#96 /07.02; dated record text and original source/observation pins are '
                    'unchanged. No other coordinate or canonical research authority moves.',
 'receiving_reviews': [{'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/553#pullrequestreview-5570634226',
                        'created_at': '2026-10-19T11:07:34Z',
                        'role': 'actual_in_session_data_consumer',
                        'disposition': 'ACCEPT_WITH_CONSTRAINTS',
                        'state': 'COMMENTED',
                        'source_revision': 'a6ca821954aa29b15813afd82238e0c120169237',
                        'body_bytes': 8326,
                        'body_sha256': '2e7d2435caf8eb6fe426868eb5395f4a60fb0df7844844b906d4731d8c33052a'},
                       {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/553#pullrequestreview-5570653952',
                        'created_at': '2026-10-19T11:09:19Z',
                        'role': 'actual_in_session_android_catalog_ux_receiver',
                        'disposition': 'ACCEPT_WITH_CONSTRAINTS',
                        'state': 'COMMENTED',
                        'source_revision': 'a6ca821954aa29b15813afd82238e0c120169237',
                        'body_bytes': 4310,
                        'body_sha256': '08db433bdb681d863ca439d2cfe0ea12a0a0ca5a0d7644b39b6d2eb458f65a7d'}],
 'receiving_identity_scope': 'Two disclosed delegated in-session technical roles through '
                             'CompleteDotTech. COMMENTED records are not responses attributed to '
                             'absent assignees or distinct-account formal APPROVED votes.',
 'implemented_subtasks': [{'text': 'Evaluate or request alternatives for the specific kind, role '
                                   'and replacement selection, comparing each candidate with the '
                                   'same build with that slot empty.',
                           'sha256': 'f983e17b173937fcc26125eed5d158f87239341291a249fffc6446d46fd062a7',
                           'disposition': 'ACCEPT_WITH_CONSTRAINTS'},
                          {'text': 'Attribute only candidate-introduced worsened findings to its '
                                   'verdict, preserving genuine candidate conflicts and separating '
                                   'unknown alternatives from compatible alternatives.',
                           'sha256': '887ab89dd4003f33c7d5692cc9c0863903c79255b97c7997abbb64bfc873d579',
                           'disposition': 'ACCEPT_WITH_CONSTRAINTS'},
                          {'text': 'Implement conservative filter defaults, counts for excluded '
                                   'options, explanatory rule links and an explicit path to '
                                   'inspect incompatible or unknown choices.',
                           'sha256': '1f6f71d4acec36a9af7d9c6ae3a882626913ee81551c9bf0845b9f4d925d1247',
                           'disposition': 'ACCEPT_WITH_CONSTRAINTS'},
                          {'text': 'Bind candidate results to build ID/revision, catalog/rules '
                                   'versions and role; discard stale results after edits and '
                                   'preserve an unresolved state during recalculation.',
                           'sha256': 'e8a4be33bae5c246cc431fc22b4dd449f8e89ca4e3b27ed62888ef270b403d7c',
                           'disposition': 'ACCEPT_WITH_CONSTRAINTS'}],
 'criterion_observations': [{'id': '96.1',
                             'text_sha256': '5ee53e7d77dccacc77f46744c834b6acd94371e47d9d50a4c2b0186e552f259a',
                             'disposition': 'ACCEPT_WITH_CONSTRAINTS',
                             'actual_named_controls': [{'identity': 'ai.pickyour.core.evaluation.DraftCompatibleOptionsTest.emptyStoredDraftKeepsFoundationFailureAndSeparatesNewFailureAndUnknown',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.DraftCompatibleOptionsTest',
                                                                                  'emptyStoredDraftKeepsFoundationFailureAndSeparatesNewFailureAndUnknown()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                                        'source_blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                                        'source_sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.core.evaluation.DraftCompatibleOptionsTest.currentCompleteTypedFindingsEqualTheExistingFrozenInterpreter',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.DraftCompatibleOptionsTest',
                                                                                  'currentCompleteTypedFindingsEqualTheExistingFrozenInterpreter()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                                        'source_blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                                        'source_sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.actualFrozenRulesExposeUnknownAndConflictingChoicesWithSeparateReasons',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'actualFrozenRulesExposeUnknownAndConflictingChoicesWithSeparateReasons'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'}],
                             'boundary': 'Compatible means no worsened affected-rule finding '
                                         'relative to this baseline. Inherited failure or unknown, '
                                         'unrepresented fields and unavailable external evidence '
                                         'do not become whole-draft validity or deployment '
                                         'readiness.'},
                            {'id': '96.2',
                             'text_sha256': '067e538a5d09abcdf509df9d4d0378472710e1828f4d9fbb9b7aa85e2fd36287',
                             'disposition': 'ACCEPT_WITH_CONSTRAINTS',
                             'actual_named_controls': [{'identity': 'ai.pickyour.core.evaluation.DraftCompatibleOptionsTest.emptyStoredDraftKeepsFoundationFailureAndSeparatesNewFailureAndUnknown',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.DraftCompatibleOptionsTest',
                                                                                  'emptyStoredDraftKeepsFoundationFailureAndSeparatesNewFailureAndUnknown()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                                        'source_blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                                        'source_sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.core.evaluation.DraftCompatibleOptionsTest.storedSecondaryNeverPromotesIntoAnEmptiedPrimaryAndRealConflictsRemain',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.DraftCompatibleOptionsTest',
                                                                                  'storedSecondaryNeverPromotesIntoAnEmptiedPrimaryAndRealConflictsRemain()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                                        'source_blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                                        'source_sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.core.evaluation.CompatibleOptionsTest.exactEmptyPrimaryKeepsSecondaryRoleAndExposesIndependentConflicts',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.CompatibleOptionsTest',
                                                                                  'exactEmptyPrimaryKeepsSecondaryRoleAndExposesIndependentConflicts()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/CompatibleOptionsTest.kt',
                                                        'source_blob': 'a9c77981332e28cc92764721ad9f8b16c7a04eac',
                                                        'source_sha256': '85b8225d2318240feac6ebc4f8f6a03fa86a8aa0d2f596aeaf9973f96238b995',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.blankSavedDraftKeepsInheritedFoundationFailureAndUsesActualCanonicalAdd',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'blankSavedDraftKeepsInheritedFoundationFailureAndUsesActualCanonicalAdd'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'}],
                             'boundary': 'The old frozen-Build API still rejects an '
                                         'invalid/minimum-size baseline; frozen schema and archive '
                                         'admission are intact. The private profile is not a new '
                                         'shared wire or historical report.'},
                            {'id': '96.3',
                             'text_sha256': 'b885bfebef67da42d3067169a417a6997cd4464cac6753e544a58b52f86a6344',
                             'disposition': 'ACCEPT_WITH_CONSTRAINTS',
                             'actual_named_controls': [{'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.actualFrozenRulesExposeUnknownAndConflictingChoicesWithSeparateReasons',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'actualFrozenRulesExposeUnknownAndConflictingChoicesWithSeparateReasons'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.pagedRecalculationClearsOldRowsAndPreservesCatalogBrowseContext',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'pagedRecalculationClearsOldRowsAndPreservesCatalogBrowseContext'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.core.evaluation.DraftCompatibleOptionsTest.rawFloatingCandidateOperandRejectsTheWholeBatchWithoutPartialOptions',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.DraftCompatibleOptionsTest',
                                                                                  'rawFloatingCandidateOperandRejectsTheWholeBatchWithoutPartialOptions()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                                        'source_blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                                        'source_sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.disabledOptionalTierIsWithheldUntilAnExplicitSavedActivation',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'disabledOptionalTierIsWithheldUntilAnExplicitSavedActivation'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'}],
                             'boundary': 'Counts are page-scoped and candidates follow the '
                                         'captured local catalog order. Rule details are local '
                                         'inspection, not a fetched remote evidence assertion. '
                                         'Explicitly choosing a shown non-compatible option still '
                                         'needs confirmation and ordinary command checks.'},
                            {'id': '96.4',
                             'text_sha256': '5dfb96099d40291ffab3abe11283b2242ecd717b8939bcfe4847528673f5025d',
                             'disposition': 'ACCEPT_WITH_CONSTRAINTS',
                             'actual_named_controls': [{'identity': 'ai.pickyour.core.evaluation.DraftCompatibleOptionsTest.secondaryReplacementPreservesTheOriginalPrimaryAndExactIncidentLinks',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.DraftCompatibleOptionsTest',
                                                                                  'secondaryReplacementPreservesTheOriginalPrimaryAndExactIncidentLinks()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                                        'source_blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                                        'source_sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.core.evaluation.CompatibleOptionsTest.secondaryScopedRuleEvaluatesTheRequestedSelectionInsteadOfThePrimary',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.CompatibleOptionsTest',
                                                                                  'secondaryScopedRuleEvaluatesTheRequestedSelectionInsteadOfThePrimary()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/CompatibleOptionsTest.kt',
                                                        'source_blob': 'a9c77981332e28cc92764721ad9f8b16c7a04eac',
                                                        'source_sha256': '85b8225d2318240feac6ebc4f8f6a03fa86a8aa0d2f596aeaf9973f96238b995',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.secondaryReplacementKeepsPrimaryExactAndRetainsRoleIdentitySettingsAndLinks',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'secondaryReplacementKeepsPrimaryExactAndRetainsRoleIdentitySettingsAndLinks'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.core.evaluation.DraftCompatibleOptionsTest.realStoredEditAndRoleChangeRetireEarlierResultsBeforeQueuedStartOrDelivery',
                                                        'original_xml_identity': ['ai.pickyour.core.evaluation.DraftCompatibleOptionsTest',
                                                                                  'realStoredEditAndRoleChangeRetireEarlierResultsBeforeQueuedStartOrDelivery()'],
                                                        'source_path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCompatibleOptionsTest.kt',
                                                        'source_blob': 'e9c0d48f51b6fa978c0d0bd71a9c91963aff1e81',
                                                        'source_sha256': '8810764e55accff1131e01b32e7863391c4efc8df77fba1ba689f722fb306cce',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.roleAndSettingsAbaEditsRetireQueuedPreparationAndOldRetryCannotReviveIt',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'roleAndSettingsAbaEditsRetireQueuedPreparationAndOldRetryCannotReviveIt'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'},
                                                       {'identity': 'ai.pickyour.android.preview.CompatibleOptionsActivityTest.actualStoreRevisionConflictCannotBeOverwrittenByAStillVisibleOlderChoice',
                                                        'original_xml_identity': ['ai.pickyour.android.preview.CompatibleOptionsActivityTest',
                                                                                  'actualStoreRevisionConflictCannotBeOverwrittenByAStillVisibleOlderChoice'],
                                                        'source_path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CompatibleOptionsActivityTest.kt',
                                                        'source_blob': '083e059ea294f2a9617703386eb1739dbf2bf598',
                                                        'source_sha256': 'cf13ab337f3f828918f2de08a5a65925fc79f50f2d0be8a5f8a8b5429dc9c277',
                                                        'prepared_outcome': 'PASS',
                                                        'post_main_outcome': 'PASS'}],
                             'boundary': 'Local store identity/revision stays local; it is not '
                                         'substituted for a shared Build revision. Compatibility '
                                         'output grants neither a save nor durable authority. The '
                                         'existing explicit user confirmation and canonical store '
                                         'remain mandatory.'}],
 'literal_test_requirements': ['Run shared scenarios for blank foundation, inherited failure, '
                               'newly introduced failure, unknown evidence and contradictory '
                               'deployment/model selections.',
                               'Change the active role and build revision while candidate results '
                               'are in flight; assert that stale results cannot populate the '
                               'picker or drive an add action.'],
 'consumed_prerequisites': [{'issue': 82,
                             'disposition': 'ACCEPT the delivered bounded local browser output for '
                                            'this consumer',
                             'consumed': 'Seven tiers/29 canonical kinds, exact admitted '
                                         'snapshot/item/version/hash, literal query, stable '
                                         'sorting/facets, ten-row pages and retained '
                                         'browser/viewport context. Picker calculations capture '
                                         'the same original catalog bytes and leave main browser '
                                         'state unchanged.',
                             'primary_comment_ids': [6245571480,
                                                     6245875151,
                                                     6247073533,
                                                     6259237927],
                             'current_source': 'PreviewCatalogBrowser, CatalogFixtureData, actual '
                                               'picker/catalog hash guards and paged-recalculation '
                                               'native control',
                             'not_consumed': 'Remote transport/pages/cancellation/retry/ordering, '
                                             'governed real catalog publication, installed-device '
                                             'or broader #82 acceptance.'},
                            {'issue': 84,
                             'disposition': 'ACCEPT the delivered frozen interpreter and exact '
                                            'binding/report-admission boundary for this consumer',
                             'consumed': 'Same 45-rule finding operation, actual accepted local '
                                         'no-external-evidence semantics, original '
                                         'frozen/reference and 26 operator/17 configuration '
                                         'compatibility checks, typed unsupported/unknown and '
                                         'current-result guards. The original #84 private archive '
                                         'interface remains intact; the new typed draft profile '
                                         'returns local findings only.',
                             'primary_comment_ids': [6260202471, 6260204544, 6260223696],
                             'record': 'docs/delivery/android/work-packages/09.01.md; existing '
                                       'effective ADR0010',
                             'not_consumed': 'Whole #84 status, graph pruning/execution, qualified '
                                             'external estimates, shared/export wire, source '
                                             'liveness, cost readiness, automatic archive '
                                             'generation or new resource policy.'}],
 'profile_limits': ['Accept only existing DraftSelections and non-null DraftWorkload values, '
                    'preserving exact decimals/integer types and absence. No free-text '
                    'objective/context/primaryModel is interpreted as v1 policy.',
                    'Accept optional activation as a conservative availability precondition: '
                    'unknown activation, enabled selections in disabled tiers and proposed enabled '
                    'candidates in disabled tiers withhold the batch. Explicit enabled-but-empty '
                    'slots remain fillable; no tier is activated by comparison.',
                    'No shared Build ID/revision/owner/locks/policy/timestamp, Graph/Pricebook '
                    'operand, calibrated resource fact, cost value or reusable report authority is '
                    'invented.',
                    'Accept the private profile and current interpreter descriptor for this '
                    'consumer through this actual delegated data/Research review. Frozen public '
                    'admission remains unchanged; this is no v1 amendment or new service endpoint.',
                    'Inherited unknowns/failures remain inspectable and may coexist with a '
                    'compatible candidate when no affected rule worsens. That precise relative '
                    'verdict, not universal compatibility, is accepted.',
                    'COMPATIBLE is only the absence of a newly worsened affected-rule finding in '
                    'the exact slot-empty comparison. It is not whole-draft validity, deployment '
                    'readiness or an evidence/cost pass.',
                    'Candidate buckets remain separate from per-rule outcomes. Default '
                    'compatible-only filters, current ten-row page counts and explicit reason '
                    'inspection preserve unknown/incompatible alternatives without silently saving '
                    'them.',
                    'The exact kind, role, replacement identity, local store/revision/hash, '
                    'catalog/rules bytes, form epoch and opaque request/ticket must stay current. '
                    'Every save still uses one-use confirmation and existing canonical '
                    'compare-and-swap.',
                    'Frozen shared Build/archive admission, including minItems and absent versus '
                    'explicit-null locks, is unchanged. The private incomplete-draft profile '
                    'creates no shared identity, policy, archive or external-evidence authority.',
                    'Named #82/#84 outputs are consumed for this local receiver. Their other '
                    'criteria, transitive #37, native dependency edges, Project fields, assignees '
                    'and release/device gates are unchanged.',
                    'Original SDK28 Robolectric and unsigned-package evidence do not establish '
                    'installed-device behavior, TalkBack, real transport, source rights, '
                    'production configuration, hardware calibration or release qualification.'],
 'signed_delivery': {'revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                     'tree': '8d4dec5720c64560e7b08032dc6efb4db9575dad',
                     'parents': ['517025db301785f8e3689abdeeb47fc8d09b9afc'],
                     'merged_at': '2026-10-19T15:11:30Z',
                     'signature_verified': True,
                     'merge_method': 'ordinary_expected_head_squash',
                     'expected_source': 'fd1a62ce1db00bf0690a481314caa93b2ad3f59b',
                     'merge_actor': 'CompleteDotTech',
                     'pr_state': 'merged',
                     'receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/338#issuecomment-6270594305'},
 'prepared_observation': {'source_revision': 'fd1a62ce1db00bf0690a481314caa93b2ad3f59b',
                          'execution_revision': '1056ce77b3bb138e7c9df78dc593b0a82978de0d',
                          'execution_tree': '8d4dec5720c64560e7b08032dc6efb4db9575dad',
                          'execution_parents': ['517025db301785f8e3689abdeeb47fc8d09b9afc',
                                                'fd1a62ce1db00bf0690a481314caa93b2ad3f59b'],
                          'signature_verified': True,
                          'event': 'pull_request',
                          'attempt': 1,
                          'run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39272677303',
                          'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39272676968',
                          'jobs': [{'id': '118128816098',
                                    'name': 'Frozen contracts and JVM core',
                                    'run_id': '39272677303',
                                    'started_at': '2026-10-19T14:51:39Z',
                                    'completed_at': '2026-10-19T14:55:45Z',
                                    'conclusion': 'success',
                                    'successful_steps': 28},
                                   {'id': '118128816642',
                                    'name': 'Unsigned synthetic Android preview',
                                    'run_id': '39272677303',
                                    'started_at': '2026-10-19T14:53:42Z',
                                    'completed_at': '2026-10-19T15:00:27Z',
                                    'conclusion': 'success',
                                    'successful_steps': 60},
                                   {'id': '118128814056',
                                    'name': 'Pinned secret scanning',
                                    'run_id': '39272676968',
                                    'started_at': '2026-10-19T14:51:31Z',
                                    'completed_at': '2026-10-19T14:51:43Z',
                                    'conclusion': 'success',
                                    'successful_steps': 7}],
                          'all_original_steps_concluded_success': True,
                          'successful_steps': 95,
                          'core_cases': 5853,
                          'core_suites': 109,
                          'core_case_identities_sha256': '36b695a42ffe3a193a82677bbc29404b897e92b11e940908bd82816e0ea46148',
                          'simulated_android_cases': 512,
                          'simulated_android_suites': 49,
                          'native_case_identity_encoding': 'Sorted class/name arrays; canonical '
                                                           'JSON sort_keys=True, '
                                                           'ensure_ascii=True, '
                                                           'separators=(comma,colon), no newline.',
                          'native_case_identities_sha256': '226a98b232ab7a14ccac6f168505eba09c0f43b88542f647c15cbe68e0d835f9',
                          'failures': 0,
                          'errors': 0,
                          'skips': 0,
                          'duplicate_cases': 0,
                          'native_attendance_programs': 41,
                          'core_host_executions': 333,
                          'native_host_executions': 148,
                          'core_host_suite_counts': [21, 5, 7, 14, 14, 100, 120, 24, 28],
                          'native_host_suite_counts': [120, 5, 5, 18],
                          'host_observation_scope': 'Original suite executions; each 120-case '
                                                    'ledger footer is nonverbose and does not '
                                                    'provide individually named outcomes. No '
                                                    'repeated test execution during qualification.',
                          'lint_warnings': 10,
                          'lint_errors': 0,
                          'scanner': {'reachable_commits': 248,
                                      'raw_exit_code': 10,
                                      'raw_findings': [{'commit': '899efec0a740f91236d76c309dc13dc66e788099',
                                                        'file': 'tests/fixtures/android-api-consumer-intake-v1.json',
                                                        'line': 5,
                                                        'rule_id': 'generic-api-key'},
                                                       {'commit': '388d6ef7e4ed39bdd418e7e662fc65b2a7b1d5f3',
                                                        'file': 'tests/fixtures/android-api-consumer-intake-v1.json',
                                                        'line': 5,
                                                        'rule_id': 'generic-api-key'}],
                                      'reviewed_findings': [{'commit': '899efec0a740f91236d76c309dc13dc66e788099',
                                                             'disposition': 'android36-public-frozen-contract-checksum',
                                                             'file': 'tests/fixtures/android-api-consumer-intake-v1.json',
                                                             'file_blob': 'd2f15c794bf5a4b73b6dbb843006434f0bac84e2',
                                                             'file_sha256': '48df93adc749ef07f81abe37e4d54a2a412558d0fd8c305ec6a3ab36b29e1879',
                                                             'line': 5,
                                                             'rule_id': 'generic-api-key'},
                                                            {'commit': '388d6ef7e4ed39bdd418e7e662fc65b2a7b1d5f3',
                                                             'disposition': 'android36-public-frozen-contract-checksum',
                                                             'file': 'tests/fixtures/android-api-consumer-intake-v1.json',
                                                             'file_blob': 'd2f15c794bf5a4b73b6dbb843006434f0bac84e2',
                                                             'file_sha256': '48df93adc749ef07f81abe37e4d54a2a412558d0fd8c305ec6a3ab36b29e1879',
                                                             'line': 5,
                                                             'rule_id': 'generic-api-key'}],
                                      'unresolved_count': 0,
                                      'unresolved_findings': [],
                                      'control_methods': 28,
                                      'policy': 'reviewed-public-checksum-v1',
                                      'limits': ['pattern detection is not proof that no secret '
                                                 'exists',
                                                 'unreachable branches, local files and external '
                                                 'systems are not scanned',
                                                 'no live credential validation or production '
                                                 'credential rotation',
                                                 'not a dependency vulnerability scan, SBOM or '
                                                 'release acceptance']},
                          'original_artifacts': [{'id': '12196705717',
                                                  'name': 'core-verification-1056ce77b3bb138e7c9df78dc593b0a82978de0d',
                                                  'bytes': 1802561,
                                                  'sha256': '23e0ea07c544060097c09cc38c6f2684c81e8d84307e45ef43cfc60d920bf1ec'},
                                                 {'id': '12196671543',
                                                  'name': 'android-preview-1056ce77b3bb138e7c9df78dc593b0a82978de0d',
                                                  'bytes': 3350916,
                                                  'sha256': '785043cde3ea68df5eaab83ed462c515f9399fcc36b97a988058eaaf228aec9f'},
                                                 {'id': '12197200957',
                                                  'name': 'android-runtime-inventory-1056ce77b3bb138e7c9df78dc593b0a82978de0d',
                                                  'bytes': 4494645,
                                                  'sha256': 'c063d42561c26729bc76518f032bb72949b57614974a879169a14d613a2d7d4e'},
                                                 {'id': '12195069788',
                                                  'name': 'secret-scan-1056ce77b3bb138e7c9df78dc593b0a82978de0d',
                                                  'bytes': 1287,
                                                  'sha256': '41a45261fdb2a8f50e884dfe6e2ae9dbcb461cf5131d813078766f7178207204'}],
                          'original_logs': [{'job': '118128816098',
                                             'name': 'core',
                                             'bytes': 124588,
                                             'sha256': '43dda8e9a9d60aca8ff5b2b5cba8729c73ecf06b35553384f129df622c4bda6d'},
                                            {'job': '118128816642',
                                             'name': 'preview',
                                             'bytes': 232519,
                                             'sha256': '81bf5fdd1b028648b8239ecc49d4d7c253d115fabf430fe2785e882671c48d1f'},
                                            {'job': '118128814056',
                                             'name': 'secret-scan',
                                             'bytes': 39366,
                                             'sha256': '5c32160ce43c972d57f942843d44abf4713ec90a002a0e06c8b571b0259859e4'}],
                          'outer_archive_members': {'core': 255,
                                                    'native': 112,
                                                    'runtime': 8,
                                                    'scanner': 1},
                          'core_compiled_output': {'bytes': 1403231,
                                                   'sha256': '74df4cd89106903cfb91551071f14bbe0f6bbb710ce7dc7a1093079cb488119e',
                                                   'members': 796},
                          'original_native_inventory_outputs': [{'file': 'core/build/libs/core.jar',
                                                                 'bytes': 1403231,
                                                                 'sha256': 'd3bc19b97b6f97f1ee3dee3f4a14728e9f53d728b0b87042f9fa9d52e9b1849d'},
                                                                {'file': 'preview/build/outputs/apk/debug/preview-debug-unsigned.apk',
                                                                 'bytes': 3268267,
                                                                 'sha256': '2fe6e7e36fa8b9c921bf678d7fe51fbc671a2f97edb71850274ee80e7904bff9'}],
                          'input_and_policy_coverage': {'core_pins': 8,
                                                        'native_pins': 10,
                                                        'packaged_assets': 17,
                                                        'core_spdx_packages': 23,
                                                        'core_spdx_relationships': 44,
                                                        'native_spdx_packages': 48,
                                                        'native_spdx_relationships': 91,
                                                        'wire_observations': 223,
                                                        'private_migration_observations': 34,
                                                        'private_migration_guards': 3,
                                                        'configuration_cases': 17,
                                                        'frozen_payloads': 43,
                                                        'frozen_manifests': 2,
                                                        'package_policy_cases': 12,
                                                        'actual_java_source_identity_controls': 3,
                                                        'finite_configuration_bytes': 451},
                          'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/553#issuecomment-6270529913',
                          'qualification_proofs': {'core': {'bytes': 1320740,
                                                            'sha256': 'dd302fb9ff4203ce2137dec0535d73be0818473e74573ece74b2f24d7d33df6e'},
                                                   'native_package_scanner': {'bytes': 623100,
                                                                              'sha256': '8b2901a660b19a6efc00b065c2abb022ef576371791a8ecd7b19d673322acebf'}},
                          'qualification_scope': 'Complete original source/XML/build/unsigned '
                                                 'package/inventory/SPDX/guard/scanner '
                                                 'observations; no installed-device, real-service, '
                                                 'production data or release acceptance.'},
 'post_main_observation': {'source_revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                           'execution_revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                           'execution_tree': '8d4dec5720c64560e7b08032dc6efb4db9575dad',
                           'execution_parents': ['517025db301785f8e3689abdeeb47fc8d09b9afc'],
                           'signature_verified': True,
                           'event': 'push',
                           'attempt': 1,
                           'run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39275747663',
                           'scanner_run_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39275747768',
                           'jobs': [{'id': '118139480591',
                                     'name': 'Frozen contracts and JVM core',
                                     'run_id': '39275747663',
                                     'started_at': '2026-10-19T15:12:41Z',
                                     'completed_at': '2026-10-19T15:17:52Z',
                                     'conclusion': 'success',
                                     'successful_steps': 28},
                                    {'id': '118139481051',
                                     'name': 'Unsigned synthetic Android preview',
                                     'run_id': '39275747663',
                                     'started_at': '2026-10-19T15:12:41Z',
                                     'completed_at': '2026-10-19T15:20:32Z',
                                     'conclusion': 'success',
                                     'successful_steps': 60},
                                    {'id': '118139480949',
                                     'name': 'Pinned secret scanning',
                                     'run_id': '39275747768',
                                     'started_at': '2026-10-19T15:12:21Z',
                                     'completed_at': '2026-10-19T15:12:38Z',
                                     'conclusion': 'success',
                                     'successful_steps': 7}],
                           'all_original_steps_concluded_success': True,
                           'successful_steps': 95,
                           'core_cases': 5853,
                           'core_suites': 109,
                           'core_case_identities_sha256': '36b695a42ffe3a193a82677bbc29404b897e92b11e940908bd82816e0ea46148',
                           'simulated_android_cases': 512,
                           'simulated_android_suites': 49,
                           'native_case_identity_encoding': 'Sorted class/name arrays; canonical '
                                                            'JSON sort_keys=True, '
                                                            'ensure_ascii=True, '
                                                            'separators=(comma,colon), no newline.',
                           'native_case_identities_sha256': '226a98b232ab7a14ccac6f168505eba09c0f43b88542f647c15cbe68e0d835f9',
                           'failures': 0,
                           'errors': 0,
                           'skips': 0,
                           'duplicate_cases': 0,
                           'native_attendance_programs': 41,
                           'core_host_executions': 333,
                           'native_host_executions': 148,
                           'core_host_suite_counts': [21, 5, 7, 14, 14, 100, 120, 24, 28],
                           'native_host_suite_counts': [120, 5, 5, 18],
                           'host_observation_scope': 'Original suite executions; each 120-case '
                                                     'ledger footer is nonverbose and does not '
                                                     'provide individually named outcomes. No '
                                                     'repeated test execution during '
                                                     'qualification.',
                           'lint_warnings': 10,
                           'lint_errors': 0,
                           'scanner': {'reachable_commits': 241,
                                       'raw_exit_code': 10,
                                       'raw_findings': [{'commit': '388d6ef7e4ed39bdd418e7e662fc65b2a7b1d5f3',
                                                         'file': 'tests/fixtures/android-api-consumer-intake-v1.json',
                                                         'line': 5,
                                                         'rule_id': 'generic-api-key'}],
                                       'reviewed_findings': [{'commit': '388d6ef7e4ed39bdd418e7e662fc65b2a7b1d5f3',
                                                              'disposition': 'android36-public-frozen-contract-checksum',
                                                              'file': 'tests/fixtures/android-api-consumer-intake-v1.json',
                                                              'file_blob': 'd2f15c794bf5a4b73b6dbb843006434f0bac84e2',
                                                              'file_sha256': '48df93adc749ef07f81abe37e4d54a2a412558d0fd8c305ec6a3ab36b29e1879',
                                                              'line': 5,
                                                              'rule_id': 'generic-api-key'}],
                                       'unresolved_count': 0,
                                       'unresolved_findings': [],
                                       'control_methods': 28,
                                       'policy': 'reviewed-public-checksum-v1',
                                       'limits': ['pattern detection is not proof that no secret '
                                                  'exists',
                                                  'unreachable branches, local files and external '
                                                  'systems are not scanned',
                                                  'no live credential validation or production '
                                                  'credential rotation',
                                                  'not a dependency vulnerability scan, SBOM or '
                                                  'release acceptance']},
                           'original_artifacts': [{'id': '12197722161',
                                                   'name': 'core-verification-8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                                                   'bytes': 1804368,
                                                   'sha256': '67815a32c30fdf0f8e3e5a32899a8892519463aa4c8fa4d4678ca89e1d6c2519'},
                                                  {'id': '12197767569',
                                                   'name': 'android-preview-8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                                                   'bytes': 3351232,
                                                   'sha256': '3b63d19d4b7c0f43252c8743c875103eb84f9d3ce8cba0714b71e92f7a20e57d'},
                                                  {'id': '12197702505',
                                                   'name': 'android-runtime-inventory-8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                                                   'bytes': 4494317,
                                                   'sha256': '4108b9e60a825d123877c477f1e0f698e7ede7b8b4cea9fce7183605394c99d7'},
                                                  {'id': '12196588765',
                                                   'name': 'secret-scan-8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                                                   'bytes': 1241,
                                                   'sha256': '42ff868a32bd8fd7a974035407b6caa962430cbefb1998d86f7cf1218d5e3dda'}],
                           'original_logs': [{'job': '118139480591',
                                              'name': 'core',
                                              'bytes': 123487,
                                              'sha256': '907986dd948d8879061a610d0da55dc8b38f3ae81d4c2d8c4ca47c499bfc5152'},
                                             {'job': '118139481051',
                                              'name': 'preview',
                                              'bytes': 231447,
                                              'sha256': '8836d0f06023787dd582c66344f6fbce766a1cb3d85e84594243eb6e4f2c3a08'},
                                             {'job': '118139480949',
                                              'name': 'secret-scan',
                                              'bytes': 38032,
                                              'sha256': 'a102afacb3b9ee916c500be16915114ff8a41ab6f42330935d0e626c3f4e9c6e'}],
                           'outer_archive_members': {'core': 255,
                                                     'native': 112,
                                                     'runtime': 8,
                                                     'scanner': 1},
                           'core_compiled_output': {'bytes': 1403231,
                                                    'sha256': '11ce7969e0d9293c51053070e6e60643e393d9cb4c555ed66824290776cd75aa',
                                                    'members': 796},
                           'original_native_inventory_outputs': [{'file': 'core/build/libs/core.jar',
                                                                  'bytes': 1403231,
                                                                  'sha256': '03b2f1c994a2eca876168d6813eba920b841fc718338a358e63fbc9053c7c907'},
                                                                 {'file': 'preview/build/outputs/apk/debug/preview-debug-unsigned.apk',
                                                                  'bytes': 3268267,
                                                                  'sha256': '2fe6e7e36fa8b9c921bf678d7fe51fbc671a2f97edb71850274ee80e7904bff9'}],
                           'input_and_policy_coverage': {'core_pins': 8,
                                                         'native_pins': 10,
                                                         'packaged_assets': 17,
                                                         'core_spdx_packages': 23,
                                                         'core_spdx_relationships': 44,
                                                         'native_spdx_packages': 48,
                                                         'native_spdx_relationships': 91,
                                                         'wire_observations': 223,
                                                         'private_migration_observations': 34,
                                                         'private_migration_guards': 3,
                                                         'configuration_cases': 17,
                                                         'frozen_payloads': 43,
                                                         'frozen_manifests': 2,
                                                         'package_policy_cases': 12,
                                                         'actual_java_source_identity_controls': 3,
                                                         'finite_configuration_bytes': 451},
                           'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/553#issuecomment-6270969615',
                           'qualification_proofs': {'core': {'bytes': 1321292,
                                                             'sha256': 'c492266679ebfed25a433f0dfd51a00fd7e8c0dd42cd6a221f54915c1771559f'},
                                                    'native_package_scanner': {'bytes': 613539,
                                                                               'sha256': 'df65cb6ebeb517623b4d3fe63a2d0a99e170a98ade9ac980445baf555f4cf3e2'}},
                           'qualification_scope': 'Complete original source/XML/build/unsigned '
                                                  'package/inventory/SPDX/guard/scanner '
                                                  'observations; no installed-device, '
                                                  'real-service, production data or release '
                                                  'acceptance.'},
 'same_tree_execution_continuity': {'prepared_revision': '1056ce77b3bb138e7c9df78dc593b0a82978de0d',
                                    'main_revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                                    'tree': '8d4dec5720c64560e7b08032dc6efb4db9575dad',
                                    'all_core_identities_retained': 5853,
                                    'all_native_identities_retained': 512,
                                    'added_cases': 0,
                                    'removed_cases': 0,
                                    'scope': 'Two distinct original runs on the exact same source '
                                             'tree, each fully qualified. Artifact containers, '
                                             'compilation timestamps and reachable scanner '
                                             'ancestry retain their own original identities.'},
 'preserved_failures_and_history': [{'source_revision': '454e53b5156f862d4568cec790843e436a15a5ef',
                                     'scope': 'Original optional-pricebook three-case overlay: '
                                              'schema-valid omitted lock failed at the production '
                                              'null assertion; present-lock and explicit-null '
                                              'rejection controls passed. Same input overlay then '
                                              'passed after the independently reviewed nullable '
                                              'identity correction.',
                                     'prior_failures': 1,
                                     'control_passes': 2,
                                     'corrected_passes': 3,
                                     'no_external_donor_attribution': True},
                                    {'source_revision': '4e439de0b3b41df2273fd3293085019a3a819368',
                                     'tree': '71860e482ca7c16a96feec0e7621039f708447d1',
                                     'scope': 'Separate original normal-source-set portable causal '
                                              'replay; all three committed methods passed. Not the '
                                              'earlier external-overlay execution.',
                                     'passes': 3,
                                     'committed_test_blob': 'abe7f60a70022f01b8a95ce53a05efa7b8db49b2'},
                                    {'source_revision': 'b0d638ce6cef2f8d47f1cb204efec31b388c2501',
                                     'tree': '408ca42c46c54f81094c4f2b45b23ef67c8de120',
                                     'scope': 'The command missing -PandroidPreview=true stopped '
                                              'before tests; the corrected command ran the '
                                              'original eleven real native picker simulations. The '
                                              'pre-test stop is preserved and is not a passing '
                                              'execution.',
                                     'pre_test_stop_preserved': True,
                                     'subsequent_passes': 11},
                                    {'record_revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
                                     'record_path': 'docs/delivery/android/work-packages/07.02.md',
                                     'record_sha256': '7ea1ac9ff3aee360e1724b89f0ed2417aa052f49cece9fbd180dcfabbf8267a1',
                                     'scope': 'The complete dated07.02 record retains 0f05, b0, '
                                              'd0, the original '
                                              'nullable/viewport/compile/bootstrap failures, seven '
                                              'd0 artifact/log pins and both original role '
                                              'decisions byte-for-byte. Later1494/9db/bb/1056 '
                                              'observations remain at their exact public #553 '
                                              'receipt/source histories; none becomes a new '
                                              'installed-device result.'}],
 'preserved_prior_ledger': {'schema_version': 11,
                            'canonical_sha256': '1698ebb2a1c802a80e3562659afeee3ee828e81ee83e443b1bb9eafe9d9b3a23',
                            'raw_sha256': '7b7a9ce256d4cb0e6766519c95c63bbe50f888c71fc7aaf5c7066fd6670a6152',
                            'original_issue96_sha256': '331ec9dda81d118c0976676694e348235633da33e49ff2f41a7275e6fd7b7246',
                            'other_issue_objects': 128,
                            'evidence_objects': 28,
                            'literal_criteria': 441,
                            'scope': 'Entire prior-v11 object, including unknown-field rejection '
                                     'in the new layer, original metadata and all partial/history '
                                     'fields; earlier validators and version-specific inverses '
                                     'remain unchanged.'},
 'strict_history_admission': 'Version12 deliberately rejects unreviewed extra ledger fields '
                             'through the complete prior-v11 identity. The finite/Unicode JSON '
                             'decoder and optional source-capture parsing retain their prior '
                             'behavior.'}
IMPLEMENTATION_96_EVIDENCE = {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/553',
 'observed_state': 'merged',
 'revision': '8bedd80dcc0b3cdd9756785c57cef0e54211c63b',
 'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/8bedd80dcc0b3cdd9756785c57cef0e54211c63b/docs/delivery/android/work-packages/07.02.md',
 'scope_limit': 'Four reviewed Android #96 criteria in '
                'local-stored-draft-slot-empty-diagnostics/1: real Add/exact-role replacement, '
                'separate relative candidate verdicts/reasons and immutable currentness/CAS '
                'guards, with the nullable-lock causal correction and effective ADR0013/07.02. Two '
                'separately qualified5853-Core/512-simulated-native original receiving/main runs; '
                'no whole-draft validity, shared wire, qualified external evidence, upstream-issue '
                'completion, installed-device or release acceptance.'}

PRIOR_V11_LEDGER_SHA256 = '1698ebb2a1c802a80e3562659afeee3ee828e81ee83e443b1bb9eafe9d9b3a23'
PRIOR_96_ISSUE_SHA256 = '331ec9dda81d118c0976676694e348235633da33e49ff2f41a7275e6fd7b7246'
IMPLEMENTATION_96_CRITERIA = [
    '5ee53e7d77dccacc77f46744c834b6acd94371e47d9d50a4c2b0186e552f259a',
    '067e538a5d09abcdf509df9d4d0378472710e1828f4d9fbb9b7aa85e2fd36287',
    'b885bfebef67da42d3067169a417a6997cd4464cac6753e544a58b52f86a6344',
    '5dfb96099d40291ffab3abe11283b2242ecd717b8939bcfe4847528673f5025d',
]
PRIOR_96_FIELDS = {
    'status': 'incomplete',
    'completion_evidence': [],
    'remaining_engineering': 'Implement and validate this exact criterion in the selected scope; record negative/recovery cases and receiving evidence.',
    'external_prerequisites': [],
    'evidence_relation': 'issue_context_only; inspect artifact scope before attributing any criterion result',
}
LEDGER_PURPOSE_V12 = 'Living evidence index: 25 reviewed documentary/design criteria for Android #2/#20/#23/#24/#25/#34/#35/#47, 11 reviewed implementation criteria for #26/#84/#96, the single reviewed import-integrity criterion 21.2 and 3 reviewed bounded support criteria for #33 are complete; 401 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies.'


def reconstruct_prior_v11(ledger):
    """Reverse only the reviewed #96 amendment after fixed outcome admission.

    Preserve every unlisted field, earlier receipt and partial context in the
    deep copy. The complete prior-v11 identity below is an additional historical
    boundary; this helper cannot grant acceptance or normalize an unknown field.
    """
    previous = copy.deepcopy(ledger)
    issue = previous['issues'][95]
    issue.pop('accepted_implementation_outcome')
    issue['canonical_acceptance_record'] = copy.deepcopy(
        IMPLEMENTATION_96_ACCEPTANCE['historical_acceptance_record'])
    for criterion in issue['criteria']:
        criterion.update(copy.deepcopy(PRIOR_96_FIELDS))
    previous['evidence'].pop('pr-553')
    previous['schema_version'] = 11
    previous['purpose'] = LEDGER_PURPOSE_V11
    return previous


def _validate_prior_v12(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 12,
            'unsupported schema version')
    require(ledger['purpose'] == LEDGER_PURPOSE_V12,
            'unsupported ledger completion summary')
    issues = ledger['issues']
    numbers = [issue['number'] for issue in issues]
    require(all(type(number) is int for number in numbers),
            'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)),
            'expected unique ordered issue numbers 1–129')
    issue = issues[95]
    require('accepted_design_outcome' not in issue, 'unsupported design acceptance: 96')
    require('accepted_support_outcome' not in issue, 'unsupported support acceptance: 96')
    require(issue['canonical_acceptance_record'] == {
        'repository': REPOSITORY, 'path': IMPLEMENTATION_96_ACCEPTANCE['record_path']},
        'unreviewed acceptance coordinates or placement: 96')
    require(json_digest(issue.get('accepted_implementation_outcome')) ==
            json_digest(IMPLEMENTATION_96_ACCEPTANCE),
            'missing or changed reviewed implementation acceptance: 96')
    require([c['text_sha256'] for c in issue['criteria']] == IMPLEMENTATION_96_CRITERIA,
            'reviewed implementation criterion set changed: 96')
    require(json_digest(ledger['evidence'].get('pr-553')) ==
            json_digest(IMPLEMENTATION_96_EVIDENCE),
            'implementation receipt is not the reviewed merged artifact: 96')
    for index, criterion in enumerate(issue['criteria'], 1):
        key = f'96.{index}'
        require(criterion['id'] == key, f'criterion identity/order mismatch: {key}')
        require(criterion['status'] == 'complete' and
                criterion['completion_evidence'] == ['pr-553'],
                f'missing reviewed implementation completion evidence: {key}')
        require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                f'completed criterion still has unfinished requirements: {key}')
        require(criterion['evidence_relation'] == COMPLETE_IMPLEMENTATION_RELATION,
                f'unsupported implementation completion attribution: {key}')

    previous_v11 = reconstruct_prior_v11(ledger)
    # Keep the full previous validator and its diagnostic branches unchanged.
    # Its own v10/v9/v8 reconstruction is still explicit and version-specific.
    _validate_prior_v11(previous_v11, source)
    require(json_digest(previous_v11['issues'][95]) == PRIOR_96_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #96 context and partial history')
    require(json_digest(previous_v11) == PRIOR_V11_LEDGER_SHA256,
            'unreviewed change to complete prior v11 ledger history')


# Metadata-only amendment over the complete accepted v12 ledger.
METADATA_PRIOR_V12_SHA256 = '0b213fb07461765844b38ef9901674df4f397dd59924312f2a78ba3f5251e3c4'
METADATA_PRIOR_V12_BYTES = 609028
METADATA_PRIOR_V12_RAW_SHA256 = 'cce0a733e94fc6e38bf4c8f4956a7e0059d42e9324c23e81fbece8f67ddf1551'

PRIOR_METADATA_REPOSITORY = 'Pick-Your-AI/pickyour.ai'

METADATA_PLACEMENTS = {
    48: {
        'path': 'docs/delivery/android/work-packages/02.01.md',
        'decision_path': 'docs/decisions/0008-android-48-acceptance-record.md',
        'decision_blob': '904da4ee38ec00ae57eb5ece2fbc4dd4a222eb97',
        'effective_revision': '710d337fed2004689993c0b8bc01f12de780b04c',
    },
    59: {
        'path': 'docs/delivery/android/work-packages/02.05.md',
        'decision_path': 'docs/decisions/0011-android-59-acceptance-record.md',
        'decision_blob': 'de362c1ee08d36c8feb1ef6368ab4d73a376f9ab',
        'effective_revision': '146413ff168583f4c2b0df87ab41d36e57f8ea85',
    },
}

PRIOR_57_REMAINING = 'Implement explicit environment/configuration failures and package-diagnostic secret checks.'

METADATA_57_REMAINING = (
    'Complete approved staging/production endpoint, identity and capability handoffs and qualification '
    'of each selected real variant; reconcile the remaining literal configuration and diagnostic '
    'receiving requirements. The finite synthetic admission/failure/fixture-exclusion behavior and '
    'loaded bundled identity are already delivered scoped outputs, not evidence of real '
    'configuration, installed execution or release.'
)

METADATA_57_REFS = {
    '57.1': ['pr-136', 'pr-515', 'pr-502'],
    '57.2': ['pr-136', 'pr-502'],
    '57.3': ['pr-136', 'pr-502'],
}

METADATA_57_EVIDENCE = {
    'pr-515': {
        'pr_url': f'{BASE}/pull/515',
        'observed_state': 'merged',
        'revision': '1c51313d7499118a31b0d3a5af322aed5fa64a59',
        'artifact_url': f'{BASE}/blob/1c51313d7499118a31b0d3a5af322aed5fa64a59/docs/LOCAL_PREVIEW_IDENTITY.md',
        'scope_limit': 'Loaded bundled identity/version observability for criterion 57.1 only. Historical delivery and receipt 6259700859 retain their actual scope; no configured staging/production endpoint, credential, installed-device, release or full criterion completion evidence.',
    },
    'pr-502': {
        'pr_url': f'{BASE}/pull/502',
        'observed_state': 'merged',
        'revision': '146413ff168583f4c2b0df87ab41d36e57f8ea85',
        'artifact_url': f'{BASE}/blob/146413ff168583f4c2b0df87ab41d36e57f8ea85/docs/ENVIRONMENT_CONFIGURATION.md',
        'scope_limit': 'Finite synthetic environment admission, explicit missing-configuration/lifecycle rejection and preview-fixture exclusion for criteria 57.1–57.3. Delivered source and post-main receipt 6267418772 retain decoded-log/source/API/upload qualification with binary contents uninspected; no configured real variant, installed-device, release or whole-criterion acceptance.',
    },
}

def reconstruct_prior_v12(ledger):
    """Reverse only the fixed metadata cells after validate() admits them.

    Preserve every other field, including unknown issue/criterion properties,
    for the unchanged historical validators and complete prior-v12 digest.
    This deep-copy projection does not authorize its input or complete work.
    """
    previous = copy.deepcopy(ledger)
    for number in METADATA_PLACEMENTS:
        previous['issues'][number - 1]['canonical_acceptance_record']['repository'] = (
            PRIOR_METADATA_REPOSITORY)
    for criterion in previous['issues'][56]['criteria']:
        criterion['partial_evidence_refs'] = ['pr-136']
        criterion['remaining_engineering'] = PRIOR_57_REMAINING
    for evidence_ref in METADATA_57_EVIDENCE:
        previous['evidence'].pop(evidence_ref)
    previous['schema_version'] = 12
    return previous

def _validate_prior_v13(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 13,
            'unsupported schema version')
    require(ledger['purpose'] == LEDGER_PURPOSE_V12,
            'unsupported ledger completion summary')
    issues = ledger['issues']
    numbers = [issue['number'] for issue in issues]
    require(all(type(number) is int for number in numbers),
            'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)),
            'expected unique ordered issue numbers 1–129')
    for number, placement in METADATA_PLACEMENTS.items():
        require(issues[number - 1]['canonical_acceptance_record'] == {
            'repository': REPOSITORY, 'path': placement['path']},
            f'unreviewed acceptance coordinates or metadata placement: {number}')
    # Preserve the earlier rejection order for forged #57 acceptance before
    # checking metadata that such a forged outcome may also have replaced.
    for kind in ('support', 'implementation', 'design'):
        require(f'accepted_{kind}_outcome' not in issues[56],
                f'unsupported {kind} acceptance: 57')
    criteria = issues[56]['criteria']
    require([criterion['id'] for criterion in criteria] == list(METADATA_57_REFS),
            'metadata criterion identity/order mismatch: 57')
    for criterion in criteria:
        key = criterion['id']
        require(criterion['partial_evidence_refs'] == METADATA_57_REFS[key],
                f'unreviewed metadata evidence references: {key}')
        require(criterion['remaining_engineering'] == METADATA_57_REMAINING,
                f'unreviewed metadata remaining work: {key}')
    for evidence_ref, expected in METADATA_57_EVIDENCE.items():
        require(json_digest(ledger['evidence'].get(evidence_ref)) == json_digest(expected),
                f'metadata evidence is not the fixed scoped artifact: {evidence_ref}')

    previous_v12 = reconstruct_prior_v12(ledger)
    # Preserve the complete stricter preceding validator and its diagnostics.
    # The entire reconstructed object is fixed; no extra metadata is discarded.
    _validate_prior_v12(previous_v12, source)
    require(json_digest(previous_v12) == METADATA_PRIOR_V12_SHA256,
            'unreviewed change to complete prior v12 ledger history')

# Exact #37 implementation receiving and actual signed delivery. Input data
# cannot enlarge this reviewed allowlist or rewrite any prior ledger history.
IMPLEMENTATION_37_ACCEPTANCE = {'classification': 'reviewed_implementation_outcome',
 'scope': 'selected_synthetic_normalized_placement_and_relationship_consumer',
 'profile': 'android-placement-receiving/0.1',
 'criteria': ['37.1', '37.2', '37.3'],
 'implemented_subtasks': ['Resolve component deployment modes, role-bound relationships and '
                          'adapter/base inheritance from the existing '
                          'Pick-Your-AI/pick-your-ai-research#40 queue.',
                          'Specify supported CPU/unified/discrete memory placement and allocations '
                          'without treating aggregate advertised VRAM as usable capacity.',
                          'Define shared versus independent input/output budgets and route '
                          'overrides without an invented combined limit.',
                          'Publish positive/failing/unresolved hybrid, ambiguous-binding and '
                          'context cases with migration impact.'],
 'evidence_refs': ['pr-517', 'pr-535', 'pr-598'],
 'historical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                  'path': 'docs/delivery/android/work-packages/03.04.md'},
 'record_revision': 'ed70452180a7f1020d5156c711ccde5baa66f0de',
 'record_tree': '20df104d4055c8cbc574e7b0d89d7b7d32a6c774',
 'record_path': 'docs/delivery/android/work-packages/03.04.md',
 'record_blob': '212b7c09becdb24338826c5d66486454e93a722d',
 'record_bytes': 40286,
 'record_sha256': '0bce5553bbc8e104930399e0e4831311b24654078f737c77d28b798e56506eab',
 'decision_path': 'docs/decisions/0012-android-37-acceptance-record.md',
 'decision_blob': '7b775d25b9c5316b45d23b53ca57eccf4ef4290a',
 'decision_bytes': 6128,
 'decision_sha256': '5806c9046f41ca045bc8748500960470fe00b794d2eb83aa956c0cae4e8f42ad',
 'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/ed70452180a7f1020d5156c711ccde5baa66f0de/docs/delivery/android/work-packages/03.04.md',
 'decision_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/ed70452180a7f1020d5156c711ccde5baa66f0de/docs/decisions/0012-android-37-acceptance-record.md',
 'record_delivery': {'pr': 535,
                     'merged_at': '2026-10-19T16:45:38Z',
                     'signature_verified': True,
                     'parents': ['fd05539e72dc707fb7242b8e2f9321db1de0bdda'],
                     'delivery_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#issuecomment-6272386365',
                     'placement_effect': 'ADR_0012_effective_for_only_android_03_04_after_actual_reviewed_normal_delivery'},
 'receiving_identity_scope': 'Actual delegated in-session engineering roles through the shared '
                             'project account; no absent-assignee reply, distinct-account formal '
                             'approval, participant/device/provider test or credentials are '
                             'inferred.',
 'receiving_reviews': [{'role': 'actual_in_session_central_contract_and_android_domain',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37#issuecomment-6263823132',
                        'created_at': '2026-10-19T09:15:57Z',
                        'body_bytes': 9197,
                        'body_sha256': '31a8f9fc663840443c33f7cd83c129df606fb00fc55d034bb3d40b0d3ddc3be3',
                        'source_revision': 'b1adcec7dff97447f472d1c4b1a3380d212d3b9d',
                        'source_tree': 'd452154a42edc14bc290047e9c73156e0126dc95',
                        'disposition': 'accepted_all_three_literal_criteria_and_four_subtasks_for_selected_profile',
                        'read_and_execution_scope': 'Context author and archive qualifier; '
                                                    'independent complete placement/source review. '
                                                    'Receives the selected six-interface outcome '
                                                    'and available #21 local input boundary, '
                                                    'without granting broader #21 acceptance.'},
                       {'role': 'actual_in_session_affected_ios',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37#issuecomment-6263781725',
                        'created_at': '2026-10-19T09:13:38Z',
                        'body_bytes': 7089,
                        'body_sha256': '593b9ebbf20ec010da11ccb4f9ff889cfe5677e7c0550d67060374729d3073d4',
                        'source_revision': 'b1adcec7dff97447f472d1c4b1a3380d212d3b9d',
                        'source_tree': 'd452154a42edc14bc290047e9c73156e0126dc95',
                        'disposition': 'accepted_all_three_literal_criteria_and_four_subtasks_for_selected_profile',
                        'read_and_execution_scope': 'Complete record, ADR, mapping and scoped '
                                                    'evidence receiving; original Apple URL '
                                                    'finding corrected. No product test execution '
                                                    'or original-archive re-audit by this '
                                                    'reviewer.'},
                       {'role': 'actual_in_session_coordinating_and_affected_website',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37#issuecomment-6265403655',
                        'created_at': '2026-10-19T10:45:18Z',
                        'body_bytes': 8290,
                        'body_sha256': 'e8d2d7a6a2ea36b1c644eb4de04b8bbc1bf096b69565ad6cb1e897824b1bfd47',
                        'source_revision': 'b1adcec7dff97447f472d1c4b1a3380d212d3b9d',
                        'source_tree': 'd452154a42edc14bc290047e9c73156e0126dc95',
                        'disposition': 'accepted_all_three_literal_criteria_and_four_subtasks_for_selected_profile',
                        'read_and_execution_scope': 'Complete selected outcome and narrow Android '
                                                    'record placement accepted; ADR becomes '
                                                    'effective through reviewed normal delivery. '
                                                    'Historical website absence applies only to '
                                                    'the inspected revision; no general website or '
                                                    'prerequisite acceptance.'}],
 'final_register_source_review': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/550#pullrequestreview-5570894623',
 'full_placement_source_review': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/534#pullrequestreview-5568143967',
 'context_independent_source_review': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/505#pullrequestreview-5566487937',
 'context_receiving_source_review': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/517#pullrequestreview-5567753510',
 'implementation_source_revision': 'af8ce984a2d0169523fc5151a909328124b3ac18',
 'implementation_source_pins': [{'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementAnswers.kt',
                                 'blob': '8633c51aee6ab937513b582ddbe319069dc7140b',
                                 'bytes': 17067,
                                 'sha256': '24dfb31e6ef4ae84c0c5060d707a50cdae5d0501b0d79738603dabc501ebf4da'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementBasis.kt',
                                 'blob': '76187d7aa86f31db4b655013cfbd64c2955abd6f',
                                 'bytes': 11626,
                                 'sha256': 'f0b1084aa4198a9c08653585fa53bafac156164ae649496829e98bfb68449635'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementBindings.kt',
                                 'blob': 'aee5620db9c5f2dc25479468f8ad91aa3ac161df',
                                 'bytes': 13791,
                                 'sha256': 'e07f4458a29ee43f1604c2cec5aa86a28377cbc93fbc6924ebc42e43298a870c'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementDeployment.kt',
                                 'blob': 'f01790e824b866ef6efd360826986511856432d5',
                                 'bytes': 15411,
                                 'sha256': 'db27d8edc98ee29e961bc8cc1d9d00042050fc0a95f8caf3e48416411288b894'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementMemory.kt',
                                 'blob': '59b2404ca43896cdc8bd4c00ac1f2cc3df856e8f',
                                 'bytes': 32817,
                                 'sha256': '74d7d760554a8fc108932d816b5570278c89700bd6813ccc6902b11cecd68276'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementMemoryRecords.kt',
                                 'blob': '638a0b4d168203e62c2f4c719ad429f19bd8eaeb',
                                 'bytes': 11789,
                                 'sha256': 'ecb54a791addadb777bc1e1ad4d9f2e285add675cbee570966c6c433aad9a4b1'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementResults.kt',
                                 'blob': '558cb819a96208d586311d2b40630e632502ee4a',
                                 'bytes': 8150,
                                 'sha256': '0cf3a9fd7e38ce73299bd419ad9c530ae1607a040c3f5a506b4e8e77d38046c5'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementServing.kt',
                                 'blob': 'e80aa376293ecf7f68979df23f697fa07ace740b',
                                 'bytes': 1938,
                                 'sha256': '79fd41d1baf55c2fa361bd11f8d38f2d46e132de9c7d5006962d451ca9bf1ca2'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementWorkload.kt',
                                 'blob': 'bfe21e7d571095a32320caba78402a85eb06a5b0',
                                 'bytes': 8688,
                                 'sha256': '024d51ae12c481b03d2a4d8ab75a271279a405b306e95823b344dfe6498189e4'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/context/Cd11ContextRecord.kt',
                                 'blob': 'ee34172c89a763012c1bd2c93cf1bea8b49c7637',
                                 'bytes': 18146,
                                 'sha256': '79454aea84ba5a31fba4a53640f0b56cb44e4cf7b27bc72fc8237a39e5956ffc'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/context/ContextBudgetMath.kt',
                                 'blob': 'aabcaca2ff009989cfe5d42227b46bd26b8c173d',
                                 'bytes': 3845,
                                 'sha256': 'e1767cf134bdb6498669f05bd8e85759981c738fde6d35da5dd3cba2951e535f'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/context/ContextValues.kt',
                                 'blob': '735b76af234fa79bb97a10e926041d70ac797d6c',
                                 'bytes': 7934,
                                 'sha256': '6380d8b17a91a9afe151934d86063d25c437fdf0c1eb56833172b17ac3e96e6d'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementBindingDeploymentTest.kt',
                                 'blob': 'e93590664b89970d404a4daf7663e6ac2d059599',
                                 'bytes': 30536,
                                 'sha256': 'ff574bbdab832d98f312e4deb31e883432042598f5d08ced7a93fb148de5d558'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementMemoryFixtures.kt',
                                 'blob': '66d75fe28262c692c9c5b3ff4464dd0722a5995e',
                                 'bytes': 13148,
                                 'sha256': 'd1881a1c0d0f60a6ff7e6de221651d2f23c47896e0cb657d898e0da98317584d'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementMemoryTest.kt',
                                 'blob': '56607a8e83fef029a8050399ecd264d4f14930f5',
                                 'bytes': 23075,
                                 'sha256': '9dcf4647677feefbe1773ac45d4cf3aa45936b6d7ff2a8e6d90f5a52a06b1f76'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementReceivedVectorsTest.kt',
                                 'blob': '9ac442cec9bc33837452befea8511b8d346f09c4',
                                 'bytes': 8216,
                                 'sha256': 'c4c190f406bab40c188ac4240763c766c80239cf8aa6b90ed88f33332859c52e'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementTestFixtures.kt',
                                 'blob': '25bd1cb9ffc2a684425237b02c4e4d94af27d902',
                                 'bytes': 16490,
                                 'sha256': 'd5e4179b2eaa19ae725c80e9fe7be152c2f86e1b35f512e23322b3940f1ff3a1'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementWorkloadTest.kt',
                                 'blob': '00d4423465c4947b1f8ea9a2091e557e5e6c0a70',
                                 'bytes': 12104,
                                 'sha256': 'c1b50ca0db6a1c23fe3c70c935b7fffc113db3e58d03bc87903f76d6dc241004'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/context/Cd11ContextRecordTest.kt',
                                 'blob': '2fe666bf734eea2f4367ae2081656c34f8f2a6c3',
                                 'bytes': 33038,
                                 'sha256': '70e0fbc53ff4364f5a9c5a6ef8796ccbe652fb0a2ae107d681b35653adee67e6'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/context/ContextBudgetMathTest.kt',
                                 'blob': '7ab8388bb787d394e27155bc750a186d46e2268e',
                                 'bytes': 10695,
                                 'sha256': '3b57d1275016bba5ce072a1c6b90a5ce42fbcfb734c8adaa4f5c9704fb82f66b'},
                                {'path': 'core/src/test/resources/placement/context/reference/context-budget-answers.json',
                                 'blob': '3006ada1fe0945649296b3e3f9a5fec0aa240f0b',
                                 'bytes': 734,
                                 'sha256': 'be4fc13496144cb559fa2a8116da5f81fd5e1395f50fa6c6b1b8999bf6c9b2f1'},
                                {'path': 'core/src/test/resources/placement/context/reference/context-budget-binding.json',
                                 'blob': '659821843ecd9db3c9d85469cfa89877329da684',
                                 'bytes': 647,
                                 'sha256': 'c3108457577e8710dda63f7630925422b4f1c138d039619dcb49b96ad97470a3'},
                                {'path': 'core/src/test/resources/placement/context/reference/context-budget-binding.schema.json',
                                 'blob': '5480495d59bf9c41c9108c7d13934359420a088e',
                                 'bytes': 1912,
                                 'sha256': 'f99e8d65954cdf4a51df10db345443c7d3936382f700aae28c30785b68a0722c'},
                                {'path': 'core/src/test/resources/placement/context/reference/reference-pins.json',
                                 'blob': '420d5e6387d3961bbaf5868ff92e2b74714780aa',
                                 'bytes': 1176,
                                 'sha256': '34af15882a77d8be291aee757f09001b9ab5894f0ca5db47d0e1d547a5752917'},
                                {'path': 'core/src/test/resources/placement/context/reference/t0-answer-states.schema.json',
                                 'blob': '91aab5e6bae86f3b7dfb8e504a65c02502c6defb',
                                 'bytes': 1370,
                                 'sha256': '51e18c1b5694daf905aee539712dd0a9e46e78be0ca8e438d758628d88357e0d'},
                                {'path': 'docs/CD11_CONTEXT_PROFILE.md',
                                 'blob': '2143ce84db0f288228dc14510732bb424868a6a3',
                                 'bytes': 13975,
                                 'sha256': '0b11846f26a42a045fb0bc0fa7db2107b902d5155d3f16058d5b6378ae4e3566'},
                                {'path': 'docs/NORMALIZED_PLACEMENT_PROFILE.md',
                                 'blob': 'af5a8635834aaf97d767a6a5d07a9234a10d02c8',
                                 'bytes': 18057,
                                 'sha256': '8422de93897b44058677be20b9d5bba90de7196db0a75ee05deb6378c007fa5d'}],
 'selected_interfaces': ['immutable_basis_and_original_inputs',
                         'original_cd1_0_2_answers_and_qualification',
                         'single_cd6_graph_deployment_and_exact_lineage',
                         'cd2_physical_pools_coverage_and_copies',
                         'exact_workload_sequence_and_admission_scope',
                         'preserving_cd11_context_and_prior_normalization'],
 'vector_groups': [{'id': 'D1',
                    'received_behavior': 'Hybrid retains applicable local failures, skips only '
                                         'established remote local-only requirements, and retains '
                                         'independent service failures.'},
                   {'id': 'D2',
                    'received_behavior': 'Actual operator and capacity facts determine '
                                         'applicability; API protocol, catalog access and '
                                         'customer-supplied runtime do not invent physical '
                                         'exposure.'},
                   {'id': 'D3',
                    'received_behavior': 'Unknown operator, provider capacity or required exposure '
                                         'remains unknown; independent service failure survives. '
                                         'Reserved capacity and exposed hardware are separate.'},
                   {'id': 'B1',
                    'received_behavior': 'Two eligible engine candidates remain ambiguous in both '
                                         'orders; required absence, unestablished inventory and '
                                         'optional inapplicability remain distinct.'},
                   {'id': 'B2',
                    'received_behavior': 'Known wrong-kind or disabled required targets fail; '
                                         'unavailable exact catalog version remains unknown. '
                                         'Malformed and dangling provided records reject before '
                                         'shortcuts.'},
                   {'id': 'B3',
                    'received_behavior': 'Exact adapter/base/engine facts and used checked-only '
                                         'prerequisites survive every dependent exit; a defensive '
                                         'two-adapter cycle is UNKNOWN/BINDING_CYCLE.'},
                   {'id': 'M1',
                    'received_behavior': 'Exact billion-byte units: 18U+8U fails one 24U UMA pool; '
                                         'separate 30U and 6U demands retain fail/pass against '
                                         'distinct 24U budgets.'},
                   {'id': 'M2',
                    'received_behavior': 'Only qualified physical identity permits one charge for '
                                         'sharing; host/offload/transfer copies and independent '
                                         'rank ceilings retain their own scope.'},
                   {'id': 'M3',
                    'received_behavior': 'Before-reserve 24U minus 8U and after-reserve 16U both '
                                         'pass 16U and fail 16U+1 byte; zero and values beyond '
                                         'binary64/Long remain exact.'},
                   {'id': 'M4',
                    'received_behavior': 'Qualified complete scenarios, transition coverage, '
                                         'identity and inventories are required; unknown '
                                         'nonnegative charges do not erase an independently '
                                         'qualified disjoint failure.'},
                   {'id': 'W1',
                    'received_behavior': 'One request expands to four live sequences before a '
                                         'qualified bound of two; exact '
                                         'runtime/build/replica/admission/stage and '
                                         'retained/queued coverage are required.'},
                   {'id': 'C1',
                    'received_behavior': '7000+2000 fails shared 8000 and passes independent input '
                                         '8000/output 4000; no combined vendor limit is invented.'},
                   {'id': 'C2',
                    'received_behavior': 'An explicit 8500 total policy independently fails 9000; '
                                         'missing/unknown/withheld, optional N/A and provided zero '
                                         'remain distinct.'},
                   {'id': 'C3',
                    'received_behavior': 'Eligible 5000–7000 input plus 2000 straddles shared '
                                         '8000; conservative upper failure and independent '
                                         'definite failure precedence retain their selected '
                                         'meanings.'},
                   {'id': 'C4',
                    'received_behavior': 'All nine scope dimensions are exact; valid '
                                         'missing/mismatched scope or ineligible evidence is '
                                         'unknown with no arithmetic from that bound; malformed '
                                         'scope rejects before disabled evaluation.'},
                   {'id': 'C5',
                    'received_behavior': 'Prior normalized native 32768 remains available without '
                                         'qualified extension; evidenced 131072 obeys a supplied '
                                         '65536 runtime ceiling. The binding reader invents no '
                                         'missing-operand fallback.'},
                   {'id': 'G1',
                    'received_behavior': 'Closed versions/types and original-byte/hash/all-record '
                                         'preflight precede outcomes; valid changed basis is '
                                         'ineligible, and immutable original provenance and frozen '
                                         'historical dispatch remain preserved.'}],
 'profile_integrity': {'research_revision': '449a29be1752a3c545709da466c7d53fc18fef73',
                       'research_tree': '6a188a54d269028e5283288d9f6afa1f63ba2163',
                       'central_decision_blob': 'd3d53f3c135556a3bd227e8008dceb9f08676749',
                       'deployment_decision_blob': '8d64fcc0bf16222b0ed3e06dc53c396e525c0741',
                       'context_decision_blob': '5384081104cc2d872a4173138fc00a00c115c072',
                       'physical_profile': 'cd2-physical-pools/0.1.0',
                       'physical_answer_version': 'cd1-0.2',
                       'physical_count_type': 'provided_canonical_nonnegative_decimal_ByteCount_string_to_BigInteger',
                       'context_profile': 'cd11-0.1',
                       'context_answer_version': 'cd1-0.1',
                       'context_count_type': 'original_JSON_integer_token_to_BigInteger_with_field_specific_zero_rules',
                       'context_answer_sha256': 'be4fc13496144cb559fa2a8116da5f81fd5e1395f50fa6c6b1b8999bf6c9b2f1',
                       'version_boundary': 'No relabeling, reserialization, invented '
                                           'assumption/source or silent old-to-new answer '
                                           'conversion.',
                       'qualification_boundary': 'Original bytes/hash and structural validity do '
                                                 'not grant evidence eligibility; exact basis, '
                                                 'original input, pointer, domain, operation and '
                                                 'source-policy identities remain required.',
                       'failure_boundary': 'Malformed identities, dangling provided records and '
                                           'failed declared original bindings reject; well-formed '
                                           'changed or unestablished active scope remains '
                                           'unknown/ineligible, retaining independently qualified '
                                           'failures.',
                       'cycle_boundary': 'B3 is a defensive normalized two-adapter '
                                         'UNKNOWN/BINDING_CYCLE case, not a semantically valid '
                                         'frozen-v1 base_model_ref configuration or a revised '
                                         'catalog-kind contract.'},
 'consumed_prerequisites': [{'repository': 'Pick-Your-AI/pick-your-ai-research',
                             'issue': 40,
                             'output': 'Delivered selected CD-2/CD-6/CD-7 decisions and received '
                                       'CD-5/CD-11 test-profile semantics at the exact pinned '
                                       'boundary.',
                             'limits': 'No full central successor wire release, migration, service '
                                       'or real-provider acceptance is inferred.'},
                            {'repository': 'Pick-Your-AI/pick-your-ai-android',
                             'issue': 21,
                             'output': 'Available exact frozen Build/catalog/import/admission '
                                       'output at this local typed consumer boundary, explicitly '
                                       'received by the central/Android and coordinating roles.',
                             'limits': 'Whole #21 API/storage/decimal-money criteria 21.1 and '
                                       '21.3, issue state and native dependency edges are '
                                       'unchanged.'}],
 'neutral_fixture_scope': {'existing_bundle_cases': 223,
                           'existing_placement_cases': 98,
                           'placement_dispositions': {'preserved': 30,
                                                      'unresolved': 20,
                                                      'unsupported_field': 24,
                                                      'invalid_contract': 24},
                           'gzip_bytes': 43929,
                           'gzip_sha256': 'c1e66aa954c20354ec607ceb8f90a90c599a8748ab10dcff17ce63fda8bf366c',
                           'raw_bytes': 1273770,
                           'raw_sha256': '499fd9a01780ac42fa9bf2ab8cf9e1195db27321fec36040451b553a8b9ea5f9',
                           'relation': 'Existing structural frozen-v1 observations stay distinct '
                                       'from the selected normalized behavior; no duplicate '
                                       'producer, changed payload or promotion of unsupported '
                                       'fields.'},
 'historical_observations': [{'event': 'actual_context_and_cd1_main_push',
                              'revision': '5d0e57dddaa819a41eb86ad811b04fb562e9c03b',
                              'tree': '4eb8109b041ae4f140a57671ebe796ccd8ad7150',
                              'core_cases': 5150,
                              'preview_cases': 388,
                              'context_cases': 158,
                              'cd1_cases': 643,
                              'receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/517#issuecomment-6261956039',
                              'job_ids': [117928936512, 117928936242, 117928936664],
                              'attribution': 'Context author/receiving controller inspected actual '
                                             'original hosted artifacts; independent source '
                                             'reviews retain their separate scope.'},
                             {'event': 'corrected_focused_placement',
                              'revision': 'b2d6d2b82b523afb11317bf24a6cccc1b87face8',
                              'cases': 111,
                              'binding_deployment': 41,
                              'memory': 49,
                              'workload': 21,
                              'authored_patch_sha256': '0ee0f240b2032be47b90ea7e0aa081ae24f2890abec87722da18d8e0ecdc6431',
                              'attribution': 'Placement author executed pinned Kotlin '
                                             '2.1.20/JDK17/target17; this is not the configured '
                                             'hosted JDK21 gate.'},
                             {'event': 'original_placement_receiving',
                              'revision': 'a3ee0fb4d877f7d66bd746485de81007adc16998',
                              'tree': 'b9d1f9850516630b6ae5b6d7d97fe4d860a1b5ac',
                              'core_cases': 5261,
                              'preview_cases': 388,
                              'receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#issuecomment-6262345939',
                              'scope': 'Original actual 111 placement cases; this historical run '
                                       'does not include the later seven supplement cases.'},
                             {'event': 'exact_vector_supplement',
                              'revision': 'b392a8fdaf51ba94313524b7badf9b27c804491a',
                              'cases': 7,
                              'test_blob': '9ac442cec9bc33837452befea8511b8d346f09c4',
                              'receipt_sha256': '9e33d7dbc95854d1849b5ceaa8f94698bb9fa9cc4bf66933102b03920c82ad1c',
                              'attribution': 'Placement author executed focused original source '
                                             'with pinned Kotlin/JDK17; later main-push '
                                             'observation below independently includes the same '
                                             'seven identities.'}],
 'main_observation': {'execution_revision': 'af8ce984a2d0169523fc5151a909328124b3ac18',
                      'execution_tree': 'e99114b2bc7119cf8805695251bd998e159dbda1',
                      'event': 'push',
                      'branch': 'main',
                      'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#issuecomment-6273876242',
                      'qualification_bytes': 649333,
                      'qualification_sha256': 'badfa8a2bb510c8799a8cd2816ded31de8a0092b0509676de6ebc165fb2584b1',
                      'core_cases': 6186,
                      'core_xml_suites': 116,
                      'preview_cases': 526,
                      'preview_xml_suites': 51,
                      'failures': 0,
                      'errors': 0,
                      'skips': 0,
                      'duplicate_identities': 0,
                      'target_identity_hash_format': 'SHA256 of ordered JSON [class,name] pairs, '
                                                     'UTF-8, sorted object keys, comma/colon '
                                                     'separators.',
                      'target_identities': [{'group': 'corrected_112_placement_identities',
                                             'unique_cases': 112,
                                             'ordered_identity_json_sha256': 'b81ea5a9f24c67fe2485f97be44e5a682023fc7579dfd65ddefbe3ce7032bf49'},
                                            {'group': 'prior_7_vector_identities',
                                             'unique_cases': 7,
                                             'ordered_identity_json_sha256': '5c7858d93c5fb41a0336ba6d1f5a723ce31d5b0ee99dd585e5a4132c76a719ae'},
                                            {'group': 'context_158_identities',
                                             'unique_cases': 158,
                                             'ordered_identity_json_sha256': '53e68938f19373c3550418f624a55abcf5afa416a30019e0bcdfb9459eef6ed9'},
                                            {'group': 'cd1_643_identities',
                                             'unique_cases': 643,
                                             'ordered_identity_json_sha256': 'be6dd4cf83c05d0ccb106ee60bde013e736c3d798d352671838f82fe87869987'}],
                      'contains_regression': {'additional_junit_methods': 1,
                                              'internal_rows': 154,
                                              'internal_rows_are_not_junit_cases': True},
                      'jobs': [{'id': 118209476537,
                                'run_id': 39295930096,
                                'name': 'Unsigned synthetic Android preview',
                                'html_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39295930096/job/118209476537',
                                'head_sha': 'af8ce984a2d0169523fc5151a909328124b3ac18',
                                'status': 'completed',
                                'conclusion': 'success',
                                'completed_at': '2026-10-19T17:51:00Z'},
                               {'id': 118209477145,
                                'run_id': 39295930096,
                                'name': 'Frozen contracts and JVM core',
                                'html_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39295930096/job/118209477145',
                                'head_sha': 'af8ce984a2d0169523fc5151a909328124b3ac18',
                                'status': 'completed',
                                'conclusion': 'success',
                                'completed_at': '2026-10-19T17:49:57Z'},
                               {'id': 118208322960,
                                'run_id': 39295930030,
                                'name': 'Pinned secret scanning',
                                'html_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39295930030/job/118208322960',
                                'head_sha': 'af8ce984a2d0169523fc5151a909328124b3ac18',
                                'status': 'completed',
                                'conclusion': 'success',
                                'completed_at': '2026-10-19T17:39:20Z'}],
                      'original_artifacts': [{'kind': 'runtime',
                                              'id': 12209120645,
                                              'name': 'android-runtime-inventory-af8ce984a2d0169523fc5151a909328124b3ac18',
                                              'bytes': 4832131,
                                              'sha256': '56e5c7d0cfe1a3c9ccedc4cb950af41e6a74150f0546f2ad0f7aad2567aa2b52',
                                              'members': 8},
                                             {'kind': 'core',
                                              'id': 12207764901,
                                              'name': 'core-verification-af8ce984a2d0169523fc5151a909328124b3ac18',
                                              'bytes': 2068430,
                                              'sha256': 'ef47a7561dbfeeb175c6a6c52752830624f99029f15b23263a40268be32a93fe',
                                              'members': 270},
                                             {'kind': 'native',
                                              'id': 12207518557,
                                              'name': 'android-preview-af8ce984a2d0169523fc5151a909328124b3ac18',
                                              'bytes': 3464746,
                                              'sha256': 'b41fd073d01d2654f27aabcd870a48cb59dff6880eade12ce9b13d3a64fccf1b',
                                              'members': 116},
                                             {'kind': 'scanner',
                                              'id': 12209415184,
                                              'name': 'secret-scan-af8ce984a2d0169523fc5151a909328124b3ac18',
                                              'bytes': 1325,
                                              'sha256': 'f36d39c254c16b6cae11c4e27cc5f4e41024e1b21b89063962df30eb13ae517b',
                                              'members': 1}],
                      'literal_attendance_programs': 49,
                      'original_archive_members': 395,
                      'conformance': {'bytes': 65948,
                                      'sha256': '51d66e45bfd65b90c316907c687e6234f8644309de5fd32171fa86176e88aa9b',
                                      'tests': 260,
                                      'wire_observations': 223,
                                      'migration_observations': 34},
                      'original_apk': {'bytes': 3380735,
                                       'identical_in_both_original_archives': True,
                                       'sha256': '4adc3d92919b8fdc2a55de27481106ecdaf3c635de5673b5978a9a2933811586'},
                      'hosted_toolchain': {'gradle': '8.11.1',
                                           'java': 'Temurin 21.0.12-1',
                                           'python': '3.12.15'},
                      'scanner': {'reachable_commits': 263,
                                  'raw_exit_code': 10,
                                  'raw_findings': 3,
                                  'reviewed_public_checksum_findings': 3,
                                  'unresolved_findings': 0,
                                  'scope': 'actual_reachable_main_history',
                                  'limits': 'The same three reviewed public checksum findings '
                                            'retain the actual af8 report order and immutable '
                                            'proof joins; no new scan, credential validation or '
                                            'absence-of-secrets proof.'},
                      'attribution': 'Placement delivery controller acquired and qualified the '
                                     'four actual af8 main-push originals, exact prior-name '
                                     'preservation, literal guards, source/dependency inventories, '
                                     'conformance/SPDX and regular APK. Source reviewers did not '
                                     'thereby execute these jobs.',
                      'preserved_failures': 'Original context accessor compilation failure, '
                                            'placement 29/34 first result and subsequent '
                                            '103-to-111 provenance corrections, and all historical '
                                            'receiving/audit failures keep their original scope. '
                                            'No all-attempts-passed claim. The original '
                                            'changed-basis UNKNOWN-versus-SKIPPED RED, successful '
                                            'controls, focused GREEN and cancelled fbdd push '
                                            'remain distinct; only af8 supplies this completed '
                                            'corrected main-push observation.'},
 'profile_limits': ['Explicit opt-in synthetic normalized Core consumer; no Preview/default '
                    'evaluator dispatch or runtime deployment result.',
                    'No new Build/catalog/Answer wire field, service endpoint, persistent '
                    'migration, source resolver, activation or globally released successor '
                    'profile.',
                    'No physical or installed-device run, live provider/operator evidence, '
                    'measured hardware fit, readiness, rights, cost, release or store acceptance.',
                    'No unchanged hash, supplied label, nonempty inventory or provided value is an '
                    'evidence qualification/completeness grant.',
                    'Only the three original #37 criteria and four subtasks are received; other '
                    'issue criteria, assignees, native edges, formal historical-person approvals '
                    'and Project fields are not changed by this receipt.'],
 'original_main_observation': {'execution_revision': 'ed70452180a7f1020d5156c711ccde5baa66f0de',
                               'execution_tree': '20df104d4055c8cbc574e7b0d89d7b7d32a6c774',
                               'event': 'push',
                               'branch': 'main',
                               'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#issuecomment-6272714192',
                               'qualification_bytes': 750891,
                               'qualification_sha256': 'afdb580cd37b28d5f18c47d57c8d887c5e92c090f2e83a2e6c1b8da1b049ea44',
                               'core_cases': 5993,
                               'core_xml_suites': 115,
                               'preview_cases': 522,
                               'preview_xml_suites': 50,
                               'failures': 0,
                               'errors': 0,
                               'skips': 0,
                               'duplicate_identities': 0,
                               'target_identity_hash_format': 'SHA256 of ordered JSON [class,name] '
                                                              'pairs, UTF-8, sorted object keys, '
                                                              'comma/colon separators.',
                               'target_identities': [{'group': 'prior_111_placement_identities',
                                                      'unique_cases': 111,
                                                      'ordered_identity_json_sha256': '2e20f3b962f9f07192556afea1d1cf0213f169a7adcace47c5743dee3d5a61a2'},
                                                     {'group': 'prior_7_vector_identities',
                                                      'unique_cases': 7,
                                                      'ordered_identity_json_sha256': '5c7858d93c5fb41a0336ba6d1f5a723ce31d5b0ee99dd585e5a4132c76a719ae'},
                                                     {'group': 'context_158_identities',
                                                      'unique_cases': 158,
                                                      'ordered_identity_json_sha256': '53e68938f19373c3550418f624a55abcf5afa416a30019e0bcdfb9459eef6ed9'},
                                                     {'group': 'cd1_643_identities',
                                                      'unique_cases': 643,
                                                      'ordered_identity_json_sha256': 'be6dd4cf83c05d0ccb106ee60bde013e736c3d798d352671838f82fe87869987'}],
                               'contains_regression': {'additional_junit_methods': 1,
                                                       'internal_rows': 154,
                                                       'internal_rows_are_not_junit_cases': True},
                               'jobs': [{'id': 118181571022,
                                         'run_id': 39288171205,
                                         'name': 'Unsigned synthetic Android preview',
                                         'html_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39288171205/job/118181571022',
                                         'head_sha': 'ed70452180a7f1020d5156c711ccde5baa66f0de',
                                         'status': 'completed',
                                         'conclusion': 'success',
                                         'completed_at': '2026-10-19T16:54:34Z'},
                                        {'id': 118181571431,
                                         'run_id': 39288171205,
                                         'name': 'Frozen contracts and JVM core',
                                         'html_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39288171205/job/118181571431',
                                         'head_sha': 'ed70452180a7f1020d5156c711ccde5baa66f0de',
                                         'status': 'completed',
                                         'conclusion': 'success',
                                         'completed_at': '2026-10-19T16:51:52Z'},
                                        {'id': 118181569170,
                                         'run_id': 39288171302,
                                         'name': 'Pinned secret scanning',
                                         'html_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39288171302/job/118181569170',
                                         'head_sha': 'ed70452180a7f1020d5156c711ccde5baa66f0de',
                                         'status': 'completed',
                                         'conclusion': 'success',
                                         'completed_at': '2026-10-19T16:46:02Z'}],
                               'original_artifacts': [{'kind': 'native',
                                                       'id': 12205445495,
                                                       'name': 'android-preview-ed70452180a7f1020d5156c711ccde5baa66f0de',
                                                       'bytes': 3453953,
                                                       'sha256': 'feab292255c66644d5292555cee1a0d63bab584f7093ecb67ec198670f2001b4',
                                                       'members': 114},
                                                      {'kind': 'core',
                                                       'id': 12205415312,
                                                       'name': 'core-verification-ed70452180a7f1020d5156c711ccde5baa66f0de',
                                                       'bytes': 2034446,
                                                       'sha256': '2cd3424a7a8d52a1144b8aa14feadfada116e05b558c22dd5e79310436806c9e',
                                                       'members': 268},
                                                      {'kind': 'runtime',
                                                       'id': 12204542712,
                                                       'name': 'android-runtime-inventory-ed70452180a7f1020d5156c711ccde5baa66f0de',
                                                       'bytes': 4799324,
                                                       'sha256': 'ea25c1b3ebe90c1d41a8a9f2b6d094ba3d199b999a0ac9a41df1959b6ddad342',
                                                       'members': 8},
                                                      {'kind': 'scanner',
                                                       'id': 12205075529,
                                                       'name': 'secret-scan-ed70452180a7f1020d5156c711ccde5baa66f0de',
                                                       'bytes': 1326,
                                                       'sha256': '1293a27294025beada31df6f5eedcdd46098afac064857410c876e7c90c71fc5',
                                                       'members': 1}],
                               'literal_attendance_programs': 48,
                               'original_archive_members': 391,
                               'conformance': {'bytes': 65948,
                                               'sha256': '32e3be794070393680fc9bcf217e089130d66aa4098278c34c59f74b68528f70',
                                               'tests': 260,
                                               'wire_observations': 223,
                                               'migration_observations': 34},
                               'original_apk': {'bytes': 3372275,
                                                'identical_in_both_original_archives': True,
                                                'sha256': '82b3036182a7984feec117a2090d9fc92c52d9a31095df4bd72b5b3307a85278'},
                               'hosted_toolchain': {'gradle': '8.11.1',
                                                    'java': 'Temurin 21.0.12-1',
                                                    'python': '3.12.15'},
                               'scanner': {'reachable_commits': 255,
                                           'raw_exit_code': 10,
                                           'raw_findings': 3,
                                           'reviewed_public_checksum_findings': 3,
                                           'unresolved_findings': 0,
                                           'scope': 'actual_reachable_main_history',
                                           'limits': 'Historical fourth finding outside the '
                                                     'squashed main history remains in the prior '
                                                     'receipt; no new scan, credential validation '
                                                     'or absence-of-secrets proof.'},
                               'attribution': 'Placement delivery controller inspected all '
                                              'original archives and actual completed jobs, '
                                              'replayed literal guards and reinspected the '
                                              'original regular APK; source reviewers did not '
                                              'thereby execute these jobs.',
                               'preserved_failures': 'Original context accessor compilation '
                                                     'failure, placement 29/34 first result and '
                                                     'subsequent 103-to-111 provenance '
                                                     'corrections, and all historical '
                                                     'receiving/audit failures keep their original '
                                                     'scope. No all-attempts-passed claim.'},
 'changed_basis_correction': {'scope': 'restore_selected_G1_changed_basis_unknown_before_old_membership_or_inapplicability',
                              'defect': 'Old inactive deployment plus qualified current coverage '
                                        'could return SKIPPED instead of UNKNOWN after the same-ID '
                                        'Build changed. This was a stale-membership classification '
                                        'error, not a false PASS.',
                              'original_source_revision': '76badfa3000388526ef66e5d7fd208b6449c3c96',
                              'original_record_main_revision': 'ed70452180a7f1020d5156c711ccde5baa66f0de',
                              'original_finding_review': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#pullrequestreview-5574641300',
                                                          'review_id': 5574641300,
                                                          'anchor': 'c654312b676639876da8db05fce6336d5112de46',
                                                          'body_bytes': 3357,
                                                          'body_sha256': '347de917fddb4f0a9416d9b596c27cd5de63bca09ad908667d92f5d12dbefcff',
                                                          'submitted_at': '2026-10-19T16:33:22Z'},
                              'correction_review': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#pullrequestreview-5574672890',
                                                    'review_id': 5574672890,
                                                    'anchor': 'c654312b676639876da8db05fce6336d5112de46',
                                                    'body_bytes': 7833,
                                                    'body_sha256': '43eec86a2b0928c0cfd5f5ba2a769b227adc36138110009d28166541f6d12577',
                                                    'submitted_at': '2026-10-19T16:36:20Z'},
                              'prior_source_pins_unchanged': 25,
                              'changed_source_pins': [{'original': {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementDeployment.kt',
                                                                    'blob': '7dc569b84b32265af4bea887121d4c7065930fd6',
                                                                    'bytes': 15046,
                                                                    'sha256': 'd9d7758d3cf4dc084ce8568b57384ce65109df632795734b838131b399c127c2'},
                                                       'corrected': {'path': 'core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementDeployment.kt',
                                                                     'blob': 'f01790e824b866ef6efd360826986511856432d5',
                                                                     'bytes': 15411,
                                                                     'sha256': 'db27d8edc98ee29e961bc8cc1d9d00042050fc0a95f8caf3e48416411288b894'}},
                                                      {'original': {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementBindingDeploymentTest.kt',
                                                                    'blob': '8b163098eb6767b4a6db22e8536c16a49b1fbce4',
                                                                    'bytes': 27700,
                                                                    'sha256': 'b67b65a30191729ba50730ed95b3e4ed6e51f82c972d2ee19bed315bc30d3d00'},
                                                       'corrected': {'path': 'core/src/test/kotlin/ai/pickyour/core/evidence/placement/PlacementBindingDeploymentTest.kt',
                                                                     'blob': 'e93590664b89970d404a4daf7663e6ac2d059599',
                                                                     'bytes': 30536,
                                                                     'sha256': 'ff574bbdab832d98f312e4deb31e883432042598f5d08ced7a93fb148de5d558'}}],
                              'original_red': {'attribution': 'Original independent review author '
                                                              'executed the focused reproducer; '
                                                              'this ledger author read its '
                                                              'complete retained review, not a new '
                                                              'local reproduction.',
                                               'compile_exit_code': 0,
                                               'test_exit_code': 1,
                                               'tests': 1,
                                               'failures': 1,
                                               'expected': 'UNKNOWN',
                                               'observed': 'SKIPPED',
                                               'passing_controls': ['same_basis_inactive_is_skipped',
                                                                    'current_enabled_is_pass_and_contains_model',
                                                                    'old_deployment_and_old_coverage_against_current_is_unknown'],
                                               'qualification_bytes': 10594,
                                               'qualification_sha256': 'e6817ac0c458f7c1bf204b186015f06b2f8a77d0edcee60616fa878b88d0e639',
                                               'original_junit_bytes': 2683,
                                               'original_junit_sha256': '2561267c27ef835313ed985b686dba5de187d4f4f1a79f046743b041a6c7b7cb'},
                              'focused_green': {'attribution': 'Correction reviewer executed the '
                                                               'one actual regression with '
                                                               'Kotlin2.1.20/JDK17/target17; this '
                                                               'is distinct from the full hosted '
                                                               'JDK21 result.',
                                                'tests': 1,
                                                'failures': 0,
                                                'skips': 0,
                                                'aborts': 0,
                                                'compile_exit_code': 0,
                                                'test_exit_code': 0,
                                                'qualification_bytes': 9554,
                                                'qualification_sha256': '20bbcde02644b8785e40a34f8a02e5627edd48f8f182ff4ec76cc1b8f209a776',
                                                'original_junit_bytes': 1263,
                                                'original_junit_sha256': '1695997ec2597e5f7e7771ca53a8d8efdf3f03404eb59f7c9a814c56e01e0773'},
                              'permanent_regression': {'class': 'ai.pickyour.core.evidence.placement.PlacementBindingDeploymentTest',
                                                       'method': 'currentCoverageCannotMakeAnOlderInactiveDeploymentInapplicable()',
                                                       'original_controls_retained': True,
                                                       'corrected_result': 'UNKNOWN/CHANGED_BASIS '
                                                                           'bound to current basis '
                                                                           'and qualifications, '
                                                                           'empty components, '
                                                                           'unresolved serving '
                                                                           'membership and UNKNOWN '
                                                                           'mode; current coverage '
                                                                           'PASS does not erase '
                                                                           'the substantive '
                                                                           'UNKNOWN.'},
                              'receiving_reviews': ['https://github.com/Pick-Your-AI/pick-your-ai-android/pull/598#pullrequestreview-5575165478',
                                                    'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/598#pullrequestreview-5575236400'],
                              'receiving_execution': {'revision': '41726876c1b1aae40b606af33013b13d555359e4',
                                                      'tree': 'de307038a7ae424fb11fa3a84665783227071aa3',
                                                      'ordered_parents': ['75f031379b28a6df62c5828889fdbd5338cc8d5b',
                                                                          'f6052e4c784008d051320c5907954fc2ce44202d'],
                                                      'completed_success_job_ids': [118197944972,
                                                                                    118197944687,
                                                                                    118197944991],
                                                      'receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/598#issuecomment-6273359114',
                                                      'scope': 'Receiving controller inspected '
                                                               'actual completed configured checks '
                                                               'and full logs; its historical '
                                                               'prepared tree is not the later '
                                                               'main-push execution.'},
                              'delivery': {'pr': 598,
                                           'revision': 'fbdd62a90d636136d47f3d83f03b2a6c3ad52322',
                                           'tree': '9c965086f923c8c63b8986b5aa584daa62f2be72',
                                           'parents': ['152818c5a4a4aae70fed71805b14fc1a605394c3'],
                                           'merged_at': '2026-10-19T17:37:50Z',
                                           'signature_verified': True,
                                           'total_leaves': 629,
                                           'other_accepted_leaves_exact': 627,
                                           'receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#issuecomment-6273406643'},
                              'post_main_follow_through': {'cancelled_delivery_build_revision': 'fbdd62a90d636136d47f3d83f03b2a6c3ad52322',
                                                           'cancelled_build_is_not_completed_validation': True,
                                                           'qualified_successor_revision': 'af8ce984a2d0169523fc5151a909328124b3ac18',
                                                           'qualified_successor_tree': 'e99114b2bc7119cf8805695251bd998e159dbda1',
                                                           'successor_parents': ['fbdd62a90d636136d47f3d83f03b2a6c3ad52322'],
                                                           'exact_correction_blobs_preserved': True,
                                                           'preserved_parent_leaves_total': 627,
                                                           'preserved_other_parent_leaves': 625,
                                                           'preserved_correction_leaves': 2,
                                                           'source_map_clarification_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#issuecomment-6274260790',
                                                           'separate_successor_delta_paths': 7,
                                                           'new_regression_in_original_xml': True,
                                                           'completed_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535#issuecomment-6273876242',
                                                           'limits': 'af8 completed naturally '
                                                                     'after the fbdd push was '
                                                                     'cancelled; no cancelled or '
                                                                     'previous-source execution is '
                                                                     'reassigned. Later main '
                                                                     'commits require their own '
                                                                     'preservation and gates.'},
                              'policy_effect': 'Repairs the previously selected changed-basis '
                                               'boundary without a new profile, wire, domain '
                                               'decision, default dispatch or external '
                                               'acceptance.'}}
IMPLEMENTATION_37_EVIDENCE = {'pr-517': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/517',
            'observed_state': 'merged',
            'revision': '5d0e57dddaa819a41eb86ad811b04fb562e9c03b',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/5d0e57dddaa819a41eb86ad811b04fb562e9c03b/docs/CD11_CONTEXT_PROFILE.md',
            'scope_limit': 'Delivered distinct CD1-0.2 and preserving CD11-0.1/CD1-0.1 opt-in '
                           'packages; original 5150/388 and 643/158 evidence remains at signed 5d. '
                           'This alone did not complete #37 or authorize default/native/provider '
                           'behavior.'},
 'pr-535': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/535',
            'observed_state': 'merged',
            'revision': 'ed70452180a7f1020d5156c711ccde5baa66f0de',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/ed70452180a7f1020d5156c711ccde5baa66f0de/docs/delivery/android/work-packages/03.04.md',
            'scope_limit': 'Delivered the selected six-interface/seventeen-group normalized '
                           'placement source, seven exact vectors, complete #37 receiving record '
                           'and narrow ADR0012 placement. Original ed704 main-push5993/522 '
                           'includes111+7+158 target cases and remains historical; the later '
                           'changed-basis finding requires the separately delivered and qualified '
                           'pr-598 correction. Only the original three scoped criteria/four '
                           'subtasks are received, with no other issue or '
                           'external/default/installed acceptance.'},
 'pr-598': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/598',
            'observed_state': 'merged',
            'revision': 'fbdd62a90d636136d47f3d83f03b2a6c3ad52322',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/fbdd62a90d636136d47f3d83f03b2a6c3ad52322/core/src/main/kotlin/ai/pickyour/core/evidence/placement/PlacementDeployment.kt',
            'scope_limit': 'Delivered the exact reviewed changed-basis UNKNOWN guard and permanent '
                           'regression. The original SKIPPED failure, focused RED/GREEN and '
                           'receiving417 remain historical; actual signed successor af8 '
                           'main-push6186/526 separately includes112 placement+7 vectors+158 '
                           'context with all prior names retained. This restores the selected '
                           'opt-in boundary and grants no broader '
                           '#37/default/native/provider/release authority.'}}
IMPLEMENTATION_37_CRITERIA = ['9df2e61310ffcf71d7d218c570c7ec5fa80baab8b5b2555ffac64ac693e7723f',
 'f630371bd9ffb4454e5fc7ab389a68ec62c7be860beb91e35e808757c41dc2a0',
 '6abccbe5c92bebd946bc512281a9a1e8b26a68f60a6d49ba6460c4edd00ebf9b']
PRIOR_37_ISSUE_SHA256 = '7cdc3a35d20f64e525d9e4467fdd9f0835a2b1ecf15f9ef9413c9172127cdb90'
PRIOR_37_PREREQUISITES = [{'kind': 'contract_acceptance',
  'requirement': 'Accepted placement, relationship and context-budget semantics through research '
                 '#40; preserve unresolved bindings.'}]
PRIOR_V13_LEDGER_SHA256 = '2cb45d15a0db15a25ffb8d50effb4c9a34c1df94c4121c8d5d86e2e50e39ab07'
PRIOR_V13_FILE_SHA256 = '54e4ed9e29f42eb7e82b3d05454335ed1f44e251a21d0baeb00e4a64c55cf3dc'
PRIOR_V13_FILE_BYTES = 611487
LEDGER_PURPOSE_V14 = 'Living evidence index: 25 reviewed documentary/design criteria for Android #2/#20/#23/#24/#25/#34/#35/#47, 14 reviewed implementation criteria for #26/#37/#84/#96, the single reviewed import-integrity criterion 21.2 and 3 reviewed bounded support criteria for #33 are complete; 398 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies.'


def reconstruct_prior_v13(ledger):
    """Reverse only the fixed #37 receipt; preserve every other byte-level field.

    This is an inverse, not admission. validate() first checks the complete fixed
    receipt and criterion fields. Unexpected historical fields remain available
    to the old validators and complete predecessor digest below.
    """
    previous = copy.deepcopy(ledger)
    issue = previous['issues'][36]
    issue.pop('accepted_implementation_outcome')
    issue['canonical_acceptance_record'] = copy.deepcopy(
        IMPLEMENTATION_37_ACCEPTANCE['historical_acceptance_record'])
    for criterion in issue['criteria']:
        criterion.update(status='incomplete', completion_evidence=[],
                         remaining_engineering=PRIOR_84_REMAINING,
                         external_prerequisites=copy.deepcopy(PRIOR_37_PREREQUISITES),
                         evidence_relation='issue_context_only; inspect artifact scope before attributing any criterion result')
    for evidence_ref in IMPLEMENTATION_37_EVIDENCE:
        previous['evidence'].pop(evidence_ref)
    previous['schema_version'] = 13
    previous['purpose'] = LEDGER_PURPOSE_V12
    return previous


def _validate_prior_v14(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 14,
            'unsupported schema version')
    require(ledger['purpose'] == LEDGER_PURPOSE_V14,
            'unsupported ledger completion summary')
    issues = ledger['issues']
    numbers = [issue['number'] for issue in issues]
    require(all(type(number) is int for number in numbers),
            'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)),
            'expected unique ordered issue numbers 1–129')
    issue = issues[36]
    require('accepted_design_outcome' not in issue, 'unsupported design acceptance: 37')
    require('accepted_support_outcome' not in issue, 'unsupported support acceptance: 37')
    require(issue['canonical_acceptance_record'] == {
        'repository': REPOSITORY, 'path': IMPLEMENTATION_37_ACCEPTANCE['record_path']},
        'unreviewed acceptance coordinates or placement: 37')
    require(json_digest(issue.get('accepted_implementation_outcome')) ==
            json_digest(IMPLEMENTATION_37_ACCEPTANCE),
            'missing or changed reviewed implementation acceptance: 37')
    require([c['text_sha256'] for c in issue['criteria']] == IMPLEMENTATION_37_CRITERIA,
            'reviewed implementation criterion set changed: 37')
    for evidence_ref, expected in IMPLEMENTATION_37_EVIDENCE.items():
        require(json_digest(ledger['evidence'].get(evidence_ref)) == json_digest(expected),
                'implementation receipt is not the reviewed merged artifact: 37')
    for index, criterion in enumerate(issue['criteria'], 1):
        key = f'37.{index}'
        require(criterion['id'] == key, f'criterion identity/order mismatch: {key}')
        require(criterion['status'] == 'complete' and
                criterion['completion_evidence'] == IMPLEMENTATION_37_ACCEPTANCE['evidence_refs'],
                f'missing reviewed implementation completion evidence: {key}')
        require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                f'completed criterion still has unfinished requirements: {key}')
        require(criterion['evidence_relation'] == COMPLETE_IMPLEMENTATION_RELATION,
                f'unsupported implementation completion attribution: {key}')

    previous_v13 = reconstruct_prior_v13(ledger)
    # Run the actual entire predecessor validator first to preserve its useful
    # diagnostics and every earlier receipt/coordinate/source-text boundary.
    _validate_prior_v13(previous_v13, source)
    require(json_digest(previous_v13['issues'][36]) == PRIOR_37_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #37 context and partial history')
    require(json_digest(previous_v13) ==
            PRIOR_V13_LEDGER_SHA256,
            'unreviewed change to complete prior v13 ledger history')



# Version 15 records only the independently accepted selected #48 foundation.
# The signed record, actual receiving decision and distinct original executions
# are fixed source facts. Input flags cannot grant new acceptance or enlarge
# a console observation into an archive, pixel, physical-device or release audit.
IMPLEMENTATION_48_ACCEPTANCE = {
    "classification": "reviewed_implementation_outcome",
    "scope": "android_selected_toolchain_core_and_opt_in_preview_foundation",
    "criteria": [
        "48.1",
        "48.2",
        "48.3",
    ],
    "implemented_subtasks": [
        "Initialize the selected Android client and modules in the approved repository location.",
        "Pin compatible toolchain/wrappers/dependencies, including JDK/Gradle/Kotlin where applicable.",
        "Configure approved package identity, SDK support and debug/release variants.",
        "Document clean-checkout setup without machine-specific paths or embedded credentials.",
    ],
    "validation_methods": [
        "Execute the documented build in an isolated clean checkout.",
        "Install and launch the resulting debug artifact on an emulator or supported device.",
    ],
    "evidence_refs": [
        "pr-652",
    ],
    "record_repository": "Pick-Your-AI/pick-your-ai-android",
    "record_path": "docs/delivery/android/work-packages/02.01.md",
    "record_revision": "020d7fa2192cf3414e258c95f25554473fd3b14d",
    "record_tree": "f2168e31dd21b2e135bfa6091817c5462f6221df",
    "record_blob": "5a7793fa1daeee575e29be97c5a04ee252ee9871",
    "record_bytes": 25962,
    "record_sha256": "6f64954356c138abc7b7feb6a50347f9a36a5ef3c4724cb578e63166953eae8e",
    "record_url": "https://github.com/Pick-Your-AI/pick-your-ai-android/blob/020d7fa2192cf3414e258c95f25554473fd3b14d/docs/delivery/android/work-packages/02.01.md",
    "record_delivery": {
        "pr": 652,
        "merged_at": "2026-10-20T00:43:22Z",
        "signature_verified": True,
        "signature_reason": "valid",
        "parents": [
            "32f5bd7b70c411c8593338e5a9c726371281e806",
        ],
        "delivery_receipt": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/652#issuecomment-6280617503",
        "source_revision": "11ff6e76c3a0dc8c2bd65e741346ce235208ecc1",
        "source_tree": "c9f697dbc6277106b64f2b6f9060eb22e001f43c",
        "source_signature": "unsigned_git_data_transport",
        "source_parent": "4b9efe1dcdc60ba978906b446071d9b9d5fd9449",
        "full_delivered_leaf_count": 721,
        "unchanged_other_main_entries": 720,
        "documentation_check_receipt": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/652#issuecomment-6280603000",
        "check_scope": "The original signed preparation's Core, Preview and scanner passed. The separate installed original was in progress at record delivery; no actual-main or new native pass is inferred by this ledger.",
    },
    "receiving_identity_scope": "Actual independent in-session Android technical and CI receiving under the maintainer's delegated engineering authority; no distinct-person/account APPROVED vote, absent-assignee reply or operational appointment is inferred.",
    "receiving_reviews": [
        {
            "role": "actual_independent_android_technical_and_ci_receiving",
            "url": "https://github.com/Pick-Your-AI/pick-your-ai-android/issues/48#issuecomment-6280278860",
            "created_at": "2026-10-20T00:18:19Z",
            "body_bytes": 6149,
            "body_sha256": "078584b3b5fdc37003592a51797d98fd6fc6226a585a897466a42f9ad6112602",
            "inspected_revision": "4b9efe1dcdc60ba978906b446071d9b9d5fd9449",
            "disposition": "accepted_all_three_literal_foundation_criteria_and_four_subtasks_for_selected_preview_identity",
            "scope": "Complete canonical record, ADR0008, source/build/install chain, current input preservation and website independence; source and retained original evidence only, with no new run or raw archive acquisition.",
        },
    ],
    "source_reviews": [
        {
            "url": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/652#pullrequestreview-5579377557",
            "commit_id": "11ff6e76c3a0dc8c2bd65e741346ce235208ecc1",
            "state": "COMMENTED",
            "submitted_at": "2026-10-20T00:28:34Z",
            "body_bytes": 3212,
            "body_sha256": "a170bd8da511a09da928f84f63445de05b3d89442bb08202d2e05156c5467d0f",
        },
        {
            "url": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/652#pullrequestreview-5579456649",
            "commit_id": "11ff6e76c3a0dc8c2bd65e741346ce235208ecc1",
            "state": "COMMENTED",
            "submitted_at": "2026-10-20T00:41:42Z",
            "body_bytes": 1625,
            "body_sha256": "3046a11169905d3d017cee3c747616cb197c7e19f15a166a242444f037f1d210",
        },
    ],
    "selected_client": {
        "modules": [
            ":core",
            ":preview",
        ],
        "preview_opt_in": True,
        "package_identity": "ai.pickyour.android.preview",
        "toolchain": {
            "jdk_major": 21,
            "gradle": "8.11.1",
            "kotlin": "2.1.20",
            "android_gradle_plugin": "8.9.2",
        },
        "ordinary_debug": "unsigned_synthetic_preview",
        "installed_copy": "separately_signed_disposable_test_copy",
        "release_variant": "disabled",
        "scope_limit": "Foundation identity already accepted through #34; future :app / ai.pickyour.android and production signing are separate outcomes.",
    },
    "placement": {
        "decision_path": "docs/decisions/0008-android-48-acceptance-record.md",
        "decision_blob": "904da4ee38ec00ae57eb5ece2fbc4dd4a222eb97",
        "effective_revision": "710d337fed2004689993c0b8bc01f12de780b04c",
        "effective_delivery_pr": 448,
        "retained_prior_coordinate": "v13 already places only Android 02.01 in Pick-Your-AI/pick-your-ai-android; this amendment does not move it.",
        "identity_review": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/442#pullrequestreview-5564989747",
        "receiving_review": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/448#pullrequestreview-5565750494",
        "handoff": "https://github.com/Pick-Your-AI/pick-your-ai-android/issues/48#issuecomment-6257138171",
    },
    "prepared_original": {
        "revision": "bd2487f63745c0b9d2f83835a810766cc2a043c1",
        "tree": "4846f4ebfb499fe11ec8a99166ef23380e492b09",
        "signature_verified": True,
        "qualification": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6274389871",
        "qualification_scope": "Attributed independent original log/archive/source review, including exact producer/APK handoff, eight original images, rotations and final ACL restoration. This ledger performs no new execution or archive/pixel audit.",
        "core_cases": 5993,
        "preview_cases": 544,
        "installed_cases": 4,
        "navigation_contrast_ratio": "8.97386058887348:1",
        "required_contrast_ratio": "3:1",
        "older_failed_images": "retained_as_original_failed_evidence",
    },
    "foundation_delivery": {
        "pr": 467,
        "revision": "0f5fea42c5cd149472269da2e47559a24689ba71",
        "tree": "c9e91124c0ebbfe101e73eaa0772c5f7d3af235f",
        "signature_verified": True,
        "parents": [
            "5d9fb10d72ca059a93b00f3abf3b93da9d3e6897",
        ],
        "original_followthrough": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6274771555",
        "failed_main_original": {
            "revision": "0f5fea42c5cd149472269da2e47559a24689ba71",
            "conclusion": "failure",
            "boundary": "SDK system-image archive preparation",
            "installed_cases_executed": 0,
            "final_receipt_validated": False,
            "cause": "No cause beyond the observed archive-preparation failure is established.",
        },
        "cancelled_retry": {
            "conclusion": "cancelled",
            "new_installed_job": False,
            "scope": "The later job-only retry was cancelled without a new installed job; no later success is assigned to it.",
        },
    },
    "later_main_original": {
        "revision": "a82e4066184850a4b4152378682055531bfeae5a",
        "tree": "1287cdd361b71145aecfed7455c7132905cc8acc",
        "signature_verified": True,
        "parents": [
            "0f5fea42c5cd149472269da2e47559a24689ba71",
        ],
        "selected_decoder_changes": 4,
        "unchanged_other_parent_entries": 677,
        "run_id": 39305746890,
        "run_url": "https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39305746890",
        "core_job_id": 118246154466,
        "preview_job_id": 118246154111,
        "installed_job_id": 118250826685,
        "core_conclusion": "success",
        "preview_conclusion": "success",
        "installed_conclusion": "success",
        "installed_completed_at": "2026-10-19T19:32:01Z",
        "installed_cases": 4,
        "all_declared_installed_steps_succeeded": 16,
        "unsigned_apk_sha256": "846ec220e2d903dc5f385fa219e4d60e330a767c3139db212b98f83d3aff1808",
        "final_source_bound_receipt_and_acl_validation": True,
        "qualification": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/608#issuecomment-6275513012",
        "decoded_console_bytes": 66340,
        "decoded_console_sha256": "1a191984f10f9c2174d74219b9ef03fd942ed8774c36467bb13822376eec116e",
        "uploaded_artifact": {
            "id": 12215607030,
            "bytes": 1117643,
            "sha256": "f479a69976cecff9232480559750dc51f184c5410b88ef7afceac57ecbb43f27",
        },
        "personal_archive_received": False,
        "personal_archive_audit": False,
        "personal_junit_audit": False,
        "personal_pixel_audit": False,
        "observation_scope": "Complete actual decoded original console, all hosted step outcomes and final source-bound validators. Supported materialization did not provide local ZIP bytes; the successful hosted installation is retained within that narrower scope.",
        "no_result_relabeling": "This successful later main-push execution neither repairs the literal0f failed outcome nor passes its cancelled retry.",
    },
    "current_foundation_inputs": {
        "executed_revision": "a82e4066184850a4b4152378682055531bfeae5a",
        "inspected_revision": "4b9efe1dcdc60ba978906b446071d9b9d5fd9449",
        "inspected_tree": "74ab033c81b3016e8c7acb41573d9336fb5557bc",
        "preserved_through_record_revision": "020d7fa2192cf3414e258c95f25554473fd3b14d",
        "paths": [
            {
                "path": "settings.gradle.kts",
                "mode": "100644",
                "type": "blob",
                "blob": "da66557fffc059b8836a0f3b22536dc3cbea48fc",
            },
            {
                "path": "build.gradle.kts",
                "mode": "100644",
                "type": "blob",
                "blob": "ab50e98b093e74c3205975c93c979e008d8c07c3",
            },
            {
                "path": "core/build.gradle.kts",
                "mode": "100644",
                "type": "blob",
                "blob": "a59ad81dfaa59c2a9018726b1f685be23949d0e8",
            },
            {
                "path": "preview/build.gradle.kts",
                "mode": "100644",
                "type": "blob",
                "blob": "08611b4b7ef5135000c32e023bef9ca8934f7a0b",
            },
            {
                "path": "gradle.properties",
                "mode": "100644",
                "type": "blob",
                "blob": "5b9b501e5f55e0085d976899ad029728a22a845e",
            },
            {
                "path": "gradle/wrapper/gradle-wrapper.properties",
                "mode": "100644",
                "type": "blob",
                "blob": "eb1a55be0e150907b4442736a9f121cb6de6f2bd",
            },
            {
                "path": "gradle/wrapper/gradle-wrapper.jar",
                "mode": "100644",
                "type": "blob",
                "blob": "a4b76b9530d66f5e68d973ea569d8e19de379189",
            },
            {
                "path": "gradle/wrapper/wrapper.sha256",
                "mode": "100644",
                "type": "blob",
                "blob": "9d44ca8af69faa0e6e61caab80f7f72ecf82c3ef",
            },
            {
                "path": "gradlew",
                "mode": "100755",
                "type": "blob",
                "blob": "f5feea6d6b116baaca5a2642d4d9fa1f47d574a7",
            },
            {
                "path": "gradlew.bat",
                "mode": "100644",
                "type": "blob",
                "blob": "9d21a21834d5195c278ba17baec3115b2aaab06e",
            },
            {
                "path": "core/gradle.lockfile",
                "mode": "100644",
                "type": "blob",
                "blob": "596f93e213026a5e1a2faa286c1e384da022d7d1",
            },
            {
                "path": "preview/gradle.lockfile",
                "mode": "100644",
                "type": "blob",
                "blob": "10f0b93deee3b4b7ecd99c95a7bb5806e5054e2f",
            },
            {
                "path": "gradle/verification-metadata.xml",
                "mode": "100644",
                "type": "blob",
                "blob": "49109cf8496472aad2efb0c37f9b768fdcc09532",
            },
            {
                "path": "preview/src/main/AndroidManifest.xml",
                "mode": "100644",
                "type": "blob",
                "blob": "5f64a72f325a6548cea1cf0ac270f8fcef352d6d",
            },
            {
                "path": "requirements-contracts.txt",
                "mode": "100644",
                "type": "blob",
                "blob": "bd11036222d64a68a368bd74e87fbf7013039ee4",
            },
        ],
        "observation_scope": "All 15 documented foundation inputs retain exact bytes/modes/types. This source preservation does not execute the changed later application tree.",
    },
    "website_independence": {
        "inspected_revision": "6cea6db8a7b66ce7da1f94814c04010ed1a66753",
        "inspected_tree": "ef116ef4a20f44598740fe9b1550636e9a76ef99",
        "signature_verified": True,
        "package_blob": "931ab4034176af1b39d24c06ec8d6fe20909364b",
        "workflow_blob": "e9124e185aa760b6ab4a7be74c1273092de02542",
        "toolchain": "Node 22/npm",
        "requires_android_sdk_or_gradle": False,
        "source_observation": "The entire package scripts and CI retain independent quality, unit, contracts, build, browser and required-gate paths; no new website execution or deployment is claimed.",
        "original_execution": {
            "revision": "dc66a9e269917ab7241c7d08fd0e7f2a2f039872",
            "run_id": 38996265873,
            "quality_job_id": 117289398475,
            "unit_job_id": 117289398447,
            "build_job_id": 117289398500,
            "required_gate_job_id": 117289742133,
            "unit_cases": 65,
            "unit_files": 6,
            "scope": "Retained canonical record's original decoded quality/unit/build/ci-required results at their own source. The intentional dependency-review push skip remains distinct; no new audit, browser or deployment result is inferred.",
        },
        "later_handoff": "https://github.com/Pick-Your-AI/pick-your-ai-android/issues/48#issuecomment-6257138171",
    },
    "scope_limits": [
        "Only stable criteria48.1–48.3 and their four exact subtasks are accepted for the selected foundation.",
        "All 128 other issue records, 34 prior evidence entries, 441 literal criterion identities/text hashes, prior partial evidence and all 43 prior completions remain unchanged.",
        "Future production identity/signing, physical devices, TalkBack matrices, Play distribution, real data and release retain their own acceptance.",
        "Ledger validation is source/receipt admission and does not perform new native, website, provider or account work.",
        "Issue checkboxes and closure are reconciled separately after ordinary reviewed delivery of this ledger.",
    ],
}

IMPLEMENTATION_48_EVIDENCE = {
    "pr_url": "https://github.com/Pick-Your-AI/pick-your-ai-android/pull/652",
    "observed_state": "merged",
    "revision": "020d7fa2192cf3414e258c95f25554473fd3b14d",
    "artifact_url": "https://github.com/Pick-Your-AI/pick-your-ai-android/blob/020d7fa2192cf3414e258c95f25554473fd3b14d/docs/delivery/android/work-packages/02.01.md",
    "scope_limit": "Delivered the reviewed canonical #48 foundation receiving record at valid-signed020d, using actual independent6280278860 acceptance. The attributed bd248 archive audit, failed literal0f and cancelled retry, distinct successful a82 console/hosted validation, 15 exact inputs and independent website source/original execution remain separate. Only the three selected-foundation criteria are complete; no broader physical, release, account or operating acceptance.",
}

IMPLEMENTATION_48_CRITERIA = [
    "48394baee1202faa552240baa51f5a3a86724392455773034f4f03629a1f75f5",
    "ea26c0e64f5f5ea9f161699998d60ac1769e4a4e96fdd1d59e1f9e7f5382d9fe",
    "ae75fb12f1eda553f8eb6322e007c249bf6568d91c370939d48e64b5c774caa7",
]

PRIOR_48_FIELDS = {
    "status": "incomplete",
    "completion_evidence": [],
    "remaining_engineering": "Extend unsigned diagnostic build to the accepted app/package and exercise actual install/launch.",
    "evidence_relation": "issue_context_only; inspect artifact scope before attributing any criterion result",
}
PRIOR_48_ISSUE_SHA256 = 'c41bb287fa5deb7590dfe6254f4e62ed0f7aa27c8e27065ce26864cb7c07e891'
PRIOR_V14_LEDGER_SHA256 = '69fcf968d4c453676322d2c77960ce4c2cf7b454f5cd80df6ce1dd76b5e8d51c'
PRIOR_V14_FILE_SHA256 = 'a0082a409fb38a5e0537b71e33501aa57538784bef6859e34afd306aa528f8a8'
PRIOR_V14_FILE_BYTES = 659947
LEDGER_PURPOSE_V15 = "Living evidence index: 25 reviewed documentary/design criteria for Android #2/#20/#23/#24/#25/#34/#35/#47, 17 reviewed implementation criteria for #26/#37/#48/#84/#96, the single reviewed import-integrity criterion 21.2 and 3 reviewed bounded support criteria for #33 are complete; 395 criteria remain incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies."


def reconstruct_prior_v14(ledger):
    """Reverse only #48's new receipt/completion fields in a detached copy.

    This inverse is not admission. validate() first requires the complete fixed
    receipt and criterion fields. All other data, including unexpected fields,
    remains available to the actual predecessor validator and complete digest.
    The original placement and pr-133/pr-136 partial history are never replaced.
    """
    previous = copy.deepcopy(ledger)
    issue = previous['issues'][47]
    issue.pop('accepted_implementation_outcome')
    for criterion in issue['criteria']:
        for key, value in PRIOR_48_FIELDS.items():
            criterion[key] = copy.deepcopy(value)
    previous['evidence'].pop('pr-652')
    previous['schema_version'] = 14
    previous['purpose'] = LEDGER_PURPOSE_V14
    return previous


def _validate_prior_v15(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 15,
            'unsupported schema version')
    require(ledger['purpose'] == LEDGER_PURPOSE_V15,
            'unsupported ledger completion summary')
    issues = ledger['issues']
    numbers = [issue['number'] for issue in issues]
    require(all(type(number) is int for number in numbers),
            'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)),
            'expected unique ordered issue numbers 1–129')
    issue = issues[47]
    require('accepted_design_outcome' not in issue, 'unsupported design acceptance: 48')
    require('accepted_support_outcome' not in issue, 'unsupported support acceptance: 48')
    require(issue['canonical_acceptance_record'] == {
        'repository': REPOSITORY, 'path': IMPLEMENTATION_48_ACCEPTANCE['record_path']},
        'unreviewed acceptance coordinates or placement: 48')
    require(json_digest(issue.get('accepted_implementation_outcome')) ==
            json_digest(IMPLEMENTATION_48_ACCEPTANCE),
            'missing or changed reviewed implementation acceptance: 48')
    require([c['text_sha256'] for c in issue['criteria']] == IMPLEMENTATION_48_CRITERIA,
            'reviewed implementation criterion set changed: 48')
    require(json_digest(ledger['evidence'].get('pr-652')) ==
            json_digest(IMPLEMENTATION_48_EVIDENCE),
            'implementation receipt is not the reviewed merged artifact: 48')
    for index, criterion in enumerate(issue['criteria'], 1):
        key = f'48.{index}'
        require(criterion['id'] == key, f'criterion identity/order mismatch: {key}')
        require(criterion['status'] == 'complete' and
                criterion['completion_evidence'] == ['pr-652'],
                f'missing reviewed implementation completion evidence: {key}')
        require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                f'completed criterion still has unfinished requirements: {key}')
        require(criterion['evidence_relation'] == COMPLETE_IMPLEMENTATION_RELATION,
                f'unsupported implementation completion attribution: {key}')

    previous_v14 = reconstruct_prior_v14(ledger)
    # Preserve all predecessor diagnostics, exact issue/source comparison and
    # earlier receipt boundaries before checking the complete historical image.
    _validate_prior_v14(previous_v14, source)
    require(json_digest(previous_v14['issues'][47]) == PRIOR_48_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #48 context and partial history')
    require(json_digest(previous_v14) == PRIOR_V14_LEDGER_SHA256,
            'unreviewed change to complete prior v14 ledger history')



# Version16 receives only the delivered #98 local handoff and effective record.
IMPLEMENTATION_98_ACCEPTANCE = {'classification': 'reviewed_implementation_outcome',
 'scope': 'android_local_catalog_selection_and_bounded_component_route_handoff',
 'criteria': ['98.1', '98.2', '98.3', '98.4'],
 'implemented_subtasks': ['Carry explicit draft ID, expected local revision, kind, role and '
                          'optional replacement selection through search/detail selection '
                          'navigation.',
                          'Validate selected item/version and cardinality against the active draft '
                          'before committing one transactional add or replacement.',
                          'Route stale, missing or unsupported-version selections to a recoverable '
                          'explanation, preserving the original draft and user navigation context.',
                          'Normalize supported incoming component links through a bounded trusted '
                          'route parser; require an explicit add action before changing a build.'],
 'evidence_refs': ['pr-566', 'pr-576'],
 'implementation_revision': '12b50e0fad40581d35ae472617f06d4f48ab230d',
 'implementation_tree': '03fd9e8b543506cddfc6f46e53c42f6ba79dc5a6',
 'implementation_delivery': {'pr': 566,
                             'merged_at': '2026-10-19T14:05:39Z',
                             'parents': ['bc664e3bb9f085bc1950f05a80cb3c43c7e7f925'],
                             'signature_verified': True,
                             'signature_reason': 'valid',
                             'source_revision': '18c0fc1a649a29159f6de7bf4a72fff22d2b7b01',
                             'source_tree': '3e6bf6039fe9cb5581ed68ab83180a6a9cfe3869',
                             'source_signature': 'unsigned_git_data_transport',
                             'delivery_receipt': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/566#issuecomment-6269561061'},
 'record_repository': 'Pick-Your-AI/pick-your-ai-android',
 'record_path': 'docs/delivery/android/work-packages/07.05.md',
 'record_revision': 'fa1fece43fb0498465cc54363de132235ea2f18a',
 'record_tree': '0ca80b557829d9a36708551f061dd947e55ac460',
 'record_blob': 'ab85a283d0955d546867b06ea5e7562b503ad010',
 'record_bytes': 29800,
 'record_sha256': 'efa9f1a1e03ad6ba9532896f9bed610196c4a2e45296bb3a2fe8c948e794c2f5',
 'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/fa1fece43fb0498465cc54363de132235ea2f18a/docs/delivery/android/work-packages/07.05.md',
 'record_delivery': {'pr': 576,
                     'merged_at': '2026-10-19T16:58:14Z',
                     'parents': ['20baabebcdec38f85c5816b7c15de091fa7bee13'],
                     'signature_verified': True,
                     'signature_reason': 'valid',
                     'source_revision': 'ab4862cdd160f1342b2a00241bb5942159135ba2',
                     'source_signature': 'unsigned_git_data_transport',
                     'source_reviews': [{'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/576#pullrequestreview-5573424142',
                                         'commit_id': '28e1d82521f0266f78bd5dcee97278d6c6b8fb29',
                                         'state': 'COMMENTED',
                                         'submitted_at': '2026-10-19T14:53:21Z',
                                         'body_bytes': 3115,
                                         'body_sha256': 'fb3a31bbbf1608142796c98246b30addc11edb1317af6867061013c7c68fc573'},
                                        {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/576#pullrequestreview-5573946863',
                                         'commit_id': 'ab4862cdd160f1342b2a00241bb5942159135ba2',
                                         'state': 'COMMENTED',
                                         'submitted_at': '2026-10-19T15:31:14Z',
                                         'body_bytes': 566,
                                         'body_sha256': '2ae3871477a2993ea310c2d45583c9d830b61f1c040daa1557cd28749b2ff0d2'},
                                        {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/576#pullrequestreview-5574635614',
                                         'commit_id': 'ab4862cdd160f1342b2a00241bb5942159135ba2',
                                         'state': 'COMMENTED',
                                         'submitted_at': '2026-10-19T16:32:51Z',
                                         'body_bytes': 870,
                                         'body_sha256': 'aac7465a449d4b7ffef4570b2426dc0f8b07e87f3304f7abca84c16920f223ea'}],
                     'scope': 'Ordinary reviewed four-document delivery; its preparation outcomes '
                              'stay separate from implementation 12b push execution.'},
 'placement': {'decision': 'ADR0014',
               'path': 'docs/decisions/0014-android-98-acceptance-record.md',
               'mode': '100644',
               'blob': '1461ea8a948c16d8f9dc892009776f8b87686c3c',
               'bytes': 4871,
               'sha256': '71ef903adb5887ac13a682c145f73ba823ee35a48c8fd24648c3886ef7687459',
               'effective_revision': 'fa1fece43fb0498465cc54363de132235ea2f18a',
               'effective_delivery_pr': 576,
               'historical_coordinate': {'repository': 'Pick-Your-AI/pickyour.ai',
                                         'path': 'docs/delivery/android/work-packages/07.05.md'},
               'scope': 'Move only the already-delivered Android 07.05 record coordinate. Preserve '
                        'every other work-package location and shared authority.'},
 'receiving_reviews': [{'role': 'android_catalog_and_ux_receiver',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98#issuecomment-6269464601',
                        'created_at': '2026-10-19T14:20:22Z',
                        'body_bytes': 4612,
                        'body_sha256': '2c8d72946d5fd57590026fa1921389f248ce7ca469db255a7850ed19cbb20795',
                        'inspected_revision': '12b50e0fad40581d35ae472617f06d4f48ab230d',
                        'execution_revision': 'f44684d019ab21c04ad06395196d07cdd9c94223',
                        'disposition': 'accepted_all_four_literal_criteria_and_four_subtasks_for_the_local_handoff'},
                       {'role': 'independent_affected_data_consumer',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98#issuecomment-6269539895',
                        'created_at': '2026-10-19T14:23:50Z',
                        'body_bytes': 13248,
                        'body_sha256': '9ed047f7a138c637faa2f52024e9e36bd61306e808320834c84bc5cfa082cb3e',
                        'inspected_revision': '12b50e0fad40581d35ae472617f06d4f48ab230d',
                        'execution_revision': 'f44684d019ab21c04ad06395196d07cdd9c94223',
                        'disposition': 'accepted_all_four_literal_criteria_and_four_subtasks_for_the_local_handoff'}],
 'receiving_identity_scope': 'Actual independent in-session catalog/UX and data-consumer '
                             'engineering responsibilities under the maintainer delegation. Public '
                             'issue comments are technical receiving records; no separate '
                             'human-account APPROVED vote, absent-assignee commitment or release '
                             'authority is inferred.',
 'accepted_behavior': {'identity': 'Exact canonical kind, item ID, opaque version and original '
                                   'validated catalog-byte digest; display labels and another '
                                   'available version never substitute.',
                       'authority': 'The route grants no draft, role, revision or write authority. '
                                    'Explicit local choice and an exact captured stored '
                                    'draft/revision, catalog origin and storage token gate one '
                                    'transactional add or replacement.',
                       'replay_and_staleness': 'Stable selection IDs, busy state, retired '
                                               'callbacks and expected-revision persistence '
                                               'prevent duplicate commits; changed or removed '
                                               'targets preserve the newer draft and require '
                                               'recovery.',
                       'pending_input': 'Search/link inspection preserves raw pending fields and '
                                        'the existing750ms autosave deadline. A new stored '
                                        'revision requires a fresh selection capture.',
                       'private_route': {'scheme': 'pickyourai-preview',
                                         'authority': 'component',
                                         'path': '/v1',
                                         'fields': ['kind', 'id', 'version', 'snapshot'],
                                         'transport_bytes_max': 4096,
                                         'version_utf8_bytes_min': 1,
                                         'version_utf8_bytes_max': 256,
                                         'public_domain_association_accepted': False}},
 'consumed_prerequisites': [{'issue': 76,
                             'accepted_output': 'Local Activity entry/restoration, explicit draft '
                                                'choice, active pointer, pending-input checkpoint '
                                                'and canonical store binding at12b.',
                             'scope_limit': 'Only this local handoff consumer; broader '
                                            'route/back/task, installed process-death and '
                                            'accessibility acceptance remain with76.'},
                            {'issue': 82,
                             'accepted_output': 'Loaded validated synthetic catalog,29 canonical '
                                                'kinds/seven tiers, bounded search, deterministic '
                                                'sort, local10-row paging and guarded exact detail '
                                                'origin at12b.',
                             'scope_limit': 'No governed real catalog, remote '
                                            'paging/cancellation/retry or installed-device outcome '
                                            'is supplied; wider82 remains separate.'}],
 'implementation_source_pins': [{'path': '.github/workflows/core.yml',
                                 'mode': '100644',
                                 'blob': '98ac254121ae97e8f39fb8b83bcd71c766ae3cf7',
                                 'bytes': 119282,
                                 'sha256': '7bb2a0f6f84a4aa5a9c762d618d6bb07122ed51733b8822cb3f2d52c3db602a5'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/preview/PreviewComponentRoute.kt',
                                 'mode': '100644',
                                 'blob': '4454b1b01aa2cee8405f545b21d3f429e3e620fa',
                                 'bytes': 4579,
                                 'sha256': 'abf3dac02cc822f6c057875752b584daea1597595c4dc71fc53cc1282f541797'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/preview/PreviewComponentRouteTest.kt',
                                 'mode': '100644',
                                 'blob': '8e5f341c6f3da6a649b0b77431e385fe2331f205',
                                 'bytes': 8771,
                                 'sha256': 'e36dd34de01f69bff6207ef6e2c570b5496b5e42e411c0d23150847ab3f3795c'},
                                {'path': 'docs/COMPONENT_SELECTION_HANDOFF.md',
                                 'mode': '100644',
                                 'blob': '190fd267776ed5c3c30653012b31c5a8615c1f8b',
                                 'bytes': 8843,
                                 'sha256': '211789eb5baf560a7b0d8fa9e146b40cedf8294c81093fff0ee3f29484f45a6e'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/PreviewActivity.kt',
                                 'mode': '100644',
                                 'blob': 'd3bdf7c791d5a14f2d342a8a53850824eb203963',
                                 'bytes': 87445,
                                 'sha256': '21124e7d0bc3faef6f7de70cbcfd095990026ebe2886317822d160d8462bf758'},
                                {'path': 'preview/src/main/res/values/catalog_selection_strings.xml',
                                 'mode': '100644',
                                 'blob': '0d9d874f4d6c80d977addc152243f909ca6c2f8c',
                                 'bytes': 729,
                                 'sha256': 'f811a6486166107e1721e2341f6a8193a196b2af757a3f515c739fc919587451'},
                                {'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CatalogSelectionHandoffActivityTest.kt',
                                 'mode': '100644',
                                 'blob': '8559d25d1d3570eb3fec809f46727dadea01f708',
                                 'bytes': 19049,
                                 'sha256': 'ca3221b19299af6a946735d7f71161dcd40ee989fba1106ca06f0f33bbfc62e4'},
                                {'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/IncomingComponentLinkActivityTest.kt',
                                 'mode': '100644',
                                 'blob': '65978bf40490b0fe72eea84773d0e608de8831f2',
                                 'bytes': 27329,
                                 'sha256': 'be5fb44041c8bfbb4b109c2873e29de2546353b1f3a5da9ab31371b3932bf979'}],
 'authored_regression_cohorts': [{'class': 'ai.pickyour.core.preview.PreviewComponentRouteTest',
                                  'path': 'core/src/test/kotlin/ai/pickyour/core/preview/PreviewComponentRouteTest.kt',
                                  'mode': '100644',
                                  'blob': '8e5f341c6f3da6a649b0b77431e385fe2331f205',
                                  'bytes': 8771,
                                  'sha256': 'e36dd34de01f69bff6207ef6e2c570b5496b5e42e411c0d23150847ab3f3795c',
                                  'methods': ['diagnostics disclose no incoming version or '
                                              'identity()',
                                              'empty and oversized raw entry is rejected before '
                                              'any catalog use()',
                                              'malformed UTF8 never becomes replacement characters '
                                              'or a different version()',
                                              'matching requires the exact kind item version and '
                                              'raw snapshot identity()',
                                              'normalized checkpoint restoration repeats every '
                                              'identity admission check()',
                                              'only frozen canonical kinds and bounded canonical '
                                              'item identifiers are accepted()',
                                              'only the exact private route authority and path are '
                                              'recognized()',
                                              'parameter order transport case and hex spelling do '
                                              'not change identity()',
                                              'raw nonASCII whitespace backslashes and decoded '
                                              'control characters reject()',
                                              'reserved delimiters are unambiguous and values are '
                                              'decoded only once()',
                                              'snapshot identity requires exactly a full '
                                              'hexadecimal digest()',
                                              'the four explicit fields survive parsing with exact '
                                              'opaque version spelling()',
                                              'unknown duplicate missing empty and encoded '
                                              'parameter keys reject the complete route()',
                                              'version budget counts strict UTF8 bytes without '
                                              'trimming or Unicode normalization()']},
                                 {'class': 'ai.pickyour.android.preview.CatalogSelectionHandoffActivityTest',
                                  'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CatalogSelectionHandoffActivityTest.kt',
                                  'mode': '100644',
                                  'blob': '8559d25d1d3570eb3fec809f46727dadea01f708',
                                  'bytes': 19049,
                                  'sha256': 'ca3221b19299af6a946735d7f71161dcd40ee989fba1106ca06f0f33bbfc62e4',
                                  'methods': ['aRemovedReplacementTargetCannotOverwriteTheOtherWritersNewRevision',
                                              'cardinalityAndDuplicateRoleRejectionRetainInputUntilExplicitValidCorrection',
                                              'changedCatalogRejectsTheOriginalSearchActionWithoutSelectingAnotherVersion',
                                              'changedSavedDraftRetiresSearchAuthorityAndExplainsRecoveryWithoutOverwriting',
                                              'pendingBuilderTextDisablesSearchSelectionAndRemainsUntouched',
                                              'recreationRetiresTheSearchSelectionOriginWithoutChangingTheSavedDraft',
                                              'searchReplacementPreservesPrimaryAndSecondaryIdentitiesAndExactlyTheirRoles',
                                              'searchResultAddsExactlyOnePinnedSelectionToTheCapturedDraftAndRole']},
                                 {'class': 'ai.pickyour.android.preview.IncomingComponentLinkActivityTest',
                                  'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/IncomingComponentLinkActivityTest.kt',
                                  'mode': '100644',
                                  'blob': '65978bf40490b0fe72eea84773d0e608de8831f2',
                                  'bytes': 27329,
                                  'sha256': 'be5fb44041c8bfbb4b109c2873e29de2546353b1f3a5da9ab31371b3932bf979',
                                  'methods': ['backgroundAndDestroyedLinkControlsCannotOpenAWindowOrWriteTheDraft',
                                              'coldComponentLinkStaysUnboundAcrossRecreationUntilAnExplicitLocalChoice',
                                              'corruptedNormalizedCheckpointCannotReparseOriginalIntentOrAcquireAnActiveDraft',
                                              'incomingDeliveryCannotDismissCommitOrReplaceAnOpenComponentForm',
                                              'malformedOversizedAndPermissionBearingLinksCannotReplaceWarmDraftOrBrowseInput',
                                              'missingKindItemVersionAndSnapshotNeverFallBackToAnAvailableSelection',
                                              'narrowRtlLargeTextKeepsIncomingAndDraftSelectionActionsReachable',
                                              'recognizedWarmLinkPreservesUncommittedBuilderContextAcrossRecreation',
                                              'warmComponentLinkRequiresExplicitAddAndRepeatedDeliveryCannotDuplicateIt',
                                              'warmLinkPreservesTheAutosaveDeadlineAndRequiresAFreshSelectionCapture']}],
 'prepared_original': {'revision': 'f44684d019ab21c04ad06395196d07cdd9c94223',
                       'tree': '3e6bf6039fe9cb5581ed68ab83180a6a9cfe3869',
                       'event': 'pull_request',
                       'attempt': 1,
                       'core_preview_run': 39257450934,
                       'jobs': [{'role': 'core',
                                 'id': 118076196433,
                                 'conclusion': 'success',
                                 'successful_step_records': 27,
                                 'passing_cases': 5819,
                                 'suites': 106},
                                {'role': 'simulated_preview',
                                 'id': 118076196890,
                                 'conclusion': 'success',
                                 'successful_step_records': 58,
                                 'passing_cases': 487,
                                 'suites': 46},
                                {'role': 'scanner',
                                 'id': 118076196243,
                                 'run_id': 39257450948,
                                 'conclusion': 'success',
                                 'successful_step_records': 7}],
                       'passing_cases': 6306,
                       'failures': 0,
                       'errors': 0,
                       'skips': 0,
                       'duplicate_cases': 0,
                       'successful_step_records': 92,
                       'configured_attendance_programs': 44,
                       'python_executions': {'core': 333, 'preview': 148, 'scanner': 28},
                       'original_artifacts': [{'role': 'core',
                                               'id': 12189690009,
                                               'bytes': 1728188,
                                               'sha256': '8ba2b6e4cefcd3571393d70b7cd9287f1d0884c419be3ede9fbd1616774cc79b'},
                                              {'role': 'simulated_preview',
                                               'id': 12188636820,
                                               'bytes': 3289993,
                                               'sha256': '0b978f39b729a95f23d87d22a76e398f2784ea1f3da801cfa3842cfcb9a1fc3e'},
                                              {'role': 'runtime_dependencies',
                                               'id': 12188118087,
                                               'bytes': 4375363,
                                               'sha256': '856395d90d170363289141125ebf085f190789f153556bc2342a2c6c010293ce'},
                                              {'role': 'scanner',
                                               'id': 12187944442,
                                               'bytes': 1287,
                                               'sha256': '6c93ab4264eb76ce8a42ea9c56921652e26818cc1a95be80f48b6db13978ef12'}],
                       'safe_crc_archive_members': 364,
                       'authored_selection_cases': 32,
                       'qualification': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/566#issuecomment-6268641401',
                                         'created_at': '2026-10-19T13:41:31Z',
                                         'body_bytes': 5854,
                                         'body_sha256': 'cd36e9f82a27df9287f2d709ffb4c1ddf5281bb3288a29091b4c299dab6a4b27'},
                       'scanner': {'raw_exit': 10,
                                   'reachable_commits': 239,
                                   'raw_findings': 2,
                                   'reviewed_public_checksum_findings': 2,
                                   'unresolved_findings': 0,
                                   'disposition': 'passed_with_reviewed_findings'},
                       'observation_scope': 'Attributed complete original XML/archive/log/source '
                                            'qualification at this exact checkout; no new archive '
                                            'acquisition, product execution, installed-device or '
                                            'release audit by the ledger amendment.'},
 'main_original': {'revision': '12b50e0fad40581d35ae472617f06d4f48ab230d',
                   'tree': '03fd9e8b543506cddfc6f46e53c42f6ba79dc5a6',
                   'event': 'push',
                   'attempt': 1,
                   'core_preview_run': 39265845662,
                   'jobs': [{'role': 'core',
                             'id': 118105072596,
                             'conclusion': 'success',
                             'successful_step_records': 27,
                             'passing_cases': 5819,
                             'suites': 106},
                            {'role': 'simulated_preview',
                             'id': 118105072945,
                             'conclusion': 'success',
                             'successful_step_records': 58,
                             'passing_cases': 495,
                             'suites': 46},
                            {'role': 'scanner',
                             'id': 118105075857,
                             'run_id': 39265846120,
                             'conclusion': 'success',
                             'successful_step_records': 7}],
                   'passing_cases': 6314,
                   'failures': 0,
                   'errors': 0,
                   'skips': 0,
                   'duplicate_cases': 0,
                   'successful_step_records': 92,
                   'configured_attendance_programs': 44,
                   'python_executions': {'core': 333, 'preview': 148, 'scanner': 28},
                   'original_artifacts': [{'role': 'core',
                                           'id': 12192828305,
                                           'bytes': 1724949,
                                           'sha256': 'e812a0c4465b934192c121aee7644830a31b3d14dec9267de979b2acbc44f489'},
                                          {'role': 'simulated_preview',
                                           'id': 12192988906,
                                           'bytes': 3291405,
                                           'sha256': '89999e39d112b4c0e0393f7b9d549f61325ece584fc70b8ed02a8c7cdb31f3b6'},
                                          {'role': 'runtime_dependencies',
                                           'id': 12193653052,
                                           'bytes': 4375748,
                                           'sha256': '9bdfa6c834797d626763f4ff7c5f9fb66a96d1f8b145768d8f329c6ee6ba4e09'},
                                          {'role': 'scanner',
                                           'id': 12194000269,
                                           'bytes': 1241,
                                           'sha256': '2e57523da048b3e3edbc049d38ec9e41d0bcaf6de809fe7d16b9f335ec8b3444'}],
                   'safe_crc_archive_members': 364,
                   'authored_selection_cases': 32,
                   'qualification': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/566#issuecomment-6269561061',
                                     'created_at': '2026-10-19T14:24:49Z',
                                     'body_bytes': 7163,
                                     'body_sha256': '0e6856baf9e70ad964d4c1c3911503d0583102b3708346d4581cdeb51a4ea961'},
                   'scanner': {'raw_exit': 10,
                               'reachable_commits': 238,
                               'raw_findings': 1,
                               'reviewed_public_checksum_findings': 1,
                               'unresolved_findings': 0,
                               'disposition': 'passed_with_reviewed_findings'},
                   'observation_scope': 'Attributed complete original XML/archive/log/source '
                                        'qualification at this exact checkout; no new archive '
                                        'acquisition, product execution, installed-device or '
                                        'release audit by the ledger amendment.'},
 'execution_preservation': {'retained_prepared_case_identities': 6306,
                            'added_main_recovery_case_identities': 8,
                            'all_32_authored_selection_identities_pass_in_both_originals': True,
                            'source_and_execution_scope': 'The separately accepted552 recovery '
                                                          'carry explains the eight new main '
                                                          'methods. No f446 result is reassigned '
                                                          'to12b or the later record/main.'},
 'scope_limits': ['Only criteria98.1–98.4 and their four literal subtasks are completed for the '
                  'received local handoff.',
                  'All128 other issues,437 other criterion objects,35 prior evidence objects and '
                  'every441 literal identity/text/hash remain exact.',
                  'Earlier partial pr-137 evidence and the original website coordinate remain '
                  'recoverable through the complete prior-v15 inverse.',
                  'The earlier unpublished190-control preparation was lost with its workspace. '
                  'This is a new source reconstruction against actual v15; no unavailable private '
                  'file or original archive is claimed present.',
                  'The existing public receiving decisions and original qualifications are '
                  'attributed evidence. This ledger adds no Android product run, physical-device, '
                  'installed link, governed-data, domain association or release result.',
                  'The separately reviewed21.3/113.2 offer, wider76/82 work and all other records '
                  'remain outside this amendment.',
                  'Live issue and Project reconciliation follows actual reviewed ledger delivery '
                  'and fresh exact readback.']}

IMPLEMENTATION_98_EVIDENCE = {'pr-566': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/566',
            'observed_state': 'merged',
            'revision': '12b50e0fad40581d35ae472617f06d4f48ab230d',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/commit/12b50e0fad40581d35ae472617f06d4f48ab230d',
            'scope_limit': 'Signed local search/component-route implementation at12b, exact '
                           'expected-draft/revision/role/catalog authority, explicit single '
                           'mutation and preserved context. Originalf446 preparation and '
                           'separate12b push reports are attributed to their exact sources; no '
                           'domain App Link, installed-device or release acceptance.'},
 'pr-576': {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/576',
            'observed_state': 'merged',
            'revision': 'fa1fece43fb0498465cc54363de132235ea2f18a',
            'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/fa1fece43fb0498465cc54363de132235ea2f18a/docs/delivery/android/work-packages/07.05.md',
            'scope_limit': 'Signed delivery of the reviewed07.05 record and effective ADR0014, '
                           'including the actual catalog/UX and data-consumer decisions. Only the '
                           'exact four98 local-handoff criteria and record coordinate are '
                           'received; other prerequisites, records and release scopes remain '
                           'separate.'}}

IMPLEMENTATION_98_CRITERIA = [{'id': '98.1',
  'text': 'Add/replace selection deterministically, preserve builder state, handle removed or '
          'incompatible-version entries and untrusted links.',
  'text_sha256': '825886054a142f8c9dabe9592ad31c1cac676c82bf1c7ec7657f830e4b0e1f0e'},
 {'id': '98.2',
  'text': 'A selection commits exactly once to the intended draft/role, and repeated taps or '
          'repeated navigation delivery cannot create duplicate selections.',
  'text_sha256': 'd56683218cd97a1c566925aa97c3fc202278b870c54fbcf5525b60a17c370c07'},
 {'id': '98.3',
  'text': 'A changed draft revision or removed replacement target is detected before mutation and '
          'does not overwrite a newer edit.',
  'text_sha256': 'cd04d9509daf09ffa5ed70218554e6f3362a27ed7c31b121a0c56aafd9d89f23'},
 {'id': '98.4',
  'text': 'Opening a component link never automatically edits the active build or resolves an '
          'unavailable pinned version to an unrequested substitute.',
  'text_sha256': '7f098cfb1e4a24410df9379e11e5eeb8461f05ee7bbf642f4f7b11699fff2b7d'}]

PRIOR_98_FIELDS = {'status': 'incomplete',
 'completion_evidence': [],
 'remaining_engineering': 'Integrate validated links with explicit target/revision-safe selection; '
                          'opening links must not mutate.',
 'evidence_relation': 'issue_context_only; inspect artifact scope before attributing any criterion '
                      'result'}

PRIOR_98_REPOSITORY = 'Pick-Your-AI/pickyour.ai'

PRIOR_98_ISSUE_SHA256 = 'd813265d48de917cc944149f37a04987a45c6797e19654f48d065a300d3c3bf7'

PRIOR_V15_LEDGER_SHA256 = '6c258512f46a4e7cfcf79b7d1e8da85f56c5b7497bfc3978e005f01090b6da1e'

PRIOR_V15_FILE_SHA256 = '6bb1bfacfca018c0f4f4c2e7c4c279eb4c94b240971b800275b6e982ff60dafc'

PRIOR_V15_FILE_BYTES = 676687

LEDGER_PURPOSE_V16 = ('Living evidence index: 25 reviewed documentary/design criteria for Android '
 '#2/#20/#23/#24/#25/#34/#35/#47, 21 reviewed implementation criteria for #26/#37/#48/#84/#96/#98, '
 'the single reviewed import-integrity criterion 21.2 and 3 reviewed bounded support criteria for '
 '#33 are complete; 391 criteria remain incomplete. This does not replace acceptance receipts, '
 'GitHub issue status or native dependencies.')


def reconstruct_prior_v15(ledger):
    """Reverse only98's accepted fields in a detached copy, retaining unknown data.

    This is not admission: validate requires the complete reviewed new layer and
    runs the unchanged predecessor validator before the whole-v15 identity check.
    """
    previous = copy.deepcopy(ledger)
    issue = previous['issues'][97]
    issue.pop('accepted_implementation_outcome')
    issue['canonical_acceptance_record']['repository'] = PRIOR_98_REPOSITORY
    for criterion in issue['criteria']:
        for key, value in PRIOR_98_FIELDS.items():
            criterion[key] = copy.deepcopy(value)
    for key in IMPLEMENTATION_98_EVIDENCE:
        previous['evidence'].pop(key)
    previous['schema_version'] = 15
    previous['purpose'] = LEDGER_PURPOSE_V15
    return previous


def _validate_prior_v16(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 16,
            'unsupported schema version')
    require(ledger['purpose'] == LEDGER_PURPOSE_V16,
            'unsupported ledger completion summary')
    numbers = [issue['number'] for issue in ledger['issues']]
    require(all(type(number) is int for number in numbers),
            'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)),
            'expected unique ordered issue numbers 1–129')
    issue = ledger['issues'][97]
    require('accepted_design_outcome' not in issue, 'unsupported design acceptance: 98')
    require('accepted_support_outcome' not in issue, 'unsupported support acceptance: 98')
    require(issue['canonical_acceptance_record'] == {
        'repository': REPOSITORY, 'path': IMPLEMENTATION_98_ACCEPTANCE['record_path']},
        'unreviewed acceptance coordinates or placement: 98')
    require(json_digest(issue.get('accepted_implementation_outcome')) ==
            json_digest(IMPLEMENTATION_98_ACCEPTANCE),
            'missing or changed reviewed implementation acceptance: 98')
    require([{key: c[key] for key in ('id', 'text', 'text_sha256')} for c in issue['criteria']] ==
            IMPLEMENTATION_98_CRITERIA, 'reviewed implementation criterion set changed: 98')
    for key, expected in IMPLEMENTATION_98_EVIDENCE.items():
        require(json_digest(ledger['evidence'].get(key)) == json_digest(expected),
                'implementation receipt is not the reviewed merged artifact: 98')
    for index, criterion in enumerate(issue['criteria'], 1):
        key = f'98.{index}'
        require(criterion['status'] == 'complete' and
                criterion['completion_evidence'] == ['pr-566', 'pr-576'],
                f'missing reviewed implementation completion evidence: {key}')
        require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                f'completed criterion still has unfinished requirements: {key}')
        require(criterion['evidence_relation'] == COMPLETE_IMPLEMENTATION_RELATION,
                f'unsupported implementation completion attribution: {key}')
    previous_v15 = reconstruct_prior_v15(ledger)
    _validate_prior_v15(previous_v15, source)
    require(json_digest(previous_v15['issues'][97]) == PRIOR_98_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #98 context and partial history')
    require(json_digest(previous_v15) == PRIOR_V15_LEDGER_SHA256,
            'unreviewed change to complete prior v15 ledger history')


# Version 17 admits only the actually reviewed #100 diagnostic outcome and
# effective ADR0015 placement, preserving the complete accepted #98/v16 layer.
# Source-fixed receipts keep original execution, receiving and delivery distinct.
IMPLEMENTATION_100_ACCEPTANCE = {'classification': 'reviewed_implementation_outcome',
 'scope': 'local-stored-draft-current-diagnostics/1',
 'criteria': ['100.1', '100.2', '100.3', '100.4'],
 'implemented_subtasks': ['Calculate affected kinds and selections using graph edges active before or after '
                          'an edit, including inverse relationships needed for reevaluation.',
                          'Treat unknown activation conservatively and traverse cyclic relationships with '
                          'bounded visited-state handling.',
                          'Invalidate evaluation, candidate and derived resource/cost state for impacted '
                          'identities while keeping unaffected historical reports immutable.',
                          'Publish a deterministic rechecking state and affected-selection set to the '
                          'Android UI without inventing new dependency policy.'],
 'validation_methods': ['Run graph fixtures for model-to-infrastructure, accelerator-to-model, mode switches '
                        'and optional-tier disable with before/after expected sets.',
                        'Inject cycles and unknown edge predicates; assert bounded completion, conservative '
                        'affected sets and unchanged historical reports.'],
 'evidence_refs': ['pr-663'],
 'record_repository': 'Pick-Your-AI/pick-your-ai-android',
 'record_path': 'docs/delivery/android/work-packages/09.02.md',
 'record_revision': 'd6697ed824332070a30f8d7ecab938add5838156',
 'record_tree': 'c804f446f6cb555ddfac365d433b461215053417',
 'record_blob': '449427e9d7b62a908a877e6c77106d9d01148ec6',
 'record_bytes': 26133,
 'record_sha256': '43b23f47b62ad44fe3ff5a31be7c0e425b6ab158e5aec5945b03b37e005416c7',
 'record_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/d6697ed824332070a30f8d7ecab938add5838156/docs/delivery/android/work-packages/09.02.md',
 'record_delivery': {'pr': 663,
                     'merged_at': '2026-10-20T03:36:23Z',
                     'signature_verified': True,
                     'signature_reason': 'valid',
                     'parents': ['3b49f484ea848a635e1c8fee8edb5c9f427a9f3b'],
                     'source_revision': 'dd0643ddfb7c670a701af47aa46480f45bd5c376',
                     'source_tree': 'eae75e70122d8390dc6568dafe0fb34cb960dfeb',
                     'source_signature': 'unsigned_git_data_transport',
                     'source_parent': 'ffc4ad81378895bb5675b0d6a0a126b3b89448f4',
                     'full_delivered_leaf_count': 751,
                     'exact_authored_documents': 4,
                     'unchanged_other_main_entries': 747,
                     'qualification': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/663#issuecomment-6282823662',
                                       'id': 6282823662,
                                       'body_bytes': 4605,
                                       'body_sha256': 'fef03d0d63c07dcd345af73706ed2eb189733a2b788face8be9cc7d4f1ca75a0',
                                       'created_at': '2026-10-20T03:43:45Z',
                                       'updated_at': '2026-10-20T03:43:45Z'},
                     'source_review': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/663#pullrequestreview-5579846515',
                                       'id': 5579846515,
                                       'body_bytes': 3928,
                                       'body_sha256': 'cfab0ff7f02d2f025821dabd037b22a10ddeff3cd40c057d2883be2bbe76aeb5',
                                       'submitted_at': '2026-10-20T01:46:13Z',
                                       'commit_id': 'dd0643ddfb7c670a701af47aa46480f45bd5c376',
                                       'state': 'COMMENTED'},
                     'scope': 'Normal reviewed delivery makes ADR0015 effective. Its four exact document '
                              'leaves compose with all other current-main entries; original preparation, '
                              'delivered main and ledger reconciliation are separate identities.'},
 'placement': {'path': 'docs/decisions/0015-android-100-acceptance-record.md',
               'blob': 'a85518659e45c65a6f0f03175254e96b260e2ed2',
               'bytes': 6395,
               'sha256': '42e860498b642ea25259c3b374f5101ffc6d0fcd2632dbd3ff6cbd88435d9411',
               'historical_acceptance_record': {'repository': 'Pick-Your-AI/pickyour.ai',
                                                'path': 'docs/delivery/android/work-packages/09.02.md'},
               'current_acceptance_record': {'repository': 'Pick-Your-AI/pick-your-ai-android',
                                             'path': 'docs/delivery/android/work-packages/09.02.md'},
               'effective_revision': 'd6697ed824332070a30f8d7ecab938add5838156',
               'effective_delivery_pr': 663,
               'scope': 'Only Android 09.02 moves to the same path beside its implementation. All other '
                        'acceptance coordinates, website source, native prerequisites and project '
                        'assignments remain unchanged.'},
 'receiving_reviews': [{'role': 'governed_compatibility_capacity',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/100#issuecomment-6281024595',
                        'id': 6281024595,
                        'body_bytes': 8849,
                        'body_sha256': 'cf247e52de1a733bb6f3a7930c63e0ee64f83785b8895bc18b24a11dd7952257',
                        'created_at': '2026-10-20T01:16:01Z',
                        'updated_at': '2026-10-20T01:16:55Z'},
                       {'role': 'android_domain',
                        'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/100#issuecomment-6281144652',
                        'id': 6281144652,
                        'body_bytes': 11004,
                        'body_sha256': 'a05421cb486a8e38c259220a61e994b06476073fe89e0dbeca5273b4a4c9093f',
                        'created_at': '2026-10-20T01:25:46Z',
                        'updated_at': '2026-10-20T01:27:13Z'}],
 'receiving_disposition': 'accept_with_constraints_all_four_literal_criteria_and_four_tasks',
 'receiving_identity_scope': 'Actual independent in-session engineering decisions by /root and /root/apple '
                             'under the maintainer delegation through the shared CompleteDotTech account. No '
                             'absent assignee, distinct-account APPROVED vote or operational appointment is '
                             'inferred.',
 'receiving_record_tree': '230c7f84f765695acf81367b199281c2976af59c',
 'receiving_record_parent': '300be53170f6ff2a0c1ed9a4f99ac3931b39ddb3',
 'prerequisite': {'issue': 84,
                  'profile': 'local-retained-frozen-v1-no-external-evidence/1',
                  'record_path': 'docs/delivery/android/work-packages/09.01.md',
                  'governed_receiving': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/84#issuecomment-6260202471',
                  'android_receiving': 'https://github.com/Pick-Your-AI/pick-your-ai-android/issues/84#issuecomment-6260223696',
                  'scope': 'Reuse the accepted private interpreter, supported frozen-v1 semantics and exact '
                           'input/report binding. No additional operands, production authority or external '
                           'evidence is admitted.'},
 'implementation_delivery': {'pr': 620,
                             'revision': '32f5bd7b70c411c8593338e5a9c726371281e806',
                             'tree': '285244c4726c978e0b2d4b88ddbd0890e25d650c',
                             'signature_verified': True,
                             'merged_at': '2026-10-20T00:34:54Z',
                             'full_delivered_leaf_count': 721,
                             'readback': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/620#issuecomment-6280535230',
                             'execution_relation': '720 exact receiving entries plus the separately accepted '
                                                   'count-label resource '
                                                   '0226940774f09592c25a25437c1e1e0096372340; accepted main '
                                                   'is not relabeled as execution of c111.'},
 'implementation_source_pins': [{'path': 'core/src/main/kotlin/ai/pickyour/core/invalidation/DependencyInvalidation.kt',
                                 'blob': '062b65591c65053a2413d24c4cf154f1afe3e3a8'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/DraftCurrentEvaluation.kt',
                                 'blob': '965d29c29e2c4bee51625ae7b402d50698788d8e'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/preview/PreviewCurrentDraftAnalysis.kt',
                                 'blob': 'cf4d17c4b7c9c3cb2c3d6a0122056f3b867050a8'},
                                {'path': 'core/src/main/kotlin/ai/pickyour/core/evaluation/FrozenDiagnosticEvaluator.kt',
                                 'blob': 'f66cb21d610597366d22caa4a1b4eecdef2b19cd'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/CurrentDraftCheckSection.kt',
                                 'blob': '0ec4e5da9daa9007edc38e63f6bff939573830fe'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/PreviewActivity.kt',
                                 'blob': '983225c51ded914a6d6081d36d8aecd3517a1efd'},
                                {'path': 'preview/src/main/kotlin/ai/pickyour/android/preview/CurrentDraftSummarySection.kt',
                                 'blob': '475339fe26a45fba70c6c52aa3a297ad6077cf2e'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/invalidation/DependencyActivationChangeTest.kt',
                                 'blob': '7190ade068ac3951dbe6635f9ba8b8418e3b8e10'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/invalidation/DependencyInvalidationTest.kt',
                                 'blob': '068b557e340c68cf685a59c124d810c88ac8fd00'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/evaluation/DraftCurrentEvaluationTest.kt',
                                 'blob': 'ae9d784be6f08ad23297c62e1aaf80d314a12f67'},
                                {'path': 'core/src/test/kotlin/ai/pickyour/core/preview/PreviewCurrentDraftAnalysisTest.kt',
                                 'blob': '87212b229a309aa72ec7083a4ec8dceb94f2d5fc'},
                                {'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CurrentDraftCheckActivityTest.kt',
                                 'blob': 'c42cb969dfb9af369a7525d59cc7e9b0e3ac9396'},
                                {'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/CurrentDraftCheckPresentationTest.kt',
                                 'blob': 'd645061d310973ae93cc09214b759ea22fd08454'},
                                {'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/SiblingInspectionActivityTest.kt',
                                 'blob': '0b665d89ca249ae8ab552153de77032759d65659'},
                                {'path': 'preview/src/test/kotlin/ai/pickyour/android/preview/PreviewDraftReads.kt',
                                 'blob': '339224128100160b8d9ce2953ba1c22d315607b7'},
                                {'path': '.github/workflows/core.yml',
                                 'blob': 'cf98c6417dd7fa7b7357131ec9df037100f59bfe'}],
 'implementation_original': {'revision': 'c11134e77d8a807669fd83cae55d966628a6d741',
                             'tree': '3877233de00217b02c1b449df13dc8c6f00ce8ce',
                             'signature_verified': True,
                             'run_id': 39346237697,
                             'core_job_id': 118379405016,
                             'preview_job_id': 118379405678,
                             'installed_job_id': 118382395523,
                             'core_cases': 6352,
                             'core_xml_suites': 124,
                             'preview_cases': 683,
                             'preview_xml_suites': 67,
                             'installed_cases': 4,
                             'failures': 0,
                             'errors': 0,
                             'skipped': 0,
                             'core_preview_qualification': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/620#issuecomment-6280489480',
                                                            'id': 6280489480,
                                                            'body_bytes': 1860,
                                                            'body_sha256': 'b1e8d2825f3c84126b08c5afdd1f295cfd17b4524e8ef67157ab0811166843e4',
                                                            'created_at': '2026-10-20T00:34:12Z',
                                                            'updated_at': '2026-10-20T00:34:12Z'},
                             'installed_qualification': {'url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/620#issuecomment-6280990301',
                                                         'id': 6280990301,
                                                         'body_bytes': 2050,
                                                         'body_sha256': '56419c1e7a0cb6fd4c705e9da4e90b430baed4288acf5713178e9125bff0fa30',
                                                         'created_at': '2026-10-20T01:13:18Z',
                                                         'updated_at': '2026-10-20T01:13:18Z'},
                             'unsigned_apk_bytes': 3512882,
                             'unsigned_apk_sha256': '59ed94c15bc27fa263a8c44685ec671d0f913a4ccc0d99f93a56ab096b502ef2',
                             'installed_artifact': {'id': 12232441850,
                                                    'bytes': 1127788,
                                                    'sha256': '3e53514663dbe914b001d6358e1677e0bbe925f69b052a590c912a5eac80509a',
                                                    'safe_unique_members': 32},
                             'scope': 'Attributed receiving-owner original qualifications read in full. This '
                                      'ledger author did not reacquire c111 archives or execute another '
                                      'native run. Earlier compile failure, 6294/629 failed composition and '
                                      'separate local/historical cohorts remain in the canonical record; no '
                                      'counts are summed across cohorts.'},
 'record_original': {'revision': 'a05f605b8f1fb1ad1be9a893e5a16b54e3ce60e5',
                     'tree': 'eae75e70122d8390dc6568dafe0fb34cb960dfeb',
                     'signature_verified': True,
                     'parents': ['ffc4ad81378895bb5675b0d6a0a126b3b89448f4',
                                 'dd0643ddfb7c670a701af47aa46480f45bd5c376'],
                     'run_id': 39355159448,
                     'run_attempt': 1,
                     'jobs': {'core': 118408144438, 'preview': 118408144262, 'installed': 118411506449},
                     'artifacts': {'core': {'id': 12235711215,
                                            'bytes': 2235300,
                                            'sha256': '463ef26c6c37e5860fdcfb8b351160c0b7be4d806f2fbfed2c35e4dc62284a6d',
                                            'safeUniqueCrcShaVerifiedMembers': 287},
                                   'preview': {'id': 12234869012,
                                               'bytes': 3651163,
                                               'sha256': 'd1ae3a0725d57dff57c8d4d71060f3d6e8f617ebe6397f0efc2af0fdf0e16c25',
                                               'safeUniqueCrcShaVerifiedMembers': 152},
                                   'runtime': {'id': 12234963741,
                                               'bytes': 5100071,
                                               'sha256': '54a5bf6dbad6c56c78f933044e1718906420f8b59d8b09de119b773cb1adbd21',
                                               'safeUniqueCrcShaVerifiedMembers': 8},
                                   'installed': {'id': 12236685491,
                                                 'bytes': 1118882,
                                                 'sha256': '3e7e383503bedd58902fb39611ed91423933c6b7002321cd77f3a66027781744',
                                                 'safeUniqueCrcShaVerifiedMembers': 32}},
                     'junit': {'core': {'suites': 124,
                                        'uniqueCases': 6366,
                                        'failures': 0,
                                        'errors': 0,
                                        'skipped': 0},
                               'preview': {'suites': 69,
                                           'uniqueCases': 716,
                                           'failures': 0,
                                           'errors': 0,
                                           'skipped': 0}},
                     'original_decoded_logs': {'core': {'bytes': 253151,
                                                        'sha256': 'f4f157db7dd87f090674691f2e624c2d12101506c3aae0142437675fdf31138b',
                                                        'literalCheckoutConfirmed': True},
                                               'preview': {'bytes': 313825,
                                                           'sha256': '66184518074cdbdf32d307508b2d7ebd3d971312e990ee2863463ef1de3401d9',
                                                           'literalCheckoutConfirmed': True},
                                               'installed': {'bytes': 66185,
                                                             'sha256': 'a1dae670580f9f61e41f924e26cec2b7f0e40ca09ef3a969ae6eb7f27ab02ac4',
                                                             'literalCheckoutConfirmed': True}},
                     'unsigned_apk': {'bytes': 3526634,
                                      'sha256': '22806e369e1c505c34b7f8be71481ce78e447c696c60c4e107a0baf0d3bb6a07',
                                      'previewArchiveRuntimeInventoryAndInstalledReceiptsMatch': True},
                     'installed_case_ids': ['installed_synthetic_launch',
                                            'acknowledged_save_survives_force_stop',
                                            'pending_fields_survive_rotation',
                                            'confirmed_deletion_does_not_resurrect'],
                     'hierarchy_dumps': {'attempted': 581, 'completed': 581, 'exit_137': 0},
                     'original_uid': 1001,
                     'original_identity_and_kvm_acl_restored': True,
                     'archive_audit': '479 safe unique original members checked by full byte length, CRC and '
                                      'SHA-256; all inventory source pins and each producer own output hash '
                                      'verified.',
                     'offline_validators': 'Exact source complete_receipt and complete_boundary passed '
                                           'against recorded historical context; current_context was not '
                                           'invoked.',
                     'scanner_qualification': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/663#issuecomment-6281532475',
                     'scanner_guards': 45,
                     'scanner_reviewed_raw_findings': 3,
                     'scanner_unresolved_findings': 0,
                     'qualifier_receipt_bytes': 9168,
                     'qualifier_receipt_sha256': 'd1d53f93743b945ac9fdf639e920c7f6121e70009143d43a324ff5ebb2ce322e',
                     'scope': 'Root acquired and qualified these original Core/Preview/runtime/Installed '
                              'archives and complete decoded console bindings. Twelve complete command text '
                              'payloads and eight PNG/XML bindings matched. No new pixel inspection, '
                              'recomputed contrast, emulator run, live-host ACL observation or d669 '
                              'main-push execution is claimed.'},
 'limits': ['All four accepted criteria and tasks are limited to local-stored-draft-current-diagnostics/1. '
            'Current resource/cost producers remain unavailable; affected identities or missing operands '
            'never grant compatibility, capacity or zero-cost authority.',
            'Conservative direct adjacency and finite evaluation reuse accepted dependency policy. '
            'Before/after durable-head checks and live source/lifecycle admission do not promise continuous '
            'external-file monitoring or atomic disk/UI-frame locking.',
            'Historical reports and retained inspections keep their original identities. Unresolved optional '
            'intent is withheld before the rule loop and is not labeled completed evaluation.',
            'Production catalog/rules, external evidence, alternative qualification, services, '
            'physical-device/TalkBack/performance evidence, publisher identity, publication rights and '
            'release retain their own work-package requirements.',
            'Ledger classification is distinct from issue metadata reconciliation and any later source or '
            'main execution. Neither receiving comment is an absent-person response or distinct-account '
            'approval.']}

IMPLEMENTATION_100_EVIDENCE = {'pr_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/pull/663',
 'observed_state': 'merged',
 'revision': 'd6697ed824332070a30f8d7ecab938add5838156',
 'artifact_url': 'https://github.com/Pick-Your-AI/pick-your-ai-android/blob/d6697ed824332070a30f8d7ecab938add5838156/docs/delivery/android/work-packages/09.02.md',
 'scope_limit': 'Delivered canonical #100/09.02 and ADR0015 at signed d669, with both actual receiving '
                'decisions accepting only local-stored-draft-current-diagnostics/1. Implementation32f5, '
                'original c111, record preparation a05 and delivered d669 remain separate. Completes only '
                '100.1–100.4; unavailable current estimates, production evidence, device and release '
                'acceptance remain outside this outcome.'}

IMPLEMENTATION_100_CRITERIA = ['5d0ab820e797a0082313539b3431bf26a8411ad6b3eac64f820c42655feb45f7',
 '5fc98809caf35572e764453108e106e483c03704bb4b28df9035381170f5b885',
 '55ace8f7c2a5078fb7cba1f7c40baed95889067b8091a0e368f66b53dd456dc0',
 '893eb491f77ebb39dee1874894895177a8359e0bf4a699e4bddcdd70d418b92f']

PRIOR_100_FIELDS = {'status': 'incomplete',
 'completion_evidence': [],
 'remaining_engineering': 'Implement and validate this exact criterion in the selected scope; record '
                          'negative/recovery cases and receiving evidence.',
 'evidence_relation': 'issue_context_only; inspect artifact scope before attributing any criterion result'}

PRIOR_100_ISSUE_SHA256 = 'fa190ff1e451d5e379c66387bf76fcd4d1bd2afea15ccda466f74da6fd6c065c'

PRIOR_V16_LEDGER_SHA256 = '72f771fa30b21cbe1ac1a9520392ac351350c24f8c8d638c332bf9dcfec1ab77'

PRIOR_V16_FILE_SHA256 = 'b75ef6275cbed9c555f9b0a541025f427852224ad958a7059d1baa8fa1aaadda'

PRIOR_V16_FILE_BYTES = 701444

LEDGER_PURPOSE_V17 = ('Living evidence index: 25 reviewed documentary/design criteria for Android #2/#20/#23/#24/#25/#34/#35/#47, '
 '25 reviewed implementation criteria for #26/#37/#48/#84/#96/#98/#100, the single reviewed import-integrity '
 'criterion 21.2 and 3 reviewed bounded support criteria for #33 are complete; 387 criteria remain '
 'incomplete. This does not replace acceptance receipts, GitHub issue status or native dependencies.')

def reconstruct_prior_v16(ledger):
    """Detach and reverse only #100's accepted completion and placement fields.

    Admission occurs in validate(), before this inverse. Unknown issue, criterion,
    coordinate and historical fields survive for the unchanged v16 validator and
    complete predecessor digest. No caller data or prior evidence is replaced.
    """
    previous = copy.deepcopy(ledger)
    issue = previous['issues'][99]
    issue.pop('accepted_implementation_outcome')
    issue['canonical_acceptance_record']['repository'] = 'Pick-Your-AI/pickyour.ai'
    for criterion in issue['criteria']:
        for key, value in PRIOR_100_FIELDS.items():
            criterion[key] = copy.deepcopy(value)
    previous['evidence'].pop('pr-663')
    previous['schema_version'] = 16
    previous['purpose'] = LEDGER_PURPOSE_V16
    return previous


def validate(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 17,
            'unsupported schema version')
    require(ledger['purpose'] == LEDGER_PURPOSE_V17,
            'unsupported ledger completion summary')
    issues = ledger['issues']
    numbers = [issue['number'] for issue in issues]
    require(all(type(number) is int for number in numbers),
            'ledger issue numbers must be integers')
    require(numbers == list(range(1, 130)),
            'expected unique ordered issue numbers 1–129')
    issue = issues[99]
    require('accepted_design_outcome' not in issue, 'unsupported design acceptance: 100')
    require('accepted_support_outcome' not in issue, 'unsupported support acceptance: 100')
    require(json_digest(issue['canonical_acceptance_record']) == json_digest({
        'repository': REPOSITORY, 'path': IMPLEMENTATION_100_ACCEPTANCE['record_path']}),
        'unreviewed acceptance coordinates or placement: 100')
    require(json_digest(issue.get('accepted_implementation_outcome')) ==
            json_digest(IMPLEMENTATION_100_ACCEPTANCE),
            'missing or changed reviewed implementation acceptance: 100')
    require([c['text_sha256'] for c in issue['criteria']] == IMPLEMENTATION_100_CRITERIA,
            'reviewed implementation criterion set changed: 100')
    require(json_digest(ledger['evidence'].get('pr-663')) ==
            json_digest(IMPLEMENTATION_100_EVIDENCE),
            'implementation receipt is not the reviewed merged artifact: 100')
    for index, criterion in enumerate(issue['criteria'], 1):
        key = f'100.{index}'
        require(criterion['id'] == key, f'criterion identity/order mismatch: {key}')
        require(criterion['status'] == 'complete' and criterion['completion_evidence'] == ['pr-663'],
                f'missing reviewed implementation completion evidence: {key}')
        require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                f'completed criterion still has unfinished requirements: {key}')
        require(criterion['evidence_relation'] == COMPLETE_IMPLEMENTATION_RELATION,
                f'unsupported implementation completion attribution: {key}')

    previous_v16 = reconstruct_prior_v16(ledger)
    _validate_prior_v16(previous_v16, source)
    require(json_digest(previous_v16['issues'][99]) == PRIOR_100_ISSUE_SHA256,
            'unreviewed change to another issue record: preserve prior #100 context and partial history')
    require(json_digest(previous_v16) == PRIOR_V16_LEDGER_SHA256,
            'unreviewed change to complete prior v16 ledger history')



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
    print('PASS: 129 unique issue URLs, 387 incomplete criteria, 25 reviewed design criteria, 25 reviewed implementation criteria, 1 reviewed import-integrity criterion, 3 reviewed support criteria, pinned evidence and canonical paths' +
          ('; exact source criteria matched.' if source is not None else '; live source comparison NOT performed.'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
