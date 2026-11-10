# DSHA 0.2.1-rc.5-20261105.1930-dsh0.2.2-rc.1 / build323

- DSH 0.2.2, première étape : le DSH intégré et le DSH d’urgence autonome passent tous deux à 0.2.2-rc.1, l’exécuteur hôte et la migration de configuration restant compatibles.
- Les entrées d’exécution gérées sont reconstruites pour 0.2.2 et reverrouillées sur leurs empreintes fixes ; plugins, sessions et données personnelles restent en place.
- Problème d’affichage connu : à la première ouverture d’une page, « reconnexion » clignote brièvement puis « connecté ». C’est le comportement d’affichage de l’avis de première connexion en amont ; cela n’affecte ni la connexion ni les conversations, et un correctif est prévu pour la prochaine version.
- Portée de la vérification : tests hôte, contrôle des descripteurs d’exécution et contrôles de source ; les conclusions sur appareil suivent le relevé réellement confirmé et ne sont pas élargies à une matrice de modèles. versionCode porté à 323 (basé sur build322). La révision DSHA reste 0.2.1-rc.5 et le DSH intégré est 0.2.2-rc.1.
