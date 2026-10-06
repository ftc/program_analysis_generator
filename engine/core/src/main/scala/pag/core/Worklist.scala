package pag.core

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import pag.api.Domain
import pag.ir.{Loc, Step, Transition}
import pag.results.{ErrorInfo, Incomplete}

/** The compute stage's result: the invariant map and how the search stopped.
  * Not a verdict — only the certifier (§7) produces one.
  */
final case class Computed[S](
    states: Map[Loc, S], // a location absent from the map is ⊥
    stopped: Option[Incomplete], // None: the worklist emptied
    iterations: Int,
    unexplored: Int, // transitions still queued when the search stopped
    elapsedMs: Long,
    widenedAt: Set[Loc]
)

/** The compute stage (implementation_strategy.md §7): a backward worklist from
  * the targets, seeded ⊤ there and ⊥ everywhere else. Pop a transition `ℓ → ℓ'`,
  * take `transfer(step, I(ℓ'))`, join it into `I(ℓ)` — widening at a loop head —
  * and queue the transitions into `ℓ` if `I(ℓ)` grew. First in, first out, and
  * no transition is queued twice at once.
  *
  * Not trust base: it may be arbitrarily heuristic, because the certifier
  * re-checks its map against `cfg.transitions`. It never abandons a state: it
  * stops only when the worklist empties, or on the first `Incomplete`.
  */
object Worklist:

  def compute[S](
      d: Domain[S],
      resolver: ControlFlowResolver,
      targets: Set[Loc],
      limits: Limits = Limits(),
      recorder: Recorder = NullRecorder,
      clock: () => Long = () => System.currentTimeMillis()
  ): Computed[S] =
    val start = clock()
    val heads = resolver.loopHeads(targets)
    val ordered = targets.toList.sortBy(_.toString)

    def stop(states: Map[Loc, S], why: Option[Incomplete], its: Int, queue: Queue[Transition], widened: Set[Loc]) =
      queue.foreach(recorder.unexplored)
      Computed(states, why, its, queue.size, clock() - start, widened)

    def run(bottom: S, top: S): Computed[S] =
      @tailrec
      def loop(queue: Queue[Transition], queued: Set[Transition], states: Map[Loc, S], its: Int,
          widened: Set[Loc]): Computed[S] =
        val elapsed = clock() - start
        if queue.isEmpty then stop(states, None, its, queue, widened)
        else if its >= limits.iterations then stop(states, Some(Incomplete.IterationLimit(its)), its, queue, widened)
        else if elapsed > limits.deadlineMs then stop(states, Some(Incomplete.Deadline(elapsed)), its, queue, widened)
        else
          val (t, rest) = queue.dequeue
          val waiting = queued - t
          val post = states.getOrElse(t.to, bottom)
          val old = states.getOrElse(t.from, bottom)
          val head = heads(t.from)
          val transferred: Either[Incomplete.DomainFailure, S] = t.step match
            case Step.Skip => Right(post) // the identity; never reaches the domain (§5.3)
            case s =>
              val step = VocabularyConverter.step(s) // outside the guard: a throw here is the engine's
              guard("transfer")(d.transfer(step, post))
          val outcome = for
            contribution <- transferred
            _ = recorder.transferred(t, post, contribution)
            joined <- guard("join")(d.join(old, contribution))
            next <- if head then guard("widen")(d.widen(old, joined)) else Right(joined)
            covered <- guard("entails")(d.entails(next, old))
          yield (next, covered)
          outcome match
            case Left(failure)    => stop(states, Some(failure), its + 1, rest, widened)
            case Right((_, true)) => loop(rest, waiting, states, its + 1, widened)
            case Right((next, false)) =>
              recorder.updated(t.from, next, head)
              val fresh = resolver.into(t.from).filterNot(waiting)
              loop(rest.enqueueAll(fresh), waiting ++ fresh, states.updated(t.from, next), its + 1,
                if head then widened + t.from else widened)

      val initial = ordered.flatMap(resolver.into).distinct
      loop(Queue.from(initial), initial.toSet, ordered.map(_ -> top).toMap, 0, Set.empty)

    (for bottom <- guard("bottom")(d.bottom()); top <- guard("top")(d.top()) yield (bottom, top)) match
      case Left(failure)        => Computed(Map.empty, Some(failure), 0, 0, clock() - start, Set.empty)
      case Right((bottom, top)) => run(bottom, top)

  /** A domain call: any `Throwable`, or a null result, is a `DomainFailure` (§7). */
  private[core] def guard[A](op: String)(call: => A): Either[Incomplete.DomainFailure, A] =
    try
      val a = call
      if a == null then Left(Incomplete.DomainFailure(op, ErrorInfo.of(NullPointerException(s"$op returned null"))))
      else Right(a)
    catch case e: Throwable => Left(Incomplete.DomainFailure(op, ErrorInfo.of(e)))
