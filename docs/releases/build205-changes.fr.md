# DSHA 0.2.1-rc.3-20261027.1312-dsh0.2.1-rc.3 / build205

- Le collage du terminal utilise une file d’entrée FIFO bornée ; le collage entre marqueurs forme une seule entrée. Si la file est pleine, les entrées suivantes sont suspendues en attente de confirmation ; le contenu non envoyé n’est pas réessayé automatiquement.
- Si le résultat du démarrage de l’écran virtuel est inconnu, conserver les diagnostics autorisés. Un diagnostic ne prouve pas l’arrêt distant et ne lève pas la protection liée au résultat inconnu.
- Lier les actions de contrôle du téléphone à la cible de la dernière observation réussie. Un changement de cible ou une nouvelle observation exige une preuve actualisée ; une ancienne observation ne rétablit pas l’autorisation.
- Lorsque le DNS de l’appareil est disponible, migrer uniquement la configuration DNS par défaut historique strictement identique octet par octet. Préserver les configurations modifiées et l’ancienne configuration si le DNS de l’appareil est indisponible.
- Étendre les scénarios de régression pour la reprise des flux de session et d’espace de travail, la détection du téléchargement et les réponses compressées. Ces scénarios ne valident ni le téléphone ni le redémarrage d’un serveur réel.
- Conserver dsh-web-mobile 3.2.2, DSH rc.3, l’accès local et réseau local et les dix langues. La validation sur téléphone reste suspendue.
