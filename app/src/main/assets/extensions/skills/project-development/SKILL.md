---
name: project-development
description: Read, modify, build, or debug Python, Node, and C/C++ projects while preserving their existing structure and conventions.
metadata:
  capri/source: builtin
---

Inspect project instructions, entry points, and dependency/build manifests before choosing commands. Use list_files/search_files to locate code, read_file for relevant ranges, and edit_file for focused changes. Reuse inspected sha256 values when editing. Preserve the requested language and existing project structure; do not recreate an existing project.

Use terminal or python_run for execution. Retain terminal/command IDs and read long-running output incrementally instead of restarting commands. Diagnose actual build errors before changing dependencies. Run meaningful checks for the change and report verified behavior and artifact paths accurately.
