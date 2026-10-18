#!/usr/bin/env python3
"""Validate ledger integrity; --issues additionally compares exact live issue criteria.

Read-only: never calls GitHub, changes issue state, or interprets checks as acceptance.
"""
import argparse
import hashlib
import json
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

# This is one reviewed documentary outcome, not a generic permission to complete
# other criteria or relocate their records. Further acceptance needs source review.
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


def require(condition, message):
    if not condition:
        raise ValueError(message)


def source_criteria(body):
    require('## Acceptance criteria' in body, 'missing source acceptance section')
    section = body.split('## Acceptance criteria', 1)[1].split('\n## ', 1)[0]
    return re.findall(r'^- \[[ xX]\] (.+)$', section, re.M)


def validate(ledger, source=None):
    require(type(ledger['schema_version']) is int and ledger['schema_version'] == 2, 'unsupported schema version')
    require(ledger['repository'] == REPOSITORY, 'wrong repository')
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
        accepted_design = number == 23
        if accepted_design:
            require(record == DESIGN_23_RECORD, 'unreviewed acceptance placement: 23')
            require(issue.get('accepted_design_outcome') == DESIGN_23_ACCEPTANCE,
                    'missing or changed reviewed design acceptance: 23')
            require([c['text_sha256'] for c in issue['criteria']] == DESIGN_23_CRITERIA,
                    'reviewed design criterion set changed: 23')
            receipt = evidence.get('pr-279', {})
            require(receipt.get('observed_state') == 'merged' and
                    receipt.get('revision') == DESIGN_23_REVISION and
                    receipt.get('artifact_url') == DESIGN_23_ACCEPTANCE['record_url'],
                    'accepted design receipt is not the reviewed merged artifact: 23')
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
                require(criterion['status'] == 'complete' and criterion['completion_evidence'] == ['pr-279'],
                        f'missing reviewed design completion evidence: {key}')
                require(criterion['remaining_engineering'] == '' and criterion['external_prerequisites'] == [],
                        f'completed criterion still has unfinished requirements: {key}')
                require(criterion['evidence_relation'] == COMPLETE_DESIGN_RELATION,
                        f'unsupported design completion attribution: {key}')
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ledger', type=Path, default=ROOT / 'docs/delivery/criterion-evidence.json')
    parser.add_argument('--issues', type=Path, help='fresh gh issue list --state all JSON for authoritative text/path comparison')
    args = parser.parse_args()
    try:
        source = json.loads(args.issues.read_text()) if args.issues else None
        validate(json.loads(args.ledger.read_text()), source)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Delivery ledger check failed: {error}', file=sys.stderr)
        return 1
    print('PASS: 129 unique issue URLs, 438 incomplete criteria, 3 reviewed design criteria, pinned evidence and canonical paths' +
          ('; exact source criteria matched.' if source is not None else '; live source comparison NOT performed.'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
