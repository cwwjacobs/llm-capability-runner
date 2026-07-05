# LCR Model Library

This document outlines the specs for the future Model Library in the LLM Capability Runner.

## Design Rules

1.  **User-Selected Folder Scanning**: The application will not aggressively scan the entire device storage for models. The user must explicitly select a folder (via Storage Access Framework or directory selection) to be scanned.
2.  **Hugging Face Integration**: 
    *   **Token Storage**: Tokens must be stored securely (e.g., using Android Keystore and EncryptedSharedPreferences).
    *   **Downloads**: Future support will include downloading models directly from the Hub, with progress tracking and checksum validation.
3.  **Local Model Validation**: Local models will be validated (magic bytes, header checks) before being added to the registry.

## Future Interfaces

```kotlin
// Inactive interface for future implementation
interface HuggingFaceClient {
    suspend fun searchModels(query: String): List<ModelDescriptor>
    suspend fun downloadModel(id: String, progressCallback: (Float) -> Unit)
}
```
