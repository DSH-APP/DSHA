# DSHA 0.2.1-rc.3-20261030.1506-dsh0.2.1-rc.3 / build212

- La récupération initiale décide désormais selon trois états : se reconnecter si le Web d'origine est toujours vivant ; ne purger les anciens enregistrements et poursuivre le démarrage qu'après confirmation que le Web et son lanceur sont tous deux sortis ; conserver la protection lorsqu'on ne peut pas confirmer.
- Quand la sortie est confirmée, l'enregistrement chiffré de l'instance et l'ancien enregistrement PID sont retirés ensemble, pour que la prochaine ouverture ne s'arrête plus sur « confirmation en attente ».
- Un appui sur démarrer pendant la vérification de l'ancienne instance enregistre l'intention de cette passe et la poursuit automatiquement une fois après la fin ; un arrêt, une nouvelle génération ou une session déjà reconnectée ne démarrent jamais deux fois.
- Si l'identité est confirmée mais que l'authentification échoue brièvement, le lien est revérifié au plus 3 fois (attentes bornées de 500ms/1500ms) ; l'état non confirmé n'est conservé qu'ensuite.
- La page de démarrage affiche selon la raison les états et entrées — reconnexion, lanceur encore en sortie, enregistrement illisible, authentification temporairement indisponible — et, pendant la vérification, le bouton démarrer reste désactivé, tandis que les entrées redémarrer/démarrage sûr restent cliquables et enregistrent l'intention de démarrage de cette passe, en la poursuivant automatiquement une fois la vérification terminée.
- Le versionCode passe à 212 ; le périmètre de build et de recette suit cette exécution et le reçu du Release.
