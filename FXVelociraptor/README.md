# 🦖 FX VELOCIRAPTOR - Bot de Trading Android

Application Android native (Kotlin) qui automatise la stratégie **FXVelociraptor**
sur MetaTrader 5, avec détection automatique de danger et coupure du stop loss.

---

## 📱 FONCTIONNALITÉS

- ✅ **Connexion réelle à MT5** via WebSocket (EA Bridge)
- ✅ **Détection automatique** des patterns RBD, DBR, RBR, DBD
- ✅ **Entrées Sniper** ST1, ST2, ST3, ST4
- ✅ **Coupure automatique du SL** si danger détecté
- ✅ **Gestion du risque** automatique (lot size calculé)
- ✅ **Bouton d'urgence** : fermeture immédiate de toutes les positions
- ✅ **Service en arrière-plan** : fonctionne même si l'app est fermée
- ✅ **Redémarrage auto** après reboot du téléphone
- ✅ **Notifications push** sur chaque trade ouvert/danger

---

## 🚀 INSTALLATION EN 3 ÉTAPES

### ÉTAPE 1 — Installer l'EA Bridge sur MetaTrader 5

1. Ouvrez **MetaTrader 5** sur votre PC ou VPS
2. Menu **Fichier → Ouvrir le dossier des données**
3. Allez dans `MQL5/Experts/`
4. Copiez le fichier `ea/FXVelociraptor_Bridge.mq5` dans ce dossier
5. Dans MT5, ouvrez l'**éditeur MetaEditor** (F4)
6. Compilez l'EA (F7)
7. Glissez-déposez l'EA sur un graphique (n'importe quel symbole)
8. ⚠️ Activez **"Autoriser le trading algorithmique"** dans les options MT5
9. ⚠️ Activez **"Autoriser les requêtes externes"** dans les paramètres de l'EA
10. Notez votre **adresse IP** (cherchez "quelle est mon IP" sur Google)

### ÉTAPE 2 — Construire l'APK Android

**Prérequis :**
- Android Studio (téléchargez sur developer.android.com)
- JDK 17+

**Étapes :**
```bash
# 1. Ouvrez le dossier FXVelociraptor dans Android Studio
# 2. Laissez Gradle synchroniser les dépendances
# 3. Menu Build → Build Bundle(s)/APK(s) → Build APK(s)
# 4. L'APK se trouve dans: app/build/outputs/apk/debug/app-debug.apk
```

**Installation sur Android :**
1. Activez **"Sources inconnues"** dans les paramètres Android
2. Transférez l'APK sur votre téléphone (USB ou email)
3. Installez l'APK

### ÉTAPE 3 — Configurer l'application

1. Ouvrez **FX Velociraptor** sur votre téléphone
2. Appuyez sur ⚙️ **Paramètres**
3. Remplissez :
   - **URL WebSocket** : `ws://VOTRE_IP_PC:8080`
     - Si PC local (même WiFi) : `ws://192.168.1.XXX:8080`
     - Si VPS : `ws://IP_DU_VPS:8080`
   - **Login MT5** : votre numéro de compte FBS
   - **Mot de passe MT5** : votre mot de passe de trading
   - **Serveur** : `FBS-Real` ou `FBS-Demo`
4. Appuyez sur **🔄 TESTER LA CONNEXION**
5. Si ✅ OK → Sauvegardez
6. Revenez à l'écran principal → **▶ DÉMARRER LE BOT**

---

## ⚙️ CONFIGURATION DU BOT

| Paramètre | Défaut | Description |
|-----------|--------|-------------|
| Symboles | EURUSD, XAUUSD | Marchés à trader |
| Timeframe | H1 | Timeframe d'analyse |
| Risque/trade | 1% | % du compte risqué par trade |
| R:R Ratio | 2.0 | Risk:Reward minimum |
| Float max | 50$ | Perte flottante max avant coupure auto |
| Max positions | 3 | Positions simultanées max |
| Scan interval | 30s | Fréquence d'analyse |

---

## 🛡️ SYSTÈME DE DÉTECTION DE DANGER

Le bot surveille toutes les positions ouvertes toutes les **10 secondes** :

| Niveau | Condition | Action |
|--------|-----------|--------|
| 🟢 AUCUN | Position en profit ou normale | Rien |
| 🟡 FAIBLE | Accumulation détectée | Maintien (règle FXVelociraptor) |
| 🟠 MOYEN | Structure brisée / mouvement adverse | Notification d'alerte |
| 🔴 ÉLEVÉ | Float > seuil max | Fermeture auto (si activé) |
| 🚨 CRITIQUE | SL très proche / cassure majeure | Fermeture IMMÉDIATE |

---

## 📊 LOGIQUE DE TRADING

### Patterns détectés automatiquement :
- **DBR** (Drop-Base-Rally) → Signal **BUY** 📈
- **RBD** (Rally-Base-Drop) → Signal **SELL** 📉
- **RBR** (Rally-Base-Rally) → Signal **BUY** 📈 (continuation)
- **DBD** (Drop-Base-Drop) → Signal **SELL** 📉 (continuation)

### Entrées Sniper :
- **ST1** : Entrée directe sur la zone (85% confiance)
- **ST2** : Entrée sur le QML - 50% de la zone (75%)
- **ST3** : Entrée sur pullback (70%)
- **ST4** : Entrée sur Order Block (80%)

### Stop Loss automatique :
- SL placé **sous** la Demand Zone pour les BUY
- SL placé **au-dessus** de la Supply Zone pour les SELL
- Buffer de 3 pips (50 cents pour GOLD)

---

## 🌐 CONFIGURATION RÉSEAU

### Option A : PC Local (même réseau WiFi)
```
URL: ws://192.168.1.100:8080
```
➡️ Ouvrez le port 8080 dans votre pare-feu Windows :
`Panneau de configuration → Pare-feu → Règles entrantes → Nouveau → Port 8080`

### Option B : VPS (recommandé pour trading 24/7)
```
URL: ws://123.456.789.000:8080
```
➡️ Louez un VPS Windows chez : Vultr, DigitalOcean, Contabo
➡️ Installez MT5 sur le VPS
➡️ Ouvrez le port 8080 dans le firewall du VPS

---

## ⚠️ AVERTISSEMENT

> **Le trading Forex comporte des risques importants de perte en capital.**
> Cette application est un outil d'automatisation. Les performances passées
> ne garantissent pas les résultats futurs. Commencez TOUJOURS sur un
> **compte démo** avant de trader en réel. Ne risquez jamais de l'argent
> que vous ne pouvez pas vous permettre de perdre.

---

## 📁 STRUCTURE DU PROJET

```
FXVelociraptor/
├── app/src/main/java/com/fxvelociraptor/trading/
│   ├── api/
│   │   └── MT5ApiClient.kt          # Client WebSocket MT5
│   ├── strategy/
│   │   └── FXVelociraptorStrategy.kt # Moteur de stratégie
│   ├── service/
│   │   ├── TradingBotService.kt     # Service background
│   │   └── BootReceiver.kt          # Redémarrage auto
│   ├── ui/
│   │   ├── MainActivity.kt          # Dashboard principal
│   │   ├── SettingsActivity.kt      # Configuration
│   │   └── TradeHistoryActivity.kt  # Historique
│   ├── model/Models.kt              # Modèles de données
│   └── utils/PrefsManager.kt        # Stockage sécurisé
├── ea/
│   └── FXVelociraptor_Bridge.mq5    # EA MT5 (à installer sur PC/VPS)
└── README.md
```
