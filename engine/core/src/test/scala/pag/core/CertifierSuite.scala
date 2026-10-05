package pag.core

import pag.api
import pag.ir.*
import pag.core.TestDomains.*

/** The certifier (implementation_strategy.md §7): one test per check, on maps
  * written out by hand rather than produced by the worklist.
  */
class CertifierSuite extends munit.FunSuite:

  import Flag.{Maybe, No}

  // 0 —skip→ 1 —x := 1→ 2 —assume(1 < 2)→ 3, the target: reachable
  val reachable: Cfg = cfg(skip(0, 1), assign(1, 2), guardEdge(2, 1, BinOp.Lt, 2, 3))
  // the same with assume(1 > 2): unreachable
  val unreachable: Cfg = cfg(skip(0, 1), assign(1, 2), guardEdge(2, 1, BinOp.Gt, 2, 3))
  val target: Set[Loc] = Set(L(3))

  def certify(g: Cfg, states: Map[Loc, Flag], d: FlagDomain = FlagDomain(), recorder: Recorder = NullRecorder) =
    Certifier.certify(d, g, target, states, recorder).fold(f => fail(s"domain failure $f"), identity)

  // --- All three pass, or not

  test("a certified map of an unreachable target refutes"):
    val c = certify(unreachable, Map(L(3) -> Maybe))
    assertEquals(c, Certification(3, Nil, targetsTop = true, initBottom = true))
    assert(c.refutes)

  test("the fixed point of a reachable target passes [edge-inductive] and [inductive] but not [refute]"):
    val c = certify(reachable, Map(L(0) -> Maybe, L(1) -> Maybe, L(2) -> Maybe, L(3) -> Maybe))
    assertEquals(c, Certification(3, Nil, targetsTop = true, initBottom = false))
    assert(!c.refutes)

  // --- [edge-inductive]

  test("[edge-inductive] names exactly the edges that fail"):
    // I(2) = Maybe but I(1) = ⊥: the assignment 1 → 2 is not covered; 0 → 1 (⊥ to ⊥) is
    val c = certify(reachable, Map(L(2) -> Maybe, L(3) -> Maybe))
    assertEquals(c.uncertified, List(assign(1, 2)))
    assert(!c.refutes)

  test("[edge-inductive] on a skip is I(ℓ') entails I(ℓ)"):
    val c = certify(reachable, Map(L(1) -> Maybe, L(2) -> Maybe, L(3) -> Maybe))
    assertEquals(c.uncertified, List(skip(0, 1)))

  test("every transition is checked, including ones the map never mentions"):
    val extra = cfg(skip(0, 1), assign(1, 2), guardEdge(2, 1, BinOp.Gt, 2, 3), skip(7, 8), skip(8, 9))
    assertEquals(certify(extra, Map(L(3) -> Maybe)).edges, 5)
    // and a failure there is found: I(8) = Maybe is not entailed by I(7) = ⊥
    assertEquals(certify(extra, Map(L(3) -> Maybe, L(8) -> Maybe)).uncertified, List(skip(7, 8)))

  // --- [inductive]

  test("[inductive]: the all-⊥ map passes the other two checks, and must not refute"):
    val c = certify(reachable, Map.empty)
    assertEquals(c, Certification(3, Nil, targetsTop = false, initBottom = true))
    assert(!c.refutes, "without [inductive], ⊥ everywhere would refute every target")

  // --- [refute]

  test("[refute]: a map with init not ⊥ does not refute, however inductive"):
    val c = certify(unreachable, Map(L(0) -> Maybe, L(3) -> Maybe))
    assertEquals((c.uncertified, c.targetsTop, c.initBottom), (Nil, true, false))

  // --- Domain failures and engine bugs

  test("a domain call that throws during certification is a DomainFailure, not an Alarm"):
    val boom = RuntimeException("boom")
    def failing(op: String): FlagDomain = op match
      case "transfer" => new FlagDomain { override def transfer(s: api.Step, p: Flag) = throw boom }
      case "entails"  => new FlagDomain { override def entails(a: Flag, b: Flag) = throw boom }
      case "isBottom" => new FlagDomain { override def isBottom(s: Flag) = throw boom }
      case "top"      => new FlagDomain { override def top = throw boom }
      case "bottom"   => new FlagDomain { override def bottom = throw boom }
    for op <- List("transfer", "entails", "isBottom", "top", "bottom") do
      assertEquals(Certifier.certify(failing(op), unreachable, target, Map(L(3) -> Maybe)).left.toOption: Option[Incomplete],
        Some(Incomplete.DomainFailure(op, boom)): Option[Incomplete], op)

  test("a null from a domain call during certification is a DomainFailure"):
    val d = new FlagDomain { override def transfer(s: api.Step, p: Flag) = null }
    Certifier.certify(d, unreachable, target, Map(L(3) -> Maybe)) match
      case Left(Incomplete.DomainFailure("transfer", _: NullPointerException)) => ()
      case other                                                               => fail(s"got $other")

  test("an engine bug in the converter propagates"):
    val bad = cfg(Transition(L(0), Step.Assign(LVal.StaticField("java.math.BigInteger", "ONE"), RVal.IntConst(1)), L(3)))
    intercept[IllegalStateException](Certifier.certify(FlagDomain(), bad, target, Map(L(3) -> Maybe)))

  // --- The recorder

  test("the recorder hears about every uncertified edge, in CFG order"):
    // A recorder is an observer by design, so a test one must keep what it sees.
    final class Heard extends Recorder:
      val edges = java.util.concurrent.ConcurrentLinkedQueue[Transition]()
      def transferred(t: Transition, post: Any, contribution: Any): Unit = ()
      def updated(loc: Loc, state: Any, widened: Boolean): Unit = ()
      def unexplored(t: Transition): Unit = ()
      def uncertified(t: Transition): Unit = edges.add(t)
    val heard = Heard()
    certify(reachable, Map(L(1) -> Maybe, L(3) -> Maybe), recorder = heard)
    // 2 → 3: Maybe not entailed by I(2) = ⊥; 0 → 1: skip, Maybe not entailed by I(0) = ⊥
    assertEquals(heard.edges.toArray.toList, List(skip(0, 1), guardEdge(2, 1, BinOp.Lt, 2, 3)))
