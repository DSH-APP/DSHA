# DSHA 0.2.1-rc.3-20261103.0200-dsh0.2.1-rc.5 / build281

- Expérimental : avec ADB activé, rouvrir DSHA après déverrouillage fait d'abord céder la sonde ADB en arrière-plan, ce qui réduit les longues attentes sur « Vérification de l'environnement ». Correctif au mieux : le problème d'origine n'a pas pu être reproduit.
- Portée de la vérification : tests unitaires ciblés, Lint et contrôles de source locaux uniquement ; non vérifié sur appareil. versionCode porté à 281 (basé sur build280). La révision DSHA reste 0.2.1-rc.3 et le DSH intégré reste 0.2.1-rc.5.
