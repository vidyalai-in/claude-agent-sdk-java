# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.24] - 2026-08-19

### Added
- **`ResultException` carries the error result's payload** (Python SDK v0.2.140, PR #1205): the CLI ends a failed run by emitting a `result` message with `is_error: true` and *then* exiting non-zero. The SDK already replaced the bare "exit code 1" `ProcessException` with the result's error text, but the text was all the caller got — branching on *why* a run failed meant string-matching it. That replacement is now a **`ResultException`**, a `ProcessException` subclass (so existing `catch (ProcessException e)` handlers are unaffected) exposing `subtype()`, `errors()`, `result()`, `apiErrorStatus()`, `terminalReason()`, `sessionId()` and the raw `data()`, with the original exit error as its `getCause()`. The message-stream iterator now rethrows the exception the reader actually failed with instead of flattening it to a `ClaudeSDKException(String)`, so a `CLIConnectionException` or `CLIJSONDecodeException` keeps its type and payload too. You normally meet it as `QueryFailedException.getCause()` — the collecting `ClaudeSDK.query(...)` wraps it so the messages already received are not lost; it is thrown directly only by a failed control request, such as an `initialize` the CLI refuses at startup. `ClaudeSDKClient.receiveResponse()` does not throw it: that iterator stops at the `ResultMessage` (exactly as Python's `receive_response()` does), so check `ResultMessage.isError()` there instead.
- **`forwardSubagentText` option** (Python SDK v0.2.140, PR #1206): a subagent spawned through the Agent tool only surfaces its `tool_use` / `tool_result` blocks in the parent stream — enough for a progress heartbeat, not enough to render what it said. `ClaudeAgentOptions.builder().forwardSubagentText(true)` asks the CLI to forward the subagent's text and thinking blocks the same way, as `AssistantMessage` objects whose `parentToolUseId` is the spawning Agent `tool_use` id. Sent on the `initialize` control request and only when enabled, so older CLIs see the request unchanged. New `ForwardSubagentTextExample` runs the same prompt with the option off and on, and `feature-agents.md` gains an "Observing a subagent's output" section.
- **`SessionMessage.parentAgentId()`**, and `parentToolUseId()` is now populated for subagent reads (Python SDK v0.2.140, PR #1207): a subagent's transcript lines do not record which Agent `tool_use` spawned it — that lives in the `agent-<id>.meta.json` sidecar next to the transcript (and in the `agent_metadata` entry a `SessionStore` receives in its place). `getSubagentMessages()` and `getSubagentMessagesFromStore()` now read it, so every message they return is attributable to the spawning tool call, with `parentAgentId` naming the spawning subagent for nested subagents. A missing, corrupt, non-object, or non-string-valued sidecar degrades to null rather than failing the read. `SessionMessage` gains a backwards-compatible constructor without the new field, and `SubagentTranscriptExample` now reports which Agent `tool_use` spawned each transcript it prints.

### Changed
- **`canUseTool` works with string prompts** (Python SDK v0.2.140, PR #1204): `ClaudeSDK.query(String, options)` rejected a `canUseTool` callback with "requires streaming mode". That restriction never reflected the transport: a string prompt is written over stdin as a single streamed message like any other, so the control protocol — and therefore the callback — was available all along. The `IllegalArgumentException` is gone; the callback and `permissionPromptToolName` remain mutually exclusive. The validation is now shared by `query()` and `ClaudeSDKClient.connect()` instead of being duplicated with a divergent set of checks. `PermissionCallbacks` gains a section demonstrating the one-shot form; it disables setting sources, because an allow rule in the user's `settings.json` (a bare `Write(*)` is enough) shadows the callback just like an `allowedTools` entry does, and the shadowing advisory cannot see settings-file rules to warn about it.
- **A typed control-request failure is no longer wrapped** (same PR family): `initialize()` and `sendControlRequest()` wrapped every failure in `ClaudeSDKException("Failed to initialize: …")` / `("Control request failed: …")`, which flattened the `ResultException` built for a refused resume back into an opaque base exception. An already-typed `ClaudeSDKException` now propagates as-is (matching Python's `raise result`); opaque failures still get the wrapper.

### Fixed
- **`canUseTool` alone no longer closes stdin early** (Python SDK v0.2.140, PR #1204): permission prompts are served over the control protocol exactly like hooks and SDK MCP servers — the CLI blocks on a `control_response` arriving over stdin. `streamInput()` held stdin open for hooks and SDK MCP servers but not for a `canUseTool` callback, so a run configured with only a callback closed stdin at end of input and every later permission request failed CLI-side with "Stream closed". The callback now counts as a bidirectional need.
- **A failing prompt iterator no longer hangs the run** (same PR): if the caller's `Iterator` threw, `streamInput()` skipped `endInput()` entirely — the CLI then waited for input that would never come and the consumer's iteration never finished. The close now happens on that path too. Conversely, when *nothing* was written there is no result to wait for, so stdin closes immediately instead of waiting out the stream-close timeout.
- **A stray `type` key in a subagent's `.meta.json` no longer shadows the `agent_metadata` marker** (Python SDK v0.2.140, PR #1207): `importSessionToStore()` built the store entry by writing the synthetic `"type": "agent_metadata"` discriminator *first* and then merging the CLI-owned sidecar over it, so a sidecar carrying its own `type` silently replaced the marker — the entry was then read back as a transcript line. The discriminator is written last. In the same path, a corrupt or non-object sidecar aborted the whole import with an `IOException`; it is now treated as absent, and the transcript still imports.
- **A malformed `errors` field no longer rejects the whole result frame**: `MessageParser` cast `errors` to `List<String>` unchecked, so a bare string (or any other shape) from an older or buggy emitter raised `MessageParseException` and the caller lost the result entirely. A bare string is now kept as a single-element list and any other shape is ignored, matching Python, which does no type check on this field. The error text built from the field tolerates the same shapes, and drops blank entries so the text and `ResultException.errors()` always agree.
- **An API failure no longer reports itself as "success"**: a run that ends on an API error arrives as `subtype: "success"` with `is_error: true`, an empty `errors[]` and the prose in `result` — which produced the self-contradictory `Claude Code returned an error result: success`. The text is now picked as `errors[]`, then `result`, then a non-`success` `subtype`, then `API error (HTTP <status>)`.

### Notes
- Python SDK v0.2.139 is a bundled-CLI version bump only (2.1.233). v0.2.140 also bumps the bundled CLI to 2.1.235. The Java SDK resolves the CLI from `PATH` rather than bundling it, so there is nothing to track.
- **Known divergence, unchanged by this release:** the in-process SDK MCP server reports a failed `tools/call` (unknown tool, handler exception) as a JSON-RPC error, where Python returns a successful response with `isError: true`, and Java does not validate arguments against a tool's declared input schema before invoking the handler. This pre-dates the sync — Python inherited that behavior from the `mcp` library's `@call_tool` decorator, and PR #1218 only moved it into SDK code so both mcp majors match. Tracked in `docs/PYTHON_SDK_PARITY.md` (§7, Known Behavioral Divergences).
- Python SDK PR #1218 ("Support mcp 2.x alongside 1.x for in-process SDK MCP servers") is **not applicable**: it makes the Python SDK work against both major versions of the `mcp` PyPI package by delegating JSON-RPC dispatch to that library's in-memory transport. The Java SDK's `SdkMcpServer` is self-contained — it has no third-party MCP library to be compatible with — so there is no equivalent change.

### Synced
- Python SDK v0.2.138 → v0.2.140 (commits 961aff8c..a4eaba4a)

[0.1.24]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.24

## [0.1.23] - 2026-08-14

### Added
- **`ConversationResetMessage` for the CLI's `conversation_reset` frame** (Python SDK v0.2.137, PR #1196): in streaming-input mode one connection carries many user turns, and a `/clear` — or any other flow that discards the transcript mid-session — resets the conversation *and* zeroes the running totals reported on subsequent `ResultMessage` objects. The CLI announces this with a top-level `conversation_reset` frame, which `MessageParser` dropped through its forward-compatibility fallthrough, so applications never saw resets they did not initiate and had no signal to snapshot totals before they zero. The frame now parses into a `ConversationResetMessage` record (`newConversationId`, `uuid`, `sessionId`). Note that `newConversationId` is a UI key for the fresh transcript, *not* the `sessionId` of subsequent messages — read that from the next message. **Breaking:** this widens the sealed `Message` interface, so an exhaustive `switch` over `Message` with no `default` no longer compiles until a `ConversationResetMessage` case is added. Code that raises on unrecognised message classes will now also see a frame that was previously dropped silently.
- **Message origin on `UserMessage` and `ResultMessage`** (Python SDK v0.2.137, PR #1199): in streaming-input mode one connection interleaves the turns the application sends with turns the session injects on its own — background-task notifications, fired scheduled-task prompts, MCP channel messages, messages relayed from peer sessions. The CLI attributes these with an `origin` object on user messages and forwards the triggering message's origin on each result; the parser dropped the field entirely, so a consumer could not tell "this result answers my prompt" from a task-notification follow-up. New `origin()` accessor on both types, typed as `MessageOrigin` with the per-kind fields (`server`, `from`, `name`, `fromSession`, `senderTaskId`, `body`, `verifiedPeerPid`, `subkind`) plus `MessageOriginKind` and `TaskNotificationOriginSubkind` enums. Null means the CLI did not attribute the message — prompts sent through `query()` arrive that way unless the host stamps `"origin": {"kind": "human"}` on the message map itself (only the `human` kind is honored from an SDK host). Both records gain a backwards-compatible constructor without the new field.

  Where Python passes the CLI's dict through verbatim as a `TypedDict`, Java models the documented keys and keeps the full object on `raw()`, following `RateLimitInfo`/`ModelUsage`. A kind or sub-kind newer than this SDK models leaves the enum null rather than rejecting the frame; the wire string stays readable via `kindValue()` / `raw()`, and `isHuman()` is false for it — matching Python's "treat anything unrecognized as not human".
- **`resumeSessionAt` / `resumeDropsTurn` for truncating resume** (Python SDK v0.2.137, PR #1198): `resumeSessionAt` loads a resumed conversation only up to and including a given transcript-entry UUID, branching from an earlier point (pair with `forkSession`). `resumeDropsTurn` makes that truncation safe: given the UUID of the user prompt whose turn you intend to discard, the CLI validates at load time that every entry past the fork point belongs to that turn and refuses otherwise — so a queued user message or task notification the session absorbed mid-turn, which the caller never observed, is not silently discarded. A refusal surfaces as an exception whose message contains `Resume rejected by --resume-drops-turn:`; treat it as deterministic and resume plainly rather than retrying. Both are emitted in `--flag=value` form with the same Windows cmd-metacharacter rejection as `resume`/`sessionId`. `resumeDropsTurn` is forwarded whenever non-null — an empty string reaches the CLI and is rejected there as malformed, rather than being dropped by the SDK and silently disarming a guard the caller believes is armed. No SDK-side validation of the option combination is added; like the TypeScript SDK this defers to the CLI.

### Fixed
- **Seed user `settings.json` into the temp config dir on `SessionStore` resume** (Python SDK v0.2.137, PR #1197): on a `SessionStore`-backed resume the SDK runs the CLI under a temporary `CLAUDE_CONFIG_DIR` seeded from the caller's real one. The seed was `.credentials.json` (refresh token redacted) and `.claude.json` only — user `settings.json` was left behind, and with it `apiKeyHelper` (a fourth auth mechanism alongside the credentials file, the macOS Keychain and env vars) plus the user's `env`, `hooks` and `permissions`. A host authenticating solely via `apiKeyHelper` therefore failed with **"Not logged in"** the moment it resumed from the store, with nothing in the error pointing at why. `settings.json` and `cowork_settings.json` are now copied through a transform that drops only the keys that misbehave under a redirected config dir: `enabledPlugins` / `extraKnownMarketplaces` (which would reconcile against the always-empty temp plugin cache and network-install every declared marketplace on each resume) and `env.CLAUDE_CONFIG_DIR` (which would point the subprocess's config reads away from the temp dir). A UTF-8 BOM (PowerShell-written settings) is tolerated; content that is not valid UTF-8, or does not parse as a JSON object, is copied byte-for-byte; output is written owner-only inside the owner-only temp dir.
- **An unreadable seed file no longer aborts a resume that would otherwise succeed** (same PR): the seed files are best-effort enrichment, so anything other than "missing" — a permissions error, or a directory or FIFO where a file was expected — is now logged and skipped rather than propagated. A FIFO in particular would have blocked the read forever. A copy that fails midway removes the partial destination so the subprocess cannot misparse a truncated file.
- **`ClaudeSDKClient.query(Iterator)` no longer ends the session** (Java-side parity fix, found while validating the examples for this release): the overload handed its iterator to `QueryHandler.streamInput()`, which closes the CLI's stdin once the iterator is exhausted — that is correct for the one-shot `ClaudeSDK.query()` API it was written for, but on a live client it ended the session. The CLI exited, and the next `query()`/`sendMessage()` failed with `ProcessTransport is not ready for writing`, so the only API that can send a raw message map was effectively single-shot. Since a raw map is the only way to stamp `origin`, send structured content blocks, or set an explicit `uuid`, this made those features unusable beyond the first turn. The client now writes each message straight to the transport and leaves stdin open, matching Python's `ClaudeSDKClient.query()`; the one-shot path still uses `streamInput()` and is unchanged. Messages are written before the call returns, so successive calls stay ordered — drive a lazy or unbounded iterator from your own thread. **Also:** `session_id` is now filled in with `"default"` on any streamed message that omits it (Python does the same), a new `query(Iterator, String sessionId)` overload takes the session explicitly, and the caller's maps are copied rather than mutated when the field is added. With the background submit gone, the client's per-connection streaming `ExecutorService` — created on every `connect()` and torn down on every `disconnect()` — had no remaining use and was removed along with its shutdown path; internal only, no API change.
- **A collecting query no longer discards the messages it already gathered** (Java-side parity fix, found while running the examples for this release): the CLI reports `error_max_turns` and `error_max_budget_usd` by emitting a *complete* turn — assistant messages plus a final `ResultMessage` carrying the subtype, cost and usage — and only then exiting non-zero. `ClaudeSDK.query(...)` collected those messages into a list and then rethrew bare from the iterator, so the list was dropped on the floor and the caller was left with an error string. A streaming consumer never had this problem (`receiveMessages()`/`receiveResponse()` hand over each message and raise only at the end), and neither does Python, whose `query()` is a generator that yields everything before raising. The collecting API must either return or throw, so it now throws the new **`QueryFailedException`** — a `ClaudeSDKException` subclass, so existing `catch` blocks are unaffected — carrying `partialMessages()` plus a `resultMessage()` convenience accessor for the final result. This makes `maxTurns`/`maxBudgetUsd` genuinely usable: reaching a cap you set yourself is an expected outcome, and you can now see what was spent getting there. `MaxBudgetExample` was rewritten around it (it had been dying at its second section, never reaching the "tight budget" demo it exists to show).
- **A refused resume reports why, not just "exit code 1"** (Python SDK v0.2.137, PR #1198): the CLI reports a refused resume as an error result on stdout followed by a non-zero exit, *before* it answers the SDK's `initialize` request. The read loop already replaced the bare `ProcessException` with the result's error text for the message stream, but pending control requests — including that in-flight `initialize` — still received the raw exception, so callers saw `Command failed with exit code 1` with the actual reason discarded. Pending control requests now receive a `ProcessException` carrying the same `Claude Code returned an error result: …` text and the original exit code. This also improves e.g. resuming a nonexistent session, which takes the same path.

### Notes
- Python SDK v0.2.131–v0.2.136 and v0.2.138 are bundled-CLI version bumps only (2.1.223–2.1.228, 2.1.231–2.1.232). The Java SDK resolves the CLI from `PATH` rather than bundling it, so there is nothing to track.

### Synced
- Python SDK v0.2.130 → v0.2.138 (commits e8238a3c..961aff8c)

[0.1.23]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.23

## [0.1.22] - 2026-08-05

### Added
- **`allowUnsafeWindowsBatchCli` opt-in for npm `claude.cmd` deployments** ([#2](https://github.com/vidyalai-in/claude-agent-sdk-java/issues/2)): the Windows batch-script refusal added in 0.1.21 left centrally-managed deployments — where software distribution is controlled and migrating to the native `claude.exe` needs a longer transition — with no option but to pin an old SDK or fork it. `ClaudeAgentOptions.builder().allowUnsafeWindowsBatchCli(true)` waives the refusal. Default is `false`; nothing changes for callers who do not set it.

  This is deliberately not a plain bypass, because a bare waiver would restore the whole `cmd.exe` re-parse hole. Investigating the JDK showed why the refusal was right on Java's own terms and how to narrow the waiver: `ProcessImpl` reads `jdk.lang.Process.allowAmbiguousCommands` with a default of `"true"`, which selects a legacy mode whose escape set is **empty** — nothing but whitespace is quoted and embedded quotes are accepted. On a stock JVM, Java is exposed to this class exactly as Node.js was. Setting the property to `false` instead selects `VERIFICATION_CMD_BAT` for a non-`.exe` target, where the JDK quotes `" < > & | ^` and rejects any argument carrying a quote.

  So the opt-in requires that mode and supplies the part the JDK omits: (1) `connect()` throws `CLIConnectionException` unless `jdk.lang.Process.allowAmbiguousCommands=false`, matching the JDK's own `!"false".equalsIgnoreCase(value)` reading rather than inventing separate truthiness; (2) every CLI argument is swept for `& | < > ^ % ! "` and CR/LF, throwing `IllegalArgumentException` naming the offending option — `%` and `!` are absent from the JDK's escape set, and quoting does not stop `%VAR%` expansion, so an unquoted `%FOO%` expanding to `x&calc` would be re-parsed; (3) a `WARNING` is logged once per transport naming the accepted risk. The net attack surface is narrower than simply not having the check. **Residual risk:** cmd.exe still expands `%VAR%` from the environment, which an argv sweep cannot address. POSIX is unaffected. The default refusal message now names the opt-in so users who hit the wall can find it.

### Security
- **Validate skill names before they reach `--allowedTools`** (Python SDK v0.2.129, PR #1145): names passed to `ClaudeAgentOptions.builder().skills(List)` were formatted into the CLI's `--allowedTools` value unchecked. The CLI splits that value into permission rules on commas and spaces outside parentheses, and its tokenizer honors no escape sequences — escaping exists only in the per-rule grammar, applied *after* splitting — so a name carrying a delimiter cannot be passed through reliably: what it tokenizes into depends on what surrounds it. `skills(List.of("x),Bash(*"))` emitted `Skill(x),Bash(*)`, granting the session unrestricted `Bash`. `applySkillsDefaults()` now validates each name before formatting it, so the rejection surfaces at `connect()` before the CLI is spawned. Rejected with `IllegalArgumentException`: parentheses, commas, control characters (C0, DEL, C1), byte-order marks, and empty or whitespace-only names. Ordinary names are unaffected — plugin-qualified (`myplugin:pdf`), interior spaces, single backslashes, and non-ASCII all still build the same argv as before.

### Changed
- **Skill names that could never match are now rejected instead of silently ignored** (same PR). **Breaking:** `skills(List.of("*"))` and `skills(List.of("plugin:*"))` throw — use `.skillsAll()` to enable every skill, or add a `Skill(...)` entry to `allowedTools` directly for prefix matching. A leading `/` (`"/commit"` — the slash-command form rather than the canonical name), surrounding whitespace (the Skill tool trims the invoked name before matching), consecutive backslashes (the per-rule parser collapses them, so the rule would name a different skill), and a trailing unpaired backslash now throw as well. Each previously built a well-formed rule that matched nothing, leaving the skill quietly unavailable.
- Two checks diverge from the Python SDK, deliberately. **Surrogates:** Python rejects every surrogate code point — sound there, since a `str` holds code points and an astral character is one non-surrogate item, so any surrogate present is unpaired by construction. Java strings are UTF-16, where an astral character *is* a high/low pair, so that rule would reject ordinary names like `"𝕤kill"`; only lone surrogates are rejected here. **Whitespace:** `String.strip()` follows `Character.isWhitespace`, which leaves the non-breaking spaces (U+00A0, U+2007, U+202F) that Python's `str.strip()` removes, so the padding check unions it with `Character.isSpaceChar`. U+FEFF stays out of both and is rejected as an invalid character, exactly as in Python.
- Python's `_reject_non_list_skills` guard is ported as `rejectNonListSkills` even though Java's builder (`skills(List)` / `skillsAll()`) already makes a bare string or non-list iterable unreachable — a raw-typed or reflective caller now fails closed rather than silently installing no skill filter.

### Notes
- Python SDK v0.2.130 is a bundled-CLI version bump only (2.1.222). The Java SDK resolves the CLI from `PATH` rather than bundling it, so there is nothing to track.

[0.1.22]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.22

## [0.1.21] - 2026-07-29

### Security
- **Refuse to spawn `.bat`/`.cmd` CLI scripts on Windows** (Python SDK v0.2.124, PR #1127): follow-up to the argv-injection fix in #1123. Windows has no shebang mechanism — the OS runs a batch script by rewriting the spawn into `cmd.exe /c`, and cmd.exe re-parses the whole command line. Argument quoting follows the MSVCRT argv rules, not cmd.exe's, so cmd.exe metacharacters inside an argument value (a `--resume` session title, the `--mcp-config` JSON, a system prompt) reach cmd.exe unescaped and can execute injected commands before the CLI even starts. The `--flag=value` form from #1123 does not help here: once cmd.exe re-parses the string there is no argv boundary left to protect. This is the "BatBadBut" class (CVE-2024-27980), and refusing is the same remediation Node.js shipped. `SubprocessCLITransport.connect()` now rejects a `.bat`/`.cmd` path immediately after the CLI path is resolved and before anything is spawned with it, so it covers PATH discovery, an explicit `cliPath`, and the version probe alike. The extension test normalizes the path the way Win32 does — trailing dots and spaces stripped, NTFS alternate-data-stream specs (`claude.cmd:stream`, `claude:evil.cmd`) covered in both directions, drive-relative `C:claude.cmd`, a bare `.cmd` treated as an extension — and classifies *every* path component, so no `.`/`..` normalization trick reaches the spawn. Plain string logic, not `Path`, so it behaves identically on the POSIX hosts the tests run on and the Windows hosts it guards. **Behavior change on Windows only:** launching a `.bat`/`.cmd` CLI now throws `CLIConnectionException`; use the native installer (`irm https://claude.ai/install.ps1 | iex`) or point `cliPath` at a `claude.exe`.
- **Windows CLI discovery prefers a native `claude.exe`** (same PR): PATH is now swept for `claude.exe` in full before any extensionless entry is considered, so a git-bash / WSL wrapper script named `claude` in an early PATH directory no longer shadows a real `claude.exe` installed in a later one. The Windows fallback probes only `~/.local/bin/claude.exe` — the POSIX-shaped locations are skipped because an extensionless match there would preempt the explanatory refusal with an opaque spawn failure, and a driveless `/usr/local/bin/claude` resolves against the current drive, a path another local user can create. When nothing native is discoverable, `claude.cmd`/`claude.bat` is returned as a last resort specifically so `connect()` raises the batch-script refusal (with remediation) rather than a bare not-found error. POSIX discovery is unchanged.
- **Reject cmd.exe metacharacters in `resume`/`sessionId` on Windows** (same PR): defense in depth. With batch spawning refused these characters are already harmless, but `resume` and `sessionId` are the values applications most often take from external input, so they now throw `IllegalArgumentException` on Windows when they contain `& | < > ^ % ! "` or CR/LF — keeping them inert even if a cmd.exe hop is ever reintroduced. No format is imposed beyond that (resume values may be arbitrary session titles, not only UUIDs). POSIX behavior is unchanged.
- **Bind dash-leading `extraArgs` values with `--flag=value`** (same PR): the remaining two-token call site. A dash-leading value in the two-token form parses as a separate CLI flag rather than as the option's value — the same injection class #1123 closed for `resume`/`sessionId`.

### Added
- **`ResultMessage.terminalReason`** (Python SDK v0.2.126, PR #1142): surfaces why the query loop ended — `"completed"`, `"max_turns"`, `"aborted_streaming"`, `"aborted_tools"`, etc. The CLI has always emitted `terminal_reason` on the result frame; the SDK was dropping it. A value of `"aborted_streaming"` or `"aborted_tools"` means the turn was cancelled via `ClaudeSDKClient.interrupt()`, giving callers an explicit cancelled marker without a new result subtype. Null when the CLI did not report one (older CLI versions, or a result that bypassed the query loop such as a local slash command). Mirrors the TypeScript SDK's `SDKResultMessage.terminal_reason`.
- **`ModelUsage` type for `ResultMessage.modelUsage`** (Python SDK v0.2.126, PR #1143): `modelUsage` is now `Map<String, ModelUsage>` instead of `Map<String, Object>`. `ModelUsage` mirrors the TypeScript SDK's shape (`inputTokens`, `outputTokens`, `cacheReadInputTokens`, `cacheCreationInputTokens`, `webSearchRequests`, `costUsd`, `contextWindow`, `maxOutputTokens`) plus the two fields a newer CLI adds: `canonicalModel`, a stable key for client-side rate-table lookups across provider-specific model ids and aliases (Bedrock ARNs → `claude-opus-4-7`), and `provider` (`firstParty`, `bedrock`, `vertex`, …). Following the house pattern set by `RateLimitInfo`, each entry also keeps the verbatim CLI map as `raw()`, so unmodeled fields stay reachable. Unlike Python — where the change is type-only because a `TypedDict` is a `dict` at runtime — this is a **source-incompatible change** for callers that read `modelUsage()` values as `Map`; read the typed accessors, or `raw()` for the old shape.

### Fixed
- **Do not close stdin on a result frame while tasks are in flight** (Python SDK v0.2.127, PR #1103, issue #1088): a `result` frame ends one *turn*, not the *run*. A background task keeps running past it and still needs stdin for hook and SDK-MCP control responses, but `QueryHandler` closed stdin on the first result frame — so a still-running subagent's SDK-MCP tool calls failed with `"Stream closed"` and its `PreToolUse` hooks were *silently bypassed*, letting built-in tools execute with no callback delivered and deny-gate hooks stop gating. `QueryHandler` now tracks in-flight tasks from the `task_started` / `task_notification` / terminal `task_updated` lifecycle frames and only treats a result as run-ending when none are in flight; each task completion wakes the parent for a follow-up turn that ends in such a result, which also makes *chained* background tasks work. Only `local_agent` and `local_workflow` are tracked: a background shell may never reach a terminal status, and since the CLI in stream-json mode exits only on stdin EOF, tracking one would withhold the close forever rather than briefly.

### Synced
- Python SDK v0.2.123 → v0.2.128 (commits 2d4ef946..f8b9ec92)
- v0.2.124: Windows batch-script refusal (#1127, ported above).
- v0.2.125, v0.2.128: CLI bumps 2.1.217 and 2.1.220 (no API changes). Java resolves the CLI from `PATH` rather than bundling it, so CLI version bumps are informational only.
- v0.2.126: `terminal_reason` (#1142) and typed `model_usage` (#1143), both ported above.
- v0.2.127: in-flight task tracking before stdin closure (#1103, ported above).

[0.1.21]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.21

## [0.1.20] - 2026-07-23

### Added
- **`ImageBlock` and `DocumentBlock` content blocks.** Reading a PDF with the `Read` tool makes the CLI emit a short `tool_result` announcing the page count, followed by a *separate* user message carrying the file itself — either as one `image` block per rendered page (`image/jpeg`, base64) or as a single `document` block holding the whole PDF (`application/pdf`, base64). Both shapes were observed from CLI 2.1.218 against the same 1.5 MB file on different runs, so both are modelled. `MessageParser` previously rejected both with `MessageParseException: Unknown content block type`, which meant *any* agent granted `Read` over a directory of PDFs died mid-run without ever mentioning images or documents in its own configuration. `source` is kept as the raw map — the API defines `base64`, `url`, `file`, `text` and `content` source shapes and can add more — with `sourceType()`, `mediaType()` and `data()` covering the base64 case.
- **`UnknownBlock` for unmodelled content block types.** `MessageParser.parse()` already returns `null` for an unrecognised *message* type so that a newer CLI does not crash an older SDK; content blocks now get the same forward compatibility instead of throwing. An unrecognised block is preserved whole as an `UnknownBlock` (`type()` plus the raw map) and logged once per type at `WARNING` from `in.vidyalai.claude.sdk.internal.MessageParser`. This is what `image` and `document` needed and did not have: the old behaviour killed the reader thread, discarded every other block in the message — including text the model had already produced — and surfaced as a JSON decode failure naming a type the caller never asked for.

### Notes
- `ContentBlock` is sealed, and its `permits` clause has grown by three. An exhaustive `switch` over it in caller code will no longer compile until the new types are handled; add an `UnknownBlock` branch rather than a `default`, so the next addition is still a compile error rather than a silent fallthrough.
- **Reading PDFs requires raising `ClaudeAgentOptions.maxBufferSize`.** These blocks are base64, four bytes per three, on a single line of CLI stdout: a 1.5 MB PDF is a ~2.1 MB line against the 1 MB default. The default is unchanged (it matches the Python SDK), so callers that read files of any size must set it explicitly.

[0.1.20]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.20

## [0.1.19] - 2026-07-19

### Security
- **Pass `resume`/`sessionId` as `--flag=value` to prevent argv flag injection** (Python SDK v0.2.120, PR #1123): `SubprocessCLITransport.buildCommand()` now emits `--resume=<value>` and `--session-id=<value>` as single argv tokens instead of the two-token form (`--resume`, `<value>`). The CLI declares `--resume` with an *optional* value, so in the two-token form a dash-leading value is not bound to the flag and is parsed as an independent CLI flag — letting an application that routes untrusted input into `resume`/`sessionId` (e.g. a "resume my session" endpoint taking a session ID from a request) inject arbitrary flags. For example, `resume("--version")` silently ran `claude --version` and yielded zero messages; an injected value-taking flag escalates further. The equals form always binds the value to the flag, and the CLI then rejects a dash-leading value as an invalid session ID. This is argv-level (one argument per option, no shell involved) — flag injection, not command execution — and only affects apps forwarding untrusted input into these options. Matches the `--setting-sources=` style already used elsewhere in `buildCommand()`; the TypeScript SDK shipped the same fix in 0.3.208.

### Synced
- Python SDK v0.2.113 → v0.2.123 (commits 5513b209..2d4ef946)
- v0.2.120: `--resume`/`--session-id` flag-injection fix (#1123, ported above). Also PR #1117 (validate `CLAUDE_CLI_VERSION` + drop shell interpolation in build scripts) — Python build-script hardening, N/A for Java, which resolves the CLI from `PATH` rather than bundling it.
- v0.2.114-0.2.119, v0.2.121-0.2.123: CLI bumps 2.1.205-2.1.215 (no API changes) plus Python-repo CI/tooling only — Slack-notification field escaping (#1116), workspace-trust for project-scoped grants (#1085), and new build/download-CLI test coverage. Java resolves the CLI from `PATH`, so CLI version bumps are informational only.

[0.1.19]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.19

## [0.1.18] - 2026-07-08

### Added
- **`canUseTool` shadowing warning** (Python SDK v0.2.111, PR #1081): a new `CanUseToolShadow` helper logs a `WARNING` when a `canUseTool` callback is registered alongside options that auto-approve tool calls before the callback would ever fire — `allowedTools` entries that allow a whole tool (`"Read"`, `"Read()"`, `"Read(*)"`) or `permissionMode=BYPASS_PERMISSIONS`. Emitted once per query construction from `ClaudeSDKClient.connect()` and `ClaudeSDK.query(Iterator)` streaming setup. Advisory only (never throws): shadowing can be intentional. `skills("all")` is accounted for (it injects a bare `Skill` allow rule). Java uses `java.util.logging` in place of Python's `warnings`/`CanUseToolShadowedWarning`; suppress by configuring the `in.vidyalai.claude.sdk.internal.CanUseToolShadow` logger level.

### Fixed
- **`MessageParseException` on non-dict content blocks** (Python SDK v0.2.111, PR #1058): `MessageParser` now raises an explicit `MessageParseException` ("Invalid content block (expected dict, got …)" / "Invalid assistant content (expected list, got …)") when the CLI emits a message whose `content` is a bare string or whose content list holds a non-object element, instead of surfacing a raw `ClassCastException`. Both `user` and `assistant` messages are covered.
- **No longer stop tracking a still-running CLI child on close** (Python SDK v0.2.111, PR #1082): `SubprocessCLITransport.close()` now removes the process from the active-children set only after confirming it is no longer alive (`!process.isAlive()`). A process that survived the terminate/kill escalation (kill raced, or `waitFor` timed out) stays tracked so the JVM shutdown-hook reaper still gets a chance at it, instead of being leaked.

### Synced
- Python SDK v0.2.103 → v0.2.113 (commits 7f74cdf6..5513b209)
- v0.2.111: four bug fixes — `canUseTool` shadowing warning (#1081, ported above); non-dict message content → `MessageParseException` (#1058, ported above); shield subprocess cleanup from cancellation + keep an un-reaped child tracked (#1082, behavioral part ported; the anyio `CancelScope` shielding is Python-async-specific — Java's `close()` is synchronous and always runs to completion); e2e stderr test cwd fix (#1084 — Python test infra, N/A).
- v0.2.111: **silent whitespace loss on NDJSON lines >64 KiB (#1083) — N/A for Java.** The Python fix reframes chunk-based stream reads for stdout *and* stderr (anyio yields ≤64 KiB chunks, and the old code stripped each chunk, dropping whitespace at a seam inside a JSON string). Java reads both stdout and stderr with `BufferedReader.readLine()`, which reassembles partial reads and returns a *complete* line regardless of length, stripping only the complete line — the chunk-seam bug is structurally impossible. The associated new tests are all sub-line chunk-framing simulations that `readLine()` handles transparently. (Behaviors that outlive the framing rewrite — oversized-line rejection, non-JSON skip, final-line-without-newline delivery, truncated-tail drop — are already covered by `SubprocessBufferingTest`.)
- v0.2.104-0.2.110, v0.2.112-0.2.113: CLI bumps 2.1.181-2.1.204 (no API changes). Java resolves the CLI from `PATH` rather than bundling it, so CLI version bumps are informational only.

[0.1.18]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.18

## [0.1.17] - 2026-06-17

### Added
- **`TaskUpdatedMessage` typed lifecycle message** (Python SDK v0.2.101, PR #1016): the CLI emits `system`/`task_updated` events as a background task moves through its lifecycle, and a task's terminal state sometimes arrives *only* as a `task_updated` patch with no accompanying `TaskNotificationMessage` — e.g. a task stopped via `TaskStop` reports `status="killed"` here. `MessageParser` now routes `task_updated` to a new `TaskUpdatedMessage` record (a `Message` sealed-interface member, `type()` = `"system"`) exposing `taskId`, `patch` (the changed fields), `status`, `sessionId`, and `uuid`. Parsed defensively — a missing/non-Map `patch` falls back to an empty map and an unknown/absent status to `null`, so a lifecycle event never crashes parsing.
- **`TaskUpdatedStatus` enum** in `in.vidyalai.claude.sdk.types.message` (Python SDK v0.2.101): mirrors Python's `TaskUpdatedStatus` literal — `PENDING`, `RUNNING`, `PAUSED` (non-terminal) and `COMPLETED`, `FAILED`, `KILLED` (terminal). Note `task_updated` reports the raw `killed`; the CLI maps that to `stopped` only when it emits a `task_notification`. `fromValueOrNull(String)` resolves a raw status without throwing on unknown/null values.
- **`TaskUpdatedMessage.TERMINAL_TASK_STATUSES`** (Python SDK v0.2.101): Java equivalent of Python's top-level `TERMINAL_TASK_STATUSES` frozenset — `{"completed", "failed", "stopped", "killed"}`, spanning both lifecycle vocabularies so consumers can treat the status of a `TaskNotificationMessage` and a `TaskUpdatedMessage` the same way. A convenience `TaskUpdatedMessage.isTerminal()` returns whether the update's status is terminal.

### Synced
- Python SDK v0.2.95 → v0.2.103 (commits 7c37e347..7f74cdf6)
- v0.2.96-0.2.103: `TaskUpdatedMessage` / `TaskUpdatedStatus` / `TERMINAL_TASK_STATUSES` (PR #1016, ported above); `deps: pin mcp below 2.0.0` (PR #1028 — Python package constraint, N/A for Java); CLI 2.1.172-2.1.179 (no API changes).

[0.1.17]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.17

## [0.1.16] - 2026-06-01

### Added
- **`EffortLevel` enum** in `in.vidyalai.claude.sdk.types.config` (Python SDK v0.2.82, PR #951): mirrors Python's `EffortLevel` type alias with the same five values — `LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX` — each carrying the lowercase wire value via `@JsonValue`. Available as a public API for downstream wrappers and type annotations. `ClaudeAgentOptions.Builder.effort(EffortLevel)` overload added alongside the existing `effort(String)` setter; passing `null` clears the field. Backward-compatible — the `effort()` getter still returns `String`.

### Fixed
- **Stderr callback isolation** in `SubprocessCLITransport.handleStderr` (Python SDK v0.2.82, PR #932): a `try/catch` around each `stderrCallback.accept(line)` invocation guarantees a throwing callback no longer kills the read loop and silently drops every subsequent stderr line for the rest of the session. Outer-loop exceptions are now logged at `FINE` instead of being silently swallowed.

### Changed
- **Hooks dispatch concurrency** documented on `ClaudeAgentOptions.hooks()` / `Builder.hooks(Map)` and `HookMatcher` Javadoc (Python SDK v0.2.82, PR #956): clarifies that matchers registered on the same event are dispatched concurrently by the CLI, not sequentially. Existing behavior — Javadoc-only update.

### Synced
- Python SDK v0.1.80 → v0.2.82 (commits 694e4f3b..c352a509)
- v0.1.81: CLI 2.1.139 (no API changes)
- v0.2.82: `EffortLevel` export; stderr callback isolation; `_swallow_done_exception` for `CancelledError` in eager-flush done callback (Python-asyncio-only — N/A for Java's `CompletableFuture`); tighter `permission_suggestions` on `SDKControlPermissionRequest` (Java already tighter via `PermissionUpdate.fromMap` `@JsonCreator`); hooks dispatch concurrency docs; `mcp>=1.23.0` floor for GHSA-9h52-p55h-vw2f (Python package metadata only — N/A for Java); CLI 2.1.140-2.1.143
- Python SDK v0.2.82 → v0.2.87 (commits c352a509..6218b9b4) — **no Java-relevant API or behavioral changes**.
- v0.2.83-0.2.87: Ported the Python `session_store` resume/listing/mirroring path from `asyncio` to `anyio` so it runs under both the asyncio and trio event-loop backends (PR #990). This is Python-concurrency-backend portability only:
  - `_internal/session_resume.py`, `_internal/sessions.py`, `_internal/transcript_mirror_batcher.py`: `asyncio.sleep`/`wait_for`/`gather`/`Semaphore`/`Lock` → `anyio` equivalents. Java already uses `CompletableFuture`/virtual threads and a `ReentrantLock`-serialized synchronous flush executor — N/A.
  - Removed the asyncio-only `_swallow_done_exception` eager-flush done-callback helper (its "unretrieved exception" warning has no `CompletableFuture` equivalent; `TranscriptMirrorBatcher.scheduleDrain` already documents this).
  - Python `TranscriptMirrorBatcher.close()` flush is now shielded from cancellation; Java's executor-backed `close()` already completes its final flush during teardown.
  - New `tests/test_session_store_anyio.py` (trio backend) and `test_transcript_mirror.py` updates are backend-specific — N/A for Java.
- CLI 2.1.144-2.1.159 (no API changes).
- Python SDK v0.2.87 → v0.2.95 (commits 6218b9b4..7c37e347) — **no Java-relevant API or behavioral changes**.
- v0.2.88: Completed the `asyncio` → `anyio` port of the session-store code paths (`TranscriptMirrorBatcher`, `session_resume`, `sessions`), fixing a `TypeError: trio.run received unrecognized yield message` crash when passing `session_store=` to `query()`/`ClaudeSDKClient` under trio (PR #990). Python-concurrency-backend portability only — Java uses `CompletableFuture`/virtual threads and a synchronous flush executor, so N/A. The conformance docstring example switched `@pytest.mark.asyncio` → `@pytest.mark.anyio` (Python test-infra — N/A).
- v0.2.91: Switched the Python test suite from `pytest-asyncio` to anyio's pytest plugin, running every async test under both asyncio and trio backends (PR #1021). Python test-infrastructure only — N/A for Java.
- CI: e2e jobs switched from a static API key to workload identity federation (short-lived OIDC tokens, PR #1018) — Python-repo CI only, N/A.
- CLI 2.1.160-2.1.170 (no API changes).

[0.1.16]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.16

## [0.1.15] - 2026-05-09

### Added
- **`includeHookEvents` option on `ClaudeAgentOptions` + `HookEventMessage`** (Python SDK v0.1.74): when `true`, the transport adds `--include-hook-events`. The CLI then streams `system/hook_started` and `system/hook_response` envelopes which `MessageParser` routes to a new `HookEventMessage` (`Message` sealed-interface member). The message exposes `subtype`, `hookEventName`, `sessionId`, `uuid`, and the full raw `data` map.
- **`"defer"` hook decision + `DeferredToolUse` on `ResultMessage`** (Python SDK v0.1.74): `PermissionDecision.DEFER` serializes to `"defer"`. `MessageParser.parseResultMessage` deserializes the `deferred_tool_use` payload into a new `DeferredToolUse` record (`id` / `name` / `input`). The pre-enrichment 11-arg / 15-arg `ResultMessage` constructors are preserved for callers who don't use the new fields.
- **`strictMcpConfig` option on `ClaudeAgentOptions`** (Python SDK v0.1.74): when `true`, the transport adds `--strict-mcp-config` so the CLI ignores project / user / global / plugin MCP configurations and uses only the servers passed via `mcpServers`.
- **`ToolPermissionContext` enrichment** (Python SDK v0.1.74): `decisionReason`, `blockedPath`, `title`, `displayName`, `description`. `SDKControlPermissionRequest` carries the new fields off the wire (with a backwards-compatible 6-arg constructor) and `QueryHandler` forwards them into the context handed to `canUseTool`. The pre-enrichment 4-arg `ToolPermissionContext` constructor is preserved.
- **`updatedToolOutput` on `PostToolUseHookSpecificOutput`** (Python SDK v0.1.74): replaces any tool's output (built-ins included), in addition to the existing `updatedMCPToolOutput` for MCP-only replacements. The 2-arg constructor is preserved.
- **`"xhigh"` effort level** (Python SDK v0.1.74): documented on `ClaudeAgentOptions.effort()` and `AgentDefinition.effort` Javadoc as an Opus 4.7-specific level that falls back to `"high"` on other models. The field type stays `String` so callers can pass any future effort value.
- **`apiErrorStatus` on `ResultMessage`** (Python SDK v0.1.76): `Integer` field surfacing the HTTP status code (e.g. 429, 500, 529) of the failing API call when `isError=true` and `subtype="success"`. Safe to log (no message content). `MessageParser` populates it from the CLI's `api_error_status` field.

### Changed
- **JVM shutdown hook for live CLI subprocesses** in `SubprocessCLITransport` (Python SDK v0.1.74): a static `ConcurrentHashMap.newKeySet()` tracks every spawned `Process`; a `Runtime.addShutdownHook` registered at class init calls `destroy()` on each live child, preventing orphaned `claude` processes from leaking when the parent JVM exits before `close()`. Mirrors the Python SDK's `atexit` handler.
- **Actionable error message after error result** in `QueryHandler.readMessages` (Python SDK v0.1.77): tracks the last error result's text while reading; when the read loop catches a `ProcessException` after a result with `is_error=true`, the synthetic `{"type":"error"}` message carries `"Claude Code returned an error result: <text>"` instead of the generic `"Command failed with exit code N"`. The text is built from the `errors` array (joined by `"; "`) or the result `subtype` when the array is missing. Resets on any non-result, non-`session_state_changed` traffic so a fresh crash later in the run keeps its original `ProcessException` message.
- **`createdAt` head-buffer scan** in `Sessions.parseSessionInfoFromLite` (Python SDK v0.1.74 fix #907): `extractCreatedAtFromFirstLine` now scans the entire `head` buffer instead of only the first JSONL line. Sessions whose first record is a metadata-only entry (e.g. `permission-mode`) without a `timestamp` field now correctly report a `createdAt` from the next record's timestamp.

### Synced
- Python SDK v0.1.72 → v0.1.80 (commits 0a69e944..694e4f3b)
- v0.1.73: CLI 2.1.128 (no API changes — `session_store_flush` shipped in Java v0.1.14)
- v0.1.74: `include_hook_events` + `HookEventMessage`, `"defer"` decision + `DeferredToolUse`, `strict_mcp_config`, `ToolPermissionContext` enrichment, `updatedToolOutput`, `"xhigh"` effort, parent-exit subprocess cleanup, sessions `created_at` head-scan fix; CLI 2.1.129
- v0.1.75: CLI 2.1.131 (no API changes)
- v0.1.76: `api_error_status` on `ResultMessage`; permission-suggestions deserialization fix (Java already correct via `PermissionUpdate.fromMap` `@JsonCreator`); CLI 2.1.132
- v0.1.77: actionable error after error result; `"Skill"` deprecation in `allowed_tools`; CLI 2.1.133
- v0.1.78-0.1.80: CLI updates 2.1.136-2.1.138 (no API changes)

[0.1.15]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.15

## [0.1.14] - 2026-05-04

### Added
- **`sessionStoreFlush` option on `ClaudeAgentOptions`**: New `SessionStoreFlushMode` enum (`BATCHED` / `EAGER`) controlling when transcript-mirror entries are flushed to the configured `SessionStore` adapter. `BATCHED` (default) coalesces entries and flushes once per turn or on buffer overflow; `EAGER` zeroes the `TranscriptMirrorBatcher` thresholds so every enqueued frame schedules a background drain for near-real-time delivery. Wired through `SessionResume.buildMirrorBatcher()` from both `ClaudeSDK.query(stream)` and `ClaudeSDKClient.connect()`. Matches Python SDK v0.1.72.
- **Domain allowlist fields on `SandboxNetworkConfig`** (matching Python SDK v0.1.71): `allowedDomains` (domains sandboxed processes can access), `deniedDomains` (always-blocked overrides), `allowManagedDomainsOnly` (managed-settings exclusivity flag), and `allowMachLookup` (macOS XPC/Mach service names with trailing-wildcard support). The pre-v0.1.71 5-arg constructor is preserved for backward compatibility — existing callers continue to compile and the new fields default to `null`.

### Synced
- Python SDK v0.1.68 → v0.1.72 (commits 8348d1f8..0a69e944)
- v0.1.69: Docstrings on `ClaudeAgentOptions` fields (Java already has Javadoc); CLI 2.1.121
- v0.1.70: `spawn_detached` stderr reader fix (Python/trio-specific — N/A for Java); `mcp>=1.19.0` dependency floor (Python package metadata only — N/A for Java); CLI 2.1.122
- v0.1.71: Domain allowlist fields on `SandboxNetworkConfig`; CLI 2.1.123
- v0.1.72: `session_store_flush` option for eager `SessionStore` mirroring; CLI 2.1.126

[0.1.14]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.14

## [0.1.13] - 2026-04-27

### Added
- **`SessionStore` adapter protocol**: New `in.vidyalai.claude.sdk.types.session.SessionStore` interface for mirroring session transcripts to external storage (S3, Postgres, Redis, custom backends). Required: `append(SessionKey, List<SessionStoreEntry>)` and `load(SessionKey)`. Optional: `listSessions`, `listSessionSummaries`, `delete`, `listSubkeys` (with `implements*()` probe flags). Adapters can override either sync or async variants — the unimplemented one defaults to wrapping the implemented one. Matches Python SDK v0.1.64.
- **Async SessionStore variants with configurable executor**: `appendAsync`/`loadAsync`/`listSessionsAsync`/`listSessionSummariesAsync`/`deleteAsync`/`listSubkeysAsync` default methods returning `CompletableFuture`. Each has overloads taking an explicit `Executor`. Default executor is configured globally via `SessionStoreExecutor.setDefault(Executor)`; built-in default is per-task virtual thread (`Thread.ofVirtual()`). Adapters with native async clients (AWS SDK v2 async, R2DBC, Lettuce reactive) should override the `*Async` methods directly to avoid a thread hop. The mirror batcher and resume materializer call `*Async` so async adapters preserve parallelism end-to-end.
- **Runtime mirror integration**: `TranscriptMirrorBatcher` ports the Python batcher 1:1 (~100ms cadence, `MAX_PENDING_ENTRIES=500` / `MAX_PENDING_BYTES=1 MiB` thresholds, 3-attempt retry with `[200ms, 800ms]` backoff, no retry on timeout). Coalesces frames per `filePath`, drops frames whose path falls outside `projectsDir` with a warning, and surfaces final-attempt failures via `onError` → `MirrorErrorMessage`.
- **`SessionResume.materializeResumeSession()`**: loads from store, writes to a temp `CLAUDE_CONFIG_DIR` so the CLI subprocess can resume from local disk; copies `.credentials.json` (with `refreshToken` redacted) and `.claude.json`; cleans up on disconnect with retry on transient Windows AV/indexer locks. Subagent transcripts and `.meta.json` sidecars are reconstructed when the store implements `listSubkeys`. Subpath safety check rejects empty/absolute/`..`-containing keys.
- **`SessionResume.applyMaterializedOptions()` / `buildMirrorBatcher()`**: helpers wired into both `ClaudeSDKClient.connect()` and the static `ClaudeSDK.query(stream)` path. The batcher uses the temp dir's `projects/` when materialized, otherwise resolves from `options.env().CLAUDE_CONFIG_DIR` or the process environment.
- **`SessionStoreValidation.validate()`**: fail-fast pre-flight check called before subprocess spawn. Rejects `continueConversation + sessionStore` without `listSessions()`, and `sessionStore + enableFileCheckpointing`.
- **`QueryHandler.setTranscriptMirrorBatcher()` / `reportMirrorError()`**: peels `transcript_mirror` frames off stdout (never yielded to consumers), enqueues them on the batcher, flushes before yielding `result` and again at end-of-stream / close. `reportMirrorError` enqueues a `mirror_error` system message into the consumer stream.
- **`ClaudeSDK.importSessionToStore()`**: local→store replay helper (Python's `import_session_to_store`). Streams the on-disk JSONL line-by-line and calls `store.append` in batches of 500 entries / 1 MiB. Recursively imports subagent transcripts and `.meta.json` sidecars when `includeSubagents=true`.
- **`SessionStoreConformance` test harness**: public, framework-agnostic 14-contract suite at `in.vidyalai.claude.sdk.testing.SessionStoreConformance`. Runs against the bundled `InMemorySessionStore` in `SessionStoreConformanceTest` and is the recommended way for adapter authors to validate their own implementations. Uses plain `AssertionError` so it works under JUnit, TestNG, Spock, or a smoke `main()`.
- **`InMemorySessionStore.filePathToSessionKey(filePath, projectsDir)`**: static helper for resolving an on-disk transcript path back to a `SessionKey`. Used internally by the mirror batcher; exposed for adapter implementations that need the same mapping.
- **SessionStore types**: `SessionKey`, `SessionListSubkeysKey`, `SessionStoreEntry` (map-backed structural supertype), `SessionStoreListEntry`, `SessionSummaryEntry` in `in.vidyalai.claude.sdk.types.session`. Matches Python SDK v0.1.64.
- **`InMemorySessionStore`**: Reference adapter for tests/dev with full `SessionStore` protocol coverage including incremental summary maintenance. Matches Python SDK v0.1.64.
- **`SessionSummary` helpers**: `foldSessionSummary()` and `summaryEntryToSdkInfo()` for incremental sidecar maintenance inside `append()`. Matches Python SDK v0.1.64.
- **SessionStore-backed APIs on `ClaudeSDK`**: `listSessionsFromStore()`, `getSessionInfoFromStore()`, `getSessionMessagesFromStore()`, `listSubagentsFromStore()`, `getSubagentMessagesFromStore()`. Mirrors Python's `*_from_store` functions as synchronous methods. Matches Python SDK v0.1.64.
- **SessionStore-backed mutations on `ClaudeSDK`**: `renameSessionViaStore()`, `tagSessionViaStore()`, `deleteSessionViaStore()`, `forkSessionViaStore()`. Internal fork transform extracted to `SessionMutations.buildForkLines()` so disk and store paths share the UUID-remap logic. Matches Python SDK v0.1.64.
- **`projectKeyForDirectory()`** on `ClaudeSDK` and `SessionStores`. Derives the SessionStore project key using the same realpath + NFC normalization + djb2-hashed sanitization the CLI uses for project directory names. Matches Python SDK v0.1.64.
- **`sessionStore` and `loadTimeoutMs` options** on `ClaudeAgentOptions`. When `sessionStore` is set, the transport adds `--session-mirror` to the CLI command so the CLI emits transcript-mirror traffic. Default `loadTimeoutMs=60000`. Matches Python SDK v0.1.64.
- **`MirrorErrorMessage`**: New `Message` sealed-interface member for non-fatal `SessionStore.append()` failures. Parser dispatches the `mirror_error` system-message subtype and decodes the associated `SessionKey`. Matches Python SDK v0.1.64.
- **`ServerToolUseBlock`/`ServerToolResultBlock`/`ServerToolName`**: New `ContentBlock` sealed-interface members for server-side tools (advisor, web_search, web_fetch, code_execution, bash_code_execution, text_editor_code_execution, tool_search_tool_regex, tool_search_tool_bm25). Parser handles `server_tool_use` and `advisor_tool_result` content-block types. Matches Python SDK v0.1.65 (PR #836).
- **`ThinkingDisplay`** enum (`SUMMARIZED`/`OMITTED`) with optional `display` field on `ThinkingConfigAdaptive` and `ThinkingConfigEnabled`. Transport forwards `--thinking-display` CLI flag for adaptive/enabled (never for disabled). Matches Python SDK v0.1.65 (PR #830).
- **`SessionStoreExample.java`**: New example demonstrating the `SessionStore` protocol — direct usage, wiring into `ClaudeAgentOptions`, handling `MirrorErrorMessage`.

### Changed
- **Stderr piping condition** narrowed: the transport now pipes stderr only when `stderrCallback` is registered. The legacy `--debug-to-stderr` extra-arg detection was removed in upstream prep for the CLI flag's deprecation. The `StderrCallbackExample` was updated to drop `extraArgs(Map.of("debug-to-stderr", ""))`. Matches Python SDK v0.1.65 (PR #860).
- **Permission mode docs** corrected: `dontAsk` is now described as "Deny anything not pre-approved by allow rules" and `auto` as "A model classifier approves or denies each tool call". Matches Python SDK v0.1.65 (PR #863).

### Synced
- Python SDK v0.1.63 → v0.1.68 (commits 7ca64f67..8348d1f8)
- v0.1.64: `SessionStore` protocol + types, `InMemorySessionStore`, `*_from_store` listing APIs, `*_via_store` mutations, `MirrorErrorMessage`, `--session-mirror` CLI flag, S3/Redis/Postgres reference adapters (Java ports the protocol; external adapters left for users to wrap their preferred client); CLI 2.1.116
- v0.1.65: `ThinkingDisplay` + `display` field on adaptive/enabled thinking configs with `--thinking-display` flag forwarding; `server_tool_use`/`advisor_tool_result` content blocks (`ServerToolUseBlock`/`ServerToolResultBlock`); `SessionStore.list_session_summaries` batch fetch; transport drops `--debug-to-stderr` detection; `dontAsk`/`auto` permission_mode docs corrected; `import_session_to_store` (Java callers can `store.append` directly); CLI 2.1.117-2.1.118
- v0.1.66: CLI 2.1.119; trio compatibility fix (Python-only)
- v0.1.67: CLI 2.1.120 (no API changes)
- v0.1.68: Docstrings on `ClaudeAgentOptions` fields (Java already has Javadoc); CLI 2.1.119

[0.1.13]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.13

## [0.1.12] - 2026-04-19

### Added
- **Top-level `skills` option on `ClaudeAgentOptions`**: New `skills(List<String>)` and `skillsAll()` builder methods. The SDK auto-injects `Skill(name)` entries (or the bare `Skill` tool for `skillsAll()`) into `allowedTools` and defaults `settingSources` to user/project so the CLI discovers installed skills without extra wiring. The allowlist is also propagated via the initialize control request so a supporting CLI can filter which skills are loaded into the system prompt; older CLIs ignore the field. Empty list suppresses every skill from the listing (matching Python SDK v0.1.62)
- **`ClaudeSDK.listSubagents()` / `getSubagentMessages()`**: New session helpers for reading subagent transcripts under `<project>/<sessionId>/subagents/`, including nested directories like `subagents/workflows/<runId>/`. Added 2 + 3 overloads each; mirrors Python SDK helpers (matching Python SDK v0.1.60)
- **W3C trace context propagation**: When OpenTelemetry is on the classpath and an active span exists, the SDK injects `TRACEPARENT`/`TRACESTATE` into the CLI subprocess environment so its spans parent under the caller's distributed trace. Best-effort via reflection through the public `OpenTelemetry` / `ContextPropagators` / `TextMapPropagator` interfaces (concrete `GlobalOpenTelemetry$ObfuscatedOpenTelemetry` is package-private). No hard dependency on `opentelemetry-api`; also handles stale-env scrubbing, baggage-only carriers, and propagator errors (matching Python SDK v0.1.60)
- **`SkillsExample.java`** and **`SubagentTranscriptExample.java`**: New examples demonstrating the skills option modes and subagent transcript helpers

### Changed
- **`deleteSession()` cascades subagent transcripts**: Removing a session now also recursively deletes the sibling `<sessionId>/` directory containing subagent transcripts (matching Python SDK v0.1.60, TypeScript SDK behavior)

### Fixed
- **Empty `settingSources` list**: `settingSources(List.of())` is now passed as `--setting-sources=` (single token) so the CLI knows to disable all filesystem settings. Previously the empty list was silently dropped, falling back to CLI defaults. Regression of the v0.1.10 omit-when-empty behavior — explicit empty now wins (matching Python SDK v0.1.60)

### Synced
- Python SDK v0.1.58 → v0.1.63 (commits c26fd62..7ca64f6)
- v0.1.59: CLI 2.1.105 (no API changes)
- v0.1.60: `list_subagents`/`get_subagent_messages`, W3C trace context propagation, `delete_session` subagent cascade, fix `--setting-sources=` for empty list; CLI 2.1.107-2.1.111
- v0.1.61: CLI 2.1.112 (no API changes)
- v0.1.62: Top-level `skills` option on `ClaudeAgentOptions`; CLI 2.1.113
- v0.1.63: CLI 2.1.114 (no API changes)

[0.1.12]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.12

## [0.1.11] - 2026-04-13

### Added
- **`auto` permission mode**: New `PermissionMode.AUTO` enum value for automatically determining permission mode (matching Python SDK v0.1.57)
- **`excludeDynamicSections` on `SystemPromptPreset`**: Strip per-user dynamic sections (working directory, auto-memory, git status) for cross-user prompt caching; wired through initialize request to CLI (matching Python SDK v0.1.57)
- **`maxResultSizeChars` on `ToolAnnotations`**: Controls the CLI's layer-2 tool-result spill threshold for large MCP results; forwarded via `_meta` with `anthropic/maxResultSizeChars` key in tools/list JSONRPC response to bypass Zod annotation stripping (matching Python SDK v0.1.55)

### Fixed
- **Thinking config CLI flags**: `--thinking adaptive` and `--thinking disabled` are now passed as proper flags instead of being converted to `--max-thinking-tokens` values. `thinking` config takes strict precedence over the deprecated `maxThinkingTokens` (matching Python SDK v0.1.57)

### Synced
- Python SDK v0.1.54 → v0.1.58 (commits 574044a..c26fd62)
- v0.1.55: Forward maxResultSizeChars via `_meta` to bypass Zod annotation stripping; CLI 2.1.91
- v0.1.56: CLI 2.1.92 (no API changes)
- v0.1.57: `exclude_dynamic_sections` on SystemPromptPreset, `--thinking` flag fix, `auto` permission mode; CLI 2.1.94-2.1.96
- v0.1.58: CLI 2.1.97 (no API changes)

[0.1.11]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.11

## [0.1.10] - 2026-04-02

### Added
- **`dontAsk` permission mode**: New `PermissionMode.DONT_ASK` enum value for allowing all tools without prompting (matching Python SDK v0.1.51)
- **`SystemPromptFile` support**: New `SystemPromptFile` record for loading system prompts from files via `--system-prompt-file` CLI flag (matching Python SDK v0.1.51)
- **`TaskBudget` option**: New `TaskBudget` record and `taskBudget()` builder method for API-side token budget management via `--task-budget` CLI flag (matching Python SDK v0.1.51)
- **`AgentDefinition` new fields**: Added `disallowedTools`, `initialPrompt`, `maxTurns`, `background`, `effort`, `permissionMode`; `model` field relaxed from `AIModel` enum to `String` to support full model IDs (matching Python SDK v0.1.51-v0.1.54)
- **Preserved fields on `AssistantMessage`**: New `messageId`, `stopReason`, `sessionId`, `uuid` fields capturing API-level identifiers (matching Python SDK v0.1.51)
- **New fields on `ResultMessage`**: Added `modelUsage`, `permissionDenials`, `errors`, `uuid` for richer result metadata (matching Python SDK v0.1.51)
- **`toolUseId`/`agentId` on `ToolPermissionContext`**: Expose tool call ID and sub-agent ID in permission callbacks (matching Python SDK v0.1.52)
- **`sessionId` on `ClaudeAgentOptions`**: New `sessionId()` builder method for specifying session ID via `--session-id` CLI flag (matching Python SDK v0.1.52)
- **`getContextUsage()` on `ClaudeSDKClient`**: Returns `ContextUsageResponse` with token usage breakdown by category, matching the CLI's `/context` command (matching Python SDK v0.1.52)
- **`ContextUsageResponse`/`ContextUsageCategory` types**: Typed response for context window usage data (matching Python SDK v0.1.52)
- **`deleteSession()` API**: Delete a session permanently by removing its JSONL file (matching Python SDK v0.1.51)
- **`forkSession()` API**: Fork a session into a new branch with UUID remapping, optional truncation, and title derivation. Returns `ForkSessionResult` (matching Python SDK v0.1.51)
- **Offset pagination in `listSessions()`**: New `offset` parameter for paginated session listing (matching Python SDK v0.1.51)

### Fixed
- **Setting sources flag**: No longer sends `--setting-sources ""` when setting sources list is empty or null (matching Python SDK v0.1.53)
- **String prompt in `connect()`**: String prompts are now sent via `transport.write()` directly during connection instead of being dropped (matching Python SDK v0.1.52)
- **Non-JSON stdout lines**: Lines not starting with `{` are now skipped when the JSON buffer is empty, preventing parse corruption from CLI debug output (matching Python SDK v0.1.51)
- **`CLAUDECODE` env var filtered**: SDK-spawned subprocesses no longer inherit the `CLAUDECODE` environment variable (matching Python SDK v0.1.51)
- **MCP `isError` propagation**: `ToolResult.toMap()` now uses `isError` key (was `is_error`) to match MCP protocol conventions (matching Python SDK v0.1.51)

### Synced
- Python SDK v0.1.50 → v0.1.54 (commits a7fd631..574044a)
- v0.1.51: AgentDefinition fields, ResultMessage errors/modelUsage/uuid, AssistantMessage preserved fields, delete/fork session, offset pagination, task_budget, dontAsk, SystemPromptFile, non-JSON skip, CLAUDECODE filter, MCP isError; CLI 2.1.83-2.1.85
- v0.1.52: get_context_usage, session_id option, tool_use_id/agent_id in ToolPermissionContext, control_cancel_request handling, string prompt connect fix; CLI 2.1.86-2.1.87
- v0.1.53: Fix setting-sources empty, spawn wait_for_result as task; CLI 2.1.88
- v0.1.54: AgentDefinition background/effort/permissionMode; CLI 2.1.89-2.1.90

[0.1.10]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.10

## [0.1.9] - 2026-03-22

### Added
- **Per-turn `usage` on `AssistantMessage`**: New optional `usage` field (Map) preserving the API's full usage dict (input_tokens, output_tokens, cache token breakdown) on every assistant message (matching Python SDK v0.1.50)
- **`AgentDefinition` new fields**: Added `skills` (List<String>), `memory` (String: "user"/"project"/"local"), and `mcpServers` (List<Object>) to agent definitions for richer agent configuration (matching Python SDK v0.1.50)
- **`SDKSessionInfo` new fields**: Added `tag` (user-set session tag) and `createdAt` (creation time from first entry timestamp) fields; `fileSize` changed from `long` to nullable `Long` for remote storage compatibility (matching Python SDK v0.1.50)
- **`getSessionInfo()` single-session lookup**: New `ClaudeSDK.getSessionInfo(sessionId)` and `getSessionInfo(sessionId, directory)` methods for O(1) session metadata retrieval without directory scan (matching Python SDK v0.1.50)
- **Enhanced session summary resolution**: Session summary now considers `aiTitle` (AI-generated title) and `lastPrompt` in addition to `customTitle` and `summary`, matching the updated Python SDK priority order (matching Python SDK v0.1.50)

### Changed
- **ENTRYPOINT default-if-absent**: `CLAUDE_CODE_ENTRYPOINT` is now set as a default before merging user env vars, allowing callers to override it via `ClaudeAgentOptions.env()` (matching Python SDK v0.1.50)
- **Graceful subprocess shutdown**: Transport close now waits up to 5 seconds for the subprocess to exit after stdin EOF before sending SIGTERM, preventing session file corruption (matching Python SDK v0.1.50)
- **Removed `System.setProperty` calls**: Removed `CLAUDE_CODE_ENTRYPOINT` system property setting from `ClaudeSDK` and `ClaudeSDKClient` constructors; entrypoint is now only set via process environment (matching Python SDK v0.1.50)

### Synced
- Python SDK v0.1.49 → v0.1.50 (commits 302ceb6..a7fd631)
- v0.1.50: Per-turn usage, AgentDefinition skills/memory/mcpServers, SDKSessionInfo tag/created_at, get_session_info(), aiTitle/lastPrompt summary, ENTRYPOINT override, graceful shutdown; CLI 2.1.77-2.1.81

[0.1.9]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.9

## [0.1.8] - 2026-03-15

### Added
- **Typed `RateLimitEvent` message**: `rate_limit_event` messages from the CLI are now parsed into a typed `RateLimitEvent` record (matching Python SDK v0.1.49). Previously returned `null` (forward-compat). New types:
  - `RateLimitEvent` — implements `Message`, includes `rateLimitInfo`, `uuid`, `sessionId`
  - `RateLimitInfo` — fields: `status` (`"allowed"`, `"allowed_warning"`, `"rejected"`), `resetsAt`, `rateLimitType`, `utilization`, `overageStatus`, `overageResetsAt`, `overageDisabledReason`, `raw`
- **Session mutation APIs**: Two new static methods on `ClaudeSDK` (matching Python SDK v0.1.49):
  - `renameSession(String sessionId, String title)` — rename a session by appending a `custom-title` entry to its JSONL file
  - `renameSession(String sessionId, String title, Path directory)` — rename within a specific project directory
  - `tagSession(String sessionId, String tag)` — tag a session (pass `null` to clear)
  - `tagSession(String sessionId, String tag, Path directory)` — tag within a specific project directory
  - Tags are Unicode-sanitized (removes zero-width chars, directional marks, private-use chars) for CLI filter compatibility
  - Internal `SessionMutations` class mirrors Python SDK's `_internal/session_mutations.py`

### Fixed
- **Reverted fine-grained tool streaming**: Removed automatic `CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING=1` env var when `includePartialMessages=true` (matching Python SDK revert in v0.1.49, commit 21560e3)

### Synced
- Python SDK v0.1.48 → v0.1.49 (commits d6f0352..302ceb6)
- v0.1.49: Typed `RateLimitEvent`, `rename_session`, `tag_session` APIs; CLI bumps to 2.1.72-2.1.76; revert FGTS

[0.1.8]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.8

## [0.1.6] - 2026-03-07

### Added
- **Typed MCP Status Response**: `getMcpStatus()` on `ClaudeSDKClient` now returns typed `McpStatusResponse` instead of raw `Map<String, Object>`. New types added: `McpStatusResponse`, `McpServerStatus`, `McpServerInfo`, `McpToolInfo`, `McpToolAnnotations` (matching Python SDK v0.1.45)
- **MCP Control Methods**: Three new methods on `ClaudeSDKClient`:
  - `reconnectMcpServer(String serverName)` — reconnect a disconnected or failed MCP server
  - `toggleMcpServer(String serverName, boolean enabled)` — enable or disable an MCP server
  - `stopTask(String taskId)` — stop a running task (emits `task_notification` with status `"stopped"`)
- **Typed Task System Messages**: `parseSystemMessage` now dispatches to typed subclasses (matching Python SDK v0.1.45):
  - `TaskStartedMessage` — emitted when a task starts (fields: `taskId`, `description`, `uuid`, `sessionId`, `toolUseId`, `taskType`)
  - `TaskProgressMessage` — emitted while a task is in progress (adds `usage`, `lastToolName`)
  - `TaskNotificationMessage` — emitted when a task completes/fails/stops (adds `status`, `outputFile`, `summary`, `usage`)
  - All three implement `Message` and are sealed variants of the interface
- **`stop_reason` on `ResultMessage`**: New optional `stopReason` field on `ResultMessage` (matching Python SDK v0.1.45)
- **`agent_id`/`agent_type` on tool-lifecycle hook inputs**: Added optional `agentId` and `agentType` fields to `PreToolUseHookInput`, `PostToolUseHookInput`, `PostToolUseFailureHookInput`, and `PermissionRequestHookInput` for sub-agent attribution (matching Python SDK v0.1.45)
- **Session listing APIs**: Full implementation of `ClaudeSDK.listSessions()` and `ClaudeSDK.getSessionMessages()` (matching Python SDK v0.1.45):
  - `listSessions()` — list all sessions across all projects from `~/.claude/projects/`
  - `listSessions(Path directory)` — list sessions for a specific project directory
  - `listSessions(Path directory, Integer limit, boolean includeWorktrees)` — full control
  - `getSessionMessages(String sessionId)` — retrieve full conversation history for a session
  - `getSessionMessages(String sessionId, Path directory)` — search within a specific project
  - `getSessionMessages(String sessionId, Path directory, Integer limit, int offset)` — full control
  - Sessions are read directly from JSONL files using lightweight head/tail reads (64 KB each) for listing and full reads for messages
  - Internal `Sessions` class mirrors Python SDK's `_internal/sessions.py` including path sanitization, hash algorithm, sidechain filtering, and conversation chain reconstruction
- **Session listing types**: Added `SDKSessionInfo` and `SessionMessage` record types (matching Python SDK v0.1.45)
- **New control protocol request types**: `SDKControlMcpReconnectRequest`, `SDKControlMcpToggleRequest`, `SDKControlStopTaskRequest`

### Fixed
- **Fine-grained tool streaming**: When `includePartialMessages` option is `true`, the env var `CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING=1` is now set automatically so tool input parameters stream eagerly rather than being buffered (matching Python SDK v0.1.48)

### Synced
- Python SDK v0.1.39 → v0.1.48 (commits 146e3d6..d6f0352)
- v0.1.40-v0.1.44: CLI bumps to 2.1.51-2.1.59 (no API changes)
- v0.1.45: Major API additions (task messages, MCP control, session types, stop_reason, typed MCP status)
- v0.1.46: Fix string prompt stdin closing (already correct in Java SDK); CLI 2.1.68-2.1.69
- v0.1.47: CLI bump to 2.1.70 (no API changes)
- v0.1.48: Fine-grained tool streaming for partial messages; CLI 2.1.71

[0.1.6]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.6

## [0.1.5] - 2026-02-21

### Fixed
- **Forward-Compatible Message Parsing**: `MessageParser.parse()` now returns `null` for unknown message types instead of throwing `MessageParseException`, matching Python SDK v0.1.39 behavior. This makes the SDK forward-compatible with newer CLI versions that may emit new message types (e.g., `rate_limit_event` introduced in CLI v2.1.45+). The message iterator in `QueryHandler` silently skips null messages.

### Synced
- Python SDK v0.1.36 → v0.1.39 (commits 4d74748..146e3d6)
- v0.1.37: CLI bump to 2.1.44 (no API changes)
- v0.1.38: CLI bumps to 2.1.45 and 2.1.47 (no API changes)
- v0.1.39: Fix unknown message types (rate_limit_event, etc.) to return null instead of crashing

[0.1.5]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.5

## [0.1.4] - 2026-02-16

### Added
- **ThinkingConfig Types**: Added `ThinkingConfig` sealed interface with three variants for controlling extended thinking behavior (matching Python SDK v0.1.36):
  - `ThinkingConfigAdaptive` — uses adaptive thinking with default 32,000 token budget
  - `ThinkingConfigEnabled` — enables thinking with a specified token budget
  - `ThinkingConfigDisabled` — disables extended thinking
- **Thinking Configuration Option**: Added `thinking` field to `ClaudeAgentOptions` that takes precedence over the deprecated `maxThinkingTokens` field
- **Effort Option**: Added `effort` field to `ClaudeAgentOptions` for controlling thinking depth with values: "low", "medium", "high", "max" (matching Python SDK v0.1.36)

### Changed
- Updated thinking token resolution logic in `SubprocessCLITransport` to support new `ThinkingConfig` types
- `thinking` config now takes precedence over deprecated `maxThinkingTokens` field
- Thinking config resolves to `--max-thinking-tokens` CLI flag: adaptive → 32,000 (default), enabled → budget_tokens, disabled → 0
- Effort level is passed to CLI via `--effort` flag

### Documentation
- Updated `docs/PYTHON_SDK_PARITY.md` to reflect 100% parity with Python SDK v0.1.36
- Added 4 new type definitions (ThinkingConfig, ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled)
- Updated configuration options count from 35+ to 37+ options

[0.1.4]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.4

## [0.1.3] - 2026-02-08

### Added
- **MCP Tool Annotations Support**: Added `ToolAnnotations` class with support for `readOnlyHint`, `destructiveHint`, `idempotentHint`, and `openWorldHint` to provide semantic hints about tool behavior (matching Python SDK v0.1.31)
- Annotations can be specified via `@Tool` annotation attributes or `SdkMcpTool.Builder.annotations()`
- Annotations are automatically included in MCP `tools/list` responses when set
- **LargeAgentsExample**: New example demonstrating that 260KB+ agent definitions work correctly via the initialize request, covering both `ClaudeSDKClient` and `query()` usage

### Changed
- **Agent Definitions Fix**: Agents are now sent via initialize request through stdin instead of CLI `--agents` flag, avoiding platform-specific ARG_MAX limits and enabling arbitrarily large agent definitions (260KB+) (matching Python SDK v0.1.31)
- Removed `--agents` CLI flag handling from `SubprocessCLITransport`
- Updated `SDKControlInitializeRequest` to include optional `agents` field
- All agent definitions are now passed through the control protocol initialization handshake

### Fixed
- Large agent definitions (260KB+) no longer fail silently due to command-line argument length limits
- Agents are properly registered when using both `query()` and `ClaudeSDKClient`

[0.1.3]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.3

## [0.1.2] - 2026-02-05

### Added
- **New hook events**: Added support for three new hook event types to match Python SDK v0.1.29:
  - `Notification` — for handling notification events with `NotificationHookInput` and `NotificationHookSpecificOutput`
  - `SubagentStart` — for handling subagent startup with `SubagentStartHookInput` and `SubagentStartHookSpecificOutput`
  - `PermissionRequest` — for handling permission requests with `PermissionRequestHookInput` and `PermissionRequestHookSpecificOutput`

- **Enhanced hook input types**: Added missing fields to existing hook types:
  - `PreToolUseHookInput`: added `toolUseId` field
  - `PostToolUseHookInput`: added `toolUseId` field
  - `SubagentStopHookInput`: added `agentId`, `agentTranscriptPath`, and `agentType` fields

- **Enhanced hook output types**: Added new fields to hook-specific output types:
  - `PreToolUseHookSpecificOutput`: added `additionalContext` field
  - `PostToolUseHookSpecificOutput`: added `updatedMCPToolOutput` field

- **New examples**: Added four new comprehensive examples to improve SDK documentation:
  - `AgentsExample.java` — demonstrates programmatic subagent definitions
  - `FilesystemAgentsExample.java` — shows filesystem-based agent configuration
  - `SystemPromptExample.java` — illustrates custom system prompt usage
  - `IncludePartialMessagesExample.java` — demonstrates streaming with partial message updates

### Fixed
- **AssistantMessage error field**: Ensured the `error` field in `AssistantMessage` is correctly populated from top-level response data (matching Python SDK v0.1.28 bug fix). The Java implementation already had the correct behavior with proper documentation.

### Documentation
- Updated `PYTHON_SDK_PARITY.md` with comprehensive comparison against Python SDK v0.1.29
- Verified 100% feature parity with all functional features from Python SDK v0.1.29
- Updated documentation to reflect 76+ types with complete parity
- Documented all 10 hook events with enhanced fields

[0.1.2]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.2

## [0.1.1] - 2026-01-30

### Added
- `PostToolUseFailure` hook event type for handling tool use failures
- `PostToolUseFailureHookInput` type with fields for tool name, input, use ID, error, and optional interrupt flag
- `HookSpecificOutput.postToolUseFailure()` builder method for creating hook responses

[0.1.1]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.1

## [0.1.0] - 2026-01-29

### Added
- Initial release of Claude Agent SDK for Java
- Core `ClaudeSDK` facade with static helper methods for one-shot queries
- `ClaudeSDKClient` for bidirectional, multi-turn conversations
- Support for custom MCP servers with `@Tool` annotation
- Hook system for pre/post tool use callbacks
- Permission callbacks for tool execution control
- Comprehensive example suite (15 examples covering all major features)
- Full Java 25 support with sealed interfaces and virtual threads

### Features
- One-shot queries with `ClaudeSDK.query()`
- Multi-turn conversations with `ClaudeSDKClient`
- Custom tool creation via SDK MCP servers
- File checkpointing and rewind with `rewindFiles()`
- Streaming events support
- Sandbox configuration
- Permission mode management
- Dynamic model switching
- Error handling with custom exceptions
- Automatic version detection via templating

[0.1.0]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.0
