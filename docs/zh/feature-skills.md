# Skills

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-skills.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

`ClaudeAgentOptions` 上的 `skills` 选项，是为主会话启用 Claude Code skills 的唯一入口。SDK 会
自动串联 `allowedTools` 与 `settingSources`，调用方无需手动分别配置两者。

> **什么是 skill？** skill 是一个可复用的能力包，安装在 `.claude/skills/<name>/SKILL.md`（项目
> 作用域）或 `~/.claude/skills/<name>/SKILL.md`（用户作用域）。skill 通过内置的 `Skill` 工具调用。
> 关于如何编写 skill，请参阅 Claude Code 文档。

## 快速上手

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Mode 1 — enable every discovered skill
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .build();

// Mode 2 — enable only specific skills
var options = ClaudeAgentOptions.builder()
    .skills(List.of("commit", "review"))
    .build();

// Mode 3 — suppress every skill from the listing
var options = ClaudeAgentOptions.builder()
    .skills(List.of())
    .build();

// Mode 4 (default) — no SDK auto-configuration; CLI defaults apply
var options = ClaudeAgentOptions.builder().build();
```

## 各种模式

| Builder 调用 | 注入到 `allowedTools` | `settingSources` 默认值 | initialize 协议字段 |
|---|---|---|---|
| _未设置_ | 无 | 无 | 省略 |
| `.skillsAll()` | 添加裸的 `Skill` | `[user, project]` | 省略 |
| `.skills(List.of("a", "b"))` | 添加 `Skill(a)`、`Skill(b)` | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | 无 | `[user, project]` | `[]` |

说明：

- **`null` ≠ 关闭 skills。** 不设置该选项会保留 CLI 的默认行为。若要让模型的列表中不出现任何
  skill，请传入空列表。
- **`"all"` 与未设置在协议层面是等价的。** 两者都会在 initialize 控制请求中省略 `skills` 字段。
  CLI 把省略视为"无过滤"。
- **空列表会真正发送到协议上。** `List.of()` 在 initialize 请求中会变成 `"skills": []`，告知支持
  该字段的 CLI 不要把任何 skill 加载进系统提示词。

## 自动串联的工作方式

`SubprocessCLITransport.applySkillsDefaults()` 会在派生子进程之前构建实际生效的 CLI 标志。原始的
`ClaudeAgentOptions` 永远不会被修改。

对于 `skillsAll()`：

- 如果 `allowedTools` 中尚不包含 `"Skill"`，会追加这个裸工具。
- 如果 `settingSources` 为 null，会默认取 `[USER, PROJECT]`，以便 CLI 能发现已安装的 skill。
- 显式设置的 `settingSources(...)` 始终优先于该默认值。

对于 `skills(List.of(...))`：

- 对每个名称 `n`，向 `allowedTools` 追加 `Skill(n)`（并与已有条目去重）。
- `settingSources` 的默认行为与上面相同。

对于 `skills(List.of())`：

- `allowedTools` 保持不变。
- `settingSources` 的默认行为与上面相同。

## 名称校验（0.1.22）

传给 `skills(List.of(...))` 的名称，会在被格式化进 CLI 的 `--allowedTools` 取值之前接受校验。任何
被拒绝的情形都会在**连接时**抛出 `IllegalArgumentException` —— 来自 `buildCommand()`，早于 CLI
子进程被派生。

之所以需要校验，是因为 `--allowedTools` 是单个字符串，CLI 会按括号外的逗号和空格把它拆分成权限
规则，而那个分词器不识别任何转义序列。转义只存在于单条规则的语法中，且在拆分*之后*才应用，因此
携带分隔符的名称无法被可靠地传递 —— 它会被分成什么，取决于它周围是什么：

```java
// Before 0.1.22 this emitted --allowedTools "Skill(x),Bash(*)",
// silently granting the session unrestricted Bash.
ClaudeAgentOptions.builder()
    .skills(List.of("x),Bash(*"))
    .build();

