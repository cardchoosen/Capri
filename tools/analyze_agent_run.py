#!/usr/bin/env python3
"""Summarize a capri_debug --wait JSONL log, or one explicitly selected saved chat.
Prints counts/timings only, never message bodies, command text or credentials.
Persisted chat entries have no wall-clock timestamps: never infer total task time.
"""
import argparse
from collections import Counter
import json
from pathlib import Path


def summarize(entries):
    tools, commands, failures, diagnostics = Counter(), {}, [], Counter()
    for index, entry in enumerate(entries):
        if entry.get('role') != 'tool': continue
        name, _, body = entry.get('text', '').partition('\n')
        tools[name] += 1
        if name == 'python_run' and body.startswith('exit=') and not body.startswith('exit=0\n'):
            failures.append({'entry': index, 'tool': name, 'kind': 'nonzero_exit'})
        try: result = json.loads(body)
        except (ValueError, TypeError): continue
        if not isinstance(result, dict): continue
        if name == 'environment_check': diagnostics[result.get('readiness', 'unknown')] += 1
        if result.get('status') in ('error', 'invalid_argument'):
            failures.append({'entry': index, 'tool': name, 'kind': result['status']})
        data = result.get('data')
        command = data.get('command') if isinstance(data, dict) else None
        if isinstance(command, dict) and 'command_id' in command:
            commands[command['command_id']] = command
    measured = []
    for command_id, command in commands.items():
        start, end = command.get('started_at'), command.get('finished_at')
        measured.append({'command_id': command_id, 'state': command.get('state'), 'exit_code': command.get('exit_code'),
                         'seconds': round((end - start) / 1000, 3) if isinstance(start, (int, float)) and isinstance(end, (int, float)) else None})
    return {'tool_calls': sum(tools.values()), 'by_tool': dict(tools),
            'attachments': sum(e.get('role') == 'attachment' for e in entries),
            'tool_failures': failures, 'terminal_commands': measured, 'diagnostic_readiness': dict(diagnostics),
            'total_task_seconds': None,
            'timing_limit': 'Command durations are not total task/model latency; polling history can lack the final command state.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    parser.add_argument('--session', help='required for a sessions.json array; reads only this selected chat')
    parser.add_argument('--last-turn', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    text = args.file.read_text()
    if args.session:
        selected = next(s for s in json.loads(text) if s['id'] == args.session)
        entries = selected['messages']
    else:
        if text.lstrip().startswith('['): parser.error('--session is required for saved chat history')
        entries = []
        for line in text.splitlines():
            if not line.startswith('{'): continue
            record = json.loads(line)
            entries.extend(record.get('entries', []))
    if args.last_turn:
        start = next((i for i in range(len(entries)-1, -1, -1) if entries[i].get('role') == 'user'), 0)
        entries = entries[start:]
    report = json.dumps(summarize(entries), ensure_ascii=False, indent=2) + '\n'
    if args.output: args.output.write_text(report)
    else: print(report, end='')


if __name__ == '__main__':
    main()
