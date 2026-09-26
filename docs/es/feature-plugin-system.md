# Sistema de plugins

Carga plugins de Claude Code —comandos slash personalizados, agentes, skills y hooks empaquetados en un
directorio— en una sesión del SDK.

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-plugin-system.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

## Visión general

Un plugin es un directorio que el CLI de Claude Code carga al arrancar. El SDK no ejecuta por sí mismo
el código de los plugins: pasa el directorio de cada plugin al CLI con `--plugin-dir`, y el CLI
descubre lo que aporta el plugin.

## SdkPluginConfig

`SdkPluginConfig` es un record anidado en `ClaudeAgentOptions`:

```java
public record SdkPluginConfig(String type, String path) {
    public static SdkPluginConfig local(String path);  // type = "local"
}
```

Solo se admite el tipo `"local"`. Por cada plugin `local`, el transporte añade `--plugin-dir <path>` al
comando del CLI; una configuración con cualquier otro `type` se omite sin error, así que construye
siempre las configuraciones con `local(...)`.

## Configurar plugins

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.SdkPluginConfig;

var options = ClaudeAgentOptions.builder()
    .plugins(List.of(
        SdkPluginConfig.local("/path/to/my-plugin"),
        SdkPluginConfig.local("/path/to/another-plugin")))
    .build();
```

Los plugins se pasan en el orden de la lista, un `--plugin-dir` por plugin.

## Estructura de un plugin

El plugin de demostración del repositorio muestra la estructura mínima que espera el CLI:

```
examples/src/main/java/examples/plugins/demo-plugin/
├── .claude-plugin/
│   └── plugin.json       # Manifest: name, description, version, author
└── commands/
    └── greet.md          # A custom /greet slash command
```

`plugin.json`:

```json
{
  "name": "demo-plugin",
  "description": "A demo plugin showing how to extend Claude Code with custom commands",
  "version": "1.0.0",
  "author": {
    "name": "Claude Code Team"
  }
}
```

Los plugins también pueden aportar agentes, skills y hooks; consulta la documentación de plugins de
Claude Code para ver la estructura de directorios completa.

## Verificar que un plugin se cargó

El CLI informa de los plugins cargados en el campo `plugins` del mensaje de sistema `init`, una lista
de mapas con `name` y `path`:

```java
for (Message msg : ClaudeSDK.query("Hello!", options)) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        @SuppressWarnings("unchecked")
        List<Object> plugins = (List<Object>) system.get("plugins");
        if (plugins != null) {
            for (Object p : plugins) {
                if (p instanceof Map<?, ?> plugin) {
                    System.out.println(plugin.get("name") + " (" + plugin.get("path") + ")");
                }
            }
        }
    }
}
```

## Véase también
- [Opciones de configuración](./feature-configuration-options.md#plugins): opción plugins
- [Ejemplo de Plugins](../../examples/src/main/java/examples/PluginsExample.java)
