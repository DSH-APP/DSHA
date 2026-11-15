# DSHA 0.2.1-rc.3-20261102.1000-dsh0.2.1-rc.5 / build270

- Expérimental : page d'îlot d'état des tâches redessinée. Une carte d'aperçu en haut montre la capsule et la notification visibles après le retour à l'écran d'accueil ; l'état du système ne se contente plus de dire qu'il ne peut être confirmé, il explique quoi faire et renvoie aux réglages système, avec un raccourci pour activer l'interrupteur système s'il est coupé.
- Quand l'interrupteur principal est coupé, celui du minuteur est grisé mais garde son choix enregistré ; une liste de l'ordre de l'îlot met en évidence le niveau de tâche qui l'occupe ; les écrans larges utilisent deux colonnes. Les réglages existants ne changent pas.
- Il s'agit d'une mise à jour d'interface expérimentale sans revue visuelle unifiée sur appareil.
- build269 n'a pas été publié séparément : sa publication a échoué au contrôle Lint car la page des téléchargements utilisait une mauvaise constante de coupure de ligne ; c'est corrigé et la réécriture de cette page est livrée avec cette version.
- Portée de la vérification : tests unitaires et contrôles de source locaux uniquement ; pas de rendu sur appareil. versionCode porté à 270 (basé sur build269). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
