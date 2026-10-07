package pag.campaign

import java.nio.file.{Files, Path, Paths}

/** Finding the repository the campaign runs in. */
object Repo:

  /** The nearest directory at or above `start` holding both build.sbt and domains/. */
  def root(start: Path = Paths.get("").toAbsolutePath): Either[String, Path] =
    Iterator.iterate(start.toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(d => Files.exists(d.resolve("build.sbt")) && Files.isDirectory(d.resolve("domains")))
      .toRight(s"no repository root (build.sbt and domains/) at or above $start")
