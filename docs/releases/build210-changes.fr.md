# DSHA 0.2.1-rc.3-20261030.0214-dsh0.2.1-rc.3 / build210

- Le dsh-web-mobile intégré est synchronisé avec le contenu amont le plus récent (main @ 04dcd19, version toujours 3.2.2) : profil tactile tablette, entrée de téléchargement de fichiers et réglages rapides du tiroir, avec des correctifs locaux réalignés sur le nouvel amont.
- Correction de faux échecs de vérification du paquet de production : le nouvel aapt2 imprime l’API minimale en minSdkVersion, et l’APK n’est plus refusé pour minSdk différent.
- Le suffixe versionName de la build de test revient à -SunsetTest, et la page de mise à jour reconnaît de nouveau l’identité de test.
- Les conditions du workflow de publication utilisent désormais le nom actuel du dépôt et main, donc la tâche n’est plus ignorée après le renommage.
- Portes source complétées : textes du réseau local ajoutés au catalogue des dix langues, attributs d’accessibilité sur l’entrée de langue et la liste DNS, contraste du texte discret et de l’état pressé porté à 4,5:1.
- Façade HarnessController scindée : la lecture des ressources embarquées passe dans PackagedAssets et la sauvegarde en un clic ne dépend plus directement du core.
- Les 14 régressions hôte passent ; les résultats de build et de publication suivent les reçus du dépôt.
