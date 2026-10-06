package pag.cli

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Builds domain jars for tests: Java sources compiled against pag.api alone,
  * as the Gradle template does (§3), then packed with `java.util.jar`.
  */
object DomainJars:

  /** pag.api's classes: the jar sbt packages (`-Dpag.apiJar`, build.sbt), or,
    * under a runner that does not pass it such as IntelliJ, wherever this JVM
    * loaded `pag.api.Domain` from — `engine/api/target/classes`. Either serves as
    * a classpath.
    */
  val apiPath: Path = Option(sys.props("pag.apiJar")).map(Paths.get(_)).getOrElse(
    Paths.get(classOf[pag.api.Domain[?]].getProtectionDomain.getCodeSource.getLocation.toURI))

  /** The repository: `-Dpag.repoRoot` from sbt, or else the nearest directory
    * above the working directory holding both build.sbt and domains/.
    */
  val repoRoot: Path = Option(sys.props("pag.repoRoot")).map(Paths.get(_)).getOrElse {
    val start = Paths.get("").toAbsolutePath
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)
      .find(d => Files.exists(d.resolve("build.sbt")) && Files.isDirectory(d.resolve("domains")))
      .getOrElse(sys.error(s"no repository root (build.sbt and domains/) at or above $start; set -Dpag.repoRoot"))
  }

  /** A domain's sources from the repository, `domains/<id>/src`, path → text. */
  def domainSources(id: String): Map[String, String] =
    val src = repoRoot.resolve(s"domains/$id/src")
    Using.resource(Files.walk(src)) { files =>
      files.iterator.asScala.filter(_.toString.endsWith(".java"))
        .map(p => src.relativize(p).toString -> Files.readString(p)).toMap
    }

  /** The reference interval domain's sources. */
  def intervalSources: Map[String, String] = domainSources("ref-interval")

  /** A jar of `sources` (path → Java source), plus `extra` files copied in verbatim
    * (path in jar → bytes), handed to `body` and deleted afterwards.
    */
  def withJar[A](sources: Map[String, String], extra: Map[String, Array[Byte]] = Map.empty)(body: Path => A): A =
    val dir = Files.createTempDirectory("domain-src")
    val jar = Files.createTempFile("domain", ".jar")
    try
      val files = sources.map { (rel, text) =>
        val f = dir.resolve("src").resolve(rel); Files.createDirectories(f.getParent); Files.writeString(f, text); f
      }
      val classes = dir.resolve("classes"); Files.createDirectories(classes)
      val javac = ToolProvider.getSystemJavaCompiler
      val args = List("--release", "21", "-classpath", apiPath.toString, "-d", classes.toString) ++ files.map(_.toString)
      val errors = java.io.ByteArrayOutputStream()
      require(javac.run(null, null, errors, args*) == 0, s"javac failed:\n$errors")
      Using.resource(JarOutputStream(Files.newOutputStream(jar))) { out =>
        val compiled = Using.resource(Files.walk(classes))(_.iterator.asScala.filter(Files.isRegularFile(_)).toList)
        for f <- compiled do
          out.putNextEntry(JarEntry(classes.relativize(f).toString.replace(File.separatorChar, '/')))
          out.write(Files.readAllBytes(f)); out.closeEntry()
        for (name, bytes) <- extra do
          out.putNextEntry(JarEntry(name)); out.write(bytes); out.closeEntry()
      }
      body(jar)
    finally
      Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
      Files.deleteIfExists(jar)

  /** The class files of `pag.api`, from the jar or the classes directory, for a jar that bundles its own copy. */
  def apiClasses: Map[String, Array[Byte]] =
    def isApiClass(name: String) = name.startsWith("pag/api/") && name.endsWith(".class")
    if Files.isDirectory(apiPath) then
      Using.resource(Files.walk(apiPath)) { files =>
        files.iterator.asScala.filter(Files.isRegularFile(_))
          .map(f => apiPath.relativize(f).toString.replace(File.separatorChar, '/') -> f)
          .collect { case (name, f) if isApiClass(name) => name -> Files.readAllBytes(f) }.toMap
      }
    else
      Using.resource(java.util.jar.JarFile(apiPath.toFile)) { j =>
        j.entries.asScala.filter(e => isApiClass(e.getName))
          .map(e => e.getName -> j.getInputStream(e).readAllBytes()).toMap
      }

  /** A minimal domain over `Object`, with `body` spliced into the class and the
    * bodies of `isBottom` and `transfer` replaceable.
    */
  def stub(
      name: String,
      body: String = "",
      modifiers: String = "public",
      isBottom: String = """return s.equals("bottom");""",
      transfer: String = "return post;"
  ): (String, String) =
    s"stub/$name.java" ->
      s"""package stub;
         |import pag.api.*;
         |$modifiers class $name implements Domain<Object> {
         |  $body
         |  public String name() { return "$name"; }
         |  public Object top() { return "top"; }
         |  public Object bottom() { return "bottom"; }
         |  public boolean isBottom(Object s) { $isBottom }
         |  public boolean entails(Object a, Object b) { return a.equals("bottom") || b.equals("top"); }
         |  public Object join(Object a, Object b) { return a.equals("top") || b.equals("top") ? "top" : "bottom"; }
         |  public Object widen(Object a, Object b) { return join(a, b); }
         |  public Object transfer(Step step, Object post) { $transfer }
         |}
         |""".stripMargin

  /** transfer throws IllegalStateException("boom"). */
  val throwingStub: Map[String, String] =
    Map(stub("Throws", transfer = """throw new IllegalStateException("boom");"""))

  /** Refutes every target: isBottom always answers true (misc.md §8). */
  val refutesAllStub: Map[String, String] = Map(stub("RefutesAll", isBottom = "return true;"))
