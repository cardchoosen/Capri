---
name: task-environment
description: Prepare dependencies or diagnose runtime gaps for code execution, builds, and installation failures, using task-specific evidence rather than routine full-environment probes.
metadata:
  capri/source: builtin
---

Identify the goal, implementation language, existing project manifests, and actual failure first. Choose the smallest sufficient implementation and dependency set. Reuse applicable valid evidence; one failed installation does not imply a broken environment or permanent inability.

For short tasks, submit known requirements with python_run code/steps so preparation and verification precede execution in one call. Use environment.prepare separately for project or persistent-terminal setup; install shared system packages before opening terminals. Use inspect for uncertain commands/imports/headers/libraries, refresh only for an explicit fresh check, and verify only when a separate sample is needed. Do not install speculative toolchains or confuse cached downloads with installed packages.

On failure, inspect the actual exit code and key output: distinguish network, version constraints, distribution/import names, and missing build libraries. Fix the failed stage and preserve successful intermediate work. Missing dependencies do not justify handwritten codecs or changing the user's language. Combine necessary program/artifact assertions with execution, then deliver the result without redundant probes or repeated rendering.
