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


def validate(ledger, source=None):
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
    print('PASS: 129 unique issue URLs, 409 incomplete criteria, 25 reviewed design criteria, 3 reviewed implementation criteria, 1 reviewed import-integrity criterion, 3 reviewed support criteria, pinned evidence and canonical paths' +
          ('; exact source criteria matched.' if source is not None else '; live source comparison NOT performed.'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
