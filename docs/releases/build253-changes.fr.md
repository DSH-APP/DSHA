# DSHA 0.2.1-rc.3-20261101.2200-dsh0.2.1-rc.5 / build253

- Finitions du terminal : les deux rangées de touches annexes ont un espace entre les touches, les touches de fonction sont plus foncées, les flèches plus grandes, et ^C / ^D / ^Z et les symboles utilisent une police à chasse fixe ; le verrouillage de Ctrl et Alt s'affiche par une barre en bas. La disposition personnalisée et la personnalisation par appui long ne changent pas.
- Barre latérale simplifiée : les sessions PTY et terminal simple partagent une liste triée par création, l'état de chaque session est un petit point après son nom, et Tout terminer est masqué sans session et demande toujours confirmation.
- Nouvelle session devient trois cartes (terminal PTY, terminal simple, DSH CLI), et l'aide présente quatre points clés avec une légende des états.
- Portée de la vérification : tests unitaires locaux du terminal et contrôles statiques uniquement ; aucune installation sur appareil, tests d'interface et Lint confiés à la CI. versionCode porté à 253 (basé sur build252). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
