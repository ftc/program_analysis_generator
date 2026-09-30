package pag.harness

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** What a JVM run printed and how it ended. `exitCode` is the process's own:
  * 0 normally, 1 for an uncaught exception such as running out of inputs, 137
  * when killed at the timeout.
  */
final case class JvmRunResult(reached: Vector[BigInt], exitCode: Int, timedOut: Boolean, stderr: String)

/** The JVM run: the probe's own class file in a fresh JVM, the verdict of record
  * (implementation_strategy.md §9). Nothing is rewritten; reach(id) prints the
  * marker itself (§5.8). Trust base (§2).
  */
object JvmRun:

  val DefaultTimeout: FiniteDuration = 10.seconds

  private val Marker = """REACHED-(-?\d+)""".r

  /** probe-lib, wherever this JVM loaded Rand from: a directory under sbt, a jar when packaged. */
  def probeLib: Path = Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI)

  def run(classes: Path, mainClass: String, inputs: List[BigInt], timeout: FiniteDuration = DefaultTimeout): JvmRunResult =
    val out = Files.createTempFile("jvm-run-out", ".txt")
    val err = Files.createTempFile("jvm-run-err", ".txt")
    try
      val command = List(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        s"-D${pag.probe.Inputs.PROPERTY}=${inputs.mkString(",")}",
        "-cp", List(classes, probeLib).mkString(File.pathSeparator),
        mainClass)
      // stdout goes to a file, never a pipe: destroyForcibly closes the parent's
      // end of a pipe, losing whatever was unread, possibly the marker (§9).
      val process = ProcessBuilder(command.asJava).redirectOutput(out.toFile).redirectError(err.toFile).start()
      val finished = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
      if !finished then
        process.destroyForcibly()
        process.waitFor()
      val reached = Files.readAllLines(out).asScala.toVector.collect { case Marker(id) => BigInt(id) }
      JvmRunResult(reached, process.exitValue, timedOut = !finished, Files.readString(err))
    finally
      Files.deleteIfExists(out)
      Files.deleteIfExists(err)
