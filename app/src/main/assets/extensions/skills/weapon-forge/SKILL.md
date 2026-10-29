---
name: weapon-forge
description: Meta-skill that forges reusable weapon-category hacker Skills for Capri. Combine the current runtime environment (root/network/hardware/dependencies) with vulnerability intelligence to simulate and reproduce a vulnerability and solidify the reusable attack into an on-demand weapon. Use when the user wants to turn a vulnerability reproduction, penetration technique, or security test flow into a reusable weapon.
metadata:
  capri/source: builtin
---

Turn a one-off vulnerability reproduction into a reusable weapon skill. Generated artifacts are always `weapon`; this forge skill itself is `general`, not a weapon.

Handle only clearly authorized targets (owned devices, labs, written authorization). Reproduction does not expand the authorization boundary: target, scope, and impact are defined by the user's task. This skill does not expand the attack surface on its own, touch unrelated systems, store credentials, or embed private keys.

## Relationship to skill-creator

This skill reuses skill-creator conventions to produce skills: SKILL.md structure, frontmatter, `scripts/`/`references/`/`assets/` layout, installation and verification flow. Before forging a weapon, `load_skill(skill-creator)` and follow its conventions, then layer weapon-specific steps on top without repeating skill-creator's already-specified general rules.

## Forging workflow

1. **Define target and authorization**: confirm the attack target, scope, and impact surface. Do not start without authorization.
2. **Sense the environment**: use the `environment` tool to inspect the current runtime — root capability, network namespace, Wi-Fi/network topology, hardware (e.g. whether the wireless chip supports monitor mode), installed dependencies, shared Ubuntu. This is the key input for "combining with the current runtime environment" and determines which techniques are viable.
3. **Collect vulnerability intelligence**: use `web_search` / `web_read` for vulnerability details, CVE, PoC, affected versions, and exploitation conditions. Prefer official/primary sources; verify before reproducing; do not fabricate.
4. **Simulate and reproduce**: test on a lab/authorized target, confirm the vulnerability triggers and the attack lands, and record key evidence (trigger conditions, commands, output, verdict criteria).
5. **Solidify into a weapon**: split into reusable components — `SKILL.md` (prerequisites, workflow, key decisions, real limits) + `scripts/` (fixed scripts) + `references/` (vulnerability background) + `assets/` (public material). The frontmatter must set `metadata.capri/category: weapon`.
6. **Install and verify**: install via `extensions(install_skill)`; verify scripts with a real small task, distinguishing "installed" from "scripts verified".

## Weaponization criteria

- **Reusable**: scripts parameterized (target IP/MAC etc.), no hardcoded one-off field values.
- **Verdictable**: scripts output a structured "success / failure / which layer blocked" conclusion, not relying on manual log reading.
- **Recoverable**: attacks have cleanup/restore steps, leaving no residual impact.
- **Bounded**: no embedded credentials, device identifiers, or private keys; sensitive material recovered on the spot, not persisted.

## Artifact categorization

- Generated skills are always `weapon`.
- `weapon` vs `general`: weapon is attack/penetration/reproduction arsenal; general is general capability (skill-creator, project-development, etc.).
- Category is set through the frontmatter `metadata` field `capri/category` (e.g. `capri/category: weapon`). The `SkillCategory` enum currently supports `general` and `weapon`; unknown values fall back to `general`.
