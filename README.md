# NetSpeedOverlay — v1

Moniteur de débit réseau global (entrant + sortant), sans UI, rendu dans une fenêtre overlay.

## Build

```bash
# depuis la racine du projet (ajouter le wrapper si absent : gradle wrapper)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26 / compileSdk 35. Versions AGP/Kotlin à ajuster selon ton Android Studio.

## Première exécution

1. Lancer l'app → elle ouvre directement le réglage *Affichage par-dessus les autres applications*.
2. Accorder → retour auto → modale « Le trafic entrant et sortant est maintenant affiché en temps réel. » → OK → l'activity se termine.
3. L'overlay apparaît en haut à gauche : `↓ 1.2M  ↑ 340K`.

## Supprimer la notification (Android 13+)

La notification du foreground service est soumise à `POST_NOTIFICATIONS` depuis Android 13.
Le code ne demande **jamais** cette permission, donc elle reste refusée par défaut et rien
n'apparaît dans le panneau. Si ton OEM l'a pré-accordée :

```bash
adb shell pm revoke mg.acchadu.netspeed android.permission.POST_NOTIFICATIONS
```

Sur Android 8–12, la notification ne peut pas être supprimée par l'app. Le canal est en
`IMPORTANCE_MIN` + `VISIBILITY_SECRET`, donc elle se replie en bas de la section silencieuse.
Pour la masquer complètement, désactiver le canal « Moniteur de débit » dans les réglages
de notification de l'app — le service continue de tourner.

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

Limites connues : trafic VPN compté deux fois sur certains devices ; overlay masqué
au-dessus des écrans marqués `FLAG_SECURE` et de certains plein-écrans.
