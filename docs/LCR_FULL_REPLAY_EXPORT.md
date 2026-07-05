# LCR Full Replay Export

This document details the export mechanisms and schema requirements for extracting data from the crash-resilient Conversation Ledger.

## Export Capabilities

The UI must allow explicit user commands for the following actions:
1.  **Save Now**: Force an immediate checkpoint to the internal durable store.
2.  **Export Conversation**: Export standard, human-readable conversation text.
3.  **Export Full Replay Ledger**: Export the complete internal event stream for deterministic replay and debugging.
4.  **Export Training-Ready JSONL**: Generate and export curated data formatted for future model fine-tuning.

## Output Formats

Exports generated from the internal durable store may include:
*   `runner_conversations_lite.json`: Basic message history.
*   `runner_conversations_full.json`: Detailed message history including settings and model info.
*   `runner_replay_ledger.jsonl`: The append-only event stream of the entire session.
*   `runner_training_export.jsonl`: Curated prompt/response pairs suitable for training.

## Capture Requirements

The generated replay and full conversation exports **must capture**:
*   Visible user messages and visible assistant/model responses
*   Timestamps
*   Selected persona and active mode
*   Selected/hydrated memories
*   Operator gates (user approvals/rejections)
*   Model metadata (SHA, parameter counts, architecture)
*   Generation settings (Top K, Top P, Temperature, Context limits)
*   Attachments metadata (image dimensions, mime types, placeholders)
*   Tool and capability events
*   KSL/U-KSL receipts
*   Verification results
*   Redaction events
*   Memory candidate and approval events
