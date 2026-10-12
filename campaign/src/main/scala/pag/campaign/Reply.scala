package pag.campaign

import scala.annotation.tailrec

/** The files read from a model's reply, and what could not be used. */
final case class ReplyFiles(files: Map[String, String], ignoredBlocks: Int, problems: List[String])

/** Reads source files out of a model's reply (implementation_strategy.md Phase
  * 10): each fenced block whose opening line names a path, as the system prompt
  * asks — or, failing that, whose first line is the path alone, bare or as a
  * `//` comment, a common way to write it. The aim is to measure the domain, not
  * obedience to one way of labelling a file; the raw reply is always recorded,
  * so an attempt can be read again, under these rules or later ones. The files come from a model and are written to disk, so a path must be
  * relative, normalized, under `src/` or `test/`, and end in `.java`; anything
  * else is reported as a problem and never written. A test placed under `src/` —
  * under `src/test/`, or importing JUnit, which `src/` cannot see — is refused the
  * same way: it could never compile there, and its errors would bury the domain's.
  */
object Reply:

  /** The version of these rules, pinned by every campaign: a change to what `files` returns for some reply is a
    * new version (ReplySuite checks it against a fingerprint). v1: before 2026-10-11, when tests under `src/`
    * were written and built.
    */
  val Rules: String = "v2"

  private val SafePath = "(src|test)/[A-Za-z0-9_/.$-]+\\.java".r
  private val ImportsJUnit = "(?m)^\\s*import\\s+(static\\s+)?org\\.junit\\.".r

  def files(reply: String): ReplyFiles =
    val lines = reply.linesIterator.toVector

    /** Block starts and ends: an opening fence has an info string or none; a closing fence is ``&#96; alone. */
    @tailrec
    def blocks(from: Int, acc: List[(String, String)], ignored: Int, problems: List[String]): ReplyFiles =
      lines.indexWhere(_.startsWith("```"), from) match
        case -1 => collect(acc.reverse, ignored, problems.reverse)
        case open =>
          val info = lines(open).drop(3).trim
          lines.indexWhere(_.trim == "```", open + 1) match
            case -1 => collect(acc.reverse, ignored, (s"an unclosed code block at line ${open + 1}" :: problems).reverse)
            case close =>
              val inside = lines.slice(open + 1, close)
              val onFence = info.split("\\s+").find(_.endsWith(".java"))
              val onFirstLine = inside.headOption.map(_.trim.stripPrefix("//").trim)
                .filter(l => l.endsWith(".java") && !l.contains(" "))
              (onFence, onFirstLine) match
                case (Some(path), _) => blocks(close + 1, (path, inside.mkString("\n")) :: acc, ignored, problems)
                case (None, Some(path)) =>
                  blocks(close + 1, (path, inside.drop(1).mkString("\n")) :: acc, ignored, problems)
                case (None, None) => blocks(close + 1, acc, ignored + 1, problems)

    blocks(0, Nil, 0, Nil)

  private def collect(found: List[(String, String)], ignored: Int, problems: List[String]): ReplyFiles =
    val (safe, unsafe) = found.partition((path, _) => isSafe(path))
    val (misplaced, placed) = safe.partition(isTestUnderSrc)
    val duplicates = placed.groupBy(_._1).collect { case (p, xs) if xs.size > 1 => p }.toList.sorted
    ReplyFiles(
      placed.filterNot((p, _) => duplicates.contains(p)).toMap,
      ignored,
      problems ++ unsafe.map((p, _) => s"refused path: $p") ++ misplaced.map((p, _) => s"test file under src/: $p")
        ++ duplicates.map(p => s"given twice: $p")
    )

  private def isTestUnderSrc(path: String, text: String): Boolean =
    path.startsWith("src/") && (path.startsWith("src/test/") || ImportsJUnit.findFirstIn(text).isDefined)

  private def isSafe(path: String): Boolean =
    SafePath.matches(path) && !path.split('/').exists(seg => seg == ".." || seg == "." || seg.isEmpty)
