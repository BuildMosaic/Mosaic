package org.buildmosaic.analysis

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.buildmosaic.analysis.metadata.WireEnvelope
import org.buildmosaic.analysis.metadata.WireLocator
import org.buildmosaic.analysis.metadata.WirePayload
import org.buildmosaic.analysis.metadata.WireProducer
import org.buildmosaic.analysis.metadata.toModel
import org.buildmosaic.analysis.metadata.toWire
import java.security.MessageDigest
import java.util.Properties

/** Host build compiler; introspector ABI builds are defined separately in the measured mapping. */
const val ANALYSIS_KOTLIN_VERSION = "2.4.21"

/** Internal JAR resource. Compatibility is decided by the header, not this path. */
const val SUMMARY_PATH = "META-INF/mosaic-analysis/v1/summary.json"

data class SummaryProducer(val analysisVersion: String, val compilerVersion: String)

data class SummaryMetadata(
  val contractVersion: Int,
  val producer: SummaryProducer,
  val moduleId: String,
  val sourceSet: String,
  val complete: Boolean,
  val payloadHash: String,
  val module: ModuleContract,
  val limitations: List<String>,
  val binaryLocators: Map<String, String>,
  val coreAnalysisRevisions: Set<Int>,
)

object SummaryCodec {
  private const val CONTRACT_VERSION = 6

  private val analysisVersion: String =
    Properties().apply {
      val stream =
        requireNotNull(SummaryCodec::class.java.getResourceAsStream("version.properties")) {
          "Mosaic Analysis artifact is missing its version metadata"
        }
      stream.use(::load)
    }.getProperty("version").also {
      require(!it.isNullOrBlank()) { "Mosaic Analysis artifact has invalid version metadata" }
    }
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
    coreAnalysisRevisions: Set<Int>,
  ): ByteArray =
    encode(
      module,
      moduleId,
      sourceSet,
      limitations,
      binaryLocators,
      coreAnalysisRevisions,
      ANALYSIS_KOTLIN_VERSION,
    )

  fun encode(
    module: ModuleContract,
    moduleId: String = module.id,
    sourceSet: String = "main",
    limitations: List<String> = emptyList(),
    binaryLocators: Map<String, String> = emptyMap(),
    coreAnalysisRevisions: Set<Int>,
    compilerVersion: String,
  ): ByteArray {
    require(compilerVersion.isNotBlank()) { "Missing Mosaic producer compiler version" }
    require(module.id == moduleId) { "Module identity mismatch" }
    require(moduleId.isNotBlank() && sourceSet == "main") { "Unsupported Mosaic module or source-set identity" }
    val payload =
      WirePayload(
        module.toWire(),
        limitations.distinct().sorted(),
        binaryLocators.toSortedMap().map { (id, locator) -> WireLocator(id, locator) },
        coreAnalysisRevisions.sorted(),
      )
    val envelope =
      WireEnvelope(
        CONTRACT_VERSION,
        WireProducer(analysisVersion, compilerVersion),
        moduleId,
        sourceSet,
        true,
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
      require(element["contractVersion"]?.jsonPrimitive?.content == CONTRACT_VERSION.toString()) {
        val legacy = element["formatVersion"]?.jsonPrimitive?.content
        "Unsupported Mosaic metadata contract version${legacy?.let { "; legacy format $it" } ?: ""}; " +
          "expected $CONTRACT_VERSION. $regeneration"
      }
      val envelope = json.decodeFromJsonElement(WireEnvelope.serializer(), element)
      require(envelope.producer.compilerVersion.isNotBlank()) { "Missing Mosaic producer compiler version" }
      require(envelope.producer.analysisVersion.isNotBlank()) { "Missing Mosaic producer analysis version" }
      require(envelope.complete) { "Partial Mosaic summary cannot be used as complete" }
      require(envelope.moduleId.isNotBlank() && envelope.moduleId == envelope.payload.module.id) {
        "Module identity mismatch"
      }
      require(envelope.sourceSet == "main") { "Unsupported source set identity ${envelope.sourceSet}" }
      val hash = sha256(json.encodeToString(envelope.payload).toByteArray(Charsets.UTF_8))
      require(envelope.payloadHash == hash) { "Mosaic summary payload hash mismatch" }
      CoreAnalysisRevision.requireUnderstood(envelope.payload.coreAnalysisRevisions)
      val locators = linkedMapOf<String, String>()
      envelope.payload.binaryLocators.forEach {
        require(!locators.containsKey(it.id)) { "Duplicate binary locator ${it.id}" }
        locators[it.id] = it.locator
      }
      return SummaryMetadata(
        envelope.contractVersion, SummaryProducer(envelope.producer.analysisVersion, envelope.producer.compilerVersion),
        envelope.moduleId, envelope.sourceSet, envelope.complete,
        envelope.payloadHash, envelope.payload.module.toModel(), envelope.payload.limitations, locators,
        envelope.payload.coreAnalysisRevisions.toSet(),
      )
    } catch (error: SerializationException) {
      throw IllegalArgumentException("Malformed Mosaic summary: ${error.message}", error)
    }
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
