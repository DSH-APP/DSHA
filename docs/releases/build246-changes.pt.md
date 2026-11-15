# DSHA 0.2.1-rc.3-20261101.1600-dsh0.2.1-rc.5 / build246

- Corrige a janela do assistente flutuante não estar isolada do teclado: o assistente recolhido fica na parte inferior da tela e, ao tocar num campo de texto de outro app, subia junto com o teclado desse app e cobria o conteúdo perto da área de entrada.
- O assistente flutuante nunca recebe entrada de texto (a edição é feita na página separada do assistente). Agora a janela respeita apenas as barras do sistema e ignora o teclado de outros apps, e a área de exclusão que ele registra para a observação de tela não muda mais quando o teclado abre ou fecha.
- Escopo da verificação: apenas testes unitários locais/CI; **não testado em dispositivo real**. A correção pode não funcionar em algumas versões do Android ou ROMs (o assistente pode continuar subindo com o teclado); é necessário retorno dos usuários para confirmar. versionCode elevado para 246. A revisão do DSHA continua 0.2.1-rc.3 e o DSH incluído continua 0.2.1-rc.5.
