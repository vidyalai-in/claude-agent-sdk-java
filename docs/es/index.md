# Claude Agent SDK for Java - Documentación técnica

[English](../README.md) · [简体中文](../zh/index.md) · [日本語](../ja/index.md) · [한국어](../ko/index.md) · [Português](../pt/index.md) · **Español**

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../README.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido. Consulta [docs/TRANSLATIONS.md](../TRANSLATIONS.md) para más detalles.

Te damos la bienvenida a la documentación técnica del Claude Agent SDK for Java. Esta
documentación ofrece información completa sobre la arquitectura, las funciones y el uso del SDK.

## Visión general

El Claude Agent SDK for Java es una biblioteca completa para integrar las capacidades de IA de
Claude en aplicaciones Java. Proporciona una API de Java moderna y con seguridad de tipos para
interactuar con el CLI de Claude Code, y admite tanto consultas puntuales sencillas como
conversaciones complejas de varios turnos.

**Aspectos destacados:**
- 🎯 **API con seguridad de tipos**: interfaces selladas y records, de modo que la coincidencia exhaustiva de patrones funciona en el código consumidor a partir de Java 21
- ⚡ **Hilos virtuales**: el trabajo en segundo plano se ejecuta en hilos virtuales de Project Loom en Java 21+, y en hilos de plataforma daemon en 17-20
- 🔧 **Arquitectura flexible**: admite tanto consultas sin estado como conversaciones con estado
- 🛠️ **Herramientas personalizadas**: crea tus propias herramientas con MCP (Model Context Protocol)
- 🔌 **Plugins**: carga plugins de Claude Code (comandos, agentes, skills, hooks) desde directorios locales
- 🎨 **Patrón builder**: API fluida para la configuración

## Índice de la documentación

### Arquitectura y diseño
- **[Visión general de la arquitectura](./architecture.md)**: arquitectura del sistema, patrones de diseño y estructura interna
  - Diagrama de arquitectura de alto nivel
  - Componentes principales (capa de API, configuración, protocolo, transporte)
  - Patrones de diseño (interfaces selladas, builder, fachada, hilos virtuales)
  - Diagramas de flujo de datos y modelo de concurrencia
  - Ciclo de vida de stdin: cuándo termina una ejecución (estado de la sesión, libro de tareas, límite entre turnos)
  - Jerarquía del sistema de tipos y dependencias

### Funciones principales
- **[Consultas simples](./feature-simple-queries.md)**: consultas puntuales con la fachada ClaudeSDK
  - Ejemplos de uso básico
  - Resumen de los métodos de consulta
  - Opciones de configuración
  - Patrones de manejo de mensajes
  - Buenas prácticas

- **[Conversaciones interactivas](./feature-interactive-conversations.md)**: conversaciones de varios turnos con ClaudeSDKClient
  - Gestión de la conexión
  - Envío y recepción de mensajes
  - Métodos de control
  - Gestión de sesiones
  - Seguridad entre hilos
  - Ejemplos completos

- **[Opciones de configuración](./feature-configuration-options.md)**: guía completa del builder ClaudeAgentOptions
  - Las más de 30 opciones de configuración
  - Configuración de herramientas
  - Formas del prompt de sistema y `snapshot`
  - Ajustes de permisos
  - Configuración del modelo
  - Variables de entorno, y las que el propio SDK establece
  - `verbatimPrompts`: entregar los prompts sin expansión de `@path` ni comandos slash
  - Hooks y callbacks
  - Ejemplos completos de los patrones habituales

