# DSHA 0.2.1-rc.3-20261031.0300-dsh0.2.1-rc.5 / build215

- Pacote menor: o DSH de emergência independente passa de 0.2.1-rc.3 para o mesmo 0.2.1-rc.5 do ambiente principal. Os dois arquivos de runtime agora são idênticos byte a byte, então o APK guarda uma única cópia (um arquivo DSH de cerca de 127,7 MB a menos); o ambiente de emergência continua sendo extraído no próprio diretório, com bloqueio, identidade, sessões e fluxo de inicialização próprios.
- Corrigida a falha na primeira preparação do DSH de emergência independente, causada pela lista de entradas da sobreposição que não correspondia ao descritor (desde o build193): as receitas agora são lidas para a versão do bloqueio de emergência, com novo teste unitário e verificação cruzada no build.
- Removidos recursos do BouncyCastle que o app nunca carrega (tabelas de constantes pós-quânticas do Picnic e mensagens de revisão de caminho de certificados, cerca de 1,2 MB); AES-GCM, SHA-256 e PBKDF2 usados na criptografia de backups não são afetados.
- versionCode elevado para 215; a revisão DSHA continua 0.2.1-rc.3 e o sufixo do DSH embutido é 0.2.1-rc.5; o escopo de build e aceitação segue esta execução e o recibo da Release.
