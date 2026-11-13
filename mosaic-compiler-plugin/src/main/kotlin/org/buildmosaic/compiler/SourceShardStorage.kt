package org.buildmosaic.compiler

import org.buildmosaic.analysis.SourceShardPaths
import org.jetbrains.kotlin.ir.declarations.IrFile
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Internal source-relative shard identity and obsolete output cleanup. */
internal object SourceShardStorage {
  fun sourceIdentity(
    file: IrFile,
    sourceRoot: String?,
  ): String {
    val source = File(file.fileEntry.name)
    return sourceRoot?.let { SourceShardPaths.sourceId(File(it), source) } ?: source.canonicalFile.name
  }

  fun pruneObsoleteShards(
    root: File,
    outputRoot: File,
  ) {
    require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(outputRoot.toPath())) {
      "Mosaic source and shard roots must not be symbolic links"
    }
    val shardRoot = outputRoot.toPath()
    if (!Files.exists(shardRoot)) return
    Files.walkFileTree(
      shardRoot,
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          val sourceId = SourceShardPaths.sourceIdForShard(outputRoot, file.toFile())
          if (sourceId != null) {
            val source = File(root, sourceId)
            if (!source.isFile || source.extension != "kt") Files.delete(file)
          }
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(
          dir: Path,
          error: java.io.IOException?,
        ): FileVisitResult {
          if (error != null) throw error
          if (dir != shardRoot && Files.list(dir).use { !it.findAny().isPresent }) Files.delete(dir)
          return FileVisitResult.CONTINUE
        }
      },
    )
  }
}
