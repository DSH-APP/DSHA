# DSHA 0.2.1-rc.5-20261105.0230-dsh0.2.1-rc.5 / build316

- Verificação de dependências de plugins: o caminho rápido da análise CAS volta a verificar o tipo e o inode das entradas; trocar um ficheiro por uma ligação e repor a data de modificação no mesmo intervalo deixa de passar despercebido. O instantâneo de documentos passa a libertar a autorização quando a inicialização do SQLite falha, pelo que a navegação seguinte já não fica bloqueada.
- Âmbito da verificação: apenas testes unitários direcionados, Lint e verificações de código locais; não verificado num dispositivo. versionCode elevado para 316 (com base no build315). A revisão DSHA mantém-se 0.2.1-rc.5 e o DSH incluído mantém-se 0.2.1-rc.5.
