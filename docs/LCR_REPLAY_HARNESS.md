# LCR Replay Harness

This document outlines the conceptual design for how the LLM Capability Runner will ingest and utilize the `runner_replay_ledger.jsonl` files.

## Purpose

The Replay Harness is a system designed to parse an exported `runner_replay_ledger.jsonl` file and deterministically reconstruct the exact state of a historical conversation. 

Because the Conversation Ledger enforces an append-only persistence of every granular event (messages, tool executions, memory hydration, and operator gates), the Replay Harness can play back the timeline step-by-step.

## Capabilities

*   **State Reconstruction**: By replaying the JSONL events sequentially, the harness reconstructs the UI state, context window state, and model configuration exactly as they were prior to a crash or at the time of export.
*   **Debugging**: Allows developers and advanced users to load a replay ledger and inspect exactly why an LLM took a specific action, by examining the generation settings, tool receipts, and hydrated memories at that exact timestamp.
*   **Resumption**: Enables taking a completed or interrupted session from another device or time and resuming the conversation from the last valid checkpoint, guaranteeing no data loss of the conversational context.
