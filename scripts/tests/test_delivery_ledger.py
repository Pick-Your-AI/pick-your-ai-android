"""Regression checks for fail-closed coverage and evidence claims."""
import copy
import importlib.util
import json
from pathlib import Path
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
        for version in (1, 2, 3, True, '2', '3', '4', 4.0):
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
                with self.assertRaisesRegex(ValueError, f'unsupported design acceptance: {number}'):
                    module.validate(ledger)


if __name__ == '__main__':
    unittest.main()
