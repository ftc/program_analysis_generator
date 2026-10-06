package pag.core

import pag.api.Domain
import pag.ir.{Cfg, Loc, Step, Transition}
import pag.results.Incomplete

/** The outcome of the three checks (implementation_strategy.md §7). */
final case class Certification(
    edges: Int, // transitions checked: all of cfg.transitions
    uncertified: List[Transition], // [edge-inductive] failed on these
    targetsTop: Boolean, // [inductive]
    initBottom: Boolean // [refute]
):
  def refutes: Boolean = uncertified.isEmpty && targetsTop && initBottom

/** The certifier (§7): re-checks a map, however it was produced. Trust base (§2):
  * only a map passing all three checks yields `Refuted`.
  *
  * It enumerates `cfg.transitions` itself and never consults
  * `ControlFlowResolver` or anything else the worklist used: the argument for
  * `Refuted` needs every transition checked, so a bug in the worklist or the
  * resolver can cost a proof but never fake one. A location absent from `states`
  * is ⊥, the same reading the worklist uses.
  */
object Certifier:

  def certify[S](
      d: Domain[S],
      cfg: Cfg,
      targets: Set[Loc],
      states: Map[Loc, S],
      recorder: Recorder = NullRecorder
  ): Either[Incomplete.DomainFailure, Certification] =
    import Worklist.guard
    for
      bottom <- guard("bottom")(d.bottom())
      top <- guard("top")(d.top())
      at = (l: Loc) => states.getOrElse(l, bottom)
      // [edge-inductive]: entails(transfer(step, I(ℓ')), I(ℓ)) for every transition ℓ → ℓ'
      failed <- cfg.transitions.foldLeft[Either[Incomplete.DomainFailure, List[Transition]]](Right(Nil)) {
        (acc, t) =>
          acc.flatMap { failedSoFar =>
            val post = at(t.to)
            val pre: Either[Incomplete.DomainFailure, S] = t.step match
              case Step.Skip => Right(post) // the identity (§5.3)
              case s =>
                val step = VocabularyConverter.step(s) // outside the guard: a throw here is the engine's
                guard("transfer")(d.transfer(step, post))
            for p <- pre; ok <- guard("entails")(d.entails(p, at(t.from)))
            yield if ok then failedSoFar else t :: failedSoFar
          }
      }
      // [inductive]: I(ℓ) is ⊤ at every target, i.e. ⊤ entails it
      targetsTop <- all(targets.toList)(l => guard("entails")(d.entails(top, at(l))))
      // [refute]
      initBottom <- guard("isBottom")(d.isBottom(at(cfg.init)))
    yield
      val uncertified = failed.reverse
      uncertified.foreach(recorder.uncertified)
      Certification(cfg.transitions.size, uncertified, targetsTop, initBottom)

  private def all[A](xs: List[A])(p: A => Either[Incomplete.DomainFailure, Boolean]) =
    xs.foldLeft[Either[Incomplete.DomainFailure, Boolean]](Right(true))((acc, x) => acc.flatMap(b => p(x).map(b && _)))
