import unittest
from verify import instrumentation_ok, instrumentation_cases


class VerificationTest(unittest.TestCase):
    def test_adb_success_is_not_test_success(self):
        self.assertFalse(instrumentation_ok('INSTRUMENTATION_CODE: -1\nFAILURES!!!\nTests run: 1, Failures: 1'))
        self.assertFalse(instrumentation_ok('INSTRUMENTATION_CODE: -1'))
        self.assertTrue(instrumentation_ok('OK (1 test)\nINSTRUMENTATION_CODE: -1'))
        self.assertFalse(instrumentation_ok('OK (1 test)\nProcess crashed'))
        self.assertFalse(instrumentation_ok('OK (0 tests)'))
        self.assertFalse(instrumentation_ok('INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)'))

    def test_case_timings_keep_failure_and_method_identity(self):
        lines = [(0, 'INSTRUMENTATION_STATUS: class=Example'), (0, 'INSTRUMENTATION_STATUS: test=first'),
                 (1, 'INSTRUMENTATION_STATUS_CODE: 1'), (4, 'INSTRUMENTATION_STATUS_CODE: 0'),
                 (4, 'INSTRUMENTATION_STATUS: test=second'), (5, 'INSTRUMENTATION_STATUS_CODE: 1'),
                 (7, 'INSTRUMENTATION_STATUS_CODE: -2')]
        self.assertEqual(instrumentation_cases(lines), [
            {'test': 'Example#first', 'seconds': 3, 'result_code': 0},
            {'test': 'Example#second', 'seconds': 2, 'result_code': -2}])


class AgentMetricsTest(unittest.TestCase):
    def test_polling_same_failed_command_is_not_multiple_command_failures(self):
        import json
        from analyze_agent_run import summarize
        result = {'data': {'command': {'command_id': 'one', 'state': 'failed', 'exit_code': 1, 'started_at': 1000, 'finished_at': 2500}}}
        entry = {'role': 'tool', 'text': 'terminal\n' + json.dumps(result)}
        metrics = summarize([entry, entry, {'role': 'attachment', 'text': '{}'}])
        self.assertEqual(metrics['tool_calls'], 2)
        self.assertEqual(len(metrics['terminal_commands']), 1)
        self.assertEqual(metrics['terminal_commands'][0]['seconds'], 1.5)
        self.assertIsNone(metrics['total_task_seconds'])
        unavailable = summarize([{'role': 'tool', 'text': 'android\n{"status":"error","data":null}'}])
        self.assertEqual(unavailable['tool_failures'][0]['kind'], 'error')
