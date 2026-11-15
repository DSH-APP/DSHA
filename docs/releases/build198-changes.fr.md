# DSHA 0.2.1-rc.3-20261025.2100-dsh0.2.1-rc.3 / build198

- Intégration des améliorations de gestion des extensions de Sunset-3090-Q, avec moins de rafraîchissements répétés et conservation des opérations confirmées.
- Correction de la pause permanente de la lecture d’écran après un changement de confirmation globale. Les refus explicites, pauses manuelles et autorisations Android restent applicables.
- Suppression des espaces PC, de l’appairage QR, des connecteurs PC et des relais. Le contrôle du téléphone et les accès local/LAN sont conservés.
- Unification de la zone de sécurité supérieure native et Web, avec barre d’état visible. Les dix langues et la langue du système sont conservées.

## Notes historiques de la branche de test Sunset distincte (pas une validation de la compilation fusionnée)

### DSHA 0.2.1-rc.3-20261026.0644-dsh0.2.1-rc.3 / build198

- Le code de version passe à 198. La boule flottante du terminal et le menu de l'atelier ouvrent désormais la barre latérale des sessions : le mode minimal et les ateliers étroits utilisent un tiroir, tandis que les ateliers d'au moins 840dp gardent la barre latérale intégrée. La barre regroupe création, changement, renommage, fin vérifiée, accueil, affichage, clavier, collage, focus de saisie et aide.
- La compilation de test autonome Sunset utilise désormais dsh.android.client.SunsetRNE2 et peut coexister avec l'application de production dsh.android.client et la compilation de test SunsetRNE précédente.
- Cette compilation n'a pas encore été compilée pour Android, testée unitairement, passée au Lint, empaquetée en APK ni installée et validée sur appareil.
