# Referência da API ClaudeSDKClient

Client interativo para conversas bidirecionais.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../api-claude-sdk-client.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral da classe

```java
public class ClaudeSDKClient implements AutoCloseable
```

Client para conversas com estado e interativas com o Claude.

## Construtor

```java
public ClaudeSDKClient()
public ClaudeSDKClient(ClaudeAgentOptions options)
```

## Métodos de conexão

### connect()

```java
public void connect() throws CLIConnectionException
```

Estabelece a conexão com o CLI do Claude Code.

**Segurança entre threads**: thread-safe, idempotente

**Lança**: `IllegalStateException` se chamado depois de close()

### connect(String initialMessage)

```java
public void connect(String initialMessage) throws CLIConnectionException
```

Conecta e envia uma mensagem inicial.

### isConnected()

```java
public boolean isConnected()
```

Verifica se está conectado.

**Retorna**: `boolean`

### disconnect() / close()

```java
public void disconnect()
public void close()
```

Fecha a conexão e libera recursos.

**Segurança entre threads**: thread-safe, idempotente

## Enviando mensagens

### sendMessage(String prompt)

```java
public void sendMessage(String prompt)
```

Envia uma mensagem e continua recebendo.

### sendMessage(String prompt, String sessionId)

```java
public void sendMessage(String prompt, String sessionId)
```

Envia uma mensagem para uma sessão específica.

### query(String prompt)

```java
public void query(String prompt)
```

Envia uma mensagem. Retorna assim que o prompt é escrito — leia a resposta com
[`receiveResponse()`](#receiveresponse) ou [`receiveMessages()`](#receivemessages).

Equivalente a `query(prompt, "default")`.

### query(String prompt, String sessionId)

```java
public void query(String prompt, String sessionId)
```

Consulta uma sessão específica.

### query(Iterator&lt;Map&lt;String, Object&gt;&gt; messageStream)

```java
public void query(Iterator<Map<String, Object>> messageStream)
public void query(Iterator<Map<String, Object>> messageStream, String sessionId)
```

Envia mapas de mensagem brutos. Prefira este em vez de `query(String)` quando a mensagem precisar de
campos que a forma em string não constrói — atribuição de `origin`, blocos de conteúdo estruturados,
um `uuid` explícito:

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", "Reply with exactly: one"));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));   // attribute the turn

client.query(List.of(message).iterator());
for (Message msg : client.receiveResponse()) { /* ... */ }
```

O `session_id` é preenchido com `"default"` — ou com `sessionId` na sobrecarga de dois argumentos —
em qualquer mensagem que o omita. Os mapas de quem chama são copiados em vez de alterados quando
esse campo é adicionado, então passar um mapa imutável é seguro.

Como `query(String)`, este método mantém o stdin do CLI aberto, de modo que pode ser chamado
repetidamente ao longo de uma sessão e misturado livremente com a sobrecarga em string.

> **Alterado na v0.1.23.** Este método antes entregava o iterador ao caminho interno de streaming de
> uso único, que fecha o stdin assim que o iterador se esgota. Isso encerrava a sessão: o CLI saía e
> o `query()` ou `sendMessage()` seguinte falhava com
> `ProcessTransport is not ready for writing`. Agora ele escreve diretamente, igual ao SDK Python.
> Quem dependia do comportamento antigo para encerrar uma sessão deve chamar `disconnect()` (ou usar
> try-with-resources).

As mensagens são escritas antes de a chamada retornar, o que mantém a ordem entre chamadas
sucessivas. Conduza um iterador preguiçoso ou ilimitado a partir da sua própria thread se precisar
ler respostas enquanto ele ainda está produzindo.

## Recebendo mensagens

### receiveMessages()

```java
public Iterator<Message> receiveMessages()
```

Obtém um iterador sobre todas as mensagens (contínuo).

**Segurança entre threads**: thread-safe, mas as mensagens são distribuídas entre vários iteradores

**Retorna**: `Iterator<Message>`

### receiveResponse()

```java
public Iterable<Message> receiveResponse()
```

Obtém as mensagens até o próximo ResultMessage (fecha automaticamente).

**Segurança entre threads**: thread-safe

**Retorna**: `Iterable<Message>`

## Métodos de controle

### interrupt()

```java
public void interrupt()
```

Interrompe a execução atual.

**Segurança entre threads**: thread-safe

### setModel(String model)

```java
public void setModel(String model)
```

Troca o modelo de IA.

**Parâmetros**: `model` — nome do modelo (por exemplo, "claude-opus-4-6")

### setPermissionMode(PermissionMode mode)

```java
public void setPermissionMode(PermissionMode mode)
```

Muda o modo de permissão.

**Parâmetros**: `mode` — novo modo de permissão

### rewindFiles(String userMessageId)

```java
public void rewindFiles(String userMessageId)
```

Restaura os arquivos ao estado de uma mensagem de usuário (requer checkpoints).

**Parâmetros**: `userMessageId` — ID da mensagem para a qual voltar

### getMcpStatus()

```java
public Map<String, Object> getMcpStatus()
```

Obtém o status de conexão dos servidores MCP.

**Retorna**: `Map<String, Object>` — informações de status

### getContextUsage()

```java
public ContextUsageResponse getContextUsage()
```

Obtém um detalhamento do uso atual da janela de contexto por categoria.

Retorna os mesmos dados mostrados pelo comando `/context` no CLI, incluindo contagem de tokens por
categoria, uso total e detalhamentos de ferramentas MCP, arquivos de memória e agentes.

**Retorna**: `ContextUsageResponse` com os campos:
- `categories` — lista de `ContextUsageCategory` (name, tokens, color)
- `totalTokens` — total de tokens na janela de contexto
- `maxTokens` — limite efetivo de contexto
- `percentage` — percentual de contexto usado (0-100)
- `model` — nome do modelo
- Além de campos opcionais: `autoCompactThreshold`, `memoryFiles`, `mcpTools`, `agents` etc.

**Lança**: `CLIConnectionException` se não estiver conectado

### getServerInfo()

```java
public Map<String, Object> getServerInfo()
```

Obtém as informações de inicialização do servidor.

**Retorna**: `Map<String, Object>` — informações do servidor

## Segurança entre threads

- **connect()**: thread-safe, sincronizado
- **métodos de envio**: thread-safe
- **métodos de recebimento**: thread-safe, mas compartilham a fila
- **métodos de controle**: thread-safe
- **close()**: thread-safe, idempotente

## Gerenciamento de recursos

Use sempre try-with-resources:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
}
```

## Veja também
- [Guia de conversas interativas](./feature-interactive-conversations.md)
