# DSHA 0.2.1-rc.3-20261027.1149-dsh0.2.1-rc.3 / build204

- Interface: o aspeto das páginas v5 do protótipo de interação passa a cobrir definições, plugins, configuração de execução, instalação e ambiente, autorizações do dispositivo, dados e cópias de segurança, atualizações, diagnóstico, boas-vindas/primeiro arranque, sobre e recuperação do ambiente; títulos, grupos, cartões, campos e botões usam a mesma escala de 26sp e a mesma paleta clara/noturna.
- Terminal: «Nova sessão», «Terminar sessão» e «Voltar à introdução» respondem ao primeiro toque; o seletor «Sistema / Claro / Escuro» foi removido da barra lateral e o terminal segue o tema da aplicação sem recriar sessões nem repetir comandos.
- Remate: a página de emparelhamento ADB deixou de mostrar o título duplicado, a confirmação de instalação por ligação passou para a escala de 26sp e a página de manutenção do ambiente (extração) usa cartões e botões v5.
- Integradas as melhorias de gestão de plugins de Sunset-3090-Q, reduzindo atualizações repetidas e preservando operações confirmadas.
- Corrigida a pausa permanente da leitura do ecrã após alterar a confirmação global. Recusas explícitas, pausas do utilizador e permissões Android continuam válidas.
- Removidos espaços de trabalho de PC, emparelhamento QR, conectores de PC e retransmissores. Mantidos o controlo do telefone e o acesso local/LAN.
- Unificada a área segura superior entre a interface nativa e a Web, mantendo a barra de estado visível. Mantidos dez idiomas e o idioma do sistema.
- O lançamento na tela virtual aceita generation opcional e verifica o estado antes de iniciar; o pedido nativo contém apenas coreInstance e package. Essa verificação prévia não é uma barreira atômica de geração.
- Esta integração de código inclui dsh-web-mobile 3.2.2 e DSH rc.3. A validação no telefone continua suspensa; resultados de outros ramos ou pacotes não comprovam esta compilação.