// 0.1.22: IllegalArgumentException at connect()
// "Invalid skill name 'x),Bash(*': parentheses, commas, control characters,
//  and byte-order marks are not allowed. ..."
```

### 被拒绝的形式

| 形式 | 示例 | 原因 |
|---|---|---|
| 括号或逗号 | `"x),Bash(*"`、`"a,b"`、`"()"` | 规则分隔符 —— 即上文的注入途径 |
| 控制字符 | C0（`\n`、`\t`、`\u0000`）、DEL（`\u007F`）、C1（`\u0080`–`\u009F`） | 永远不会出现在 skill 目录名中 |
| 字节序标记 | 名称中任意位置的 `﻿` | CLI 会把 U+FEFF 当作空白裁掉；规则最终指向的会是另一个 skill |
| 空或仅含空白 | `""`、`" "`、`"  \t "` | 没有指向任何东西 |
| 裸通配符 | `"*"` | 请改用 `skillsAll()` |
| 通配符后缀 | `"pdf:*"`、`"my skill *"` | 请按精确名称逐个列出 skill |
| 首尾空白 | `" pdf"`、`"pdf "` | 永远无法匹配 —— `Skill` 工具会裁掉被调用名称的首尾空白 |
| 以 `/` 开头 | `"/commit"` | 该选项接受的是规范名称，而不是斜杠命令形式 |
| 连续反斜杠 | `"mid\\\\dle"` | 单条规则的解析器会把它们折叠，导致规则指向另一个 skill |
| 结尾处孤立的反斜杠 | `"name\\"` | 悬空的转义 |
| 不成对的代理项 | 单独一个 `\ud800` | 永远无法匹配 CLI 发现的任何名称 |

只有前三行属于注入途径。其余的都能被干净地分词，但会构造出永远无法匹配你所指定 skill 的规则 ——
之所以拒绝它们，是为了让失败在 `connect()` 时大声报错，而不是让某个 skill 在会话中悄无声息地缺席。
上表中的字符串示例都以 Java 源码字面量形式给出，因此 `"mid\\\\dle"` 表示一个含有两个反斜杠的
名称，而 `"dir\\sub"`（被接受）表示含有一个反斜杠。

### 被接受的名称

普通名称不受影响。下面这些依然会生成与以前完全相同的 argv：

```java
.skills(List.of(
    "pdf-tools",          // hyphens
    "my_skill.v2",        // underscores, dots
    "myplugin:pdf",       // plugin-qualified
    "skill with spaces",  // interior spaces are fine; only surrounding ones are not
    "dir\\sub",           // a single backslash
    "日本語スキル"          // non-ASCII
))
```

### 破坏性变更

有两种此前被接受的形式现在会抛出异常：

| 原写法 | 旧行为 | 现在 |
|---|---|---|
| `skills(List.of("*"))`、`skills(List.of("plugin:*"))` | 构造出一条通配符规则 | 抛出异常 —— 请改用 `skillsAll()`，或者直接向 `allowedTools` 添加一条 `Skill(...)` 条目来做前缀匹配 |
| `skills(List.of(" name"))`、`skills(List.of("/name"))` | 构造出一条什么都匹配不到的规则，于是该 skill **悄无声息地不可用** | 抛出异常，并指出问题所在 |

### Java 特有的行为

有两项检查刻意与 Python SDK 不同，因为两种语言对字符串的建模方式不同：

- **代理项。** Python 会拒绝每一个代理码点 —— 这在 Python 中是合理的，因为 Python 的 `str` 存放的
  是码点，星平面字符是单个非代理项元素，所以只要出现代理项，从构造上就必然是不成对的。Java 的
  字符串是 UTF-16，其中星平面字符本来*就是*一对高/低代理项。因此 Java 只拒绝**孤立的**代理项；
  像 `"𝕤kill"` 这样的名称是被接受的。
- **空白。** `String.strip()` 遵循 `Character.isWhitespace`，这会漏掉 Python 的 `str.strip()` 会
  去掉的不换行空格（U+00A0、U+2007、U+202F）。填充检查把它与 `Character.isSpaceChar` 取并集，
  因此那些字符也会被捕获。U+FEFF 不在这两者之中，会作为非法字符被拒绝，与 Python 中的处理完全一致。

`skillsAll()` 不做名称检查 —— 根本没有名称可检查 —— 而 `skills(List.of())` 仍然是有效的空操作。

## Initialize 协议

skills 也会通过 SDK 控制协议、经由 `SDKControlInitializeRequest.skills` 传递。只有显式给出的列表
才会被发送；`"all"` 与 `null` 都会省略该字段。

不认识 `skills` initialize 字段的旧版 CLI 会忽略它 —— 自动注入的 `allowedTools` 条目仍然有效。

> **废弃说明：** 在 `allowedTools(...)`（或 `AgentDefinition.tools`）中直接传入裸的 `"Skill"` 记号
> 已**废弃**。请改用 `skillsAll()` / `skills(List.of(...))` —— 它们会配置好所需的一切（包括放行
> `Skill` 工具），并避免 `allowedTools` 与协议层 `skills` 字段之间产生漂移。

## 示例

### 与显式的 `allowedTools` 混用

skills 是对已有白名单的补充，绝不会替换它：

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write"))
    .skills(List.of("commit"))
    .build();
// effective allowedTools: [Read, Write, Skill(commit)]
```

### 幂等注入

如果你已经把 `Skill` 或 `Skill(name)` 加入白名单，SDK 不会重复添加：

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Skill(pdf)"))
    .skills(List.of("pdf"))
    .build();
// effective allowedTools: [Skill(pdf)]   (not [Skill(pdf), Skill(pdf)])
```

### 保留显式设置的 `settingSources`

```java
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .settingSources(List.of(SettingSource.LOCAL))
    .build();
// effective settingSources: [LOCAL]   (your value wins over the [USER, PROJECT] default)
```

## 安全提示

`skills` 选项是一个**上下文过滤器，而不是沙箱。** 未列出的 skill 会从模型的 skill 列表中隐藏，
也无法通过 `Skill` 工具调用，但它们的文件仍然留在磁盘上 —— 拥有 `Read` 或 `Bash` 的会话依然可以
直接访问 `.claude/skills/**`。

要做到硬隔离：

- 把 `cwd` 指向一个其 `.claude/skills/` 只包含所需子集的目录，**或者**
- 针对 skill 路径为 `Read`/`Bash` 添加权限拒绝规则。

内置的 skill 与已安装插件提供的 skill，无论 `settingSources` 如何都会被发现。`skills` 白名单是把
它们从模型列表中隐藏起来的唯一机制。

**不要把机密信息存放在 skill 文件里。**

## 完整示例

可运行的三种模式演示，请参见
[`examples/SkillsExample.java`](../../examples/src/main/java/examples/SkillsExample.java)。

```java
package examples;

import java.util.List;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;

public class SkillsExample {
    public static void main(String[] args) throws Exception {
        var options = ClaudeAgentOptions.builder()
            .skillsAll()
            .maxTurns(1)
            .build();
        ClaudeSDK.query("List the skills you have available.", options);
    }
}
```

## 另见

- [配置选项](./feature-configuration-options.md) —— 完整的 builder API
- [Agent 定义](./feature-agents.md) —— `AgentDefinition` 上的 `skills` 字段（针对单个子 agent 的白名单）
- [会话历史](./feature-session-history.md) —— 从磁盘读取会话记录
