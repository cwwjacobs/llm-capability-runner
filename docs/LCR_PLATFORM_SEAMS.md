# LCR Platform Seams

This document outlines the architectural seams available for future feature expansion in the LLM Capability Runner (LCR). These seams are designed as inactive interfaces in the `lightapp` codebase that will allow future implementation without requiring a massive architectural rewrite.

## Future Seams Defined

1.  **Model Library**: A framework for selecting, importing, and validating models.
2.  **Folder Scanning**: User-selected folders for discovering suitable local models.
3.  **Hugging Face Integration**: Future token storage and model search/download design.
4.  **Conversation History**: Storage and retrieval of past chat sessions.
5.  **Memory Hydration**: Human-curated memory that is searchable, taggable, and selectable.
6.  **Capability Registry/Menu**: A registry for tools, skills, and extensions.
7.  **Bubble Overlay Mode**: A permission-gated mode for drawing over other apps.
8.  **Screenshot-to-Model Mode**: Passing current screen state to the vision model.
9.  **Active Screen Context**: Live updating context from the screen.
10. **Workflow Runner**: Human-gated execution of receipts and plans.

## Implementation Principles

*   **No Uncontrolled Access**: Folder scanning requires explicit user selection.
*   **Security First**: Hugging Face tokens will be securely stored (e.g., EncryptedSharedPreferences).
*   **User Control**: Overlay and screen capture modes will be strictly permission-gated.
