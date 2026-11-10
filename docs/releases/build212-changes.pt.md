# DSHA 0.2.1-rc.3-20261030.1506-dsh0.2.1-rc.3 / build212

- A recuperação inicial agora decide em três estados: reconecta se o Web original ainda estiver vivo; só limpa os registros antigos e continua a inicialização depois de confirmar que o Web e o launcher saíram; e mantém a proteção quando não for possível confirmar.
- Quando a saída é confirmada, o registro criptografado da instância e o registro de PID antigo são aposentados juntos, para que a próxima abertura não pare em "aguardando confirmação".
- Tocar em iniciar durante a verificação da instância antiga registra a intenção desta rodada e continua automaticamente uma vez ao concluir; parada, nova geração ou sessão já reconectada não iniciam de novo.
- Se a identidade está confirmada mas a autenticação falha por instantes, o vínculo é reverificado no máximo 3 vezes (esperas limitadas de 500ms/1500ms); só então o estado não confirmado é mantido.
- A página de inicialização mostra estados e entradas por motivo — reconectando, launcher ainda saindo, registro ilegível, autenticação temporariamente indisponível — e, durante a verificação, o botão iniciar fica desabilitado, enquanto as entradas reiniciar/início seguro continuam clicáveis e registram a intenção de início desta rodada, continuando automaticamente uma vez ao concluir a verificação.
- O versionCode sobe para 212; o escopo de build e aceitação segue esta rodada e o recibo do Release.
