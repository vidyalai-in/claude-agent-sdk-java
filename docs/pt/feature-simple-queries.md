# Consultas simples

As consultas simples são a forma mais direta de interagir com o Claude em operações pontuais e sem
estado, usando a fachada `ClaudeSDK`.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-simple-queries.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Quando usar consultas simples](#quando-usar-consultas-simples)
- [Uso básico](#uso-básico)
- [Métodos de consulta](#métodos-de-consulta)
- [Opções de configuração](#opções-de-configuração)
- [Tratamento de mensagens](#tratamento-de-mensagens)
- [Exemplos](#exemplos)
- [Boas práticas](#boas-práticas)

## Visão geral

A classe `ClaudeSDK` oferece métodos estáticos para consultas simples e sem estado. Ela cuida de toda
a complexidade de:
- Criar e gerenciar o transporte
- Preparar o QueryHandler
- Interpretar as mensagens
- Liberar os recursos

**Características principais:**
- **Unidirecional**: envia todas as mensagens de uma vez e recebe todas as respostas
- **Sem estado**: cada consulta é independente
- **Simples**: no estilo "dispare e esqueça"
- **Sem interrupções**: não é possível interromper nem enviar mensagens de acompanhamento
- **Limpeza automática**: os recursos são gerenciados internamente

## Quando usar consultas simples

### ✅ Bons casos de uso

1. **Perguntas avulsas**
   ```java
   ClaudeSDK.query("What is the capital of France?");
   ```

2. **Processamento em lote**
   ```java
   for (String prompt : prompts) {
       List<Message> result = ClaudeSDK.query(prompt, options);
       processResult(result);
   }
   ```

3. **Geração de código**
   ```java
   String code = ClaudeSDK.queryForText(
       "Generate a Java function to reverse a string",
       options);
   ```

4. **Pipelines de CI/CD**
   ```java
   String review = ClaudeSDK.queryForText(
       "Review this code for security issues: " + code,
       options);
   ```

5. **Scripts automatizados**
   ```java
   ResultMessage result = ClaudeSDK.queryForResult(
       "Analyze this log file",
       options);
   System.out.println("Cost: $" + result.totalCostUsd());
   ```

### ❌ Não serve para

1. **Conversas interativas** — use `ClaudeSDKClient`
2. **Interfaces de chat** — use `ClaudeSDKClient` para múltiplos turnos
3. **Perguntas de acompanhamento** — use `ClaudeSDKClient` para manter o contexto
4. **Capacidade de interromper** — use `ClaudeSDKClient` para controle
5. **Sessões de longa duração** — use `ClaudeSDKClient` para manter estado

## Uso básico

### A consulta mais simples (opções padrão)

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;

List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
```

### Consulta com opções

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
```

### Obter só o texto

```java
String answer = ClaudeSDK.queryForText(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println(answer); // "4"
```

### Obter a mensagem de resultado

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Stop reason: " + result.stopReason());
```

## Métodos de consulta

### 1. query(String prompt)

Executa uma consulta com as opções padrão.

```java
List<Message> messages = ClaudeSDK.query("Hello, Claude!");
```

**Retorna**: `List<Message>` — todas as mensagens da conversa

### 2. query(String prompt, ClaudeAgentOptions options)

Executa uma consulta com opções próprias.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("Hello!", options);
```

**Parâmetros**:
- `prompt` — a pergunta ou instrução
- `options` — opções de configuração

**Retorna**: `List<Message>` — todas as mensagens da conversa

**Lança**:
- `IllegalArgumentException` — se `canUseTool` e `permissionPromptToolName` forem ambos definidos
- `CLIConnectionException` — se a conexão falhar
- `ProcessException` — se o processo do CLI falhar

### 3. query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

Executa uma consulta por streaming com várias mensagens.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First message")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Follow-up"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

**Parâmetros**:
- `messageStream` — iterador de dicionários de mensagem
- `options` — opções de configuração

**Retorna**: `List<Message>` — todas as mensagens da conversa

**Formato da mensagem**:
```java
{
    "type": "user",
    "session_id": "default",
    "message": {
        "role": "user",
        "content": "Your message here"
    }
}
```

### 4. queryForText(String prompt, ClaudeAgentOptions options)

Método de conveniência que devolve só o conteúdo de texto das mensagens do assistente.

```java
String text = ClaudeSDK.queryForText(
    "What is the capital of France?",
    ClaudeAgentOptions.defaults()
);
```

**Retorna**: `String` — o conteúdo de texto de todas as mensagens do assistente, concatenado

### 5. queryForResult(String prompt, ClaudeAgentOptions options)

Método de conveniência que devolve só a mensagem de resultado.

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "Analyze this code",
    options
);

System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Input tokens: " + result.usageInput());
System.out.println("Output tokens: " + result.usageOutput());
```

**Retorna**: `ResultMessage` — o resultado final, ou null se não houver

## Opções de configuração

### Opções essenciais para consultas simples

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    // Model selection
    .model("claude-sonnet-4-5")
    .fallbackModel("claude-haiku-4-5")

    // Limits
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .maxThinkingTokens(10000)

    // System prompt
    .systemPrompt("You are a helpful assistant. Be concise.")

    // Tools
    .allowedTools(List.of("Read", "Grep"))
    .disallowedTools(List.of("Write", "Edit"))

    // Permissions
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)

    // Working directory
    .cwd(Path.of("/path/to/project"))

    // Environment
    .env(Map.of("KEY", "value"))

    .build();
```

### Padrões comuns

#### Consulta rápida somente leitura
```java
var options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .maxTurns(5)
    .build();
```

#### Consulta limitada por orçamento
```java
var options = ClaudeAgentOptions.builder()
    .maxBudgetUsd(0.10)  // Limit to 10 cents
    .maxTurns(3)
    .model("claude-haiku-4-5")  // Use cheaper model
    .build();
```

#### Consulta rápida de um único turno
```java
var options = ClaudeAgentOptions.builder()
    .maxTurns(1)
    .model("claude-haiku-4-5")
    .systemPrompt("Be extremely concise.")
    .build();
```

## Tratamento de mensagens

### Processando todas as mensagens

```java
List<Message> messages = ClaudeSDK.query("Hello!", options);

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());
            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Used tool: " + tool.name());
                }
            }
        }

        case ResultMessage result ->
            System.out.println("Cost: $" + result.totalCostUsd());

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            // Usually not present in simple queries
            System.out.println("Stream event: " + event);
    }
}
```

### Extraindo informações específicas

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

// Get last assistant message
AssistantMessage lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second)
    .orElse(null);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n"));

// Get result
ResultMessage result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst()
    .orElse(null);
```

## Exemplos

### Exemplo 1: revisão de código

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class CodeReview {
    public static void main(String[] args) {
        String code = """
            public void processUser(User user) {
                db.save(user);  // No null check!
            }
            """;

        var options = ClaudeAgentOptions.builder()
            .systemPrompt("You are a code reviewer. Focus on bugs and security.")
            .maxTurns(1)
            .build();

        String review = ClaudeSDK.queryForText(
            "Review this code for issues:\n" + code,
            options
        );

        System.out.println(review);
    }
}
```

### Exemplo 2: tradução em lote

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class Translator {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-haiku-4-5")  // Fast and cheap
            .maxTurns(1)
            .systemPrompt("Translate to French. Return only the translation.")
            .build();

        List<String> phrases = List.of(
            "Hello, how are you?",
            "The weather is nice today.",
            "I love programming."
        );

        for (String phrase : phrases) {
            String translation = ClaudeSDK.queryForText(phrase, options);
            System.out.println(phrase + " -> " + translation);
        }
    }
}
```

### Exemplo 3: análise de logs

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import java.nio.file.Path;

public class LogAnalyzer {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .cwd(Path.of("/path/to/logs"))
            .allowedTools(List.of("Read", "Grep"))
            .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
            .maxTurns(10)
            .build();

        String analysis = ClaudeSDK.queryForText(
            "Analyze error.log and summarize all ERROR level messages",
            options
        );

        System.out.println(analysis);
    }
}
```

### Exemplo 4: consulta atenta ao custo

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

public class CostAwareQuery {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .maxBudgetUsd(0.05)  // 5 cent limit
            .build();

        ResultMessage result = ClaudeSDK.queryForResult(
            "Explain quantum computing",
            options
        );

        if (result != null) {
            System.out.println("Cost: $" + result.totalCostUsd());
            System.out.println("Input tokens: " + result.usageInput());
            System.out.println("Output tokens: " + result.usageOutput());
            System.out.println("Stop reason: " + result.stopReason());
        }
    }
}
```

## Boas práticas

### 1. Use opções adequadas

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .build();

// ❌ Bad: No limits
ClaudeSDK.query(longComplexTask);  // Could be expensive!
```

### 2. Trate todos os tipos de mensagem

```java
// ✅ Good: Pattern matching handles all types
switch (message) {
    case AssistantMessage a -> process(a);
    case ResultMessage r -> logCost(r);
    case UserMessage u -> log(u);
    case SystemMessage s -> log(s);
    case StreamEvent e -> log(e);
}

// ❌ Bad: Only handling one type
if (message instanceof AssistantMessage) {
    // Missing other types!
}
```

### 3. Use os métodos de conveniência quando fizer sentido

```java
// ✅ Good: Simple use case
String answer = ClaudeSDK.queryForText(prompt, options);

// ❌ Overkill: Manual extraction
List<Message> messages = ClaudeSDK.query(prompt, options);
String answer = messages.stream()...  // Complex extraction
```

### 4. Defina o diretório de trabalho para operações com arquivos

```java
// ✅ Good: Explicit working directory
var options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/project/root"))
    .allowedTools(List.of("Read", "Write"))
    .build();

// ❌ Bad: Using current directory (unpredictable)
ClaudeSDK.query("Read config.json", options);
```

### 5. Escolha o modelo certo

```java
// ✅ Good: Match model to task
var fastOptions = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Quick, simple tasks
    .build();

var complexOptions = ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")  // Complex reasoning
    .build();

// ❌ Bad: Using opus for simple tasks (expensive)
ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")
    .build();
ClaudeSDK.query("What is 2+2?", options);  // Overkill!
```

### 6. Use streaming em cenários de múltiplos turnos

```java
// ✅ Good: Streaming for multiple messages
var messages = List.of(
    Map.of("type", "user", ...),
    Map.of("type", "user", ...)
);
ClaudeSDK.query(messages.iterator(), options);

// ❌ Bad: Multiple separate queries (loses context)
ClaudeSDK.query("First question", options);
ClaudeSDK.query("Follow-up", options);  // No context!
```

### 7. Trate os erros

```java
// ✅ Good: Handle exceptions
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
    // Process messages
} catch (CLIConnectionException e) {
    System.err.println("Failed to connect: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI process failed: " + e.getMessage());
}

// ❌ Bad: No error handling
ClaudeSDK.query(prompt, options);  // Could throw!
```

## Veja também

- [Conversas interativas](./feature-interactive-conversations.md) — para conversas de múltiplos turnos
- [Opções de configuração](./feature-configuration-options.md) — o guia completo de opções
- [Tipos de mensagem](./feature-message-types.md) — entendendo as mensagens
- [Referência da API ClaudeSDK](./api-claude-sdk.md) — documentação detalhada da API
