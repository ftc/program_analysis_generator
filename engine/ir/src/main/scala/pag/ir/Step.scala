package pag.ir

/** One CFG edge's command (implementation_strategy.md §5.3). Domains handle
  * Assign, Assume and Call, through the Java domain vocabulary (§5.4); the
  * engine handles Skip itself as the identity.
  */
enum Step:
  case Assign(target: LVal, source: RVal)
  case Assume(cond: RVal)

  /** A call with no dispatch kind; a receiver, if any, is the first argument. */
  case Call(target: Option[LVal.Local], callee: MethodId, args: List[RVal])

  /** Engine-only: control flow between locations, never passed to a domain. */
  case Skip

/** One CFG edge: `from —step→ to` (§5.3). */
final case class Transition(from: Loc, step: Step, to: Loc)

/** The lowered program a domain analyses (§5.3). */
final case class Cfg(transitions: List[Transition], init: Loc, exit: Loc)
