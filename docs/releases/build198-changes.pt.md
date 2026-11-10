# DSHA 0.2.1-rc.3-20261025.2100-dsh0.2.1-rc.3 / build198

- Integradas as melhorias de gestão de plugins de Sunset-3090-Q, reduzindo atualizações repetidas e preservando operações confirmadas.
- Corrigida a pausa permanente da leitura do ecrã após alterar a confirmação global. Recusas explícitas, pausas do utilizador e permissões Android continuam válidas.
- Removidos espaços de trabalho de PC, emparelhamento QR, conectores de PC e retransmissores. Mantidos o controlo do telefone e o acesso local/LAN.
- Unificada a área segura superior entre a interface nativa e a Web, mantendo a barra de estado visível. Mantidos dez idiomas e o idioma do sistema.

## Notas históricas do ramo de teste Sunset separado (não são validação desta compilação integrada)

### DSHA 0.2.1-rc.3-20261026.0644-dsh0.2.1-rc.3 / build198

- O código de versão foi atualizado para 198. A bola flutuante do terminal e o menu da bancada agora abrem a barra lateral de sessões: o modo minimalista e bancadas estreitas usam uma gaveta, enquanto bancadas com 840dp ou mais mantêm a barra lateral fixa. A barra reúne criação, troca, renomeação, encerramento com verificação, início, exibição, teclado, colagem, foco de entrada e ajuda.
- A compilação de teste independente Sunset agora usa dsh.android.client.SunsetRNE2 e pode coexistir com o aplicativo de produção dsh.android.client e a compilação de teste SunsetRNE anterior.
- Esta compilação ainda não passou por build Android, testes unitários, Lint, empacotamento de APK ou instalação e validação em dispositivo.
