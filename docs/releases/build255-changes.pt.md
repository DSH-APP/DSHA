# DSHA 0.2.1-rc.3-20261101.2300-dsh0.2.1-rc.5 / build255

- Rodapé da página de manutenção reorganizado: as três ações secundárias passam a botões pequenos com contorno e espaço entre si, os ícones sobem acima do texto com letra grande ou ecrã estreito, e o botão principal desativado distingue-se melhor.
- Após uma atualização falhada ou interrompida, o DSH de emergência fica realçado a vermelho; quando as verificações de prontidão falham, Registos de manutenção usa a cor de aviso. O comportamento ao tocar e os bloqueios de manutenção não mudam.
- Âmbito da verificação: apenas testes unitários locais e verificações estáticas; sem instalação em dispositivo, testes de interface e Lint ficam para a CI. versionCode elevado para 255 (com base no build254). A revisão DSHA mantém-se 0.2.1-rc.3 e o DSH incluído mantém-se 0.2.1-rc.5.
