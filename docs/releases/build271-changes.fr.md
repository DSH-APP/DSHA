# DSHA 0.2.1-rc.3-20261102.1030-dsh0.2.1-rc.5 / build271

- Expérimental : nouvelle capacité Appareil externe, désactivée par défaut. Une fois activée séparément dans le groupe des canaux de Réglages → Autorisations de l'appareil, elle permet de piloter un autre téléphone en USB ou en débogage sans fil et d'y installer un APK, APKS, XAPK ou module après confirmation ; chaque installation demande d'abord votre accord.
- Le flashage se limite à un contrôle préalable en lecture seule et à une simulation, sans aucune écriture ; la capacité exige un root autorisé et n'hérite ni de l'ADB ni des autorisations globales de ce téléphone.
- Cette fonction expérimentale n'a pas été vérifiée avec un appareil connecté et peut ne pas fonctionner.
- Portée de la vérification : tests unitaires et contrôles de source locaux uniquement ; non vérifié sur appareil. versionCode porté à 271 (basé sur build270). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
