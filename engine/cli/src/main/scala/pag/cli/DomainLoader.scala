package pag.cli

import java.lang.reflect.{InvocationTargetException, Modifier}
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.Using

import pag.api.Domain

/** Why a domain jar did not yield a domain, and the exit code that says so (§11). */
final case class DomainLoadFailure(exitCode: Int, message: String)

/** Loads the one domain in a jar (implementation_strategy.md §11): exactly one
  * public, concrete class implementing `pag.api.Domain`, with a public
  * constructor taking no arguments. Zero or several such classes, or a missing
  * constructor, is a usage error (exit 1); a constructor or static initializer
  * that throws is a domain failure (exit 5).
  *
  * The jar's class loader delegates to `pag`'s own first (`URLClassLoader`'s
  * default), so `pag.api` resolves to the engine's copy even if the jar bundles
  * one: engine and domain agree on the contract types. Classes are scanned
  * without being initialized, so no domain code runs until the constructor.
  * Phase 6 grows this: child-first for everything but `pag.api`, and the smoke
  * test.
  */
object DomainLoader:

  def load(jar: Path, parent: ClassLoader = getClass.getClassLoader): Either[DomainLoadFailure, Domain[Any]] =
    if !Files.isRegularFile(jar) then Left(DomainLoadFailure(1, s"not a file: $jar"))
    else
      val loader = URLClassLoader(Array(jar.toUri.toURL), parent) // left open: the domain lives as long as pag
      classNames(jar).flatMap { names =>
        val classes = names.map(n => n -> scan(n, loader))
        classes.collectFirst { case (n, Left(e)) => DomainLoadFailure(1, s"$jar: cannot load class $n: $e") } match
          case Some(failure) => Left(failure)
          case None =>
            classes.collect { case (_, Right(c)) if isCandidate(c) => c } match
              case List(c) => instantiate(c)
              case Nil => Left(DomainLoadFailure(1, s"$jar: no public, concrete class implements pag.api.Domain"))
              case many =>
                Left(DomainLoadFailure(1,
                  s"$jar: several classes implement pag.api.Domain: ${many.map(_.getName).sorted.mkString(", ")}"))
      }

  /** The binary names of the jar's classes, `module-info` aside. */
  private def classNames(jar: Path): Either[DomainLoadFailure, List[String]] =
    Using(JarFile(jar.toFile)) { j =>
      j.entries.asScala.map(_.getName)
        .filter(n => n.endsWith(".class") && !n.endsWith("module-info.class"))
        .map(n => n.stripSuffix(".class").replace('/', '.'))
        .toList.sorted
    }.toEither.left.map(e => DomainLoadFailure(1, s"not a readable jar: $jar: $e"))

  /** Loaded, never initialized: no static initializer runs here. */
  private def scan(name: String, loader: ClassLoader): Either[Throwable, Class[?]] =
    try Right(Class.forName(name, false, loader))
    catch case e: Throwable => Left(e) // LinkageError is not an Exception

  private def isCandidate(c: Class[?]): Boolean =
    classOf[Domain[?]].isAssignableFrom(c) && Modifier.isPublic(c.getModifiers) &&
      !Modifier.isAbstract(c.getModifiers) && !c.isInterface

  /** The engine never looks inside a state, so `Domain[Any]` is safe. */
  private def instantiate(c: Class[?]): Either[DomainLoadFailure, Domain[Any]] =
    c.getConstructors.find(_.getParameterCount == 0) match
      case None => Left(DomainLoadFailure(1, s"${c.getName} has no public constructor taking no arguments"))
      case Some(ctor) =>
        try Right(ctor.newInstance().asInstanceOf[Domain[Any]])
        catch
          case e: InvocationTargetException => Left(DomainLoadFailure(5, s"${c.getName}'s constructor threw ${e.getCause}"))
          case e: Throwable                 => Left(DomainLoadFailure(5, s"${c.getName} failed to initialize: $e"))
