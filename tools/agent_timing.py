#!/usr/bin/env python3
"""Summarize numeric Capri traces without double-counting nested spans.

Library for analyze_agent_run.py; accepts events JSON or JSONL traces. Never emits prompts, outputs,
URLs, provider identifiers or exception text from the input.
"""
from collections import Counter, defaultdict
import json
import math

PURPOSES = {'execution', 'compression', 'delivery'}
PRIORITY = ['delivery', 'compression', 'execution_model', 'unattributed_model', 'retry_wait', 'tools', 'preparation']
NESTED = {'model_request', 'model_prepare', 'model_queue', 'model_headers', 'model_body', 'model_parse', 'model_backoff', 'turn_prepare', 'context_prepare', 'delivery_prepare', 'final_reply', 'tool'}


def number(value):
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0 else None


def union(intervals):
    total = 0.0
    end = 0.0
    for start, stop in sorted(intervals):
        total += max(0, stop - max(start, end))
        end = max(end, stop)
    return round(total, 3)


def summarize(events):
    if not isinstance(events, list) or len(events) > 20000 or not all(isinstance(e, dict) for e in events):
        raise ValueError('Expected at most 20000 event objects.')
    finishes = [e for e in events if e.get('stage') == 'turn' and e.get('status') != 'started' and number(e.get('duration_ms')) is not None]
    observed = number(finishes[-1]['duration_ms']) if finishes else max((number(e.get('offset_ms')) or 0 for e in events), default=0)
    spans = []
    nested = defaultdict(list)
    attempts = Counter()
    failures = Counter()
    usage = defaultdict(Counter)
    routes = Counter()
    queue_events = 0
    for e in events:
        stage = e.get('stage')
        purpose = e.get('purpose') if e.get('purpose') in PURPOSES else 'unknown'
        if stage == 'model_request':
            attempts[purpose] += 1
            if e.get('status') not in {'ok', 'returned'}:
                failures[purpose] += 1
        if stage == 'model_queue':
            queue_events += 1
        if stage == 'delivery_route' and e.get('route') in {'persona', 'direct_exact_json'}:
            routes[e['route']] += 1
        if stage == 'model_parse' and isinstance(e.get('usage'), dict):
            for field in ('prompt_tokens', 'completion_tokens', 'total_tokens', 'prompt_cache_hit_tokens', 'prompt_cache_miss_tokens'):
                n = number(e['usage'].get(field))
                if n is not None:
                    usage[purpose][field] += int(n)
        stop, duration = number(e.get('offset_ms')), number(e.get('duration_ms'))
        if stage not in NESTED or stop is None or duration is None or duration == 0:
            continue
        start, stop = max(0, stop - duration), min(observed, stop)
        if stop <= start:
            continue
        nested[stage].append((start, stop))
        category = None
        if stage == 'final_reply':
            category = 'delivery'
        elif stage == 'model_request':
            category = {'execution': 'execution_model', 'compression': 'compression', 'delivery': 'delivery'}.get(purpose, 'unattributed_model')
        elif stage == 'model_backoff':
            category = 'retry_wait'
        elif stage == 'tool':
            category = 'tools'
        elif stage in {'turn_prepare', 'context_prepare'}:
            category = 'preparation'
        if category:
            spans.append((start, stop, category))
    # Sweep boundaries once. Nested or concurrent work is attributed by explicit precedence.
    changes = defaultdict(Counter)
    changes[0]; changes[observed]
    for start, stop, category in spans:
        changes[start][category] += 1
        changes[stop][category] -= 1
    active = Counter()
    wall = Counter({name: 0.0 for name in PRIORITY + ['other']})
    last = 0.0
    for point in sorted(changes):
        category = next((name for name in PRIORITY if active[name] > 0), 'other')
        wall[category] += point - last
        active.update(changes[point])
        last = point
    total_attempts = sum(attempts.values())
    status = finishes[-1].get('status') if finishes else 'unfinished'
    if status not in {'ok', 'returned', 'error', 'cancelled', 'timeout', 'budget', 'incomplete', 'waiting'}:
        status = 'unknown'
    return {
        'schema': 1, 'complete_trace': bool(finishes), 'status': status,
        'observed_ms': observed, 'wall_ms': {key: round(value, 3) for key, value in wall.items()},
        'nested_union_ms': {key: union(value) for key, value in nested.items()},
        'model_attempts': dict(attempts), 'unsuccessful_attempts': dict(failures),
        'usage_by_purpose': {key: dict(value) for key, value in usage.items()},
        'delivery_routes': dict(routes),
        'queue_measurement': {'measured_attempts': queue_events, 'total_attempts': total_attempts,
                              'coverage_percent': round(min(queue_events, total_attempts) / total_attempts * 100, 1) if total_attempts and finishes else None},
        'limits': [
            'wall_ms partitions observed time; do not add nested_union_ms to it.',
            'Concurrent categories use attribution precedence: ' + ', '.join(PRIORITY) + ', other.',
            'Legacy traces without purpose remain unattributed; missing queue events mean unmeasured, not zero.',
            'Attempt counts use recorded model_request endings; an interrupted in-flight attempt may lack one. Queue coverage is unavailable for unfinished traces.',
            'Headers are not streaming first-token latency. A trace without turn end is an observed prefix only.',
            'Token usage is reported by the provider; no monetary cost is inferred.'
        ]
    }
