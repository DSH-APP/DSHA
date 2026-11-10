# DSHA 0.2.1-rc.3-20261102.0630-dsh0.2.1-rc.5 / build263

- Fermer un onglet de terminal ne bloque plus l'app : fermer le dernier onglet faisait attendre l'interface jusqu'à la fin complète de la session, ce qui pouvait dépasser 5 secondes et afficher L'application ne répond pas ; choisir alors Fermer l'application arrêtait aussi DSH. L'attente de fin de session ne bloque plus l'interface et DSH n'est plus entraîné.
- Portée de la vérification : l'utilisateur a confirmé sur un paquet de test que fermer un onglet de terminal ne bloque plus l'app et que DSH reste connecté ; tests unitaires et contrôles de source locaux réussis. versionCode porté à 263 (basé sur build262). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
