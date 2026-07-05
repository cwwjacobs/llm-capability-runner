# LCR Capability Menu

This document specifies the design for the future Capability Menu in LCR.

## Design Rules

1.  **Registry Pattern**: Capabilities (skills, tools, external integrations) will be registered into a central registry that the UI can enumerate.
2.  **Modular**: The engine remains separate from the capability. Capabilities are injected into the context window as needed.
3.  **Menu UI**: A dedicated UI component will allow users to browse, enable, and configure capabilities before or during a conversation.
4.  **Permissions**: Capabilities that require specific Android permissions (e.g., screen reading) will prompt the user at activation time.
