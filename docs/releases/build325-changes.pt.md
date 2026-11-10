# DSHA 0.2.1-rc.5-20261105.2135-dsh0.2.2-rc.1 / build325

- Corrige o ecrã branco após uma instalação por cima do build324: a engrenagem do footer da barra lateral da shell móvel passa a ceder com base na largura medida, sem depender de o anfitrião mostrar o indicador de «estado da ligação»; a barra lateral deixa de se sobrepor e a área de trabalho deixa de ficar branca.
- A atualização por cima passa a desencadear normalmente a manutenção/recriação do ambiente, sem tratar «entrar no ecrã principal» como ação principal.
- Âmbito da verificação: testes de anfitrião, verificações de código e de descritores de runtime; o utilizador confirmou em dispositivo (pacote de teste) que o ecrã branco desapareceu e que a recriação do ambiente é desencadeada normalmente; visibilidade/clique da engrenagem, aviso de primeira ligação e confirmação de chamada de skill continuam pendentes de confirmação e não são escritos como aprovados. versionCode elevado para 325 (com base no build324).
