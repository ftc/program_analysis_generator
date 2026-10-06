package pag.results

import java.io.{PrintWriter, StringWriter}

/** The result types shared by the engine and the campaign driver
  * (implementation_strategy.md §13): plain data, no dependency on the rest of
  * the engine, so the driver can read them without linking it (§12).
  */

/** What the analysis is asked (§6). Sealed with one case: the other query forms
  * reduce to `Reachable` (README.md).
  */
sealed trait Query

/** Is the `reach(id)` call reachable? (§5.8) */
final case class Reachable(id: Int) extends Query

/** What the analysis learned, never how it stopped (§7). */
enum Verdict:
  case Refuted // certified unreachable
  case Alarm // searched fully, could not prove it
  case Inconclusive(why: Incomplete) // did not search fully

/** Why a search was incomplete (§7). All three stop it, so exactly one can fire. */
enum Incomplete:
  case IterationLimit(at: Int)
  case Deadline(afterMs: Long)

  /** `op` threw (any `Throwable`) or returned null; the null case is reported as
    * a `NullPointerException` naming `op`.
    */
  case DomainFailure(op: String, error: ErrorInfo)

/** A `Throwable` as data: what a domain failure needs to be printed, stored, and
  * fed back to a generator, without keeping the exception object.
  */
final case class ErrorInfo(className: String, message: Option[String], stackTrace: String):
  /** As `Throwable.toString`: the class name, then `: message` if there is one. */
  override def toString: String = className + message.fold("")(m => s": $m")

object ErrorInfo:
  def of(t: Throwable): ErrorInfo =
    val trace = StringWriter()
    t.printStackTrace(PrintWriter(trace))
    ErrorInfo(t.getClass.getName, Option(t.getMessage), trace.toString)

/** What a reachability check concludes (§9). */
enum Outcome:
  /** Refuted, and the run printed `REACHED-<id>`: the domain is unsound. */
  case Unsound

  /** Nothing contradicted: not refuted, or refuted and this run does not reach the target. */
  case Consistent

  /** The analysis did not finish, so the run judges nothing. */
  case NoVerdict(why: Incomplete)