- **[Tipos de mensaje](./feature-message-types.md)**: entender el sistema de tipos de mensaje
  - UserMessage, AssistantMessage, SystemMessage, ResultMessage, StreamEvent, RateLimitEvent
  - Mensajes del ciclo de vida de tareas (TaskStartedMessage, TaskProgressMessage, TaskNotificationMessage, TaskUpdatedMessage)
  - HookEventMessage (cuando `includeHookEvents` está habilitado)
  - DeferredToolUse en ResultMessage; campo de estado HTTP `apiErrorStatus`
  - ConversationResetMessage: la conversación se reemplazó a mitad de sesión (por ejemplo, `/clear`)
  - Origen del mensaje: distinguir tus propios turnos de los inyectados por la sesión
  - Bloques de contenido (Text, Thinking, ToolUse, ToolResult)
  - Coincidencia de patrones
  - Ejemplos y buenas prácticas

- **[Servidores MCP](./feature-mcp-servers.md)**: crear herramientas personalizadas con el Model Context Protocol
  - Servidores MCP del SDK (in-process)
  - Servidores MCP externos (stdio/SSE/HTTP)
  - Uso de la anotación @Tool
  - Creación programática de herramientas
  - Esquemas de herramienta y validación de argumentos contra el `inputSchema` de la herramienta
  - Semántica de fallos (resultados `isError` para todo lo que encuentra una llamada a herramienta)
  - Cancelar una herramienta en ejecución con `ToolCallContext`
  - Detalles del protocolo (negociación de versión, notificaciones, métodos)
  - Handlers MCP personalizados mediante `McpMessageHandler`
  - Patrones de ejecución asíncrona
  - Ejemplos completos (calculadora, base de datos, integración con API)

- **[Definiciones de agente](./feature-agents.md)**: subagentes personalizados con prompts, herramientas y modelos específicos
  - Definiciones de agente en línea
  - Agentes basados en el sistema de archivos
  - Soporte para agentes grandes (más de 260KB mediante stdin)
  - API AgentDefinition (con campos de skills, alcance de memoria y servidores MCP)
  - Observar la salida de un subagente y `forwardSubagentText`

- **[Historial de sesiones](./feature-session-history.md)**: leer y gestionar desde disco sesiones anteriores de conversación de Claude Code
  - Listar sesiones de todos los proyectos o filtradas por directorio
  - Consultar una sesión concreta por su ID (`getSessionInfo`)
  - Leer transcripciones completas de conversación
  - Leer transcripciones de subagentes (`listSubagents`, `getSubagentMessages`),
    atribuidas al `tool_use` del Agent que las originó
  - Renombrar sesiones (`renameSession`)
  - Etiquetar sesiones para organizarlas (`tagSession`)
  - Eliminar sesiones (`deleteSession`): borra en cascada el directorio de transcripciones de subagentes
  - Bifurcar sesiones con reasignación de UUID (`forkSession`)
  - Reanudación con truncado (`resumeSessionAt` / `resumeDropsTurn`): rebobina con seguridad a un punto anterior
  - Tipos SDKSessionInfo (con tag, createdAt y fileSize anulable) y SessionMessage
  - Paginación con offset y compatibilidad con worktree

- **[Session Store](./feature-session-store.md)**: replica transcripciones a S3 / Postgres / Redis / back-ends propios
  - Protocolo del adaptador `SessionStore` con variantes síncrona y asíncrona (`CompletableFuture`)
  - Ejecutor de hilos virtuales configurable mediante `SessionStoreExecutor`
  - Adaptador de referencia `InMemorySessionStore` incluido y helper `filePathToSessionKey`
  - API de lectura: `listSessionsFromStore`, `getSessionInfoFromStore`, `getSessionMessagesFromStore`, `listSubagentsFromStore`, `getSubagentMessagesFromStore`
  - API de mutación: `renameSessionViaStore`, `tagSessionViaStore`, `deleteSessionViaStore`, `forkSessionViaStore`
  - `importSessionToStore` para reproducir de local a store; `MirrorErrorMessage` para fallos de anexado no fatales
  - Conjunto público de pruebas `SessionStoreConformance` (14 contratos, independiente del framework)
  - Reanudar desde un store (el subproceso recibe un `CLAUDE_CONFIG_DIR` temporal); agrupador del espejo de transcripciones

