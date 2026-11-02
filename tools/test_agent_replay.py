import copy
import json
from pathlib import Path
import unittest
from agent_replay import evaluate, replay


class ReplayTest(unittest.TestCase):
    def setUp(self):
        self.cases = json.loads((Path(__file__).parent / 'fixtures/agent_reliability/cases.json').read_text())

    def test_fixed_cases_pass_with_unknown_cost_explicit(self):
        report = evaluate(self.cases)
        self.assertEqual(6, report['passed'])
        self.assertTrue(report['comparable'])
        self.assertIsNone(report['cases'][0]['monetary_cost'])
        self.assertEqual(1, report['cases'][3]['responses_without_usage'])

    def test_duplicate_effects_and_corrupt_artifacts_fail(self):
        case = copy.deepcopy(self.cases[0])
        case['events'].insert(2, dict(kind='tool_intent', offset_ms=2, call_id='second', effect_key='write:v1'))
        case['events'][-2]['content'] = 'corrupted'
        row = replay(case)
        self.assertFalse(row['passed'])
        self.assertIn('repeated_operation', row['violations'])
        self.assertIn('artifact_hash_mismatch', row['violations'])

    def test_lost_constraints_or_unresolved_effects_fail(self):
        case = copy.deepcopy(self.cases[2])
        case['events'][2]['hash'] = 'changed'
        self.assertIn('constraint_changed_or_missing', replay(case)['violations'])
        case = copy.deepcopy(self.cases[1])
        case['expect']['allow_unknown_effects'] = False
        self.assertIn('unresolved_effects', replay(case)['violations'])

    def test_incompatible_runs_are_not_comparable(self):
        self.cases[0]['environment']['model'] = 'other'
        self.assertFalse(evaluate(self.cases)['comparable'])

    def test_invalid_timing_usage_and_missing_environment_are_rejected(self):
        for key, value in [('offset_ms', -1), ('tokens', -1)]:
            case = copy.deepcopy(self.cases[0]);case['events'][0][key] = value
            with self.assertRaises(ValueError):
                replay(case)
        del self.cases[0]['environment']['model']
        with self.assertRaises(ValueError):
            replay(self.cases[0])
