# DSHA 0.2.1-rc.3-20261102.0530-dsh0.2.1-rc.5 / build261

- Colar texto longo no terminal já não bloqueia a app: cada carácter escrito voltava a fazer uma verificação completa do ambiente na thread da interface, por isso uma colagem longa podia ultrapassar 5 segundos e mostrar o aviso A aplicação não está a responder. A escrita e a troca de separadores no terminal leem agora um resultado de verificação em cache; continua a haver verificação completa durante a manutenção do ambiente ou quando os ficheiros relevantes mudam.
- Âmbito da verificação: o utilizador confirmou a colagem num pacote de teste; testes unitários e verificações de código locais aprovados. versionCode elevado para 261 (com base no build260). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
