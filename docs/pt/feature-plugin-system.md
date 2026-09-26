# Sistema de plugins

Carregue plugins do Claude Code — comandos de barra personalizados, agentes, skills e hooks
empacotados em um diretório — em uma sessão do SDK.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-plugin-system.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral

Um plugin é um diretório que o Claude Code CLI carrega na inicialização. O SDK não executa código
de plugin por conta própria: ele passa o diretório de cada plugin ao CLI com `--plugin-dir`, e o
CLI descobre o que o plugin fornece.

## SdkPluginConfig

`SdkPluginConfig` é um record aninhado em `ClaudeAgentOptions`:

```java
public record SdkPluginConfig(String type, String path) {
    public static SdkPluginConfig local(String path);  // type = "local"
}
```

Apenas o tipo `"local"` é suportado. Para cada plugin `local`, o transporte acrescenta
`--plugin-dir <path>` ao comando do CLI; uma configuração com qualquer outro `type` é ignorada sem
erro, então sempre construa as configurações com `local(...)`.

## Configurando plugins

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.SdkPluginConfig;

var options = ClaudeAgentOptions.builder()
    .plugins(List.of(
        SdkPluginConfig.local("/path/to/my-plugin"),
        SdkPluginConfig.local("/path/to/another-plugin")))
    .build();
```

Os plugins são repassados na ordem da lista, um `--plugin-dir` por plugin.

## Estrutura de um plugin

O plugin de demonstração do repositório mostra a estrutura mínima que o CLI espera:

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

Plugins também podem fornecer agentes, skills e hooks; consulte a documentação de plugins do
Claude Code para a estrutura de diretórios completa.

## Verificando se um plugin foi carregado

O CLI informa os plugins carregados no campo `plugins` da mensagem de sistema `init`, uma lista de
maps com `name` e `path`:

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

## Veja também
- [Opções de configuração](./feature-configuration-options.md#plugins) — opção plugins
- [Exemplo de Plugins](../../examples/src/main/java/examples/PluginsExample.java)
