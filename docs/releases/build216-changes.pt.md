# DSHA 0.2.1-rc.3-20261031.0340-dsh0.2.1-rc.5 / build216

- O dsh-web-mobile embutido foi sincronizado com o upstream main e a tag v3.2.2 atual `bb0e5e1`: o painel direito em tela cheia recupera a faixa da folha e o preenchimento de papel de parede (#247), a sobreposição do pet não cobre mais folhas/painéis nem engole toques (#248), e o gesto do painel de arquivos lê `data-sidebar-right-open` e reconhece primeiro a raiz nomeada do pet (#249).
- Todos os patches locais do DSHA foram reaplicados aos novos bytes do upstream: as diferenças de entrada de sessão/fechamento da gaveta, linha de busca, pressionamento do fundo e linha de família de fontes permanecem; o upstream não absorveu o patch da linha de fontes.
- O lock do upstream passa a 232 membros e SHA-256 do arquivo `80973622…`; o descritor de runtime, os avisos de terceiros, os documentos de patches móveis e de proveniência foram atualizados, e referências de pacote, versionCode e DSH da era build190 foram corrigidas.
- O versionCode sobe para 216; a revisão DSHA permanece 0.2.1-rc.3 e o DSH embutido é 0.2.1-rc.5; o escopo de build e aceitação segue esta execução de CI e o recibo da Release.
