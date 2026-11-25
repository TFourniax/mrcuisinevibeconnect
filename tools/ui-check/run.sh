#!/usr/bin/env bash
# Vérification de l'interface sur l'émulateur Android 6 (lancé par .github/workflows/ui-check.yml) :
# écran 1024×600 comme le robot, réseau coupé (rien ne part vers Foyer ni GitHub), données de
# démonstration (tools/ui-check/seed.py), puis le parcours (tools/ui-check/tour.py).
set -euo pipefail
OUT=${1:-ui-check}
APK=app/build/outputs/apk/debug/app-debug.apk
SEED=$(mktemp -d)

adb wait-for-device
adb shell wm size 1024x600
adb shell wm density 160
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc wifi disable || true
adb shell svc data disable || true

adb install -r "$APK"
# Premier lancement : Toque crée ses dossiers, puis on l'arrête pour poser les données.
adb shell am start -n app.toque/.ui.MainActivity
sleep 6
adb shell am force-stop app.toque

python3 tools/ui-check/seed.py "$SEED"
adb push "$SEED/toque.xml" /data/local/tmp/toque.xml
adb push "$SEED/recettes.db" /data/local/tmp/recettes.db
adb shell "run-as app.toque sh -c 'mkdir -p shared_prefs databases && cat /data/local/tmp/toque.xml > shared_prefs/toque.xml && cat /data/local/tmp/recettes.db > databases/recettes.db && rm -f databases/recettes.db-journal'"
adb logcat -c
adb shell am start -n app.toque/.ui.MainActivity
python3 tools/ui-check/tour.py "$OUT"
