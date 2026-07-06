package com.terminus.edge.light.workflow

import android.content.Context
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class WorkflowDraft(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val goal: String,
  val steps: List<String>,
  val updatedAtMs: Long = System.currentTimeMillis(),
  val archived: Boolean = false,
)

/**
 * Execution deliberately lives outside the builder. A local model adapter can implement this
 * interface later without coupling workflow storage or editing to a particular inference engine.
 */
fun interface WorkflowModelGateway {
  suspend fun runStep(step: String, input: String): String
}

interface WorkflowExecutor {
  suspend fun execute(
    workflow: WorkflowDraft,
    input: String,
    model: WorkflowModelGateway,
  ): WorkflowResult
}

data class WorkflowResult(
  val output: String,
  val completedSteps: Int,
)

internal object WorkflowDraftCodec {
  private val json = Json { ignoreUnknownKeys = true }

  fun encode(drafts: List<WorkflowDraft>): String =
    buildJsonArray {
        drafts.forEach { draft ->
          add(
            buildJsonObject {
              put("id", draft.id)
              put("name", draft.name)
              put("goal", draft.goal)
              put("updated_at_ms", draft.updatedAtMs)
              put("archived", draft.archived)
              put("steps", buildJsonArray { draft.steps.forEach { add(JsonPrimitive(it)) } })
            }
          )
        }
      }
      .toString()

  fun decode(source: String?): List<WorkflowDraft> {
    if (source.isNullOrBlank()) return emptyList()
    return runCatching {
        json.parseToJsonElement(source).jsonArray.mapNotNull { element ->
          val value = element.jsonObject
          val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val name = value["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val goal = value["goal"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val steps =
            value["steps"]
              ?.jsonArray
              ?.mapNotNull { it.jsonPrimitive.contentOrNull }
              .orEmpty()
          WorkflowDraft(
            id = id,
            name = name,
            goal = goal,
            steps = steps,
            updatedAtMs = value["updated_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            archived = value["archived"]?.jsonPrimitive?.contentOrNull == "true",
          )
        }
      }
      .getOrDefault(emptyList())
  }
}

class WorkflowDraftStore(context: Context) {
  private val preferences =
    context.getSharedPreferences("edge_light_workflow_drafts", Context.MODE_PRIVATE)

  fun activeDrafts(): List<WorkflowDraft> =
    loadAll().filterNot(WorkflowDraft::archived).sortedByDescending(WorkflowDraft::updatedAtMs)

  fun save(id: String?, name: String, goal: String, steps: List<String>): List<WorkflowDraft> {
    val safeName = name.trim().take(MAX_NAME_LENGTH).ifEmpty { "Untitled workflow" }
    val safeGoal = goal.trim().take(MAX_GOAL_LENGTH)
    val safeSteps =
      steps.map(String::trim).filter(String::isNotEmpty).take(MAX_STEPS).map { it.take(MAX_STEP_LENGTH) }
    require(safeGoal.isNotEmpty()) { "A workflow goal is required." }
    require(safeSteps.isNotEmpty()) { "Add at least one workflow step." }

    val all = loadAll().toMutableList()
    val existingIndex = id?.let { draftId -> all.indexOfFirst { it.id == draftId } } ?: -1
    val draft =
      WorkflowDraft(
        id = id ?: UUID.randomUUID().toString(),
        name = safeName,
        goal = safeGoal,
        steps = safeSteps,
      )
    if (existingIndex >= 0) all[existingIndex] = draft else all += draft
    persist(all.takeLast(MAX_DRAFTS))
    return activeDrafts()
  }

  fun archive(id: String): List<WorkflowDraft> {
    val updated =
      loadAll().map { draft ->
        if (draft.id == id) draft.copy(archived = true, updatedAtMs = System.currentTimeMillis())
        else draft
      }
    persist(updated)
    return activeDrafts()
  }

  private fun loadAll(): List<WorkflowDraft> =
    WorkflowDraftCodec.decode(preferences.getString(KEY_DRAFTS, null))

  private fun persist(drafts: List<WorkflowDraft>) {
    check(preferences.edit().putString(KEY_DRAFTS, WorkflowDraftCodec.encode(drafts)).commit()) {
      "Workflow drafts could not be saved."
    }
  }

  private companion object {
    const val KEY_DRAFTS = "drafts_json"
    const val MAX_DRAFTS = 100
    const val MAX_NAME_LENGTH = 120
    const val MAX_GOAL_LENGTH = 2_000
    const val MAX_STEPS = 50
    const val MAX_STEP_LENGTH = 2_000
  }
}
