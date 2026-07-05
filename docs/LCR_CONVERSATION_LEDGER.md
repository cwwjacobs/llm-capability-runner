# LCR Conversation Ledger (Crash-Resilient Autosave)

This document specifies the design for a crash-resilient Conversation Ledger in the LLM Capability Runner (LCR). The goal is to ensure that LCR preserves full conversation metadata continuously, rather than relying on a fragile final export at the end of a session.

## Storage Design & Crash Policy

*   **Durable Live Store**: The application must maintain an internal durable store for live conversation state (e.g., SQLite/Room or an append-only JSONL file). We must **not** treat one giant in-memory JSON file as the only live storage mechanism.
*   **Crash Policy**: Android cannot guarantee a final save during every crash or sudden termination. Therefore, the design must use write-through persistence or append-only event persistence. A crash should lose at most the current in-flight event, not the entire conversation history.

## Required Save & Checkpoint Behavior

The ledger must persist state at the following granular intervals:
1.  **Event-Driven**: Save conversation events exactly as they happen.
2.  **Message-Driven**: Save immediately after every user message and every assistant/model response.
3.  **Capability-Driven**: Save after every tool or capability event.
4.  **Memory-Driven**: Save after every memory candidate generation, memory approval, or memory hydration change.
5.  **Periodic Autosave**: Continuously autosave the active conversation state (e.g., every 30–60 seconds) to catch long-running inferences.
6.  **Lifecycle-Driven**: Save/checkpoint on app `onPause` or `onStop` whenever possible.

## Pre-Risky Transition Checkpoints

Before executing operations that could destabilize the app or alter fundamental context, the ledger must create a hard checkpoint:
*   Model switch or model import
*   Memory hydration changes
*   Capability or workflow runs
*   Settings migrations
