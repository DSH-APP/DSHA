# DSHA 0.2.1-rc.3-20261103.0130-dsh0.2.1-rc.5 / build280

- Si le port du pont d'appareil n'est pas publié ou illisible, la requête échoue au lieu de se rabattre sur le port 3090 avec le jeton de cette installation ; les identifiants ne sont donc pas envoyés à une autre installation DSHA du même appareil.
- Portée de la vérification : tests unitaires ciblés, Lint et contrôles de source locaux uniquement ; non vérifié sur appareil. versionCode porté à 280 (basé sur build279). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
