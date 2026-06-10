# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
