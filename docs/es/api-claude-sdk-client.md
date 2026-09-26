# Referencia de la API ClaudeSDKClient

Client interactivo para conversaciones bidireccionales.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../api-claude-sdk-client.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general de la clase

```java
public class ClaudeSDKClient implements AutoCloseable
```

Client para conversaciones con estado e interactivas con Claude.

## Constructor

```java
public ClaudeSDKClient()
public ClaudeSDKClient(ClaudeAgentOptions options)
public ClaudeSDKClient(ClaudeAgentOptions options, Transport transport)
```

Con un `transport` no nulo, el client lo usa en lugar de lanzar el subproceso del CLI. El SDK llama
a su `connect()`, escribe los prompts mediante `write()` y envía los hooks, los agentes y los demás
ajustes de la petición initialize a través del protocolo de control. Las opciones que son flags del
CLI (modelo, cwd, modo de permisos, herramientas y el resto de las que el transporte de subproceso
convierte en flags) no se aplican a un transporte propio, y se omite la reanudación de sesión
respaldada por un store.

## Métodos de conexión

### connect()

```java
public void connect() throws CLIConnectionException
```

Establece la conexión con el CLI de Claude Code.

**Seguridad entre hilos**: seguro entre hilos, idempotente

**Lanza**: `IllegalStateException` si se llama después de close()

### connect(String initialMessage)

```java
public void connect(String initialMessage) throws CLIConnectionException
```

Conecta y envía un mensaje inicial.

### isConnected()

```java
public boolean isConnected()
```

Comprueba si hay conexión.

**Devuelve**: `boolean`

### disconnect() / close()

```java
public void disconnect()
public void close()
```

Cierra la conexión y libera los recursos.

**Seguridad entre hilos**: seguro entre hilos, idempotente

## Enviar mensajes

### sendMessage(String prompt)

```java
public void sendMessage(String prompt)
```

Envía un mensaje y sigue recibiendo.

### sendMessage(String prompt, String sessionId)

```java
public void sendMessage(String prompt, String sessionId)
```

Envía un mensaje a una sesión concreta.

### query(String prompt)

```java
public void query(String prompt)
```

