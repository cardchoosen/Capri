import unittest
from agent_timing import summarize


def event(stage, end, duration=0, **extra):
    return dict(stage=stage, offset_ms=end, duration_ms=duration, **extra)


class TimingTest(unittest.TestCase):
    def test_nested_concurrent_spans_partition_wall_time_and_purpose(self):
        events = [event('turn', 0, status='started'), event('turn_prepare', 10, 10),
                  event('model_request', 50, 40, purpose='execution', status='ok'),
                  event('model_queue', 15, 5, purpose='execution'), event('model_headers', 30, 20),
                  event('tool', 70, 20), event('tool', 75, 20),
                  event('context_prepare', 85, 10), event('model_request', 82, 5, purpose='compression', status='ok'),
                  event('final_reply', 95, 10), event('model_request', 93, 7, purpose='delivery', status='ok'),
                  event('turn', 100, 100, status='ok')]
        r = summarize(events)
        self.assertEqual(100, sum(r['wall_ms'].values()))
        self.assertEqual(25, r['wall_ms']['tools'])
        self.assertEqual(10, r['wall_ms']['delivery'])
        self.assertEqual(5, r['wall_ms']['compression'])
        self.assertEqual(15, r['wall_ms']['preparation'])
        self.assertEqual(52, r['nested_union_ms']['model_request'])
        self.assertEqual(33.3, r['queue_measurement']['coverage_percent'])

    def test_legacy_and_interrupted_traces_do_not_invent_measurements(self):
        r = summarize([event('model_request', 50, 40, status='error')])
        self.assertFalse(r['complete_trace'])
        self.assertEqual(40, r['wall_ms']['unattributed_model'])
        self.assertEqual({'unknown': 1}, r['unsuccessful_attempts'])
        self.assertEqual(0, r['queue_measurement']['measured_attempts'])
        self.assertIsNone(r['queue_measurement']['coverage_percent'])

    def test_only_parse_usage_is_counted_and_private_input_is_not_exported(self):
        usage = {'prompt_tokens': 10, 'secret': 'private', 'total_tokens': 12}
        r = summarize([event('model_parse', 5, 1, purpose='delivery', usage=usage),
                       event('model_request', 5, 5, purpose='delivery', usage=usage, status='ok', prompt='private'),
                       event('turn', 5, 5, status='ok')])
        self.assertEqual(10, r['usage_by_purpose']['delivery']['prompt_tokens'])
        self.assertNotIn('private', str(r))
        self.assertNotIn('secret', str(r))

    def test_cancelled_and_direct_delivery_have_explicit_status(self):
        r = summarize([event('delivery_route', 1, route='direct_exact_json'), event('turn', 2, 2, status='cancelled')])
        self.assertEqual('cancelled', r['status'])
        self.assertEqual({'direct_exact_json': 1}, r['delivery_routes'])
        self.assertEqual({}, r['model_attempts'])

    def test_parallel_delegation_is_counted_by_purpose_without_double_wall_time(self):
        result = summarize([event('tool', 100, 100),
                            event('model_request', 90, 80, purpose='delegation', status='ok'),
                            event('model_request', 80, 60, purpose='delegation', status='ok'),
                            event('model_parse', 90, 1, purpose='delegation', usage={'total_tokens': 20}),
                            event('model_parse', 80, 1, purpose='delegation', usage={'total_tokens': 30}),
                            event('turn', 100, 100, status='ok')])
        self.assertEqual(80, result['wall_ms']['delegation_model'])
        self.assertEqual(20, result['wall_ms']['tools'])
        self.assertEqual(100, sum(result['wall_ms'].values()))
        self.assertEqual(2, result['model_attempts']['delegation'])
        self.assertEqual(50, result['usage_by_purpose']['delegation']['total_tokens'])
