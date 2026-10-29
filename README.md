# lunar-agents

Java agents (`-javaagent`) para o Minecraft 1.8.9 rodando no Lunar Client. Cada
agent é um jar independente que instrumenta classes do cliente em tempo de
carregamento via ASM, sem depender do Weave.

Os jars são carregados pelo [lunar-launcher-inject](https://github.com/laiongabriel/lunar-launcher-inject),
que injeta `-javaagent:` no processo do Lunar a partir de uma pasta `agents`
ao lado do executável (por padrão `%USERPROFILE%\Desktop\Lunar\agents`).

## Agents

| Pasta | Jar gerado | O que faz |
| --- | --- | --- |
| [`agents/accuratecps`](agents/accuratecps) | `accuratecps.jar` | Faz o HUD de CPS contar as ações reais de clique (`clickMouse` / `rightClickMouse`) em vez dos eventos crus do mouse. |
| [`agents/fastplace`](agents/fastplace) | `fastplace.jar` | Zera o `rightClickDelayTimer` para colocar bloco/lançar ovo a cada tick com o botão direito pressionado. |
| [`agents/nohitdelay`](agents/nohitdelay) | `nohitdelay.jar` | Remove o delay de 10 ticks aplicado depois de um clique esquerdo que erra. |
| [`agents/unrestricted`](agents/unrestricted) | `unrestricted.jar` | Aplica os hooks do `weave-unrestricted` (blacklist de mods, polling rate, texture pack, reach display e gate de staff/xray). |

Todos os agents:

- são carregados como `premain` (e também expõem `agentmain` quando aplicável);
- embutem o ASM 9.2 no próprio jar, então não têm dependências externas em runtime;
- se identificam por nome, string exclusiva ou forma de bytecode, para continuar
  funcionando com os mapeamentos ofuscados do Lunar;
- geram log de status em `%TEMP%` (veja o README de cada agent).

## Build

Requisitos:

- JDK 17 (os scripts usam `C:\Program Files\Zulu\zulu-17`; ajuste o caminho
  `$jdk` no script do agent se o seu estiver em outro lugar);
- ASM 9.2 no cache do Gradle (`~/.gradle/caches/modules-2/files-2.1/org.ow2.asm`).

Build de todos os agents:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Build + cópia para `C:\Users\laion\Desktop\Lunar\agents`:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install
```

Build individual (cada pasta tem seu próprio script):

```powershell
powershell -ExecutionPolicy Bypass -File .\agents\accuratecps\build.ps1
powershell -ExecutionPolicy Bypass -File .\agents\nohitdelay\build.ps1
powershell -ExecutionPolicy Bypass -File .\agents\unrestricted\build.ps1
cd .\agents\fastplace; .\gradlew.bat build
```

Os scripts geram os jars em `%TEMP%` (`cpsbuild`, `nhdbuild`,
`unrestrictedbuild`) e o FastPlace em `agents\fastplace\build\libs`.

## Estrutura

```
lunar-agents/
├── build.ps1            # build de todos os agents (opcional: -Install)
├── agents/
│   ├── accuratecps/     # Agent.java + CpsRuntime.java + build.ps1
│   ├── fastplace/       # projeto Gradle (build.gradle + gradlew)
│   ├── nohitdelay/      # Agent.java + build.ps1
│   └── unrestricted/    # Agent.java + UnrestrictedTransformer.java + build.ps1
└── README.md
```

## Créditos e licenças

Este repositório reúne ports/derivados de projetos de terceiros. Cada pasta
mantém a licença e o README originais quando existem.

- **fastplace** — fork de [Youded-byte/FastPlace-Agent](https://github.com/Youded-byte/FastPlace-Agent),
  GPL-2.0 (veja `agents/fastplace/LICENSE`). A versão aqui troca o patch de
  `ICONST_4` por uma busca pelo campo `rightClickDelayTimer` e usa delay 2 ticks.
- **accuratecps** — baseado em [ZirctaWeave/AccurateCPS](https://github.com/ZirctaWeave/AccurateCPS)
  (Unlicense), reescrito como agent ASM em vez de mod Weave.
- **nohitdelay** — ideias de [WeaveNoHitDelay](https://github.com/Nilsen84/WeaveNoHitDelay)
  e [NoHitDelayDll](https://github.com/Nilsen84/NoHitDelayDll), implementadas como
  agent Java.
- **unrestricted** — port de [weave-unrestricted](https://github.com/marioparaschiv/weave-unrestricted)
  / [unrestricted](https://github.com/Youded-byte/unrestricted) para `premain`.

## Aviso

Estes agents alteram o comportamento do cliente e podem violar as regras de
servidores (o FastPlace, por exemplo, não é permitido no Hypixel). Use apenas em
servidores/singleplayer com permissão do dono.
