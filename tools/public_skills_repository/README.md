# Capri Skills

Public, reviewed Skills for Capri. The app downloads `index.json` and listed files from one immutable repository commit, validates hashes, and installs complete definitions without executing resources. No GitHub sign-in is required to download. Local modifications and same-named personal Skills are preserved.

## Contribute

Create and validate your Skill locally, then use Capri's `skill_hub` preparation flow. Review every frozen file in the native confirmation panel before approving public publication through your own GitHub account. A branch or fork is already public before the pull request is reviewed. Never include credentials, private conversations, personal identifiers, local private paths or unlicensed third-party material.

You can also submit a normal pull request containing `skills/<portable-id>/SKILL.md` and required text resources. Keep the frontmatter `name` equal to the directory ID, and provide a single-line English description with clear triggers. First-party instructions, examples and authored diagnostics must be English; quoted source material may keep its original language.

Resource paths must use portable ASCII names. Supported resources: `.md`, `.txt`, `.py`, `.js`, `.ts`, `.json`, `.yaml`, `.yml`, `.sh`, `.toml`, `.csv`. Each Skill may contain up to 50 files and 1 MB. The complete catalog allows 100 Skills, 500 files and 10 MB. Hidden files, symlinks, dependency trees and detected credentials are rejected. Automated checks are limited; maintainers must review behavior, privacy, dependencies and licensing before merging. Do not execute submitted resources during review automation.

Run `python3 tools/catalog.py` for local validation. After an approved merge, the workflow regenerates `index.json`; contributors need not edit the index. Failed indexing leaves the previous catalog available until repaired. Administrators should require the validation check and maintainer review on `main` in repository settings.

By contributing, you agree to license your contribution under MIT and confirm that you can publish all included material. Existing third-party notices must be preserved. This public library is not a backup of unpublished personal Skills.
