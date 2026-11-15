# DSHA 0.2.1-rc.3-20261101.1700-dsh0.2.1-rc.5 / build248

- Les cartes de plugins partagent désormais une même carte pour la liste, le volet gauche tablette et le volet de détail. La source est un badge, la version sous le titre, l'état de configuration et le Web actuel tiennent sur une ligne, et version / emplacement / source se replient en tableau compact.
- Plus d'actions utilise une feuille en carte : sur téléphone elle s'ancre au-dessus de la navigation flottante ; sur tablette en portrait et paysage elle est centrée et limitée en largeur, sans s'étirer d'un bord à l'autre.
- Portée de la vérification : tests unitaires locaux pour ce changement ; le paquet de test a été installé par-dessus sur un OnePlus PJD110 (360dp, portrait) et une tablette Lenovo TB321FU (portrait et paysage). Autres appareils, langues, mode sombre, écrans courts et police 1,3× non vérifiés. versionCode porté à 248. La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
