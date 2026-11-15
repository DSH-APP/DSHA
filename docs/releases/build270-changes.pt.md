# DSHA 0.2.1-rc.3-20261102.1000-dsh0.2.1-rc.5 / build270

- Experimental: página da ilha de estado das tarefas redesenhada. Um cartão de pré-visualização no topo mostra a cápsula e a notificação que verá ao voltar ao ecrã inicial; o estado do sistema já não diz apenas que não pode ser confirmado, explica o que fazer e liga às definições do sistema, com um atalho para ligar o interruptor do sistema quando está desligado.
- Com o interruptor principal desligado, o do temporizador fica cinzento mas mantém a escolha guardada; uma nova lista da ordem da ilha destaca o nível de tarefa que a ocupa; ecrãs largos usam duas colunas. As definições existentes não mudam.
- É uma atualização de interface experimental ainda sem revisão visual unificada num dispositivo.
- O build269 não foi publicado à parte: a publicação falhou na verificação Lint porque a página de transferências usava a constante de quebra de linha errada; isso foi corrigido e a reescrita da página segue com esta versão.
- Âmbito da verificação: apenas testes unitários e verificações de código locais; sem renderização num dispositivo. versionCode elevado para 270 (com base no build269). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
