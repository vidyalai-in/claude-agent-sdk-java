# Configuração do raciocínio estendido

Controle o comportamento do raciocínio estendido do Claude com opções de configuração detalhadas.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-thinking-config.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Sumário
- [Visão geral](#visão-geral)
- [Os tipos de ThinkingConfig](#os-tipos-de-thinkingconfig)
- [Exibição do raciocínio](#exibição-do-raciocínio)
- [Níveis de esforço](#níveis-de-esforço)
- [Exemplos de uso](#exemplos-de-uso)
- [Casamento de padrões](#casamento-de-padrões)
- [Boas práticas](#boas-práticas)
- [Referência da API](#referência-da-api)

## Visão geral

O raciocínio estendido permite que o Claude use tokens adicionais de raciocínio antes de gerar as
respostas. O SDK oferece duas opções de configuração:

1. **ThinkingConfig** — controla se o raciocínio está habilitado e define orçamentos de tokens
2. **Effort** — define o nível de profundidade/intensidade do raciocínio

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(16000))  // 16K thinking tokens
    .effort("high")                               // High effort level
    .build();
```

**Observação**: `thinking()` tem precedência sobre a opção obsoleta `maxThinkingTokens()`.

## Os tipos de ThinkingConfig

ThinkingConfig é uma interface selada com três variantes:

### ThinkingConfigAdaptive

Usa raciocínio adaptativo, em que o sistema determina automaticamente quanto raciocínio usar. Passa
`--thinking adaptive` ao CLI.

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigAdaptive();

// With explicit display
ThinkingConfig display = new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();
```

**Parâmetros**:
- `display` (opcional, pode ser `null`) — veja [Exibição do raciocínio](#exibição-do-raciocínio)
  abaixo. Quando definido, é encaminhado como `--thinking-display <value>`.

**Melhor para**:
- Tarefas de raciocínio complexo
- Problemas em aberto
- Quando você quer que o Claude decida a profundidade do raciocínio
- Tarefas de pesquisa e análise

### ThinkingConfigEnabled

Habilita o raciocínio com um orçamento de tokens específico. Passa
`--max-thinking-tokens <budgetTokens>` ao CLI. Quando `display` está definido, passa também
`--thinking-display <value>`.

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigEnabled(10000);  // 10K tokens

// With explicit display
ThinkingConfig display = new ThinkingConfigEnabled(10000, ThinkingDisplay.OMITTED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(8000))  // 8K token budget
    .build();
```

**Parâmetros**:
- `budgetTokens` (int) — máximo de tokens de raciocínio (precisa ser positivo)
- `display` (opcional, pode ser `null`) — veja [Exibição do raciocínio](#exibição-do-raciocínio) abaixo

**Lança**: `IllegalArgumentException` se `budgetTokens ≤ 0`

**Melhor para**:
- Aplicações atentas ao orçamento
- Controle de custo previsível
- Quando você conhece o nível de complexidade
- Testes e benchmarking

### ThinkingConfigDisabled

Desativa completamente o raciocínio estendido. Passa `--thinking disabled` ao CLI.

```java
ThinkingConfig config = new ThinkingConfigDisabled();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();
```

**Melhor para**:
- Consultas e respostas simples
- Quando a latência é crítica
- Operações sensíveis a custo
- Tarefas diretas que não exigem raciocínio

## Exibição do raciocínio

`ThinkingDisplay` controla se o modelo devolve o texto do raciocínio ou apenas blocos de assinatura.
Encaminhado ao CLI como `--thinking-display <value>`.

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),  // Return thinking text in the assistant stream
    OMITTED("omitted");        // Omit thinking text; return signature blocks only
}
```

**Quando definir**:
- O Opus 4.7+ usa `omitted` por padrão (só assinatura). Passe `SUMMARIZED` se quiser o texto no
  fluxo.
- Modelos mais antigos aceitam qualquer um — tanto adaptive quanto enabled respeitam o campo
  `display`.

**Compatibilidade**:
- Encaminhado apenas para `ThinkingConfigAdaptive` e `ThinkingConfigEnabled`.
  `ThinkingConfigDisabled` nunca emite `--thinking-display`.
- Deixar `display` como `null` (os construtores sem argumento) significa usar o padrão do CLI
  específico do modelo.

```java
// Force summarized output regardless of model default
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED))
    .build();

// Suppress thinking text on a fixed budget
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(20000, ThinkingDisplay.OMITTED))
    .build();
```

## Níveis de esforço

A opção `effort` controla a profundidade/intensidade do raciocínio. Valores válidos:

| Nível | Descrição | Caso de uso |
|-------|-------------|----------|
| `"low"` | Raciocínio mínimo, respostas mais rápidas | Consultas simples, respostas rápidas |
| `"medium"` | Raciocínio moderado | Tarefas de uso geral |
| `"high"` | Raciocínio profundo (padrão) | Problemas complexos, análise detalhada |
| `"xhigh"` | Profundidade estendida (somente Opus 4.7) | Os problemas mais difíceis no Opus 4.7 |
| `"max"` | Esforço máximo | Pesquisa, raciocínio crítico |

```java
// Raw string overload
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .effort("xhigh")        // Opus 4.7-specific; falls back to "high" on other models
    .model("claude-opus-4-7")
    .build();

// Type-safe EffortLevel enum overload (recommended)
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .effort(EffortLevel.XHIGH)
    .model("claude-opus-4-7")
    .build();
```

**Observações**:

- O nível de esforço funciona em conjunto com o ThinkingConfig. Você pode usar effort sem definir
  explicitamente um ThinkingConfig.
- `"xhigh"` é **específico do Opus 4.7** e recai em `"high"` nos demais modelos. O campo subjacente
  continua sendo uma `String` simples, então valores futuros de esforço podem ser passados sem
  atualizar o SDK.
- O enum `EffortLevel` em `in.vidyalai.claude.sdk.types.config.EffortLevel` espelha o alias de tipo
  `EffortLevel` exportado pelo SDK Python e é recomendado para código novo. A sobrecarga com `String`
  permanece para total flexibilidade (e para eventuais níveis futuros ainda ausentes do enum). Veja
  [o enum EffortLevel](feature-configuration-options.md#enum-effortlevel) para detalhes.

## Exemplos de uso

### Consulta simples com raciocínio adaptativo

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigAdaptive;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();

List<Message> messages = ClaudeSDK.query(
    "Explain the halting problem in computer science",
    options
);
```

### Raciocínio com orçamento controlado

```java
import in.vidyalai.claude.sdk.types.config.ThinkingConfigEnabled;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(5000))  // Max 5K tokens
    .effort("medium")
    .maxBudgetUsd(1.0)  // Also limit total cost
    .build();

List<Message> messages = ClaudeSDK.query(
    "What are the key differences between Java and Python?",
    options
);
```

### Desativar o raciocínio em nome da velocidade

```java
import in.vidyalai.claude.sdk.types.config.ThinkingConfigDisabled;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();

// Fast response for simple query
String response = ClaudeSDK.queryForText(
    "What is the capital of France?",
    options
);
```

### Tarefa de pesquisa com esforço alto

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .effort("max")  // Maximum thinking depth
    .maxTurns(20)   // Allow extended conversation
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Research the latest developments in quantum computing");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Conversa interativa com raciocínio

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(12000))
    .effort("high")
    .includePartialMessages(true)  // Stream thinking blocks
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Help me debug this algorithm");
    for (Message msg : client.receiveResponse()) {
        switch (msg) {
            case ThinkingBlock thinking ->
                System.out.println("Thinking: " + thinking.thinking());
            case AssistantMessage assistant ->
                System.out.println("Response: " + assistant.getTextContent());
            default -> {}
        }
    }

    // Continue conversation
    client.sendMessage("Now optimize it for performance");
    // ... receive response
}
```

### Recursos beta com raciocínio estendido

```java
import in.vidyalai.claude.sdk.types.config.SdkBeta;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .betas(List.of(SdkBeta.CONTEXT_1M))  // Extended context
    .thinking(new ThinkingConfigEnabled(16000))
    .effort("high")
    .build();

// Large context with deep thinking
List<Message> messages = ClaudeSDK.query(
    "Analyze this entire codebase and suggest improvements",
    options
);
```

## Casamento de padrões

Use o casamento de padrões do Java para tratar os diferentes tipos de ThinkingConfig:

```java
ThinkingConfig config = options.thinking();

if (config != null) {
    switch (config) {
        case ThinkingConfigAdaptive adaptive ->
            System.out.println("Using adaptive thinking (32K default)");
        case ThinkingConfigEnabled enabled ->
            System.out.println("Budget: " + enabled.budgetTokens() + " tokens");
        case ThinkingConfigDisabled disabled ->
            System.out.println("Thinking disabled");
    }
}
```

Verificação com segurança de tipos:

```java
if (config instanceof ThinkingConfigEnabled enabled) {
    int budget = enabled.budgetTokens();
    System.out.println("Thinking budget: " + budget);
}
```

## Boas práticas

### Quando usar cada tipo

**ThinkingConfigAdaptive**:
- ✅ Tarefas de raciocínio complexo
- ✅ Complexidade do problema desconhecida
- ✅ Pesquisa e análise
- ❌ Aplicações sensíveis a orçamento
- ❌ Consultas simples

**ThinkingConfigEnabled**:
- ✅ Quando é preciso controlar o orçamento
- ✅ Nível de complexidade conhecido
- ✅ Aplicações em produção
- ✅ Testes e benchmarking
- ❌ Quando o orçamento ideal é desconhecido

**ThinkingConfigDisabled**:
- ✅ Consultas simples
- ✅ Aplicações sensíveis à latência
- ✅ Minimização de custo
- ❌ Quando é preciso raciocínio complexo
- ❌ Tarefas de pesquisa

### Combinando thinking e effort

```java
// Low complexity - disable thinking
.thinking(new ThinkingConfigDisabled())
.effort("low")

// Medium complexity - fixed budget
.thinking(new ThinkingConfigEnabled(8000))
.effort("medium")

// High complexity - adaptive with high effort
.thinking(new ThinkingConfigAdaptive())
.effort("high")

// Maximum reasoning - adaptive with max effort
.thinking(new ThinkingConfigAdaptive())
.effort("max")
```

### Otimização de custo

```java
// Optimize for cost
ClaudeAgentOptions costOptimized = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(3000))  // Low token budget
    .effort("low")
    .maxBudgetUsd(0.50)  // Hard cost limit
    .maxTurns(5)  // Limit conversation length
    .build();

// Optimize for quality
ClaudeAgentOptions qualityOptimized = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())  // Full adaptive thinking
    .effort("max")  // Maximum effort
    .maxTurns(50)  // Allow extended reasoning
    .build();

// Balanced approach
ClaudeAgentOptions balanced = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(10000))  // Moderate budget
    .effort("medium")  // Standard effort
    .maxBudgetUsd(2.0)  // Reasonable limit
    .build();
```

### Migrando de maxThinkingTokens

A opção `thinking()` substitui a obsoleta `maxThinkingTokens()`:

```java
// Old (deprecated)
.maxThinkingTokens(10000)

// New (recommended)
.thinking(new ThinkingConfigEnabled(10000))

// Note: thinking() takes precedence if both are set
```

## Referência da API

### A interface ThinkingConfig

```java
public sealed interface ThinkingConfig
    permits ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled

String type()  // Returns "adaptive", "enabled", or "disabled"
```

### O record ThinkingConfigAdaptive

```java
public record ThinkingConfigAdaptive(@Nullable ThinkingDisplay display) implements ThinkingConfig {
    public ThinkingConfigAdaptive() { this(null); }   // convenience: no display override
}
```

Flags do CLI: `--thinking adaptive` (sempre); `--thinking-display <value>` (quando
`display != null`).

### O record ThinkingConfigEnabled

```java
public record ThinkingConfigEnabled(
    int budgetTokens,
    @Nullable ThinkingDisplay display
) implements ThinkingConfig {
    public ThinkingConfigEnabled(int budgetTokens) { this(budgetTokens, null); }
}
```

**Parâmetros**:
- `budgetTokens` — máximo de tokens de raciocínio (precisa ser > 0)
- `display` (opcional, pode ser `null`) — veja `ThinkingDisplay` abaixo

**Lança**: `IllegalArgumentException` se `budgetTokens ≤ 0`

Flags do CLI: `--max-thinking-tokens <budgetTokens>` (sempre); `--thinking-display <value>` (quando
`display != null`).

### O record ThinkingConfigDisabled

```java
public record ThinkingConfigDisabled() implements ThinkingConfig
```

Flag do CLI: `--thinking disabled`. `--thinking-display` nunca é emitida para a variante disabled.

### O enum ThinkingDisplay

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),
    OMITTED("omitted");
}
```

Encaminhado como o valor de `--thinking-display`.

### Métodos de ClaudeAgentOptions

```java
// Builder methods
ClaudeAgentOptions.Builder thinking(ThinkingConfig thinking)
ClaudeAgentOptions.Builder effort(String effort)

// Getter methods
ThinkingConfig thinking()
String effort()
```

## Recursos relacionados

- [Opções de configuração](./feature-configuration-options.md) — todas as opções de configuração
- [Tipos de mensagem](./feature-message-types.md) — mensagens ThinkingBlock
- [Eventos de streaming](./feature-streaming-events.md) — transmitindo blocos de raciocínio
- [Recursos beta](./feature-configuration-options.md#betas) — contexto e raciocínio estendidos

## Código de exemplo

Veja estes exemplos para demonstrações completas:
- `examples/AdvancedFeatures.java` — os métodos betaFeatures() e completeConfiguration()
- `sdk/src/test/java/in/vidyalai/claude/sdk/ClaudeAgentOptionsTest.java` — testes unitários
