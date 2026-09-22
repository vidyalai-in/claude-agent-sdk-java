# Servidores MCP (Model Context Protocol)

O MCP (Model Context Protocol) permite criar ferramentas personalizadas que o Claude pode usar durante as conversas. O SDK suporta tanto servidores SDK no mesmo processo quanto servidores externos stdio/SSE/HTTP.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-mcp-servers.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não são traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Servidores MCP do SDK vs. servidores externos](#servidores-mcp-do-sdk-vs-servidores-externos)
- [Criando servidores MCP do SDK](#criando-servidores-mcp-do-sdk)
- [Usando a anotação @Tool](#usando-a-anotação-tool)
- [Título e anotações da ferramenta](#título-e-anotações-da-ferramenta)
- [Criação programática de ferramentas](#criação-programática-de-ferramentas)
- [Esquema da ferramenta](#esquema-da-ferramenta)
- [Execução da ferramenta](#execução-da-ferramenta)
- [Detalhes do protocolo](#detalhes-do-protocolo)
- [Handlers MCP personalizados](#handlers-mcp-personalizados)
- [Servidores MCP externos](#servidores-mcp-externos)
- [Status dos servidores MCP](#status-dos-servidores-mcp)
- [Exemplos](#exemplos)
- [Boas práticas](#boas-práticas)

## Visão geral

O MCP (Model Context Protocol) oferece uma forma padronizada de definir ferramentas personalizadas que o Claude pode invocar durante as conversas. O SDK suporta:

1. **Servidores MCP do SDK** (no mesmo processo) — rodam dentro da sua aplicação
2. **Servidores MCP externos** — rodam como processos separados (stdio/SSE/HTTP)

**Principais vantagens:**
- Ampliar as capacidades do Claude com funcionalidades próprias
- Acesso ao estado e às APIs da sua aplicação
- Definições de ferramentas com segurança de tipos
- Geração automática de esquema
- Execução assíncrona com CompletableFuture

## Servidores MCP do SDK vs. servidores externos

### Servidores MCP do SDK (no mesmo processo)

**Vantagens:**
- ✅ **Melhor desempenho**: sem custo de IPC
- ✅ **Implantação mais simples**: um único processo
- ✅ **Depuração mais fácil**: mesmo processo, mesmo depurador
- ✅ **Acesso direto**: acessa o estado da aplicação diretamente
- ✅ **Segurança de tipos**: o sistema de tipos do Java
- ✅ **Sem serialização**: chamadas de método diretas

**Casos de uso:**
- Ferramentas específicas da aplicação
- Acesso a banco de dados
- Regras de negócio
- APIs internas
- Testes e prototipagem

### Servidores MCP externos

**Vantagens:**
- ✅ **Independentes de linguagem**: escritos em qualquer linguagem
- ✅ **Isolamento**: espaço de processo separado
- ✅ **Reutilização**: compartilhados entre aplicações
- ✅ **Segurança**: sandbox de processo

**Casos de uso:**
- Ferramentas de terceiros
- Bibliotecas específicas de uma linguagem (Node.js, Python)
- Servidores de ferramentas compartilhados
- Sistemas legados

## Criando servidores MCP do SDK

Há três formas de criar servidores MCP do SDK:

1. Usando a anotação `@Tool` (declarativa)
2. Usando `SdkMcpTool.create()` (programática)
3. Usando `SdkMcpServer.create()` (manual)

### Início rápido

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import java.util.concurrent.CompletableFuture;

public class MyTools {
    @Tool(name = "greet", description = "Greet a user")
    public CompletableFuture<ToolResult> greet(String name) {
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }
}

// Create server from annotated class
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

// Use in options
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__greet"))
    .build();
```

## Usando a anotação @Tool

A anotação `@Tool` oferece uma forma declarativa de definir ferramentas.

### Anotação básica

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

public class Calculator {

    @Tool(name = "add", description = "Add two numbers")
    public CompletableFuture<ToolResult> add(double a, double b) {
        double result = a + b;
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + result)
        );
    }

    @Tool(name = "multiply", description = "Multiply two numbers")
    public CompletableFuture<ToolResult> multiply(Map<String, Object> args) {
        double a = ((Number) args.get("a")).doubleValue();
        double b = ((Number) args.get("b")).doubleValue();
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + (a * b))
        );
    }
}
```

## Título e anotações da ferramenta

### Título da ferramenta

Use o atributo `title` para dar um nome de exibição amigável, distinto do nome técnico da ferramenta:

```java
@Tool(
    name = "fetch_user_data",
    title = "User Data Fetcher",
    description = "Fetch user data from the database"
)
public CompletableFuture<ToolResult> fetchUserData(String userId) {
    // ...
}
```

O título é enviado tanto no nível superior da ferramenta, onde o MCP 2025-06-18 o coloca, quanto dentro de `annotations`, onde revisões anteriores o procuram — um cliente anterior ao campo de nível superior descarta o que não conhece. Ele chega ao `tools/list` independentemente de a ferramenta declarar anotações.

### Anotações da ferramenta (dicas semânticas)

Use o atributo `annotations` para anexar dicas de comportamento a uma ferramenta. Implemente a interface `ToolAnnotations`:

```java
import in.vidyalai.claude.sdk.mcp.ToolAnnotations;

public class ReadOnlyHints implements ToolAnnotations {
    @Override
    public Boolean readOnlyHint() { return true; }
}

@Tool(
    name = "read_file",
    title = "File Reader",
    description = "Read the contents of a file",
    annotations = ReadOnlyHints.class
)
public CompletableFuture<ToolResult> readFile(String path) {
    // ...
}
```

Dicas de anotação disponíveis:

| Dica | Descrição |
|------|-------------|
| `readOnlyHint` | A ferramenta apenas lê dados, não altera estado |
| `destructiveHint` | A ferramenta realiza operações irreversíveis |
| `idempotentHint` | Chamadas repetidas com as mesmas entradas dão o mesmo resultado |
| `openWorldHint` | A ferramenta consulta sistemas externos com resultados ilimitados |
| `maxResultSizeChars` | Tamanho máximo do resultado em caracteres antes de o CLI despejar em arquivo temporário |

### maxResultSizeChars (dica específica da Anthropic)

A anotação `maxResultSizeChars` controla o limiar de despejo de resultados de ferramenta da camada 2 do CLI. Por padrão, o CLI despeja em arquivos temporários os resultados maiores que cerca de 50 mil caracteres. Definir esta anotação eleva (ou reduz) esse limiar para uma ferramenta específica.

Como o esquema Zod do MCP SDK remove campos de anotação desconhecidos, `maxResultSizeChars` é encaminhado via `_meta` com a chave namespaced `anthropic/maxResultSizeChars` na resposta JSONRPC de `tools/list`.

```java
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .maxResultSizeChars(200_000)  // Allow up to 200K chars
    .build();

SdkMcpTool<Map<String, Object>> bigResultTool = SdkMcpTool.builder("large_query", "Query returning large results")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text(runLargeQuery((String) args.get("query")))
    ))
    .annotations(hints)
    .build();
```

### Assinaturas de método

O método anotado pode ter duas assinaturas:

#### 1. Parâmetros tipados (recomendado)

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(String firstName, String lastName) {
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + firstName + " " + lastName + "!")
    );
}
```

**Requisitos:**
- Compile com a flag `-parameters` para preservar os nomes dos parâmetros
- Os parâmetros são mapeados automaticamente para JSON Schema
- Mapeamento de tipos:
  - `String` → `"string"`
  - `int`, `Integer`, `long`, `Long` → `"integer"`
  - `double`, `Double`, `float`, `Float` → `"number"`
  - `boolean`, `Boolean` → `"boolean"`
  - `Map<String, Object>` → `"object"`

#### 2. Parâmetro Map

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
    String name = (String) args.get("name");
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + name + "!")
    );
}
```

**Use quando:**
- Você quiser extrair os parâmetros manualmente
- O esquema for complexo
- Você precisar de parâmetros opcionais

### Geração automática de esquema

Quando você usa parâmetros tipados, o SDK gera automaticamente um JSON Schema:

```java
@Tool(name = "search", description = "Search for items")
public CompletableFuture<ToolResult> search(String query, int limit) {
    // Implementation
}
```

Esquema gerado:
```json
{
    "type": "object",
    "properties": {
        "query": {
            "type": "string"
        },
        "limit": {
            "type": "integer"
        }
    },
    "required": ["query", "limit"]
}
```

### Esquema explícito

Para esquemas complexos, forneça o JSON explicitamente:

```java
@Tool(
    name = "search",
    description = "Search for items",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results",
                    "default": 10,
                    "minimum": 1,
                    "maximum": 100
                },
                "filters": {
                    "type": "object",
                    "properties": {
                        "category": {"type": "string"},
                        "minPrice": {"type": "number"}
                    }
                }
            },
            "required": ["query"]
        }
        """
)
public CompletableFuture<ToolResult> search(Map<String, Object> args) {
    // Implementation
}
```

### Criando o servidor a partir de anotações

```java
// Create server from annotated instance
Calculator calculator = new Calculator();
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    calculator
);

// Or with version
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    "1.0.0",
    calculator
);
```

## Criação programática de ferramentas

Para criar ferramentas dinamicamente, use `SdkMcpTool.create()` ou o builder:

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import java.util.concurrent.CompletableFuture;

// Simple creation
SdkMcpTool<Map<String, Object>> uppercaseTool = SdkMcpTool.create(
    "uppercase",                           // Tool name
    "Convert text to uppercase",           // Description
    Map.of(                                // JSON Schema
        "type", "object",
        "properties", Map.of(
            "text", Map.of(
                "type", "string",
                "description", "The text to convert"
            )
        ),
        "required", List.of("text")
    ),
    args -> {                              // Handler function
        String text = (String) args.get("text");
        return CompletableFuture.completedFuture(
            ToolResult.text(text.toUpperCase())
        );
    }
);

// With title and annotations using builder
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .idempotentHint(true)
    .build();

SdkMcpTool<Map<String, Object>> searchTool = SdkMcpTool.builder("search", "Search records")
    .title("Record Search")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text("Results for: " + args.get("query"))
    ))
    .annotations(hints)
    .build();
```

### Criando o servidor a partir das ferramentas

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;

// Create multiple tools
List<SdkMcpTool<?>> tools = List.of(
    uppercaseTool,
    lowercaseTool,
    reverseTool
);

// Create server
SdkMcpServer server = SdkMcpServer.create(
    "text-tools",  // Server name
    "1.0.0",       // Version
    tools          // Tool list
);

// Get config for options
McpSdkServerConfig config = server.toConfig();
```

## Esquema da ferramenta

### Formato JSON Schema

O `inputSchema` de uma ferramenta é JSON Schema. Os argumentos são validados contra ele antes de o handler rodar (veja [Validação de argumentos](#validação-de-argumentos)). O dialeto vem da palavra-chave `$schema` do esquema quando presente; sem ela, assume-se Draft 2020-12 — o dialeto sobre o qual a especificação MCP foi escrita. Draft 4, 6, 7, 2019-09 e 2020-12 são todos compreendidos.

```java
Map<String, Object> schema = Map.of(
    "type", "object",
    "properties", Map.of(
        "name", Map.of(
            "type", "string",
            "description", "User's name",
            "minLength", 1
        ),
        "age", Map.of(
            "type", "integer",
            "description", "User's age",
            "minimum", 0,
            "maximum", 150
        ),
        "email", Map.of(
            "type", "string",
            "format", "email"
        )
    ),
    "required", List.of("name", "email")
);
```

### Tipos suportados

- `string` — valores de texto
- `integer` — números inteiros
- `number` — números de ponto flutuante
- `boolean` — true/false
- `object` — objetos aninhados
- `array` — listas de valores
- `null` — valores nulos

### Restrições

```java
Map.of(
    // String constraints
    "minLength", 1,
    "maxLength", 100,
    "pattern", "^[A-Z][a-z]+$",
    "format", "email",  // email, uri, date-time, etc.

    // Number constraints
    "minimum", 0,
    "maximum", 100,
    "exclusiveMinimum", true,
    "multipleOf", 5,

    // Array constraints
    "minItems", 1,
    "maxItems", 10,
    "uniqueItems", true,

    // Enum values
    "enum", List.of("red", "green", "blue")
);
```

## Execução da ferramenta

### ToolResult

As ferramentas devem devolver `ToolResult` (envolvido em CompletableFuture):

```java
import in.vidyalai.claude.sdk.mcp.ToolResult;

// Text result
ToolResult.text("Hello, world!");

// JSON result — serialized into a single text block
ToolResult.json(Map.of("status", "success", "data", data));

// Image result (Base64)
ToolResult.image(base64Data, "image/png");

// Several content blocks
ToolResult.builder()
    .addText("Result:")
    .addJson(data)
    .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
    .build();

// From raw MCP content blocks, normalized (see below)
ToolResult.ofContent(List.of(
    Map.of("type", "text", "text", "Result:"),
    Map.of("type", "resource_link", "name", "Docs", "uri", "https://example.com")
));

// Error result
ToolResult.error("Failed to process request");
```

#### Blocos de conteúdo

O MCP define mais tipos de conteúdo do que o CLI renderiza, então os que ele não consegue exibir são convertidos em texto — a mesma conversão que o SDK Python faz:

| Bloco | Vira |
|---|---|
| `text` | ele mesmo |
| `image` | ele mesmo |
| `resource_link` | texto: nome, URI e descrição em linhas próprias, pulando os vazios (`Resource link` quando todos faltam) |
| `resource` com `text` | esse texto |
| `resource` com dados binários | descartado, registrado em `WARNING` |
| qualquer outra coisa | descartado, registrado em `WARNING` |

`addResourceLink(...)` e `addResource(...)` aplicam as mesmas regras, então um handler pode montar um resultado bloco a bloco sem conhecê-las.

### Execução assíncrona

As ferramentas executam de forma assíncrona usando CompletableFuture:

```java
@Tool(name = "fetch_data", description = "Fetch data from API")
public CompletableFuture<ToolResult> fetchData(String url) {
    // Async HTTP request
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()))
        .exceptionally(e -> ToolResult.error(e.getMessage()));
}
```

### Tratamento de erros

Relate uma falha que o modelo deve ver devolvendo `ToolResult.error(...)`, o que produz um resultado com `isError: true`:

```java
@Tool(name = "divide", description = "Divide two numbers")
public CompletableFuture<ToolResult> divide(double a, double b) {
    if (b == 0) {
        return CompletableFuture.completedFuture(
            ToolResult.error("Cannot divide by zero")
        );
    }

    return CompletableFuture.completedFuture(
        ToolResult.text("Result: " + (a / b))
    );
}
```

Você não precisa capturar tudo por conta própria: um handler que lança, ou cujo `CompletableFuture` completa excepcionalmente, é relatado da mesma forma (veja [Semântica das falhas](#semântica-das-falhas), abaixo).

### Validação de argumentos

Antes de um handler rodar, os argumentos de um `tools/call` são validados contra o `inputSchema` declarado pela ferramenta. Isso é exigido dos servidores MCP — *"Servidores **DEVEM** validar todas as entradas de ferramentas"* — e significa que um handler só vê argumentos que correspondem ao contrato que ele publicou.

Uma chamada que não corresponde volta como resultado de ferramenta com `isError: true` e texto começando com `Input validation error:`, e **o handler não é invocado**:

```
{"count": 21}            -> handler runs, returns its result
{}                       -> Input validation error: required property 'count' not found
{"count": "twenty-one"}  -> Input validation error: /count string found, integer expected
```

Duas consequências em que vale confiar:

- Uma ferramenta com efeitos colaterais não consegue aplicar metade do trabalho antes de falhar por argumentos que nunca concordou em aceitar.
- O modelo recebe uma frase nomeando a propriedade problemática, sobre a qual pode agir, em vez da exceção que o handler por acaso lançou ao tentar ler um valor ausente ou de tipo errado.

Uma ferramenta sem esquema, ou com um esquema vazio, não é validada — não há nada contra o que checar.

#### Um esquema que não é JSON Schema válido

A validação falha **fechada**, como no SDK Python. Cada `inputSchema` é checado contra o meta-esquema do seu próprio dialeto quando o servidor é construído; uma ferramenta cujo esquema não passa é registrada em `WARNING`, e toda chamada a ela volta como `isError` sem executar o handler:

```
{"type": "object", "properties": "not-an-object"}
    -> Tool 'x' has an inputSchema this server cannot use, so it cannot be
       called: /properties string found, object expected
```

Isso importa mais do que parece. O validador aceita alegremente um esquema malformado e então valida mal contra ele: `{"type": "bogus"}` compila e não casa com nada, então toda chamada falharia citando os argumentos de quem chamou, em vez do defeito real, enquanto `"properties": "a string"` é simplesmente ignorado e libera todas as chamadas sem verificação. Nenhum dos dois é um estado em que um handler deveria rodar.

O texto deliberadamente **não** começa com `Input validation error:`. Esse prefixo diz ao modelo que os argumentos dele estavam errados; um esquema quebrado é um defeito do servidor que o modelo não consegue contornar, e rotulá-lo errado convida a uma repetição infinita. Palavras-chave desconhecidas continuam legais — um esquema com extensões `x-vendor` valida normalmente.

### Semântica das falhas

Como cada tipo de falha chega a quem chamou:

| Situação | Resposta | O que o modelo vê |
|---|---|---|
| Handler devolve `ToolResult.error(msg)` | resultado, `isError: true` | `msg` |
| Handler lança, ou o future dele falha | resultado, `isError: true` | a mensagem da exceção, ou o nome da classe quando a mensagem é nula/vazia |
| Argumentos não correspondem ao `inputSchema` | resultado, `isError: true` | `Input validation error: …` (handler não executa) |
| `inputSchema` não é JSON Schema válido | resultado, `isError: true` | `… inputSchema this server cannot use …` (handler não executa) |
| Nome da ferramenta não registrado | resultado, `isError: true` | `Tool '<name>' not found` |
| A chamada foi cancelada | erro JSON-RPC `-32800` | nada; o CLI já desistiu |
| Método que este servidor não implementa | erro JSON-RPC `-32601` | nada; o modelo nunca envia esses |
| `params` ausente ou malformado | erro JSON-RPC `-32602` | nada; idem |

Tudo com que uma *chamada de ferramenta* pode se deparar é um **erro de execução de ferramenta**: a chamada foi processada e produziu um resultado que por acaso descreve uma falha, então o texto chega ao modelo como saída que ele pode ler e à qual pode se adaptar. Um erro JSON-RPC diz que a requisição não pôde sequer ser processada, e o modelo nunca vê um — por isso uma ferramenta desconhecida também é relatada como resultado, acompanhando o SDK Python. Esta semântica é do próprio SDK, então uma ferramenta se comporta igual em ambos.

### Cancelando uma ferramenta em execução

O CLI aplica seu próprio tempo limite a uma chamada de ferramenta MCP (`MCP_TOOL_TIMEOUT`). Quando ele dispara, o CLI para de esperar e envia o `notifications/cancelled` do MCP; o SDK responde à chamada pendente com `-32800` e descarta o que o handler acabar devolvendo.

O handler em si continua rodando, a menos que olhe. Um `CompletableFuture` não pode ser interrompido de fora — `cancel(true)` completa o future e deixa o trabalho em paz — então uma ferramenta que faz algo demorado, ou algo com efeitos colaterais, deveria receber um `ToolCallContext` ao lado dos argumentos:

```java
SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
        "crawl", "Fetch every page under a URL", schema,
        (args, context) -> CompletableFuture.supplyAsync(() -> {
            List<String> pages = new ArrayList<>();
            for (String url : urlsFrom(args)) {
                if (context.isCancelled()) {
                    break;              // nobody is waiting for this any more
                }
                pages.add(fetch(url));
            }
            return ToolResult.text(String.join("\n", pages));
        }));
```

`context.onCancel(runnable)` cobre trabalho que não pode ser consultado em laço — uma leitura bloqueante, uma chamada a outro serviço — dando a você um lugar para fechar o recurso; ele roda imediatamente se a chamada já foi cancelada. `context.throwIfCancelled()` é a forma de checkpoint, para um handler que prefere desenrolar a pilha.

Handlers que recebem apenas seus argumentos continuam funcionando exatamente como antes; eles simplesmente não conseguem observar o cancelamento. Métodos anotados com `@Tool` podem declarar um parâmetro `ToolCallContext` em qualquer posição da assinatura — ele é injetado e nunca aparece no esquema publicado da ferramenta.

Desconectar tem o mesmo efeito: fechar o cliente abandona as chamadas ainda em andamento, então um desligamento não fica preso a uma ferramenta que nada consegue interromper.

Uma falha de handler também é registrada localmente em `WARNING` com o stack trace, de modo que uma ferramenta que quebra é depurável sem que a transcrição do modelo seja o único registro.

### Operações demoradas

```java
@Tool(name = "process_large_file", description = "Process a large file")
public CompletableFuture<ToolResult> processFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // Long-running operation
            byte[] data = Files.readAllBytes(Path.of(path));
            String result = processData(data);
            return ToolResult.text("Processed: " + result);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    });
}
```

## Detalhes do protocolo

### Versão do protocolo

O servidor anuncia `2025-06-18` e `2024-11-05`, do mais novo para o mais antigo. No `initialize` ele ecoa a versão que o cliente pediu quando a fala, e caso contrário responde com a mais nova que fala — o handshake que a especificação prescreve.

`2025-03-26` deliberadamente não é reivindicada. Aquela revisão tornou obrigatório *receber* lotes JSON-RPC, e um lote é um array de nível superior, algo que a requisição de controle que carrega essas mensagens tipa como mapa e não consegue representar. Reivindicar uma versão cuja única mudança obrigatória o SDK não poderia honrar seria uma promessa sobre a qual o cliente agiria.

### Métodos

`initialize`, `ping`, `tools/list` e `tools/call` são implementados. Qualquer outra coisa recebe `-32601`, o que é correto e não uma ausência: o servidor anuncia apenas a capacidade `tools`, então um cliente conforme nunca pede resources, prompts ou completions. (Verificado com o CLI: um servidor que declara apenas `tools` nunca recebe `resources/list` nem `prompts/list`.)

### Notificações

Uma notificação JSON-RPC — uma mensagem com `method` e sem `id` — nunca recebe resposta, como o JSON-RPC exige. `notifications/initialized` e `notifications/cancelled` são tratadas; qualquer outra é registrada em `FINE` e descartada. A *requisição de controle* que carregou a notificação ainda é confirmada, com `{"jsonrpc": "2.0", "result": {}}`, senão o CLI esperaria para sempre.

Uma mensagem sem `method` algum é uma resposta JSON-RPC, ou lixo. O SDK não envia requisições ao CLI, então nada que chegue por esse caminho é dele para casar: é ignorado em vez de respondido.

## Handlers MCP personalizados

`McpSdkServerConfig` guarda um `McpMessageHandler`, e não especificamente um `SdkMcpServer`. Implemente a interface diretamente para servir partes do MCP que o `SdkMcpServer` não cobre — resources, prompts, completions — ou para adaptar uma biblioteca MCP de terceiros:

```java
public class MyMcpServer implements McpMessageHandler {

    @Override
    public CompletableFuture<Map<String, Object>> handleMessage(Map<String, Object> message) {
        // Return the JSON-RPC response for a request, or null for a
        // notification, which must never be answered.
        ...
    }

    @Override
    public void close() {
        // Optional: the connection using this handler is going away.
    }
}

var options = ClaudeAgentOptions.builder()
        .mcpServers(Map.of("mine", new McpSdkServerConfig("mine", new MyMcpServer())))
        .build();
```

O que o CLI envia é determinado pelas `capabilities` devolvidas por `initialize`, então um handler que anuncia resources será consultado sobre eles.

`close()` significa "a conexão que usava você está indo embora", e não "desligue": um mesmo handler pode ser registrado em mais de um cliente, então ele precisa ser idempotente e continuar utilizável depois. Pelo mesmo motivo, registre um `SdkMcpServer` por conexão — duas conexões vivas compartilhando um servidor podem emitir o mesmo id JSON-RPC, e a segunda chamada é então recusada com `-32603` em vez de arriscar que uma resposta chegue a quem não chamou.

## Servidores MCP externos

### Servidor stdio

```java
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;

McpStdioServerConfig server = new McpStdioServerConfig(
    "node",                              // Command
    List.of("path/to/server.js"),        // Arguments
    Map.of("NODE_ENV", "production")     // Environment variables
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("external", server))
    .build();
```

### Servidor SSE

```java
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;

McpSseServerConfig server = new McpSseServerConfig(
    "http://localhost:8080/sse"  // SSE endpoint URL
);
```

### Servidor HTTP

```java
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;

McpHttpServerConfig server = new McpHttpServerConfig(
    "http://localhost:8080"  // Base URL
);
```

### Servidores mistos

Você pode usar servidores do SDK e externos ao mesmo tempo:

```java
// SDK server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "app-tools",
    new MyTools()
);

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("external-server.js"),
    Map.of()
);

// Configure both
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of(
        "app", sdkServer,
        "external", externalServer
    ))
    .allowedTools(List.of(
        "mcp__app__my_tool",
        "mcp__external__their_tool"
    ))
    .build();
```

### Configuração MCP estrita

Por padrão, o CLI carrega servidores MCP do `.mcp.json` do projeto, das configurações do usuário/globais e de quaisquer plugins, além do que você passa via `mcpServers(...)`. Defina `strictMcpConfig(true)` para ignorar tudo, exceto os servidores que você passar:

```java
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("app", sdkServer))
    .strictMcpConfig(true)   // ignore project / user / plugin MCP configs
    .build();
```

Corresponde à flag `--strict-mcp-config` do CLI. Útil para implantações reproduzíveis ou isolamento de testes, quando você quer controle exato sobre quais servidores MCP são alcançáveis.

## Exemplos

### Exemplo 1: calculadora

```java
public class Calculator {

    @Tool(name = "calculate", description = "Perform calculations")
    public CompletableFuture<ToolResult> calculate(
            double a, double b, String operation) {

        double result = switch (operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> {
                if (b == 0) {
                    return CompletableFuture.completedFuture(
                        ToolResult.error("Cannot divide by zero")
                    );
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException(
                "Unknown operation: " + operation
            );
        };

        return CompletableFuture.completedFuture(
            ToolResult.text(a + " " + operation + " " + b + " = " + result)
        );
    }
}

// Usage
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    new Calculator()
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", server))
    .allowedTools(List.of("mcp__calc__calculate"))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Calculate 15 * 7, then 100 / 4");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Exemplo 2: acesso a banco de dados

```java
public class DatabaseTools {
    private final DataSource dataSource;

    public DatabaseTools(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "query_users", description = "Query users from database")
    public CompletableFuture<ToolResult> queryUsers(String filter) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM users WHERE name LIKE ?")) {

                stmt.setString(1, "%" + filter + "%");
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(Map.of(
                        "id", rs.getInt("id"),
                        "name", rs.getString("name"),
                        "email", rs.getString("email")
                    ));
                }

                return ToolResult.json(Map.of(
                    "count", users.size(),
                    "users", users
                ));

            } catch (SQLException e) {
                return ToolResult.error("Database error: " + e.getMessage());
            }
        });
    }
}
```

### Exemplo 3: integração com API

```java
public class WeatherTools {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey;

    public WeatherTools(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(name = "get_weather", description = "Get current weather")
    public CompletableFuture<ToolResult> getWeather(String city) {
        String url = "https://api.weather.com/weather?city=" + city +
                     "&key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply(response -> {
                // Parse JSON response
                Map<String, Object> data = parseJson(response.body());
                return ToolResult.json(data);
            })
            .exceptionally(e -> ToolResult.error(
                "Failed to fetch weather: " + e.getMessage()
            ));
    }
}
```

## Boas práticas

### 1. Use tipos de retorno adequados

```java
// ✅ Good: Specific result types
ToolResult.text("Simple text response");
ToolResult.json(Map.of("key", "value"));
ToolResult.error("Error message");

// ❌ Bad: Always using text for structured data
ToolResult.text("{\"key\":\"value\"}");  // Should use json()
```

### 2. Trate erros com cuidado

```java
// ✅ Good: Proper error handling
@Tool(name = "read_file", description = "Read a file")
public CompletableFuture<ToolResult> readFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            String content = Files.readString(Path.of(path));
            return ToolResult.text(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    });
}

// ❌ Bad: Throwing exceptions
public CompletableFuture<ToolResult> readFile(String path) {
    String content = Files.readString(Path.of(path));  // Throws!
    return CompletableFuture.completedFuture(ToolResult.text(content));
}
```

### 3. Escreva boas descrições

```java
// ✅ Good: Descriptive and clear
@Tool(
    name = "search_products",
    description = "Search for products by name, category, or price range. " +
                  "Returns a list of matching products with details."
)

// ❌ Bad: Vague description
@Tool(name = "search", description = "Search")
```

### 4. Use esquemas explícitos para entradas complexas

O esquema declarado é o que o SDK usa para validar os argumentos, então quanto mais precisamente ele descrever a entrada, mais o handler pode dar por certo — e mais útil é a mensagem que o modelo recebe quando chama a ferramenta errado. Uma ferramenta sem esquema não é validada de forma alguma.

```java
// ✅ Good: Explicit schema with validation
@Tool(
    name = "create_user",
    description = "Create a new user",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "email": {"type": "string", "format": "email"},
                "age": {"type": "integer", "minimum": 18}
            },
            "required": ["email"]
        }
        """
)

// ❌ Bad: No validation
@Tool(name = "create_user", description = "Create user")
public CompletableFuture<ToolResult> createUser(Map<String, Object> args)
```

### 5. Mantenha as ferramentas focadas

```java
// ✅ Good: Single responsibility
@Tool(name = "add_numbers", description = "Add two numbers")
@Tool(name = "multiply_numbers", description = "Multiply two numbers")

// ❌ Bad: Too much in one tool
@Tool(name = "math", description = "Do any math operation")
```

### 6. Use assincronia em operações de E/S

```java
// ✅ Good: Async I/O
@Tool(name = "fetch", description = "Fetch URL")
public CompletableFuture<ToolResult> fetch(String url) {
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()));
}

// ❌ Bad: Blocking I/O
public CompletableFuture<ToolResult> fetch(String url) {
    String result = blockingHttpCall(url);  // Blocks!
    return CompletableFuture.completedFuture(ToolResult.text(result));
}
```

### 7. Configure as permissões das ferramentas

```java
// ✅ Good: Explicitly allow tools
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .allowedTools(List.of(
        "mcp__calc__add",
        "mcp__calc__subtract"
    ))
    .build();

// ❌ Bad: Allowing all tools (security risk)
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .build();  // All tools allowed!
```

## Status dos servidores MCP

`ClaudeSDKClient.getMcpStatus()` devolve um `McpStatusResponse` com o estado de conexão atual de todos os servidores MCP configurados.

### McpStatusResponse

```java
record McpStatusResponse(
    List<McpServerStatus> mcpServers   // list of server status entries
)
```

### McpServerStatus

```java
record McpServerStatus(
    String name,                               // server name as configured
    McpServerConnectionStatus status,          // connection state
    @Nullable McpServerInfo serverInfo,        // info from MCP handshake (when connected)
    @Nullable String error,                    // error message (when status = FAILED)
    @Nullable McpServerStatusConfig config,    // server configuration
    @Nullable String scope,                    // config scope (project, user, local)
    @Nullable List<McpToolInfo> tools          // available tools (when connected)
)
```

### McpServerConnectionStatus

```java
enum McpServerConnectionStatus {
    CONNECTED,    // server is connected and ready
    FAILED,       // connection attempt failed
    NEEDS_AUTH,   // server requires authentication
    PENDING,      // connection in progress
    DISABLED      // server is disabled
}
```

### McpServerInfo

```java
record McpServerInfo(
    String name,      // server name from MCP handshake
    String version    // server version from MCP handshake
)
```

### McpToolInfo

```java
record McpToolInfo(
    String name,                               // tool name
    @Nullable String description,              // tool description
    @Nullable McpToolAnnotations annotations   // behavioral hints
)
```

### McpServerStatusConfig (interface selada)

Representa a configuração do servidor nas respostas de status. É polimórfica — use pattern matching:

```java
switch (server.config()) {
    case McpStdioServerConfig c -> System.out.println("stdio: " + c.command());
    case McpSseServerConfig c -> System.out.println("sse: " + c.url());
    case McpHttpServerConfig c -> System.out.println("http: " + c.url());
    case McpSdkServerConfigStatus c -> System.out.println("sdk: " + c.name());
    case McpClaudeAIProxyServerConfig c -> System.out.println("proxy: " + c.url());
    case null -> {}
}
```

### Exemplo: verificando o status do MCP

```java
try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    McpStatusResponse status = client.getMcpStatus();
    for (McpServerStatus server : status.mcpServers()) {
        System.out.printf("[%s] %s%n", server.status(), server.name());
        if (server.status() == McpServerConnectionStatus.CONNECTED) {
            if (server.tools() != null) {
                server.tools().forEach(t -> System.out.println("  - " + t.name()));
            }
        } else if (server.status() == McpServerConnectionStatus.FAILED) {
            System.err.println("  Error: " + server.error());
        }
    }
}
```

## Veja também

- [Opções de configuração](./feature-configuration-options.md) — as opções mcpServers e tools
- [Exemplo de uso de ferramentas](../../examples/src/main/java/examples/McpServer.java) — exemplos completos
- [Exemplo de geração automática de esquema](../../examples/src/main/java/examples/AutoSchemaGeneration.java)
- [Especificação do MCP](https://spec.modelcontextprotocol.io/) — documentação oficial do protocolo MCP
