package pag.campaign

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}

import io.bullet.borer.{Dom, Json}

/** One llama.cpp slot, as `/slots` reports it. */
final case class Slot(id: Int, busy: Boolean, decoded: Long, promptTokens: Long, contextSize: Long)

object Slots:

  /** llama.cpp's `/slots`, which lives beside `/v1` rather than under it. */
  def fetch(baseUrl: String): Either[String, List[Slot]] =
    val root = baseUrl.stripSuffix("/").stripSuffix("/v1")
    try
      val response = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(s"$root/slots")).timeout(java.time.Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      if response.statusCode != 200 then Left(s"/slots: HTTP ${response.statusCode}") else parse(response.body)
    catch case e: java.io.IOException => Left(s"/slots: no response ($e)")

  def parse(body: String): Either[String, List[Slot]] =
    Json.decode(body.getBytes("UTF-8")).to[Dom.Element].valueEither.left.map(_.getMessage).flatMap {
      case a: Dom.ArrayElem =>
        Right(a.elems.toList.collect { case m: Dom.MapElem =>
          val f = m.toMap.collect { case (Dom.StringElem(k), v) => k -> v }
          def num(e: Option[Dom.Element]): Long = e.collect { case Dom.IntElem(n) => n.toLong; case Dom.LongElem(n) => n }
            .getOrElse(0L)
          val next = f.get("next_token").flatMap {
            case arr: Dom.ArrayElem => arr.elems.headOption
            case other              => Some(other)
          }.collect { case n: Dom.MapElem => n.toMap.collect { case (Dom.StringElem(k), v) => k -> v } }
          Slot(num(f.get("id")).toInt, f.get("is_processing").contains(Dom.BooleanElem.True),
            num(next.flatMap(_.get("n_decoded"))), num(f.get("n_prompt_tokens")), num(f.get("n_ctx")))
        })
      case _ => Left("/slots: not a list")
    }

/** One reading of the busy slot's token count, kept across refreshes for the rate. */
final case class Reading(at: Instant, decoded: Long)

/** The `campaign status` screen (implementation_strategy.md Phase 10): a pure
  * function of what is on disk, what the server reports, and earlier readings.
  */
object StatusView:

  val StallSeconds: Long = 60
  val SameFailureRun: Int = 3

  def render(
      now: Instant,
      campaign: String,
      status: Option[Status],
      attempts: List[AttemptRecord],
      slots: Either[String, List[Slot]],
      history: List[Reading] // oldest first
  ): List[String] =
    val requested = status.map(_.samplesRequested)
    val done = attempts.size
    val busy = slots.toOption.toList.flatten.filter(_.busy)
    val avg = Option.when(done > 0)(attempts.map(_.elapsedMs).sum / done)
    val current = status.flatMap(s => s.attempt.map(a => (s, a)))
    val currentElapsed = current.flatMap(_._1.attemptStartedAt).map(t => Duration.between(Instant.parse(t), now))
    val eta = for
      r <- requested; a <- avg
      remaining = (r - done).max(0)
      if remaining > 0
      left = Duration.ofMillis(a * remaining).minus(currentElapsed.getOrElse(Duration.ZERO))
    yield if left.isNegative then Duration.ZERO else left

    val progress = List(
      s"campaign  $campaign   ${requested.fold(s"$done finished")(r => s"$done of $r finished")}" +
        status.fold("   (no status.json: not started, or from before campaign status)")(s =>
          if s.running then "" else "   (run finished)"),
      current.fold(s"now       ${status.fold("—")(_.stage)}") { (s, a) =>
        s"now       $a: ${s.stage}, for ${clock(Duration.between(Instant.parse(s.stageStartedAt), now))}" +
          currentElapsed.fold("")(e => s"  (attempt ${clock(e)} so far)")
      },
      s"timing    ${avg.fold("no attempt finished yet")(a => s"${clock(Duration.ofMillis(a))} per attempt")}" +
        eta.fold("")(e => s",  about ${clock(e)} to go")
    )

    val asking = current.exists(_._1.stage == "asking the model")
    val (live, liveWarnings) = slots match
      case Left(e) => (List(s"model     $e"), Nil)
      case Right(_) if busy.isEmpty => (List("model     no slot generating"), Nil)
      case Right(_) =>
        val s = busy.maxBy(_.decoded)
        val recent = history.filter(r => Duration.between(r.at, now).getSeconds <= 60)
        val rate = (recent.headOption, recent.lastOption) match
          case (Some(a), Some(b)) if Duration.between(a.at, b.at).toMillis > 0 =>
            Some((b.decoded - a.decoded) * 1000.0 / Duration.between(a.at, b.at).toMillis)
          case _ => None
        // stalled: the count has not changed for StallSeconds, over a history at least that long
        val lastChangeAt = history.zip(history.drop(1)).collect { case (a, b) if b.decoded != a.decoded => b.at }
          .lastOption.orElse(history.headOption.map(_.at))
        val stalled = history.headOption.exists(h => Duration.between(h.at, now).getSeconds >= StallSeconds) &&
          lastChangeAt.exists(t => Duration.between(t, now).getSeconds >= StallSeconds)
        val timeoutLeft = current.filter(_ => asking).map((st, _) =>
          st.timeoutSeconds - Duration.between(Instant.parse(st.stageStartedAt), now).getSeconds)
        val contextLeft = Option.when(s.contextSize > 0)(s.contextSize - s.promptTokens - s.decoded)
        val lines = List(
          s"model     slot ${s.id}: ${s.decoded} tokens generated" + rate.fold("")(r => f",  $r%.0f tokens/s over the last minute") +
            s",  prompt ${s.promptTokens} tokens",
          "          " + List(
            timeoutLeft.map(t => s"${clock(Duration.ofSeconds(t.max(0)))} before the client's timeout"),
            contextLeft.map(c => s"$c tokens of context left")
          ).flatten.mkString(",  ")
        ) ++ Option.when(busy.size > 1)(s"          ${busy.size - 1} other slot(s) busy: someone else is using the server")
        val warnings = List(
          Option.when(stalled)(s"generation stalled: no new tokens for over $StallSeconds s"),
          timeoutLeft.filter(t => t < (current.fold(0)(_._1.timeoutSeconds) / 4)).map(t =>
            s"generation near the client's timeout (${clock(Duration.ofSeconds(t.max(0)))} left)"),
          contextLeft.filter(c => c < s.contextSize / 10).map(c => s"context nearly full ($c tokens left)")
        ).flatten
        (lines, warnings)

    val rows = attempts.sortBy(_.attempt).map { r =>
      f"  ${r.attempt}%-12s ${outcome(r)}%-34s ${r.summary.cells.mkString(" ")}%-16s proved ${r.summary.proved}" +
        f"  ${clock(Duration.ofMillis(r.elapsedMs))}%8s"
    }
    val failures = attempts.sortBy(_.attempt).map(outcome).filterNot(_ == "evaluated")
    val streak = attempts.sortBy(_.attempt).reverse.map(outcome).takeWhile(o => o != "evaluated")
    val warnings = liveWarnings ++ List(
      Option.when(streak.size >= SameFailureRun && streak.take(SameFailureRun).distinct.size == 1)(
        s"the last ${streak.size} attempts all failed the same way (${streak.head}): check the prompt and reply format"),
      Option.when(attempts.exists(_.summary.unsound))(
        s"unsound: ${attempts.filter(_.summary.unsound).map(_.attempt).mkString(", ")} refuted a reachable target"),
      Option.when(attempts.exists(_.summary.cells.contains("H")))(
        s"a pag check was killed (H) in ${attempts.filter(_.summary.cells.contains("H")).map(_.attempt).mkString(", ")}")
    ).flatten

    progress ++ List("") ++ live ++ List("", s"finished  ${if rows.isEmpty then "none yet" else ""}") ++ rows ++
      (if failures.isEmpty then Nil else List("", "failures  " + failures.groupBy(identity).toList.sortBy(-_._2.size)
        .map((k, v) => s"$k ×${v.size}").mkString(",  "))) ++
      List("", if warnings.isEmpty then "warnings  none" else "WARNINGS") ++ warnings.map("  ! " + _)

  /** What happened to an attempt, in a few words; "evaluated" when it reached the corpus. */
  def outcome(r: AttemptRecord): String =
    if r.reply.isEmpty then r.failure.fold("no reply")(f => if f.message.contains("not retried") then "timed out" else "no reply")
    else if r.summary.outOfTokens && !(r.summary.builds && r.summary.loads) then "ran out of tokens"
    else if r.summary.files == 0 then "no files in the reply"
    else if !r.summary.builds then "did not compile"
    else if !r.summary.loads then "did not load"
    else "evaluated"

  def clock(d: Duration): String =
    val s = d.getSeconds.max(0)
    if s >= 3600 then f"${s / 3600}%dh${s % 3600 / 60}%02dm" else f"${s / 60}%dm${s % 60}%02ds"
