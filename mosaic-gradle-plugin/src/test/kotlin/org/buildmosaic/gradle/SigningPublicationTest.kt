package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SigningPublicationTest {
  @Test
  fun `signing outputs do not overlap`() {
    val repositoryRoot = File(System.getProperty("user.dir")).parentFile
    val workspace = Files.createTempDirectory("mosaic-signing-outputs-").toFile()
    val initScript = workspace.resolve("check-signatures.gradle")
    initScript.writeText(
      """
      gradle.projectsEvaluated {
        def compiler = gradle.rootProject.findProject(':mosaic-compiler-plugin')
        if (compiler == null) return
        compiler.tasks.register('checkSigningOutputs') {
          doLast {
            def signingTasks = compiler.tasks.withType(org.gradle.plugins.signing.Sign)
            assert signingTasks.size() == 3
            def owners = [:]
            signingTasks.each { task ->
              assert !task.signatures.empty
              task.signatures.each { signature ->
                def previous = owners.put(signature.file.canonicalPath, task.path)
                assert previous == null : "Overlapping signing output: " + signature.file +
                  " owned by " + previous + " and " + task.path
              }
            }
          }
        }
      }
      """.trimIndent(),
    )
    val result =
      runPublished(
        repositoryRoot,
        ":mosaic-compiler-plugin:checkSigningOutputs",
        "--init-script",
        initScript.absolutePath,
        "--no-configuration-cache",
        "--no-configure-on-demand",
      )
    assertEquals(TaskOutcome.SUCCESS, result.task(":mosaic-compiler-plugin:checkSigningOutputs")?.outcome)
  }
}
