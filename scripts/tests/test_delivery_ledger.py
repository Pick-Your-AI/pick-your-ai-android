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
        for version in (1, 2, 3, 4, 5, 6, 7, 8, 9, 11, True, '2', '3', '4', '5', '6', '7', '8', '9', '10', 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0):
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
        issues = self.ledger['issues']
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
                with self.assertRaisesRegex(ValueError, f'unsupported implementation acceptance: {number}'):
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
        for key in module.reconstruct_prior_v8(self.ledger)['evidence']:
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
        previous_v8 = module.reconstruct_prior_v8(self.ledger)
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
        self.assert_rejected('duplicate JSON member', ledger_text=self.raw_ledger.replace(
            '"schema_version": 10', '"schema_version": 5, "schema_version": 10', 1))
        self.assert_rejected('duplicate JSON member', source_text=self.raw_source.replace(
            '"number": 1', '"number": 999, "number": 1', 1))

    def test_nested_review_and_source_body_duplicates_fail_at_cli(self):
        self.assert_rejected('duplicate JSON member', ledger_text=self.raw_ledger.replace(
            '"receiving_reviews": [', '"receiving_reviews": [], "receiving_reviews": [', 1))
        self.assert_rejected('duplicate JSON member', source_text=self.raw_source.replace(
            '"body":', '"body": "Unreviewed replacement", "body":', 1))

    def test_escaped_duplicate_members_fail_at_both_cli_inputs(self):
        self.assert_rejected('duplicate JSON member', ledger_text=self.raw_ledger.replace(
            '"schema_version": 10', r'"\u0073chema_version": 5, "schema_version": 10', 1))
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
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                result = self.run_cli(ledger_text, source_text, optimized)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('409 incomplete criteria, 25 reviewed design criteria, 3 reviewed implementation criteria, 1 reviewed import-integrity criterion, 3 reviewed support criteria', result.stdout)
                self.assertIn('exact source criteria matched.', result.stdout)
                self.assertEqual(result.stderr, '')
        changed = copy.deepcopy(self.source)
        changed[0]['body'] = changed[0]['body'].replace('All selected core work packages',
                                                      'Changed selected work packages')
        self.assert_rejected('live criteria differ: 1', source_text=json.dumps(changed))

    def test_each_ordinary_record_rejects_a_different_valid_website_path(self):
        for issue in self.ledger['issues']:
            if (issue['number'] in module.REVIEWED_DESIGNS or issue['number'] in module.REVIEWED_IMPLEMENTATIONS
                    or issue['number'] == module.SUPPORT_33_NUMBER):
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
        prior = module.reconstruct_prior_v8(self.ledger)
        self.assertEqual('5db8320d3f26f0ddf24e751e41489aaec344f997eb52d408a95449b4b6cabdae',
                         module.json_digest(prior))
        # Keep this original import-only comparison at its reconstructed v9.
        reviewed_v9 = module.reconstruct_prior_v9(self.ledger)
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
        prior = module.reconstruct_prior_v8(self.ledger)
        self.assertEqual(8, prior['schema_version'])
        self.assertEqual(24, len(prior['evidence']))
        self.assertEqual(413, sum(c['status'] == 'incomplete' for i in prior['issues'] for c in i['criteria']))
        self.assertEqual('5db8320d3f26f0ddf24e751e41489aaec344f997eb52d408a95449b4b6cabdae', module.json_digest(prior))

    def test_validation_and_prior_reconstruction_never_mutate_the_callers_ledger(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        self.assertEqual(original, self.ledger)
        previous = module.reconstruct_prior_v8(self.ledger)
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
        prior = module.reconstruct_prior_v9(self.ledger)
        self.assertEqual(9, prior['schema_version'])
        self.assertEqual('81314afc8892c8b6069fa6ccfdc35805f7feec3dbec3cf304eaccc392fa5c55b',
                         module.json_digest(prior))
        raw = (json.dumps(prior, indent=2, ensure_ascii=False) + '\n').encode()
        self.assertEqual(494218, len(raw))
        self.assertEqual('49fc9557d3fc383c355663c2a71cc5096a4e2dcca5b1ed91da7657e249d627c4',
                         hashlib.sha256(raw).hexdigest())
        current = [c for i in self.ledger['issues'] for c in i['criteria']]
        previous = [c for i in prior['issues'] for c in i['criteria']]
        self.assertEqual(441, len(current))
        self.assertEqual(['33.1', '33.2', '33.3'], [c['id'] for c, old in zip(current, previous) if c != old])
        self.assertEqual([33], [i['number'] for i, old in zip(self.ledger['issues'], prior['issues']) if i != old])
        self.assertEqual(25, len(prior['evidence']))
        self.assertEqual(prior['evidence'], {k: v for k, v in self.ledger['evidence'].items() if k != 'pr-494'})
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
        for ref in module.reconstruct_prior_v9(self.ledger)['evidence']:
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
                        self.ledger['purpose'].replace('409', '408')):
            ledger = copy.deepcopy(self.ledger)
            ledger['purpose'] = purpose
            with self.assertRaisesRegex(ValueError, 'unsupported ledger completion summary'):
                module.validate(ledger)

    def test_validation_and_both_history_reconstructions_preserve_the_input(self):
        original = copy.deepcopy(self.ledger)
        module.validate(self.ledger)
        self.assertEqual(original, self.ledger)
        prior = module.reconstruct_prior_v9(self.ledger)
        earlier = module.reconstruct_prior_v8(self.ledger)
        prior['issues'][32]['criteria'][0]['text'] = 'Changed only in the returned copy.'
        prior['evidence']['pr-430']['scope_limit'] = 'Changed only in the returned copy.'
        earlier['issues'][20]['criteria'][1]['text'] = 'Changed only in the returned copy.'
        self.assertEqual(original, self.ledger)


if __name__ == '__main__':
    unittest.main()
