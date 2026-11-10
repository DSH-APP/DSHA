# DSHA 0.2.1-rc.3-20261030.0214-dsh0.2.1-rc.3 / build210

- O dsh-web-mobile embutido foi sincronizado com o conteúdo mais recente do upstream (main @ 04dcd19, versão ainda 3.2.2): perfil de toque para tablet, entrada de download de arquivos e ajustes rápidos na gaveta, com os patches locais realinhados ao novo upstream.
- Corrigidas falhas falsas na verificação do pacote de produção: o aapt2 novo imprime a API mínima como minSdkVersion, e o APK não é mais recusado por minSdk divergente.
- O sufixo versionName do build de teste voltou a -SunsetTest, e a página de atualização volta a reconhecer a identidade de teste.
- As condições do workflow de release agora usam o nome atual do repositório e main, então o job não é mais ignorado após a renomeação.
- Gates de fonte completados: textos de LAN entraram no catálogo de dez idiomas, atributos de acessibilidade no item de idioma e no seletor de DNS, e o contraste de texto suave e do estado pressionado chega a 4.5:1.
- Fachada do HarnessController dividida: a leitura de assets empacotados foi para PackagedAssets e o backup em um clique não depende mais diretamente do core.
- As 14 regressões de host agora passam; resultados de build e release seguem os recibos do repositório.