- **[Skills](./feature-skills.md)**: opción `skills` de nivel superior para la sesión principal
  - Tres modos: `skillsAll()`, `skills(List)`, `skills(List.of())`
  - Inyecta automáticamente `Skill(name)` en `allowedTools` y fija el valor por defecto de `settingSources`
  - Propagación por el protocolo mediante la petición de control initialize
  - Inyección idempotente; los ajustes explícitos siempre ganan
  - Validación del nombre de la skill: impide inyectar reglas en `--allowedTools` y rechaza nombres que nunca podrían coincidir

- **[Propagación de W3C Trace Context](./feature-trace-context.md)**: trazado distribuido entre el SDK y el CLI
  - Inyección best-effort de `TRACEPARENT`/`TRACESTATE` en el subproceso del CLI
  - Cero dependencias en tiempo de ejecución de OpenTelemetry (basado en reflexión)
  - Limpieza de variables de entorno obsoletas, contextos solo con baggage, errores de propagadores

### Funciones avanzadas
- **[Configuración del razonamiento extendido](./feature-thinking-config.md)**: controla la profundidad del razonamiento de Claude
  - Tipos ThinkingConfig (Adaptive, Enabled, Disabled)
  - Niveles de esfuerzo (low, medium, high, max)
  - Control y optimización del presupuesto
  - Ejemplos de uso completos

- **[Sistema de hooks](./feature-hooks.md)**: interceptar eventos del ciclo de vida y responder a ellos
  - 10 eventos de hook
  - HookMatcher y HookOutput
  - `updatedToolOutput` de PostToolUse (sustituye la salida de cualquier herramienta) y `updatedMCPToolOutput`
  - `PermissionDecision.DEFER` y `DeferredToolUse` en ResultMessage
  - `includeHookEvents` y el flujo de HookEventMessage
  - Ejemplos para casos de uso habituales

- **[Sistema de permisos](./feature-permissions.md)**: callbacks y modos de permiso personalizados
  - Modos de permiso
  - Callbacks de permiso personalizados (solo se disparan en decisiones `"ask"`)
  - Sombreado y cómo mantener determinista un callback con `settingSources(List.of())`
  - `ToolPermissionContext` enriquecido (`title`, `displayName`, `description`, `decisionReason`, `blockedPath`)
  - Ejemplos basados en rutas, en el tiempo y con confirmación del usuario

- **[Eventos de streaming](./feature-streaming-events.md)**: actualizaciones parciales de mensaje en tiempo real
  - Habilitar el streaming
  - Procesar eventos de stream
  - Ejemplos de integración con la interfaz

- **[Capa de transporte](./feature-transport-layer.md)**: implementaciones de transporte propias
  - Interfaz Transport
  - Implementación por defecto
  - Ejemplo de transporte personalizado
  - Rechazo de scripts batch en Windows y la habilitación explícita para instalaciones npm con `claude.cmd`

- **[Sistema de plugins](./feature-plugin-system.md)**: cargar plugins de Claude Code
  - `SdkPluginConfig.local(path)` → `--plugin-dir`
  - Estructura de un plugin y cómo verificar que se cargó

### Referencia de la API
- **[ClaudeSDK](./api-claude-sdk.md)**: fachada estática para consultas sencillas
  - Métodos de consulta
  - Métodos de fábrica de client
  - Métodos de fábrica de servidores MCP
  - Métodos de conveniencia

- **[ClaudeSDKClient](./api-claude-sdk-client.md)**: client interactivo para conversaciones
  - Métodos de conexión
  - Envío y recepción de mensajes
  - Métodos de control
  - Notas sobre seguridad entre hilos

- **[ClaudeAgentOptions](./api-claude-agent-options.md)**: builder de configuración
  - Todas las opciones de configuración
  - Métodos del builder

- **[Tipos de mensaje](./api-message-types.md)**: jerarquía completa de tipos de mensaje
  - Todos los tipos de mensaje y bloques de contenido
  - Documentación de los campos

