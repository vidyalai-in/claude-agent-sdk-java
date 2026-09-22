# Referência da API de tipos de exceção

Tratamento de erros e hierarquia de exceções.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../api-exceptions.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Hierarquia de exceções

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
├── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
```

## ClaudeSDKException

Exceção base para todos os erros do SDK.

```java
public class ClaudeSDKException extends RuntimeException {
    public ClaudeSDKException(String message);
    public ClaudeSDKException(String message, Throwable cause);
}
```

## CLIConnectionException

Falha ao conectar ao CLI do Claude Code.

```java
public class CLIConnectionException extends ClaudeSDKException {
    public CLIConnectionException(String message);
    public CLIConnectionException(String message, Throwable cause);
}
```

**Causas**:
- CLI não encontrado
- O processo não iniciou
- Tempo limite de conexão
- Problemas de rede (transporte remoto)

## CLINotFoundException

Executável do CLI do Claude Code não encontrado.

```java
public class CLINotFoundException extends ClaudeSDKException {
    public CLINotFoundException(String message);
}
```

**Solução**:
- Instale o CLI do Claude Code
- Informe um caminho personalizado com `.cliPath()`

## ProcessException

O processo do CLI falhou ou travou.

```java
public class ProcessException extends ClaudeSDKException {
    public ProcessException(String message);
    public ProcessException(String message, Throwable cause);
}
```

**Causas**:
- O CLI travou
- Argumentos inválidos
- Esgotamento de recursos

**Erro acionável após saídas por resultado de erro**: quando o CLI emite um `ResultMessage` com
`isError=true` (por exemplo `error_max_turns`, `error_during_execution` ou um subtipo `success` com
`apiErrorStatus` definido), ele sai com código diferente de zero de propósito. A `ProcessException`
que viria em seguida carregaria apenas `"Command failed with exit code N"`, o que não ajuda em nada,
então o leitor a substitui por uma `ResultException` (veja abaixo). A troca vale por turno — uma
falha nova mais adiante na execução mantém a mensagem original de `ProcessException`.

## ResultException

O CLI reportou um resultado de erro terminal e saiu. É uma subclasse de `ProcessException`, então
handlers `catch (ProcessException e)` existentes continuam funcionando.

```java
public class ResultException extends ProcessException {
    public ResultException(String message, @Nullable Map<String, Object> data,
                           @Nullable Integer exitCode);

    @Nullable public String subtype();          // "error_max_turns", "error_during_execution",
                                                // ... or "success" for a mid-turn API failure
    public List<String> errors();               // never null; empty for API failures
    @Nullable public String result();           // result text; the "API Error: ..." prose
    @Nullable public Integer apiErrorStatus();  // HTTP status of the failing API call
    @Nullable public String terminalReason();   // e.g. "api_error", "max_turns"
    @Nullable public String sessionId();
    public Map<String, Object> data();          // raw result payload, unmodifiable
}
```

A mensagem é `"Claude Code returned an error result: <text>"` mais o sufixo `" (exit code: N)"` de
`ProcessException`. `<text>` é o array `errors` do resultado unido por `"; "`, com fallback para o
texto do resultado, depois para um `subtype` diferente de `success` e, por fim, para
`"API error (HTTP <status>)"`. A `ProcessException` original da saída não zero é o `getCause()`.

Ramifique pelo payload, não pelo texto:

```java
} catch (ResultException e) {
    if ("api_error".equals(e.terminalReason())) {
        retry();
    } else if ("error_max_turns".equals(e.subtype())) {
        // ...
    }
}
```

**Onde ela aparece:**

- A família `ClaudeSDK.query(...)` que coleta mensagens a envolve numa `QueryFailedException`, para
  que as mensagens recebidas antes da falha não se percam; a `ResultException` é o `getCause()`
  dessa exceção. É assim que normalmente você a encontra.
- Diretamente, a partir de uma requisição de controle que falhou — mais importante, um `initialize`
  que o CLI recusa na inicialização (uma retomada rejeitada por `resumeDropsTurn`). Isso acontece
  antes de qualquer mensagem ser coletada, então não é envolvida.
- **Não** a partir de `ClaudeSDKClient.receiveResponse()`: esse método termina no `ResultMessage`
  (exatamente como o `receive_response()` do SDK Python) e por isso nunca observa a saída do CLI.
  Verifique `ResultMessage.isError()` ali. O `receiveMessages()` vai até o fim do fluxo e de fato
  lança, mas num client ativo o stdin continua aberto, então um resultado de erro no meio da sessão
  não encerra o fluxo.

## CLIJSONDecodeException

Falha ao interpretar o JSON vindo do CLI.

```java
public class CLIJSONDecodeException extends ClaudeSDKException {
    public CLIJSONDecodeException(String message, Throwable cause);
}
```

**Causas**:
- JSON malformado
- Formato inesperado
- Incompatibilidade de versão do CLI

## MessageParseException

Falha ao converter a mensagem em um objeto tipado.

```java
public class MessageParseException extends ClaudeSDKException {
    public MessageParseException(String message, Throwable cause);
}
```

**Causas**:
- Tipo de mensagem desconhecido
- Campos obrigatórios ausentes
- Erro de conversão de tipo

## QueryFailedException

Uma consulta que coleta mensagens terminou em um resultado de erro. Carrega as mensagens que já
haviam chegado.

```java
public class QueryFailedException extends ClaudeSDKException {
    public QueryFailedException(String message, Throwable cause, List<Message> partialMessages);

