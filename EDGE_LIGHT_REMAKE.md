# Edge Light Remake

`Android/src/lightapp` is the independent, minimal Android remake. The upstream
Gallery app remains in `Android/src/app` as a reference implementation.

## Product Kernel

- one text chat surface
- one optional local image attachment per turn for multimodal models
- one externally imported LiteRT-LM model
- one model loaded at a time
- CPU text inference with an optional GPU vision backend on `arm64-v8a`
- one private local `SKILL.md` guidance vault
- one explicit, operator-managed context window with local density compression
- compact dark mobile chat UI with persistent theme and model settings
- no embedded model
- no network permission
- no Firebase, analytics, notifications, benchmark, camera, audio, MCP,
  model catalog, or background downloader

## Skills Kernel

Edge Light 0.6.0 can import or create local `SKILL.md` guidance records. Skills
use the same frontmatter shape as the Gallery app (`name` and `description`
followed by Markdown instructions), but the light app deliberately does not
include the upstream network, JavaScript, intent, MCP, or analytics runtimes.

- explicit **Make a Skill** flow plus multi-file import, search, inspection,
  and selection
- selected Skills are added to the prompt as context-only guidance
- selected Skill IDs are recorded in local inference trace provenance
- a Skill cannot execute tools, grant permission, or prove completion

Three local Skills are seeded on first launch:

- `visual-inspection` separates visible evidence from uncertain interpretation
- `structured-code-review` leads with concrete defects, risks, and missing tests
- `private-document-analyst` extracts decisions, constraints, gaps, and actions

## Image Input

Edge Light 0.6.0 accepts one image from Android's document picker and sends the
processed PNG bytes before the text prompt through LiteRT-LM `Content.ImageBytes`.

- image input is disabled by default and must be enabled in Settings
- enabling image input reloads the model with a GPU vision backend
- text-only models may reject image mode; Edge Light restores the prior settings
- images are decoded locally, bounded to 1024 pixels on the largest dimension,
  and limited to 8 MB after PNG encoding
- the image preview can be removed before sending
- trace capture stores the exact processed PNG as a content-addressed artifact
- replay export includes referenced image artifacts and their hashes

An image is a current-turn attachment. Later turns retain its name, dimensions,
and hash in visible conversation context, but do not resend its pixels. Reattach
the image when a later request requires direct visual inspection.

## UX And Settings

Edge Light 0.6.0 keeps model management, trace controls, exports, themes, and
generation settings into the quiet gear beside the message composer.

- compact gold-bracketed context meter beside the Edge Light title
- compact `Ready / Skills / Receipts / Message / Settings` composer strip
- rounded pink-to-purple controls
- near-black shell with a true-black chat panel
- hot-pink status text, purple borders, and gold conversation text
- persistent Default, Dark, and Light themes
- persistent context size, Top K, Top P, temperature, and System Prompt
- persistent opt-in image input for multimodal models
- persistent Skill selection

## Context Window Kernel

Edge Light 0.5.0 rebuilds every inference from one explicit managed context
packet instead of relying on hidden conversation state inside LiteRT-LM.

- exact character count and clearly labeled token estimate using four
  characters per token
- cyan meter below 80%, gold from 80-89%, and red at 90% or higher
- configurable Manual, Assisted, and Automatic management modes
- configurable compression threshold and reserved output capacity
- per-message `Pinned`, `Retain`, `Compress`, `Temporary`, and `Excluded`
  policies
- older compressible entries are summarized before recent entries
- temporary entries are removed under pressure
- pinned and retained entries are protected from automatic compression
- compressed originals remain visible and can be restored locally
- oversized submissions are blocked before violating the reserved output window

Density summaries are local extractive helper output, not canonical records.
They favor decisions, constraints, identifiers, hashes, and paths, but they do
not claim tokenizer-exact accounting or semantic equivalence to the originals.
Each trace receipt records the estimate method, window and reserve, included and
excluded entry IDs, retention IDs, compressed IDs, and compression operations.

## Trace Kernel

Trace recording is local-only and disabled by default. The v2 trace layout has
three layers:

1. `trace_events.jsonl` is one append-only chronology file containing many JSON
   events. It records consent changes, inference starts, terminal outcomes, and
   reviews.
2. `artifacts/` contains many immutable, SHA-256-addressed files. Skill source,
   image inputs, conversation history, and model binaries are stored once and
   referenced by events.
