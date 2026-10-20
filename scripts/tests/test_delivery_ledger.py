"""Regression checks for fail-closed coverage and evidence claims."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ledger_check', ROOT / 'scripts/check_delivery_ledger.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LedgerValidationTest(unittest.TestCase):
    def setUp(self):
        self.ledger = json.loads((ROOT / 'docs/delivery/criterion-evidence.json').read_text())

    def test_committed_inventory_passes(self):
        module.validate(self.ledger)

    def test_missing_or_duplicate_issue_fails(self):
        for mutation in ('missing', 'duplicate'):
            ledger = copy.deepcopy(self.ledger)
            if mutation == 'missing':
                ledger['issues'].pop()
            else:
                ledger['issues'][-1] = ledger['issues'][0]
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_tampered_criterion_and_completion_claim_fail(self):
        for key, value in [('text', 'Replacement criterion'), ('status', 'complete'), ('partial_evidence_refs', ['pr-999'])]:
            ledger = copy.deepcopy(self.ledger)
            ledger['issues'][0]['criteria'][0][key] = value
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_fresh_source_text_drift_fails_even_when_local_hash_is_valid(self):
        source = []
        for issue in self.ledger['issues']:
            source.append({'number': issue['number'], 'title': issue['title'], 'body':
                '## Acceptance criteria\n' + '\n'.join('- [ ] ' + c['text'] for c in issue['criteria']) +
                '\n## Delivery record\n`' + issue['canonical_acceptance_record']['path'] + '`'})
        module.validate(self.ledger, source)
        source[0]['body'] = source[0]['body'].replace('All selected core work packages', 'Changed selected work packages')
        with self.assertRaisesRegex(ValueError, 'live criteria differ'):
            module.validate(self.ledger, source)

    def test_partial_result_requires_evidence_and_commands(self):
        criterion = next(c for issue in self.ledger['issues'] for c in issue['criteria'] if c['id'] == '122.2')
        criterion['partial_results'][0]['reproduction_commands'] = []
        with self.assertRaisesRegex(ValueError, 'missing reproduction command'):
            module.validate(self.ledger)

    def test_moved_acceptance_repository_fails(self):
        self.ledger['issues'][0]['canonical_acceptance_record']['repository'] = 'Pick-Your-AI/pick-your-ai-android'
        with self.assertRaises(ValueError):
            module.validate(self.ledger)

    def test_accepted_design_cannot_authorize_another_issue(self):
        accepted = self.ledger['issues'][22]
        target = self.ledger['issues'][88]  # Installed secure storage remains its own work.
        target['accepted_design_outcome'] = copy.deepcopy(accepted['accepted_design_outcome'])
        target['canonical_acceptance_record']['repository'] = 'Pick-Your-AI/pick-your-ai-android'
        target['criteria'][0].update(status='complete', completion_evidence=['pr-279'], remaining_engineering='')
        with self.assertRaisesRegex(ValueError, 'unsupported design acceptance: 89'):
            module.validate(self.ledger)

    def test_design_completion_requires_exact_merged_receipt_and_reviews(self):
        for changed in ('missing', 'review', 'scope', 'source', 'open', 'revision', 'artifact'):
            with self.subTest(changed=changed):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][22]
                receipt = ledger['evidence']['pr-279']
                if changed == 'missing':
                    del issue['accepted_design_outcome']
                elif changed == 'review':
                    issue['accepted_design_outcome']['receiving_reviews'] = []
                elif changed == 'scope':
                    issue['accepted_design_outcome']['scope'] = 'installed_device_security'
                elif changed == 'source':
                    issue['accepted_design_outcome']['source_review_revision'] = '0' * 40
                elif changed == 'open':
                    receipt['observed_state'] = 'open'
                elif changed == 'revision':
                    receipt['revision'] = '0' * 40
                    receipt['artifact_url'] = receipt['artifact_url'].replace(module.DESIGN_23_REVISION, '0' * 40)
                else:
                    receipt['artifact_url'] = receipt['artifact_url'].replace('14.01.md', '14.02.md')
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_accepted_design_cannot_change_criteria_even_with_recomputed_digest(self):
        import hashlib
        criterion = self.ledger['issues'][22]['criteria'][0]
        criterion['text'] = 'Installed device security accepted.'
        criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'reviewed design criterion set changed: 23'):
            module.validate(self.ledger)

    def test_design_completion_rejects_missing_proof_and_unfinished_work(self):
        for field, value in [
            ('completion_evidence', []),
            ('completion_evidence', ['pr-132']),
            ('remaining_engineering', 'Unreviewed data flow remains.'),
            ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Pending design review.'}]),
            ('evidence_relation', 'criterion_partial_results_with_issue_context'),
        ]:
            with self.subTest(field=field, value=value):
                ledger = copy.deepcopy(self.ledger)
                ledger['issues'][22]['criteria'][0][field] = value
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_accepted_placement_preserves_history_and_cannot_move_to_another_path(self):
        for changed in ('path', 'history'):
            ledger = copy.deepcopy(self.ledger)
            issue = ledger['issues'][22]
            if changed == 'path':
                issue['canonical_acceptance_record']['path'] = 'docs/delivery/android/work-packages/14.02.md'
            else:
                issue['accepted_design_outcome']['historical_acceptance_record']['repository'] = 'Pick-Your-AI/pick-your-ai-android'
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_all_state_source_keeps_closed_issue_in_exact_criterion_comparison(self):
        source = [{'number': issue['number'], 'title': issue['title'],
                   'state': 'closed' if issue['number'] == 23 else 'open',
                   'body': '## Acceptance criteria\n' +
                   '\n'.join('- [x] ' + c['text'] for c in issue['criteria']) +
                   '\n## Delivery record\n`' + issue['canonical_acceptance_record']['path'] + '`'}
                  for issue in self.ledger['issues']]
        module.validate(self.ledger, source)
        with self.assertRaisesRegex(ValueError, 'live issue inventory differs'):
            module.validate(self.ledger, [issue for issue in source if issue['state'] == 'open'])

    def test_completion_requires_reviewed_schema_version(self):
        for version in (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, True, '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15', '16', 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0):
            ledger = copy.deepcopy(self.ledger)
            ledger['schema_version'] = version
            with self.assertRaisesRegex(ValueError, 'unsupported schema version'):
                module.validate(ledger)

    def test_issue_and_source_identity_reject_numeric_aliases(self):
        source = [{'number': issue['number'], 'title': issue['title'],
                   'body': '## Acceptance criteria\n' +
                   '\n'.join('- [ ] ' + c['text'] for c in issue['criteria']) +
                   '\n## Delivery record\n`' + issue['canonical_acceptance_record']['path'] + '`'}
                  for issue in self.ledger['issues']]
        for location in ('ledger', 'source'):
            for index, alias in ((22, 23.0), (0, True)):
                with self.subTest(location=location, alias=alias):
                    ledger = copy.deepcopy(self.ledger)
                    changed_source = copy.deepcopy(source)
                    if location == 'ledger':
                        issue = ledger['issues'][index]
                        issue['number'] = alias
                        issue['url'] = f'{module.BASE}/issues/{alias}'
                        for ordinal, criterion in enumerate(issue['criteria'], 1):
                            criterion['id'] = f'{alias}.{ordinal}'
                    else:
                        changed_source[index]['number'] = alias
                    with self.assertRaisesRegex(ValueError, f'{location} issue numbers must be integers'):
                        module.validate(ledger, changed_source)


    def test_foundation_criteria_cannot_change_with_a_recomputed_digest(self):
        import hashlib
        for number in (20, 24, 34):
            for ordinal in range(3):
                with self.subTest(number=number, ordinal=ordinal + 1):
                    ledger = copy.deepcopy(self.ledger)
                    criterion = ledger['issues'][number - 1]['criteria'][ordinal]
                    criterion['text'] = 'Installed production behavior accepted.'
                    criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                    with self.assertRaisesRegex(ValueError, f'reviewed design criterion set changed: {number}'):
                        module.validate(ledger)

    def test_foundation_decisions_require_their_own_receipts(self):
        for target, source in ((20, 24), (24, 34), (34, 20), (20, 23)):
            with self.subTest(target=target, source=source):
                ledger = copy.deepcopy(self.ledger)
                ledger['issues'][target - 1]['accepted_design_outcome'] = copy.deepcopy(
                    ledger['issues'][source - 1]['accepted_design_outcome'])
                with self.assertRaisesRegex(ValueError, f'missing or changed reviewed design acceptance: {target}'):
                    module.validate(ledger)

    def test_foundation_requires_exact_delivery_placement_scope_and_reviews(self):
        for number in (20, 24, 34):
            for changed in ('missing-review', 'wrong-review', 'source', 'scope', 'history',
                            'placement', 'record', 'open', 'revision', 'artifact'):
                with self.subTest(number=number, changed=changed):
                    ledger = copy.deepcopy(self.ledger)
                    issue = ledger['issues'][number - 1]
                    accepted = issue['accepted_design_outcome']
                    receipt = ledger['evidence']['pr-297']
                    if changed == 'missing-review':
                        accepted['receiving_reviews'].pop()
                    elif changed == 'wrong-review':
                        accepted['receiving_reviews'][-1] = module.DESIGN_23_ACCEPTANCE['receiving_reviews'][0]
                    elif changed == 'source':
                        accepted['source_review_revision'] = '0' * 40
                    elif changed == 'scope':
                        accepted['scope'] = 'installed_production_execution'
                    elif changed == 'history':
                        accepted['historical_acceptance_record']['repository'] = module.REPOSITORY
                    elif changed == 'placement':
                        issue['canonical_acceptance_record']['repository'] = 'Pick-Your-AI/pickyour.ai'
                    elif changed == 'record':
                        accepted['record_url'] = module.FOUNDATION_DECISION
                    elif changed == 'open':
                        receipt['observed_state'] = 'open'
                    elif changed == 'revision':
                        receipt['revision'] = '0' * 40
                        receipt['artifact_url'] = receipt['artifact_url'].replace(module.FOUNDATION_REVISION, '0' * 40)
                    else:
                        receipt['artifact_url'] = accepted['record_url']
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_foundation_completion_preserves_unfinished_criterion_guards(self):
        for number in (20, 24, 34):
            for field, value in (
                ('completion_evidence', []),
                ('completion_evidence', ['pr-279']),
                ('completion_evidence', ['pr-297', 'pr-279']),
                ('remaining_engineering', 'Unreviewed decision remains.'),
                ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Pending design review.'}]),
                ('evidence_relation', 'issue_context_only; inspect artifact scope before attributing any criterion result'),
            ):
                with self.subTest(number=number, field=field, value=value):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][number - 1]['criteria'][0][field] = value
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_foundation_does_not_authorize_parent_or_implementation_completion(self):
        for number in (1, 48, 98):
            with self.subTest(number=number):
                ledger = copy.deepcopy(self.ledger)
                target = ledger['issues'][number - 1]
                target['accepted_design_outcome'] = copy.deepcopy(ledger['issues'][19]['accepted_design_outcome'])
                target['canonical_acceptance_record']['repository'] = module.REPOSITORY
                target['criteria'][0].update(status='complete', completion_evidence=['pr-297'],
                                             remaining_engineering='', external_prerequisites=[],
                                             evidence_relation=module.COMPLETE_DESIGN_RELATION)
                with self.assertRaisesRegex(ValueError, f'unsupported design acceptance: {number}'):
                    module.validate(ledger)


    def test_account_criteria_cannot_change_with_a_recomputed_digest(self):
        import hashlib
        for ordinal in range(3):
            with self.subTest(ordinal=ordinal + 1):
                ledger = copy.deepcopy(self.ledger)
                criterion = ledger['issues'][24]['criteria'][ordinal]
                criterion['text'] = 'Remote accounts and billing are implemented.'
                criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                with self.assertRaisesRegex(ValueError, 'reviewed design criterion set changed: 25'):
                    module.validate(ledger)

    def test_account_decision_requires_exact_delivery_placement_scope_and_reviews(self):
        for changed in ('missing', 'missing-review', 'wrong-review', 'source', 'scope',
                        'history-repository', 'history-path', 'placement', 'path',
                        'record', 'decision', 'open', 'revision', 'artifact'):
            with self.subTest(changed=changed):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][24]
                accepted = issue['accepted_design_outcome']
                receipt = ledger['evidence']['pr-322']
                if changed == 'missing':
                    del issue['accepted_design_outcome']
                elif changed == 'missing-review':
                    accepted['receiving_reviews'].pop()
                elif changed == 'wrong-review':
                    accepted['receiving_reviews'][-1] = module.FOUNDATION_REVIEWS[-1]
                elif changed == 'source':
                    accepted['source_review_revision'] = '0' * 40
                elif changed == 'scope':
                    accepted['scope'] = 'remote_accounts_and_billing_implementation'
                elif changed == 'history-repository':
                    accepted['historical_acceptance_record']['repository'] = module.REPOSITORY
                elif changed == 'history-path':
                    accepted['historical_acceptance_record']['path'] = module.DESIGN_23_PATH
                elif changed == 'placement':
                    issue['canonical_acceptance_record']['repository'] = 'Pick-Your-AI/pickyour.ai'
                elif changed == 'path':
                    issue['canonical_acceptance_record']['path'] = module.DESIGN_23_PATH
                elif changed == 'record':
                    accepted['record_url'] = module.DESIGN_25_DECISION
                elif changed == 'decision':
                    accepted['decision_url'] = accepted['record_url']
                elif changed == 'open':
                    receipt['observed_state'] = 'open'
                elif changed == 'revision':
                    receipt['revision'] = '0' * 40
                    receipt['artifact_url'] = receipt['artifact_url'].replace(module.DESIGN_25_REVISION, '0' * 40)
                else:
                    receipt['artifact_url'] = accepted['record_url']
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_account_decision_cannot_substitute_other_design_receipts(self):
        for other in (20, 23, 24, 34):
            for target, source in ((25, other), (other, 25)):
                with self.subTest(target=target, source=source):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][target - 1]['accepted_design_outcome'] = copy.deepcopy(
                        ledger['issues'][source - 1]['accepted_design_outcome'])
                    with self.assertRaisesRegex(ValueError, f'missing or changed reviewed design acceptance: {target}'):
                        module.validate(ledger)

    def test_account_completion_requires_exact_proof_and_no_unfinished_requirement(self):
        for ordinal in range(3):
            for field, value in (
                ('completion_evidence', []),
                ('completion_evidence', ['pr-297']),
                ('completion_evidence', ['pr-322', 'pr-297']),
                ('remaining_engineering', 'Applicability remains undecided.'),
                ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Pending design review.'}]),
                ('evidence_relation', 'issue_context_only; inspect artifact scope before attributing any criterion result'),
            ):
                with self.subTest(ordinal=ordinal + 1, field=field, value=value):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][24]['criteria'][ordinal][field] = value
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_account_decision_does_not_authorize_service_or_implementation_completion(self):
        for number in (1, 35, 89, 98):
            with self.subTest(number=number):
                ledger = copy.deepcopy(self.ledger)
                target = ledger['issues'][number - 1]
                target['accepted_design_outcome'] = copy.deepcopy(ledger['issues'][24]['accepted_design_outcome'])
                target['canonical_acceptance_record']['repository'] = module.REPOSITORY
                target['criteria'][0].update(status='complete', completion_evidence=['pr-322'],
                                             remaining_engineering='', external_prerequisites=[],
                                             evidence_relation=module.COMPLETE_DESIGN_RELATION)
                message = (f'missing or changed reviewed design acceptance: {number}' if number == 35
                           else f'unsupported design acceptance: {number}')
                with self.assertRaisesRegex(ValueError, message):
                    module.validate(ledger)


    def test_baseline_criteria_cannot_change_with_a_recomputed_digest(self):
        import hashlib
        for ordinal in range(3):
            with self.subTest(ordinal=ordinal + 1):
                ledger = copy.deepcopy(self.ledger)
                criterion = ledger['issues'][46]['criteria'][ordinal]
                criterion['text'] = 'Supported physical devices and production performance are qualified.'
                criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                with self.assertRaisesRegex(ValueError, 'reviewed design criterion set changed: 47'):
                    module.validate(ledger)

    def test_baseline_requires_exact_delivery_artifacts_scope_history_and_reviews(self):
        for changed in ('missing', 'missing-review', 'wrong-review', 'source', 'scope',
                        'history-repository', 'history-path', 'placement', 'path',
                        'record', 'baseline', 'decision', 'open', 'revision', 'artifact'):
            with self.subTest(changed=changed):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][46]
                accepted = issue['accepted_design_outcome']
                receipt = ledger['evidence']['pr-318']
                if changed == 'missing':
                    del issue['accepted_design_outcome']
                elif changed == 'missing-review':
                    accepted['receiving_reviews'].pop()
                elif changed == 'wrong-review':
                    accepted['receiving_reviews'][1] = module.DESIGN_25_ACCEPTANCE['receiving_reviews'][0]
                elif changed == 'source':
                    accepted['source_review_revision'] = '0' * 40
                elif changed == 'scope':
                    accepted['scope'] = 'physical_device_and_production_release_qualification'
                elif changed == 'history-repository':
                    accepted['historical_acceptance_record']['repository'] = module.REPOSITORY
                elif changed == 'history-path':
                    accepted['historical_acceptance_record']['path'] = module.DESIGN_25_PATH
                elif changed == 'placement':
                    issue['canonical_acceptance_record']['repository'] = 'Pick-Your-AI/pickyour.ai'
                elif changed == 'path':
                    issue['canonical_acceptance_record']['path'] = module.DESIGN_25_PATH
                elif changed == 'record':
                    accepted['record_url'] = accepted['baseline_url']
                elif changed == 'baseline':
                    accepted['baseline_url'] = accepted['record_url']
                elif changed == 'decision':
                    accepted['decision_url'] = accepted['record_url']
                elif changed == 'open':
                    receipt['observed_state'] = 'open'
                elif changed == 'revision':
                    receipt['revision'] = '0' * 40
                    receipt['artifact_url'] = receipt['artifact_url'].replace(module.DESIGN_47_REVISION, '0' * 40)
                else:
                    receipt['artifact_url'] = accepted['baseline_url']
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_baseline_receipt_cannot_substitute_any_other_accepted_decision(self):
        for other in (20, 23, 24, 25, 34):
            for target, source in ((47, other), (other, 47)):
                with self.subTest(target=target, source=source):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][target - 1]['accepted_design_outcome'] = copy.deepcopy(
                        ledger['issues'][source - 1]['accepted_design_outcome'])
                    with self.assertRaisesRegex(ValueError, f'missing or changed reviewed design acceptance: {target}'):
                        module.validate(ledger)

    def test_baseline_completion_requires_exact_proof_and_no_unfinished_decision(self):
        for ordinal in range(3):
            for field, value in (
                ('completion_evidence', []),
                ('completion_evidence', ['pr-322']),
                ('completion_evidence', ['pr-318', 'pr-322']),
                ('remaining_engineering', 'Qualification targets or review methods remain undecided.'),
                ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Pending decision review.'}]),
                ('evidence_relation', 'issue_context_only; inspect artifact scope before attributing any criterion result'),
            ):
                with self.subTest(ordinal=ordinal + 1, field=field, value=value):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][46]['criteria'][ordinal][field] = value
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_baseline_does_not_authorize_implementation_devices_performance_or_release(self):
        for number in (1, 48, 54, 71, 74, 107):
            with self.subTest(number=number):
                ledger = copy.deepcopy(self.ledger)
                target = ledger['issues'][number - 1]
                target['accepted_design_outcome'] = copy.deepcopy(ledger['issues'][46]['accepted_design_outcome'])
                target['canonical_acceptance_record']['repository'] = module.REPOSITORY
                target['criteria'][0].update(status='complete', completion_evidence=['pr-318'],
                                             remaining_engineering='', external_prerequisites=[],
                                             evidence_relation=module.COMPLETE_DESIGN_RELATION)
                with self.assertRaisesRegex(ValueError, f'unsupported design acceptance: {number}'):
                    module.validate(ledger)


    def test_service_criteria_cannot_change_with_a_recomputed_digest(self):
        import hashlib
        for ordinal in range(3):
            with self.subTest(ordinal=ordinal + 1):
                ledger = copy.deepcopy(self.ledger)
                criterion = ledger['issues'][34]['criteria'][ordinal]
                criterion['text'] = 'A production endpoint, accepted operator and executed restore are verified.'
                criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                with self.assertRaisesRegex(ValueError, 'reviewed design criterion set changed: 35'):
                    module.validate(ledger)

    def test_service_requires_exact_delivery_artifacts_scope_history_and_reviews(self):
        for changed in ('missing', 'missing-review', 'wrong-review', 'source', 'scope',
                        'history-repository', 'history-path', 'placement', 'path',
                        'record', 'baseline', 'decision', 'open', 'revision', 'artifact'):
            with self.subTest(changed=changed):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][34]
                accepted = issue['accepted_design_outcome']
                receipt = ledger['evidence']['pr-347']
                if changed == 'missing':
                    del issue['accepted_design_outcome']
                elif changed == 'missing-review':
                    accepted['receiving_reviews'].pop()
                elif changed == 'wrong-review':
                    accepted['receiving_reviews'][1] = module.DESIGN_25_ACCEPTANCE['receiving_reviews'][0]
                elif changed == 'source':
                    accepted['source_review_revision'] = '0' * 40
                elif changed == 'scope':
                    accepted['scope'] = 'deployed_shared_service_and_accepted_operator'
                elif changed == 'history-repository':
                    accepted['historical_acceptance_record']['repository'] = module.REPOSITORY
                elif changed == 'history-path':
                    accepted['historical_acceptance_record']['path'] = module.DESIGN_25_PATH
                elif changed == 'placement':
                    issue['canonical_acceptance_record']['repository'] = 'Pick-Your-AI/pickyour.ai'
                elif changed == 'path':
                    issue['canonical_acceptance_record']['path'] = module.DESIGN_25_PATH
                elif changed == 'record':
                    accepted['record_url'] = accepted['baseline_url']
                elif changed == 'baseline':
                    accepted['baseline_url'] = accepted['record_url']
                elif changed == 'decision':
                    accepted['decision_url'] = accepted['record_url']
                elif changed == 'open':
                    receipt['observed_state'] = 'open'
                elif changed == 'revision':
                    receipt['revision'] = '0' * 40
                    receipt['artifact_url'] = receipt['artifact_url'].replace(module.DESIGN_35_REVISION, '0' * 40)
                else:
                    receipt['artifact_url'] = accepted['baseline_url']
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_service_receipt_cannot_substitute_any_other_accepted_decision(self):
        for other in (20, 23, 24, 25, 34, 47):
            for target, source in ((35, other), (other, 35)):
                with self.subTest(target=target, source=source):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][target - 1]['accepted_design_outcome'] = copy.deepcopy(
                        ledger['issues'][source - 1]['accepted_design_outcome'])
                    with self.assertRaisesRegex(ValueError, f'missing or changed reviewed design acceptance: {target}'):
                        module.validate(ledger)

    def test_service_completion_requires_exact_proof_and_no_unfinished_decision(self):
        for ordinal in range(3):
            for field, value in (
                ('completion_evidence', []),
                ('completion_evidence', ['pr-322']),
                ('completion_evidence', ['pr-347', 'pr-322']),
                ('remaining_engineering', 'Hosting or explicit operating gate remains undecided.'),
                ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Pending decision review.'}]),
                ('evidence_relation', 'issue_context_only; inspect artifact scope before attributing any criterion result'),
            ):
                with self.subTest(ordinal=ordinal + 1, field=field, value=value):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][34]['criteria'][ordinal][field] = value
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_service_decision_does_not_authorize_parent_client_operations_or_release(self):
        for number in (1, 2, 36, 44, 51, 62, 89, 92, 98, 107):
            with self.subTest(number=number):
                ledger = copy.deepcopy(self.ledger)
                target = ledger['issues'][number - 1]
                target['accepted_design_outcome'] = copy.deepcopy(ledger['issues'][34]['accepted_design_outcome'])
                target['canonical_acceptance_record']['repository'] = module.REPOSITORY
                target['criteria'][0].update(status='complete', completion_evidence=['pr-347'],
                                             remaining_engineering='', external_prerequisites=[],
                                             evidence_relation=module.COMPLETE_DESIGN_RELATION)
                expected = ('reviewed design acceptance: 2' if number == 2 else
                            f'unsupported design acceptance: {number}')
                with self.assertRaisesRegex(ValueError, expected):
                    module.validate(ledger)


class LedgerImplementationAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = json.loads((ROOT / 'docs/delivery/criterion-evidence.json').read_text())

    def acceptance(self, ledger):
        return ledger['issues'][25]['accepted_implementation_outcome']

    def test_only_three_local_model_criteria_have_implementation_acceptance(self):
        module.validate(self.ledger)
        # Keep this earlier model-only count at the exact accepted v10.
        issues = module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))))))['issues']
        self.assertEqual([26], [x['number'] for x in issues if 'accepted_implementation_outcome' in x])
        self.assertEqual([2, 20, 23, 24, 25, 34, 35, 47], [x['number'] for x in issues if 'accepted_design_outcome' in x])
        criteria = [c for issue in issues for c in issue['criteria']]
        self.assertEqual(409, sum(c['status'] == 'incomplete' for c in criteria))
        self.assertEqual(25, sum(c['evidence_relation'] == module.COMPLETE_DESIGN_RELATION for c in criteria))
        self.assertEqual(3, sum(c['evidence_relation'] == module.COMPLETE_IMPLEMENTATION_RELATION for c in criteria))
        accepted = self.acceptance(self.ledger)
        self.assertNotEqual(accepted['implementation_revision'], accepted['record_revision'])
        self.assertEqual(['pr-407', 'pr-418'], accepted['evidence_refs'])
        self.assertEqual(4, len(accepted['implemented_subtasks']))
        self.assertTrue(all(c['partial_evidence_refs'] == ['pr-138'] for c in issues[25]['criteria']))

    def test_save_policy_correction_requires_its_distinct_actual_source_and_review(self):
        import hashlib
        accepted = self.acceptance(self.ledger)
        correction = accepted['save_policy_correction']
        self.assertNotEqual(accepted['record_revision'], correction['source_revision'])
        self.assertEqual('unsigned_review_preparation', correction['source_observed_state'])
        self.assertEqual('db60ec73b41df4f4be8f12af1d52f4833c65293b', accepted['record_blob'])
        raw = (ROOT / correction['record_path']).read_bytes()
        self.assertEqual(correction['record_bytes'], len(raw))
        self.assertEqual(correction['record_sha256'], hashlib.sha256(raw).hexdigest())
        self.assertEqual(correction['record_blob'], hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        fields = ('source_revision', 'source_tree', 'source_url', 'source_observed_state',
                  'record_path', 'record_blob', 'record_bytes', 'record_sha256', 'finding_url',
                  'receiving_reviews')
        for field in fields:
            for operation in ('remove', 'replace'):
                with self.subTest(field=field, operation=operation):
                    ledger = copy.deepcopy(self.ledger)
                    value = self.acceptance(ledger)['save_policy_correction']
                    if operation == 'remove':
                        del value[field]
                    else:
                        value[field] = 'unreviewed correction or old observation'
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)
        for index, review in enumerate(correction['receiving_reviews']):
            for field in review:
                with self.subTest(review=index, field=field):
                    ledger = copy.deepcopy(self.ledger)
                    self.acceptance(ledger)['save_policy_correction']['receiving_reviews'][index][field] = accepted['receiving_reviews'][0]['url']
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)

    def test_save_policy_correction_cannot_claim_scheduler_or_wrong_followup_scope(self):
        accepted = self.acceptance(self.ledger)
        correction = accepted['save_policy_correction']
        self.assertEqual('not_implemented_or_accepted', correction['scheduler_implementation'])
        self.assertEqual(module.BASE + '/issues/122', correction['remaining_interaction_issue'])
        self.assertIs(correction['historical_execution_relabelled'], False)
        self.assertIs(correction['new_native_execution_claimed'], False)
        mutations = (
            ('disposition', 'all_scheduler_and_native_behavior_accepted'),
            ('current_preview_policy', 'automatic_durable_save_on_field_or_lifecycle_change'),
            ('automatic_caller_contract', 'adopt_latest_token_and_retry_without_reconciliation'),
            ('scheduler_implementation', 'implemented'),
            ('remaining_interaction_issue', module.BASE + '/issues/67'),
            ('remaining_interaction_scope', 'all_autosave_and_storage_work_complete'),
            ('historical_execution_relabelled', True), ('historical_execution_relabelled', 0),
            ('new_native_execution_claimed', True), ('new_native_execution_claimed', 0),
            ('source_observed_state', 'signed_main_delivery'),
        )
        for field, value in mutations:
            with self.subTest(field=field, value=value):
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)['save_policy_correction'][field] = value
                with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                    module.validate(ledger)
        ledger = copy.deepcopy(self.ledger)
        del self.acceptance(ledger)['save_policy_correction']
        with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
            module.validate(ledger)

    def test_every_model_record_and_review_pin_is_required_and_exact(self):
        fields = ('implementation_revision', 'implementation_tree', 'record_revision',
                  'record_tree', 'record_url', 'record_blob', 'decision_url', 'decision_blob',
                  'source_review_revision', 'source_reviews', 'preserved_prior_ledger')
        for field in fields:
            for mutation in ('remove', 'replace'):
                with self.subTest(field=field, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    accepted = self.acceptance(ledger)
                    if mutation == 'remove':
                        del accepted[field]
                    else:
                        accepted[field] = 'unreviewed replacement'
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)

    def test_both_actual_merged_evidence_entries_require_their_own_identity(self):
        for key in ('pr-407', 'pr-418'):
            for mutation in ('remove', 'open', 'scope', 'revision', 'artifact', 'transplant'):
                with self.subTest(key=key, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    receipt = ledger['evidence'][key]
                    if mutation == 'remove':
                        del ledger['evidence'][key]
                    elif mutation == 'open':
                        receipt['observed_state'] = 'open'
                    elif mutation == 'scope':
                        receipt['scope_limit'] = 'All product and installed behavior accepted.'
                    elif mutation == 'revision':
                        old = receipt['revision']
                        receipt['revision'] = '0' * 40
                        receipt['artifact_url'] = receipt['artifact_url'].replace(old, '0' * 40)
                    elif mutation == 'artifact':
                        receipt['artifact_url'] = receipt['artifact_url'].rsplit('/', 1)[0] + '/OTHER.md'
                    else:
                        other = 'pr-418' if key == 'pr-407' else 'pr-407'
                        ledger['evidence'][key] = copy.deepcopy(ledger['evidence'][other])
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_original_observations_cannot_be_relabelled_or_replace_missing_failure_evidence(self):
        for observation in ('implementation_observation', 'record_observation'):
            for mutation in ('revision', 'tree', 'runtime', 'receipt', 'run', 'cases', 'status',
                             'missing-artifact', 'artifact-id', 'artifact-length', 'artifact-hash', 'apk'):
                with self.subTest(observation=observation, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    value = self.acceptance(ledger)[observation]
                    replacements = {'revision': 'execution_revision', 'tree': 'execution_tree',
                                    'runtime': 'runtime_scope', 'receipt': 'completed_receipt',
                                    'run': 'main_run_url', 'cases': 'model_cases', 'status': 'outcome'}
                    if mutation in replacements:
                        value[replacements[mutation]] = 'unreviewed or unexecuted'
                    elif mutation == 'missing-artifact':
                        value['original_artifacts'].pop()
                    elif mutation == 'artifact-id':
                        value['original_artifacts'][0]['id'] = '999'
                    elif mutation == 'artifact-length':
                        value['original_artifacts'][0]['bytes'] += 1
                    elif mutation == 'artifact-hash':
                        value['original_artifacts'][0]['sha256'] = '0' * 64
                    else:
                        value['unsigned_apk']['sha256'] = '0' * 64
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)

    def test_actual_receiving_roles_cannot_be_removed_invented_or_transplanted(self):
        for index in range(3):
            for mutation in ('remove', 'duplicate', 'role', 'url', 'revision', 'scope'):
                with self.subTest(index=index, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    reviews = self.acceptance(ledger)['receiving_reviews']
                    if mutation == 'remove':
                        reviews.pop(index)
                    elif mutation == 'duplicate':
                        reviews[index] = copy.deepcopy(reviews[(index + 1) % 3])
                    else:
                        field = {'role': 'role', 'url': 'url', 'revision': 'reviewed_revision', 'scope': 'disposition'}[mutation]
                        reviews[index][field] = 'unrecorded approval or scope'
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)

    def test_consumed_outputs_cannot_become_whole_prerequisite_completion(self):
        accepted = self.acceptance(self.ledger)
        self.assertEqual(module.BASE + '/issues/21', accepted['consumed_prerequisites'][0]['issue_url'])
        self.assertEqual('open', accepted['consumed_prerequisites'][0]['issue_state_at_receiving'])
        self.assertEqual('not_claimed', accepted['consumed_prerequisites'][0]['whole_issue_completion'])
        for index in range(2):
            for field in accepted['consumed_prerequisites'][index]:
                with self.subTest(index=index, field=field):
                    ledger = copy.deepcopy(self.ledger)
                    self.acceptance(ledger)['consumed_prerequisites'][index][field] = 'unreviewed substitute'
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)

    def test_all_four_subtasks_and_explicit_retention_failure_limits_remain_required(self):
        accepted = self.acceptance(self.ledger)
        for key in ('implemented_subtasks', 'outcome_boundaries'):
            for index in range(len(accepted[key])):
                with self.subTest(key=key, index=index):
                    ledger = copy.deepcopy(self.ledger)
                    self.acceptance(ledger)[key][index] = 'All deployment and every predecessor byte are guaranteed.'
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)

    def test_all_three_criterion_texts_cannot_change_with_recomputed_hash(self):
        import hashlib
        for index in range(3):
            with self.subTest(index=index):
                ledger = copy.deepcopy(self.ledger)
                criterion = ledger['issues'][25]['criteria'][index]
                criterion['text'] = 'A proposed design alone completes durable installed product behavior.'
                criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                with self.assertRaisesRegex(ValueError, 'reviewed implementation criterion set changed: 26'):
                    module.validate(ledger)

    def test_every_criterion_requires_both_receipts_and_no_unfinished_literal_requirement(self):
        for index in range(3):
            for field, value in (
                ('completion_evidence', []), ('completion_evidence', ['pr-418']),
                ('completion_evidence', ['pr-407']), ('completion_evidence', ['pr-418', 'pr-407']),
                ('remaining_engineering', 'Model behavior is still unimplemented.'),
                ('external_prerequisites', [{'kind': 'contract_acceptance', 'requirement': 'Required model input absent.'}]),
                ('evidence_relation', module.COMPLETE_DESIGN_RELATION), ('status', 'incomplete'),
            ):
                with self.subTest(index=index, field=field):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][25]['criteria'][index][field] = value
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_design_and_implementation_classifications_cannot_be_swapped(self):
        for mutation in ('design-field', 'design-value', 'generic-approval', 'classification', 'relation'):
            with self.subTest(mutation=mutation):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][25]
                if mutation == 'design-field':
                    issue['accepted_design_outcome'] = issue.pop('accepted_implementation_outcome')
                elif mutation == 'design-value':
                    issue['accepted_implementation_outcome'] = copy.deepcopy(ledger['issues'][24]['accepted_design_outcome'])
                elif mutation == 'generic-approval':
                    issue['accepted_implementation_outcome'] = {'approved': True}
                elif mutation == 'classification':
                    self.acceptance(ledger)['classification'] = 'reviewed_design_outcome'
                else:
                    issue['criteria'][0]['evidence_relation'] = module.COMPLETE_DESIGN_RELATION
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_model_receipt_cannot_authorize_other_issues_or_existing_designs(self):
        for number in (1, 21, 34, 84, 86, 98, 107):
            with self.subTest(number=number):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][number - 1]
                issue['accepted_implementation_outcome'] = copy.deepcopy(self.acceptance(ledger))
                issue['canonical_acceptance_record']['repository'] = module.REPOSITORY
                issue['criteria'][0].update(status='complete', completion_evidence=['pr-407', 'pr-418'],
                    remaining_engineering='', external_prerequisites=[], evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION)
                expected = (f'missing or changed reviewed implementation acceptance: {number}' if number in (84, 98)
                            else f'unsupported implementation acceptance: {number}')
                with self.assertRaisesRegex(ValueError, expected):
                    module.validate(ledger)

    def test_model_partial_context_and_historical_placement_cannot_be_rewritten(self):
        for mutation in ('drop-partial', 'replace-partial', 'new-result', 'title', 'new-field', 'history', 'placement'):
            with self.subTest(mutation=mutation):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][25]
                if mutation == 'drop-partial':
                    issue['criteria'][0]['partial_evidence_refs'] = []
                elif mutation == 'replace-partial':
                    issue['criteria'][0]['partial_evidence_refs'] = ['pr-407']
                elif mutation == 'new-result':
                    issue['criteria'][0]['partial_results'] = []
                elif mutation == 'title':
                    issue['title'] += ' (production accepted)'
                elif mutation == 'new-field':
                    issue['unrecorded_history_claim'] = 'earlier implementation passed'
                elif mutation == 'history':
                    self.acceptance(ledger)['historical_acceptance_record']['repository'] = module.REPOSITORY
                else:
                    issue['canonical_acceptance_record']['path'] = 'docs/delivery/android/work-packages/03.03.md'
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_every_other_issue_and_earlier_evidence_object_is_preserved(self):
        for number in range(1, 130):
            if number == 26:
                continue
            with self.subTest(number=number):
                ledger = copy.deepcopy(self.ledger)
                ledger['issues'][number - 1]['title'] += ' Unreviewed replacement'
                with self.assertRaisesRegex(ValueError, 'unreviewed change to another issue record'):
                    module.validate(ledger)
        for key in module.reconstruct_prior_v8(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))['evidence']:
            if key in ('pr-407', 'pr-418', 'pr-412'):
                continue
            with self.subTest(evidence=key):
                ledger = copy.deepcopy(self.ledger)
                ledger['evidence'][key]['scope_limit'] += ' A later run is retrospectively accepted.'
                with self.assertRaisesRegex(ValueError, 'unreviewed change to earlier evidence'):
                    module.validate(ledger)

    def test_historical_baseline_and_current_summary_cannot_claim_new_authority(self):
        for field in ('implementation_baseline', 'observed_on', 'source', 'purpose'):
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                ledger[field] = 'All issues and observations are newly accepted.'
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_receipt_numeric_aliases_are_not_identical_observations(self):
        for observation in ('implementation_observation', 'record_observation'):
            for mutation in ('case-float', 'artifact-float', 'boolean'):
                with self.subTest(observation=observation, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    value = self.acceptance(ledger)[observation]
                    if mutation == 'case-float':
                        value['core_cases'] = float(value['core_cases'])
                    elif mutation == 'artifact-float':
                        value['original_artifacts'][0]['bytes'] = float(value['original_artifacts'][0]['bytes'])
                    else:
                        value['model_cases'] = True
                    with self.assertRaisesRegex(ValueError, 'reviewed implementation acceptance: 26'):
                        module.validate(ledger)


class IntegratedFoundationAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    def accepted(self, ledger):
        return ledger['issues'][1]['accepted_design_outcome']

    def test_delivered_record_bytes_and_exact_decision_classification(self):
        import hashlib
        module.validate(self.ledger)
        accepted = self.accepted(self.ledger)
        self.assertEqual('reviewed_design_outcome', accepted['classification'])
        self.assertEqual(['D1', 'D2', 'D3', 'D4', 'D5', 'D6', 'D7', 'D8'],
                         accepted['decision_demonstration']['cases'])
        self.assertEqual([20, 24, 34, 35, 25, 47],
                         [child['issue'] for child in accepted['consumed_children']])
        self.assertEqual('c0278410f5a8a90776df9de2f466103cba48f9f1',
                         accepted['record_observation']['execution_revision'])
        for key in ('record', 'decision'):
            with self.subTest(artifact=key):
                expected = accepted[key]
                raw = (ROOT / expected['path']).read_bytes()
                self.assertEqual(expected['bytes'], len(raw))
                self.assertEqual(expected['sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(expected['blob'], hashlib.sha1(
                    b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        issue = self.ledger['issues'][1]
        self.assertNotIn('accepted_implementation_outcome', issue)
        self.assertEqual(4, len(issue['criteria']))
        self.assertTrue(all(c['completion_evidence'] == ['pr-412'] for c in issue['criteria']))

    def test_decision_receipt_requires_every_actual_pin_and_scope_field(self):
        accepted = self.accepted(self.ledger)
        for field in accepted:
            for mutation in ('remove', 'replace'):
                with self.subTest(field=field, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    value = self.accepted(ledger)
                    if mutation == 'remove':
                        del value[field]
                    else:
                        value[field] = 'unreviewed receipt or broader outcome'
                    with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                        module.validate(ledger)

    def test_receiving_roles_revisions_and_real_review_identity_cannot_be_substituted(self):
        for index, review in enumerate(self.accepted(self.ledger)['receiving_reviews']):
            for field in review:
                with self.subTest(review=index, field=field):
                    ledger = copy.deepcopy(self.ledger)
                    self.accepted(ledger)['receiving_reviews'][index][field] = 'historical_human_assignee_approved_everything'
                    with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                        module.validate(ledger)

    def test_every_child_and_accepted_handoff_retains_its_exact_input_scope(self):
        accepted = self.accepted(self.ledger)
        for index, child in enumerate(accepted['consumed_children']):
            for field in child:
                with self.subTest(child=index, field=field):
                    ledger = copy.deepcopy(self.ledger)
                    self.accepted(ledger)['consumed_children'][index][field] = 'unaccepted_new_child_or_implementation'
                    with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                        module.validate(ledger)
        for field in ('accepted_handoff_scope', 'outcome_boundaries'):
            for index in range(len(accepted[field])):
                with self.subTest(field=field, index=index):
                    ledger = copy.deepcopy(self.ledger)
                    self.accepted(ledger)[field][index] = 'All consumers and physical delivery are accepted.'
                    with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                        module.validate(ledger)

    def test_desk_demonstration_cannot_become_device_fit_or_operating_evidence(self):
        for field in self.accepted(self.ledger)['decision_demonstration']:
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                self.accepted(ledger)['decision_demonstration'][field] = 'installed_product_fit_and_operations_passed'
                with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                    module.validate(ledger)

    def test_actual_main_observation_and_original_artifact_identities_are_not_substitutable(self):
        observed = self.accepted(self.ledger)['record_observation']
        for field in observed:
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                self.accepted(ledger)['record_observation'][field] = 'earlier_prepared_or_unexecuted_source'
                with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                    module.validate(ledger)
        for index, artifact in enumerate(observed['original_artifacts']):
            for field in artifact:
                with self.subTest(artifact=index, field=field):
                    ledger = copy.deepcopy(self.ledger)
                    self.accepted(ledger)['record_observation']['original_artifacts'][index][field] = 'unread_replacement'
                    with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                        module.validate(ledger)

    def test_boolean_and_float_aliases_cannot_relabel_exact_observations(self):
        for mutation in ('signature', 'case-count', 'artifact-size', 'artifact-id',
                         'child-number', 'no-device', 'no-operator', 'record-size'):
            with self.subTest(mutation=mutation):
                ledger = copy.deepcopy(self.ledger)
                accepted = self.accepted(ledger)
                observed = accepted['record_observation']
                if mutation == 'signature':
                    observed['signature_verified'] = 1
                elif mutation == 'case-count':
                    observed['core_cases'] = float(observed['core_cases'])
                elif mutation in ('artifact-size', 'artifact-id'):
                    field = 'bytes' if mutation == 'artifact-size' else 'id'
                    observed['original_artifacts'][0][field] = float(observed['original_artifacts'][0][field])
                elif mutation == 'child-number':
                    accepted['consumed_children'][0]['issue'] = 20.0
                elif mutation == 'record-size':
                    accepted['record']['bytes'] = float(accepted['record']['bytes'])
                else:
                    field = 'installed_or_physical_execution_claimed' if mutation == 'no-device' else 'production_service_or_operating_acceptance_claimed'
                    accepted['decision_demonstration'][field] = 0
                with self.assertRaisesRegex(ValueError, 'reviewed design acceptance: 2'):
                    module.validate(ledger)

    def test_all_four_literal_criteria_reject_rewording_even_with_recomputed_digest(self):
        import hashlib
        for index in range(4):
            with self.subTest(index=index):
                ledger = copy.deepcopy(self.ledger)
                criterion = ledger['issues'][1]['criteria'][index]
                criterion['text'] = 'Every runtime and release is accepted after a document merge.'
                criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                with self.assertRaisesRegex(ValueError, 'reviewed design criterion set changed: 2'):
                    module.validate(ledger)

    def test_all_four_criteria_require_complete_reviewed_decision_evidence(self):
        for index in range(4):
            for field, value in (
                ('status', 'incomplete'), ('completion_evidence', []),
                ('completion_evidence', ['pr-418']), ('completion_evidence', ['pr-412', 'pr-418']),
                ('remaining_engineering', 'Selected decision integration remains unperformed.'),
                ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Receiving review absent.'}]),
                ('evidence_relation', module.COMPLETE_IMPLEMENTATION_RELATION),
            ):
                with self.subTest(index=index, field=field):
                    ledger = copy.deepcopy(self.ledger)
                    ledger['issues'][1]['criteria'][index][field] = value
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_entire_new_evidence_entry_requires_the_exact_merged_delivery(self):
        for field in self.ledger['evidence']['pr-412']:
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                receipt = ledger['evidence']['pr-412']
                receipt[field] = 'unreviewed substitute'
                with self.assertRaises(ValueError):
                    module.validate(ledger)
        ledger = copy.deepcopy(self.ledger)
        ledger['evidence']['pr-412']['scope_limit'] += ' All scanner, device and release work is complete.'
        with self.assertRaisesRegex(ValueError, 'integrated foundation receipt'):
            module.validate(ledger)

    def test_prior_foundation_context_and_all_other_objects_are_preserved(self):
        for mutation in ('title', 'new-issue-field', 'partial-reference', 'partial-result',
                         'new-criterion-field', 'historical-placement'):
            with self.subTest(mutation=mutation):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][1]
                if mutation == 'title':
                    issue['title'] += ' Production accepted'
                elif mutation == 'new-issue-field':
                    issue['invented_previous_success'] = True
                elif mutation == 'partial-reference':
                    issue['criteria'][0]['partial_evidence_refs'] = ['pr-132']
                elif mutation == 'partial-result':
                    issue['criteria'][0]['partial_results'] = []
                elif mutation == 'new-criterion-field':
                    issue['criteria'][0]['invented_historical_approval'] = True
                else:
                    issue['accepted_design_outcome']['historical_acceptance_record']['repository'] = module.REPOSITORY
                with self.assertRaises(ValueError):
                    module.validate(ledger)
        previous_v8 = module.reconstruct_prior_v8(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        other_issues = [i for i in previous_v8['issues'] if i['number'] != 2]
        old_evidence = {k:v for k,v in previous_v8['evidence'].items() if k != 'pr-412'}
        self.assertEqual('5f07f02f18bff26269edf31a221f4cbd97438f146a7179a230a18e54316b25f0',
                         module.json_digest(other_issues))
        self.assertEqual('51d457fcdd9dfae6d4fecb562173d176d9754cfeffa807e905acdbda0bf201ed',
                         module.json_digest(old_evidence))

    def test_foundation_receipt_cannot_complete_any_other_issue_or_model(self):
        for number in (1, 21, 26, 34, 47, 67, 83, 89, 122):
            with self.subTest(number=number):
                ledger = copy.deepcopy(self.ledger)
                issue = ledger['issues'][number - 1]
                issue['accepted_design_outcome'] = copy.deepcopy(self.accepted(ledger))
                issue['canonical_acceptance_record'] = copy.deepcopy(ledger['issues'][1]['canonical_acceptance_record'])
                issue['criteria'][0].update(status='complete', completion_evidence=['pr-412'],
                    remaining_engineering='', external_prerequisites=[],
                    evidence_relation=module.COMPLETE_DESIGN_RELATION)
                with self.assertRaises(ValueError):
                    module.validate(ledger)


class LedgerInputIntegrityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw_ledger = (ROOT / 'docs/delivery/criterion-evidence.json').read_text(encoding='utf-8')
        cls.ledger = json.loads(cls.raw_ledger)
        # Controlled all-state source fixture; this is not a live GitHub capture.
        cls.source = [
            {'number': issue['number'], 'title': issue['title'],
             'state': 'closed' if issue['number'] in (set(module.REVIEWED_DESIGNS) | set(module.REVIEWED_IMPLEMENTATIONS)) else 'open',
             'body': '## Acceptance criteria\n' +
                     '\n'.join('- [x] ' + c['text'] for c in issue['criteria']) +
                     '\n## Delivery record\n`' + issue['canonical_acceptance_record']['path'] + '`'}
            for issue in cls.ledger['issues']
        ]
        cls.raw_source = json.dumps(cls.source, ensure_ascii=False)

    def run_cli(self, ledger_text=None, source_text=None, optimized=False):
        with tempfile.TemporaryDirectory(prefix='ledger-input-') as directory:
            path = Path(directory)
            ledger_path = path / 'ledger.json'
            ledger_path.write_text(self.raw_ledger if ledger_text is None else ledger_text,
                                   encoding='utf-8')
            command = [sys.executable] + (['-O'] if optimized else []) + [
                str(ROOT / 'scripts/check_delivery_ledger.py'), '--ledger', str(ledger_path)]
            if source_text is not None:
                source_path = path / 'issues.json'
                source_path.write_text(source_text, encoding='utf-8')
                command += ['--issues', str(source_path)]
            return subprocess.run(command, capture_output=True, text=True, timeout=10)

    def assert_rejected(self, message, **inputs):
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                result = self.run_cli(optimized=optimized, **inputs)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('Delivery ledger check failed: ', result.stderr)
                self.assertIn(message, result.stderr)
                self.assertNotIn('PASS:', result.stdout)
                self.assertNotIn('Traceback', result.stderr)

    def test_duplicate_schema_and_source_identity_members_fail_at_cli(self):
        member = '"schema_version": ' + str(self.ledger['schema_version'])
        self.assertEqual(1, self.raw_ledger.count(member))
        duplicate = self.raw_ledger.replace(member, '"schema_version": 5, ' + member, 1)
        self.assertNotEqual(self.raw_ledger, duplicate)
        self.assert_rejected('duplicate JSON member', ledger_text=duplicate)
        self.assert_rejected('duplicate JSON member', source_text=self.raw_source.replace(
            '"number": 1', '"number": 999, "number": 1', 1))

    def test_nested_review_and_source_body_duplicates_fail_at_cli(self):
        self.assert_rejected('duplicate JSON member', ledger_text=self.raw_ledger.replace(
            '"receiving_reviews": [', '"receiving_reviews": [], "receiving_reviews": [', 1))
        self.assert_rejected('duplicate JSON member', source_text=self.raw_source.replace(
            '"body":', '"body": "Unreviewed replacement", "body":', 1))

    def test_escaped_duplicate_members_fail_at_both_cli_inputs(self):
        member = '"schema_version": ' + str(self.ledger['schema_version'])
        self.assertEqual(1, self.raw_ledger.count(member))
        duplicate = self.raw_ledger.replace(member, r'"\u0073chema_version": 5, ' + member, 1)
        self.assertNotEqual(self.raw_ledger, duplicate)
        self.assert_rejected('duplicate JSON member', ledger_text=duplicate)
        self.assert_rejected('duplicate JSON member', source_text=self.raw_source.replace(
            '"number": 1', r'"\u006eumber": 999, "number": 1', 1))
        for location, raw in (('ledger_text', self.raw_ledger), ('source_text', self.raw_source)):
            with self.subTest(location=location):
                changed = raw.replace('{', r'{"synthetic_probe": {"x": 0, "\u0078": 1},', 1)
                self.assert_rejected('duplicate JSON member', **{location: changed})

    def test_nonfinite_literals_fail_at_both_cli_inputs(self):
        for token in ('NaN', 'Infinity', '-Infinity'):
            for location, raw in (('ledger_text', self.raw_ledger), ('source_text', self.raw_source)):
                with self.subTest(token=token, location=location):
                    changed = raw.replace('{', '{"synthetic_probe": [' + token + '],', 1)
                    self.assert_rejected('non-finite JSON number', **{location: changed})

    def test_numeric_overflow_fails_at_both_cli_inputs(self):
        for token in ('1e10000', '-1e10000'):
            for location, raw in (('ledger_text', self.raw_ledger), ('source_text', self.raw_source)):
                with self.subTest(token=token, location=location):
                    changed = raw.replace('{', '{"synthetic_probe": [' + token + '],', 1)
                    self.assert_rejected('non-finite JSON number', **{location: changed})

    def test_finite_values_unicode_and_numeric_text_keep_live_audit_behavior(self):
        metadata = json.dumps({'values': [0.0, 1.125, -2.5, 1e308, 1e-308],
                               'text': 'NaN Infinity 1e10000 café 日本語'}, ensure_ascii=False)
        ledger_text = self.raw_ledger.replace('{', '{"synthetic_probe": ' + metadata + ',', 1)
        source_text = self.raw_source.replace('{', '{"synthetic_probe": ' + metadata + ',', 1)
        # JSON admission still accepts finite values and Unicode; the new
        # complete-history fence separately rejects unreviewed ledger metadata.
        with tempfile.TemporaryDirectory(prefix='ledger-finite-json-') as directory:
            path = Path(directory) / 'ledger.json'
            path.write_text(ledger_text, encoding='utf-8')
            self.assertEqual(json.loads(metadata), module.read_json(path)['synthetic_probe'])
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                result = self.run_cli(self.raw_ledger, source_text, optimized)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('391 incomplete criteria, 25 reviewed design criteria, 21 reviewed implementation criteria, 1 reviewed import-integrity criterion, 3 reviewed support criteria', result.stdout)
                self.assertIn('exact source criteria matched.', result.stdout)
                self.assertEqual(result.stderr, '')
        self.assert_rejected('unreviewed change to complete prior v11 ledger history',
                             ledger_text=ledger_text, source_text=source_text)
        changed = copy.deepcopy(self.source)
        changed[0]['body'] = changed[0]['body'].replace('All selected core work packages',
                                                      'Changed selected work packages')
        self.assert_rejected('live criteria differ: 1', source_text=json.dumps(changed))

    def test_each_ordinary_record_rejects_a_different_valid_website_path(self):
        for issue in self.ledger['issues']:
            if (issue['number'] in module.REVIEWED_DESIGNS or issue['number'] in module.REVIEWED_IMPLEMENTATIONS
                    or issue['number'] in (module.SUPPORT_33_NUMBER, 37, 84, 96)):
                continue
            with self.subTest(number=issue['number']):
                ledger = copy.deepcopy(self.ledger)
                ledger['issues'][issue['number'] - 1]['canonical_acceptance_record']['path'] = (
                    'docs/delivery/android/work-packages/99.99.md')
                with self.assertRaisesRegex(ValueError, 'unreviewed acceptance coordinates'):
                    module.validate(ledger)

    def test_matching_source_substitution_cannot_move_an_ordinary_record(self):
        ledger = copy.deepcopy(self.ledger)
        source = copy.deepcopy(self.source)
        original = ledger['issues'][47]['canonical_acceptance_record']['path']
        substituted = 'docs/delivery/android/work-packages/99.99.md'
        ledger['issues'][47]['canonical_acceptance_record']['path'] = substituted
        source[47]['body'] = source[47]['body'].replace(original, substituted)
        self.assert_rejected('unreviewed acceptance coordinates',
                             ledger_text=json.dumps(ledger), source_text=json.dumps(source))


class ImportCriterionAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    @staticmethod
    def criterion(ledger):
        return ledger['issues'][20]['criteria'][1]

    def acceptance(self, ledger):
        return self.criterion(ledger)['accepted_import_integrity_outcome']

    def test_only_literal_import_criterion_is_newly_complete(self):
        module.validate(self.ledger)
        prior = module.reconstruct_prior_v8(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        self.assertEqual('5db8320d3f26f0ddf24e751e41489aaec344f997eb52d408a95449b4b6cabdae',
                         module.json_digest(prior))
        # Keep this original import-only comparison at its reconstructed v9.
        reviewed_v9 = module.reconstruct_prior_v9(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        current = [c for issue in reviewed_v9['issues'] for c in issue['criteria']]
        original = [c for issue in prior['issues'] for c in issue['criteria']]
        self.assertEqual(441, len(current))
        self.assertEqual(['21.2'], [c['id'] for c, old in zip(current, original) if c != old])
        self.assertEqual(['21.2'], [c['id'] for c in current if 'accepted_import_integrity_outcome' in c])
        self.assertEqual(412, sum(c['status'] == 'incomplete' for c in current))
        self.assertEqual(25, sum(c['evidence_relation'] == module.COMPLETE_DESIGN_RELATION for c in current))
        self.assertEqual(3, sum(c['evidence_relation'] == module.COMPLETE_IMPLEMENTATION_RELATION for c in current))
        self.assertEqual(1, sum(c['evidence_relation'] == module.COMPLETE_IMPORT_RELATION for c in current))
        self.assertEqual(['pr-133', 'pr-134'], self.criterion(self.ledger)['partial_evidence_refs'])
        self.assertEqual(['21.1', '21.3'], [c['id'] for c in self.ledger['issues'][20]['criteria']
                                          if c['status'] == 'incomplete'])
        self.assertNotIn('accepted_design_outcome', self.ledger['issues'][20])
        self.assertNotIn('accepted_implementation_outcome', self.ledger['issues'][20])
        self.assertEqual(prior['issues'][32], reviewed_v9['issues'][32])
        self.assertEqual(set(prior['evidence']) | {'pr-430'}, set(reviewed_v9['evidence']))

    def test_import_receipt_documents_and_all_45_mapped_bytes_match(self):
        import hashlib
        accepted = self.acceptance(self.ledger)
        self.assertEqual(4, len(accepted['delivery']['artifacts']))
        for artifact in accepted['delivery']['artifacts']:
            with self.subTest(path=artifact['path']):
                raw = (ROOT / artifact['path']).read_bytes()
                self.assertEqual('100644', artifact['mode'])
                self.assertEqual(artifact['bytes'], len(raw))
                self.assertEqual(artifact['sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(artifact['blob'], hashlib.sha1(
                    b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
                self.assertIn('/blob/' + accepted['delivery']['revision'] + '/', artifact['url'])
        record = module.read_json(ROOT / accepted['import_inventory']['complete_mapping_path'])
        self.assertEqual(record['upstream'], accepted['upstream'])
        self.assertEqual(record['executed_integrity'], accepted['import_execution'])
        self.assertEqual(record['criterion']['text'], self.criterion(self.ledger)['text'])
        rows = record['consumer']['entries']
        self.assertEqual(45, len(rows))
        self.assertEqual(45, len({row['path'] for row in rows}))
        self.assertEqual(43, sum(row['role'] == 'original_package_file' for row in rows))
        self.assertEqual(2, sum(row['role'] == 'pinned_import_metadata' for row in rows))
        for row in rows:
            with self.subTest(import_path=row['path']):
                raw = (ROOT / 'contracts/v1' / row['path']).read_bytes()
                self.assertEqual('100644', row['mode'])
                self.assertEqual(row['bytes'], len(raw))
                self.assertEqual(row['sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(row['git_blob'], hashlib.sha1(
                    b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())

    def test_import_receipt_is_required_and_has_no_generic_approval_class(self):
        values = [None, {}, {'approved': True},
                  self.ledger['issues'][1]['accepted_design_outcome'],
                  self.ledger['issues'][25]['accepted_implementation_outcome']]
        for value in values:
            with self.subTest(value_type=type(value).__name__):
                ledger = copy.deepcopy(self.ledger)
                if value is None:
                    del self.criterion(ledger)['accepted_import_integrity_outcome']
                else:
                    self.criterion(ledger)['accepted_import_integrity_outcome'] = copy.deepcopy(value)
                with self.assertRaisesRegex(ValueError, 'reviewed import-integrity acceptance: 21.2'):
                    module.validate(ledger)
        for classification in ('reviewed_design_outcome', 'reviewed_implementation_outcome', 'whole_issue_complete'):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['classification'] = classification
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_import_receipt_cannot_complete_any_of_the_other_440_criteria(self):
        for issue in self.ledger['issues']:
            for index, criterion in enumerate(issue['criteria']):
                if criterion['id'] == '21.2':
                    continue
                with self.subTest(target=criterion['id']):
                    ledger = copy.deepcopy(self.ledger)
                    target = ledger['issues'][issue['number'] - 1]['criteria'][index]
                    target.update(copy.deepcopy(self.criterion(ledger)))
                    target['id'] = criterion['id']
                    target['text'] = criterion['text']
                    target['text_sha256'] = criterion['text_sha256']
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_neighbors_and_pending_support_cannot_be_completed_without_the_receipt(self):
        for number, index in ((21, 0), (21, 2), (32, 0), (33, 0), (48, 0), (49, 0)):
            with self.subTest(number=number, ordinal=index + 1):
                ledger = copy.deepcopy(self.ledger)
                ledger['issues'][number - 1]['criteria'][index].update(
                    status='complete', completion_evidence=['pr-430'], remaining_engineering='',
                    external_prerequisites=[], evidence_relation=module.COMPLETE_IMPORT_RELATION)
                with self.assertRaises(ValueError):
                    module.validate(ledger)
        ledger = copy.deepcopy(self.ledger)
        ledger['issues'][20]['accepted_import_integrity_outcome'] = copy.deepcopy(self.acceptance(ledger))
        with self.assertRaisesRegex(ValueError, 'preserve prior #21 context'):
            module.validate(ledger)

    def test_literal_text_and_original_website_coordinate_are_not_reclassified(self):
        import hashlib
        for mutation in ('text', 'repository', 'path', 'receipt-id', 'receipt-hash'):
            with self.subTest(mutation=mutation):
                ledger = copy.deepcopy(self.ledger)
                if mutation == 'text':
                    criterion = self.criterion(ledger)
                    criterion['text'] = 'All models, API and storage boundaries are complete.'
                    criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                elif mutation in ('repository', 'path'):
                    ledger['issues'][20]['canonical_acceptance_record'][mutation] = (
                        module.REPOSITORY if mutation == 'repository' else 'docs/delivery/android/work-packages/03.02.md')
                else:
                    field = 'criterion_id' if mutation == 'receipt-id' else 'criterion_text_sha256'
                    self.acceptance(ledger)[field] = ledger['issues'][20]['criteria'][0][
                        'id' if mutation == 'receipt-id' else 'text_sha256']
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_upstream_commit_tree_and_import_inventory_are_not_interchangeable(self):
        accepted = self.acceptance(self.ledger)
        self.assertNotEqual(accepted['upstream']['merge_commit'], accepted['upstream']['contract_subtree'])
        for group, field, value in (
            ('upstream', 'merge_commit', accepted['upstream']['contract_subtree']),
            ('upstream', 'contract_subtree', accepted['upstream']['merge_commit']),
            ('upstream', 'contract_subtree_object_type', 'commit'),
            ('upstream', 'root_tree', '0' * 40),
            ('upstream', 'excluded_research_document', {}),
            ('upstream', 'contract_version', '2.0.0'),
            ('import_inventory', 'original_package_files', 44),
            ('import_inventory', 'pinned_metadata_files', 0),
            ('import_inventory', 'exact_import_leaves', 43),
            ('import_inventory', 'manifest_sha256', '0' * 64),
            ('import_inventory', 'upstream_sums_sha256', '0' * 64),
            ('import_inventory', 'archived_prompt_mapping', {}),
        ):
            with self.subTest(group=group, field=field):
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)[group][field] = value
                with self.assertRaisesRegex(ValueError, 'reviewed import-integrity acceptance: 21.2'):
                    module.validate(ledger)

    def test_actual_receiving_role_source_and_signed_delivery_are_required(self):
        for group in ('receiving_reviews', 'source_review_revision', 'source_review_tree', 'delivery',
                      'outcome_boundaries', 'remaining_criteria', 'canonical_acceptance_record'):
            with self.subTest(group=group):
                ledger = copy.deepcopy(self.ledger)
                del self.acceptance(ledger)[group]
                with self.assertRaisesRegex(ValueError, 'reviewed import-integrity acceptance: 21.2'):
                    module.validate(ledger)
        for field in self.acceptance(self.ledger)['receiving_reviews'][0]:
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['receiving_reviews'][0][field] = 'unrecorded_account_or_receiving_acceptance'
            with self.assertRaises(ValueError):
                module.validate(ledger)
        for field in ('revision', 'tree', 'parent', 'signature_verified', 'merged_at', 'artifacts'):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['delivery'][field] = 'unpublished_or_unverified_source'
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_original_import_execution_cannot_be_relabelled_or_lose_a_negative_case(self):
        observed = self.acceptance(self.ledger)['import_execution']
        for field in observed:
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)['import_execution'][field] = 'a_new_unexecuted_ledger_run'
                with self.assertRaisesRegex(ValueError, 'reviewed import-integrity acceptance: 21.2'):
                    module.validate(ledger)
        for index in range(4):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['import_execution']['negative_methods'].pop(index)
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_cancelled_primary_and_successful_following_default_remain_distinct(self):
        value = self.acceptance(self.ledger)['default_verification']
        self.assertNotEqual(value['primary']['revision'], value['following_documentation_only']['revision'])
        self.assertEqual('cancelled', value['primary']['overall_outcome'])
        self.assertEqual('cancelled', value['primary']['preview_outcome'])
        self.assertEqual(0, value['primary']['completed_preview_xml_reports'])
        self.assertEqual('success', value['following_documentation_only']['overall_outcome'])
        for group, field, changed in (
            ('primary', 'overall_outcome', 'success'), ('primary', 'preview_outcome', 'success'),
            ('primary', 'completed_preview_xml_reports', 25), ('primary', 'core_outcome', 'skipped'),
            ('primary', 'scanner_outcome', 'cancelled'),
            ('following_documentation_only', 'revision', value['primary']['revision']),
            ('following_documentation_only', 'parent', '0' * 40),
            ('following_documentation_only', 'source_change', 'untested_product_change'),
            ('following_documentation_only', 'overall_outcome', 'pending'),
        ):
            with self.subTest(group=group, field=field):
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)['default_verification'][group][field] = changed
                with self.assertRaisesRegex(ValueError, 'reviewed import-integrity acceptance: 21.2'):
                    module.validate(ledger)
        for field in ('cancelled_results_relabelled', 'new_execution_claimed_by_ledger'):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['default_verification'][field] = True
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_original_artifacts_and_exact_numeric_or_boolean_types_are_required(self):
        for group in ('primary', 'following_documentation_only'):
            for mutation in ('artifact-id', 'artifact-bytes', 'artifact-hash', 'case-float', 'case-boolean'):
                with self.subTest(group=group, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    observed = self.acceptance(ledger)['default_verification'][group]
                    if mutation.startswith('artifact-'):
                        field = {'artifact-id': 'id', 'artifact-bytes': 'bytes', 'artifact-hash': 'sha256'}[mutation]
                        observed['core_artifact'][field] = ('0' * 64 if field == 'sha256' else
                                                           float(observed['core_artifact'][field]))
                    else:
                        observed['core_cases'] = True if mutation == 'case-boolean' else float(observed['core_cases'])
                    with self.assertRaisesRegex(ValueError, 'reviewed import-integrity acceptance: 21.2'):
                        module.validate(ledger)
        for field in ('canonical_acceptance_record_relocated', 'whole_issue_completion'):
            for alias in (True, 0):
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)[field] = alias
                with self.assertRaises(ValueError):
                    module.validate(ledger)
        ledger = copy.deepcopy(self.ledger)
        self.acceptance(ledger)['delivery']['signature_verified'] = 1
        with self.assertRaises(ValueError):
            module.validate(ledger)

    def test_completion_requires_only_the_exact_evidence_and_no_unfinished_import_clause(self):
        for field, value in (
            ('status', 'incomplete'), ('completion_evidence', []), ('completion_evidence', ['pr-133']),
            ('completion_evidence', ['pr-430', 'pr-133']), ('remaining_engineering', 'Import pin not checked.'),
            ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Import review pending.'}]),
            ('evidence_relation', module.COMPLETE_IMPLEMENTATION_RELATION),
        ):
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                self.criterion(ledger)[field] = value
                with self.assertRaises(ValueError):
                    module.validate(ledger)
        for field in self.ledger['evidence']['pr-430']:
            ledger = copy.deepcopy(self.ledger)
            ledger['evidence']['pr-430'][field] = 'unreviewed_receipt_or_broader_scope'
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_original_partial_context_and_all_v8_history_are_preserved(self):
        for mutation in ('partial-reference', 'partial-result', 'extra-field', 'issue-title'):
            with self.subTest(mutation=mutation):
                ledger = copy.deepcopy(self.ledger)
                criterion = self.criterion(ledger)
                if mutation == 'partial-reference':
                    criterion['partial_evidence_refs'] = ['pr-430']
                elif mutation == 'partial-result':
                    criterion['partial_results'] = []
                elif mutation == 'extra-field':
                    criterion['approved_every_boundary'] = True
                else:
                    ledger['issues'][20]['title'] += ' (all complete)'
                with self.assertRaisesRegex(ValueError, 'preserve prior #21 context'):
                    module.validate(ledger)
        prior = module.reconstruct_prior_v8(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        self.assertEqual(8, prior['schema_version'])
        self.assertEqual(24, len(prior['evidence']))
        self.assertEqual(413, sum(c['status'] == 'incomplete' for i in prior['issues'] for c in i['criteria']))
        self.assertEqual('5db8320d3f26f0ddf24e751e41489aaec344f997eb52d408a95449b4b6cabdae', module.json_digest(prior))

    def test_validation_and_prior_reconstruction_never_mutate_the_callers_ledger(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        self.assertEqual(original, self.ledger)
        previous = module.reconstruct_prior_v8(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        previous['issues'][20]['criteria'][0]['text'] = 'Only the returned prior copy changes.'
        previous['evidence']['pr-133']['scope_limit'] = 'Only the returned prior copy changes.'
        self.assertEqual(original, self.ledger)



class SupportOutcomeAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    @staticmethod
    def issue(ledger):
        return ledger['issues'][32]

    def acceptance(self, ledger):
        return self.issue(ledger)['accepted_support_outcome']

    def test_only_three_literal_support_criteria_change_and_complete_v9_is_recovered(self):
        import hashlib
        module.validate(self.ledger)
        prior = module.reconstruct_prior_v9(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        self.assertEqual(9, prior['schema_version'])
        self.assertEqual('81314afc8892c8b6069fa6ccfdc35805f7feec3dbec3cf304eaccc392fa5c55b',
                         module.json_digest(prior))
        raw = (json.dumps(prior, indent=2, ensure_ascii=False) + '\n').encode()
        self.assertEqual(494218, len(raw))
        self.assertEqual('49fc9557d3fc383c355663c2a71cc5096a4e2dcca5b1ed91da7657e249d627c4',
                         hashlib.sha256(raw).hexdigest())
        # Preserve this earlier support-only comparison at reconstructed v10.
        reviewed_v10 = module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))))))
        current = [c for i in reviewed_v10['issues'] for c in i['criteria']]
        previous = [c for i in prior['issues'] for c in i['criteria']]
        self.assertEqual(441, len(current))
        self.assertEqual(['33.1', '33.2', '33.3'], [c['id'] for c, old in zip(current, previous) if c != old])
        self.assertEqual([33], [i['number'] for i, old in zip(reviewed_v10['issues'], prior['issues']) if i != old])
        self.assertEqual(25, len(prior['evidence']))
        self.assertEqual(prior['evidence'], {k: v for k, v in reviewed_v10['evidence'].items() if k != 'pr-494'})
        self.assertEqual(409, sum(c['status'] == 'incomplete' for c in current))
        self.assertEqual(3, sum(c['evidence_relation'] == module.COMPLETE_SUPPORT_RELATION for c in current))
        self.assertEqual(prior['issues'][20], self.ledger['issues'][20])
        self.assertEqual(['21.1', '21.3'], [c['id'] for c in self.ledger['issues'][20]['criteria'] if c['status'] == 'incomplete'])
        self.assertEqual(['33.1', '33.2', '33.3'], self.acceptance(self.ledger)['criterion_ids'])

    def test_historical_document_pins_and_current_canonical_records_remain_distinct(self):
        import hashlib
        accepted = self.acceptance(self.ledger)
        docs = accepted['delivery']['artifacts']
        canonical_paths = {
            'docs/decisions/0009-android-33-acceptance-record.md',
            'docs/delivery/android/work-packages/17.04.md',
        }
        historical_guide_paths = {
            'docs/REPOSITORY_SCOPE.md', 'docs/SUPPORT_HELP.md', 'docs/LOCAL_DIAGNOSTICS.md',
        }
        self.assertEqual(5, len(docs))
        self.assertEqual(canonical_paths | historical_guide_paths, {d['path'] for d in docs})
        for artifact in docs:
            with self.subTest(path=artifact['path']):
                self.assertEqual('100644', artifact['mode'])
                self.assertIn('/blob/' + accepted['delivery']['revision'] + '/', artifact['url'])
                # All five pins describe the historical delivery. Only the two
                # canonical acceptance records are immutable current inputs;
                # later reviewed edits to the three shared guides remain possible.
                if artifact['path'] not in canonical_paths:
                    continue
                path = ROOT / artifact['path']
                raw = path.read_bytes()
                self.assertFalse(path.stat().st_mode & 0o100)
                self.assertEqual(artifact['bytes'], len(raw))
                self.assertEqual(artifact['sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(artifact['blob'], hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        changed = [d['path'] for d in docs if d['blob'] != d['original_review_blob']]
        self.assertEqual(['docs/SUPPORT_HELP.md'], changed)
        record = (ROOT / accepted['canonical_acceptance_record']['path']).read_text()
        for criterion in self.issue(self.ledger)['criteria']:
            self.assertEqual(1, record.count(criterion['text']))
        self.assertEqual(4, len(accepted['implemented_subtasks']))
        for task in accepted['implemented_subtasks']:
            self.assertIn(task, record)
        self.assertEqual(2, len(accepted['validation_methods']))
        self.assertIn('ADR 0009', record)

    def test_support_receipt_is_mandatory_and_not_design_model_import_or_generic_approval(self):
        for value in (None, {}, {'approved': True},
                      self.ledger['issues'][1]['accepted_design_outcome'],
                      self.ledger['issues'][25]['accepted_implementation_outcome'],
                      self.ledger['issues'][20]['criteria'][1]['accepted_import_integrity_outcome']):
            with self.subTest(value_type=type(value).__name__):
                ledger = copy.deepcopy(self.ledger)
                if value is None:
                    del self.issue(ledger)['accepted_support_outcome']
                else:
                    self.issue(ledger)['accepted_support_outcome'] = copy.deepcopy(value)
                with self.assertRaisesRegex(ValueError, 'reviewed support acceptance: 33'):
                    module.validate(ledger)
        for alias in ('accepted_design_outcome', 'accepted_implementation_outcome'):
            ledger = copy.deepcopy(self.ledger)
            self.issue(ledger)[alias] = self.issue(ledger).pop('accepted_support_outcome')
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_support_receipt_cannot_authorize_any_other_issue(self):
        for issue in self.ledger['issues']:
            if issue['number'] == 33:
                continue
            with self.subTest(number=issue['number']):
                ledger = copy.deepcopy(self.ledger)
                target = ledger['issues'][issue['number'] - 1]
                target['accepted_support_outcome'] = copy.deepcopy(self.acceptance(ledger))
                target['criteria'][0].update(status='complete', completion_evidence=['pr-494'],
                    remaining_engineering='', external_prerequisites=[], evidence_relation=module.COMPLETE_SUPPORT_RELATION)
                with self.assertRaisesRegex(ValueError, f"unsupported support acceptance: {issue['number']}"):
                    module.validate(ledger)

    def test_support_receipt_cannot_be_transplanted_to_any_criterion(self):
        for issue in self.ledger['issues']:
            for index, criterion in enumerate(issue['criteria']):
                with self.subTest(criterion=criterion['id']):
                    ledger = copy.deepcopy(self.ledger)
                    target = ledger['issues'][issue['number'] - 1]['criteria'][index]
                    target['accepted_support_outcome'] = copy.deepcopy(self.acceptance(ledger))
                    with self.assertRaisesRegex(ValueError, 'unsupported criterion-local support acceptance'):
                        module.validate(ledger)

    def test_bare_support_relation_and_receipt_do_not_complete_other_pending_outcomes(self):
        for number, ordinal in ((21, 0), (21, 2), (32, 0), (48, 0), (49, 0), (89, 0), (129, 0)):
            with self.subTest(number=number, ordinal=ordinal):
                ledger = copy.deepcopy(self.ledger)
                ledger['issues'][number - 1]['criteria'][ordinal].update(status='complete',
                    completion_evidence=['pr-494'], remaining_engineering='', external_prerequisites=[],
                    evidence_relation=module.COMPLETE_SUPPORT_RELATION)
                with self.assertRaises(ValueError):
                    module.validate(ledger)

    def test_support_criterion_text_identity_and_all_three_outcomes_are_fixed(self):
        import hashlib
        for ordinal in range(3):
            for mutation in ('id', 'text', 'incomplete', 'missing', 'reordered'):
                with self.subTest(ordinal=ordinal, mutation=mutation):
                    ledger = copy.deepcopy(self.ledger)
                    criteria = self.issue(ledger)['criteria']
                    if mutation == 'id':
                        criteria[ordinal]['id'] = '33.4'
                    elif mutation == 'text':
                        criteria[ordinal]['text'] = 'Installed delivery and perpetual monitoring accepted.'
                        criteria[ordinal]['text_sha256'] = hashlib.sha256(criteria[ordinal]['text'].encode()).hexdigest()
                    elif mutation == 'incomplete':
                        criteria[ordinal]['status'] = 'incomplete'
                    elif mutation == 'missing':
                        criteria.pop(ordinal)
                    else:
                        criteria[ordinal], criteria[(ordinal + 1) % 3] = criteria[(ordinal + 1) % 3], criteria[ordinal]
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_support_completion_cannot_hide_unfinished_clauses_or_borrow_evidence(self):
        for ordinal in range(3):
            for field, value in (
                ('completion_evidence', []), ('completion_evidence', ['pr-430']),
                ('completion_evidence', ['pr-494', 'pr-430']),
                ('remaining_engineering', 'Actual public receipt is still missing.'),
                ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Receiver has not accepted.'}]),
                ('evidence_relation', module.COMPLETE_DESIGN_RELATION),
                ('evidence_relation', module.COMPLETE_IMPLEMENTATION_RELATION),
                ('evidence_relation', module.COMPLETE_IMPORT_RELATION),
            ):
                with self.subTest(ordinal=ordinal, field=field, value=value):
                    ledger = copy.deepcopy(self.ledger)
                    self.issue(ledger)['criteria'][ordinal][field] = value
                    with self.assertRaises(ValueError):
                        module.validate(ledger)

    def test_source_receiving_roles_and_actual_signed_delivery_cannot_be_replaced(self):
        accepted = self.acceptance(self.ledger)
        self.assertEqual(['support/operations', 'affected local-data/service'], [r['role'] for r in accepted['receiving_reviews']])
        self.assertEqual([5566090971, 5566084664], [r['id'] for r in accepted['receiving_reviews']])
        self.assertTrue(all(r['state'] == 'COMMENTED' for r in accepted['receiving_reviews']))
        self.assertNotEqual(accepted['source_review_revision'], accepted['delivery']['revision'])
        self.assertEqual(6260058131, accepted['delivery']['receipt']['id'])
        for field in ('source_review_revision', 'source_review_tree', 'source_review_signature',
                      'receiving_reviews', 'delivery', 'implemented_subtasks', 'validation_methods', 'prerequisite'):
            ledger = copy.deepcopy(self.ledger)
            del self.acceptance(ledger)[field]
            with self.assertRaisesRegex(ValueError, 'reviewed support acceptance: 33'):
                module.validate(ledger)
        for ordinal in range(2):
            for field in accepted['receiving_reviews'][ordinal]:
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)['receiving_reviews'][ordinal][field] = 'unrecorded_approval_or_appointed_future_owner'
                with self.assertRaises(ValueError):
                    module.validate(ledger)
        for field in ('revision', 'tree', 'parent', 'signature_verified', 'merged_at', 'artifacts', 'support_help_composition', 'receipt'):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['delivery'][field] = 'unpublished_or_unverified_delivery'
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_both_browser_receipts_and_distinct_triage_cannot_be_replaced_by_render_or_api(self):
        accepted = self.acceptance(self.ledger)['controlled_intake']
        self.assertEqual([481, 482], [r['number'] for r in accepted['routes']])
        self.assertEqual([6257197299, 6257199698], [r['triage']['id'] for r in accepted['routes']])
        self.assertEqual([2181, 2553], [r['original_body_characters'] for r in accepted['routes']])
        self.assertEqual([478, 479], accepted['separate_api_history'])
        for ordinal in range(2):
            for field in accepted['routes'][ordinal]:
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)['controlled_intake']['routes'][ordinal].pop(field)
                with self.assertRaisesRegex(ValueError, 'reviewed support acceptance: 33'):
                    module.validate(ledger)
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['controlled_intake']['routes'][ordinal]['number'] = (478, 479)[ordinal]
            with self.assertRaises(ValueError):
                module.validate(ledger)
        for field, value in (('routes', []), ('explicit_browser_submissions_per_route', 0),
                             ('connector_creation_substituted', True), ('private_data_or_attachments', True),
                             ('installed_app_or_android_api_observed', True)):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['controlled_intake'][field] = value
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_synthetic_unknown_and_submitter_reference_clarification_keep_their_limits(self):
        accepted = self.acceptance(self.ledger)['controlled_intake']
        evidence = accepted['routes'][1]
        self.assertIn('retain unknown', evidence['triage']['disposition'])
        self.assertEqual(2848, evidence['submitter_link_clarification']['body_characters'])
        self.assertNotEqual(evidence['original_body_sha256'], evidence['submitter_link_clarification']['body_sha256'])
        self.assertEqual([f'https://github.com/Pick-Your-AI/pick-your-ai-research/issues/{n}' for n in (41, 39, 40, 60)],
                         evidence['submitter_link_clarification']['canonical_authorities'])
        for mutation in ('compatibility-pass', 'source-evidence', 'omit-clarification', 'rewrite-original', 'invent-ongoing-owner'):
            ledger = copy.deepcopy(self.ledger)
            intake = self.acceptance(ledger)['controlled_intake']
            route = intake['routes'][1]
            if mutation == 'compatibility-pass':
                route['triage']['disposition'] = 'Compatibility passed without evidence.'
            elif mutation == 'source-evidence':
                intake['real_domain_record_changed'] = True
            elif mutation == 'omit-clarification':
                del route['submitter_link_clarification']
            elif mutation == 'rewrite-original':
                route['original_body_sha256'] = route['submitter_link_clarification']['body_sha256']
            else:
                intake['continuing_monitoring_or_sla_claimed'] = True
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_diagnostic_consent_private_data_and_unavailable_routes_are_not_optional(self):
        accepted = self.acceptance(self.ledger)['diagnostic_and_help_boundary']
        self.assertEqual(8, len(accepted['original_eight_diagnostic_cases']))
        self.assertEqual(8, len(accepted['original_eight_help_cases']))
        self.assertEqual(3, len(accepted['later_accepted_catalog_identity_cases']))
        self.assertEqual(2, accepted['finite_state_inputs'])
        self.assertEqual(10, accepted['compiled_preview_fields'])
        self.assertFalse(accepted['automatic_upload'])
        self.assertFalse(accepted['raw_private_input_accepted'])
        for field in accepted:
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                del self.acceptance(ledger)['diagnostic_and_help_boundary'][field]
                with self.assertRaisesRegex(ValueError, 'reviewed support acceptance: 33'):
                    module.validate(ledger)
        for class_name, fields in (
            ('LocalDiagnosticsActivityTest', ['original_eight_diagnostic_cases']),
            ('LocalHelpActivityTest', ['original_eight_help_cases', 'later_accepted_catalog_identity_cases']),
        ):
            import re
            raw = (ROOT / f'preview/src/test/kotlin/ai/pickyour/android/preview/{class_name}.kt').read_text()
            methods = re.findall(r'@Test(?:\s+@[^\n]+)*\s+fun\s+(\w+)\s*\(', raw)
            expected = [method for field in fields for method in accepted[field]]
            self.assertEqual(len(expected), len(set(expected)))
            for method in expected:
                with self.subTest(class_name=class_name, method=method):
                    self.assertEqual(1, methods.count(method))

    def test_cancelled_primary_and_preserved_support_default_are_separate_originals(self):
        defaults = self.acceptance(self.ledger)['default_verification']
        primary = defaults['primary']
        later = defaults['following_preserved_support_source']
        self.assertEqual('cancelled', primary['overall_outcome'])
        self.assertEqual('cancelled', primary['preview_outcome'])
        self.assertFalse(primary['completed_preview_qualification'])
        self.assertEqual(356, primary['available_passing_preview_cases'])
        self.assertEqual(31, primary['available_preview_xml_suites'])
        self.assertEqual(5, len(primary['missing_preview_qualification']))
        self.assertEqual(primary['revision'], later['parent'])
        self.assertNotEqual(primary['run'], later['run'])
        self.assertEqual('success', later['overall_outcome'])
        self.assertEqual((4153, 86, 356, 31, 45, 27), tuple(later[k] for k in (
            'core_cases', 'core_suites', 'preview_cases', 'preview_suites', 'preview_steps_success', 'preview_attendance_programs')))
        self.assertEqual(6260050638, later['receipt']['id'])
        for group, field, value in (
            ('primary', 'overall_outcome', 'success'), ('primary', 'preview_outcome', 'success'),
            ('primary', 'completed_preview_qualification', True), ('primary', 'missing_preview_qualification', []),
            ('following_preserved_support_source', 'revision', primary['revision']),
            ('following_preserved_support_source', 'overall_outcome', 'pending'),
            ('following_preserved_support_source', 'source_change', 'unreviewed_support_change'),
            ('following_preserved_support_source', 'preview_attendance_programs', 0),
        ):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)['default_verification'][group][field] = value
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_original_artifacts_public_receipts_and_scalar_types_are_fixed(self):
        for group in ('primary', 'following_preserved_support_source'):
            observed = self.acceptance(self.ledger)['default_verification'][group]
            self.assertEqual(4, len(observed['artifacts']))
            for ordinal in range(4):
                for field in ('id', 'bytes', 'sha256'):
                    ledger = copy.deepcopy(self.ledger)
                    artifact = self.acceptance(ledger)['default_verification'][group]['artifacts'][ordinal]
                    artifact[field] = '0' * 64 if field == 'sha256' else float(artifact[field])
                    with self.assertRaises(ValueError):
                        module.validate(ledger)
        for field, value in (('issue', 33.0), ('issue', True), ('scope', 'production_shared_service'),
                             ('classification', 'generic_approved'), ('outcome_boundaries', [])):
            ledger = copy.deepcopy(self.ledger)
            self.acceptance(ledger)[field] = value
            with self.assertRaises(ValueError):
                module.validate(ledger)
        for field in ('cancelled_results_relabelled', 'new_execution_claimed_by_ledger'):
            for alias in (True, 0):
                ledger = copy.deepcopy(self.ledger)
                self.acceptance(ledger)['default_verification'][field] = alias
                with self.assertRaises(ValueError):
                    module.validate(ledger)
        ledger = copy.deepcopy(self.ledger)
        self.acceptance(ledger)['delivery']['signature_verified'] = 1
        with self.assertRaises(ValueError):
            module.validate(ledger)

    def test_only_the_reviewed_support_coordinate_moves_and_original_history_is_retained(self):
        accepted = self.acceptance(self.ledger)
        self.assertEqual(module.REPOSITORY, self.issue(self.ledger)['canonical_acceptance_record']['repository'])
        self.assertEqual('Pick-Your-AI/pickyour.ai', accepted['historical_acceptance_record']['repository'])
        self.assertEqual(33, accepted['placement_decision']['only_issue'])
        for mutation in ('record-repository', 'record-path', 'historical-repository', 'historical-path', 'adr-scope'):
            ledger = copy.deepcopy(self.ledger)
            receipt = self.acceptance(ledger)
            if mutation.startswith('record-'):
                key = mutation.removeprefix('record-')
                self.issue(ledger)['canonical_acceptance_record'][key] = ('Pick-Your-AI/pickyour.ai' if key == 'repository' else 'docs/delivery/android/work-packages/17.03.md')
            elif mutation.startswith('historical-'):
                key = mutation.removeprefix('historical-')
                receipt['historical_acceptance_record'][key] = (module.REPOSITORY if key == 'repository' else 'docs/delivery/android/work-packages/17.03.md')
            else:
                receipt['placement_decision']['only_issue'] = 21
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_earlier_support_context_metadata_and_every_prior_receipt_remain_immutable(self):
        for mutation in ('issue-title', 'partial-reference', 'extra-criterion', 'extra-issue', 'historical-metadata'):
            ledger = copy.deepcopy(self.ledger)
            if mutation == 'issue-title':
                self.issue(ledger)['title'] += ' broad completion'
            elif mutation == 'partial-reference':
                self.issue(ledger)['criteria'][0]['partial_evidence_refs'] = ['pr-494']
            elif mutation == 'extra-criterion':
                self.issue(ledger)['criteria'][0]['also_accepts_installed_service'] = True
            elif mutation == 'extra-issue':
                self.issue(ledger)['all_children_accepted'] = True
            else:
                ledger['observed_on'] = 'future_observation'
            with self.assertRaises(ValueError):
                module.validate(ledger)
        for ref in module.reconstruct_prior_v9(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))['evidence']:
            ledger = copy.deepcopy(self.ledger)
            ledger['evidence'][ref]['scope_limit'] += ' and unrestricted support'
            with self.assertRaises(ValueError):
                module.validate(ledger)

    def test_support_evidence_and_summary_cannot_be_fabricated_or_reclassified(self):
        for field in self.ledger['evidence']['pr-494']:
            ledger = copy.deepcopy(self.ledger)
            ledger['evidence']['pr-494'][field] = 'unreviewed_source_or_completion'
            with self.assertRaises(ValueError):
                module.validate(ledger)
        for purpose in (module.PRIOR_V9_PURPOSE, 'All 441 criteria are complete.',
                        self.ledger['purpose'].replace('391', '390')):
            ledger = copy.deepcopy(self.ledger)
            ledger['purpose'] = purpose
            with self.assertRaisesRegex(ValueError, 'unsupported ledger completion summary'):
                module.validate(ledger)

    def test_validation_and_both_history_reconstructions_preserve_the_input(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        self.assertEqual(original, self.ledger)
        prior = module.reconstruct_prior_v9(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        earlier = module.reconstruct_prior_v8(module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))))
        prior['issues'][32]['criteria'][0]['text'] = 'Changed only in the returned copy.'
        prior['evidence']['pr-430']['scope_limit'] = 'Changed only in the returned copy.'
        earlier['issues'][20]['criteria'][1]['text'] = 'Changed only in the returned copy.'
        self.assertEqual(original, self.ledger)



class RetainedEvaluationAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    @staticmethod
    def acceptance(ledger):
        return ledger['issues'][83]['accepted_implementation_outcome']

    def rejected(self, change):
        ledger = copy.deepcopy(self.ledger)
        change(ledger)
        with self.assertRaises(ValueError):
            module.validate(ledger)

    def test_only_four_retained_profile_criteria_extend_prior_v10(self):
        module.validate(self.ledger)
        current_v11 = module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))
        old = module.reconstruct_prior_v10(current_v11)
        self.assertEqual(module.PRIOR_V10_LEDGER_SHA256, module.json_digest(old))
        current = [c for i in current_v11['issues'] for c in i['criteria']]
        before = [c for i in old['issues'] for c in i['criteria']]
        self.assertEqual(['84.1', '84.2', '84.3', '84.4'],
                         [c['id'] for c, p in zip(current, before) if c != p])
        self.assertEqual(441, len(current))
        self.assertEqual([(c['id'], c['text'], c['text_sha256']) for c in before],
                         [(c['id'], c['text'], c['text_sha256']) for c in current])
        self.assertEqual(405, sum(c['status'] == 'incomplete' for c in current))
        self.assertEqual(25, sum(c['evidence_relation'] == module.COMPLETE_DESIGN_RELATION for c in current))
        self.assertEqual(7, sum(c['evidence_relation'] == module.COMPLETE_IMPLEMENTATION_RELATION for c in current))
        self.assertEqual(1, sum(c['evidence_relation'] == module.COMPLETE_IMPORT_RELATION for c in current))
        self.assertEqual(3, sum(c['evidence_relation'] == module.COMPLETE_SUPPORT_RELATION for c in current))
        self.assertEqual([26, 84], [i['number'] for i in current_v11['issues']
                                   if 'accepted_implementation_outcome' in i])
        self.assertEqual([i for i in old['issues'] if i['number'] != 84],
                         [i for i in current_v11['issues'] if i['number'] != 84])
        self.assertEqual(old['evidence'], {k: v for k, v in current_v11['evidence'].items()
                                         if k not in ('pr-512', 'pr-528')})
        self.assertEqual(26, len(old['evidence']))

    def test_delivered_record_and_adr_have_exact_reviewed_bytes(self):
        import hashlib
        value = self.acceptance(self.ledger)
        for prefix in ('record', 'decision'):
            with self.subTest(artifact=prefix):
                raw = (ROOT / value[prefix + '_path']).read_bytes()
                self.assertEqual(value[prefix + '_bytes'], len(raw))
                self.assertEqual(value[prefix + '_sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(value[prefix + '_blob'], hashlib.sha1(
                    b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        self.assertNotEqual(value['record_revision'], value['implementation_revision'])
        self.assertNotEqual(value['record_revision'], value['record_source_review']['signed_stage_revision'])
        self.assertNotEqual(value['record_revision'], value['record_observation']['execution_revision'])
        reuse = value['record_observation_reuse']
        self.assertEqual(value['record_revision'], reuse['record_revision'])
        self.assertEqual(value['record_tree'], reuse['record_tree'])
        self.assertEqual(value['record_observation']['execution_revision'], reuse['observation_revision'])
        self.assertEqual(value['record_observation']['execution_tree'], reuse['observation_tree'])
        self.assertEqual(504, reuse['unchanged_leaf_count'])
        self.assertEqual(4, len(reuse['changed_noncompiled_paths']))
        partial = value['record_partial_observation']
        self.assertEqual(value['record_revision'], partial['execution_revision'])
        self.assertEqual(value['record_tree'], partial['execution_tree'])
        self.assertEqual('cancelled', partial['preview_job_conclusion'])
        self.assertEqual('partial_original_observation_not_full_gate_acceptance', partial['status'])
        self.assertEqual(16, len(value['implementation_source_pins']))
        self.assertEqual(16, len({p['path'] for p in value['implementation_source_pins']}))
        self.assertEqual(4, len(value['implemented_subtasks']))
        self.assertEqual(3, len(value['receiving_reviews']))

    def test_missing_or_extra_acceptance_fields_cannot_grant_outcomes(self):
        for field in self.acceptance(self.ledger):
            with self.subTest(field=field):
                self.rejected(lambda d, field=field: self.acceptance(d).pop(field))
        self.rejected(lambda d: self.acceptance(d).update(approved=True))
        self.rejected(lambda d: d['issues'][83].pop('accepted_implementation_outcome'))

    def test_stage_source_or_old_model_cannot_replace_actual_record_delivery(self):
        for replacement in ('550145d9a2b5dd0ef7309f0305e5b400da4066d5',
                            'ea1bcd6d6a22d70d6118df1a2063e577938b27fc', '0' * 40):
            with self.subTest(replacement=replacement):
                def substitute(d):
                    a = self.acceptance(d)
                    a['record_revision'] = replacement
                    a['record_observation']['execution_revision'] = replacement
                    d['evidence']['pr-528']['revision'] = replacement
                    d['evidence']['pr-528']['artifact_url'] = (
                        f'{module.BASE}/blob/{replacement}/' + a['record_path'])
                self.rejected(substitute)
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(signature_verified=False))
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(parents=[]))
        self.rejected(lambda d: self.acceptance(d)['record_observation'].update(
            execution_revision=self.acceptance(d)['record_revision'],
            execution_tree=self.acceptance(d)['record_tree']))

    def test_each_immutable_profile_and_source_pin_is_required(self):
        for index in range(16):
            with self.subTest(pin=index):
                self.rejected(lambda d, index=index:
                              self.acceptance(d)['implementation_source_pins'][index].update(blob='0' * 40))
        for field in self.acceptance(self.ledger)['profile_integrity']:
            with self.subTest(field=field):
                self.rejected(lambda d, field=field: self.acceptance(d)['profile_integrity'].pop(field))
        self.rejected(lambda d: self.acceptance(d).update(profile='android-placement-receiving/0.1'))
        self.rejected(lambda d: self.acceptance(d).update(profile='local-qualified-external-evidence/1'))

    def test_observation_and_original_artifact_changes_are_not_new_success(self):
        for observation in ('implementation_observation', 'record_observation'):
            with self.subTest(observation=observation):
                self.rejected(lambda d, observation=observation:
                              self.acceptance(d)[observation].update(execution_tree='0' * 40))
                self.rejected(lambda d, observation=observation:
                              self.acceptance(d)[observation].update(completed_receipt=module.BASE + '/issues/84'))
        for index in range(4):
            with self.subTest(artifact=index):
                self.rejected(lambda d, index=index:
                              self.acceptance(d)['implementation_observation']['original_artifacts'][index].update(sha256='0' * 64))
        self.rejected(lambda d: self.acceptance(d)['implementation_observation'].update(preserved_failures='All attempts passed.'))
        reuse = self.acceptance(self.ledger)['record_observation_reuse']
        for field in reuse:
            with self.subTest(reuse_field=field):
                self.rejected(lambda d, field=field: self.acceptance(d)['record_observation_reuse'].pop(field))
        self.rejected(lambda d: self.acceptance(d)['record_observation_reuse'].update(unchanged_leaf_count=508))
        self.rejected(lambda d: self.acceptance(d)['record_observation_reuse']['changed_noncompiled_paths'][0].update(path='core/src/main/kotlin/Changed.kt'))
        self.rejected(lambda d: self.acceptance(d)['record_observation_reuse'].update(unchanged_leaf_map_sha256='0' * 64))
        self.rejected(lambda d: self.acceptance(d)['record_partial_observation'].update(preview_job_conclusion='success'))
        self.rejected(lambda d: self.acceptance(d)['record_partial_observation'].update(skipped_preview_steps=[]))
        for index in range(4):
            self.rejected(lambda d, index=index: self.acceptance(d)['record_observation']['original_artifacts'][index].update(sha256='0' * 64))

    def test_receiving_roles_urls_times_and_limits_are_literal(self):
        for index in range(3):
            for field in ('role', 'url', 'created_at', 'disposition'):
                with self.subTest(receipt=index, field=field):
                    self.rejected(lambda d, index=index, field=field:
                                  self.acceptance(d)['receiving_reviews'][index].pop(field))
        self.rejected(lambda d: self.acceptance(d).update(receiving_identity_scope='Formal approval by all original assignees.'))
        self.rejected(lambda d: self.acceptance(d).update(receiving_reviews=[]))

    def test_implementation_design_import_classifications_cannot_be_transposed(self):
        for classification in ('reviewed_design_outcome', 'reviewed_import_integrity_criterion',
                               'all_issues_approved'):
            with self.subTest(classification=classification):
                self.rejected(lambda d, classification=classification:
                              self.acceptance(d).update(classification=classification))
        self.rejected(lambda d: d['issues'][83].update(
            accepted_design_outcome=copy.deepcopy(d['issues'][1]['accepted_design_outcome'])))
        self.rejected(lambda d: d['issues'][83]['criteria'][0].update(
            accepted_import_integrity_outcome=copy.deepcopy(d['issues'][20]['criteria'][1]['accepted_import_integrity_outcome'])))

    def test_implementation_receipt_does_not_authorize_another_issue(self):
        for number in (1, 21, 26, 33, 49, 58, 67, 86, 113, 122):
            with self.subTest(number=number):
                def transplant(d):
                    target = d['issues'][number - 1]
                    target['accepted_implementation_outcome'] = copy.deepcopy(self.acceptance(d))
                    target['canonical_acceptance_record'] = copy.deepcopy(d['issues'][83]['canonical_acceptance_record'])
                    target['criteria'][0].update(status='complete', completion_evidence=['pr-512', 'pr-528'],
                        remaining_engineering='', external_prerequisites=[], evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION)
                self.rejected(transplant)

    def test_output_only_prerequisites_cannot_be_replaced_or_completed(self):
        for index in range(3):
            with self.subTest(prerequisite=index):
                self.rejected(lambda d, index=index:
                              self.acceptance(d)['consumed_prerequisites'][index].update(limits='Whole prerequisite complete.'))
                self.rejected(lambda d, index=index:
                              self.acceptance(d)['consumed_prerequisites'][index].update(issue=84))
        self.rejected(lambda d: self.acceptance(d).update(consumed_prerequisites=[]))
        for number in (21, 49, 58):
            self.rejected(lambda d, number=number: d['issues'][number - 1]['criteria'][0].update(
                status='complete', completion_evidence=['pr-512', 'pr-528'], remaining_engineering=''))

    def test_reference_defaults_unknown_evidence_and_private_scope_cannot_expand(self):
        self.rejected(lambda d: self.acceptance(d)['golden_scope'].update(build_policy='All arbitrary policy overrides accepted.'))
        self.rejected(lambda d: self.acceptance(d)['golden_scope'].update(decimal_scope='Unmeasured costs are zero.'))
        self.rejected(lambda d: self.acceptance(d)['golden_scope'].update(complete_configuration_reports=18))
        self.rejected(lambda d: self.acceptance(d)['golden_scope']['configuration_gzip'].update(sha256='0' * 64))
        for index in range(len(self.acceptance(self.ledger)['profile_limits'])):
            with self.subTest(limit=index):
                self.rejected(lambda d, index=index: self.acceptance(d)['profile_limits'].pop(index))
        self.rejected(lambda d: self.acceptance(d).update(implemented_subtasks=[]))

    def test_literal_criteria_and_completion_fences_reject_reclassification(self):
        import hashlib
        for index in range(4):
            with self.subTest(criterion=index):
                def replace_text(d):
                    c = d['issues'][83]['criteria'][index]
                    c['text'] = 'Every production deployment is compatible.'
                    c['text_sha256'] = hashlib.sha256(c['text'].encode()).hexdigest()
                self.rejected(replace_text)
                for field, value in (('completion_evidence', ['pr-528']),
                                     ('status', 'incomplete'),
                                     ('remaining_engineering', 'Unperformed required work'),
                                     ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Unperformed required receiving review'}]),
                                     ('evidence_relation', module.COMPLETE_DESIGN_RELATION)):
                    self.rejected(lambda d, field=field, value=value:
                                  d['issues'][83]['criteria'][index].update({field: copy.deepcopy(value)}))
        self.rejected(lambda d: d['issues'][83]['criteria'].pop())

    def test_prior_issue_history_and_placement_survive_the_inverse(self):
        for field, value in (('partial_evidence_refs', ['pr-512']), ('historical_success', True)):
            with self.subTest(field=field):
                self.rejected(lambda d, field=field, value=value:
                              d['issues'][83]['criteria'][0].update({field: value}))
        self.rejected(lambda d: d['issues'][83].update(unrecorded_history='all prior profiles accepted'))
        self.rejected(lambda d: self.acceptance(d)['historical_acceptance_record'].update(repository=module.REPOSITORY))
        self.rejected(lambda d: d['issues'][83]['canonical_acceptance_record'].update(path='docs/delivery/android/work-packages/09.02.md'))

    def test_numeric_aliases_cannot_relabel_exact_observations(self):
        for observation in ('implementation_observation', 'record_observation'):
            with self.subTest(observation=observation):
                self.rejected(lambda d, observation=observation:
                              self.acceptance(d)[observation].update(core_cases=float(self.acceptance(d)[observation]['core_cases'])))
        self.rejected(lambda d: self.acceptance(d)['implementation_observation'].update(failures=False))
        self.rejected(lambda d: self.acceptance(d)['implementation_observation']['original_artifacts'][0].update(bytes=3934864.0))
        self.rejected(lambda d: self.acceptance(d)['golden_scope'].update(original_operator_reports=26.0))
        self.rejected(lambda d: self.acceptance(d)['consumed_prerequisites'][0].update(issue=49.0))
        self.rejected(lambda d: self.acceptance(d)['record_observation_reuse'].update(unchanged_leaf_count=504.0))
        self.rejected(lambda d: self.acceptance(d)['record_partial_observation'].update(core_cases=5150.0))

    def test_evidence_entries_require_exact_delivered_artifacts(self):
        for key in ('pr-512', 'pr-528'):
            for field, value in (('revision', '0' * 40), ('observed_state', 'open'),
                                 ('scope_limit', 'Universal compatibility and all prerequisite completion.')):
                with self.subTest(evidence=key, field=field):
                    self.rejected(lambda d, key=key, field=field:
                                  d['evidence'][key].update({field: value}))
            self.rejected(lambda d, key=key: d['evidence'].pop(key))

    def test_cli_rejects_forged_profile_under_normal_and_optimized_python(self):
        ledger = copy.deepcopy(self.ledger)
        self.acceptance(ledger)['profile'] = 'qualified-external-estimates/1'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ledger.json'
            path.write_text(json.dumps(ledger), encoding='utf-8')
            for optimized in (False, True):
                command = [sys.executable] + (['-O'] if optimized else []) + [
                    str(ROOT / 'scripts/check_delivery_ledger.py'), '--ledger', str(path)]
                result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn('reviewed implementation acceptance: 84', result.stderr)
                self.assertNotIn('PASS:', result.stdout)

    def test_validation_and_prior_inverse_do_not_mutate_input(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        previous = module.reconstruct_prior_v10(module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))))))
        previous['issues'][83]['criteria'][0]['text'] = 'Only the returned copy changes.'
        previous['evidence']['pr-430']['scope_limit'] = 'Only the returned copy changes.'
        self.assertEqual(original, self.ledger)



class CompatiblePickerAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    @staticmethod
    def issue(ledger):
        return ledger['issues'][95]

    @classmethod
    def acceptance(cls, ledger):
        return cls.issue(ledger)['accepted_implementation_outcome']

    def rejected(self, change):
        ledger = copy.deepcopy(self.ledger)
        change(ledger)
        with self.assertRaises(ValueError):
            module.validate(ledger)

    def test_only_four_picker_criteria_extend_the_exact_prior_v11(self):
        module.validate(self.ledger)
        current_v12 = module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))))
        previous = module.reconstruct_prior_v11(current_v12)
        self.assertEqual(module.PRIOR_V11_LEDGER_SHA256, module.json_digest(previous))
        current = [criterion for issue in current_v12['issues'] for criterion in issue['criteria']]
        before = [criterion for issue in previous['issues'] for criterion in issue['criteria']]
        self.assertEqual(441, len(current))
        self.assertEqual(['96.1', '96.2', '96.3', '96.4'],
                         [a['id'] for a, b in zip(current, before) if a != b])
        self.assertEqual([(c['id'], c['text'], c['text_sha256']) for c in before],
                         [(c['id'], c['text'], c['text_sha256']) for c in current])
        self.assertEqual(401, sum(c['status'] == 'incomplete' for c in current))
        self.assertEqual(40, sum(c['status'] == 'complete' for c in current))
        for relation, count in ((module.COMPLETE_DESIGN_RELATION, 25),
                                (module.COMPLETE_IMPLEMENTATION_RELATION, 11),
                                (module.COMPLETE_IMPORT_RELATION, 1),
                                (module.COMPLETE_SUPPORT_RELATION, 3)):
            self.assertEqual(count, sum(c['evidence_relation'] == relation for c in current))
        self.assertEqual([26, 84, 96], [i['number'] for i in current_v12['issues']
                                      if 'accepted_implementation_outcome' in i])
        self.assertEqual([i for i in previous['issues'] if i['number'] != 96],
                         [i for i in current_v12['issues'] if i['number'] != 96])
        self.assertEqual(28, len(previous['evidence']))
        self.assertEqual(previous['evidence'],
                         {k: v for k, v in current_v12['evidence'].items() if k != 'pr-553'})

    def test_complete_prior_inverse_is_detached_and_never_changes_the_input(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        previous = module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))
        self.assertEqual(module.PRIOR_96_ISSUE_SHA256,
                         module.json_digest(previous['issues'][95]))
        previous['issues'][95]['criteria'][0]['text'] = 'Only the returned copy changes.'
        previous['issues'][95]['criteria'][0]['partial_evidence_refs'].append('pr-553')
        previous['evidence']['pr-430']['scope_limit'] = 'Only the returned copy changes.'
        self.assertEqual(original, self.ledger)
        module.validate(self.ledger)

    def test_one_delivered_revision_can_contain_both_record_and_implementation(self):
        import hashlib
        value = self.acceptance(self.ledger)
        self.assertEqual(value['implementation_revision'], value['record_revision'])
        self.assertEqual(value['implementation_tree'], value['record_tree'])
        for prefix in ('record', 'decision'):
            with self.subTest(artifact=prefix):
                raw = (ROOT / value[prefix + '_path']).read_bytes()
                self.assertEqual(value[prefix + '_bytes'], len(raw))
                self.assertEqual(value[prefix + '_sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(value[prefix + '_blob'], hashlib.sha1(
                    b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        module.validate(self.ledger)

    def test_literal_picker_criteria_and_completion_fences_cannot_be_reclassified(self):
        import hashlib
        for index in range(4):
            with self.subTest(criterion=index):
                def replace_text(ledger):
                    criterion = self.issue(ledger)['criteria'][index]
                    criterion['text'] = 'Every production deployment is compatible.'
                    criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
                self.rejected(replace_text)
                for key, value in (('id', '84.1'), ('status', 'incomplete'),
                                   ('completion_evidence', ['pr-527']),
                                   ('remaining_engineering', 'Required receiving work is unfinished.'),
                                   ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Unperformed receiving review.'}]),
                                   ('evidence_relation', module.COMPLETE_DESIGN_RELATION)):
                    self.rejected(lambda d, key=key, value=value, index=index:
                                  self.issue(d)['criteria'][index].update({key: copy.deepcopy(value)}))
        self.rejected(lambda d: self.issue(d)['criteria'].reverse())
        self.rejected(lambda d: self.issue(d)['criteria'].pop())

    def test_only_the_reviewed_record_coordinate_moves_and_history_remains_exact(self):
        value = self.acceptance(self.ledger)
        self.assertEqual('Pick-Your-AI/pickyour.ai',
                         value['historical_acceptance_record']['repository'])
        self.assertEqual('docs/delivery/android/work-packages/07.02.md', value['record_path'])
        self.assertEqual('docs/decisions/0013-android-96-acceptance-record.md', value['decision_path'])
        for key, value in (('repository', 'Pick-Your-AI/pick-your-ai-website'),
                           ('path', 'docs/delivery/android/work-packages/07.03.md')):
            self.rejected(lambda d, key=key, value=value:
                          self.issue(d)['canonical_acceptance_record'].update({key: value}))
        self.rejected(lambda d: self.acceptance(d)['historical_acceptance_record'].update(
            repository=module.REPOSITORY))
        self.rejected(lambda d: self.acceptance(d).update(
            decision_path='docs/decisions/0012-android-37-acceptance-record.md'))

    def test_missing_or_extra_role_and_profile_authority_never_becomes_acceptance(self):
        for key in ('receiving_reviews', 'implemented_subtasks', 'criterion_observations',
                    'consumed_prerequisites', 'profile_limits'):
            self.assertTrue(self.acceptance(self.ledger)[key], key)
            self.rejected(lambda d, key=key: self.acceptance(d).update({key: []}))
        for index in range(len(self.acceptance(self.ledger)['receiving_reviews'])):
            self.rejected(lambda d, index=index: self.acceptance(d)['receiving_reviews'].pop(index))
        for key, value in (('profile', 'unrestricted-deployment-acceptance/1'),
                           ('all_prerequisites_complete', True),
                           ('formal_distinct_account_approval', True)):
            self.rejected(lambda d, key=key, value=value:
                          self.acceptance(d).update({key: value}))
        self.rejected(lambda d: self.issue(d).pop('accepted_implementation_outcome'))

    def test_picker_acceptance_cannot_be_transplanted_to_a_prerequisite_or_release_issue(self):
        for number in (1, 21, 37, 82, 84, 89, 129):
            with self.subTest(number=number):
                def transplant(ledger):
                    target = ledger['issues'][number - 1]
                    target['accepted_implementation_outcome'] = copy.deepcopy(self.acceptance(ledger))
                    target['canonical_acceptance_record'] = copy.deepcopy(
                        self.issue(ledger)['canonical_acceptance_record'])
                    target['criteria'][0].update(status='complete', completion_evidence=['pr-553'],
                        remaining_engineering='', external_prerequisites=[],
                        evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION)
                self.rejected(transplant)

    def test_consumed_outputs_do_not_close_other_upstream_criteria(self):
        for number in (21, 37, 82):
            target = module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))))['issues'][number - 1]
            indexes = [n for n, c in enumerate(target['criteria']) if c['status'] == 'incomplete']
            self.assertTrue(indexes, number)
            for index in indexes:
                self.rejected(lambda d, number=number, index=index:
                              d['issues'][number - 1]['criteria'][index].update(
                                  status='complete', completion_evidence=['pr-553'],
                                  remaining_engineering='', external_prerequisites=[],
                                  evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION))
        self.rejected(lambda d: self.acceptance(d).update(consumed_prerequisites='All upstream work is complete.'))

    def test_prior_partial_context_and_unknown_fields_survive_the_inverse_and_reject(self):
        for change in (
            lambda d: self.issue(d)['criteria'][0].update(partial_evidence_refs=['pr-553']),
            lambda d: self.issue(d)['criteria'][0].update(unreviewed_observation='Production deployment passed.'),
            lambda d: self.issue(d).update(all_children_complete=True),
            lambda d: d.update(unreviewed_default_execution=True),
            lambda d: d.update(observed_on='Unobserved future execution'),
            lambda d: d['issues'][81].update(title='Unreviewed upstream completion'),
        ):
            self.rejected(change)
        previous = module.reconstruct_prior_v11(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))))
        for ref in previous['evidence']:
            self.rejected(lambda d, ref=ref: d['evidence'][ref].update(
                scope_limit='This historical observation now accepts all deployments.'))

    def test_numeric_aliases_do_not_change_the_type_of_fixed_receipt_observations(self):
        def numeric_paths(value, path=()):
            if isinstance(value, dict):
                for key, child in value.items():
                    yield from numeric_paths(child, path + (key,))
            elif isinstance(value, list):
                for index, child in enumerate(value):
                    yield from numeric_paths(child, path + (index,))
            elif type(value) in (int, bool):
                yield path, value
        observations = 0
        for root_key, value in (('acceptance', self.acceptance(self.ledger)),
                                ('evidence', self.ledger['evidence']['pr-553'])):
            paths = list(numeric_paths(value))
            observations += len(paths)
            for path, old in paths:
                with self.subTest(receipt=root_key, field=path):
                    def alias(ledger):
                        node = self.acceptance(ledger) if root_key == 'acceptance' else ledger['evidence']['pr-553']
                        for key in path[:-1]:
                            node = node[key]
                        node[path[-1]] = int(old) if type(old) is bool else float(old)
                    self.rejected(alias)
        self.assertGreater(observations, 0)

    def test_implementation_evidence_requires_the_actual_merged_record(self):
        for key, value in (('revision', '0' * 40), ('observed_state', 'open'),
                           ('scope_limit', 'Universal compatibility and all prerequisite completion.')):
            self.rejected(lambda d, key=key, value=value: d['evidence']['pr-553'].update({key: value}))
        self.rejected(lambda d: d['evidence'].pop('pr-553'))
        self.rejected(lambda d: d['evidence']['pr-553'].update(
            artifact_url='https://github.com/Pick-Your-AI/pick-your-ai-android/pull/527'))

    def test_normal_and_optimized_cli_reject_a_forged_picker_profile(self):
        ledger = copy.deepcopy(self.ledger)
        self.acceptance(ledger)['profile'] = 'qualified-external-estimates/1'
        with tempfile.TemporaryDirectory(prefix='ledger-picker-forgery-') as directory:
            path = Path(directory) / 'ledger.json'
            path.write_text(json.dumps(ledger), encoding='utf-8')
            for optimized in (False, True):
                command = [sys.executable] + (['-O'] if optimized else []) + [
                    str(ROOT / 'scripts/check_delivery_ledger.py'), '--ledger', str(path)]
                result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn('reviewed implementation acceptance: 96', result.stderr)
                self.assertNotIn('PASS:', result.stdout)
                self.assertNotIn('Traceback', result.stderr)


    def test_receiving_and_main_checkouts_cannot_be_aliased(self):
        accepted = self.acceptance(self.ledger)
        prepared = accepted['prepared_observation']
        main = accepted['post_main_observation']
        self.assertNotEqual(prepared['execution_revision'], main['execution_revision'])
        self.assertEqual(prepared['execution_tree'], main['execution_tree'])
        self.assertEqual(main['execution_revision'], accepted['implementation_revision'])
        self.assertEqual(main['execution_revision'], main['source_revision'])
        self.assertEqual('pull_request', prepared['event'])
        self.assertEqual('push', main['event'])
        self.assertEqual(2, len(prepared['execution_parents']))
        self.assertEqual(1, len(main['execution_parents']))
        for target, other in (('prepared_observation', 'post_main_observation'),
                              ('post_main_observation', 'prepared_observation')):
            self.rejected(lambda d, target=target, other=other:
                          self.acceptance(d).update({target: copy.deepcopy(self.acceptance(d)[other])}))
            for field, value in (('event', 'workflow_dispatch'), ('attempt', 2),
                                 ('signature_verified', False), ('execution_tree', '0' * 40),
                                 ('execution_parents', []), ('execution_revision', accepted['implementation_source']['revision'])):
                self.rejected(lambda d, target=target, field=field, value=value:
                              self.acceptance(d)[target].update({field: value}))

    def test_unsigned_transport_and_commented_review_do_not_become_formal_authority(self):
        accepted = self.acceptance(self.ledger)
        self.assertFalse(accepted['implementation_source']['signature_verified'])
        self.assertTrue(accepted['signed_delivery']['signature_verified'])
        self.assertEqual('COMMENTED', accepted['source_review']['state'])
        for change in (
            lambda d: self.acceptance(d)['implementation_source'].update(signature_verified=True),
            lambda d: self.acceptance(d)['signed_delivery'].update(signature_verified=False),
            lambda d: self.acceptance(d)['signed_delivery'].update(pr_state='open'),
            lambda d: self.acceptance(d)['signed_delivery'].update(expected_source=self.acceptance(d)['implementation_revision']),
            lambda d: self.acceptance(d)['signed_delivery'].update(parents=self.acceptance(d)['prepared_observation']['execution_parents']),
            lambda d: self.acceptance(d)['source_review'].update(state='APPROVED'),
            lambda d: self.acceptance(d)['source_review'].update(source_revision=self.acceptance(d)['implementation_revision']),
            lambda d: self.acceptance(d)['receiving_reviews'][0].update(role='unobserved_historical_assignee_approval'),
        ):
            self.rejected(change)

    def test_original_job_artifact_and_log_failure_boundaries_are_preserved(self):
        for observation in ('prepared_observation', 'post_main_observation'):
            actual = self.acceptance(self.ledger)[observation]
            self.assertEqual([7, 28, 60], sorted(j['successful_steps'] for j in actual['jobs']))
            self.assertEqual(95, actual['successful_steps'])
            self.assertEqual(4, len(actual['original_artifacts']))
            self.assertEqual(3, len(actual['original_logs']))
            for index in range(len(actual['jobs'])):
                self.rejected(lambda d, observation=observation, index=index:
                              self.acceptance(d)[observation]['jobs'][index].update(conclusion='cancelled'))
                self.rejected(lambda d, observation=observation, index=index:
                              self.acceptance(d)[observation]['jobs'][index].update(successful_steps=0))
            for collection in ('original_artifacts', 'original_logs'):
                for index in range(len(actual[collection])):
                    self.rejected(lambda d, observation=observation, collection=collection, index=index:
                                  self.acceptance(d)[observation][collection].pop(index))
                    self.rejected(lambda d, observation=observation, collection=collection, index=index:
                                  self.acceptance(d)[observation][collection][index].update(sha256='0' * 64))
            for field, value in (('failures', 1), ('errors', 1), ('skips', 1), ('duplicate_cases', 1),
                                 ('core_cases', 5852), ('simulated_android_cases', 511),
                                 ('native_attendance_programs', 40), ('lint_warnings', 0),
                                 ('all_original_steps_concluded_success', False)):
                self.rejected(lambda d, observation=observation, field=field, value=value:
                              self.acceptance(d)[observation].update({field: value}))

    def test_source_named_controls_cannot_be_removed_or_replaced_by_counts(self):
        observations = self.acceptance(self.ledger)['criterion_observations']
        names = {c['identity'] for o in observations for c in o['actual_named_controls']}
        self.assertEqual(15, len(names))
        for index, observation in enumerate(observations):
            self.assertTrue(observation['actual_named_controls'])
            self.rejected(lambda d, index=index:
                          self.acceptance(d)['criterion_observations'][index].update(actual_named_controls=[]))
            for case, control in enumerate(observation['actual_named_controls']):
                self.assertEqual('PASS', control['prepared_outcome'])
                self.assertEqual('PASS', control['post_main_outcome'])
                for field, value in (('original_xml_identity', ['OtherClass', 'unobservedCase']),
                                     ('post_main_outcome', 'SKIPPED'), ('prepared_outcome', 'FAIL'),
                                     ('source_blob', '0' * 40), ('source_sha256', '0' * 64)):
                    self.rejected(lambda d, index=index, case=case, field=field, value=value:
                                  self.acceptance(d)['criterion_observations'][index]['actual_named_controls'][case].update({field: value}))
        self.rejected(lambda d: self.acceptance(d).update(criterion_observations={'passing_cases': 6365}))
        self.rejected(lambda d: self.acceptance(d)['same_tree_execution_continuity'].update(removed_cases=1))

    def test_raw_scanner_findings_keep_their_distinct_original_ancestry(self):
        accepted = self.acceptance(self.ledger)
        prepared = accepted['prepared_observation']['scanner']
        main = accepted['post_main_observation']['scanner']
        self.assertEqual((248, 2), (prepared['reachable_commits'], len(prepared['raw_findings'])))
        self.assertEqual((241, 1), (main['reachable_commits'], len(main['raw_findings'])))
        self.assertNotEqual(prepared['raw_findings'], main['raw_findings'])
        for observation in ('prepared_observation', 'post_main_observation'):
            actual = accepted[observation]['scanner']
            self.assertEqual(10, actual['raw_exit_code'])
            self.assertEqual(0, actual['unresolved_count'])
            self.assertEqual(len(actual['raw_findings']), len(actual['reviewed_findings']))
            for field, value in (('raw_exit_code', 0), ('raw_findings', []), ('reviewed_findings', []),
                                 ('reachable_commits', 0), ('control_methods', 27),
                                 ('limits', []), ('unresolved_count', 1)):
                self.rejected(lambda d, observation=observation, field=field, value=value:
                              self.acceptance(d)[observation]['scanner'].update({field: value}))
        self.rejected(lambda d: self.acceptance(d)['post_main_observation'].update(
            scanner=copy.deepcopy(self.acceptance(d)['prepared_observation']['scanner'])))

    def test_causal_failure_and_pre_test_stop_are_not_rewritten_as_success(self):
        history = self.acceptance(self.ledger)['preserved_failures_and_history']
        self.assertEqual((1, 2, 3), (history[0]['prior_failures'], history[0]['control_passes'], history[0]['corrected_passes']))
        self.assertTrue(history[2]['pre_test_stop_preserved'])
        self.assertEqual(11, history[2]['subsequent_passes'])
        for change in (
            lambda d: self.acceptance(d).update(preserved_failures_and_history=[]),
            lambda d: self.acceptance(d)['preserved_failures_and_history'][0].update(prior_failures=0),
            lambda d: self.acceptance(d)['preserved_failures_and_history'][0].update(no_external_donor_attribution=False),
            lambda d: self.acceptance(d)['preserved_failures_and_history'][1].update(source_revision=self.acceptance(d)['implementation_revision']),
            lambda d: self.acceptance(d)['preserved_failures_and_history'][2].update(pre_test_stop_preserved=False),
            lambda d: self.acceptance(d)['preserved_failures_and_history'][3].update(record_sha256='0' * 64),
            lambda d: self.acceptance(d).update(profile_limits=['All platforms, data and deployments are approved.']),
        ):
            self.rejected(change)


class MetadataReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    def rejected(self, change):
        ledger = copy.deepcopy(self.ledger)
        change(ledger)
        with self.assertRaises(ValueError):
            module.validate(ledger)

    def test_complete_prior_v12_bytes_and_all_criterion_states_are_exact(self):
        module.validate(self.ledger)
        current_v13 = module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))
        import hashlib
        previous = module.reconstruct_prior_v12(current_v13)
        raw = (json.dumps(previous, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
        self.assertEqual(609028, len(raw))
        self.assertEqual('cce0a733e94fc6e38bf4c8f4956a7e0059d42e9324c23e81fbece8f67ddf1551',
                         hashlib.sha256(raw).hexdigest())
        self.assertEqual('0b213fb07461765844b38ef9901674df4f397dd59924312f2a78ba3f5251e3c4',
                         module.json_digest(previous))
        current = [c for issue in current_v13['issues'] for c in issue['criteria']]
        prior = [c for issue in previous['issues'] for c in issue['criteria']]
        self.assertEqual(441, len(current))
        self.assertEqual([(c['id'], c['text'], c['text_sha256'], c['status']) for c in prior],
                         [(c['id'], c['text'], c['text_sha256'], c['status']) for c in current])
        self.assertEqual(40, sum(c['status'] == 'complete' for c in current))
        self.assertEqual(401, sum(c['status'] == 'incomplete' for c in current))
        self.assertEqual(['57.1', '57.2', '57.3'], [c['id'] for c, old in zip(current, prior) if c != old])
        self.assertEqual([48, 57, 59], [i['number'] for i, old in zip(current_v13['issues'], previous['issues']) if i != old])
        self.assertEqual(126, sum(i == old for i, old in zip(current_v13['issues'], previous['issues'])))
        self.assertEqual(438, sum(c == old for c, old in zip(current, prior)))
        self.assertEqual(29, len(previous['evidence']))
        self.assertEqual(previous['evidence'], {k: v for k, v in current_v13['evidence'].items()
                                              if k not in ('pr-515', 'pr-502')})
        for field in ('observed_on', 'repository', 'implementation_baseline', 'source', 'purpose'):
            self.assertEqual(previous[field], current_v13[field])

    def test_exact_field_inverse_keeps_only_the_scoped_metadata_changes(self):
        module.validate(self.ledger)
        current_v13 = module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))
        previous = module.reconstruct_prior_v12(current_v13)
        expected = copy.deepcopy(previous)
        expected['schema_version'] = 13
        for number in (48, 59):
            expected['issues'][number - 1]['canonical_acceptance_record']['repository'] = module.REPOSITORY
        refs = [['pr-136', 'pr-515', 'pr-502'], ['pr-136', 'pr-502'], ['pr-136', 'pr-502']]
        for criterion, required_refs in zip(expected['issues'][56]['criteria'], refs):
            criterion['partial_evidence_refs'] = required_refs
            criterion['remaining_engineering'] = module.METADATA_57_REMAINING
        expected['evidence'].update(copy.deepcopy(module.METADATA_57_EVIDENCE))
        self.assertEqual(expected, current_v13)
        for number in (48, 59):
            self.assertEqual(previous['issues'][number - 1]['criteria'],
                             current_v13['issues'][number - 1]['criteria'])
        self.assertEqual(previous['issues'][56]['canonical_acceptance_record'],
                         current_v13['issues'][56]['canonical_acceptance_record'])

    def test_effective_placement_decisions_are_exact_and_do_not_complete_criteria(self):
        import hashlib
        # The placement amendment is historical; later #48 acceptance is separate.
        historical_v14 = module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))
        for number, path, revision in (
                (48, 'docs/delivery/android/work-packages/02.01.md', '710d337fed2004689993c0b8bc01f12de780b04c'),
                (59, 'docs/delivery/android/work-packages/02.05.md', '146413ff168583f4c2b0df87ab41d36e57f8ea85')):
            with self.subTest(number=number):
                issue = historical_v14['issues'][number - 1]
                self.assertEqual({'repository': module.REPOSITORY, 'path': path},
                                 issue['canonical_acceptance_record'])
                placement = module.METADATA_PLACEMENTS[number]
                self.assertEqual(revision, placement['effective_revision'])
                raw = (ROOT / placement['decision_path']).read_bytes()
                self.assertEqual(placement['decision_blob'], hashlib.sha1(
                    b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
                self.assertTrue((ROOT / path).is_file())
                self.assertTrue(all(c['status'] == 'incomplete' and c['completion_evidence'] == []
                                    for c in issue['criteria']))
                for key in ('accepted_design_outcome', 'accepted_implementation_outcome', 'accepted_support_outcome'):
                    self.assertNotIn(key, issue)

    def test_wrong_issue_repository_path_or_extra_coordinate_cannot_borrow_placement(self):
        for number in (48, 59):
            for change in ({'repository': 'Pick-Your-AI/pickyour.ai'},
                           {'path': 'docs/delivery/android/work-packages/02.02.md'},
                           {'path': 'docs/delivery/android/work-packages/02.05.md' if number == 48
                            else 'docs/delivery/android/work-packages/02.01.md'},
                           {'approved': True}, {'decision_revision': '0' * 40}):
                with self.subTest(number=number, change=change):
                    self.rejected(lambda d, number=number, change=change:
                                  d['issues'][number - 1]['canonical_acceptance_record'].update(change))
        for number in (1, 57, 60):
            self.rejected(lambda d, number=number:
                          d['issues'][number - 1]['canonical_acceptance_record'].update(repository=module.REPOSITORY))

    def test_help_reference_stays_only_on_57_1_and_original_context_is_required(self):
        expected = [['pr-136', 'pr-515', 'pr-502'], ['pr-136', 'pr-502'], ['pr-136', 'pr-502']]
        self.assertEqual(expected, [c['partial_evidence_refs'] for c in self.ledger['issues'][56]['criteria']])
        for index, refs in enumerate(expected):
            variants = [refs[1:], refs[:-1], list(reversed(refs)), refs + ['pr-502'], ['pr-502']]
            if index:
                variants.append(['pr-136', 'pr-515', 'pr-502'])
            for changed in variants:
                with self.subTest(index=index, refs=changed):
                    self.rejected(lambda d, index=index, changed=changed:
                                  d['issues'][56]['criteria'][index].update(partial_evidence_refs=changed))

    def test_each_new_evidence_field_and_its_shape_are_fixed(self):
        for key in ('pr-515', 'pr-502'):
            for field in self.ledger['evidence'][key]:
                with self.subTest(key=key, field=field):
                    self.rejected(lambda d, key=key, field=field:
                                  d['evidence'][key].update({field: 'unreviewed_source_or_completion'}))
            self.rejected(lambda d, key=key: d['evidence'][key].update(approved=True))
            self.rejected(lambda d, key=key: d['evidence'].pop(key))
        self.rejected(lambda d: d['evidence']['pr-515'].update(
            revision=d['evidence']['pr-502']['revision'], artifact_url=d['evidence']['pr-502']['artifact_url']))

    def test_no_other_criterion_can_gain_the_new_scoped_references(self):
        for number in (1, 48, 59, 60):
            for key in ('pr-515', 'pr-502'):
                with self.subTest(number=number, key=key):
                    self.rejected(lambda d, number=number, key=key:
                                  d['issues'][number - 1]['criteria'][0]['partial_evidence_refs'].append(key))

    def test_remaining_work_cannot_be_erased_or_replaced_by_approval(self):
        for index in range(3):
            for changed in ('', module.PRIOR_57_REMAINING, 'All real variants are approved.', True):
                with self.subTest(index=index, changed=changed):
                    self.rejected(lambda d, index=index, changed=changed:
                                  d['issues'][56]['criteria'][index].update(remaining_engineering=changed))

    def test_affected_criteria_cannot_gain_completion_or_change_attribution(self):
        for number in (48, 57, 59):
            for index in range(len(self.ledger['issues'][number - 1]['criteria'])):
                for field, value in (
                        ('status', 'incomplete' if number == 48 else 'complete'),
                        ('completion_evidence', ['pr-502']),
                        ('evidence_relation', module.COMPLETE_DESIGN_RELATION if number == 48
                         else module.COMPLETE_IMPLEMENTATION_RELATION),
                        ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'A new receiver'}]),
                        ('accepted_import_integrity_outcome', {'approved': True})):
                    with self.subTest(number=number, index=index, field=field):
                        self.rejected(lambda d, number=number, index=index, field=field, value=value:
                                      d['issues'][number - 1]['criteria'][index].update({field: copy.deepcopy(value)}))
            for field in ('accepted_design_outcome', 'accepted_implementation_outcome', 'accepted_support_outcome'):
                self.rejected(lambda d, number=number, field=field:
                              d['issues'][number - 1].update({field: {'approved': True}}))

    def test_unknown_fields_survive_the_inverse_and_are_rejected(self):
        for number in (48, 57, 59):
            for location in ('issue', 'criterion'):
                with self.subTest(number=number, location=location):
                    changed = copy.deepcopy(self.ledger)
                    issue = changed['issues'][number - 1]
                    target = issue if location == 'issue' else issue['criteria'][0]
                    target['unreviewed_history'] = {'approved': True}
                    previous = module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(changed))))
                    old_issue = previous['issues'][number - 1]
                    old_target = old_issue if location == 'issue' else old_issue['criteria'][0]
                    self.assertEqual({'approved': True}, old_target['unreviewed_history'])
                    with self.assertRaises(ValueError):
                        module.validate(changed)
        changed = copy.deepcopy(self.ledger)
        changed['issues'][47]['canonical_acceptance_record']['approved'] = True
        self.assertTrue(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(changed))))['issues'][47]['canonical_acceptance_record']['approved'])
        with self.assertRaises(ValueError):
            module.validate(changed)

    def test_recomputed_text_hash_and_matching_synthetic_source_do_not_change_history(self):
        import hashlib
        for index in range(3):
            changed = copy.deepcopy(self.ledger)
            criterion = changed['issues'][56]['criteria'][index]
            criterion['text'] += ' All deployed environments are accepted.'
            criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode()).hexdigest()
            source = [{'number': issue['number'], 'title': issue['title'],
                       'body': '## Acceptance criteria\n' + '\n'.join('- [ ] ' + c['text'] for c in issue['criteria']) +
                       '\n## Delivery record\n`' + issue['canonical_acceptance_record']['path'] + '`'}
                      for issue in changed['issues']]
            with self.assertRaisesRegex(ValueError, 'unreviewed change to another issue record'):
                module.validate(changed, source)

    def test_original_evidence_and_metadata_are_not_replaced_by_the_projection(self):
        changed = copy.deepcopy(self.ledger)
        changed['evidence']['pr-136']['scope_limit'] += ' All variants complete.'
        self.assertEqual(changed['evidence']['pr-136'], module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(changed))))['evidence']['pr-136'])
        with self.assertRaises(ValueError):
            module.validate(changed)
        for field in ('observed_on', 'implementation_baseline', 'source'):
            self.rejected(lambda d, field=field: d.update({field: 'unreviewed_observation'}))
        self.rejected(lambda d: d['evidence'].update(unreviewed_approval=copy.deepcopy(d['evidence']['pr-502'])))

    def test_57_identity_set_is_validated_before_history_projection(self):
        for change in ('missing', 'extra', 'reordered', 'alias'):
            changed = copy.deepcopy(self.ledger)
            criteria = changed['issues'][56]['criteria']
            if change == 'missing':
                criteria.pop()
            elif change == 'extra':
                criteria.append(copy.deepcopy(criteria[-1]))
            elif change == 'reordered':
                criteria.reverse()
            else:
                criteria[0]['id'] = 57.1
            with self.assertRaisesRegex(ValueError, 'metadata criterion identity/order mismatch: 57'):
                module.validate(changed)

    def test_validation_and_prior_copies_preserve_the_input(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        first = module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))))
        second = module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))))
        first['issues'][47]['canonical_acceptance_record']['path'] = 'only_the_copy'
        first['issues'][56]['criteria'][0]['partial_evidence_refs'].append('only_the_copy')
        first['evidence']['pr-136']['scope_limit'] = 'only_the_copy'
        self.assertEqual(original, self.ledger)
        self.assertEqual(module.reconstruct_prior_v12(module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(original)))), second)

    def test_cli_rejects_metadata_scope_and_placement_under_normal_and_optimized_python(self):
        cases = []
        wrong_scope = copy.deepcopy(self.ledger)
        wrong_scope['issues'][56]['criteria'][1]['partial_evidence_refs'].append('pr-515')
        cases.append((wrong_scope, 'unreviewed metadata evidence references: 57.2'))
        wrong_path = copy.deepcopy(self.ledger)
        wrong_path['issues'][58]['canonical_acceptance_record']['path'] = 'docs/delivery/android/work-packages/02.01.md'
        cases.append((wrong_path, 'unreviewed acceptance coordinates'))
        false_completion = copy.deepcopy(self.ledger)
        false_completion['issues'][56]['criteria'][2]['status'] = 'complete'
        cases.append((false_completion, 'unreviewed completion claim: 57.3'))
        with tempfile.TemporaryDirectory(prefix='ledger-metadata-') as directory:
            path = Path(directory) / 'ledger.json'
            for ledger, message in cases:
                path.write_text(json.dumps(ledger), encoding='utf-8')
                for optimized in (False, True):
                    with self.subTest(message=message, optimized=optimized):
                        command = [sys.executable] + (['-O'] if optimized else []) + [
                            str(ROOT / 'scripts/check_delivery_ledger.py'), '--ledger', str(path)]
                        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn(message, result.stderr)
                        self.assertNotIn('PASS:', result.stdout)
                        self.assertNotIn('Traceback', result.stderr)

    def test_readme_addition_preserves_the_entire_v12_narrative(self):
        import hashlib
        current = (ROOT / 'docs/delivery/README.md').read_text(encoding='utf-8')
        original = '# Criterion evidence ledger\n\n' + current[current.index('## Version 12:'):]
        raw = original.encode('utf-8')
        self.assertEqual(66650, len(raw))
        self.assertEqual('892a141b1467f6018b6019ddef1a23222391f52862a6eba31d833f609dddab12', hashlib.sha256(raw).hexdigest())
        self.assertIn('No criterion status changes in this metadata amendment.', current)


class PlacementOutcomeTest(unittest.TestCase):
    def setUp(self):
        self.ledger = json.loads((ROOT / 'docs/delivery/criterion-evidence.json').read_text())

    @staticmethod
    def acceptance(ledger):
        return ledger['issues'][36]['accepted_implementation_outcome']

    def rejected(self, mutation):
        changed = copy.deepcopy(self.ledger)
        mutation(changed)
        with self.assertRaises(ValueError):
            module.validate(changed)

    def test_three_placement_criteria_only_extend_the_entire_previous_ledger(self):
        import hashlib
        module.validate(self.ledger)
        # Compare the #37 amendment at its original accepted v14 boundary.
        current_v14 = module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))
        previous = module.reconstruct_prior_v13(current_v14)
        raw = (json.dumps(previous, ensure_ascii=False, indent=2) + '\n').encode()
        self.assertEqual(module.PRIOR_V13_FILE_BYTES, len(raw))
        self.assertEqual(module.PRIOR_V13_FILE_SHA256, hashlib.sha256(raw).hexdigest())
        self.assertEqual(module.PRIOR_V13_LEDGER_SHA256, module.json_digest(previous))
        before = [c for i in previous['issues'] for c in i['criteria']]
        current = [c for i in current_v14['issues'] for c in i['criteria']]
        self.assertEqual(['37.1', '37.2', '37.3'], [c['id'] for p, c in zip(before, current) if p != c])
        self.assertEqual(441, len(current))
        self.assertEqual([(c['id'], c['text'], c['text_sha256']) for c in before],
                         [(c['id'], c['text'], c['text_sha256']) for c in current])
        self.assertEqual(43, sum(c['status'] == 'complete' for c in current))
        self.assertEqual(398, sum(c['status'] == 'incomplete' for c in current))
        self.assertEqual(14, sum(c['evidence_relation'] == module.COMPLETE_IMPLEMENTATION_RELATION for c in current))
        self.assertEqual([i for i in previous['issues'] if i['number'] != 37],
                         [i for i in current_v14['issues'] if i['number'] != 37])
        self.assertEqual(previous['evidence'], {k: v for k, v in current_v14['evidence'].items()
                                             if k not in ('pr-517', 'pr-535', 'pr-598')})
        self.assertEqual(31, len(previous['evidence']))

    def test_record_adr_and_all_selected_source_bytes_match_actual_delivered_inputs(self):
        import hashlib
        value = self.acceptance(self.ledger)
        pins = value['implementation_source_pins'] + [
            {k: value[prefix + '_' + k] for k in ('path', 'bytes', 'sha256', 'blob')}
            for prefix in ('record', 'decision')]
        self.assertEqual(29, len(pins))
        self.assertEqual(29, len({p['path'] for p in pins}))
        for p in pins:
            with self.subTest(path=p['path']):
                raw = (ROOT / p['path']).read_bytes()
                self.assertEqual(p['bytes'], len(raw))
                self.assertEqual(p['sha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual(p['blob'], hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())

    def test_original_neutral_bundle_remains_structural_and_byte_exact(self):
        import gzip
        import hashlib
        scope = self.acceptance(self.ledger)['neutral_fixture_scope']
        raw = (ROOT / 'core/src/test/resources/conformance/cross-client-v1.json.gz').read_bytes()
        uncompressed = gzip.decompress(raw)
        self.assertEqual((scope['gzip_bytes'], scope['gzip_sha256']), (len(raw), hashlib.sha256(raw).hexdigest()))
        self.assertEqual((scope['raw_bytes'], scope['raw_sha256']), (len(uncompressed), hashlib.sha256(uncompressed).hexdigest()))
        for field in ('relation', 'existing_bundle_cases', 'existing_placement_cases', 'placement_dispositions'):
            self.rejected(lambda d, field=field: self.acceptance(d)['neutral_fixture_scope'].pop(field))

    def test_profile_versions_counts_and_original_source_cannot_be_converted_by_receipt(self):
        replacements = {'physical_answer_version': 'cd1-0.1', 'context_answer_version': 'cd1-0.2',
                        'context_count_type': 'Double', 'physical_count_type': 'JSON_number',
                        'context_answer_sha256': '0' * 64, 'version_boundary': 'Relabel any original answer.',
                        'qualification_boundary': 'Matching hashes grant eligibility.',
                        'failure_boundary': 'Any scope mismatch rejects; missing original references are unknown.'}
        for field, value in replacements.items():
            with self.subTest(field=field):
                self.rejected(lambda d, field=field, value=value: self.acceptance(d)['profile_integrity'].update({field: value}))
        self.rejected(lambda d: self.acceptance(d).update(profile='default-placement/1'))

    def test_all_six_interfaces_seventeen_groups_and_literal_tasks_are_required(self):
        a = self.acceptance(self.ledger)
        self.assertEqual(6, len(a['selected_interfaces']))
        self.assertEqual(['D1', 'D2', 'D3', 'B1', 'B2', 'B3', 'M1', 'M2', 'M3', 'M4', 'W1',
                          'C1', 'C2', 'C3', 'C4', 'C5', 'G1'], [g['id'] for g in a['vector_groups']])
        self.assertEqual(4, len(a['implemented_subtasks']))
        for field in ('selected_interfaces', 'vector_groups', 'implemented_subtasks'):
            for index in range(len(a[field])):
                self.rejected(lambda d, field=field, index=index: self.acceptance(d)[field].pop(index))
        self.rejected(lambda d: self.acceptance(d)['profile_integrity'].update(
            cycle_boundary='The two-adapter cycle is a valid frozen-v1 base_model_ref configuration.'))

    def test_actual_delegated_roles_cannot_be_substituted_with_inferred_human_approval(self):
        for index in range(3):
            for field in ('role', 'url', 'created_at', 'body_sha256', 'body_bytes', 'source_revision', 'disposition', 'read_and_execution_scope'):
                with self.subTest(role=index, field=field):
                    self.rejected(lambda d, index=index, field=field: self.acceptance(d)['receiving_reviews'][index].pop(field))
        self.rejected(lambda d: self.acceptance(d).update(receiving_identity_scope='All absent historical assignees formally approved.'))
        self.rejected(lambda d: self.acceptance(d).update(receiving_reviews=[]))

    def test_only_available_prerequisite_outputs_are_received(self):
        for index in range(2):
            for field in ('repository', 'issue', 'output', 'limits'):
                self.rejected(lambda d, index=index, field=field: self.acceptance(d)['consumed_prerequisites'][index].pop(field))
        for index in (0, 2):
            self.rejected(lambda d, index=index: d['issues'][20]['criteria'][index].update(
                status='complete', completion_evidence=['pr-535'], remaining_engineering='', external_prerequisites=[]))

    def test_signed_main_and_post_main_execution_cannot_be_replaced_by_a_stage(self):
        for revision in ('79e003475a617c40b0bd46ae4cf0a3c09b43b412',
                         'a3ee0fb4d877f7d66bd746485de81007adc16998', '0' * 40):
            def substitute(d, revision=revision):
                a = self.acceptance(d)
                a['record_revision'] = revision
                a['main_observation']['execution_revision'] = revision
                d['evidence']['pr-535']['revision'] = revision
            self.rejected(substitute)
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(signature_verified=False))
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(parents=[]))
        self.rejected(lambda d: self.acceptance(d)['main_observation'].update(event='pull_request'))

    def test_each_original_job_artifact_and_target_identity_binding_is_fixed(self):
        a = self.acceptance(self.ledger)['main_observation']
        self.assertEqual([112, 7, 158, 643], [x['unique_cases'] for x in a['target_identities']])
        self.assertEqual(395, sum(x['members'] for x in a['original_artifacts']))
        for index in range(4):
            for field in ('id', 'bytes', 'sha256', 'members'):
                self.rejected(lambda d, index=index, field=field:
                              self.acceptance(d)['main_observation']['original_artifacts'][index].pop(field))
            self.rejected(lambda d, index=index: self.acceptance(d)['main_observation']['target_identities'][index].update(ordered_identity_json_sha256='0' * 64))
        for index in range(3):
            self.rejected(lambda d, index=index: self.acceptance(d)['main_observation']['jobs'][index].update(conclusion='cancelled'))
        self.rejected(lambda d: self.acceptance(d)['main_observation']['conformance'].update(sha256='0' * 64))
        self.rejected(lambda d: self.acceptance(d)['main_observation']['original_apk'].update(sha256='0' * 64))

    def test_actual_raw_scanner_findings_and_internal_rows_cannot_be_relabelled(self):
        self.rejected(lambda d: self.acceptance(d)['main_observation']['scanner'].update(raw_exit_code=0))
        self.rejected(lambda d: self.acceptance(d)['main_observation']['scanner'].update(raw_findings=0))
        self.rejected(lambda d: self.acceptance(d)['main_observation']['scanner'].update(unresolved_findings=3))
        self.rejected(lambda d: self.acceptance(d)['main_observation']['contains_regression'].update(additional_junit_methods=154))
        self.rejected(lambda d: self.acceptance(d)['main_observation'].update(core_cases=6146))

    def test_prior_executions_and_preserved_failures_cannot_be_promoted(self):
        for index in range(4):
            self.rejected(lambda d, index=index: self.acceptance(d)['historical_observations'][index].update(revision=self.acceptance(d)['record_revision']))
        self.rejected(lambda d: self.acceptance(d)['historical_observations'][2].update(core_cases=5268))
        self.rejected(lambda d: self.acceptance(d)['main_observation'].update(preserved_failures='All attempts passed.'))
        self.rejected(lambda d: self.acceptance(d)['main_observation'].update(attribution='All source reviewers ran the hosted jobs.'))

    def test_literal_criterion_text_status_and_completion_fences_are_preserved(self):
        import hashlib
        for index in range(3):
            def change_text(d, index=index):
                c = d['issues'][36]['criteria'][index]
                c['text'] = 'All physical hardware and provider deployments fit.'
                c['text_sha256'] = hashlib.sha256(c['text'].encode()).hexdigest()
            self.rejected(change_text)
            for field, value in (('status', 'incomplete'), ('completion_evidence', ['pr-535']),
                                 ('remaining_engineering', 'Unperformed required work'),
                                 ('external_prerequisites', [{'kind': 'contract_acceptance', 'requirement': 'Unreceived selected profile'}]),
                                 ('evidence_relation', module.COMPLETE_DESIGN_RELATION)):
                self.rejected(lambda d, index=index, field=field, value=value:
                              d['issues'][36]['criteria'][index].update({field: copy.deepcopy(value)}))

    def test_placement_receipt_cannot_authorize_another_issue_or_classification(self):
        for number in (1, 21, 26, 33, 38, 59, 84, 96, 113, 122):
            def transplant(d, number=number):
                target = d['issues'][number - 1]
                target['accepted_implementation_outcome'] = copy.deepcopy(self.acceptance(d))
                target['canonical_acceptance_record'] = copy.deepcopy(d['issues'][36]['canonical_acceptance_record'])
                target['criteria'][0].update(status='complete', completion_evidence=['pr-517', 'pr-535'],
                                            remaining_engineering='', external_prerequisites=[])
            self.rejected(transplant)
        for field in ('accepted_design_outcome', 'accepted_support_outcome'):
            self.rejected(lambda d, field=field: d['issues'][36].update({field: copy.deepcopy(self.acceptance(d))}))
        self.rejected(lambda d: self.acceptance(d).update(classification='reviewed_design_outcome'))

    def test_coordinate_context_and_partial_history_cannot_be_changed_by_inverse(self):
        self.rejected(lambda d: d['issues'][36]['canonical_acceptance_record'].update(repository='Pick-Your-AI/pickyour.ai'))
        self.rejected(lambda d: d['issues'][36]['canonical_acceptance_record'].update(path='docs/delivery/android/work-packages/03.05.md'))
        self.rejected(lambda d: self.acceptance(d)['historical_acceptance_record'].update(repository=module.REPOSITORY))
        self.rejected(lambda d: d['issues'][36]['criteria'][0].update(partial_evidence_refs=['pr-535']))
        self.rejected(lambda d: d['issues'][36].update(unrecorded_history='All previous native profiles passed.'))

    def test_missing_extra_or_floating_receipt_fields_do_not_grant_acceptance(self):
        for field in self.acceptance(self.ledger):
            self.rejected(lambda d, field=field: self.acceptance(d).pop(field))
        self.rejected(lambda d: self.acceptance(d).update(approved=True))
        self.rejected(lambda d: d['issues'][36].pop('accepted_implementation_outcome'))
        for field in ('core_cases', 'preview_cases', 'failures', 'literal_attendance_programs'):
            self.rejected(lambda d, field=field: self.acceptance(d)['main_observation'].update(
                {field: float(self.acceptance(d)['main_observation'][field])}))
        self.rejected(lambda d: self.acceptance(d)['main_observation'].update(errors=False))
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(signature_verified=1))

    def test_merged_receipts_and_scope_limits_are_required(self):
        for key in ('pr-517', 'pr-535', 'pr-598'):
            self.rejected(lambda d, key=key: d['evidence'].pop(key))
            for field, value in (('observed_state', 'open'), ('revision', '0' * 40),
                                 ('scope_limit', 'Default evaluator and installed provider readiness accepted.')):
                self.rejected(lambda d, key=key, field=field, value=value: d['evidence'][key].update({field: value}))
        for index in range(len(self.acceptance(self.ledger)['profile_limits'])):
            self.rejected(lambda d, index=index: self.acceptance(d)['profile_limits'].pop(index))

    def test_cli_rejects_forged_placement_under_normal_and_optimized_python(self):
        ledger = copy.deepcopy(self.ledger)
        self.acceptance(ledger)['profile'] = 'default-production-placement/1'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ledger.json'
            path.write_text(json.dumps(ledger), encoding='utf-8')
            for optimized in (False, True):
                command = [sys.executable, '-B'] + (['-O'] if optimized else []) + [
                    str(ROOT / 'scripts/check_delivery_ledger.py'), '--ledger', str(path)]
                result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn('reviewed implementation acceptance: 37', result.stderr)
                self.assertNotIn('PASS:', result.stdout)

    def test_changed_basis_correction_retains_the_real_failure_and_exact_two_source_changes(self):
        a = self.acceptance(self.ledger)
        correction = a['changed_basis_correction']
        self.assertEqual('SKIPPED', correction['original_red']['observed'])
        self.assertEqual('UNKNOWN', correction['original_red']['expected'])
        self.assertEqual(25, correction['prior_source_pins_unchanged'])
        self.assertEqual(2, len(correction['changed_source_pins']))
        follow = correction['post_main_follow_through']
        self.assertEqual((627, 625, 2), (follow['preserved_parent_leaves_total'],
                         follow['preserved_other_parent_leaves'], follow['preserved_correction_leaves']))
        self.assertEqual(follow['preserved_parent_leaves_total'],
                         follow['preserved_other_parent_leaves'] + follow['preserved_correction_leaves'])
        self.assertEqual(627, correction['delivery']['other_accepted_leaves_exact'])
        self.assertTrue(follow['source_map_clarification_receipt'].endswith('6274260790'))
        self.rejected(lambda d: self.acceptance(d)['changed_basis_correction']['post_main_follow_through'].update(
            preserved_other_parent_leaves=627))
        self.rejected(lambda d: self.acceptance(d)['changed_basis_correction']['post_main_follow_through'].update(
            all_627_other_parent_leaves_preserved=True))
        self.assertEqual(a['original_main_observation']['execution_revision'],
                         correction['original_record_main_revision'])
        for pin in correction['changed_source_pins']:
            self.assertNotEqual(pin['original']['blob'], pin['corrected']['blob'])
            self.assertIn(pin['corrected'], a['implementation_source_pins'])
        for field in ('original_red', 'focused_green', 'changed_source_pins', 'original_finding_review',
                      'correction_review', 'permanent_regression', 'delivery', 'post_main_follow_through'):
            self.rejected(lambda d, field=field: self.acceptance(d)['changed_basis_correction'].pop(field))
        self.rejected(lambda d: self.acceptance(d)['changed_basis_correction']['original_red'].update(
            observed='PASS', failures=0, test_exit_code=0))
        self.rejected(lambda d: self.acceptance(d)['changed_basis_correction'].update(
            policy_effect='A new default-placement profile and physical provider acceptance.'))

    def test_old_or_cancelled_execution_cannot_stand_in_for_corrected_main(self):
        a = self.acceptance(self.ledger)
        self.assertNotEqual(a['original_main_observation']['execution_revision'],
                            a['main_observation']['execution_revision'])
        self.assertEqual(a['implementation_source_revision'], a['main_observation']['execution_revision'])
        self.assertEqual(5993, a['original_main_observation']['core_cases'])
        self.assertEqual(6186, a['main_observation']['core_cases'])
        for revision in (a['original_main_observation']['execution_revision'],
                         a['changed_basis_correction']['delivery']['revision'],
                         a['changed_basis_correction']['receiving_execution']['revision']):
            self.rejected(lambda d, revision=revision: self.acceptance(d)['main_observation'].update(
                execution_revision=revision))
        self.rejected(lambda d: self.acceptance(d).update(
            main_observation=copy.deepcopy(self.acceptance(d)['original_main_observation'])))
        for index in range(2):
            def restore_old_source(d, index=index):
                value = self.acceptance(d)
                item = value['changed_basis_correction']['changed_source_pins'][index]
                target = next(p for p in value['implementation_source_pins'] if p['path'] == item['original']['path'])
                target.update(item['original'])
            self.rejected(restore_old_source)
        self.rejected(lambda d: self.acceptance(d)['changed_basis_correction']['post_main_follow_through'].update(
            cancelled_build_is_not_completed_validation=False))

    def test_correction_receipt_is_a_required_merged_input_without_widening_the_issue(self):
        self.assertEqual(['pr-517', 'pr-535', 'pr-598'], self.acceptance(self.ledger)['evidence_refs'])
        self.assertEqual('merged', self.ledger['evidence']['pr-598']['observed_state'])
        self.rejected(lambda d: d['evidence'].pop('pr-598'))
        self.rejected(lambda d: d['evidence']['pr-598'].update(observed_state='open'))
        self.rejected(lambda d: d['evidence']['pr-598'].update(revision=self.acceptance(d)['record_revision']))
        self.rejected(lambda d: self.acceptance(d)['evidence_refs'].remove('pr-598'))
        self.rejected(lambda d: self.acceptance(d)['changed_basis_correction']['delivery'].update(signature_verified=1))
        self.rejected(lambda d: self.acceptance(d)['changed_basis_correction']['post_main_follow_through'].update(
            exact_correction_blobs_preserved=1))

    def test_validation_and_predecessor_reconstruction_never_mutate_input(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        prior = module.reconstruct_prior_v13(module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger)))
        prior['issues'][36]['criteria'][0]['text'] = 'Only the returned copy changes.'
        prior['evidence']['pr-528']['scope_limit'] = 'Only the returned copy changes.'
        self.assertEqual(original, self.ledger)


class ApplicationFoundationAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    @staticmethod
    def issue(ledger):
        return ledger['issues'][47]

    @classmethod
    def acceptance(cls, ledger):
        return cls.issue(ledger)['accepted_implementation_outcome']

    def rejected(self, mutation):
        changed = copy.deepcopy(self.ledger)
        mutation(changed)
        # Scalar-type changes must remain real adverse inputs, including False/0.
        self.assertNotEqual(module.json_digest(self.ledger), module.json_digest(changed))
        with self.assertRaises(ValueError):
            module.validate(changed)

    def test_complete_prior_v14_bytes_and_all_original_observations_are_recovered(self):
        import hashlib
        module.validate(self.ledger)
        previous = module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))
        raw = (json.dumps(previous, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
        self.assertEqual(659947, len(raw))
        self.assertEqual('a0082a409fb38a5e0537b71e33501aa57538784bef6859e34afd306aa528f8a8',
                         hashlib.sha256(raw).hexdigest())
        self.assertEqual('69fcf968d4c453676322d2c77960ce4c2cf7b454f5cd80df6ce1dd76b5e8d51c',
                         module.json_digest(previous))
        self.assertEqual(module.PRIOR_V14_FILE_BYTES, len(raw))
        self.assertEqual(module.PRIOR_V14_FILE_SHA256, hashlib.sha256(raw).hexdigest())
        self.assertEqual(module.PRIOR_V14_LEDGER_SHA256, module.json_digest(previous))
        self.assertEqual(14, previous['schema_version'])
        self.assertEqual(34, len(previous['evidence']))
        for field in ('observed_on', 'repository', 'implementation_baseline', 'source'):
            self.assertEqual(previous[field], self.ledger[field])

    def test_only_three_foundation_criteria_extend_the_existing_acceptance_ledger(self):
        module.validate(self.ledger)
        # Keep the accepted foundation-only comparison at its exact v15 layer.
        current_v15 = module.reconstruct_prior_v15(self.ledger)
        previous = module.reconstruct_prior_v14(current_v15)
        current = [c for i in current_v15['issues'] for c in i['criteria']]
        prior = [c for i in previous['issues'] for c in i['criteria']]
        self.assertEqual(441, len(current))
        self.assertEqual([(c['id'], c['text'], c['text_sha256']) for c in prior],
                         [(c['id'], c['text'], c['text_sha256']) for c in current])
        self.assertEqual(['48.1', '48.2', '48.3'],
                         [c['id'] for old, c in zip(prior, current) if old != c])
        self.assertEqual(46, sum(c['status'] == 'complete' for c in current))
        self.assertEqual(395, sum(c['status'] == 'incomplete' for c in current))
        for relation, count in ((module.COMPLETE_DESIGN_RELATION, 25),
                                (module.COMPLETE_IMPLEMENTATION_RELATION, 17),
                                (module.COMPLETE_IMPORT_RELATION, 1),
                                (module.COMPLETE_SUPPORT_RELATION, 3)):
            self.assertEqual(count, sum(c['evidence_relation'] == relation for c in current))
        self.assertEqual([i for i in previous['issues'] if i['number'] != 48],
                         [i for i in current_v15['issues'] if i['number'] != 48])
        self.assertEqual(previous['evidence'],
                         {k: v for k, v in current_v15['evidence'].items() if k != 'pr-652'})
        self.assertEqual(set(previous['evidence']) | {'pr-652'}, set(current_v15['evidence']))
        for criterion, old in zip(self.issue(current_v15)['criteria'], self.issue(previous)['criteria']):
            expected = copy.deepcopy(old)
            expected.update(status='complete', completion_evidence=['pr-652'], remaining_engineering='',
                            evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION)
            self.assertEqual(expected, criterion)

    def test_existing_android_coordinate_and_partial_history_remain_unchanged(self):
        previous = module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))
        issue = self.issue(self.ledger)
        self.assertEqual({'repository': module.REPOSITORY,
                          'path': 'docs/delivery/android/work-packages/02.01.md'},
                         issue['canonical_acceptance_record'])
        self.assertEqual(self.issue(previous)['canonical_acceptance_record'],
                         issue['canonical_acceptance_record'])
        self.assertEqual(['pr-133', 'pr-136'], issue['criteria'][0]['partial_evidence_refs'])
        self.assertEqual([c['partial_evidence_refs'] for c in self.issue(previous)['criteria']],
                         [c['partial_evidence_refs'] for c in issue['criteria']])
        for mutation in (
                lambda d: self.issue(d)['canonical_acceptance_record'].update(
                    repository='Pick-Your-AI/pick-your-ai-website'),
                lambda d: self.issue(d)['canonical_acceptance_record'].update(
                    path='docs/delivery/android/work-packages/02.05.md'),
                lambda d: self.issue(d)['canonical_acceptance_record'].update(approved=True),
                lambda d: self.issue(d)['criteria'][0]['partial_evidence_refs'].clear(),
                lambda d: self.issue(d).update(unreviewed_previous_launch=True)):
            self.rejected(mutation)

    def test_current_acceptance_and_merged_evidence_are_required_in_full(self):
        self.rejected(lambda d: self.issue(d).pop('accepted_implementation_outcome'))
        self.rejected(lambda d: self.acceptance(d).update(approved=True))
        self.rejected(lambda d: d['evidence'].pop('pr-652'))
        for field in self.acceptance(self.ledger):
            with self.subTest(acceptance_field=field):
                self.rejected(lambda d, field=field: self.acceptance(d).pop(field))
        for field in self.ledger['evidence']['pr-652']:
            with self.subTest(evidence_field=field):
                self.rejected(lambda d, field=field:
                              d['evidence']['pr-652'].update({field: 'unreviewed_source_or_completion'}))
        self.rejected(lambda d: d['evidence']['pr-652'].update(observed_state='open'))
        self.rejected(lambda d: d['evidence']['pr-652'].update(approved=True))

    def test_all_literal_criterion_hashes_and_order_are_fixed_before_reconstruction(self):
        import hashlib
        expected_hashes = [
            '48394baee1202faa552240baa51f5a3a86724392455773034f4f03629a1f75f5',
            'ea26c0e64f5f5ea9f161699998d60ac1769e4a4e96fdd1d59e1f9e7f5382d9fe',
            'ae75fb12f1eda553f8eb6322e007c249bf6568d91c370939d48e64b5c774caa7']
        self.assertEqual(expected_hashes, module.IMPLEMENTATION_48_CRITERIA)
        self.assertEqual(expected_hashes, [c['text_sha256'] for c in self.issue(self.ledger)['criteria']])
        for index in range(3):
            changed = copy.deepcopy(self.ledger)
            criterion = self.issue(changed)['criteria'][index]
            criterion['text'] += ' Every physical device and release variant is accepted.'
            criterion['text_sha256'] = hashlib.sha256(criterion['text'].encode('utf-8')).hexdigest()
            source = [{'number': issue['number'], 'title': issue['title'],
                       'body': '## Acceptance criteria\n' +
                       '\n'.join('- [x] ' + c['text'] for c in issue['criteria']) +
                       '\n## Delivery record\n' + chr(96) + issue['canonical_acceptance_record']['path'] + chr(96)}
                      for issue in changed['issues']]
            with self.subTest(ordinal=index + 1):
                with self.assertRaises(ValueError):
                    module.validate(changed, source)
        for mutation in (
                lambda d: self.issue(d)['criteria'].pop(),
                lambda d: self.issue(d)['criteria'].append(copy.deepcopy(self.issue(d)['criteria'][-1])),
                lambda d: self.issue(d)['criteria'].reverse(),
                lambda d: self.issue(d)['criteria'][0].update(id=48.1),
                lambda d: self.issue(d)['criteria'][0].update(id='48.2')):
            self.rejected(mutation)

    def test_each_foundation_completion_rejects_borrowed_proof_and_unfinished_requirements(self):
        for index in range(3):
            for field, value in (
                    ('status', 'incomplete'), ('completion_evidence', []),
                    ('completion_evidence', ['pr-136']), ('completion_evidence', ['pr-652', 'pr-136']),
                    ('remaining_engineering', 'A production signing and device gate is unreviewed.'),
                    ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Pending receiver'}]),
                    ('evidence_relation', module.COMPLETE_DESIGN_RELATION),
                    ('evidence_relation', module.COMPLETE_IMPORT_RELATION),
                    ('evidence_relation', module.COMPLETE_SUPPORT_RELATION)):
                with self.subTest(ordinal=index + 1, field=field, value=value):
                    self.rejected(lambda d, index=index, field=field, value=value:
                                  self.issue(d)['criteria'][index].update({field: copy.deepcopy(value)}))
        for field in ('accepted_design_outcome', 'accepted_support_outcome'):
            self.rejected(lambda d, field=field: self.issue(d).update({field: {'approved': True}}))

    def test_foundation_receipt_cannot_complete_other_issues_or_reclassify_existing_acceptances(self):
        for number in (1, 2, 21, 26, 37, 47, 49, 57, 59, 84, 96, 129):
            with self.subTest(number=number):
                def transplant(d, number=number):
                    issue = d['issues'][number - 1]
                    issue['accepted_implementation_outcome'] = copy.deepcopy(self.acceptance(d))
                    issue['criteria'][0].update(status='complete', completion_evidence=['pr-652'],
                        remaining_engineering='', external_prerequisites=[],
                        evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION)
                self.rejected(transplant)
        for number in (2, 26, 33, 37, 84, 96):
            with self.subTest(other_receipt=number):
                field = ('accepted_design_outcome' if number == 2 else
                         'accepted_support_outcome' if number == 33 else 'accepted_implementation_outcome')
                self.rejected(lambda d, number=number, field=field:
                              self.issue(d).update(accepted_implementation_outcome=copy.deepcopy(
                                  d['issues'][number - 1][field])))

    def test_unknown_history_fields_and_earlier_evidence_survive_the_inverse_then_fail(self):
        for location in ('ledger', 'issue', 'criterion', 'other-issue', 'old-evidence'):
            with self.subTest(location=location):
                changed = copy.deepcopy(self.ledger)
                targets = {
                    'ledger': changed,
                    'issue': self.issue(changed),
                    'criterion': self.issue(changed)['criteria'][0],
                    'other-issue': changed['issues'][58],
                    'old-evidence': changed['evidence']['pr-136']}
                targets[location]['unreviewed_history'] = {'accepted': True}
                previous = module.reconstruct_prior_v14(module.reconstruct_prior_v15(changed))
                prior_targets = {
                    'ledger': previous,
                    'issue': self.issue(previous),
                    'criterion': self.issue(previous)['criteria'][0],
                    'other-issue': previous['issues'][58],
                    'old-evidence': previous['evidence']['pr-136']}
                self.assertEqual({'accepted': True}, prior_targets[location]['unreviewed_history'])
                with self.assertRaises(ValueError):
                    module.validate(changed)
        for field in ('observed_on', 'implementation_baseline', 'source', 'purpose'):
            self.rejected(lambda d, field=field: d.update({field: 'All observations and products now accepted.'}))
        self.rejected(lambda d: d['evidence']['pr-136'].update(
            scope_limit='The original scaffold also establishes installed production success.'))

    def test_schema_and_issue_identity_aliases_do_not_enter_the_new_layer(self):
        for version in (15, 17, True, '16', 16.0):
            with self.subTest(version=version):
                self.rejected(lambda d, version=version: d.update(schema_version=version))
        for number in (48.0, True, '48'):
            with self.subTest(number=number):
                self.rejected(lambda d, number=number: self.issue(d).update(number=number))
        self.rejected(lambda d: d['issues'].append(copy.deepcopy(self.issue(d))))
        self.rejected(lambda d: d['issues'].reverse())

    def test_current_readme_prepend_keeps_every_byte_of_the_previous_v14_narrative(self):
        import hashlib
        current = (ROOT / 'docs/delivery/README.md').read_text(encoding='utf-8')
        original = '# Criterion evidence ledger\n\n' + current[current.index('## Version 14:'):]
        raw = original.encode('utf-8')
        self.assertEqual(82484, len(raw))
        self.assertEqual('029bf9f1489a088b06d43f389cff73d5de323ef3b321bb78797da9bc87436754',
                         hashlib.sha256(raw).hexdigest())

    def test_validation_and_v14_reconstruction_never_mutate_the_caller(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        first = module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))
        second = module.reconstruct_prior_v14(module.reconstruct_prior_v15(self.ledger))
        first['issues'][47]['criteria'][0]['partial_evidence_refs'].append('only_the_copy')
        first['issues'][58]['title'] = 'Only the returned copy changes.'
        first['evidence']['pr-136']['scope_limit'] = 'Only the returned copy changes.'
        self.assertEqual(original, self.ledger)
        self.assertEqual(module.reconstruct_prior_v14(module.reconstruct_prior_v15(original)), second)
        module.validate(self.ledger)

    def test_actual_signed_record_is_distinct_from_transport_preparation_and_native_execution(self):
        import hashlib
        value = self.acceptance(self.ledger)
        self.assertEqual('020d7fa2192cf3414e258c95f25554473fd3b14d', value['record_revision'])
        self.assertEqual('f2168e31dd21b2e135bfa6091817c5462f6221df', value['record_tree'])
        self.assertEqual('5a7793fa1daeee575e29be97c5a04ee252ee9871', value['record_blob'])
        self.assertEqual('11ff6e76c3a0dc8c2bd65e741346ce235208ecc1',
                         value['record_delivery']['source_revision'])
        self.assertNotEqual(value['record_revision'], value['record_delivery']['source_revision'])
        self.assertNotEqual(value['record_revision'], value['later_main_original']['revision'])
        self.assertEqual(['pr-652'], value['evidence_refs'])
        self.assertEqual(module.IMPLEMENTATION_48_EVIDENCE, self.ledger['evidence']['pr-652'])
        raw = (ROOT / value['record_path']).read_bytes()
        self.assertEqual(25962, len(raw))
        self.assertEqual('6f64954356c138abc7b7feb6a50347f9a36a5ef3c4724cb578e63166953eae8e',
                         hashlib.sha256(raw).hexdigest())
        self.assertEqual(value['record_blob'], hashlib.sha1(
            b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        for field in ('record_revision', 'record_tree', 'record_blob'):
            self.rejected(lambda d, field=field: self.acceptance(d).update({field: '0' * 40}))
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(
            source_revision=self.acceptance(d)['record_revision']))
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(
            source_signature='valid_signed_main'))
        self.rejected(lambda d: d['evidence']['pr-652'].update(
            revision=self.acceptance(d)['record_delivery']['source_revision']))

    def test_all_fifteen_source_inputs_and_existing_placement_have_their_exact_native_blobs(self):
        import hashlib
        value = self.acceptance(self.ledger)
        inputs = value['current_foundation_inputs']
        pins = inputs['paths']
        self.assertEqual(15, len(pins))
        self.assertEqual({
            'settings.gradle.kts', 'build.gradle.kts', 'core/build.gradle.kts',
            'preview/build.gradle.kts', 'gradle.properties',
            'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
            'gradle/wrapper/wrapper.sha256', 'gradlew', 'gradlew.bat',
            'core/gradle.lockfile', 'preview/gradle.lockfile', 'gradle/verification-metadata.xml',
            'preview/src/main/AndroidManifest.xml', 'requirements-contracts.txt'},
            {pin['path'] for pin in pins})
        self.assertEqual(value['later_main_original']['revision'], inputs['executed_revision'])
        self.assertEqual(value['record_revision'], inputs['preserved_through_record_revision'])
        for pin in pins:
            with self.subTest(path=pin['path']):
                raw = (ROOT / pin['path']).read_bytes()
                self.assertEqual(pin['blob'], hashlib.sha1(
                    b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
                self.assertEqual('blob', pin['type'])
                self.assertEqual('100755' if pin['path'] == 'gradlew' else '100644', pin['mode'])
        placement = value['placement']
        raw = (ROOT / placement['decision_path']).read_bytes()
        self.assertEqual('904da4ee38ec00ae57eb5ece2fbc4dd4a222eb97', hashlib.sha1(
            b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        for mutation in (
                lambda d: self.acceptance(d)['current_foundation_inputs']['paths'].pop(),
                lambda d: self.acceptance(d)['current_foundation_inputs']['paths'].reverse(),
                lambda d: self.acceptance(d)['current_foundation_inputs']['paths'][0].update(blob='0' * 40),
                lambda d: self.acceptance(d)['current_foundation_inputs']['paths'][8].update(mode='100644'),
                lambda d: self.acceptance(d)['current_foundation_inputs'].update(
                    executed_revision=self.acceptance(d)['record_revision']),
                lambda d: self.acceptance(d)['placement'].update(effective_revision='0' * 40)):
            self.rejected(mutation)

    def test_selected_preview_identity_cannot_expand_to_production_or_unapproved_platform_work(self):
        value = self.acceptance(self.ledger)
        self.assertEqual('android_selected_toolchain_core_and_opt_in_preview_foundation', value['scope'])
        self.assertEqual([':core', ':preview'], value['selected_client']['modules'])
        self.assertEqual('ai.pickyour.android.preview', value['selected_client']['package_identity'])
        self.assertIs(True, value['selected_client']['preview_opt_in'])
        self.assertEqual('disabled', value['selected_client']['release_variant'])
        self.assertEqual(4, len(value['implemented_subtasks']))
        self.assertEqual(2, len(value['validation_methods']))
        for field, changed in (
                ('modules', [':core', ':app']), ('package_identity', 'ai.pickyour.android'),
                ('preview_opt_in', False), ('ordinary_debug', 'production_signed_package'),
                ('installed_copy', 'production_signing_key'), ('release_variant', 'enabled'),
                ('scope_limit', 'All physical devices and distribution channels are accepted.')):
            with self.subTest(field=field):
                self.rejected(lambda d, field=field, changed=changed:
                              self.acceptance(d)['selected_client'].update({field: copy.deepcopy(changed)}))
        self.rejected(lambda d: self.acceptance(d).update(scope='production_release_and_physical_devices'))
        self.rejected(lambda d: self.acceptance(d)['implemented_subtasks'].pop())
        self.rejected(lambda d: self.acceptance(d)['validation_methods'].pop())
        self.rejected(lambda d: self.acceptance(d)['scope_limits'].clear())

    def test_real_receiving_and_source_review_identities_cannot_be_replaced_by_inferred_approval(self):
        value = self.acceptance(self.ledger)
        self.assertEqual(1, len(value['receiving_reviews']))
        self.assertEqual(2, len(value['source_reviews']))
        self.assertEqual('https://github.com/Pick-Your-AI/pick-your-ai-android/issues/48#issuecomment-6280278860',
                         value['receiving_reviews'][0]['url'])
        self.assertTrue(all(review['state'] == 'COMMENTED' for review in value['source_reviews']))
        for field in ('role', 'url', 'created_at', 'body_sha256', 'inspected_revision', 'disposition', 'scope'):
            with self.subTest(receiving_field=field):
                self.rejected(lambda d, field=field: self.acceptance(d)['receiving_reviews'][0].update(
                    {field: 'inferred_assignee_approval'}))
        for index in range(2):
            for field in ('commit_id', 'state', 'url', 'body_sha256', 'submitted_at'):
                with self.subTest(review=index, field=field):
                    self.rejected(lambda d, index=index, field=field:
                                  self.acceptance(d)['source_reviews'][index].update(
                                      {field: 'unreviewed_formal_approval'}))
        self.rejected(lambda d: self.acceptance(d)['receiving_reviews'].clear())
        self.rejected(lambda d: self.acceptance(d)['source_reviews'].reverse())
        self.rejected(lambda d: self.acceptance(d).update(
            receiving_identity_scope='A separately authenticated human release owner approved every platform.'))

    def test_failed_delivery_and_cancelled_retry_never_become_successful_installed_evidence(self):
        delivery = self.acceptance(self.ledger)['foundation_delivery']
        self.assertEqual('0f5fea42c5cd149472269da2e47559a24689ba71', delivery['revision'])
        self.assertEqual('failure', delivery['failed_main_original']['conclusion'])
        self.assertEqual(0, delivery['failed_main_original']['installed_cases_executed'])
        self.assertIs(False, delivery['failed_main_original']['final_receipt_validated'])
        self.assertEqual('cancelled', delivery['cancelled_retry']['conclusion'])
        self.assertIs(False, delivery['cancelled_retry']['new_installed_job'])
        for field, changed in (('conclusion', 'success'), ('installed_cases_executed', 4),
                               ('final_receipt_validated', True), ('boundary', 'all installed cases passed')):
            with self.subTest(failed_field=field):
                self.rejected(lambda d, field=field, changed=changed:
                              self.acceptance(d)['foundation_delivery']['failed_main_original'].update(
                                  {field: changed}))
        self.rejected(lambda d: self.acceptance(d)['foundation_delivery'].pop('failed_main_original'))
        self.rejected(lambda d: self.acceptance(d)['foundation_delivery'].pop('cancelled_retry'))
        self.rejected(lambda d: self.acceptance(d)['foundation_delivery']['cancelled_retry'].update(
            conclusion='success'))
        self.rejected(lambda d: self.acceptance(d)['foundation_delivery']['cancelled_retry'].update(
            new_installed_job=True))
        self.rejected(lambda d: self.acceptance(d)['prepared_original'].update(
            older_failed_images='all_original_images_passed'))

    def test_later_hosted_success_keeps_its_own_source_and_narrow_personal_audit_scope(self):
        later = self.acceptance(self.ledger)['later_main_original']
        self.assertEqual('a82e4066184850a4b4152378682055531bfeae5a', later['revision'])
        self.assertEqual(['0f5fea42c5cd149472269da2e47559a24689ba71'], later['parents'])
        self.assertEqual(39305746890, later['run_id'])
        self.assertEqual(118250826685, later['installed_job_id'])
        self.assertEqual(4, later['installed_cases'])
        self.assertEqual(16, later['all_declared_installed_steps_succeeded'])
        self.assertEqual('success', later['installed_conclusion'])
        self.assertIs(True, later['final_source_bound_receipt_and_acl_validation'])
        for field in ('personal_archive_received', 'personal_archive_audit',
                      'personal_junit_audit', 'personal_pixel_audit'):
            self.assertIs(False, later[field])
            self.rejected(lambda d, field=field: self.acceptance(d)['later_main_original'].update({field: True}))
        for field, changed in (
                ('revision', self.acceptance(self.ledger)['record_revision']),
                ('tree', self.acceptance(self.ledger)['foundation_delivery']['tree']),
                ('installed_cases', 5), ('installed_job_id', 1),
                ('all_declared_installed_steps_succeeded', 15),
                ('final_source_bound_receipt_and_acl_validation', False),
                ('decoded_console_sha256', '0' * 64),
                ('no_result_relabeling', 'This also passes the failed primary and cancelled retry.')):
            with self.subTest(field=field):
                self.rejected(lambda d, field=field, changed=changed:
                              self.acceptance(d)['later_main_original'].update({field: changed}))
        self.rejected(lambda d: self.acceptance(d)['later_main_original']['uploaded_artifact'].update(
            sha256='0' * 64))
        self.rejected(lambda d: self.acceptance(d)['prepared_original'].update(
            revision=self.acceptance(d)['later_main_original']['revision']))

    def test_website_source_and_earlier_execution_keep_their_independent_scope(self):
        website = self.acceptance(self.ledger)['website_independence']
        self.assertEqual('6cea6db8a7b66ce7da1f94814c04010ed1a66753', website['inspected_revision'])
        self.assertEqual('dc66a9e269917ab7241c7d08fd0e7f2a2f039872',
                         website['original_execution']['revision'])
        self.assertIs(False, website['requires_android_sdk_or_gradle'])
        self.assertEqual('Node 22/npm', website['toolchain'])
        self.rejected(lambda d: self.acceptance(d)['website_independence'].update(
            requires_android_sdk_or_gradle=True))
        self.rejected(lambda d: self.acceptance(d)['website_independence']['original_execution'].update(
            revision=self.acceptance(d)['website_independence']['inspected_revision']))
        self.rejected(lambda d: self.acceptance(d)['website_independence'].update(
            inspected_revision=self.acceptance(d)['record_revision']))
        self.rejected(lambda d: self.acceptance(d)['website_independence']['original_execution'].update(
            run_id=self.acceptance(d)['later_main_original']['run_id']))
        self.rejected(lambda d: self.acceptance(d)['website_independence'].update(
            source_observation='All current website browser, native and release checks were rerun.'))

    def test_exact_numeric_and_boolean_receipt_types_cannot_be_aliased(self):
        for location, field in (
                ('record_delivery', 'pr'), ('record_delivery', 'full_delivered_leaf_count'),
                ('record_delivery', 'unchanged_other_main_entries'), ('prepared_original', 'installed_cases'),
                ('later_main_original', 'run_id'), ('later_main_original', 'installed_job_id'),
                ('later_main_original', 'installed_cases')):
            with self.subTest(location=location, field=field):
                original = self.acceptance(self.ledger)[location][field]
                self.rejected(lambda d, location=location, field=field, original=original:
                              self.acceptance(d)[location].update({field: float(original)}))
        for location in ('record_delivery', 'prepared_original', 'foundation_delivery', 'later_main_original'):
            self.rejected(lambda d, location=location:
                          self.acceptance(d)[location].update(signature_verified=1))
        for field in ('personal_archive_received', 'personal_archive_audit',
                      'personal_junit_audit', 'personal_pixel_audit'):
            self.rejected(lambda d, field=field:
                          self.acceptance(d)['later_main_original'].update({field: 0}))
        self.rejected(lambda d: self.acceptance(d).update(record_bytes=float(self.acceptance(d)['record_bytes'])))
        self.rejected(lambda d: self.acceptance(d)['foundation_delivery']['failed_main_original'].update(
            installed_cases_executed=False))

    def test_cli_rejects_forged_foundation_scope_and_false_retry_success_without_asserts(self):
        cases = []
        forged_scope = copy.deepcopy(self.ledger)
        self.acceptance(forged_scope)['selected_client']['package_identity'] = 'ai.pickyour.android'
        cases.append(forged_scope)
        false_retry = copy.deepcopy(self.ledger)
        self.acceptance(false_retry)['foundation_delivery']['cancelled_retry']['conclusion'] = 'success'
        cases.append(false_retry)
        with tempfile.TemporaryDirectory(prefix='ledger-foundation48-') as directory:
            path = Path(directory) / 'ledger.json'
            for changed in cases:
                path.write_text(json.dumps(changed), encoding='utf-8')
                for optimized in (False, True):
                    with self.subTest(optimized=optimized, kind=self.acceptance(changed)['selected_client']['package_identity']):
                        command = [sys.executable] + (['-O'] if optimized else []) + [
                            str(ROOT / 'scripts/check_delivery_ledger.py'), '--ledger', str(path)]
                        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                        self.assertIn('Delivery ledger check failed:', result.stderr)
                        self.assertNotIn('PASS:', result.stdout)
                        self.assertNotIn('Traceback', result.stderr)


class SelectionHandoffAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.ledger = module.read_json(ROOT / 'docs/delivery/criterion-evidence.json')

    @staticmethod
    def issue(ledger):
        return ledger['issues'][97]

    @classmethod
    def acceptance(cls, ledger):
        return cls.issue(ledger)['accepted_implementation_outcome']

    def rejected(self, mutation):
        changed = copy.deepcopy(self.ledger)
        mutation(changed)
        self.assertNotEqual(module.json_digest(self.ledger), module.json_digest(changed))
        with self.assertRaises(ValueError):
            module.validate(changed)

    def test_complete_v15_original_bytes_and_all_earlier_outcomes_are_recovered(self):
        import hashlib
        module.validate(self.ledger)
        previous = module.reconstruct_prior_v15(self.ledger)
        raw = (json.dumps(previous, indent=2, ensure_ascii=False) + '\n').encode()
        self.assertEqual(676687, len(raw))
        self.assertEqual('6bb1bfacfca018c0f4f4c2e7c4c279eb4c94b240971b800275b6e982ff60dafc',
                         hashlib.sha256(raw).hexdigest())
        self.assertEqual('6c258512f46a4e7cfcf79b7d1e8da85f56c5b7497bfc3978e005f01090b6da1e',
                         module.json_digest(previous))
        self.assertEqual(15, previous['schema_version'])
        self.assertEqual(35, len(previous['evidence']))
        self.assertEqual(module.PRIOR_V15_FILE_BYTES, len(raw))
        self.assertEqual(module.PRIOR_V15_FILE_SHA256, hashlib.sha256(raw).hexdigest())
        self.assertEqual(module.PRIOR_V15_LEDGER_SHA256, module.json_digest(previous))
        module._validate_prior_v15(previous)
        for field in ('observed_on', 'repository', 'implementation_baseline', 'source'):
            self.assertEqual(previous[field], self.ledger[field])

    def test_only_four_selection_criteria_and_the_effective_coordinate_change(self):
        previous = module.reconstruct_prior_v15(self.ledger)
        current = [c for i in self.ledger['issues'] for c in i['criteria']]
        old = [c for i in previous['issues'] for c in i['criteria']]
        self.assertEqual(441, len(current))
        self.assertEqual([(c['id'], c['text'], c['text_sha256']) for c in old],
                         [(c['id'], c['text'], c['text_sha256']) for c in current])
        self.assertEqual(['98.1', '98.2', '98.3', '98.4'],
                         [c['id'] for c, p in zip(current, old) if c != p])
        self.assertEqual(50, sum(c['status'] == 'complete' for c in current))
        self.assertEqual(391, sum(c['status'] == 'incomplete' for c in current))
        for relation, count in ((module.COMPLETE_DESIGN_RELATION, 25),
                                (module.COMPLETE_IMPLEMENTATION_RELATION, 21),
                                (module.COMPLETE_IMPORT_RELATION, 1),
                                (module.COMPLETE_SUPPORT_RELATION, 3)):
            self.assertEqual(count, sum(c['evidence_relation'] == relation for c in current))
        self.assertEqual([i for i in previous['issues'] if i['number'] != 98],
                         [i for i in self.ledger['issues'] if i['number'] != 98])
        self.assertEqual(previous['evidence'],
                         {k: v for k, v in self.ledger['evidence'].items() if k not in ('pr-566', 'pr-576')})
        expected = copy.deepcopy(self.issue(previous))
        expected['canonical_acceptance_record']['repository'] = module.REPOSITORY
        expected['accepted_implementation_outcome'] = copy.deepcopy(module.IMPLEMENTATION_98_ACCEPTANCE)
        for criterion in expected['criteria']:
            criterion.update(status='complete', completion_evidence=['pr-566', 'pr-576'],
                             remaining_engineering='', evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION)
        self.assertEqual(expected, self.issue(self.ledger))
        self.assertEqual(['pr-137'], self.issue(self.ledger)['criteria'][0]['partial_evidence_refs'])

    def test_signed_implementation_record_and_preparation_keep_distinct_observed_parents(self):
        a = self.acceptance(self.ledger)
        self.assertEqual('12b50e0fad40581d35ae472617f06d4f48ab230d', a['implementation_revision'])
        self.assertEqual('03fd9e8b543506cddfc6f46e53c42f6ba79dc5a6', a['implementation_tree'])
        self.assertEqual('fa1fece43fb0498465cc54363de132235ea2f18a', a['record_revision'])
        self.assertEqual('0ca80b557829d9a36708551f061dd947e55ac460', a['record_tree'])
        self.assertEqual(['bc664e3bb9f085bc1950f05a80cb3c43c7e7f925'], a['implementation_delivery']['parents'])
        self.assertEqual(['20baabebcdec38f85c5816b7c15de091fa7bee13'], a['record_delivery']['parents'])
        self.assertEqual('2026-10-19T14:05:39Z', a['implementation_delivery']['merged_at'])
        self.assertEqual('2026-10-19T16:58:14Z', a['record_delivery']['merged_at'])
        self.assertNotEqual(a['prepared_original']['revision'], a['implementation_revision'])
        self.assertNotEqual(a['record_revision'], a['implementation_revision'])
        for key in ('implementation_revision', 'implementation_tree', 'record_revision', 'record_tree'):
            self.rejected(lambda d, key=key: self.acceptance(d).update({key: '0' * 40}))
        for key in ('implementation_delivery', 'record_delivery'):
            for field, value in (('signature_verified', False), ('signature_reason', 'unsigned'),
                                  ('parents', []), ('merged_at', 'unobserved_future_delivery')):
                self.rejected(lambda d, key=key, field=field, value=value:
                              self.acceptance(d)[key].update({field: copy.deepcopy(value)}))
        self.rejected(lambda d: self.acceptance(d)['prepared_original'].update(
            revision=self.acceptance(d)['implementation_revision']))

    def test_record_and_placement_bytes_bind_the_existing_delivered_documents(self):
        import hashlib
        a = self.acceptance(self.ledger)
        for path, size, expected_sha, expected_blob in (
                (a['record_path'], 29800, 'efa9f1a1e03ad6ba9532896f9bed610196c4a2e45296bb3a2fe8c948e794c2f5',
                 'ab85a283d0955d546867b06ea5e7562b503ad010'),
                (a['placement']['path'], 4871, '71ef903adb5887ac13a682c145f73ba823ee35a48c8fd24648c3886ef7687459',
                 '1461ea8a948c16d8f9dc892009776f8b87686c3c')):
            raw = (ROOT / path).read_bytes()
            self.assertEqual(size, len(raw))
            self.assertEqual(expected_sha, hashlib.sha256(raw).hexdigest())
            self.assertEqual(expected_blob, hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        self.assertEqual(a['record_revision'], a['placement']['effective_revision'])
        self.assertEqual({'repository': module.REPOSITORY, 'path': a['record_path']},
                         self.issue(self.ledger)['canonical_acceptance_record'])
        self.assertEqual('Pick-Your-AI/pickyour.ai', a['placement']['historical_coordinate']['repository'])
        for field in ('record_path', 'record_blob', 'record_sha256', 'record_url'):
            self.rejected(lambda d, field=field: self.acceptance(d).update({field: 'another_record'}))
        self.rejected(lambda d: self.acceptance(d)['placement'].update(effective_revision='0' * 40))
        self.rejected(lambda d: self.issue(d)['canonical_acceptance_record'].update(repository='Pick-Your-AI/pickyour.ai'))
        self.rejected(lambda d: self.issue(d)['canonical_acceptance_record'].update(approved=True))

    def test_actual_receiving_roles_and_source_reviews_cannot_be_substituted(self):
        a = self.acceptance(self.ledger)
        self.assertEqual(['android_catalog_and_ux_receiver', 'independent_affected_data_consumer'],
                         [x['role'] for x in a['receiving_reviews']])
        self.assertEqual([6269464601, 6269539895],
                         [int(x['url'].split('-')[-1]) for x in a['receiving_reviews']])
        self.assertEqual(3, len(a['record_delivery']['source_reviews']))
        self.assertTrue(all(x['state'] == 'COMMENTED' for x in a['record_delivery']['source_reviews']))
        for index in range(2):
            for field in a['receiving_reviews'][index]:
                self.rejected(lambda d, index=index, field=field:
                              self.acceptance(d)['receiving_reviews'][index].update({field: 'inferred_approval'}))
        for index in range(3):
            self.rejected(lambda d, index=index: self.acceptance(d)['record_delivery']['source_reviews'][index].update(state='APPROVED'))
            self.rejected(lambda d, index=index: self.acceptance(d)['record_delivery']['source_reviews'][index].update(body_sha256='0' * 64))
        self.rejected(lambda d: self.acceptance(d)['receiving_reviews'].reverse())
        self.rejected(lambda d: self.acceptance(d).update(receiving_identity_scope='A separate human release owner approved everything.'))

    def test_each_original_job_artifact_and_execution_boundary_is_fixed(self):
        a = self.acceptance(self.ledger)
        self.assertEqual((6306, 6314), (a['prepared_original']['passing_cases'], a['main_original']['passing_cases']))
        self.assertEqual(('pull_request', 'push'), (a['prepared_original']['event'], a['main_original']['event']))
        self.assertEqual([12192828305, 12192988906, 12193653052, 12194000269],
                         [x['id'] for x in a['main_original']['original_artifacts']])
        for key in ('prepared_original', 'main_original'):
            value = a[key]
            self.assertEqual(92, sum(x['successful_step_records'] for x in value['jobs']))
            self.assertEqual([27, 58, 7], [x['successful_step_records'] for x in value['jobs']])
            self.assertEqual(364, value['safe_crc_archive_members'])
            self.assertEqual(44, value['configured_attendance_programs'])
            self.assertEqual({'core': 333, 'preview': 148, 'scanner': 28}, value['python_executions'])
            for index in range(4):
                for field in ('id', 'bytes', 'sha256'):
                    self.rejected(lambda d, key=key, index=index, field=field:
                                  self.acceptance(d)[key]['original_artifacts'][index].pop(field))
            for index in range(3):
                self.rejected(lambda d, key=key, index=index:
                              self.acceptance(d)[key]['jobs'][index].update(conclusion='cancelled'))
            for field in ('revision', 'tree', 'event', 'observation_scope'):
                self.rejected(lambda d, key=key, field=field:
                              self.acceptance(d)[key].update({field: 'a_new_unexecuted_checkout'}))
        self.rejected(lambda d: self.acceptance(d)['execution_preservation'].update(retained_prepared_case_identities=6314))
        self.rejected(lambda d: self.acceptance(d)['execution_preservation'].update(added_main_recovery_case_identities=0))

    def test_all_32_authored_regressions_and_original_source_pins_remain_bound(self):
        a = self.acceptance(self.ledger)
        cohorts = a['authored_regression_cohorts']
        self.assertEqual([14, 8, 10], [len(x['methods']) for x in cohorts])
        identities = [(x['class'], name) for x in cohorts for name in x['methods']]
        self.assertEqual(32, len(set(identities)))
        self.assertEqual(8, len(a['implementation_source_pins']))
        record = (ROOT / a['record_path']).read_text(encoding='utf-8')
        for index, cohort in enumerate(cohorts):
            self.assertIn(cohort['class'], record)
            self.assertIn(cohort['blob'], record)
            self.assertIn(cohort['sha256'], record)
            for name in cohort['methods']:
                self.assertIn('`' + name + '`', record)
            self.rejected(lambda d, index=index: self.acceptance(d)['authored_regression_cohorts'][index]['methods'].pop())
            self.rejected(lambda d, index=index: self.acceptance(d)['authored_regression_cohorts'][index].update(blob='0' * 40))
        self.rejected(lambda d: self.acceptance(d)['authored_regression_cohorts'][0]['methods'].append(
            self.acceptance(d)['authored_regression_cohorts'][0]['methods'][0]))
        for index in range(8):
            self.rejected(lambda d, index=index: self.acceptance(d)['implementation_source_pins'][index].update(sha256='0' * 64))

    def test_raw_scanner_findings_remain_at_their_distinct_original_ancestries(self):
        a = self.acceptance(self.ledger)
        for key, commits, findings in (('prepared_original', 239, 2), ('main_original', 238, 1)):
            s = a[key]['scanner']
            self.assertEqual((10, commits, findings, findings, 0),
                             (s['raw_exit'], s['reachable_commits'], s['raw_findings'],
                              s['reviewed_public_checksum_findings'], s['unresolved_findings']))
            self.assertEqual('passed_with_reviewed_findings', s['disposition'])
            for field, value in (('raw_exit', 0), ('raw_findings', 0), ('reachable_commits', commits + 1),
                                  ('reviewed_public_checksum_findings', 0), ('unresolved_findings', 1)):
                self.rejected(lambda d, key=key, field=field, value=value:
                              self.acceptance(d)[key]['scanner'].update({field: value}))
        self.rejected(lambda d: self.acceptance(d)['main_original'].update(
            scanner=copy.deepcopy(self.acceptance(d)['prepared_original']['scanner'])))

    def test_each_criterion_requires_its_complete_reviewed_evidence_and_no_unfinished_fields(self):
        for index in range(4):
            for field, value in (
                    ('status', 'incomplete'), ('completion_evidence', []), ('completion_evidence', ['pr-137']),
                    ('completion_evidence', ['pr-566']), ('completion_evidence', ['pr-576']),
                    ('remaining_engineering', 'An unreviewed local behavior remains.'),
                    ('external_prerequisites', [{'kind': 'owner_acceptance', 'requirement': 'Still pending'}]),
                    ('evidence_relation', module.COMPLETE_DESIGN_RELATION)):
                self.rejected(lambda d, index=index, field=field, value=value:
                              self.issue(d)['criteria'][index].update({field: copy.deepcopy(value)}))
        for key in ('pr-566', 'pr-576'):
            self.rejected(lambda d, key=key: d['evidence'].pop(key))
            self.rejected(lambda d, key=key: d['evidence'][key].update(observed_state='open'))
        for key in ('accepted_design_outcome', 'accepted_support_outcome'):
            self.rejected(lambda d, key=key: self.issue(d).update({key: {'approved': True}}))

    def test_changed_literal_criterion_text_order_and_joint_source_rewrite_reject(self):
        import hashlib
        for index in range(4):
            changed = copy.deepcopy(self.ledger)
            c = self.issue(changed)['criteria'][index]
            c['text'] += ' Every public domain and installed device is now accepted.'
            c['text_sha256'] = hashlib.sha256(c['text'].encode()).hexdigest()
            source = [{'number': i['number'], 'title': i['title'],
                       'body': '## Acceptance criteria\n' + '\n'.join('- [x] ' + q['text'] for q in i['criteria']) +
                       '\n## Delivery record\n`' + i['canonical_acceptance_record']['path'] + '`'}
                      for i in changed['issues']]
            with self.assertRaises(ValueError):
                module.validate(changed, source)
        self.rejected(lambda d: self.issue(d)['criteria'].reverse())
        self.rejected(lambda d: self.issue(d)['criteria'].pop())
        self.rejected(lambda d: self.issue(d)['criteria'].append(copy.deepcopy(self.issue(d)['criteria'][0])))
        self.rejected(lambda d: self.issue(d)['criteria'][0].update(id='98.2'))
        self.rejected(lambda d: self.acceptance(d)['implemented_subtasks'].pop())

    def test_unknown_prior_context_and_other_evidence_survive_the_inverse_then_reject(self):
        for location in ('ledger', 'issue', 'criterion', 'other-issue', 'old-evidence'):
            changed = copy.deepcopy(self.ledger)
            targets = {'ledger': changed, 'issue': self.issue(changed),
                       'criterion': self.issue(changed)['criteria'][0],
                       'other-issue': changed['issues'][75], 'old-evidence': changed['evidence']['pr-137']}
            targets[location]['unreviewed_history'] = {'accepted': True}
            previous = module.reconstruct_prior_v15(changed)
            old_targets = {'ledger': previous, 'issue': self.issue(previous),
                           'criterion': self.issue(previous)['criteria'][0],
                           'other-issue': previous['issues'][75], 'old-evidence': previous['evidence']['pr-137']}
            self.assertEqual({'accepted': True}, old_targets[location]['unreviewed_history'])
            with self.assertRaises(ValueError):
                module.validate(changed)
        self.rejected(lambda d: self.issue(d)['criteria'][0]['partial_evidence_refs'].clear())
        self.rejected(lambda d: self.issue(d).update(title='A broader replaced work package'))
        for key in module.reconstruct_prior_v15(self.ledger)['evidence']:
            self.rejected(lambda d, key=key: d['evidence'][key].update(scope_limit='Every deployment is accepted.'))

    def test_local_prerequisite_intake_cannot_complete_other_rows_or_broaden_scope(self):
        a = self.acceptance(self.ledger)
        self.assertEqual([76, 82], [x['issue'] for x in a['consumed_prerequisites']])
        self.assertEqual('android_local_catalog_selection_and_bounded_component_route_handoff', a['scope'])
        self.assertFalse(a['accepted_behavior']['private_route']['public_domain_association_accepted'])
        for number in (21, 67, 76, 82, 113, 124):
            incomplete = next(c for c in self.ledger['issues'][number - 1]['criteria'] if c['status'] == 'incomplete')
            index = self.ledger['issues'][number - 1]['criteria'].index(incomplete)
            self.rejected(lambda d, number=number, index=index: d['issues'][number - 1]['criteria'][index].update(
                status='complete', completion_evidence=['pr-566', 'pr-576'], remaining_engineering='',
                external_prerequisites=[], evidence_relation=module.COMPLETE_IMPLEMENTATION_RELATION))
        self.rejected(lambda d: self.acceptance(d)['consumed_prerequisites'].clear())
        self.rejected(lambda d: self.acceptance(d)['scope_limits'].clear())
        self.rejected(lambda d: self.acceptance(d)['accepted_behavior']['private_route'].update(public_domain_association_accepted=True))
        self.rejected(lambda d: self.acceptance(d).update(scope='installed_production_and_all_upstream_features'))

    def test_current_schema_exact_integer_and_boolean_receipt_types_reject_aliases(self):
        for version in (15, 17, True, '16', 16.0):
            self.rejected(lambda d, version=version: d.update(schema_version=version))
        for number in (98.0, '98', True):
            self.rejected(lambda d, number=number: self.issue(d).update(number=number))
        for key in ('prepared_original', 'main_original'):
            for field in ('attempt', 'passing_cases', 'safe_crc_archive_members'):
                value = self.acceptance(self.ledger)[key][field]
                self.rejected(lambda d, key=key, field=field, value=value:
                              self.acceptance(d)[key].update({field: float(value)}))
            self.rejected(lambda d, key=key: self.acceptance(d)[key]['scanner'].update(unresolved_findings=False))
        self.rejected(lambda d: self.acceptance(d)['record_delivery'].update(signature_verified=1))
        self.rejected(lambda d: self.acceptance(d)['accepted_behavior']['private_route'].update(public_domain_association_accepted=0))
        self.rejected(lambda d: self.acceptance(d).update(record_bytes=float(self.acceptance(d)['record_bytes'])))

    def test_inverse_is_detached_and_readme_keeps_the_entire_original_v15_narrative(self):
        import hashlib
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        first = module.reconstruct_prior_v15(self.ledger)
        second = module.reconstruct_prior_v15(self.ledger)
        first['issues'][97]['criteria'][0]['partial_evidence_refs'].append('only_the_copy')
        first['issues'][75]['title'] = 'Only the detached copy'
        first['evidence']['pr-137']['scope_limit'] = 'Only the detached copy'
        self.assertEqual(original, self.ledger)
        self.assertEqual(second, module.reconstruct_prior_v15(original))
        readme = (ROOT / 'docs/delivery/README.md').read_text(encoding='utf-8')
        previous = '# Criterion evidence ledger\n\n' + readme[readme.index('## Version 15:'):]
        self.assertEqual(90887, len(previous.encode()))
        self.assertEqual('fc57c64f1580a0e0e35ed31f9e4a3be01127d3ceedea15103a0ab7a6a19a5271',
                         hashlib.sha256(previous.encode()).hexdigest())

    def test_normal_and_optimized_cli_reject_forged_delivery_and_accept_exact_source(self):
        source = [{'number': i['number'], 'title': i['title'],
                   'body': '## Acceptance criteria\n' + '\n'.join('- [x] ' + c['text'] for c in i['criteria']) +
                   '\n## Delivery record\n`' + i['canonical_acceptance_record']['path'] + '`'}
                  for i in self.ledger['issues']]
        changed = copy.deepcopy(self.ledger)
        self.acceptance(changed)['main_original']['revision'] = self.acceptance(changed)['record_revision']
        with tempfile.TemporaryDirectory(prefix='ledger-selection98-') as directory:
            ledger_path = Path(directory) / 'ledger.json'
            source_path = Path(directory) / 'issues.json'
            source_path.write_text(json.dumps(source), encoding='utf-8')
            for value, code in ((self.ledger, 0), (changed, 1)):
                ledger_path.write_text(json.dumps(value), encoding='utf-8')
                for optimized in (False, True):
                    command = [sys.executable] + (['-O'] if optimized else []) + [
                        str(ROOT / 'scripts/check_delivery_ledger.py'), '--ledger', str(ledger_path), '--issues', str(source_path)]
                    result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                    self.assertEqual(code, result.returncode, result.stdout + result.stderr)
                    if code == 0:
                        self.assertIn('391 incomplete criteria, 25 reviewed design criteria, 21 reviewed implementation criteria', result.stdout)
                        self.assertIn('exact source criteria matched.', result.stdout)
                        self.assertEqual('', result.stderr)
                    else:
                        self.assertIn('Delivery ledger check failed:', result.stderr)
                        self.assertNotIn('PASS:', result.stdout)
                        self.assertNotIn('Traceback', result.stderr)


if __name__ == '__main__':
    unittest.main()
