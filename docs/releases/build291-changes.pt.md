# DSHA 0.2.1-rc.3-20261103.0700-dsh0.2.1-rc.5 / build291

- Corrige a cópia de segurança num toque que falhava sempre com uma mudança de localização dos dados: a aplicação passa a parar as escritas antes de confirmar as localizações; os ficheiros de estado próprios do dispositivo deixam de entrar nessa verificação e a cópia continua a parar se os dados do utilizador forem substituídos.
- Âmbito da verificação: apenas testes unitários direcionados, Lint e verificações de código locais; não verificado num dispositivo. versionCode elevado para 291 (com base no build290). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
