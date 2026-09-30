package pag.core

import pag.ir.{BinOp, InvokeKind}

/** A language profile: which source-IR constructs a program may use
  * (implementation_strategy.md §5.2). The lists are how the language grows.
  * Construct names are the IR's case names ("Assign", "IntConst", …).
  */
final case class Profile(
    name: String,
    methods: Set[String],      // user method names; the generated constructor is not counted
    types: Set[String],        // local types, as JType.toString; args and compare temps excepted
    mainArgs: MainArgs,
    reach: ReachIds,
    commands: Set[String],
    lvals: Set[String],
    rvals: Set[String],
    operators: Set[BinOp],
    invokes: Set[InvokeKind],
    callees: Set[String],      // MethodId.qualifiedName
    staticFields: Set[String]  // declaringClass.name
)

/** Whether main may read its String[] parameter (§5.2). */
enum MainArgs:
  case Unread

/** How a probe's reach calls must name their ids (§5.8). */
enum ReachIds:
  /** Each reach id is an int literal, and no id appears twice in a program. */
  case LiteralUnique

object Profile:

  /** v1: single-method, BigInteger-locals Java with one source of input (§5.2).
    * Hard-coded until config parsing arrives in Phase 6.
    */
  val BigintMainV1: Profile = Profile(
    name = "bigint-main-v1",
    methods = Set("main"),
    types = Set("java.math.BigInteger"),
    mainArgs = MainArgs.Unread,
    reach = ReachIds.LiteralUnique,
    commands = Set("Assign", "Goto", "Nop", "Return", "InvokeStmt"),
    lvals = Set("Local"),
    rvals = Set("Local", "IntConst", "BoolConst", "Binop", "Invoke", "StaticField"),
    operators = Set(BinOp.Lt, BinOp.Le, BinOp.Gt, BinOp.Ge, BinOp.Eq, BinOp.Ne),
    invokes = Set(InvokeKind.Static, InvokeKind.Virtual),
    callees = Set(
      "java.math.BigInteger.valueOf",
      "java.math.BigInteger.add", "java.math.BigInteger.subtract",
      "java.math.BigInteger.multiply", "java.math.BigInteger.negate",
      "java.math.BigInteger.compareTo", "java.math.BigInteger.equals",
      "pag.probe.Rand.randInt", "pag.probe.Reach.reach"
    ),
    staticFields = Set(
      "java.math.BigInteger.ZERO", "java.math.BigInteger.ONE",
      "java.math.BigInteger.TWO", "java.math.BigInteger.TEN"
    )
  )
