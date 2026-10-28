---
name: git-ssh
description: Clone or maintain Git repositories, use SSH/SFTP/SCP, or inspect configured application-wide accounts and public identity metadata.
metadata:
  capri/source: builtin
---

Git and SSH credentials are application-managed. Reuse the configured HTTPS credential helper rather than requesting credentials again. For SSH, use the existing global identity/configuration and public manifest; run execution tools only as needed for aliases, public keys or connection diagnostics. Never read or print private keys, tokens or model API keys.

Use hosts, usernames and ports from user instructions or actual configuration, never guesses based on key filenames. Explain fingerprint mismatches from evidence; do not disable host verification to hide them. Git handles repository operations and transport; use a configured GitHub MCP for service operations such as issues or pull requests when relevant. Inspect uncertain effects before repeating commits, pushes or other mutations.