3. Replay export creates one ZIP containing `events.jsonl`, all referenced
   artifacts, and one `manifest.json` with file hashes and ledger health.

This division keeps event records inspectable without repeating a full Skill,
image, conversation, or multi-gigabyte model inside every JSON line.

When enabled, each attempt appends an `inference_started` event before calling
the model and exactly one terminal event:

- `inference_completed`
- `inference_failed`
- `inference_cancelled`

The start event captures:

- raw user prompt and the exact composed prompt sent to the model
- system prompt and hashes for all prompt layers
- session, turn index, parent trace, and prior conversation snapshot
- model name, SHA-256, size, import time, and exact binary snapshot reference
- LiteRT-LM version, backend, generation settings, and app version
- immutable Skill snapshot references, including fingerprints
- exact processed image snapshot references for multimodal turns

The terminal event captures the response or partial response, finish reason,
total latency, time to first chunk, chunk count, and character count. LiteRT-LM
does not currently expose authoritative prompt or output token counts to this
module, so those fields are explicitly recorded as unavailable rather than
estimated.

Operator review appends a separate `review` event with one of:

- `keep`
- `reject`
- `edited`, with a corrected response

Reviews may also contain a reason, quality tags, 1-5 correctness/usefulness/
groundedness/safety scores, source response hashes, and a link to the review
event they supersede.

Every v2 event contains its own SHA-256 and the previous v2 event hash. This
detects accidental or unsanctioned ledger edits, deletion, and reordering when
the chain is checked. It is tamper-evident, not a cryptographic identity
signature: an attacker with full private-storage access could rewrite the
ledger and all hashes.

Model snapshots use a hard link when the app filesystem supports it, avoiding
an immediate second multi-gigabyte allocation. A byte-for-byte copy is the
fallback. A later model import can replace the active model while historical
trace snapshots remain available for replay.

Model imports are staged and initialized before replacing the active model. If
validation fails, Edge Light preserves and reloads the previous model. Model and
artifact hashes are verified before first use in trace provenance.

The ledger does not store hidden reasoning, account identifiers, device
identifiers, or the live partial token stream as separate events. A failed or
cancelled terminal receipt may retain the partial response already emitted to
the operator.

Exports are explicit Android Storage Access Framework writes:

- raw export: all event lines exactly as stored, including readable v1 history
- curated export: latest `keep` and valid `edited` completions as
  `edge-training.v2` JSONL; legacy v1 records remain exportable as v1
- replay export: `edge-replay.v1` ZIP with the ledger, deduplicated context
  artifacts, exact model snapshots, and a checksummed manifest

Before replay export, the active model is registered as a content-addressed
snapshot if it is not already present. Export verifies that every model hash
recorded by an inference has a matching binary snapshot and stops with an
explicit missing-model error rather than producing an incomplete replay claim.

Curated records remain training candidates. Export does not assert that model
output licensing or other rights have been cleared for training.

Replay preserves exact stored inputs, settings, context, runtime metadata, and
model bytes. It does not promise identical generated text because sampling,
runtime implementation, and hardware behavior can vary. If a prior turn failed
or was cancelled, the next receipt marks conversation history as uncertain
because LiteRT-LM does not expose its internal post-failure conversation state.

## Claim Boundary

This module provides local capture, review, provenance metadata, and export. It
does not tune a model, train a model, certify data quality, remove sensitive
content automatically, establish ownership of third-party model outputs, or
cryptographically identify the operator. Raw, curated, and replay exports are
plaintext inside their destination files and require operator confirmation.
Private app files are excluded from Android cloud backup and device transfer.

## Build And Install

From `Android/src`:

```bash
./gradlew :lightapp:testDebugUnitTest :lightapp:assembleDebug
./gradlew :lightapp:installDebug
```

The debug APK is written to:

```text
Android/src/lightapp/build/outputs/apk/debug/lightapp-debug.apk
```

The app requires Android 12 or newer. It is intentionally offline and does not
download models.

## Load Gemma 4 E2B

Download the general Android LiteRT-LM file:

```text
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it.litertlm
```

Copy `gemma-4-E2B-it.litertlm` to the Android device, open Edge Light, tap
**Import model**, and select the file. Google AI Edge Gallery's model metadata
specifies at least 8 GB of device memory for this model. Keep several additional
gigabytes of storage free because Edge Light copies the selected model into
private app storage.
