# DSHA 0.2.1-rc.3-20261029.0741-dsh0.2.1-rc.3 / build208

- - Corrigido o controle do telefone tratar as próprias janelas sobrepostas do DSHA (barra de status, assistente, confirmação) como oclusão do alvo: sobreposições próprias não registradas e sem foco são excluídas por inteiro pela identidade do pacote e não produzem mais TARGET_WINDOW_OCCLUDED. Trajetórias de coordenadas que cruzam a região excluída continuam recusadas, e uma cobertura completa ainda não deixa área utilizável (#210).
- - Em dispositivos onde link() é rejeitado, a materialização dos links antigos do pnpm como cópias completas não é mais morta no meio por um tempo fixo de 120 segundos; o limite passa a ser 15 minutos contra travamentos reais, evitando que a cópia interrompida deixe a transação pendente e bloqueie a próxima inicialização (#209).
- - Manter dsh-web-mobile 3.2.2, DSH rc.3, acesso local e à rede local e dez idiomas. A validação no telefone continua suspensa.