- **[Tipos de excepción](./api-exceptions.md)**: manejo de errores y excepciones
  - Jerarquía de excepciones
  - `ResultException` y la carga útil de un resultado de error terminal
  - Dónde aparece realmente cada excepción
  - Ejemplos de manejo de errores

### Recursos del proyecto
- **[CHANGELOG](../CHANGELOG.md)**: historial de versiones y notas de publicación (solo en inglés)
- **[Paridad con el SDK de Python](../PYTHON_SDK_PARITY.md)**: comparación de funciones con el SDK de Python (solo en inglés)
- **[Sobre las traducciones](../TRANSLATIONS.md)**: alcance de las traducciones, política de sincronización y cómo contribuir (en inglés)

## Estructura del proyecto

Este es un proyecto Maven con varios módulos:

```
claude-agent-sdk-java/
├── sdk/              # Core SDK library (published to Maven Central; mirrored to GitHub Packages)
│   ├── src/main/java/in/vidyalai/claude/sdk/
│   │   ├── ClaudeSDK.java              # Main facade
│   │   ├── ClaudeSDKClient.java        # Interactive client
│   │   ├── ClaudeAgentOptions.java     # Configuration builder
│   │   ├── exceptions/                 # Exception types
│   │   ├── transport/                  # Transport layer
│   │   ├── internal/                   # Internal implementation
│   │   ├── mcp/                        # MCP server support
│   │   └── types/                      # Type definitions
│   └── src/test/java/                  # SDK tests
└── examples/         # Usage examples (separate module)
    └── src/main/java/examples/
        ├── QuickStart.java
        ├── MultiTurnConversation.java
        ├── McpServer.java
        └── ... (15+ examples)
```

## Primeros pasos

### Requisitos previos
- Java 17 o posterior (a partir del 21 se usan hilos virtuales automáticamente)
- Maven 3.6+
- El CLI de Claude Code instalado por separado

### Instalación

Añádelo a tu `pom.xml`: no hace falta configurar repositorios ni autenticación:

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.2.2</version>
</dependency>
```

Las versiones también se replican en GitHub Packages para quienes ya apuntan allí. Esa vía
requiere un token de acceso personal aunque los artefactos sean públicos, así que usa Maven
Central salvo que tengas un motivo para no hacerlo; consulta el
[README raíz](./README.md#alternativa-github-packages) para la configuración de repositorio y
autenticación.

### Hello World

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;

// Simple query
List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        System.out.println(assistant.getTextContent());
    }
}
```

