"""Concise offline inventory; task requirements belong to task_environment.py."""
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import sys


def inspect_environment():
    return {'distribution': Path('/etc/os-release').read_text(), 'python': sys.version,
            'executable': sys.executable, 'prefix': sys.prefix, 'in_venv': sys.prefix != sys.base_prefix,
            'pip': importlib.metadata.version('pip'),
            'commands': {name: shutil.which(name) for name in ('git', 'curl', 'ssh', 'node', 'npm', 'cc', 'g++', 'make')},
            'package_count': len(list(importlib.metadata.distributions())),
            'scope': 'shared Ubuntu; current chat HOME/venv/workspace',
            'guest_uid': os.getuid(), 'identity': 'PRoot virtual root; Android permissions unchanged'}


if __name__ == '__main__': print(json.dumps(inspect_environment(), ensure_ascii=False, indent=2))
