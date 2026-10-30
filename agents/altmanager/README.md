# AltManager-Agent

Agent Java que adiciona um gerenciador de contas **offline** (cracked) ao Lunar
Client 1.8.9, com tela própria dentro do jogo. Não usa Weave e não altera nenhum
outro comportamento do cliente.

## Como funciona

Duas partes, ambas no mesmo jar:

1. **`Minecraft.getSession()` é hookado** (ASM, no premain). Quando existe uma
   conta marcada como ativa em `alts.properties`, o getter passa a devolver uma
   `Session` offline construída na hora; quando não existe, o corpo original roda
   sem nenhuma alteração.
   - o UUID é o UUID offline de verdade: `UUID.nameUUIDFromBytes("OfflinePlayer:<nome>")`,
     que é o que os servidores em `online-mode=false` calculam do lado deles;
   - token `"0"` e tipo `"legacy"`;
   - a `Session` é criada por reflexão, então funciona tanto com o construtor
     vanilla `(String, String, String, String)` quanto com a variante que recebe
     `Session$Type`;
   - a leitura é cacheada por *mtime*, então trocar a conta ativa vale **no
     próximo join, sem reiniciar o jogo**.

2. **`GuiAltManager`** é uma `GuiScreen` normal, mas compilada contra stubs e
   **definida dentro do class loader do jogo em runtime**
   (`MethodHandles.privateLookupIn(GuiScreen).defineClass`). Isso é necessário
   porque o `GenesisClassLoader` não enxerga as classes do jar do agent.
   A definição acontece dentro do próprio código injetado, no primeiro uso: o
   agent publica o bytecode da tela em `%TEMP%\altmanager-gui.class` e aponta o
   caminho por system property.

A tela abre pelo **botão "Alt Manager"** na lista de servidores
(`GuiMultiplayer`), no canto inferior esquerdo. Ele chama
`Minecraft.alt$open(this)`, que instancia a tela e chama `displayGuiScreen`.
Nenhuma thread, nenhum polling, nenhuma dependência do `loadClass` do jogo: se
algo falhar, é logado e o cliente segue normal.

## Tela

Só primitivas do jogo (`Gui.drawRect`, `FontRenderer.drawString`/`getStringWidth`,
`GuiButton` e `drawDefaultBackground()`), sem fontes ou texturas próprias:

- painel central translúcido (`0x50000000`) com moldura de 1 px sobre o fundo do jogo;
- lista de contas à esquerda, com barra verde na conta ativa e a etiqueta `active`;
- detalhes da conta selecionada à direita: nome e status (o UUID offline é usado
  na sessão, mas não é exibido);
- setas de paginação no topo da lista, aparecendo só quando há mais contas que
  linhas visíveis, desabilitadas nas pontas e com o scroll seguindo a seleção;
- `Add` / `Rename` / `Delete` / `Set Active` / `Done` como `GuiButton` normais;
  com a conta ativa selecionada, o botão vira **`Set Inactive`** e limpa a conta
  ativa (o jogo volta a usar a sessão do launcher no próximo join);
- campo de nome no centro (borda de 1 px + fundo translúcido), Enter confirma,
  Esc cancela, **Ctrl+A** seleciona tudo e **Ctrl+V** cola (o texto é filtrado
  para `[A-Za-z0-9_]`), nick `3-16`;
- a mensagem de status aparece entre o painel e os botões;
- salva a cada alteração (arquivo temporário + move atômico);
- `Done` / `Esc` voltam para a lista de servidores.

## Configuração

`alts.properties`, criado ao lado do jar (`<lunar>/agents/alts.properties`):

```properties
active=Notch
accounts=Notch,Jeb_
```

O arquivo é gerenciado pela tela; editar à mão só é necessário para reordenar ou
limpar contas com o jogo fechado.

## Build

Requer JDK 17 (`$env:JAVA_HOME`, ou `C:\Program Files\Zulu\zulu-17` sem a
variável) e os jars do ASM 9.2 no cache do Gradle, os mesmos dos outros agents
(fora do cache padrão, defina `ASM_HOME`).

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Gera `%TEMP%\altmanagerbuild\altmanager.jar`. Para já instalar em
`$env:LUNAR_AGENTS` (ou `C:\Users\laion\Desktop\Lunar\agents` sem a variável):

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install
```

O build também garante que nenhum stub (`Minecraft`, `GuiScreen`, `GuiButton`,
…) vaze para o jar: eles existem só para compilar.

## Teste offline

Validado com classes sintéticas no formato 1.8.9 (`Minecraft`, `GuiScreen`,
`GuiMultiplayer`, `GuiButton`, `Session`), usando o transformer real e
`-Xverify:all`. São dois testes porque um `Minecraft` patchado em class loader
separado não convive com a `GuiScreen` sintética da aplicação:

- **AltTest** — transformer aplicado, `getSession()` mantém a sessão original
  sem conta ativa, devolve a conta ativa com UUID offline e token `"0"`,
  `alt$open()` define e exibe a tela, a lista de servidores ganha o botão
  "Alt Manager" e o clique abre a tela.
- **GuiTest** — a tela renderiza vazia e com conta, adiciona conta (Enter),
  grava `accounts`/`active`, rejeita nome com 2 caracteres e exclui a conta
  ativa limpando o `active`.

O que **não** dá para validar fora do jogo: o `GenesisClassLoader` real e o
desenho na GPU. Se a tela não abrir, o log diz exatamente onde parou.

## Log

`%TEMP%\altmanager-status.log` — mostra o patch do `Minecraft`, o patch da lista
de servidores, a definição da tela e qualquer falha ao abrir.

## Limitações

- Contas offline **só entram em servidores `online-mode=false`**. Servidor
  premium rejeita a sessão ("Failed to verify username") — é assim que o
  protocolo funciona, e não tem como contornar pelo lado do cliente.
- Sem skin (o servidor offline não fornece) e sem acesso à conta Mojang.
- Como qualquer troca de nick, usar em servidor que não permite é contra as
  regras dele.

## Créditos

Ideia e fluxo inspirados no `AltManager` do
[WiltLunar](https://github.com/XiaoHeiPa/WiltLunar) (GPL-3.0). O código aqui é
uma reimplementação do zero: sem Weave, sem Mixin, sem login Yggdrasil com
senha, e com tela própria.
