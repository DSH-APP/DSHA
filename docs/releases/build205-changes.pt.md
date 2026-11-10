# DSHA 0.2.1-rc.3-20261027.1312-dsh0.2.1-rc.3 / build205

- A colagem no terminal usa uma fila FIFO de entrada limitada; a colagem delimitada é enviada como uma só entrada. Quando a fila enche, as entradas seguintes ficam pausadas para confirmação; o conteúdo não enviado não é reenviado automaticamente.
- Quando o resultado da inicialização da tela virtual for desconhecido, preservar os diagnósticos permitidos. Um diagnóstico não comprova a saída remota nem remove a proteção para resultado desconhecido.
- Vincular as ações de controle do telefone ao alvo da observação de tela bem-sucedida mais recente. Uma mudança de alvo ou nova observação exige evidência atualizada; uma observação antiga não restaura a permissão.
- Quando o DNS do dispositivo estiver disponível, migrar apenas a configuração DNS padrão antiga que corresponda exatamente aos bytes originais. Preservar configurações alteradas e a configuração antiga quando o DNS do dispositivo estiver indisponível.
- Ampliar os testes de regressão de recuperação de fluxos de sessão e espaço de trabalho, sondagem de downloads e respostas compactadas. Esses testes não validam o telefone nem a reinicialização de um servidor real.
- Manter dsh-web-mobile 3.2.2, DSH rc.3, acesso local e à rede local e dez idiomas. A validação no telefone continua suspensa.
