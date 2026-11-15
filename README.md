# ELMDash

Tableau de bord OBD-II pour téléphone Android **et** montre Wear OS, avec boîtier ELM327 Bluetooth.

## Mettre en ligne et compiler (aucune installation sur ton PC)
1. Mets **tout le contenu de ce dossier** dans ton dépôt GitHub (y compris le dossier caché `.github` et le dossier `keystore`).
2. Onglet **Actions** → « ELMDash build » : le build se lance tout seul à chaque envoi (ou bouton « Run workflow »).
3. Les APK nommés `ELMDash-telephone-v1.N.apk` et `ELMDash-montre-v1.N.apk` sont dans **Releases** (téléchargeables directement depuis le téléphone) et dans les artefacts du build.
4. **Mise à jour sans désinstaller** : la clé de signature est dans `keystore/elmdash.p12`, identique à chaque build. Il suffit d'installer le nouvel APK par-dessus.
5. Montre : `adb connect IP:PORT` puis `adb install -r ELMDash-montre-vX.apk`.

> La clé de signature est dans le dépôt pour que ce soit sans friction (appli personnelle). Si ton dépôt est public et que tu veux une clé privée, remplace `keystore/elmdash.p12` et les mots de passe dans `mobile/build.gradle.kts` et `wear/build.gradle.kts`. Attention : changer de clé oblige à désinstaller une fois.

## Protocoles
- **K-line seul** : ISO 9141-2 / KWP uniquement (équivalent du « ISO » de FORScan).
- **OBD-II ancien (défaut)** : ISO 9141-2, puis détection auto, J1850, KWP, puis CAN. Pour la Ford Probe 1997.
- **OBD-II standard récent** : détection automatique puis CAN ISO 15765-4.
- **FORScan** : CAN 11 bits 500 kbps, adressage physique 7E0, requêtes Mode 22 (PID perso).

## Utilisation
- Cadrans : touche un cadran pour le modifier, **＋** pour en ajouter. 4 types (manomètre, courbe, numérique, barre), plusieurs lectures par cadran.
- Données : Réglages › Données › Scanner le calculateur.
- Simulation complète : Réglages › Boîtier › Ouvrir le simulateur (la montre réagit en direct).
- Montre : molette = changer de page, double-tap haut/bas = luminosité, écran toujours allumé, taille et rotation réglables, connexion directe au boîtier sans téléphone (un ELM327 n'accepte qu'un client : activer « Boîtier utilisé par la montre » côté téléphone).
- Si l'appli plante, le rapport s'affiche au lancement suivant (bouton Copier).
