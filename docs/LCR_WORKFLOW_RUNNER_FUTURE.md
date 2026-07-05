# LCR Workflow Runner Future

This document details the architectural spec for a future Workflow Runner in LCR.

## Design Rules

1.  **Receipt-Based**: Workflows are defined by structured receipts or traces (e.g., the JSONL format already present in the app).
2.  **Human-Gated**: Workflows will not execute autonomously without human supervision. Critical steps or state changes require human approval or gating.
3.  **No Autonomous App Control**: The workflow runner does not currently possess autonomous browser or app control capabilities. It operates within the constraints of the LCR context and provided capabilities.
4.  **Execution Engine**: The runner will interpret a workflow definition, hydrate the context, and prompt the LLM, managing the state machine of the workflow.

## Future Interfaces

```kotlin
// Inactive interface for future implementation
interface WorkflowRunner {
    suspend fun executeWorkflow(receipt: TraceReceipt, stepCallback: (WorkflowStep) -> Unit)
    fun pauseWorkflow()
    fun resumeWorkflow()
}
```
