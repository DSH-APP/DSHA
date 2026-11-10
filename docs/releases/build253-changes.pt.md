# DSHA 0.2.1-rc.3-20261101.2200-dsh0.2.1-rc.5 / build253

- Acabamento do terminal: as duas filas de teclas extra têm espaço entre as teclas, as teclas de função ficam mais escuras, as setas maiores e ^C / ^D / ^Z e os símbolos usam letra monoespaçada; o bloqueio de Ctrl e Alt aparece numa barra inferior. A disposição personalizada e a personalização por toque longo não mudam.
- Barra lateral simplificada: as sessões PTY e de terminal simples partilham uma lista ordenada por criação, o estado de cada sessão é um pequeno ponto após o nome, e Terminar tudo fica oculto sem sessões e continua a pedir confirmação.
- Nova sessão passa a três cartões (terminal PTY, terminal simples, DSH CLI) e a ajuda mostra quatro pontos-chave com legenda de estados.
- Âmbito da verificação: apenas testes unitários locais do terminal e verificações estáticas; sem instalação em dispositivo, testes de interface e Lint ficam para a CI. versionCode elevado para 253 (com base no build252). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
