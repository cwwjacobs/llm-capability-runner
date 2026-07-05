package com.terminus.edge.light.agent

import com.terminus.edge.light.UiMessage
import com.terminus.edge.light.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

interface EdgeDelegate {
  suspend fun sendSystemMessage(content: String)
  suspend fun onWorkflowComplete(result: String)
}

class WorkflowRunner(
  private val scope: CoroutineScope,
  private val delegate: EdgeDelegate
) {
  private var isRunning = false

  fun startWorkflow(goal: String) {
    if (isRunning) return
    isRunning = true
    
    scope.launch {
      delegate.sendSystemMessage("Starting workflow for goal: $goal")
      
      var steps = 0
      val maxSteps = 5
      
      while (isActive && isRunning && steps < maxSteps) {
        steps++
        delegate.sendSystemMessage("Executing step $steps for goal...")
        delay(2000) // Simulate work
        
        // In a real implementation, we'd feed state to the model and parse tool calls.
        // For now, we simulate an autonomous loop.
        if (steps == 3) {
          delegate.sendSystemMessage("Encountered simulated sub-task. Processing...")
          delay(1000)
        }
      }
      
      if (isRunning) {
        isRunning = false
        delegate.onWorkflowComplete("Workflow completed in $steps steps.")
      }
    }
  }

  fun stopWorkflow() {
    if (isRunning) {
      isRunning = false
      scope.launch {
        delegate.sendSystemMessage("Workflow manually stopped.")
      }
    }
  }
}
