package org.buildmosaic.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.buildmosaic.analysis.metadata.WireLocator
import org.buildmosaic.analysis.metadata.WirePayload
import org.buildmosaic.analysis.metadata.toModel
import org.buildmosaic.analysis.metadata.toWire
import java.io.File
import java.nio.file.Files

/** One path convention for compiler output and Gradle's current-source inputs. */
object SourceShardPaths {
  private const val SUFFIX = ".shard.json"

  fun sourceId(
    sourceRoot: File,
    source: File,
  ): String {
    require(!Files.isSymbolicLink(sourceRoot.toPath())) { "Mosaic source root must not be a symbolic link" }
    val root = sourceRoot.canonicalFile.toPath()
    val path = source.canonicalFile.toPath()
    require(path.startsWith(root)) { "Mosaic source is outside supported root: $path" }
    return root.relativize(path).toString().replace(File.separatorChar, '/').also(::checkSourceId)
  }

  fun shardFile(
    shardRoot: File,
    sourceId: String,
  ): File {
    checkSourceId(sourceId)
    require(!Files.isSymbolicLink(shardRoot.toPath())) { "Mosaic shard root must not be a symbolic link" }
    val shard = File(shardRoot, "$sourceId$SUFFIX")
    require(shard.canonicalFile.toPath().startsWith(shardRoot.canonicalFile.toPath())) {
      "Mosaic shard path escapes output root: $shard"
    }
    return shard
  }

  fun sourceIdForShard(
    shardRoot: File,
    shard: File,
  ): String? {
    val root = shardRoot.absoluteFile.toPath().normalize()
    val path = shard.absoluteFile.toPath().normalize()
    require(path.startsWith(root)) { "Mosaic shard is outside output root: $path" }
    val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
    if (!relative.endsWith(SUFFIX)) return null
    return relative.removeSuffix(SUFFIX).also(::checkSourceId)
  }

  internal fun checkSourceId(id: String) {
    require(id.isNotBlank() && !id.startsWith('/') && '\\' !in id && id.split('/').none { it == ".." || it == "." }) {
      "Invalid Mosaic shard source identity $id"
    }
  }
}

/** Internal compiler output. Shards are never packaged or accepted as dependency metadata. */
data class SourceShard(
  val sourceId: String,
  val module: ModuleContract,
  val limitations: List<String> = emptyList(),
  val binaryLocators: Map<String, String> = emptyMap(),
  val coreAnalysisRevision: Int,
  val compilerVersion: String = ANALYSIS_KOTLIN_VERSION,
)

@Serializable
private data class ShardEnvelope(
  val shardVersion: Int,
  val sourceId: String,
  val payload: WirePayload,
  val compilerVersion: String,
)

object SourceShardCodec {
  private const val SHARD_VERSION = 4

  private val json =
    Json {
      classDiscriminator = "kind"
      encodeDefaults = true
      explicitNulls = true
    }

  fun encode(shard: SourceShard): ByteArray {
    SourceShardPaths.checkSourceId(shard.sourceId)
    require(shard.compilerVersion.isNotBlank()) { "Missing Mosaic shard compiler version" }
    val payload =
      WirePayload(
        shard.module.toWire(),
        shard.limitations.distinct().sorted(),
        shard.binaryLocators.toSortedMap().map { (id, locator) -> WireLocator(id, locator) },
        listOf(shard.coreAnalysisRevision),
      )
    val envelope = ShardEnvelope(SHARD_VERSION, shard.sourceId, payload, shard.compilerVersion)
    return (json.encodeToString(envelope) + "\n").toByteArray(Charsets.UTF_8)
  }

  fun decode(bytes: ByteArray): SourceShard {
    val raw = bytes.toString(Charsets.UTF_8)
    require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Malformed Mosaic shard UTF-8" }
    val envelope = json.decodeFromString<ShardEnvelope>(raw)
    require(envelope.shardVersion == SHARD_VERSION) { "Unsupported Mosaic shard version ${envelope.shardVersion}" }
    require(envelope.compilerVersion.isNotBlank()) { "Missing Mosaic shard compiler version" }
    SourceShardPaths.checkSourceId(envelope.sourceId)
    val locators = linkedMapOf<String, String>()
    envelope.payload.binaryLocators.forEach {
      require(locators.putIfAbsent(it.id, it.locator) == null) { "Duplicate Mosaic shard locator ${it.id}" }
    }
    val revision =
      requireNotNull(envelope.payload.coreAnalysisRevisions.singleOrNull()) {
        "Mosaic shard must record exactly one observed Core analysis revision"
      }
    return SourceShard(
      envelope.sourceId,
      envelope.payload.module.toModel(),
      envelope.payload.limitations,
      locators,
      revision,
      envelope.compilerVersion,
    )
  }

  fun assemble(
    moduleId: String,
    shards: Collection<SourceShard>,
    coreAnalysisRevision: Int,
    dependencyRevisions: Set<Int> = emptySet(),
    compilerVersion: String = ANALYSIS_KOTLIN_VERSION,
  ): ByteArray {
    val requirements = dependencyRevisions + coreAnalysisRevision
    CoreAnalysisRevision.requireUnderstood(requirements)
    val ids = mutableSetOf<String>()
    val canvases = mutableListOf<CanvasContract>()
    val tiles = mutableListOf<TileContract>()
    val callables = mutableListOf<CallableContract>()
    val overrides = mutableListOf<ResolvedOverride>()
    val keys = mutableListOf<KeyContract>()
    val limitations = mutableListOf<String>()
    val locators = linkedMapOf<String, String>()
    val sources = mutableSetOf<String>()

    fun owner(
      kind: String,
      id: String,
    ) {
      require(ids.add("$kind:$id")) { "Duplicate local Mosaic $kind owner $id" }
    }
    shards.sortedBy { it.sourceId }.forEach { shard ->
      require(shard.compilerVersion == compilerVersion) {
        "Stale or mixed Mosaic compiler version in ${shard.sourceId}: " +
          "observed ${shard.compilerVersion}, selected $compilerVersion. Recompile all current sources."
      }
      require(shard.coreAnalysisRevision == coreAnalysisRevision) {
        "Stale or mixed Mosaic Core analysis revision in ${shard.sourceId}: " +
          "observed ${shard.coreAnalysisRevision}, selected $coreAnalysisRevision. Recompile all current sources."
      }
      require(sources.add(shard.sourceId)) { "Duplicate Mosaic source shard ${shard.sourceId}" }
      require(shard.module.id == moduleId) { "Mosaic shard module mismatch in ${shard.sourceId}" }
      shard.module.canvases.forEach {
        owner("canvas", it.id)
        canvases += it
      }
      shard.module.tiles.forEach {
        owner("tile", it.id)
        tiles += it
      }
      shard.module.callables.forEach {
        owner("callable", it.id)
        callables += it
      }
      shard.module.overrides.forEach {
        owner("override", "${it.receiverType}:${it.baseId}:${it.implementationId}")
        overrides += it
      }
      shard.module.keys.forEach {
        owner("key", it.id)
        keys += it
      }
      limitations += shard.limitations
      shard.binaryLocators.forEach { (id, locator) ->
        require(locators.putIfAbsent(id, locator) == null) { "Duplicate local Mosaic binary locator $id" }
      }
    }
    return SummaryCodec.encode(
      ModuleContract(moduleId, canvases, tiles, callables, overrides, keys),
      limitations = limitations.distinct().sorted(),
      binaryLocators = locators,
      coreAnalysisRevisions = requirements,
      compilerVersion = compilerVersion,
    )
  }
}
