# Conversas interativas

Conversas interativas permitem trocas com estado e em vários turnos com o Claude usando a classe `ClaudeSDKClient`.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-interactive-conversations.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não são traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Quando usar o ClaudeSDKClient](#quando-usar-o-claudesdkclient)
- [Uso básico](#uso-básico)
- [Gerenciamento da conexão](#gerenciamento-da-conexão)
- [Enviando mensagens](#enviando-mensagens)
- [Recebendo mensagens](#recebendo-mensagens)
- [Métodos de controle](#métodos-de-controle)
- [Gerenciamento de sessões](#gerenciamento-de-sessões)
- [Segurança de threads](#segurança-de-threads)
- [Gerenciamento de recursos](#gerenciamento-de-recursos)
- [Exemplos](#exemplos)
- [Boas práticas](#boas-práticas)

## Visão geral

O `ClaudeSDKClient` dá controle total sobre uma conversa bidirecional com o Claude. Diferentemente
da fachada simples `ClaudeSDK.query()`, este cliente é:

- **Com estado**: o contexto da conversa é preservado entre mensagens
- **Bidirecional**: envie e receba mensagens a qualquer momento
- **Interativo**: envie perguntas de acompanhamento com base nas respostas
- **Controlável**: interrompa, troque de modelo, altere permissões durante a execução
- **Ciente de sessões**: suporta retomar e bifurcar conversas

## Quando usar o ClaudeSDKClient

### ✅ Ideal para

1. **Interfaces de chat**
   ```java
   try (var client = ClaudeSDK.createClient()) {
       client.connect();
       while (userInput = getUserInput()) {
           client.sendMessage(userInput);
           for (var msg : client.receiveResponse()) {
               display(msg);
           }
       }
   }
   ```

2. **Interfaces no estilo REPL**
   ```java
   while (true) {
       String command = console.readLine();
       client.sendMessage(command);
       processResponse(client.receiveResponse());
   }
   ```

3. **Conversas com vários turnos**
   ```java
   client.sendMessage("What is Python?");
   // ... process response
   client.sendMessage("Show me a code example");
   // ... context preserved
   ```

4. **Depuração interativa**
   ```java
   client.sendMessage("Analyze this error");
   var response = client.receiveResponse();
   if (needsMoreInfo) {
       client.sendMessage("Here's more context...");
   }
   ```

5. **Sessões de longa duração**
   ```java
   try (var client = ClaudeSDK.createClient(options)) {
       client.connect();
       // Hours-long session with state
   }
   ```

### ❌ Não é o ideal para

- Perguntas simples e isoladas → use `ClaudeSDK.query()`
- Processamento em lote → use `ClaudeSDK.query()`
- Scripts de execução única → use `ClaudeSDK.query()`

## Uso básico

### Criar e conectar

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Create with default options
ClaudeSDKClient client = ClaudeSDK.createClient();

// Or with custom options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(20)
    .build();
ClaudeSDKClient client = ClaudeSDK.createClient(options);

// Connect (establishes subprocess)
client.connect();
```

### Conversa simples

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // First message
    client.sendMessage("What is 2 + 2?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }

    // Follow-up (context preserved)
    client.sendMessage("What about 3 + 3?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Conectar com uma mensagem inicial

```java
// Connect and send initial message in one call
client.connect("Hello, Claude!");

for (var msg : client.receiveResponse()) {
    // Process initial response
}
```

## Gerenciamento da conexão

### connect()

Estabelece a conexão com o CLI do Claude Code.

```java
client.connect();  // No initial message
client.connect("Initial prompt");  // With initial message
```

**Segurança de threads**: seguro para threads e idempotente. Chamadas concorrentes são protegidas.

**Lança**:
- `IllegalStateException` — se chamado depois de `close()`
- `CLIConnectionException` — se a conexão falhar

### isConnected()

Verifica se o cliente está conectado.

```java
if (client.isConnected()) {
    client.sendMessage("Hello");
}
```

### disconnect() / close()

Fecha a conexão e libera os recursos.

```java
client.disconnect();  // Explicit disconnect
// or
client.close();  // AutoCloseable

// Best practice: use try-with-resources
try (var client = ClaudeSDK.createClient()) {
    // Use client
}  // Automatically closed
```

**Segurança de threads**: seguro para threads e idempotente. Pode ser chamado várias vezes com segurança.

## Enviando mensagens

### sendMessage(String prompt)

Envia uma mensagem e continua recebendo.

```java
client.sendMessage("Hello, Claude!");
```

**Quando usar**: quando você quer enviar uma mensagem e continuar ouvindo todos os eventos.

### sendMessage(String prompt, String sessionId)

Envia uma mensagem para uma sessão específica.

```java
client.sendMessage("Hello!", "session-1");
```

### query(String prompt)

Envia uma mensagem e recebe apenas a resposta dela (bloqueia até o ResultMessage).

```java
List<Message> response = client.query("What is 2 + 2?");
```

**Quando usar**: quando você quer o padrão requisição/resposta (enviar e esperar a resposta completa).

### query(String prompt, String sessionId)

Consulta usando um ID de sessão específico.

```java
List<Message> response = client.query("Question", "session-1");
```

### query(Iterator<Map<String, Object>> messageStream)

Envia várias mensagens como um stream.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Second"))
);

List<Message> responses = client.query(messages.iterator());
```

## Recebendo mensagens

### receiveMessages()

Retorna um iterador sobre **todas** as mensagens (stream contínuo).

```java
Iterator<Message> messages = client.receiveMessages();

while (messages.hasNext()) {
    Message msg = messages.next();
    // Process each message as it arrives

    if (shouldStop(msg)) {
        break;
    }
}
```

**Quando usar**:
- Você quer processar mensagens continuamente
- Você está lidando com várias sessões
- Você precisa ver todos os eventos, inclusive mensagens de sistema

**Características**:
- O iterador bloqueia até haver mensagens disponíveis
- Continua retornando mensagens até o fim do stream
- Vários iteradores compartilham a mesma fila (as mensagens são distribuídas)

### receiveResponse()

Retorna um iterador que para no próximo ResultMessage.

```java
Iterable<Message> response = client.receiveResponse();

for (Message msg : response) {
    // Process messages until ResultMessage
}
// Iterator auto-closes when ResultMessage received
```

**Quando usar**:
- Você quer o padrão requisição/resposta
- Você está esperando uma consulta terminar
- Você quer parar automaticamente no ResultMessage

**Características**:
- Bloqueia até haver mensagens disponíveis
- Para no ResultMessage e fecha automaticamente
- Retorna todas as mensagens de uma resposta

### Processando mensagens

```java
for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());

            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Tool: " + tool.name());
                }
            }
        }

        case ResultMessage result -> {
            System.out.println("Done! Cost: $" + result.totalCostUsd());
            System.out.println("Stop reason: " + result.stopReason());
        }

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            System.out.println("Partial: " + event.delta());
    }
}
```

## Métodos de controle

### interrupt()

Interrompe a execução atual.

```java
// In another thread
client.interrupt();
```

**Casos de uso**:
- O usuário cancela a operação
- Um tempo limite foi atingido
- Interromper operações caras

### setModel(String model)

Troca o modelo de IA no meio da conversa.

```java
client.setModel("claude-opus-4-6");
```

**Casos de uso**:
- Mudar para um modelo mais capaz em tarefas complexas
- Mudar para um modelo mais barato em perguntas simples

### setPermissionMode(PermissionMode mode)

Altera o modo de permissão durante a conversa.

```java
client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
```

**Modos disponíveis**:
- `ACCEPT_ALL` — aceita todas as permissões automaticamente
- `ACCEPT_EDITS` — aceita edições automaticamente, pergunta no resto
- `BYPASS_PERMISSIONS` — ignora todas as verificações de permissão
- `PROMPT` — pergunta em todas as permissões (padrão)

### rewindFiles(String userMessageId)

Reverte os arquivos ao estado de uma mensagem de usuário (requer checkpointing habilitado).

```java
// Enable checkpointing in options
var options = ClaudeAgentOptions.builder()
    .checkpointFiles(true)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Create file.txt");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user) {
            String messageId = user.id();
            // Save ID for later
        }
    }

    // Later: rewind to that message
    client.rewindFiles(messageId);
}
```

### getMcpStatus()

Obtém o status das conexões com servidores MCP.

```java
Map<String, Object> status = client.getMcpStatus();
System.out.println("MCP servers: " + status);
```

### getServerInfo()

Obtém as informações de inicialização do servidor.

```java
Map<String, Object> info = client.getServerInfo();
System.out.println("CLI version: " + info.get("version"));
```

## Gerenciamento de sessões

### Sessão padrão

Por padrão, todas as mensagens usam a sessão "default".

```java
client.sendMessage("Hello");  // Uses "default" session
```

### Múltiplas sessões

Envie mensagens para sessões diferentes.

```java
// Session 1
client.sendMessage("Analyze code.java", "session-1");

// Session 2
client.sendMessage("Write tests", "session-2");

// Receive from all sessions
for (var msg : client.receiveMessages()) {
    // Process messages from any session
}
```

### Retomar uma sessão anterior

```java
// First conversation
var options1 = ClaudeAgentOptions.builder()
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
}

// Later: resume with context
var options2 = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more");
    // Has context from previous session
}
```

### Bifurcar uma sessão

Bifurcar cria uma nova sessão a partir de uma existente.

```java
var options = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .forkSession(true)  // Fork instead of continue
    .build();
```

**Diferença**:
- `resume(id)` — continua a mesma sessão
- `resume(id) + forkSession(true)` — cria uma nova sessão com o mesmo contexto

## Segurança de threads

O `ClaudeSDKClient` é **parcialmente seguro para threads**:

### Operações seguras para threads

```java
// ✅ Safe: Multiple threads can send
Thread t1 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 1"));
Thread t2 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 2"));

// ✅ Safe: Control methods
client.interrupt();
client.setModel("claude-sonnet-4-5");
client.setPermissionMode(PermissionMode.ACCEPT_ALL);

// ✅ Safe: connect() is synchronized
client.connect();  // Only one connection established
```

### Estado compartilhado

```java
// ⚠️ Warning: Multiple iterators share the queue
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();

// Messages distributed across both iterators!
// Typically use only one iterator per client
```

### Boa prática

```java
// ✅ Good: One receive loop per client
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Dedicated receive thread
    Thread.ofVirtual().start(() -> {
        for (var msg : client.receiveMessages()) {
            processMessage(msg);
        }
    });

    // Main thread sends
    client.sendMessage("Question 1");
    client.sendMessage("Question 2");
}
```

## Gerenciamento de recursos

### AutoCloseable

Sempre use try-with-resources:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
}  // Automatically cleaned up
```

### Limpeza manual

Se não usar try-with-resources:

```java
ClaudeSDKClient client = ClaudeSDK.createClient();
try {
    client.connect();
    // Use client
} finally {
    client.close();  // Important!
}
```

### Recursos liberados

No `close()`, o cliente libera:
- O QueryHandler e os pools de threads
- Os executores de streaming
- A camada de transporte e o subprocesso do CLI
- Filas de mensagens e iteradores

## Exemplos

### Exemplo 1: chat interativo

```java
import java.util.Scanner;

public class Chat {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-sonnet-4-5")
            .build();

        try (var client = ClaudeSDK.createClient(options);
             var scanner = new Scanner(System.in)) {

            client.connect();
            System.out.println("Chat started! Type 'exit' to quit.");

            while (true) {
                System.out.print("\nYou: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                client.sendMessage(input);

                System.out.print("Claude: ");
                for (var msg : client.receiveResponse()) {
                    if (msg instanceof AssistantMessage assistant) {
                        System.out.print(assistant.getTextContent());
                    }
                }
                System.out.println();
            }
        }
    }
}
```

### Exemplo 2: interromper uma operação longa

```java
import java.util.concurrent.TimeUnit;

public class InterruptExample {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start long operation in background
            Thread.ofVirtual().start(() -> {
                client.sendMessage("Analyze all files in this large codebase");
                for (var msg : client.receiveResponse()) {
                    System.out.println(msg);
                }
            });

            // Wait 5 seconds then interrupt
            TimeUnit.SECONDS.sleep(5);
            System.out.println("Interrupting...");
            client.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Exemplo 3: troca dinâmica de modelo

```java
public class ModelSwitching {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Simple question with Haiku
            client.setModel("claude-haiku-4-5");
            client.sendMessage("What is 2+2?");
            processResponse(client.receiveResponse());

            // Complex question with Opus
            client.setModel("claude-opus-4-6");
            client.sendMessage("Explain quantum entanglement");
            processResponse(client.receiveResponse());

            // Back to Sonnet for balanced tasks
            client.setModel("claude-sonnet-4-5");
            client.sendMessage("Write a Java function");
            processResponse(client.receiveResponse());
        }
    }
}
```

### Exemplo 4: gerenciamento de várias sessões

```java
public class MultiSession {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start multiple tasks in different sessions
            client.sendMessage("Review code.java for bugs", "review");
            client.sendMessage("Write tests for util.java", "testing");
            client.sendMessage("Document api.java", "docs");

            // Process responses from all sessions
            for (var msg : client.receiveMessages()) {
                switch (msg) {
                    case AssistantMessage a ->
                        System.out.println("[Session] " + a.getTextContent());
                    case ResultMessage r ->
                        System.out.println("[Done] Cost: $" + r.totalCostUsd());
                    default -> {}
                }

                // Stop when all three sessions complete
                if (allSessionsComplete()) {
                    break;
                }
            }
        }
    }
}
```

### Exemplo 5: checkpoint de arquivos

```java
public class Checkpointing {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .checkpointFiles(true)
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();

            String checkpointId = null;

            // Create a file and save checkpoint
            client.sendMessage("Create test.txt with 'Hello'");
            for (var msg : client.receiveResponse()) {
                if (msg instanceof UserMessage user) {
                    checkpointId = user.id();
                }
            }

            // Modify the file
            client.sendMessage("Append 'World' to test.txt");
            for (var msg : client.receiveResponse()) {}

            // Rewind to original state
            if (checkpointId != null) {
                client.rewindFiles(checkpointId);
                System.out.println("Rewound to checkpoint");
            }
        }
    }
}
```

## Boas práticas

### 1. Use try-with-resources

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    client.connect();
}

// ❌ Bad: Resource leak
var client = ClaudeSDK.createClient();
client.connect();
// Forgot to close!
```

### 2. Trate erros de conexão

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    try {
        client.connect();
    } catch (CLIConnectionException e) {
        System.err.println("Failed to connect: " + e.getMessage());
        return;
    }
    // Use client
}
```

### 3. Use receiveResponse() para requisição/resposta

```java
// ✅ Good: Clean request/response
client.sendMessage("Question");
for (var msg : client.receiveResponse()) {
    // Processes until ResultMessage
}

// ❌ Bad: Manual ResultMessage checking
for (var msg : client.receiveMessages()) {
    if (msg instanceof ResultMessage) break;
}
```

### 4. Não crie vários iteradores de recepção

```java
// ✅ Good: Single iterator
Iterator<Message> messages = client.receiveMessages();

// ❌ Bad: Messages split across iterators
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();
```

### 5. Defina limites adequados

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(50)  // Long conversation
    .maxBudgetUsd(5.0)
    .build();

// ❌ Bad: No limits in interactive session
var client = ClaudeSDK.createClient();  // Could be expensive!
```

### 6. Trate todos os tipos de mensagem

```java
// ✅ Good: Exhaustive pattern matching
for (var msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage u -> handleUser(u);
        case AssistantMessage a -> handleAssistant(a);
        case ResultMessage r -> handleResult(r);
        case SystemMessage s -> handleSystem(s);
        case StreamEvent e -> handleStream(e);
    }
}
```

### 7. Use os métodos de controle com critério

```java
// ✅ Good: Switch models based on task complexity
if (isComplexTask) {
    client.setModel("claude-opus-4-6");
}

client.sendMessage(task);

// ✅ Good: Interrupt on timeout
CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)
    .execute(() -> client.interrupt());
```

## Veja também

- [Consultas simples](./feature-simple-queries.md) — para consultas isoladas
- [Opções de configuração](./feature-configuration-options.md) — todas as ClaudeAgentOptions
- [Tipos de mensagem](./feature-message-types.md) — entendendo as mensagens
- [Referência da API ClaudeSDKClient](./api-claude-sdk-client.md) — documentação completa da API
