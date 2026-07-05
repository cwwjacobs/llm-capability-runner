# LCR Redaction Policy

This document strictly defines what data must be omitted from all persistent storage mechanisms (both the internal durable ledger and external exports) within the LLM Capability Runner.

## Exclusion Requirements

To protect user privacy and secure sensitive infrastructure, the ledger and exports **must never capture or persist**:

*   **Hidden Chain-of-Thought**: Internal reasoning steps that are not meant for the user's view (unless explicitly operating in a raw debug mode with user consent, though default policy is exclusion).
*   **Raw API Keys**: Third-party API keys entered by the user.
*   **Hugging Face Tokens**: Authentication tokens for model registries.
*   **Private Keys**: Cryptographic keys of any kind.
*   **Unredacted Secrets**: Any explicitly marked secret or password handled by tools.
*   **Hidden System/Developer Prompts**: Internal orchestrator prompts that guide the engine but are hidden from the user, protecting the proprietary behavior bounds of the application from leaking into standard exports.
