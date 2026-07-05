package com.terminus.edge.light.persona

import java.io.File

data class Persona(
  val id: String,
  val name: String,
  val systemPrompt: String,
  val allowedCapabilities: List<String>
)

class PersonaVault(private val root: File) {
  init {
    require(root.mkdirs() || root.isDirectory) { "Could not create the Persona vault." }
  }
  
  fun list(): List<Persona> =
    root
      .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
      .orEmpty()
      .mapNotNull { file -> 
         // simplistic mock for the boundary
         Persona(
           id = file.nameWithoutExtension,
           name = file.nameWithoutExtension,
           systemPrompt = "You are a helpful assistant.",
           allowedCapabilities = emptyList()
         )
      }
}
