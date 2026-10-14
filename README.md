# NetSpeedOverlay — v2

Moniteur de débit réseau global (entrant + sortant) et de RAM, sans UI, affiché dans la
**barre d'état** (plus d'overlay sur l'écran).

## Build

```bash
# depuis la racine du projet (ajouter le wrapper si absent : gradle wrapper)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26 / compileSdk 35. Versions AGP/Kotlin à ajuster selon ton Android Studio.

## Première exécution

1. Lancer l'app → Android 13+ demande l'autorisation des notifications.
2. Accorder → modale « Le débit réseau et la RAM sont maintenant affichés dans la barre d'état. » → OK → l'activity se termine.
3. Deux icônes apparaissent dans la barre d'état :
   - **débit** : total ↓+↑ sur deux lignes (`340` / `KB/s`) ; le détail ↓ / ↑ est dans le panneau ;
   - **RAM** : pourcentage utilisé (`41%` / `RAM`) ; utilisé / total / disponible dans le panneau.

## Fonctionnement : icônes de notification

Une app tierce ne peut pas dessiner directement dans la barre d'état. La seule voie
supportée est la petite icône d'une notification, générée ici à chaque seconde
(`Icon.createWithBitmap`). Conséquences :

- `POST_NOTIFICATIONS` est **obligatoire** sur Android 13+ (sans elle, rien ne s'affiche).
- Canaux en `IMPORTANCE_LOW` : `MIN` masque l'icône de la barre d'état. Pas de son ni de heads-up.
- Deux canaux, « Débit réseau » et « Mémoire RAM » : désactiver l'un dans les réglages de
  notification de l'app masque l'icône correspondante.
- Le système ne garde que le canal alpha de l'icône et la teinte selon le thème.
- Certains OEM (MIUI/HyperOS, One UI selon réglages) limitent le nombre d'icônes de
  notification en barre d'état ou les remplacent par un point : à régler côté système.
- Écran éteint : l'échantillonnage continue mais aucune notification n'est postée.

Le canal v1 (`netspeed_fgs`, `IMPORTANCE_MIN`) est supprimé au démarrage : l'importance
d'un canal existant ne peut pas être relevée par l'app.

## Survie du process

- `START_STICKY` + `BootReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED).
- Sur les OEM agressifs (Xiaomi/MIUI, Huawei, Oppo, Samsung) : exclure l'app de
  l'optimisation batterie et l'épingler dans les récents, sinon le service sera tué.

```bash
adb shell dumpsys deviceidle whitelist +mg.acchadu.netspeed
```

## Source des données

`TrafficStats.getTotalRxBytes()` / `getTotalTxBytes()` : compteurs cumulés depuis le boot,
toutes interfaces confondues, loopback exclu. Échantillonnage à 1 Hz, delta / Δt via
`SystemClock.elapsedRealtime()`. Ces compteurs restent accessibles sans permission
(contrairement aux compteurs per-UID, restreints depuis Android 7).

RAM : `ActivityManager.getMemoryInfo()` → utilisé = `totalMem - availMem`. `availMem`
inclut le cache récupérable, donc la valeur est proche de « MemAvailable » de
`/proc/meminfo`, pas de « MemFree ».

Limites connues : trafic VPN compté deux fois sur certains devices.
