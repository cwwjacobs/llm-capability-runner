package com.terminus.edge.light.memory

data class MemoryRecord(
  val id: String,
  val name: String,
  val content: String,
  val tags: List<String>,
  val source: String
)
