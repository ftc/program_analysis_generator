package pag.campaign

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/** How a subprocess ended. `exitCode` is None when it was killed for running too long. */
final case class ProcessResult(exitCode: Option[Int], stdout: String, stderr: String, timedOut: Boolean, elapsedMs: Long)

/** Running a subprocess with a wall-clock bound: the real timeout for anything a
  * generated domain can hang (implementation_strategy.md §7, §12). Output goes to
  * files, never pipes, since unread pipe output is lost when a process is killed
  * (§9).
  */
object Processes:

  def run(command: List[String], timeout: FiniteDuration): ProcessResult =
    val out = Files.createTempFile("campaign-out", ".txt")
    val err = Files.createTempFile("campaign-err", ".txt")
    try
      val started = System.currentTimeMillis()
      val process = ProcessBuilder(command.asJava).redirectOutput(out.toFile).redirectError(err.toFile).start()
      val finished = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
      if !finished then
        process.descendants.forEach(_.destroyForcibly())
        process.destroyForcibly().waitFor()
      ProcessResult(Option.when(finished)(process.exitValue), Files.readString(out), Files.readString(err),
        !finished, System.currentTimeMillis() - started)
    finally
      Files.deleteIfExists(out)
      Files.deleteIfExists(err)
