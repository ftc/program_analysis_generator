package pag.campaign

import io.bullet.borer.Codec
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

import pag.results.{CheckResult, Envelope}
import pag.results.Codecs.given
import pag.campaign.ChatClient.given // ChatMessage's codec, defined once

/** Everything one attempt produced (experiments.md E1), written as
  * `results/<campaign>/<attempt>/attempt.json`. Record types have no default
  * values: Borer leaves a field equal to its default out of the encoding, so a
  * record could silently lose it. Config types (`AgentConfig`) have defaults for
  * convenience, which is why the agent is copied into `AgentRecord` here.
  */
final case class AttemptRecord(
    envelope: Envelope,
    campaign: String,
    attempt: String,
    sample: Int,
    startedAt: String, // ISO-8601, UTC
    elapsedMs: Long,
    agent: AgentRecord,
    serverModels: Option[String], // the server's own /v1/models report, raw
    prompt: PromptRecord,
    reply: Option[ChatReply], // content, reasoning, usage, raw response
    failure: Option[ChatFailure], // when there is no reply
    files: Option[FilesRecord],
    build: Option[BuildRecord],
    targets: List[TargetRecord],
    summary: Summary
)

final case class AgentRecord(
    baseUrl: String,
    model: String,
    apiKeyEnv: Option[String],
    temperature: Double,
    maxTokens: Option[Int],
    timeoutSeconds: Int,
    retries: Int,
    source: SourceRecord
)

final case class SourceRecord(url: Option[String], file: Option[String], revision: Option[String], sha256: Option[String])

object AgentRecord:
  def of(a: AgentConfig): AgentRecord =
    AgentRecord(a.baseUrl, a.model, a.apiKeyEnv, a.temperature, a.maxTokens, a.timeoutSeconds, a.retries,
      SourceRecord(a.source.url, a.source.file, a.source.revision, a.source.sha256))

/** The full messages sent, not just their identity (implementation_strategy.md Phase 10). */
final case class PromptRecord(version: String, sha256: String, messages: List[ChatMessage])

final case class FilesRecord(written: List[String], ignoredBlocks: Int, problems: List[String])

final case class BuildRecord(
    compiled: Boolean,
    compileLog: String,
    testsRun: Int,
    testFailures: Int,
    testErrors: Int,
    testLog: String,
    elapsedMs: Long
)

final case class TargetRecord(
    probe: String,
    reach: Int,
    reachable: Boolean,
    rung: Int,
    cell: String,
    exitCode: Option[Int],
    check: Option[CheckResult],
    stderr: String,
    timedOut: Boolean,
    elapsedMs: Long
)

/** Table 1's row, before inspection (experiments.md). */
final case class Summary(
    files: Int,
    builds: Boolean,
    testsRun: Int,
    testsFailed: Int, // failures plus errors
    loads: Boolean,
    cells: List[String], // one per target, in corpus order
    proved: Int,
    unsound: Boolean
)

object AttemptRecord:
  given Codec[SourceRecord] = deriveCodec[SourceRecord]
  given Codec[AgentRecord] = deriveCodec[AgentRecord]
  given Codec[PromptRecord] = deriveCodec[PromptRecord]
  given Codec[ChatReply] = deriveCodec[ChatReply]
  given Codec[ChatFailure] = deriveCodec[ChatFailure]
  given Codec[FilesRecord] = deriveCodec[FilesRecord]
  given Codec[BuildRecord] = deriveCodec[BuildRecord]
  given Codec[TargetRecord] = deriveCodec[TargetRecord]
  given Codec[Summary] = deriveCodec[Summary]
  given Codec[AttemptRecord] = deriveCodec[AttemptRecord]
