package pag.core

import pag.ir.{Cfg, Loc, Transition}

/** Answers "what comes before here" (implementation_strategy.md §7), as
  * Historia's class of the same name does. v1 asks only what the `Cfg` alone
  * can answer; call targets come through a front-end interface once there are
  * calls.
  *
  * Not trust base: the worklist uses it to choose what to do next and where to
  * widen, and the certifier checks the result against the `Cfg` itself. A wrong
  * answer here costs precision or termination, never soundness.
  *
  * Not final: Phase 4's tests substitute a broken resolver to show that it
  * still cannot produce `Refuted`.
  */
class ControlFlowResolver(cfg: Cfg):

  private val byTarget: Map[Loc, List[Transition]] = cfg.transitions.groupBy(_.to)
  private val bySource: Map[Loc, List[Transition]] = cfg.transitions.groupBy(_.from)

  /** Transitions ending at `loc`: what may execute just before it. Backward, these
    * are the edges whose `from` state must be recomputed when `loc`'s changes.
    */
  def into(loc: Loc): List[Transition] = byTarget.getOrElse(loc, Nil)

  /** Transitions leaving `loc`: backward, the edges that contribute to `loc`'s state. */
  def outOf(loc: Loc): List[Transition] = bySource.getOrElse(loc, Nil)

  /** Where the worklist widens: the targets of back edges in a depth-first search
    * from `seeds` over the backward graph (following `into`). Every cycle the
    * search can reach contains a back edge, so every loop that can lead to a
    * seed has a head. Deterministic, since `Cfg.transitions` is ordered.
    *
    * Recursive, one frame per location on the current search path.
    */
  def loopHeads(seeds: Set[Loc]): Set[Loc] =
    /** (visited, heads) after searching from `loc`, with `onPath` the locations above it. */
    def search(loc: Loc, onPath: Set[Loc], visited: Set[Loc], heads: Set[Loc]): (Set[Loc], Set[Loc]) =
      into(loc).map(_.from).foldLeft((visited + loc, heads)) { case ((vis, hs), before) =>
        if onPath(before) || before == loc then (vis, hs + before) // a back edge: `before` heads a loop
        else if vis(before) then (vis, hs)
        else search(before, onPath + loc, vis, hs)
      }
    seeds.toList.sortBy(_.toString).foldLeft((Set.empty[Loc], Set.empty[Loc])) { case ((vis, hs), seed) =>
      if vis(seed) then (vis, hs) else search(seed, Set.empty, vis, hs)
    }._2
