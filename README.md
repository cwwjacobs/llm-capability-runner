# Edge Light

**Canonical Android repo:** `cwwjacobs/edge-lite`  
**Canonical branch:** `main`  
**Current status:** active working Android repo  
**Latest merged milestone:** `0.6.0` via PR #1, `Add image support, skills, and audit fixes`

This repository is the current source of truth for the Terminus Protocol Android / Edge Light work. Use this repo when downloading, cloning, prompting agents, or continuing implementation.

## Download the current version

Use GitHub's normal download path:

1. Open `cwwjacobs/edge-lite`.
2. Select branch `main`.
3. Use **Code → Download ZIP**.

Do not use `cwwjacobs/edge-light` for current work unless explicitly recovering old material.

## What this repo is

Edge Light is a reduced Android fork path derived from Google AI Edge Gallery. The upstream Gallery app remains present as reference material, while the light app work lives under:

```text
Android/src/lightapp
```

The goal is a compact Android LiteRT-LM chat and trace-review app with local model import, local skills, explicit receipts, and bounded multimodal/image handling.

## Current 0.6.0 kernel

The merged 0.6.0 work adds or updates:

- optional local image attachments for compatible multimodal LiteRT-LM models
- processed image preview and trace/replay artifacts
- explicit **Make a Skill** flow
- seeded local skills:
  - `visual-inspection`
  - `structured-code-review`
  - `private-document-analyst`
- safer model import behavior
- trace, receipt, replay, and curated export hardening
- context compression improvements
- Android backup / device-transfer privacy rules

## Important claim boundary

Image input exists as an opt-in path, but live image inference must not be claimed as verified unless tested on-device with a compatible multimodal model. The merged PR noted that live image inference was not exercised because image mode remained disabled for the installed model.

## Agent handoff rule

When assigning follow-up work to Codex, Kimi, Grok, Claude, or another code agent, point them here:

```text
Repo: cwwjacobs/edge-lite
Branch: main
Scope: Android/src/lightapp
Rule: make one bounded PR; do not rewrite or rename the app
```

Preferred order:

1. implementation updates: Kimi or Codex
2. adversarial review: Grok
3. final integration/audit: human operator check

## Known sibling repo

`cwwjacobs/edge-light` is a sibling / older confusing repo name. Treat `edge-lite/main` as canonical unless a future migration note says otherwise.

## License and upstream lineage

This repository descends from Google AI Edge Gallery and retains the upstream Apache-2.0 license where applicable. Keep upstream attribution and license notices intact.
