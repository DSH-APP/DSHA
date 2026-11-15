# DSHA 0.2.1-rc.3-20261102.0630-dsh0.2.1-rc.5 / build263

- Fechar um separador do terminal já não bloqueia a app: fechar o último separador fazia a interface esperar que a sessão terminasse por completo, o que podia ultrapassar 5 segundos e mostrar A aplicação não está a responder; escolher então Fechar aplicação também terminava o DSH. A espera pelo fim da sessão já não bloqueia a interface e o DSH deixa de ser arrastado.
- Âmbito da verificação: o utilizador confirmou num pacote de teste que fechar um separador do terminal já não bloqueia a app e o DSH mantém a ligação; testes unitários e verificações de código locais aprovados. versionCode elevado para 263 (com base no build262). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
