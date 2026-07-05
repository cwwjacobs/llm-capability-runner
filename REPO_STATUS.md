# Repo Status

Status as of 2026-06-14:

```text
Canonical Android repo: cwwjacobs/edge-lite
Canonical branch: main
Current Android scope: Android/src/lightapp
Latest canonical merge: PR #1, Add image support, skills, and audit fixes
Merge commit: 6e0216d3660f5b18b58bfdd1b673f2a6f2e23eaf
```

## Use this repo for

- downloading the latest Android work
- cloning for local development
- Kimi / Codex implementation passes
- Grok adversarial review passes
- future Android PRs

## Do not confuse with

`cwwjacobs/edge-light` is a sibling repo name and is not the current source of truth. It should be treated as superseded unless a task explicitly says to compare or recover old material.

## Next safe continuation pattern

1. Start from `main`.
2. Inspect `Android/src/lightapp`.
3. Make one bounded branch.
4. Open one PR.
5. Include validation and known limitations.
6. Do not rewrite, rename, or broaden scope without operator approval.
