#!/usr/bin/env python3
"""Offline, side-effect-free checks of versioned normalized agent fixtures or exported traces."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def replay(case):
    if case.get('schema') != 1:
        raise ValueError('Unsupported replay schema')
    identity = case['environment']
    if not all(identity.get(k) for k in ('model', 'protocol', 'environment', 'fixture_version')):
        raise ValueError('Model and environment identity are required')
    events, checks = case['events'], case['expect']
    if len(events) > 10000:
        raise ValueError('Trace exceeds the event limit')
    intents, results, duplicates, violations = {}, set(), 0, []
    requests, tokens, unknown_usage = 0, 0, 0
    last_time, phases, constraints = 0, [], []
    for event in events:
        at = event['offset_ms']
        if not isinstance(at, int) or at < last_time:
            raise ValueError('Trace offsets must be monotonic nonnegative integers')
        last_time = at
        kind = event['kind']
        if kind == 'tool_intent':
            call = event['call_id']
            duplicates += int(call in intents)
            intents[call] = event.get('effect_key', call)
        elif kind == 'tool_result':
            if event['call_id'] not in intents:
                violations.append('result_without_intent')
            results.add(event['call_id'])
        elif kind == 'model_attempt':
            requests += 1
            usage = event.get('tokens')
            if usage is None:
                unknown_usage += 1
            elif not isinstance(usage, int) or usage < 0:
                raise ValueError('Token counts must be nonnegative integers')
            else:
                tokens += usage
        elif kind == 'phase_changed':
            phases.append(event['state'])
        elif kind == 'constraints':
            constraints.append(event['hash'])
        elif kind == 'artifact':
            if digest(event['content']) != event['sha256']:
                violations.append('artifact_hash_mismatch')
        elif kind not in {'resume', 'compaction', 'turn_finished', 'cancelled'}:
            raise ValueError('Unsupported normalized trace kind')
    repeated_effects = len(intents) - len(set(intents.values()))
    unknown = sorted(set(intents) - results)
    if duplicates or repeated_effects > checks.get('allowed_repeated_effects', 0):
        violations.append('repeated_operation')
    if unknown and not checks.get('allow_unknown_effects', False):
        violations.append('unresolved_effects')
    if checks.get('required_phase') and checks['required_phase'] not in phases:
        violations.append('missing_expected_phase')
    if checks.get('constraints_hash') and (not constraints or any(h != checks['constraints_hash'] for h in constraints)):
        violations.append('constraint_changed_or_missing')
    if requests > checks.get('maximum_requests', 50):
        violations.append('request_budget_exceeded')
    artifacts = [e for e in events if e['kind'] == 'artifact']
    if checks.get('artifact_sha256') and not any(e['sha256'] == checks['artifact_sha256'] for e in artifacts):
        violations.append('missing_expected_artifact')
    return dict(id=case['id'], passed=not violations, violations=violations,
                elapsed_ms=last_time, model_requests=requests, reported_tokens=tokens,
                responses_without_usage=unknown_usage, monetary_cost=None,
                repeated_operations=repeated_effects + duplicates, unresolved_calls=unknown,
                environment_hash=digest(json.dumps(identity, sort_keys=True)),
                evidence_scope=case.get('evidence_scope', 'normalized_trace_only'))


def evaluate(cases):
    rows = [replay(case) for case in cases]
    identities = {row['environment_hash'] for row in rows}
    return dict(schema=1, cases=rows, passed=sum(row['passed'] for row in rows), total=len(rows),
                comparable=len(identities) == 1, real_model_quality='not_measured',
                notice='Fixture checks validate declared invariants only. Unknown cost is not zero; trace success is not general task proof.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixtures', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = evaluate(json.loads(args.fixtures.read_text()))
    encoded = json.dumps(report, indent=2)
    if args.output:
        args.output.write_text(encoded + '\n')
    else:
        print(encoded)
    raise SystemExit(0 if report['passed'] == report['total'] else 1)