### Conversación interactiva

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Hello!");

    for (var msg : client.receiveResponse()) {
        // Process messages
    }

    client.sendMessage("Tell me more");
    for (var msg : client.receiveResponse()) {
        // Process follow-up
    }
}
```

## Ejemplos

El SDK incluye más de 30 ejemplos ejecutables que cubren:
- Consultas y conversaciones básicas
- Herramientas MCP personalizadas
- Callbacks de permisos
- Sistema de hooks, incluidos los hooks atendidos en el turno de seguimiento de un subagente en segundo plano (`BackgroundAgentHooksExample`)
- Eventos de streaming
- Manejo de errores
- Prompts de sistema, incluido `snapshot` (`SystemPromptExample`)
- Entrega literal de prompts (`VerbatimPromptsExample`)
- Funciones avanzadas (checkpoints, sandbox, formato de salida)
- Y mucho más...

Consulta el directorio `examples/` del repositorio.

## Cómo usar esta documentación

1. **Usuarios nuevos**: empieza por este README para la instalación y el inicio rápido
2. **Entender la arquitectura**: lee la [Visión general de la arquitectura](./architecture.md)
3. **Casos de uso sencillos**: sigue [Consultas simples](./feature-simple-queries.md)
4. **Herramientas personalizadas**: aprende sobre [Servidores MCP](./feature-mcp-servers.md)
5. **Funciones avanzadas**: explora las demás guías según lo necesites

## Estado de la documentación

### ✅ Completado: toda la documentación principal
- README principal con inicio rápido y visión general
- Visión general de la arquitectura (exhaustiva, con diagramas; subsistema SessionStore;
  manejo de fallos en peticiones de control; ciclo de vida de stdin y detección del final de la ejecución)
- Todas las guías de funciones:
  - Consultas simples
  - Conversaciones interactivas
  - Opciones de configuración (incluidos `sessionStore`, `loadTimeoutMs`,
    `verbatimPrompts` y el `snapshot` del prompt de sistema)
  - Tipos de mensaje (mensajes de tarea, bloques de herramienta del servidor, MirrorErrorMessage)
  - Servidores MCP (con ToolAnnotations, títulos de herramienta, tipos de estado, validación de
    entrada, semántica de fallos, cancelación y handlers personalizados)
  - Definiciones de agente
  - Configuración del razonamiento extendido (con `ThinkingDisplay`)
  - Sistema de hooks (con los campos agentId/agentType)
  - Sistema de permisos
  - Eventos de streaming
  - Capa de transporte (con `--session-mirror`, `--thinking-display`, eliminación de `--debug-to-stderr`)
  - Sistema de plugins
  - Historial de sesiones (listSessions / getSessionMessages)
  - Session Store (replicar transcripciones a S3/Postgres/Redis/back-ends propios; ejecutores
    acotados)
- Referencia completa de la API (5 documentos):
  - ClaudeSDK (incluidos los métodos de historial de sesiones)
  - ClaudeSDKClient
  - ClaudeAgentOptions
  - Tipos de mensaje
  - Tipos de excepción
- Ejemplos de código (más de 20 en el directorio examples/)
- Documentación de paridad con el SDK de Python

## Contribuir a la documentación

Al añadir documentación nueva:
1. Sigue la estructura y el formato existentes
2. Incluye ejemplos de código que funcionen
3. Verifica todos los ejemplos de código contra la implementación real
4. Añade referencias cruzadas a la documentación relacionada
5. Actualiza el índice de la documentación con los documentos nuevos
6. Sigue los estándares de la documentación:
   - Tabla de contenidos clara
   - Ejemplos prácticos
   - Sección de buenas prácticas
   - Sección "Véase también" con enlaces

## Principios de la documentación

Toda la documentación de este proyecto sigue estos principios:
1. **Exactitud**: todos los ejemplos de código deben funcionar y coincidir con la API real
2. **Completitud**: cubrir todos los casos de uso y escenarios principales
3. **Claridad**: usar un lenguaje claro y explicar los conceptos complejos
4. **Ejemplos**: incluir ejemplos de código prácticos y ejecutables
5. **Referencias cruzadas**: enlazar a la documentación relacionada
6. **Buenas prácticas**: incluir patrones recomendados y antipatrones
7. **Actualidad**: mantenerla sincronizada con los cambios del código

## Soporte y recursos

- **Repositorio de GitHub**: https://github.com/vidyalai-in/claude-agent-sdk-java
- **Issues**: informa de errores y solicita funciones en las Issues de GitHub
- **Código de ejemplo**: consulta el directorio `examples/` del repositorio
- **Especificación de MCP**: https://spec.modelcontextprotocol.io/
- **Licencia**: licencia MIT
- **SDK de Python**: para comparar, consulta https://github.com/anthropics/anthropic-sdk-python
- **Documentación del Claude Agent Python SDK**: https://platform.claude.com/docs/en/agent-sdk/python

## Contribuir

¡Las contribuciones son bienvenidas! Consulta el repositorio para ver las pautas de contribución.

## Versión

La versión actual es la que aparezca en [Maven Central](https://central.sonatype.com/artifact/in.vidyalai/claude-agent-sdk-java):
deliberadamente no se repite aquí, ya que una copia mantenida a mano llegó a quedarse cuatro
versiones desactualizada.

Consulta [CHANGELOG.md](../CHANGELOG.md) (solo en inglés) para el historial de versiones y las notas de publicación.
