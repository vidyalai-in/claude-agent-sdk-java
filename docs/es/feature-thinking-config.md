# Configuración del razonamiento extendido

Controla el comportamiento del razonamiento extendido de Claude con opciones de configuración
detalladas.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-thinking-config.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Índice
- [Visión general](#visión-general)
- [Los tipos de ThinkingConfig](#los-tipos-de-thinkingconfig)
- [Visualización del razonamiento](#visualización-del-razonamiento)
- [Niveles de esfuerzo](#niveles-de-esfuerzo)
- [Ejemplos de uso](#ejemplos-de-uso)
- [Coincidencia de patrones](#coincidencia-de-patrones)
- [Buenas prácticas](#buenas-prácticas)
- [Referencia de la API](#referencia-de-la-api)

## Visión general

El razonamiento extendido permite a Claude usar tokens de razonamiento adicionales antes de generar
las respuestas. El SDK ofrece dos opciones de configuración:

1. **ThinkingConfig**: controla si el razonamiento está habilitado y fija presupuestos de tokens
2. **Effort**: fija el nivel de profundidad/intensidad del razonamiento

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(16000))  // 16K thinking tokens
    .effort("high")                               // High effort level
    .build();
```

**Nota**: `thinking()` tiene prioridad sobre la opción obsoleta `maxThinkingTokens()`.

## Los tipos de ThinkingConfig

ThinkingConfig es una interfaz sellada con tres variantes:

### ThinkingConfigAdaptive

Usa razonamiento adaptativo: el sistema determina automáticamente cuánto razonar. Pasa
`--thinking adaptive` al CLI.

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigAdaptive();

// With explicit display
ThinkingConfig display = new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();
```

**Parámetros**:
- `display` (opcional, puede ser `null`): consulta [Visualización del
  razonamiento](#visualización-del-razonamiento) más abajo. Cuando se define, se reenvía como
  `--thinking-display <value>`.

**Mejor para**:
- Tareas de razonamiento complejo
- Problemas abiertos
- Cuando quieres que Claude decida la profundidad del razonamiento
- Tareas de investigación y análisis

### ThinkingConfigEnabled

Habilita el razonamiento con un presupuesto de tokens concreto. Pasa
`--max-thinking-tokens <budgetTokens>` al CLI. Cuando `display` está definido, pasa además
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

**Parámetros**:
- `budgetTokens` (int): máximo de tokens de razonamiento (debe ser positivo)
- `display` (opcional, puede ser `null`): consulta [Visualización del
  razonamiento](#visualización-del-razonamiento) más abajo

**Lanza**: `IllegalArgumentException` si `budgetTokens ≤ 0`

**Mejor para**:
- Aplicaciones atentas al presupuesto
- Control de coste predecible
- Cuando conoces el nivel de complejidad
- Pruebas y evaluación comparativa

### ThinkingConfigDisabled

Desactiva por completo el razonamiento extendido. Pasa `--thinking disabled` al CLI.

```java
ThinkingConfig config = new ThinkingConfigDisabled();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();
```

**Mejor para**:
- Consultas y respuestas sencillas
- Cuando la latencia es crítica
- Operaciones sensibles al coste
- Tareas directas que no requieren razonar

## Visualización del razonamiento

`ThinkingDisplay` controla si el modelo devuelve el texto del razonamiento o solo bloques de firma.
Se reenvía al CLI como `--thinking-display <value>`.

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),  // Return thinking text in the assistant stream
    OMITTED("omitted");        // Omit thinking text; return signature blocks only
}
```

**Cuándo definirlo**:
- Opus 4.7+ usa `omitted` por defecto (solo firma). Pasa `SUMMARIZED` si quieres el texto en el
  flujo.
- Los modelos anteriores admiten ambos: tanto adaptive como enabled respetan el campo `display`.

**Compatibilidad**:
- Solo se reenvía para `ThinkingConfigAdaptive` y `ThinkingConfigEnabled`.
  `ThinkingConfigDisabled` nunca emite `--thinking-display`.
- Dejar `display` como `null` (los constructores sin argumentos) significa usar el valor por defecto
  del CLI propio del modelo.

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

## Niveles de esfuerzo

La opción `effort` controla la profundidad/intensidad del razonamiento. Valores válidos:

| Nivel | Descripción | Caso de uso |
|-------|-------------|----------|
| `"low"` | Razonamiento mínimo, respuestas más rápidas | Consultas sencillas, respuestas rápidas |
| `"medium"` | Razonamiento moderado | Tareas de uso general |
| `"high"` | Razonamiento profundo (por defecto) | Problemas complejos, análisis detallado |
| `"xhigh"` | Profundidad de razonamiento ampliada (solo Opus 4.7) | Los problemas más difíciles en Opus 4.7 |
| `"max"` | Esfuerzo máximo | Investigación, razonamiento crítico |

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

**Notas**:

- El nivel de esfuerzo funciona junto con ThinkingConfig. Puedes usar effort sin definir
  explícitamente un ThinkingConfig.
- `"xhigh"` es **propio de Opus 4.7** y recae en `"high"` en los demás modelos. El campo subyacente
  sigue siendo una `String` normal, así que se pueden pasar futuros valores de esfuerzo sin
  actualizar el SDK.
- El enum `EffortLevel` en `in.vidyalai.claude.sdk.types.config.EffortLevel` refleja el alias de tipo
  `EffortLevel` que exporta el SDK de Python y se recomienda para el código nuevo. La sobrecarga con
  `String` se mantiene para conservar toda la flexibilidad (y para cualquier nivel futuro que aún no
  esté en el enum). Consulta [el enum
  EffortLevel](feature-configuration-options.md#enum-effortlevel) para más detalles.

## Ejemplos de uso

### Consulta sencilla con razonamiento adaptativo

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

### Razonamiento con presupuesto controlado

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

### Desactivar el razonamiento para ganar velocidad

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

### Tarea de investigación con esfuerzo alto

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

### Conversación interactiva con razonamiento

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

### Funciones beta con razonamiento extendido

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

## Coincidencia de patrones

Usa la coincidencia de patrones de Java para tratar los distintos tipos de ThinkingConfig:

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

Comprobación con seguridad de tipos:

```java
if (config instanceof ThinkingConfigEnabled enabled) {
    int budget = enabled.budgetTokens();
    System.out.println("Thinking budget: " + budget);
}
```

## Buenas prácticas

### Cuándo usar cada tipo

**ThinkingConfigAdaptive**:
- ✅ Tareas de razonamiento complejo
- ✅ Complejidad del problema desconocida
- ✅ Investigación y análisis
- ❌ Aplicaciones sensibles al presupuesto
- ❌ Consultas sencillas

**ThinkingConfigEnabled**:
- ✅ Cuando hace falta controlar el presupuesto
- ✅ Nivel de complejidad conocido
- ✅ Aplicaciones en producción
- ✅ Pruebas y evaluación comparativa
- ❌ Cuando se desconoce el presupuesto óptimo

**ThinkingConfigDisabled**:
- ✅ Consultas sencillas
- ✅ Aplicaciones sensibles a la latencia
- ✅ Minimizar costes
- ❌ Cuando hace falta razonamiento complejo
- ❌ Tareas de investigación

### Combinar thinking y effort

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

### Optimización de costes

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

### Migrar desde maxThinkingTokens

La opción `thinking()` sustituye a la obsoleta `maxThinkingTokens()`:

```java
// Old (deprecated)
.maxThinkingTokens(10000)

// New (recommended)
.thinking(new ThinkingConfigEnabled(10000))

// Note: thinking() takes precedence if both are set
```

## Referencia de la API

### La interfaz ThinkingConfig

```java
public sealed interface ThinkingConfig
    permits ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled

String type()  // Returns "adaptive", "enabled", or "disabled"
```

### El record ThinkingConfigAdaptive

```java
public record ThinkingConfigAdaptive(@Nullable ThinkingDisplay display) implements ThinkingConfig {
    public ThinkingConfigAdaptive() { this(null); }   // convenience: no display override
}
```

Flags del CLI: `--thinking adaptive` (siempre); `--thinking-display <value>` (cuando
`display != null`).

### El record ThinkingConfigEnabled

```java
public record ThinkingConfigEnabled(
    int budgetTokens,
    @Nullable ThinkingDisplay display
) implements ThinkingConfig {
    public ThinkingConfigEnabled(int budgetTokens) { this(budgetTokens, null); }
}
```

**Parámetros**:
- `budgetTokens`: máximo de tokens de razonamiento (debe ser > 0)
- `display` (opcional, puede ser `null`): consulta `ThinkingDisplay` más abajo

**Lanza**: `IllegalArgumentException` si `budgetTokens ≤ 0`

Flags del CLI: `--max-thinking-tokens <budgetTokens>` (siempre); `--thinking-display <value>`
(cuando `display != null`).

### El record ThinkingConfigDisabled

```java
public record ThinkingConfigDisabled() implements ThinkingConfig
```

Flag del CLI: `--thinking disabled`. `--thinking-display` nunca se emite para la variante disabled.

### El enum ThinkingDisplay

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),
    OMITTED("omitted");
}
```

Se reenvía como el valor de `--thinking-display`.

### Métodos de ClaudeAgentOptions

```java
// Builder methods
ClaudeAgentOptions.Builder thinking(ThinkingConfig thinking)
ClaudeAgentOptions.Builder effort(String effort)

// Getter methods
ThinkingConfig thinking()
String effort()
```

## Funciones relacionadas

- [Opciones de configuración](./feature-configuration-options.md): todas las opciones de configuración
- [Tipos de mensaje](./feature-message-types.md): mensajes ThinkingBlock
- [Eventos de streaming](./feature-streaming-events.md): transmitir bloques de razonamiento
- [Funciones beta](./feature-configuration-options.md#betas): contexto y razonamiento extendidos

## Código de ejemplo

Consulta estos ejemplos para ver demostraciones completas:
- `examples/AdvancedFeatures.java`: los métodos betaFeatures() y completeConfiguration()
- `sdk/src/test/java/in/vidyalai/claude/sdk/ClaudeAgentOptionsTest.java`: pruebas unitarias
