package pag.core

import pag.api
import pag.api.Domain
import pag.ir.*
import pag.core.TestDomains.*

/** The compute stage (implementation_strategy.md §7) on hand-built CFGs, with
  * small test domains whose fixed points can be worked out by hand.
  */
class WorklistSuite extends munit.FunSuite:

  // --- Test domains

  /** Counts the steps between here and the target, restarting at 1 after ⊤:
    * ever-growing around a loop unless widening jumps to ⊤. 0 is ⊥. Not a
    * sound domain, and not meant to be: it exists to make the worklist climb.
    */
  final case class Count(n: Int)
  val Top: Count = Count(Int.MaxValue)

  class CountDomain extends Domain[Count]:
    def name = "count"
    def top = Top
    def bottom = Count(0)
    def isBottom(s: Count) = s.n == 0
    def entails(a: Count, b: Count) = a.n <= b.n
    def join(a: Count, b: Count) = if a.n >= b.n then a else b
    def widen(a: Count, b: Count) = if b.n > a.n then Top else a
    def transfer(step: api.Step, post: Count) =
      if post.n == 0 then post else if post == Top then Count(1) else Count(post.n + 1)

  // --- CFGs (helpers in TestDomains)

  def run[S](d: Domain[S], g: Cfg, seed: Int, limits: Limits = Limits(), recorder: Recorder = NullRecorder,
      clock: () => Long = () => 0L): Computed[S] =
    Worklist.compute(d, ControlFlowResolver(g), Set(L(seed)), limits, recorder, clock)

  /** 0 → 1 → 2 → 1 (the loop), 2 → 3 (the target): every edge an assignment. */
  val loop: Cfg = cfg(assign(0, 1), assign(1, 2), assign(2, 1), assign(2, 3))

  /** A resolver that never widens. */
  class NoHeads(g: Cfg) extends ControlFlowResolver(g):
    override def loopHeads(seeds: Set[Loc]): Set[Loc] = Set.empty

  // --- The fixed point

  test("straight line: ⊤ at the seed and before it, nothing after"):
    val r = run(FlagDomain(), cfg(skip(0, 1), assign(1, 2), skip(2, 3)), seed = 2)
    assertEquals(r.states, Map(L(0) -> Flag.Maybe, L(1) -> Flag.Maybe, L(2) -> Flag.Maybe))
    assertEquals((r.stopped, r.iterations, r.unexplored, r.widenedAt), (None, 2, 0, Set.empty[Loc]))

  test("an infeasible assume leaves the location before it at ⊥"):
    val r = run(FlagDomain(), cfg(skip(0, 1), guardEdge(1, 1, BinOp.Gt, 2, 2)), seed = 2)
    assertEquals(r.states, Map(L(2) -> Flag.Maybe))
    assertEquals((r.stopped, r.iterations), (None, 1))

  test("a feasible branch and an infeasible one: only the feasible side propagates"):
    // 0 → 1 → (assume 1 < 2) → 3; 0 → 2 → (assume 1 > 2) → 3
    val g = cfg(skip(0, 1), skip(0, 2), guardEdge(1, 1, BinOp.Lt, 2, 3), guardEdge(2, 1, BinOp.Gt, 2, 3))
    assertEquals(run(FlagDomain(), g, seed = 3).states, Map(L(0) -> Flag.Maybe, L(1) -> Flag.Maybe, L(3) -> Flag.Maybe))

  test("one iteration is one transition, and a diamond processes each edge once"):
    // 0 → 1 → 3, 0 → 2 → 3: four transitions, four iterations
    val r = run(FlagDomain(), cfg(skip(0, 1), skip(0, 2), skip(1, 3), skip(2, 3)), seed = 3)
    assertEquals((r.stopped, r.iterations), (None, 4))

  test("a transition already queued is not queued again"):
    // 5 → 0; 0 → 1 → 3 (skip); 0 → 2 → 3 (assign). Queue [1→3, 2→3, then 0→1, 0→2]: L0 grows to 1
    // via 0→1, queueing 5→0, then to 2 via 0→2 while 5→0 still waits. Five transitions, five iterations;
    // queueing 5→0 twice would make six.
    val g = cfg(assign(5, 0), assign(0, 1), assign(0, 2), skip(1, 3), assign(2, 3))
    val r = run(CountDomain(), g, seed = 3)
    assertEquals(r.states(L(0)), Count(2))
    assertEquals((r.stopped, r.iterations), (None, 5))

  test("a loop terminates by widening at its head"):
    val r = run(CountDomain(), loop, seed = 3)
    assertEquals(r.stopped, None)
    assertEquals(r.widenedAt, ControlFlowResolver(loop).loopHeads(Set(L(3))))
    assert(r.widenedAt.nonEmpty)
    assert(r.widenedAt.forall(h => r.states(h) == Top), r.states)

  test("skip is the identity and never reaches the domain"):
    val d = new FlagDomain:
      override def transfer(step: api.Step, post: Flag) = throw AssertionError("transfer on a skip")
    val r = run(d, cfg(skip(0, 1), skip(1, 2)), seed = 2)
    assertEquals((r.stopped, r.states.size), (None, 3))

  // --- Stopping

  test("without widening the loop never settles: the iteration limit stops it"):
    val r = Worklist.compute(CountDomain(), NoHeads(loop), Set(L(3)), Limits(iterations = 50))
    assertEquals(r.stopped, Some(Incomplete.IterationLimit(50)))
    assertEquals(r.iterations, 50)
    assert(r.unexplored > 0, "a stopped search leaves work queued")
    assertEquals(r.widenedAt, Set.empty[Loc])

  test("the deadline stops the search between iterations"):
    // a clock that advances 1000 ms per reading; readings: start 0, then 1000, 2000, 3000 > 2500
    val readings = Iterator.iterate(0L)(_ + 1000)
    val chain = cfg((0 until 10).map(k => skip(k, k + 1))*)
    val r = run(FlagDomain(), chain, seed = 10, Limits(deadlineMs = 2500), clock = () => readings.next())
    assertEquals(r.stopped, Some(Incomplete.Deadline(3000)))
    assertEquals(r.iterations, 2)

  // --- Domain failures

  test("transfer throws: DomainFailure carries the op and the exception itself"):
    val boom = RuntimeException("boom")
    val d = new FlagDomain:
      override def transfer(step: api.Step, post: Flag) = throw boom
    val r = run(d, cfg(skip(0, 1), assign(1, 2)), seed = 2)
    assertEquals(r.stopped, Some(Incomplete.DomainFailure("transfer", boom)))
    assertEquals(r.states, Map(L(2) -> Flag.Maybe), "what was computed before the failure is kept")
    assertEquals(r.iterations, 1)

  test("join returns null: DomainFailure with a NullPointerException naming join"):
    val d = new FlagDomain:
      override def join(a: Flag, b: Flag) = null
    run(d, cfg(skip(0, 1)), seed = 1).stopped match
      case Some(Incomplete.DomainFailure("join", e: NullPointerException)) => assertEquals(e.getMessage, "join returned null")
      case other                                                           => fail(s"got $other")

  test("widen recurses without end: the StackOverflowError is a DomainFailure"):
    def forever(n: Int): Int = forever(n + 1) + 1
    val d = new CountDomain:
      override def widen(a: Count, b: Count) = Count(forever(0))
    run(d, loop, seed = 3).stopped match
      case Some(Incomplete.DomainFailure("widen", _: StackOverflowError)) => ()
      case other                                                          => fail(s"got $other")

  test("entails runs out of memory: the OutOfMemoryError is a DomainFailure"):
    val oom = OutOfMemoryError("simulated") // a real one would starve the test JVM
    val d = new FlagDomain:
      override def entails(a: Flag, b: Flag) = throw oom
    assertEquals(run(d, cfg(skip(0, 1)), seed = 1).stopped, Some(Incomplete.DomainFailure("entails", oom)))

  test("bottom throws: the search never starts"):
    val boom = IllegalStateException("no bottom")
    val d = new FlagDomain:
      override def bottom = throw boom
    val r = run(d, cfg(skip(0, 1)), seed = 1)
    assertEquals((r.stopped, r.states, r.iterations), (Some(Incomplete.DomainFailure("bottom", boom)), Map.empty, 0))

  test("an engine bug is not the domain's: the converter's exception propagates"):
    val bad = Transition(L(0), Step.Assign(LVal.StaticField("java.math.BigInteger", "ONE"), RVal.IntConst(1)), L(1))
    intercept[IllegalStateException](run(FlagDomain(), cfg(bad), seed = 1))

  // --- The recorder

  test("the recorder sees every update and every unexplored transition"):
    // A recorder is an observer by design, so a test one must keep what it sees.
    final class Tally extends Recorder:
      val updates = java.util.concurrent.atomic.AtomicInteger()
      val widenings = java.util.concurrent.atomic.AtomicInteger()
      val unexplored = java.util.concurrent.atomic.AtomicInteger()
      def transferred(t: Transition, post: Any, contribution: Any): Unit = ()
      def updated(loc: Loc, state: Any, widened: Boolean): Unit =
        updates.incrementAndGet(); if widened then widenings.incrementAndGet()
      def unexplored(t: Transition): Unit = unexplored.incrementAndGet()
      def uncertified(t: Transition): Unit = ()
    val settled = Tally()
    val r = run(CountDomain(), loop, seed = 3, recorder = settled)
    assert(settled.updates.get > 0 && settled.widenings.get > 0)
    assertEquals(settled.unexplored.get, r.unexplored)
    val stopped = Tally()
    val s = Worklist.compute(CountDomain(), NoHeads(loop), Set(L(3)), Limits(iterations = 20), stopped)
    assertEquals(stopped.unexplored.get, s.unexplored)
    assertEquals(stopped.widenings.get, 0)
