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

O CLI informa os plugins carregados na mensagem de sistema `init`. Leia-os por meio de
`SystemMessage.initData()`, que também lista os plugins que falharam ao carregar:

```java
for (Message msg : ClaudeSDK.query("Hello!", options)) {
    if (msg instanceof SystemMessage system && system.initData() != null) {
        SystemInitData init = system.initData();
        for (SystemInitPlugin plugin : init.plugins()) {
            System.out.println(plugin.name() + " (" + plugin.path() + ")");
        }
        if (init.pluginErrors() != null) {
            for (SystemInitPluginError error : init.pluginErrors()) {
                System.out.println("Failed: " + error.plugin() + " - " + error.type() + ": " + error.message());
            }
        }
    }
}
```

`SystemInitPlugin.version()` é a versão declarada no manifesto do plugin, ou `null` quando ele não
declara nenhuma. Para um diretório de plugin que nem chegou a carregar, `SystemInitPluginError.plugin()`
pode ser uma marcação posicional como `inline[0]`, e `path()` é o caminho dessa entrada resolvido em
relação ao cwd. Veja
[Tipos de mensagem → A mensagem init](./feature-message-types.md#a-mensagem-init-systeminitdata).

## Veja também
- [Opções de configuração](./feature-configuration-options.md#plugins) — opção plugins
- [Exemplo de Plugins](../../examples/src/main/java/examples/PluginsExample.java)
