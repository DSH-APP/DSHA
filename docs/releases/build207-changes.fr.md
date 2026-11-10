# DSHA 0.2.1-rc.3-20261029.1500-dsh0.2.1-rc.3 / build207

- Ajout de trois modes d’interception des commandes root : Strict (par défaut, liste d’autorisation uniquement), Assoupli (toute commande, mais les opérations irréversibles comme le formatage, le flash, le redémarrage ou l’effacement de données restent bloquées) et Complet (aucun contrôle du contenu des commandes) ; basculez dans Paramètres → Autorisations de l’appareil.
- Les modes Assoupli et Complet ne s’appliquent qu’au canal root. L’exécuteur générique Shizuku/ADB reste strict, et la lecture des SMS exige toujours une autorisation native dans les trois modes.
- Les commandes root brutes s’exécutent dans le processus privilégié avec délai d’expiration, troncature de la sortie à 256 Kio et vérification de l’identité d’exécution. Le mode Strict bloque aussi la suppression récursive des racines de stockage et des répertoires de premier niveau.
- Il s’agit de l’identité de test SunsetRNE (pas E7E3) avec une signature de test fixe. La vérification couvre uniquement l’installation sur ce téléphone et les trois modes root ; tous les ROM et modèles ne sont pas couverts.
