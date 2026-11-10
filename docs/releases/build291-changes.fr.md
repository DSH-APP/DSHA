# DSHA 0.2.1-rc.3-20261103.0700-dsh0.2.1-rc.5 / build291

- Corrige la sauvegarde en un geste qui échouait à chaque fois sur un changement d'emplacement des données : l'appli arrête désormais les écritures avant de confirmer les emplacements ; les fichiers d'état propres à l'appareil ne participent plus à ce contrôle, et la sauvegarde s'arrête toujours si des données utilisateur sont remplacées.
- Portée de la vérification : tests unitaires ciblés, Lint et contrôles de source locaux uniquement ; non vérifié sur appareil. versionCode porté à 291 (basé sur build290). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
