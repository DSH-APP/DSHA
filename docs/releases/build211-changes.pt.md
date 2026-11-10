# DSHA 0.2.1-rc.3-20261030.1245-dsh0.2.1-rc.3 / build211

- A barra de progresso da página de plugins agora usa o esquema F: respiração no estado indeterminado, luz fluida no determinado, e o progresso segue apenas o percent real.
- O sucesso mostra verde em 100% por 700ms; a falha mostra vermelho no maior progresso visto na tarefa por 900ms e depois desaparece.
- A nota longa e fixa da fonte de download foi movida para o popup do botão redondo de exclamação, que também mostra o último resultado ou erro da tarefa.
- Os resultados de plugins agora têm estado explícito de sucesso/falha, então falhas antes do envio — fila cheia, executor em segundo plano indisponível, erro de seleção de arquivo — também aparecem nesse popup.
- Os dois pontos de fonte de download compartilham a mesma barra; tokens claro/escuro, fallback de movimento reduzido e semântica de progresso do TalkBack foram cobertos.
- O escopo de build, testes unitários e aceitação em dispositivo é limitado a esta run e ao recibo do Release.
