# DSHA 0.2.1-rc.3-20261102.0600-dsh0.2.1-rc.5 / build262

- Nouvel aperçu d'écran compact en lecture seule : les assistants IA peuvent lire la structure complète de l'écran actuel via /app/sense/state avec moins de texte, avec les mêmes autorisations et la même isolation des tâches que la lecture d'écran existante. Les repères k*** de l'aperçu servent seulement à consulter et ne permettent pas de toucher ; les touchers passent toujours par les actions de nœud existantes.
- Les actions possibles d'un nœud sont désormais lues une fois par observation et revérifiées avant exécution, ce qui réduit les erreurs après un changement d'écran.
- build261 n'a pas été publié séparément : ses Source checks et sa publication ont échoué car trois nouveaux fichiers manquaient à la liste de formatage ; le correctif de collage du terminal de build261 est livré avec cette version.
- Portée de la vérification : contrôle en lecture seule sur une page de réglages système d'un appareil ; tests unitaires et contrôles de source locaux réussis. versionCode porté à 262 (basé sur build261). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
