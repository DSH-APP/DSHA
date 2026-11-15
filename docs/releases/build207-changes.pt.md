# DSHA 0.2.1-rc.3-20261029.1500-dsh0.2.1-rc.3 / build207

- Adicionados três modos de interceptação de comandos root: Estrito (padrão, apenas lista permitida), Relaxado (qualquer comando, mas operações irreversíveis como formatar, gravar firmware, reiniciar ou limpar dados continuam bloqueadas) e Completo (sem verificação do conteúdo dos comandos); alterne em Configurações → Permissões do dispositivo.
- Relaxado e Completo aplicam-se apenas ao canal root. O executor genérico Shizuku/ADB continua estrito, e a leitura de SMS ainda exige autorização nativa nos três modos.
- Comandos root brutos são executados no processo privilegiado com tempo limite, truncamento de saída em 256 KiB e verificação de identidade de execução. O modo Estrito também bloqueia a remoção recursiva de raízes de armazenamento e diretórios de primeiro nível.
- Este é o identificador de teste SunsetRNE (não E7E3) com assinatura de teste fixa. A verificação cobre apenas a instalação neste telefone e os três modos root; todos os ROMs e modelos não são cobertos.
