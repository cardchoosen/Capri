import copy
import unittest
from auxiliary_summary_eval import grade, load_cases


class AuxiliarySummaryTest(unittest.TestCase):
    def setUp(self):
        self.cases = load_cases()
        self.correct = {c['id']: dict(goal='Fixture goal', summary='Fixture summary', **c['expected']) for c in self.cases}

    def test_reference_structure_passes_but_still_requires_human_review(self):
        result = grade(self.cases, self.correct)
        self.assertTrue(result['mechanical_pass'])
        self.assertTrue(result['manual_review_required'])

    def test_lost_uncertainty_and_claimed_success_are_rejected(self):
        wrong = copy.deepcopy(self.correct)
        wrong['unknown_side_effect']['facts'][0]['state'] = 'verified'
        wrong['failed_validation']['facts'][1]['state'] = 'verified'
        result = grade(self.cases, wrong)
        self.assertFalse(result['mechanical_pass'])
        self.assertEqual(2, sum(not row['mechanical_pass'] for row in result['cases']))

    def test_changed_target_permission_scope_and_missing_work_are_rejected(self):
        wrong = copy.deepcopy(self.correct)
        wrong['unknown_side_effect']['constraints'][1]['target_path'] = 'old-report.md'
        wrong['permission_boundary']['constraints'] = []
        wrong['failed_validation']['remaining'] = []
        self.assertTrue(all(not row['mechanical_pass'] for row in grade(self.cases, wrong)['cases']))
