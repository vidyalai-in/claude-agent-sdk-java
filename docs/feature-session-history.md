# Session History

Read and browse historical Claude Code conversation sessions without running the CLI.

## Table of Contents
- [Overview](#overview)
- [Session Metadata](#session-metadata)
- [Session Messages](#session-messages)
- [Listing Sessions](#listing-sessions)
- [Reading Session Messages](#reading-session-messages)
- [Renaming Sessions](#renaming-sessions)
- [Tagging Sessions](#tagging-sessions)
- [Examples](#examples)
- [Best Practices](#best-practices)

## Overview

Claude Code stores every conversation as a JSONL file under `~/.claude/projects/`. The session history API lets you:

- **List sessions** across all projects or filtered by a specific working directory
- **Read messages** from any past session — the full conversation transcript

All reading is done directly from disk, independent of the CLI. No process is spawned.

**Performance:** For listing, only the first and last 64 KB of each session file are read (no full JSONL parse). Full parsing is done only when retrieving messages with `getSessionMessages`.

## Session Metadata

`SDKSessionInfo` holds metadata for a single session:

```java
record SDKSessionInfo(
    String sessionId,          // UUID identifying the session
    String summary,            // display title (custom title, auto-summary, or first prompt)
    long lastModified,         // last-modified time in milliseconds since epoch
    long fileSize,             // session file size in bytes
    @Nullable String customTitle,  // user-set title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd           // working directory for the session (may be null)
)
```

The `summary` field is resolved in priority order: custom title > auto-generated summary > first prompt.

## Session Messages

`SessionMessage` holds a single message from a session transcript:

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId     // null for top-level messages
)
```

Only top-level conversation messages are returned — tool-use sidechain messages, meta messages, and subagent messages are filtered out.

### Accessing Message Content

The `message` field is a raw `Map<String, Object>` matching the Anthropic API wire format:

```java
SessionMessage msg = ...;
if (msg.message() instanceof Map<?, ?> m) {
    Object content = m.get("content");
    if (content instanceof String text) {
        System.out.println(text);
    } else if (content instanceof List<?> blocks) {
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

## Listing Sessions

### All sessions

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
```

Returns all sessions across all projects, sorted by most-recently-modified first.

### Sessions for a project

```java
Path projectDir = Path.of("/my/project");
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(projectDir);
```

Filters to sessions whose working directory matches `projectDir`.

### With limit and worktrees

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true` runs `git worktree list` and includes sessions from all worktrees of the repository.

## Reading Session Messages

### Full transcript

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(sessionId);
```

Searches all project directories for the session UUID.

### Scoped to a project

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(
    sessionId,
    Path.of("/my/project")
);
```

### With pagination

```java
// Skip first 20 messages, return next 10
List<SessionMessage> page = ClaudeSDK.getSessionMessages(
    sessionId,
    null,    // all projects
    10,      // limit
    20       // offset
);
```

## Renaming Sessions

Rename a session by appending a custom-title entry to its JSONL file. The most recent rename always wins — safe to call multiple times.

```java
// Rename by session ID (searches all projects)
ClaudeSDK.renameSession("550e8400-e29b-41d4-a716-446655440000", "My Feature Branch Session");

// Rename scoped to a specific project directory
ClaudeSDK.renameSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "My Feature Branch Session",
    Path.of("/my/project")
);
```

**Constraints:**
- `sessionId` must be a valid UUID (lowercase hex with hyphens).
- `title` must be non-empty after stripping leading/trailing whitespace.
- Throws `FileNotFoundException` if the session JSONL file is not found.
- Throws `IOException` if the file write fails.

After renaming, `listSessions()` returns the new title in the `summary` and `customTitle` fields of `SDKSessionInfo`.

## Tagging Sessions

Tag a session for organizational filtering. Pass `null` to clear an existing tag. Tags are Unicode-sanitized before storage for CLI filter compatibility.

```java
// Tag a session (searches all projects)
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", "production");

// Clear a tag
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", null);

// Tag scoped to a specific project directory
ClaudeSDK.tagSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "staging",
    Path.of("/my/project")
);
```

**Constraints:**
- `sessionId` must be a valid UUID.
- `tag` must be non-empty after Unicode sanitization and whitespace stripping (or `null` to clear).
- Tags with dangerous Unicode characters (zero-width chars, directional marks, private-use chars) are automatically sanitized.
- Throws `FileNotFoundException` if the session JSONL file is not found.
- Throws `IOException` if the file write fails.

**Concurrent safety:** If the session is currently open in the CLI process, the CLI absorbs SDK-written entries into its cache on next metadata re-append. The most recent write wins.

## Examples

### Example 1: List recent sessions

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 5, true);

DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

for (SDKSessionInfo session : sessions) {
    String time = fmt.format(Instant.ofEpochMilli(session.lastModified()));
    System.out.printf("[%s] %s%n", time, session.summary());
    System.out.printf("  id:  %s%n", session.sessionId());
    if (session.cwd() != null) {
        System.out.printf("  cwd: %s%n", session.cwd());
    }
    if (session.gitBranch() != null) {
        System.out.printf("  git: %s%n", session.gitBranch());
    }
}
```

### Example 2: Sessions for current project

```java
Path cwd = Path.of(System.getProperty("user.dir"));
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
for (SDKSessionInfo session : sessions) {
    System.out.printf("  %s (%.1f KB)%n",
        session.summary(), session.fileSize() / 1024.0);
}
```

### Example 3: Read messages from most recent session

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (sessions.isEmpty()) {
    System.out.println("No sessions found.");
    return;
}

SDKSessionInfo recent = sessions.get(0);
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(recent.sessionId());

System.out.printf("Session: %s (%d messages)%n",
    recent.summary(), messages.size());

for (SessionMessage msg : messages) {
    System.out.printf("%n[%s]%n", msg.type().toUpperCase());
    if (msg.message() instanceof Map<?, ?> m) {
        Object content = m.get("content");
        if (content instanceof String text) {
            System.out.println(text);
        } else if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            Object first = ((List<?>) blocks).get(0);
            if (first instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

### Example 4: Find session by prompt keyword

```java
List<SDKSessionInfo> all = ClaudeSDK.listSessions();

List<SDKSessionInfo> matching = all.stream()
    .filter(s -> s.summary().toLowerCase().contains("refactor"))
    .toList();

System.out.println("Found " + matching.size() + " sessions about refactoring");
```

### Example 5: Rename the most recent session

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (!sessions.isEmpty()) {
    String sessionId = sessions.get(0).sessionId();
    ClaudeSDK.renameSession(sessionId, "Important: Production Bug Fix");
    System.out.println("Renamed session " + sessionId);
}
```

### Example 6: Tag sessions by project phase

```java
// Tag a session after a query completes, using the result's session ID
List<Message> messages = ClaudeSDK.query(prompt, options);
for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        ClaudeSDK.tagSession(result.sessionId(), "sprint-42");
        break;
    }
}
```

### Example 7: Clear a tag

```java
// Retrieve a session and clear its tag
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
for (SDKSessionInfo session : sessions) {
    if ("old-tag".equals(session.customTitle())) {
        ClaudeSDK.tagSession(session.sessionId(), null);
    }
}
```

## Best Practices

### Use directory filtering when possible

```java
// Efficient: scoped to one project
ClaudeSDK.listSessions(Path.of("/my/project"));

// Less efficient: scans all projects
ClaudeSDK.listSessions();
```

### Check for empty results

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(dir);
if (sessions.isEmpty()) {
    // No sessions yet — run Claude Code in this directory first
}
```

### Handle missing CLAUDE_CONFIG_DIR

By default, sessions are stored under `~/.claude/projects/`. If the environment variable `CLAUDE_CONFIG_DIR` is set, it overrides the location. The SDK respects this variable automatically.

### Use limit to avoid large result sets

```java
// Return only the 20 most recent
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(null, 20, false);
```

## See Also

- [API Reference: ClaudeSDK](./api-claude-sdk.md#session-history-methods) - Method signatures
- [Session Listing Example](../examples/src/main/java/examples/SessionListingExample.java) - Complete runnable example
- [Interactive Conversations](./feature-interactive-conversations.md) - Managing live sessions
