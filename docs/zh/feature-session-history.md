# 会话历史

无需运行 CLI，即可读取和浏览历史 Claude Code 会话。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-session-history.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 目录
- [概览](#概览)
- [会话元数据](#会话元数据)
- [会话消息](#会话消息)
- [列出会话](#列出会话)
- [查找单个会话](#查找单个会话)
- [读取会话消息](#读取会话消息)
- [重命名会话](#重命名会话)
- [为会话打标签](#为会话打标签)
- [删除会话](#删除会话)
- [分叉会话](#分叉会话)
- [示例](#示例)
- [最佳实践](#最佳实践)

## 概览

Claude Code 把每次会话都以 JSONL 文件的形式存放在 `~/.claude/projects/` 下。会话历史 API 让你可以：

- **列出会话**：跨所有项目，或按指定的工作目录筛选
- **读取消息**：来自任意过往会话的完整会话记录

所有读取都直接从磁盘进行，与 CLI 无关。不会派生任何进程。

**性能：** 列举时只读取每个会话文件的首尾各 64 KB（不做完整的 JSONL 解析）。只有在用
`getSessionMessages` 获取消息时才会完整解析。

> **需要远程/多主机后端？** 请参见 [Session Store](./feature-session-store.md)。本页的每个方法在
> `ClaudeSDK` 上都有对应的 `*FromStore`（读取）或 `*ViaStore`（变更）版本，它们面向
> `SessionStore` 适配器（S3、Postgres、Redis、自定义）操作。这里记录的本地磁盘 API 仍然是规范路径；
> SessionStore API 是增补性的，并且针对同样的磁盘布局，以保证可移植性。

## 会话元数据

`SDKSessionInfo` 保存单个会话的元数据：

```java
record SDKSessionInfo(
    String sessionId,              // UUID identifying the session
    String summary,                // display title (custom title, AI title, lastPrompt, summary, or first prompt)
    long lastModified,             // last-modified time in milliseconds since epoch
    @Nullable Long fileSize,       // session file size in bytes (null for remote storage backends)
    @Nullable String customTitle,  // user-set custom title or AI-generated title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd,          // working directory for the session (may be null)
    @Nullable String tag,          // user-set session tag (may be null)
    @Nullable Long createdAt       // creation time in ms since epoch from first entry's ISO timestamp (may be null)
)
```

`summary` 字段按以下优先顺序解析：自定义标题 > AI 标题 > lastPrompt > 自动生成的摘要 > 首个提示词。

另有一个不含 `tag` 与 `createdAt` 的向后兼容构造函数。

## 会话消息

`SessionMessage` 保存会话记录中的单条消息：

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId,    // spawning Agent tool_use id (subagent reads only)
    @Nullable String parentAgentId       // spawning subagent id (nested subagents only)
)
```

只返回顶层的会话消息 —— 工具使用的支线消息、元消息以及子 agent 消息都会被过滤掉。

对于 `getSessionMessages()` / `getSessionMessagesFromStore()` 的结果，`parentToolUseId` 与
`parentAgentId` 始终为 null。它们会在 `getSubagentMessages()` /
`getSubagentMessagesFromStore()` 中被填充：`parentToolUseId` 是父会话中派生该子 agent 的那个 Agent
`tool_use` 块的 id，而当一个子 agent 派生了另一个时，`parentAgentId` 指明派生方子 agent。两者都来自
该子 agent 的 `agent-<agentId>.meta.json` 附属文件（对于存储读取，则来自代替它的 `agent_metadata`
条目），因此当该元数据缺失或不可用时两者都为 null。同一份子 agent 记录中的每条消息都携带相同的这一对值。

### 访问消息内容

`message` 字段是与 Anthropic API 传输格式一致的原始 `Map<String, Object>`：

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

## 列出会话

### 所有会话

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
```

返回所有项目下的全部会话，按最近修改时间倒序排列。

### 某个项目的会话

```java
Path projectDir = Path.of("/my/project");
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(projectDir);
```

筛选出工作目录与 `projectDir` 匹配的会话。

### 带数量上限与 worktree

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true` 会运行 `git worktree list`，并包含该仓库所有 worktree 中的会话。

### 带偏移量的分页

```java
// Page 1: first 50 sessions
List<SDKSessionInfo> page1 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 0, true);

// Page 2: next 50 sessions
List<SDKSessionInfo> page2 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 50, true);
```

## 查找单个会话

使用 `getSessionInfo` 可以按 ID 查找单个会话，而无需扫描所有会话文件。当你已经知道会话 UUID 时，
这比 `listSessions` 更高效。

### 按会话 ID（搜索所有项目）

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo("550e8400-e29b-41d4-a716-446655440000");
if (info != null) {
    System.out.println("Session: " + info.summary());
    if (info.tag() != null) {
        System.out.println("Tag: " + info.tag());
    }
    if (info.createdAt() != null) {
        String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(info.createdAt()));
        System.out.println("Created: " + created);
    }
}
```

### 限定在某个项目目录内

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);
```

若会话未找到、属于支线会话，或无法提取摘要，则返回 `null`。

## 读取会话消息

### 完整记录

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(sessionId);
```

在所有项目目录中搜索该会话 UUID。

### 限定在某个项目内

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(
    sessionId,
    Path.of("/my/project")
);
```

### 带分页

```java
// Skip first 20 messages, return next 10
List<SessionMessage> page = ClaudeSDK.getSessionMessages(
    sessionId,
    null,    // all projects
    10,      // limit
    20       // offset
);
```

## 重命名会话

通过向会话的 JSONL 文件追加一条自定义标题条目来重命名会话。最近一次重命名总是生效 —— 可以安全地
多次调用。

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

**约束：**
- `sessionId` 必须是合法的 UUID（小写十六进制，带连字符）。
- `title` 在去除首尾空白后必须非空。
- 若找不到会话的 JSONL 文件，抛出 `FileNotFoundException`。
- 若文件写入失败，抛出 `IOException`。

重命名之后，`listSessions()` 会在 `SDKSessionInfo` 的 `summary` 与 `customTitle` 字段中返回新标题。

## 为会话打标签

为会话打标签以便按组织维度筛选。传入 `null` 可清除已有标签。标签在存储前会经过 Unicode 净化，以兼容
CLI 的筛选功能。

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

**约束：**
- `sessionId` 必须是合法的 UUID。
- `tag` 在经过 Unicode 净化与去除空白后必须非空（或传 `null` 以清除）。
- 含有危险 Unicode 字符（零宽字符、方向标记、私用区字符）的标签会被自动净化。
- 若找不到会话的 JSONL 文件，抛出 `FileNotFoundException`。
- 若文件写入失败，抛出 `IOException`。

**并发安全性：** 如果该会话当前正在 CLI 进程中打开，CLI 会在下一次重新追加元数据时把 SDK 写入的条目
吸收进它的缓存。最近一次写入生效。

## 删除会话

通过删除 JSONL 文件来永久删除会话。存放子 agent 记录的同级 `<sessionId>/` 目录也会被递归删除
（尽力而为；目录不存在也没关系）。

```java
// Delete by session ID (searches all projects)
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000");

// Delete scoped to a specific project directory
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000", Path.of("/my/project"));
```

**约束：**
- `sessionId` 必须是合法的 UUID。
- 若找不到会话文件，抛出 `FileNotFoundException`。
- 若需要软删除语义，请改用 `tagSession(id, "__hidden")` 并在列举时过滤。

## 读取子 agent 记录

当会话派生子 agent 时（通过 `Task` 工具或以编程方式定义的 agent），每个子 agent 会把自己的记录写到
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`。子 agent 记录也可能位于
诸如 `subagents/workflows/<runId>/` 这样的嵌套目录中。

```java
// Enumerate subagent IDs for a session
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000");

// Or scoped to a specific project
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project"));

// Read a subagent's full conversation
List<SessionMessage> messages = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123");

// With limit and offset
List<SessionMessage> page = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123",
    Path.of("/my/project"),
    50,    // limit (null or 0 = no limit)
    0);    // offset
```

**行为：**
- `listSubagents` 会递归扫描 `subagents/` 目录树，寻找匹配 `agent-<id>.jsonl` 的文件，并按目录遍历
  顺序返回这些 ID。
- `getSubagentMessages` 会从叶子节点沿 `parentUuid` 链回溯以重建整条链。子 agent 记录是线性的
  （没有压缩，也没有支线），因此返回的列表就是按时间顺序排列的完整会话。
- 损坏的 JSONL 行会被静默跳过。
- 无效的 UUID、不存在的会话、不存在的 agent，以及空的 agent ID，都会返回空列表（绝不抛出异常）。

## 分叉会话

把会话分叉成一个带全新 UUID 的新分支。它会复制源会话的会话记录消息，重映射每条消息的 UUID，并保留
`parentUuid` 链。分叉出来的会话没有撤销历史。

```java
// Fork a session (searches all projects)
ForkSessionResult result = ClaudeSDK.forkSession("550e8400-e29b-41d4-a716-446655440000");
System.out.println("New session: " + result.sessionId());

// Fork scoped to a project directory
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);

// Fork from a specific message (truncate transcript)
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    null,                                       // search all projects
    "660e8400-e29b-41d4-a716-446655440001",    // slice transcript at this message
    "My Fork Title"                            // custom title (null = original + " (fork)")
);
```

**`ForkSessionResult`** 包含：
- `sessionId` —— 新建分叉会话的 UUID

**约束：**
- `sessionId` 以及可选的 `upToMessageId` 必须是合法的 UUID。
- 若找不到源会话，抛出 `FileNotFoundException`。
- 若会话没有任何消息，或找不到 `upToMessageId`，抛出 `IllegalArgumentException`。

`forkSession()` 会在不运行 CLI 的情况下生成一份离线副本。若你想从更早的时点*恢复*并继续对话，
请改用截断式恢复。

## 截断式恢复

`resumeSessionAt` 只会把所恢复的会话加载到指定的会话记录条目 UUID（含）为止，之后的内容全部丢弃。
与 `forkSession(true)` 配合，它会分叉到一个新会话，并保持原会话不受影响。

`resumeDropsTurn` 让这种截断变得**安全**。把你打算丢弃的那一轮用户提示词的 UUID 交给它，CLI 就会在
加载时校验分叉点之后的每一个条目都属于该轮次 —— 否则予以拒绝。没有它的话，会话在轮次中途吸收的、
而你从未观察到的排队用户消息或后台任务通知，就会被悄无声息地丢弃。

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(projectDir)
    .resume(sessionId)
    .forkSession(true)               // branch; leave the source session intact
    .resumeSessionAt(keepAtUuid)     // last transcript entry of the turn to keep
    .resumeDropsTurn(nextPromptUuid) // prompt UUID of the turn being discarded
    .build();

for (Message msg : ClaudeSDK.query("Reply with exactly: three", options)) {
    if (msg instanceof ResultMessage r) {
        System.out.println("Forked session: " + r.sessionId());
    }
}
```

**如何选取这两个 UUID。** 把 `resumeSessionAt` 设为你要保留那一轮的*最后一个*会话记录条目
（无论其类型），把 `resumeDropsTurn` 设为紧随其后那一轮的提示词 UUID。两者都可以从
`ClaudeSDK.getSessionMessages(sessionId, cwd)` 中读出：找到你想保留的条目，然后取下一个 `type()` 为
`"user"` 的 `SessionMessage` 的 `uuid()`。实时观察到的 `AssistantMessage.uuid()` 同样可以作为分叉点。

请注意，在使用结构化输出（`outputFormat`）或会结束轮次的 MCP 工具时，被保留轮次的结束条目位于其
最后一条助手消息*之后* —— 因此在那些情况下，按助手消息 UUID 分叉会被按设计拒绝。

**处理拒绝。** 拒绝会以异常的形式到来，其消息中含有 `Resume rejected by --resume-drops-turn:`：

```java
try {
    ClaudeSDK.query(prompt, options);
} catch (ClaudeSDKException e) {
    if (String.valueOf(e.getMessage()).contains("Resume rejected by --resume-drops-turn:")) {
        // Deterministic — the transcript is not what we assumed.
        // Clear the fork target and resume plainly; do not retry as-is.
    }
}
```

请把它视为确定性的结果：原样重试只会以同样的方式失败。若要保留旧的、不带校验的截断行为，
请不要设置 `resumeDropsTurn`。

从 `SessionStore` 恢复时同样会转发这两个选项。可运行的端到端演示请参见
[`TruncatingResumeExample`](../../examples/src/main/java/examples/TruncatingResumeExample.java)。

## 示例

### 示例 1：列出最近的会话

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

### 示例 2：当前项目的会话

```java
Path cwd = Path.of(System.getProperty("user.dir"));
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
for (SDKSessionInfo session : sessions) {
    String sizeStr = (session.fileSize() != null)
            ? String.format("%.1f KB", session.fileSize() / 1024.0) : "N/A";
    System.out.printf("  %s (%s)%n", session.summary(), sizeStr);
}
```

### 示例 3：读取最近一次会话的消息

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

### 示例 4：按提示词关键字查找会话

```java
List<SDKSessionInfo> all = ClaudeSDK.listSessions();

List<SDKSessionInfo> matching = all.stream()
    .filter(s -> s.summary().toLowerCase().contains("refactor"))
    .toList();

System.out.println("Found " + matching.size() + " sessions about refactoring");
```

### 示例 5：重命名最近一次会话

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (!sessions.isEmpty()) {
    String sessionId = sessions.get(0).sessionId();
    ClaudeSDK.renameSession(sessionId, "Important: Production Bug Fix");
    System.out.println("Renamed session " + sessionId);
}
```

### 示例 6：按项目阶段为会话打标签

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

### 示例 7：清除标签

```java
// Retrieve a session and clear its tag
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
for (SDKSessionInfo session : sessions) {
    if ("old-tag".equals(session.customTitle())) {
        ClaudeSDK.tagSession(session.sessionId(), null);
    }
}
```

## 最佳实践

### 尽可能使用目录筛选

```java
// Efficient: scoped to one project
ClaudeSDK.listSessions(Path.of("/my/project"));

// Less efficient: scans all projects
ClaudeSDK.listSessions();
```

### 检查空结果

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(dir);
if (sessions.isEmpty()) {
    // No sessions yet — run Claude Code in this directory first
}
```

### 处理未设置的 CLAUDE_CONFIG_DIR

默认情况下，会话存放在 `~/.claude/projects/` 下。如果设置了环境变量 `CLAUDE_CONFIG_DIR`，它会覆盖
该位置。SDK 会自动遵循这个变量。

### 用 limit 避免过大的结果集

```java
// Return only the 20 most recent
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(null, 20, false);
```

## 另见

- [API 参考：ClaudeSDK](./api-claude-sdk.md#会话历史方法) —— 方法签名
- [Session Store](./feature-session-store.md) —— 基于外部存储的等价方法（`*FromStore`/`*ViaStore`）以及写入时镜像的集成
- [会话列举示例](../../examples/src/main/java/examples/SessionListingExample.java) —— 完整可运行的示例
- [交互式会话](./feature-interactive-conversations.md) —— 管理实时会话
