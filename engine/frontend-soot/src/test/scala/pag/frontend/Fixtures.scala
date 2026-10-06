package pag.frontend

import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import javax.tools.{DiagnosticCollector, JavaFileObject, ToolProvider}
import scala.jdk.CollectionConverters.*

/** Compiles fixtures from test resources the way a probe is built: `javac -g`
  * (§5.5) against probe-lib alone.
  */
object Fixtures:

  private val probeLib: Path =
    Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI)

  /** Compiles `/fixtures/<name>.java` into a temporary directory, hands that
    * directory to `use`, and deletes it afterwards, whether `use` returns or
    * throws. Whatever `use` returns must not refer to the files.
    */
  def withCompiled[A](name: String)(use: Path => A): A =
    withTempDir("fixture-src") { srcDir =>
      val src = srcDir.resolve(s"$name.java")
      Files.writeString(src, source(name))
      withCompiledSource(src)(use)
    }

  /** The text of `/fixtures/<name>.java`. */
  def source(name: String): String =
    val in = getClass.getResourceAsStream(s"/fixtures/$name.java")
    try String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()

  /** Compiles a probe source file the same way, for sources not in the test
    * resources (a recorded rejection being replayed, say).
    */
  def withCompiledSource[A](src: Path)(use: Path => A): A =
    withTempDir("fixture-classes") { out =>
      javac(src, out, src.getFileName.toString)
      use(out)
    }

  private def javac(src: Path, out: Path, name: String): Unit =
    val compiler = ToolProvider.getSystemJavaCompiler
    val diagnostics = DiagnosticCollector[JavaFileObject]()
    val files = compiler.getStandardFileManager(diagnostics, null, null)
    try
      val options = List("-g", "--release", "21", "-classpath", probeLib.toString, "-d", out.toString, "-proc:none")
      val ok = compiler.getTask(null, files, diagnostics, options.asJava, null,
        files.getJavaFileObjects(src)).call()
      assert(ok, s"fixture $name did not compile: ${diagnostics.getDiagnostics}")
    finally files.close()

  private def withTempDir[A](prefix: String)(use: Path => A): A =
    val dir = Files.createTempDirectory(prefix)
    try use(dir)
    finally
      val paths = Files.walk(dir)
      try paths.sorted(Comparator.reverseOrder()).forEach(p => Files.delete(p))
      finally paths.close()
