package pag.cli

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Builds domain jars for tests: Java sources compiled against `api.jar` alone,
  * as the Gradle template does (§3), then packed with `java.util.jar`.
  */
object DomainJars:

  val apiJar: Path = Paths.get(sys.props("pag.apiJar"))
  val repoRoot: Path = Paths.get(sys.props("pag.repoRoot"))

  /** The reference interval domain's sources, from the repository. */
  def intervalSources: Map[String, String] =
    val src = repoRoot.resolve("domains/ref-interval/src")
    Using.resource(Files.walk(src)) { files =>
      files.iterator.asScala.filter(_.toString.endsWith(".java"))
        .map(p => src.relativize(p).toString -> Files.readString(p)).toMap
    }

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
      val args = List("--release", "21", "-classpath", apiJar.toString, "-d", classes.toString) ++ files.map(_.toString)
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

  /** The class files of `pag.api`, read from api.jar, for a jar that bundles its own copy. */
  def apiClasses: Map[String, Array[Byte]] =
    Using.resource(java.util.jar.JarFile(apiJar.toFile)) { j =>
      j.entries.asScala.filter(e => e.getName.startsWith("pag/api/") && e.getName.endsWith(".class"))
        .map(e => e.getName -> j.getInputStream(e).readAllBytes()).toMap
    }

  /** A minimal domain over `Object`, with `body` spliced into the class. */
  def stub(name: String, body: String = "", modifiers: String = "public"): (String, String) =
    s"stub/$name.java" ->
      s"""package stub;
         |import pag.api.*;
         |$modifiers class $name implements Domain<Object> {
         |  $body
         |  public String name() { return "$name"; }
         |  public Object top() { return "top"; }
         |  public Object bottom() { return "bottom"; }
         |  public boolean isBottom(Object s) { return s.equals("bottom"); }
         |  public boolean entails(Object a, Object b) { return a.equals("bottom") || b.equals("top"); }
         |  public Object join(Object a, Object b) { return a.equals("top") || b.equals("top") ? "top" : "bottom"; }
         |  public Object widen(Object a, Object b) { return join(a, b); }
         |  public Object transfer(Step step, Object post) { return post; }
         |}
         |""".stripMargin
