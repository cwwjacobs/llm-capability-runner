# LCR Memory Hydration

This document defines the spec for the future Memory Hydration feature in LCR.

## Design Rules

1.  **Human-Curated**: Memory is not automatically ingested without user consent. Users must curate and tag the memories they want the model to retain.
2.  **Not Always-On**: Memory is not an always-on vector database rummaging through the user's files. It is explicitly activated when needed.
3.  **Searchable & Taggable**: Memories can be categorized and searched via tags, allowing users to selectively hydrate the model's context window.
4.  **Selectable**: Users can toggle specific memories or memory collections before starting a session.

## Future Interfaces

```kotlin
// Inactive interface for future implementation
interface MemoryHydrator {
    suspend fun hydrateContext(tags: List<String>): List<String>
    suspend fun saveMemory(content: String, tags: List<String>)
}
```
