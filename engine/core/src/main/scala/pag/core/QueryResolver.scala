package pag.core

import pag.ir.Loc
import pag.results.{Query, Reachable}

/** Resolves a query against a lowered program (§6). */
object QueryResolver:

  /** The locations seeded ⊤ for `q` (§7). A set, because a later line-based form
    * resolves to several; `Reachable` resolves to the one `pre` location of its
    * `reach` call. Trust base (§2): seeding the wrong location answers a
    * different question. Two calls sharing an id make the question ambiguous;
    * the profile check rejects that, and this is the backstop (§5.2).
    */
  def resolve(q: Query, lowered: Lowered): Either[String, Set[Loc]] = q match
    case Reachable(id) =>
      lowered.reachSites.get(BigInt(id)) match
        case Some(List(loc)) => Right(Set(loc))
        case Some(locs) if locs.nonEmpty =>
          Left(s"reach($id) appears ${locs.size} times, at ${locs.map(l => s"pre(${l.index})").mkString(", ")}; " +
            "reach ids must be unique (§5.2)")
        case _ =>
          val known = lowered.reachSites.keys.toList.sorted
          Left(
            if known.isEmpty then s"no reach($id) call: the program has no reach calls"
            else s"no reach($id) call; the program has reach(${known.mkString(", ")})"
          )