    public List<Message> partialMessages();   // never null; unmodifiable
    public ResultMessage resultMessage();     // last ResultMessage received, or null
}
```

**Causas**:
- `error_max_turns` — `maxTurns` atingido
- `error_max_budget_usd` — `maxBudgetUsd` atingido
- `error_during_execution` — inclusive uma retomada recusada por `resumeDropsTurn`

**Por que ela existe**: o CLI reporta essas condições emitindo um turno *completo* — mensagens do
assistente mais um `ResultMessage` final com o subtipo, o custo e o uso — e só então saindo com
código diferente de zero, de propósito, para quem o usa no shell. As APIs de streaming
(`ClaudeSDKClient.receiveMessages()` e `receiveResponse()`) entregam cada uma dessas mensagens ao
consumidor conforme chegam e só lançam no final, de modo que ali nada se perde. Uma chamada que
coleta precisa ou retornar uma lista ou lançar; lançar esta exceção carrega tanto o erro quanto as
mensagens, então `ClaudeSDK.query(...)` é tão informativo quanto o caminho de streaming.

Lançada apenas pela família `ClaudeSDK.query(...)` que coleta mensagens (incluindo `queryForText` e
`queryForResult`, que delegam a ela). Como estende `ClaudeSDKException`, blocos
`catch (ClaudeSDKException e)` existentes continuam funcionando sem mudanças.

```java
try {
    List<Message> messages = ClaudeSDK.query("Summarize the README", options);
    // ... normal path
} catch (QueryFailedException e) {
    // The turn is usually complete — inspect what actually happened.
    ResultMessage result = e.resultMessage();
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped by the budget cap after $%.4f%n", result.totalCostUsd());
    }
    for (Message msg : e.partialMessages()) {
        if (msg instanceof AssistantMessage a) {
            System.out.println(a.getTextContent());
        }
    }
}
```

`partialMessages()` fica vazio quando a execução falhou antes de produzir qualquer coisa (um CLI que
não conseguiu iniciar, por exemplo). Ele não é serializado — uma instância desserializada reporta
uma lista vazia em vez de null, porque `Message` não é declarado `Serializable`.

Capture esta exceção sempre que definir `maxTurns` ou `maxBudgetUsd`: atingir um limite que você
mesmo configurou é um desfecho esperado, não uma falha.

## Exemplos de tratamento de erros

### try-catch básico

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (CLINotFoundException e) {
    System.err.println("Claude CLI not installed");
} catch (CLIConnectionException e) {
    System.err.println("Connection failed: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI crashed: " + e.getMessage());
} catch (QueryFailedException e) {
    // Run stopped at a limit; the messages so far are still available.
    System.err.println("Run ended early: " + e.getMessage());
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

A ordem importa: `QueryFailedException` precisa ser capturada antes de `ClaudeSDKException`, já que
é uma subclasse.

### Com gerenciamento de recursos

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
} catch (CLIConnectionException e) {
    log.error("Failed to connect", e);
    throw new ApplicationException("Service unavailable", e);
} catch (ClaudeSDKException e) {
    log.error("SDK error", e);
    throw new ApplicationException("Internal error", e);
}
```

### Lógica de retentativa

```java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        return ClaudeSDK.query(prompt, options);
    } catch (CLIConnectionException e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep(1000 * (i + 1));  // Exponential backoff
    }
}
```

## Veja também
- [Exemplo de tratamento de erros](../../examples/src/main/java/examples/ErrorHandling.java)
