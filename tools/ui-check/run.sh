#!/usr/bin/env bash
# Vérification de l'interface sur l'émulateur Android 6 (lancé par .github/workflows/ui-check.yml) :
# écran 1024×600 comme le robot, réseau coupé (rien ne part vers Foyer ni GitHub), données de
# démonstration (tools/ui-check/seed.py), puis le parcours (tools/ui-check/tour.py).
set -euo pipefail
OUT=${1:-ui-check}
APK=app/build/outputs/apk/debug/app-debug.apk
SEED=$(mktemp -d)

adb wait-for-device
# L'écran fait déjà 1024×600 (option -skin de l'émulateur) : pas de « wm size », qui le mettrait
# en boîte aux lettres et décalerait les touchers.
adb shell wm size reset || true
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

python3 tools/ui-check/seed.py "$SEED"
adb push "$SEED/toque.xml" /data/local/tmp/toque.xml
adb push "$SEED/recettes.db" /data/local/tmp/recettes.db

# Pose les données (Toque arrêté), relance, et vérifie que Toque les a bien gardées. Une copie
# de secours (.bak) laissée par un arrêt en pleine écriture remplacerait nos réglages : on l'efface.
seeded=no
for attempt in 1 2 3; do
  adb shell am force-stop app.toque
  sleep 1
  adb shell "run-as app.toque sh -c 'mkdir -p shared_prefs databases && rm -f shared_prefs/toque.xml.bak databases/recettes.db-journal && cat /data/local/tmp/toque.xml > shared_prefs/toque.xml && cat /data/local/tmp/recettes.db > databases/recettes.db'"
  adb logcat -c
  adb shell am start -n app.toque/.ui.MainActivity
  sleep 6
  echo "--- essai $attempt : fichiers de Toque après relance ---"
  adb shell "run-as app.toque ls -l shared_prefs databases" || true
  adb shell "run-as app.toque cat shared_prefs/toque.xml" > "$SEED/after.xml" || true
  if grep -q 'name="foyerHousehold"' "$SEED/after.xml"; then
    seeded=yes
    break
  fi
  echo "!! réglages de démonstration perdus (essai $attempt) ; Toque a écrit :"
  cat "$SEED/after.xml"
  adb logcat -d -v brief | grep -i -E "SharedPreferences|toque" | tail -40 || true
done
echo "données de démonstration posées : $seeded"
python3 tools/ui-check/tour.py "$OUT"
