# DSHA 0.2.1-rc.3-20261102.0600-dsh0.2.1-rc.5 / build262

- Nova pré-visualização compacta do ecrã só de leitura: os assistentes de IA podem ler a estrutura completa do ecrã atual através de /app/sense/state com menos texto, com as mesmas permissões e isolamento de tarefas da leitura de ecrã existente. As etiquetas k*** da pré-visualização servem só para consulta e não permitem tocar; os toques continuam a usar as ações de nó existentes.
- As ações que um nó suporta passam a ser lidas uma vez por observação e verificadas de novo antes de executar, reduzindo enganos depois de o ecrã mudar.
- O build261 não foi publicado à parte: as Source checks e a publicação falharam porque faltavam três ficheiros novos na lista de formatação; a correção de colagem no terminal do build261 segue com esta versão.
- Âmbito da verificação: verificação só de leitura numa página de definições do sistema num dispositivo; testes unitários e verificações de código locais aprovados. versionCode elevado para 262 (com base no build261). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
