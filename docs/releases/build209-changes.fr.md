# DSHA 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209

- Nom de l’application : DSH-A-Su (Sunset Mod) ; applicationId : io.github.sunsetrne.dsha. Installation en parallèle de l’original dsh.android.client, données totalement isolées, sans écraser les anciennes.
- Signature de publication recréée : le certificat historique E7E3 n’est plus utilisé, au profit du certificat de publication propre au Mod. verifyPublishCertificate vérifie désormais le nouveau certificat et le nouveau nom de paquet ; l’empreinte attendue est consignée dans ci/release-identity.json.
- La variante Île (notifications promues) rejoint le paquet normal : plus de build séparée ni de nom de paquet distinct.
- Abandon d’Android 10 et antérieurs : la ligne de compatibilité low / GeckoView est supprimée ; minimum Android 11 (API 30), arm64-v8a uniquement.
- Déclaration d’accessibilité commune à toutes les variantes : placée dans le manifest principal, sans packageNames et sans surcharge par flavor ; libellé et description passent à « DSH-A-Su contrôle d’écran », en plus concis.
- Les dix langues restent prises en charge : zh, zh-Hant, en, ru, ja, ko, ar, pt, la, fr.
- Les libellés courts d’interface en fr, pt, ru, ar et la sont encore raccourcis pour libérer de l’espace.
- Correction du libellé latin (la) corrompu pour « Suivant ».
- Les entrées de build fixes passent aux ressources de prérelease Release du dépôt public, verrouillées par SHA-256 ; le dépôt public permet de restaurer l’ensemble des entrées de build (voir le [document des entrées de build fixes](../../docs/fixed-build-inputs.md)).
- Les outils de publication et les assertions CI liés au nom de paquet et au certificat sont mis à jour ; les résultats de build et de gate figurent dans les reçus du dépôt.
