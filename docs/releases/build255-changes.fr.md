# DSHA 0.2.1-rc.3-20261101.2300-dsh0.2.1-rc.5 / build255

- Pied de la page de maintenance réorganisé : les trois actions secondaires deviennent des petits boutons cerclés et espacés, les icônes passent au-dessus du texte en grande police ou sur écran étroit, et le bouton principal désactivé se distingue mieux.
- Après une mise à jour échouée ou interrompue, DSH de secours est mis en rouge ; si les contrôles de disponibilité échouent, Journal de maintenance prend la couleur d'alerte. Le comportement au toucher et les verrous de maintenance ne changent pas.
- Portée de la vérification : tests unitaires locaux et contrôles statiques uniquement ; aucune installation sur appareil, tests d'interface et Lint confiés à la CI. versionCode porté à 255 (basé sur build254). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
