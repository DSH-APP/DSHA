# DSHA 0.2.1-rc.3-20261101.2130-dsh0.2.1-rc.5 / build252

- Página de autoverificação e diagnóstico simplificada: no telemóvel, cartão de estado, resultados, feedback e ferramentas surgem por ordem, juntando os seis grupos em três secções; no tablet, estado, feedback e ferramentas à esquerda e resultados à direita.
- Os itens que precisam de atenção vêm primeiro, as verificações não confirmadas ficam marcadas à parte com um ponto cinzento e as saudáveis recolhem-se numa linha «N OK»; com o DSH parado, as não confirmadas já não indicam problema. Ecrãs largos usam duas colunas; fonte 1,3x volta a uma.
- A exportação passa a ser uma folha: escolha primeiro um pacote de diagnóstico ou um relatório de texto; os passos de reprodução opcionais só seguem nessa exportação e nunca são guardados. O último ficheiro guardado só aparece após uma exportação.
- Âmbito da verificação: apenas testes unitários locais e verificações estáticas; sem instalação em dispositivo, testes de interface e Lint ficam para a CI. versionCode elevado para 252 (com base no build251). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
