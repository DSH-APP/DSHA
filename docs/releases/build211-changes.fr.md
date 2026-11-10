# DSHA 0.2.1-rc.3-20261030.1245-dsh0.2.1-rc.3 / build211

- La barre de progression de la page des plugins passe au schéma F : respiration en état indéterminé, lumière filante en état déterminé, et la progression ne suit que le percent réel.
- Le succès affiche du vert à 100 % pendant 700 ms ; l’échec affiche du rouge à la progression maximale de la tâche pendant 900 ms, puis s’efface.
- La longue note permanente sur la source de téléchargement passe dans la fenêtre du bouton rond à point d’exclamation, qui affiche aussi le dernier résultat ou la dernière erreur.
- Les résultats de plugins ont désormais un état explicite succès/échec : file pleine, exécuteur d’arrière-plan indisponible ou erreur de sélection de fichier s’affichent aussi dans cette fenêtre.
- Les deux emplacements de source partagent la même barre ; jetons clair/sombre, repli mouvement réduit et sémantique de progression TalkBack sont couverts.
- Le périmètre de build, de tests unitaires et de recette sur appareil se limite à cette exécution et au reçu de Release.
