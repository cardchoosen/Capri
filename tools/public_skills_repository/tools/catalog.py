#!/usr/bin/env python3
"""Validate public Skill text bundles without importing or executing their resources."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ID = re.compile(r'[a-z0-9]+(?:-[a-z0-9]+)*\Z')
SECRET = re.compile(r'(?i)(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:password|api[_ -]?key|token)\s*[:=]\s*[\"\']?[A-Za-z0-9_./+-]{16,})')
EXTENSIONS = {'.md', '.txt', '.py', '.js', '.ts', '.json', '.yaml', '.yml', '.sh', '.toml', '.csv'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def build(root):
    skills = root / 'skills'
    if skills.is_symlink():
        raise ValueError('Skill root cannot be a symlink')
    result, total, count = [], 0, 0
    for folder in sorted(skills.iterdir() if skills.exists() else []):
        if not folder.is_dir() or folder.is_symlink() or len(folder.name) > 64 or not ID.fullmatch(folder.name):
            raise ValueError('Invalid Skill directory')
        files, size = [], 0
        for file in sorted(folder.rglob('*'), key=lambda p: p.relative_to(folder).as_posix()):
            relative = file.relative_to(folder).as_posix()
            if any(ord(c) < 32 or ord(c) > 126 for c in relative):
                raise ValueError('Public resource paths must be portable ASCII')
            if file.is_symlink() or any(p.startswith('.') or p in {'node_modules', '__pycache__'} for p in file.relative_to(folder).parts):
                raise ValueError('Hidden, dependency or symlink resource rejected')
            if file.is_dir():
                continue
            if not file.is_file() or len(relative) > 240 or file.suffix not in EXTENSIONS:
                raise ValueError('Public resources must be bounded regular text files')
            if file.stat().st_size > 1_000_000 or len(files) >= 50 or count >= 500:
                raise ValueError('Resource capacity exceeded')
            data = file.read_bytes()
            size += len(data); total += len(data); count += 1
            if size > 1_000_000 or total > 10_000_000:
                raise ValueError('Resource capacity exceeded')
            text = data.decode('utf-8')
            if '\0' in text or SECRET.search(text):
                raise ValueError('Potential secret or binary data found')
            if file.name == 'SKILL.md' and file.parent == folder:
                normalized = text.replace('\r\n', '\n')
                if not normalized.startswith('---\n') or '\n---\n' not in normalized[4:]:
                    raise ValueError('SKILL.md requires YAML frontmatter')
                header = normalized[4:].split('\n---\n', 1)[0]
                if not re.search(r'^name: ?' + re.escape(folder.name) + r'\s*$', header, re.M):
                    raise ValueError('Skill name must match its portable ID')
                description = re.search(r'^description: (.+)$', header, re.M)
                if not description or not 1 <= len(description[1]) <= 1024 or description[1].strip() in {'>', '|', '>-', '|-'}:
                    raise ValueError('Use a nonempty single-line public description')
            files.append(dict(path=relative, sha256=digest(data), bytes=len(data)))
        if not 1 <= len(files) <= 50 or size > 1_000_000 or not any(f['path'] == 'SKILL.md' for f in files):
            raise ValueError('Skill package exceeds limits or lacks SKILL.md')
        revision = digest('\n'.join(f"{f['path']}:{f['sha256']}" for f in files).encode())
        result.append(dict(id=folder.name, revision=revision, files=files))
    if len(result) > 100 or count > 500 or total > 10_000_000:
        raise ValueError('Catalog capacity exceeded')
    return dict(schema=1, skills=result)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--write', type=Path)
    args = parser.parse_args()
    catalog = build(args.root)
    if args.write:
        args.write.write_text(json.dumps(catalog, indent=2) + '\n')
    print(f"Validated {len(catalog['skills'])} public Skills; no resources were executed.")
