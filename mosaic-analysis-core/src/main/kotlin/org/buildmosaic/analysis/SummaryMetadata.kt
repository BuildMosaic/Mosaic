package org.buildmosaic.analysis

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.buildmosaic.analysis.metadata.WireEnvelope
import org.buildmosaic.analysis.metadata.WireLocator
import org.buildmosaic.analysis.metadata.WirePayload
import org.buildmosaic.analysis.metadata.toModel
import org.buildmosaic.analysis.metadata.toWire
import java.security.MessageDigest

/** Compiler and Gradle plugin version supported by this analysis contract reader and writer. */
const val ANALYSIS_KOTLIN_VERSION = "2.4.20"

/** Internal JAR resource. Compatibility is decided by the header, not this path. */
const val SUMMARY_PATH = "META-INF/mosaic-analysis/v1/summary.json"

data class SummaryMetadata(
  val formatVersion: Int,
  val semanticsVersion: String,
  val toolVersion: String,
  val kotlinCompilerVersion: String,
  val moduleId: String,
  val sourceSet: String,
  val complete: Boolean,
  val payloadHash: String,
  val module: ModuleContract,
  val limitations: List<String>,
  val binaryLocators: Map<String, String>,
)

object SummaryCodec {
  private const val FORMAT_VERSION = 4
  private const val SEMANTICS_VERSION = "analysis-contract-3"
  private const val TOOL_VERSION = "prototype-11"
  private val json =
    Json {
      classDiscriminator = "kind"
      encodeDefaults = true
      explicitNulls = true
    }

  fun encode(
    module: ModuleContract,
    moduleId: String = module.id,
    sourceSet: String = "main",
    limitations: List<String> = emptyList(),
    binaryLocators: Map<String, String> = emptyMap(),
  ): ByteArray {
    require(module.id == moduleId) { "Module identity mismatch" }
    require(moduleId.isNotBlank() && sourceSet == "main") { "Unsupported Mosaic module or source-set identity" }
    val payload =
      WirePayload(
        module.toWire(),
        limitations.distinct().sorted(),
        binaryLocators.toSortedMap().map { (id, locator) -> WireLocator(id, locator) },
      )
    val envelope =
      WireEnvelope(
        FORMAT_VERSION, SEMANTICS_VERSION, TOOL_VERSION, ANALYSIS_KOTLIN_VERSION, moduleId, sourceSet, true,
        sha256(
          json.encodeToString(payload).toByteArray(Charsets.UTF_8),
        ),
        payload,
      )
    return (json.encodeToString(envelope) + "\n").toByteArray(Charsets.UTF_8)
  }

  fun decode(bytes: ByteArray): SummaryMetadata {
    try {
      val raw = bytes.toString(Charsets.UTF_8)
      require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Malformed Mosaic summary UTF-8" }
      val element = json.parseToJsonElement(raw)
      require(element is JsonObject) { "Missing Mosaic summary object" }
      val regeneration = "Regenerate dependency summaries with the matching Mosaic analysis version."
      require(element["formatVersion"]?.jsonPrimitive?.content == FORMAT_VERSION.toString()) {
        "Unsupported Mosaic metadata format; expected $FORMAT_VERSION. $regeneration"
      }
      require(element["semanticsVersion"]?.jsonPrimitive?.content == SEMANTICS_VERSION) {
        "Unsupported Mosaic analyzer semantics; expected $SEMANTICS_VERSION. $regeneration"
      }
      val envelope = json.decodeFromJsonElement(WireEnvelope.serializer(), element)
      require(envelope.kotlinCompilerVersion == ANALYSIS_KOTLIN_VERSION) {
        "Unsupported Kotlin compiler ${envelope.kotlinCompilerVersion}. $regeneration"
      }
      require(envelope.toolVersion.isNotBlank()) { "Missing Mosaic producer version" }
      require(envelope.complete) { "Partial Mosaic summary cannot be used as complete" }
      require(envelope.moduleId.isNotBlank() && envelope.moduleId == envelope.payload.module.id) {
        "Module identity mismatch"
      }
      require(envelope.sourceSet == "main") { "Unsupported source set identity ${envelope.sourceSet}" }
      val hash = sha256(json.encodeToString(envelope.payload).toByteArray(Charsets.UTF_8))
      require(envelope.payloadHash == hash) { "Mosaic summary payload hash mismatch" }
      val locators = linkedMapOf<String, String>()
      envelope.payload.binaryLocators.forEach {
        require(!locators.containsKey(it.id)) { "Duplicate binary locator ${it.id}" }
        locators[it.id] = it.locator
      }
      return SummaryMetadata(
        envelope.formatVersion, envelope.semanticsVersion, envelope.toolVersion,
        envelope.kotlinCompilerVersion, envelope.moduleId, envelope.sourceSet, envelope.complete,
        envelope.payloadHash, envelope.payload.module.toModel(), envelope.payload.limitations, locators,
      )
    } catch (error: SerializationException) {
      throw IllegalArgumentException("Malformed Mosaic summary: ${error.message}", error)
    }
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
