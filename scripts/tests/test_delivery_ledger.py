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
        for version in (1, True, '2'):
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


if __name__ == '__main__':
    unittest.main()
