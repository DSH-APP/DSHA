# DSHA 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209

- O nome do aplicativo passa a ser DSH-A-Su (Sunset Mod) e o applicationId passa a ser io.github.sunsetrne.dsha; ele instala em paralelo com o original dsh.android.client, com dados totalmente isolados e sem sobrescrever os dados antigos.
- Assinatura de release criada do zero: o certificado histórico E7E3 deixa de ser usado em favor do certificado de release próprio do Mod, e verifyPublishCertificate passa a verificar o novo certificado e o novo nome de pacote, com o hash esperado registrado em ci/release-identity.json.
- A variante Ilha (notificações promovidas) entra no pacote normal: não há mais build separada nem nome de pacote próprio.
- Fim do suporte ao Android 10 e anteriores: a linha de compatibilidade low / GeckoView foi removida; mínimo Android 11 (API 30), apenas arm64-v8a.
- Declaração de acessibilidade compartilhada por todas as variantes: fica no main manifest, sem packageNames limitados e sem sobreposição de flavor; o rótulo e a descrição passam a "DSH-A-Su controle de tela", mais concisos.
- Mantidos os dez idiomas: zh, zh-Hant, en, ru, ja, ko, ar, pt, la, fr.
- Os rótulos curtos da interface em fr, pt, ru, ar e la foram ainda mais encurtados para ocupar menos tela.
- Corrigido o texto corrompido de "Próximo" em latim (la).
- As entradas fixas de build passam a usar os recursos de Release pré-lançamento do repositório público, com SHA-256 fixado; o repositório público permite restaurar todas as entradas de build (consulte o [documento de entradas fixas de build](../../docs/fixed-build-inputs.md)).
- Ferramentas de release e verificações de CI ligadas ao nome de pacote e ao certificado foram atualizadas em conjunto; os resultados de build e de gate constam dos recibos do repositório.
