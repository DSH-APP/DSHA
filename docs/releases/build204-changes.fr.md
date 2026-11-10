# DSHA 0.2.1-rc.3-20261027.1149-dsh0.2.1-rc.3 / build204

- Interface : l'habillage des pages v5 du prototype d'interaction couvre désormais les réglages, les plugins, la configuration d'exécution, l'installation et l'environnement, les autorisations de l'appareil, les données et sauvegardes, les mises à jour, le diagnostic, l'accueil/premier lancement, à propos et la restauration de l'environnement ; titres, groupes, cartes, champs et boutons partagent une même échelle de 26sp et une même palette claire/nuit.
- Terminal : « Nouvelle session », « Terminer la session » et « Revenir à la saisie » réagissent au premier appui ; le sélecteur « Système / Clair / Sombre » est retiré de la barre latérale et le terminal suit le thème de l'application sans recréer les sessions ni rejouer les commandes.
- Finitions : la page d'appairage ADB n'affiche plus de titre en double, la confirmation d'installation par lien passe à l'échelle de 26sp et la page de maintenance de l'environnement (extraction) utilise les cartes et boutons v5.
- Intégration des améliorations de gestion des extensions de Sunset-3090-Q, avec moins de rafraîchissements répétés et conservation des opérations confirmées.
- Correction de la pause permanente de la lecture d’écran après un changement de confirmation globale. Les refus explicites, pauses manuelles et autorisations Android restent applicables.
- Suppression des espaces PC, de l’appairage QR, des connecteurs PC et des relais. Le contrôle du téléphone et les accès local/LAN sont conservés.
- Unification de la zone de sécurité supérieure native et Web, avec barre d’état visible. Les dix langues et la langue du système sont conservées.
- Le lancement sur écran virtuel accepte un generation facultatif et vérifie l’état avant le lancement ; la requête native ne porte que coreInstance et package. Cette vérification préalable ne constitue pas une garantie atomique de génération.
- Cette fusion du code comprend dsh-web-mobile 3.2.2 et DSH rc.3. La validation sur téléphone reste suspendue ; les résultats d’autres branches ou paquets ne valident pas cette compilation.
