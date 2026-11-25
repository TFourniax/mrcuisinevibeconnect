#!/usr/bin/env bash
# Vérification de l'interface sur l'émulateur Android 6 (lancé par .github/workflows/ui-check.yml) :
# écran 1024×600 comme le robot, réseau coupé (rien ne part vers Foyer ni GitHub), données de
# démonstration (tools/ui-check/seed.py), puis le parcours (tools/ui-check/tour.py) ; enfin, réseau
# rallumé, une seule question à la base de Foyer (statut des agents) depuis Android 6.
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

python3 tools/ui-check/seed.py "$SEED"
adb push "$SEED/toque.xml" /data/local/tmp/toque.xml
adb push "$SEED/recettes.db" /data/local/tmp/recettes.db

# Toque est aussi l'écran d'accueil du système (il remplace le lanceur du robot) : arrêté de force,
# Android le relance aussitôt, avec ses anciens réglages en mémoire, qu'il réécrit ensuite
# par-dessus les nôtres. Les données sont donc posées avant le premier lancement, puis, s'il faut
# recommencer, paquet désactivé le temps de la copie (rien ne peut le relancer).
copy_seed() {
  adb shell "run-as app.toque sh -c 'mkdir -p shared_prefs databases && rm -f shared_prefs/toque.xml.bak databases/recettes.db-journal && cat /data/local/tmp/toque.xml > shared_prefs/toque.xml && cat /data/local/tmp/recettes.db > databases/recettes.db'"
}
seeded=no
for attempt in 1 2 3; do
  if [ "$attempt" -gt 1 ]; then
    adb shell pm disable app.toque || adb shell am force-stop app.toque
    sleep 1
    copy_seed
    adb shell pm enable app.toque || true
  else
    copy_seed
  fi
  adb logcat -c
  adb shell am start -n app.toque/.ui.MainActivity
  sleep 8
  echo "--- essai $attempt : fichiers de Toque après lancement ---"
  adb shell "run-as app.toque ls -l shared_prefs databases" || true
  adb shell "run-as app.toque cat shared_prefs/toque.xml" > "$SEED/after.xml" || true
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null || true
  adb exec-out cat /sdcard/ui.xml > "$SEED/ui.xml" || true
  if grep -q 'name="foyerHousehold"' "$SEED/after.xml" && grep -q 'text="Accueil"' "$SEED/ui.xml"; then
    seeded=yes
    break
  fi
  echo "!! données de démonstration pas prises (essai $attempt) ; réglages écrits par Toque :"
  cat "$SEED/after.xml"
  grep -o 'text="[^"]*"' "$SEED/ui.xml" | head -20 || true
done
echo "données de démonstration posées : $seeded"
python3 tools/ui-check/tour.py "$OUT"

# Après le parcours (hors réseau), le réseau revient le temps d'une question à la base de Foyer
# avec un faux secret (version de test, journal ToqueAgents) : une réponse 200 prouve qu'Android 6
# accepte le certificat de la base avec les racines embarquées.
adb shell svc wifi enable || true
adb shell svc data enable || true
sleep 10
line=""
for attempt in 1 2 3; do
  adb shell am start -n app.toque/.ui.MainActivity --ez app.toque.PROBE_AGENTS true > /dev/null
  for _ in $(seq 1 15); do
    line=$(adb logcat -d -v brief -s ToqueAgents:I | grep "probe|" | tail -1 || true)
    [ -n "$line" ] && break
    sleep 2
  done
  case "$line" in *"probe|200|"*) break ;; esac
  # Réseau pas encore prêt : on attend un peu et on redemande.
  adb logcat -c
  sleep 10
done
echo "${line:-pas de réponse}" | tee "$OUT/connexion-foyer.txt"
case "$line" in
  *"probe|200|"*) echo "Android 6 joint la base de Foyer en https" ;;
  *) echo "Android 6 ne joint pas la base de Foyer"; exit 1 ;;
esac
