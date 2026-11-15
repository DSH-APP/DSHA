# DSHA 0.2.1-rc.5-20261105.0230-dsh0.2.1-rc.5 / build316

- Vérification des dépendances de plugins : le chemin rapide de l’analyse CAS revérifie le type et l’inode des entrées ; remplacer un fichier par un lien puis rétablir sa date de modification dans le même intervalle n’échappe plus à la détection. L’instantané de documents libère désormais son autorisation si l’initialisation de SQLite échoue, la navigation suivante ne se bloque plus.
- Portée de la vérification : tests unitaires ciblés, Lint et contrôles de source locaux uniquement ; non vérifié sur appareil. versionCode porté à 316 (basé sur build315). La révision DSHA reste 0.2.1-rc.5 et le DSH intégré reste 0.2.1-rc.5.
