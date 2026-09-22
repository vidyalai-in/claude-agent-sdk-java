# Servidores MCP (Model Context Protocol)

MCP (Model Context Protocol) te permite crear herramientas personalizadas que Claude puede usar durante las conversaciones. El SDK admite tanto servidores SDK en el mismo proceso como servidores externos stdio/SSE/HTTP.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-mcp-servers.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se traducen.

## Contenido
- [Descripción general](#descripción-general)
- [Servidores MCP del SDK frente a servidores externos](#servidores-mcp-del-sdk-frente-a-servidores-externos)
- [Crear servidores MCP del SDK](#crear-servidores-mcp-del-sdk)
- [Usar la anotación @Tool](#usar-la-anotación-tool)
- [Título y anotaciones de la herramienta](#título-y-anotaciones-de-la-herramienta)
- [Creación programática de herramientas](#creación-programática-de-herramientas)
- [Esquema de la herramienta](#esquema-de-la-herramienta)
- [Ejecución de la herramienta](#ejecución-de-la-herramienta)
- [Detalles del protocolo](#detalles-del-protocolo)
- [Handlers MCP personalizados](#handlers-mcp-personalizados)
- [Servidores MCP externos](#servidores-mcp-externos)
- [Estado de los servidores MCP](#estado-de-los-servidores-mcp)
- [Ejemplos](#ejemplos)
- [Buenas prácticas](#buenas-prácticas)

## Descripción general

MCP (Model Context Protocol) ofrece una forma estandarizada de definir herramientas personalizadas que Claude puede invocar durante las conversaciones. El SDK admite:

1. **Servidores MCP del SDK** (en el mismo proceso) — se ejecutan dentro de tu aplicación
2. **Servidores MCP externos** — se ejecutan como procesos aparte (stdio/SSE/HTTP)

**Ventajas principales:**
- Ampliar las capacidades de Claude con funcionalidad propia
- Acceso al estado y a las API de tu aplicación
- Definiciones de herramientas con seguridad de tipos
- Generación automática de esquemas
- Ejecución asíncrona con CompletableFuture

## Servidores MCP del SDK frente a servidores externos

### Servidores MCP del SDK (en el mismo proceso)

**Ventajas:**
- ✅ **Mejor rendimiento**: sin sobrecarga de IPC
- ✅ **Despliegue más sencillo**: un solo proceso
- ✅ **Depuración más fácil**: mismo proceso, mismo depurador
- ✅ **Acceso directo**: acceden al estado de la aplicación sin intermediarios
- ✅ **Seguridad de tipos**: el sistema de tipos de Java
- ✅ **Sin serialización**: llamadas a métodos directas

**Casos de uso:**
- Herramientas propias de la aplicación
- Acceso a bases de datos
- Lógica de negocio
- API internas
- Pruebas y prototipos

### Servidores MCP externos

**Ventajas:**
- ✅ **Independientes del lenguaje**: escritos en cualquier lenguaje
- ✅ **Aislamiento**: espacio de proceso separado
- ✅ **Reutilización**: compartidos entre aplicaciones
- ✅ **Seguridad**: aislamiento por proceso

**Casos de uso:**
- Herramientas de terceros
- Bibliotecas propias de un lenguaje (Node.js, Python)
- Servidores de herramientas compartidos
- Sistemas heredados

## Crear servidores MCP del SDK

Hay tres maneras de crear servidores MCP del SDK:

1. Usando la anotación `@Tool` (declarativa)
2. Usando `SdkMcpTool.create()` (programática)
3. Usando `SdkMcpServer.create()` (manual)

### Inicio rápido

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

## Usar la anotación @Tool

La anotación `@Tool` ofrece una forma declarativa de definir herramientas.

### Anotación básica

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

## Título y anotaciones de la herramienta

### Título de la herramienta

Usa el atributo `title` para dar un nombre visible y amigable, distinto del nombre técnico de la herramienta:

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

El título se envía tanto en el nivel superior de la herramienta, donde lo coloca MCP 2025-06-18, como dentro de `annotations`, donde lo buscan las revisiones anteriores: un cliente anterior al campo de nivel superior descarta lo que no conoce. Llega a `tools/list` tanto si la herramienta declara anotaciones como si no.

### Anotaciones de la herramienta (pistas semánticas)

Usa el atributo `annotations` para adjuntar pistas de comportamiento a una herramienta. Implementa la interfaz `ToolAnnotations`:

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

Pistas de anotación disponibles:

| Pista | Descripción |
|------|-------------|
| `readOnlyHint` | La herramienta solo lee datos, no modifica el estado |
| `destructiveHint` | La herramienta realiza operaciones irreversibles |
| `idempotentHint` | Llamadas repetidas con las mismas entradas dan el mismo resultado |
| `openWorldHint` | La herramienta consulta sistemas externos con resultados sin límite |
| `maxResultSizeChars` | Tamaño máximo del resultado en caracteres antes de que la CLI lo vuelque a un archivo temporal |

### maxResultSizeChars (pista específica de Anthropic)

La anotación `maxResultSizeChars` controla el umbral de volcado de resultados de herramienta de la capa 2 de la CLI. Por defecto, la CLI vuelca a archivos temporales los resultados de más de unos 50 000 caracteres. Definir esta anotación sube (o baja) ese umbral para una herramienta concreta.

Como el esquema Zod del SDK de MCP elimina los campos de anotación desconocidos, `maxResultSizeChars` se reenvía mediante `_meta` con la clave con espacio de nombres `anthropic/maxResultSizeChars` en la respuesta JSONRPC de `tools/list`.

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

### Firmas de método

El método anotado puede tener dos firmas:

#### 1. Parámetros tipados (recomendado)

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(String firstName, String lastName) {
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + firstName + " " + lastName + "!")
    );
}
```

**Requisitos:**
- Compila con la bandera `-parameters` para conservar los nombres de los parámetros
- Los parámetros se mapean automáticamente a JSON Schema
- Mapeo de tipos:
  - `String` → `"string"`
  - `int`, `Integer`, `long`, `Long` → `"integer"`
  - `double`, `Double`, `float`, `Float` → `"number"`
  - `boolean`, `Boolean` → `"boolean"`
  - `Map<String, Object>` → `"object"`

#### 2. Parámetro Map

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
    String name = (String) args.get("name");
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + name + "!")
    );
}
```

**Úsalo cuando:**
- Quieras extraer los parámetros a mano
- El esquema sea complejo
- Necesites parámetros opcionales

### Generación automática de esquema

Cuando usas parámetros tipados, el SDK genera automáticamente un JSON Schema:

```java
@Tool(name = "search", description = "Search for items")
public CompletableFuture<ToolResult> search(String query, int limit) {
    // Implementation
}
```

Esquema generado:
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

Para esquemas complejos, proporciona el JSON explícitamente:

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

### Crear el servidor a partir de anotaciones

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

## Creación programática de herramientas

Para crear herramientas de forma dinámica, usa `SdkMcpTool.create()` o el builder:

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

### Crear el servidor a partir de las herramientas

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

## Esquema de la herramienta

### Formato JSON Schema

El `inputSchema` de una herramienta es JSON Schema. Los argumentos se validan contra él antes de ejecutar el handler (consulta [Validación de argumentos](#validación-de-argumentos)). El dialecto se toma de la palabra clave `$schema` del esquema cuando está presente; sin ella se asume Draft 2020-12, el dialecto sobre el que está escrita la especificación de MCP. Se entienden Draft 4, 6, 7, 2019-09 y 2020-12.

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

### Tipos admitidos

- `string` — valores de texto
- `integer` — números enteros
- `number` — números en coma flotante
- `boolean` — true/false
- `object` — objetos anidados
- `array` — listas de valores
- `null` — valores nulos

### Restricciones

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

## Ejecución de la herramienta

### ToolResult

Las herramientas deben devolver `ToolResult` (envuelto en CompletableFuture):

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

#### Bloques de contenido

MCP define más tipos de contenido de los que la CLI representa, así que los que no puede mostrar se pliegan a texto: la misma conversión que hace el SDK de Python.

| Bloque | Se convierte en |
|---|---|
| `text` | él mismo |
| `image` | él mismo |
| `resource_link` | texto: nombre, URI y descripción en líneas propias, omitiendo los vacíos (`Resource link` cuando faltan todos) |
| `resource` con `text` | ese texto |
| `resource` con datos binarios | se descarta y se registra en `WARNING` |
| cualquier otra cosa | se descarta y se registra en `WARNING` |

`addResourceLink(...)` y `addResource(...)` aplican las mismas reglas, así que un handler puede construir un resultado bloque a bloque sin conocerlas.

### Ejecución asíncrona

Las herramientas se ejecutan de forma asíncrona con CompletableFuture:

```java
@Tool(name = "fetch_data", description = "Fetch data from API")
public CompletableFuture<ToolResult> fetchData(String url) {
    // Async HTTP request
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()))
        .exceptionally(e -> ToolResult.error(e.getMessage()));
}
```

### Gestión de errores

Informa de un fallo que el modelo deba ver devolviendo `ToolResult.error(...)`, que produce un resultado con `isError: true`:

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

No hace falta que lo captures todo tú: un handler que lanza, o cuyo `CompletableFuture` termina de forma excepcional, se informa igual (consulta [Semántica de los fallos](#semántica-de-los-fallos), más abajo).

### Validación de argumentos

Antes de ejecutar un handler, los argumentos de un `tools/call` se validan contra el `inputSchema` declarado por la herramienta. Es un requisito para los servidores MCP —*«los servidores **DEBEN** validar todas las entradas de las herramientas»*— y significa que un handler solo ve argumentos que encajan con el contrato que publicó.

Una llamada que no encaja vuelve como resultado de herramienta con `isError: true` y un texto que empieza por `Input validation error:`, y **el handler no se invoca**:

```
{"count": 21}            -> handler runs, returns its result
{}                       -> Input validation error: required property 'count' not found
{"count": "twenty-one"}  -> Input validation error: /count string found, integer expected
```

Dos consecuencias en las que merece la pena confiar:

- Una herramienta con efectos secundarios no puede aplicar la mitad del trabajo antes de fallar por unos argumentos que nunca aceptó.
- El modelo recibe una frase que nombra la propiedad problemática, sobre la que puede actuar, en vez de la excepción que el handler lanzara al intentar leer un valor ausente o con el tipo equivocado.

Una herramienta sin esquema, o con uno vacío, no se valida: no hay nada contra lo que comprobar.

#### Un esquema que no es JSON Schema válido

La validación falla **en cerrado**, igual que en el SDK de Python. Cada `inputSchema` se comprueba contra el meta-esquema de su propio dialecto al construir el servidor; una herramienta cuyo esquema no lo pase se registra en `WARNING` y todas sus llamadas vuelven como `isError` sin ejecutar el handler:

```
{"type": "object", "properties": "not-an-object"}
    -> Tool 'x' has an inputSchema this server cannot use, so it cannot be
       called: /properties string found, object expected
```

Esto importa más de lo que parece. El validador acepta encantado un esquema malformado y luego valida mal contra él: `{"type": "bogus"}` compila y después no coincide con nada, así que toda llamada fallaría citando los argumentos de quien llama en vez del defecto real, mientras que `"properties": "a string"` se ignora por completo y deja pasar todas las llamadas sin comprobar. Ninguno de los dos es un estado en el que deba ejecutarse un handler.

El texto deliberadamente **no** empieza por `Input validation error:`. Ese prefijo le dice al modelo que sus argumentos estaban mal; un esquema roto es un defecto del servidor que el modelo no puede sortear, y etiquetarlo mal invita a reintentos sin fin. Las palabras clave desconocidas siguen siendo legales: un esquema con extensiones `x-vendor` valida sin problema.

### Semántica de los fallos

Cómo llega cada tipo de fallo a quien llama:

| Situación | Respuesta | Lo que ve el modelo |
|---|---|---|
| El handler devuelve `ToolResult.error(msg)` | resultado, `isError: true` | `msg` |
| El handler lanza, o su future falla | resultado, `isError: true` | el mensaje de la excepción, o el nombre de su clase si el mensaje es nulo o vacío |
| Los argumentos no encajan con `inputSchema` | resultado, `isError: true` | `Input validation error: …` (no se ejecuta el handler) |
| `inputSchema` no es JSON Schema válido | resultado, `isError: true` | `… inputSchema this server cannot use …` (no se ejecuta el handler) |
| El nombre de la herramienta no está registrado | resultado, `isError: true` | `Tool '<name>' not found` |
| La llamada se canceló | error JSON-RPC `-32800` | nada; la CLI ya se rindió |
| Método que este servidor no implementa | error JSON-RPC `-32601` | nada; el modelo nunca los emite |
| `params` falta o está malformado | error JSON-RPC `-32602` | nada; ídem |

Todo aquello con lo que puede toparse una *llamada a herramienta* es un **error de ejecución de herramienta**: la llamada se procesó y produjo un resultado que resulta describir un fallo, así que el texto llega al modelo como salida que puede leer y a la que puede adaptarse. Un error JSON-RPC dice que la petición no pudo procesarse en absoluto, y el modelo nunca ve ninguno; por eso una herramienta desconocida también se informa como resultado, en línea con el SDK de Python. Esta semántica es propia del SDK, de modo que una herramienta se comporta igual en ambos.

### Cancelar una herramienta en ejecución

La CLI aplica su propio tiempo límite a una llamada a herramienta MCP (`MCP_TOOL_TIMEOUT`). Cuando salta, la CLI deja de esperar y envía el `notifications/cancelled` de MCP; el SDK responde a la llamada pendiente con `-32800` y descarta lo que el handler acabe devolviendo.

El handler en sí sigue ejecutándose salvo que mire. Un `CompletableFuture` no se puede interrumpir desde fuera —`cancel(true)` completa el future y deja el trabajo en paz—, así que una herramienta que haga algo largo, o algo con efectos secundarios, debería recibir un `ToolCallContext` junto a sus argumentos:

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

`context.onCancel(runnable)` cubre el trabajo que no puede consultarse en bucle —una lectura bloqueante, una llamada a otro servicio— dándote un sitio donde cerrar el recurso; se ejecuta de inmediato si la llamada ya está cancelada. `context.throwIfCancelled()` es la variante de punto de control, para un handler que prefiera deshacer la pila.

Los handlers que solo reciben sus argumentos siguen funcionando exactamente igual que antes; simplemente no pueden observar la cancelación. Los métodos anotados con `@Tool` pueden declarar un parámetro `ToolCallContext` en cualquier posición de su firma: se inyecta y nunca aparece en el esquema publicado de la herramienta.

Desconectar tiene el mismo efecto: cerrar el cliente abandona las llamadas aún en curso, así que un apagado no queda retenido por una herramienta que nada puede interrumpir.

Un fallo del handler también se registra localmente en `WARNING` con su traza, de modo que una herramienta que se rompe es depurable sin que la transcripción del modelo sea el único registro.

### Operaciones largas

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

## Detalles del protocolo

### Versión del protocolo

El servidor anuncia `2025-06-18` y `2024-11-05`, de más nueva a más antigua. En `initialize` devuelve la versión que pidió el cliente cuando la habla, y en caso contrario responde con la más nueva que sí habla: el handshake que prescribe la especificación.

`2025-03-26` no se reivindica a propósito. Esa revisión hizo obligatorio *recibir* lotes JSON-RPC, y un lote es un array de nivel superior, algo que la petición de control que transporta estos mensajes tipa como mapa y no puede representar. Reivindicar una versión cuyo único cambio obligatorio el SDK no podría cumplir sería una promesa sobre la que el cliente actuaría.

### Métodos

Están implementados `initialize`, `ping`, `tools/list` y `tools/call`. A cualquier otra cosa se responde `-32601`, lo cual es correcto y no una carencia: el servidor solo anuncia la capacidad `tools`, así que un cliente conforme nunca pide resources, prompts ni completions. (Verificado con la CLI: a un servidor que declara solo `tools` nunca se le envía `resources/list` ni `prompts/list`.)

### Notificaciones

Una notificación JSON-RPC —un mensaje con `method` y sin `id`— nunca recibe respuesta, como exige JSON-RPC. `notifications/initialized` y `notifications/cancelled` se atienden; cualquier otra se registra en `FINE` y se descarta. La *petición de control* que transportó la notificación sí se confirma, con `{"jsonrpc": "2.0", "result": {}}`, o la CLI esperaría eternamente.

Un mensaje sin `method` alguno es una respuesta JSON-RPC, o basura. El SDK no envía peticiones a la CLI, así que nada que llegue por esa vía le corresponde emparejar: se ignora en lugar de responderse.

## Handlers MCP personalizados

`McpSdkServerConfig` guarda un `McpMessageHandler`, no específicamente un `SdkMcpServer`. Implementa la interfaz directamente para servir partes de MCP que `SdkMcpServer` no cubre —resources, prompts, completions— o para adaptar una biblioteca MCP de terceros:

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

Lo que envía la CLI depende de las `capabilities` devueltas por `initialize`, así que a un handler que anuncie resources se le pedirán.

`close()` significa «la conexión que te usaba se va», no «apágate»: un mismo handler puede registrarse con más de un cliente, así que debe ser idempotente y seguir siendo utilizable después. Por la misma razón, registra un `SdkMcpServer` por conexión: dos conexiones vivas compartiendo un servidor pueden emitir el mismo id JSON-RPC, y entonces la segunda llamada se rechaza con `-32603` en lugar de arriesgarse a que una respuesta llegue a quien no llamó.

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

### Servidores mixtos

Puedes usar servidores del SDK y externos a la vez:

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

### Configuración MCP estricta

Por defecto, la CLI carga servidores MCP del `.mcp.json` del proyecto, de los ajustes de usuario/globales y de cualquier plugin, además de lo que pases por `mcpServers(...)`. Define `strictMcpConfig(true)` para ignorar todo salvo los servidores que pases:

```java
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("app", sdkServer))
    .strictMcpConfig(true)   // ignore project / user / plugin MCP configs
    .build();
```

Se corresponde con la bandera `--strict-mcp-config` de la CLI. Resulta útil para despliegues reproducibles o aislamiento de pruebas cuando quieres control exacto sobre qué servidores MCP son alcanzables.

## Ejemplos

### Ejemplo 1: calculadora

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

### Ejemplo 2: acceso a base de datos

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

### Ejemplo 3: integración con una API

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

## Buenas prácticas

### 1. Usa tipos de retorno adecuados

```java
// ✅ Good: Specific result types
ToolResult.text("Simple text response");
ToolResult.json(Map.of("key", "value"));
ToolResult.error("Error message");

// ❌ Bad: Always using text for structured data
ToolResult.text("{\"key\":\"value\"}");  // Should use json()
```

### 2. Gestiona los errores con cuidado

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

### 3. Escribe buenas descripciones

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

### 4. Usa esquemas explícitos para entradas complejas

El esquema declarado es lo que el SDK usa para validar los argumentos, así que cuanto más precisamente describa la entrada, más cosas puede dar por sentadas el handler, y más útil es el mensaje que recibe el modelo cuando llama mal a la herramienta. Una herramienta sin esquema no se valida en absoluto.

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

### 5. Mantén las herramientas centradas

```java
// ✅ Good: Single responsibility
@Tool(name = "add_numbers", description = "Add two numbers")
@Tool(name = "multiply_numbers", description = "Multiply two numbers")

// ❌ Bad: Too much in one tool
@Tool(name = "math", description = "Do any math operation")
```

### 6. Usa asincronía para las operaciones de E/S

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

### 7. Configura los permisos de las herramientas

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

## Estado de los servidores MCP

`ClaudeSDKClient.getMcpStatus()` devuelve un `McpStatusResponse` con el estado de conexión actual de todos los servidores MCP configurados.

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

### McpServerStatusConfig (interfaz sellada)

Representa la configuración del servidor en las respuestas de estado. Es polimórfica: usa pattern matching.

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

### Ejemplo: comprobar el estado de MCP

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

## Véase también

- [Opciones de configuración](./feature-configuration-options.md) — las opciones mcpServers y tools
- [Ejemplo de uso de herramientas](../../examples/src/main/java/examples/McpServer.java) — ejemplos completos
- [Ejemplo de generación automática de esquema](../../examples/src/main/java/examples/AutoSchemaGeneration.java)
- [Especificación de MCP](https://spec.modelcontextprotocol.io/) — documentación oficial del protocolo MCP