Envía un mensaje. Vuelve en cuanto el prompt queda escrito: lee la respuesta con
[`receiveResponse()`](#receiveresponse) o [`receiveMessages()`](#receivemessages).

Con `verbatimPrompts(true)` en las opciones, todos los prompts que escribe este client —desde
`connect(String)`, `sendMessage` y las dos formas de `query`— se marcan con `client_composed: true`,
de modo que Claude Code los entrega tal como están escritos (sin expansión de `@path` ni despacho de
comandos slash). Los mensajes en streaming se marcan sobre una copia.

Equivale a `query(prompt, "default")`.

### query(String prompt, String sessionId)

```java
public void query(String prompt, String sessionId)
```

Consulta una sesión concreta.

### query(Iterator&lt;Map&lt;String, Object&gt;&gt; messageStream)

```java
public void query(Iterator<Map<String, Object>> messageStream)
public void query(Iterator<Map<String, Object>> messageStream, String sessionId)
```

Envía mapas de mensaje en bruto. Recurre a este en lugar de `query(String)` cuando el mensaje
necesite campos que la forma en cadena no construye: atribución de `origin`, bloques de contenido
estructurados, un `uuid` explícito:

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", "Reply with exactly: one"));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));   // attribute the turn

client.query(List.of(message).iterator());
for (Message msg : client.receiveResponse()) { /* ... */ }
```

El `session_id` se rellena con `"default"` —o con `sessionId` en la sobrecarga de dos argumentos— en
cualquier mensaje que lo omita. Los mapas de quien llama se copian en lugar de modificarse cuando se
añade ese campo, así que pasar un mapa inmutable es seguro.

Igual que `query(String)`, este método deja abierto el stdin del CLI, así que se puede llamar
repetidamente a lo largo de una sesión y mezclar libremente con la sobrecarga en cadena.

> **Cambiado en la v0.1.23.** Antes este método entregaba el iterador a la ruta interna de streaming
> de un solo uso, que cierra el stdin en cuanto el iterador se agota. Eso terminaba la sesión: el CLI
> salía y el siguiente `query()` o `sendMessage()` fallaba con
> `ProcessTransport is not ready for writing`. Ahora escribe directamente, igual que el SDK de
> Python. Quien dependiera del comportamiento antiguo para terminar una sesión debe llamar a
> `disconnect()` (o usar try-with-resources).

Los mensajes se escriben antes de que la llamada retorne, lo que mantiene el orden entre llamadas
sucesivas. Si necesitas leer respuestas mientras el iterador todavía está produciendo, hazlo
avanzar desde tu propio hilo cuando sea perezoso o ilimitado.

## Recibir mensajes

### receiveMessages()

```java
public Iterator<Message> receiveMessages()
```

Obtiene un iterador sobre todos los mensajes (continuo).

**Seguridad entre hilos**: seguro entre hilos, pero los mensajes se reparten entre varios iteradores

**Devuelve**: `Iterator<Message>`

### receiveResponse()

```java
public Iterable<Message> receiveResponse()
```

Obtiene los mensajes hasta el siguiente ResultMessage (se cierra automáticamente).

**Seguridad entre hilos**: seguro entre hilos

**Devuelve**: `Iterable<Message>`

## Métodos de control

### interrupt()

```java
public void interrupt()
```

Interrumpe la ejecución en curso.

**Seguridad entre hilos**: seguro entre hilos

### setModel(String model)

```java
public void setModel(String model)
```

Cambia el modelo de IA.

**Parámetros**: `model`, nombre o alias del modelo (por ejemplo, `"claude-sonnet-5"`, `"haiku"`), o `null` para el predeterminado. El CLI comprueba los IDs completos de modelo contra la API, así que un ID retirado falla; un alias se asigna a un modelo actual.

### setPermissionMode(PermissionMode mode)

```java
public void setPermissionMode(PermissionMode mode)
```

Cambia el modo de permisos.

**Parámetros**: `mode`, el nuevo modo de permisos

### rewindFiles(String userMessageId)

```java
public void rewindFiles(String userMessageId)
```

Devuelve los archivos rastreados al estado que tenían en un mensaje de usuario.

Requiere `enableFileCheckpointing(true)` para rastrear los cambios en los archivos, y
`extraArgs(Map.of("replay-user-messages", ""))` para que los objetos `UserMessage` del flujo lleven el
`uuid` al que volver.

**Parámetros**: `userMessageId`, UUID del mensaje de usuario al que volver

### getMcpStatus()

```java
public McpStatusResponse getMcpStatus()
```

Obtiene el estado de conexión de los servidores MCP.

**Devuelve**: `McpStatusResponse`, cuyo `mcpServers()` enumera un `McpServerStatus` por servidor

### reconnectMcpServer(String serverName)

```java
public void reconnectMcpServer(String serverName)
```

Reintenta la conexión con un servidor MCP que no pudo conectarse o que se desconectó.

### toggleMcpServer(String serverName, boolean enabled)

```java
public void toggleMcpServer(String serverName, boolean enabled)
```

Habilita o deshabilita un servidor MCP. Deshabilitarlo lo desconecta y retira sus herramientas del
conjunto disponible; habilitarlo lo vuelve a conectar y hace que vuelvan a estar disponibles.

### stopTask(String taskId)

```java
public void stopTask(String taskId)
```

Detiene una tarea en segundo plano en ejecución.

**Parámetros**: `taskId`, el ID de tarea de un `TaskStartedMessage`

Cuando este método retorna, el CLI informa del final de la tarea como un `TaskUpdatedMessage` cuyo
estado es terminal (`"killed"` para una tarea detenida). Puede seguirle un `TaskNotificationMessage`
con estado `"stopped"`, pero a veces se suprime, así que trata como final un estado terminal de
cualquiera de los dos mensajes (consulta `TaskUpdatedMessage.TERMINAL_TASK_STATUSES`).

### getContextUsage()

```java
public ContextUsageResponse getContextUsage()
```

Obtiene un desglose por categorías del uso actual de la ventana de contexto.

Devuelve los mismos datos que muestra el comando `/context` en el CLI, incluidos el recuento de
tokens por categoría, el uso total y desgloses detallados de herramientas MCP, archivos de memoria y
agentes.

**Devuelve**: `ContextUsageResponse` con los campos:
- `categories`: lista de `ContextUsageCategory` (name, tokens, color)
- `totalTokens`: total de tokens en la ventana de contexto
- `maxTokens`: límite efectivo de contexto
- `percentage`: porcentaje de contexto usado (0-100)
- `model`: nombre del modelo
- Y campos opcionales: `autoCompactThreshold`, `memoryFiles`, `mcpTools`, `agents`, etc.

**Lanza**: `CLIConnectionException` si no hay conexión

### getServerInfo()

```java
public Map<String, Object> getServerInfo()
```

Obtiene la información de inicialización del servidor: comandos disponibles, estilos de salida y
capacidades del servidor.

**Devuelve**: `Map<String, Object>`, la información de la respuesta a `initialize` (`null` solo si el
CLI no devolvió ninguna)

## Seguridad entre hilos

- **connect()**: seguro entre hilos, sincronizado
- **métodos de envío**: seguros entre hilos
- **métodos de recepción**: seguros entre hilos, pero comparten la cola
- **métodos de control**: seguros entre hilos
- **close()**: seguro entre hilos, idempotente

## Gestión de recursos

Usa siempre try-with-resources:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
}
```

## Véase también
- [Guía de conversaciones interactivas](./feature-interactive-conversations.md)
