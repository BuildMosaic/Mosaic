package org.buildmosaic.analysis

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceShardTest {
  private val site = SourceLocation("fixture.Tile", "Foo.kt", 1, 1)

  @Test
  fun `shards assemble in stable order`() {
    val first =
      SourceShard(
        "north/Foo.kt",
        ModuleContract(
          "fixture:app",
          tiles =
            listOf(
              TileContract(
                "fixture.NorthTile",
                listOf(
                  Effect.Unknown("first", "first", site),
                  Effect.Unknown("second", "second", site),
                ),
                site,
              ),
            ),
        ),
        binaryLocators = mapOf("fixture.NorthTile" to "north/FooKt"),
        coreAnalysisRevision = 1,
      )
    val second =
      SourceShard(
        "south/Foo.kt",
        ModuleContract(
          "fixture:app",
          tiles =
            listOf(
              TileContract("fixture.SouthTile", emptyList(), site),
            ),
        ),
        coreAnalysisRevision = 1,
      )
    assertEquals(first, SourceShardCodec.decode(SourceShardCodec.encode(first)))
    val forward = SourceShardCodec.assemble("fixture:app", listOf(first, second), coreAnalysisRevision = 1)
    assertContentEquals(
      forward,
      SourceShardCodec.assemble("fixture:app", listOf(second, first), coreAnalysisRevision = 1),
    )
    val assembled = SummaryCodec.decode(forward)
    assertEquals(
      listOf("first", "second"),
      assembled.module.tiles.single { it.id == "fixture.NorthTile" }
        .effects.filterIsInstance<Effect.Unknown>().map { it.id },
    )
    assertEquals(2, assembled.module.tiles.size)
  }

  @Test
  fun `duplicate owners and malformed shards fail`() {
    val owner = TileContract("fixture.Tile", emptyList(), site)
    val shard = SourceShard("One.kt", ModuleContract("fixture:app", tiles = listOf(owner)), coreAnalysisRevision = 1)
    assertFailsWith<IllegalArgumentException> {
      SourceShardCodec.assemble("fixture:app", listOf(shard, shard.copy(sourceId = "Two.kt")), coreAnalysisRevision = 1)
    }
    assertFailsWith<Exception> { SourceShardCodec.decode("{malformed".toByteArray()) }
    assertFailsWith<IllegalArgumentException> {
      SourceShardCodec.encode(shard.copy(sourceId = "../outside.kt"))
    }
  }

  @Test
  fun `assembly rejects old and mixed semantic facts`() {
    val first = SourceShard("One.kt", ModuleContract("fixture"), coreAnalysisRevision = 1)
    val stale = first.copy(sourceId = "Two.kt", coreAnalysisRevision = 2)
    assertFailsWith<IllegalArgumentException> {
      SourceShardCodec.assemble("fixture", listOf(first, stale), coreAnalysisRevision = 1)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceShardCodec.assemble("fixture", listOf(stale), coreAnalysisRevision = 1)
    }
    assertFailsWith<IllegalArgumentException> {
      SourceShardCodec.assemble("fixture", emptyList(), dependencyRevisions = setOf(2), coreAnalysisRevision = 1)
    }
    assertEquals(
      setOf(1),
      SummaryCodec.decode(
        SourceShardCodec.assemble("fixture", emptyList(), coreAnalysisRevision = 1),
      ).coreAnalysisRevisions,
    )
  }

  @Test
  fun `assembly preserves actual compiler provenance`() {
    val shard = SourceShard("One.kt", ModuleContract("fixture"), coreAnalysisRevision = 1, compilerVersion = "2.2.0")
    val decoded = SourceShardCodec.decode(SourceShardCodec.encode(shard))
    val summary =
      SourceShardCodec.assemble(
        "fixture",
        listOf(decoded),
        coreAnalysisRevision = 1,
        compilerVersion = "2.2.0",
      )
    assertEquals("2.2.0", SummaryCodec.decode(summary).producer.compilerVersion)
    assertEquals(
      "2.3.20",
      SummaryCodec.decode(
        SourceShardCodec.assemble("fixture", emptyList(), coreAnalysisRevision = 1, compilerVersion = "2.3.20"),
      ).producer.compilerVersion,
    )
    assertFailsWith<IllegalArgumentException> {
      SourceShardCodec.assemble("fixture", listOf(decoded), coreAnalysisRevision = 1, compilerVersion = "2.3.20")
    }
    assertFailsWith<IllegalArgumentException> {
      SourceShardCodec.encode(shard.copy(compilerVersion = ""))
    }
  }

  @Test
  fun `shard paths stay within roots`() {
    val root = Files.createTempDirectory("mosaic-shard-paths").toFile()
    val sourceRoot = File(root, "src/main/kotlin")
    val source = File(sourceRoot, "north/Foo.kt")
    source.parentFile.mkdirs()
    source.writeText("package north")
    val shardRoot = File(root, "build/shards")
    val id = SourceShardPaths.sourceId(sourceRoot, source)
    assertEquals("north/Foo.kt", id)
    assertEquals(id, SourceShardPaths.sourceIdForShard(shardRoot, SourceShardPaths.shardFile(shardRoot, id)))
    assertFailsWith<IllegalArgumentException> { SourceShardPaths.sourceId(sourceRoot, File(root, "outside.kt")) }
    assertFailsWith<IllegalArgumentException> { SourceShardPaths.shardFile(shardRoot, "../outside.kt") }
    shardRoot.mkdirs()
    File(root, "outside").mkdirs()
    Files.createSymbolicLink(File(shardRoot, "linked").toPath(), File(root, "outside").toPath())
    assertFailsWith<IllegalArgumentException> { SourceShardPaths.shardFile(shardRoot, "linked/Foo.kt") }
  }
}
