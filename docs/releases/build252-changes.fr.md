# DSHA 0.2.1-rc.3-20261101.2130-dsh0.2.1-rc.5 / build252

- Page d'autodiagnostic simplifiée : sur téléphone, carte d'état, résultats, retours et outils se suivent, les six groupes devenant trois sections ; sur tablette, état, retours et outils à gauche, résultats à droite.
- Les éléments à traiter passent en premier, les contrôles non vérifiés sont marqués à part d'un point gris et les contrôles sains se replient en une ligne « N OK » ; un contrôle non vérifié ne signale plus de problème quand DSH n'est pas lancé. Deux colonnes sur grand écran, une seule en police 1,3x.
- L'export devient une feuille : choisissez d'abord un paquet de diagnostic ou un rapport texte ; les étapes de reproduction facultatives ne sont jointes qu'à cet export et jamais enregistrées. Le dernier fichier enregistré n'apparaît qu'après un export.
- Portée de la vérification : tests unitaires locaux et contrôles statiques uniquement ; aucune installation sur appareil, tests d'interface et Lint confiés à la CI. versionCode porté à 252 (basé sur build251). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
