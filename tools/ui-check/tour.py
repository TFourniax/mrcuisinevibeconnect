#!/usr/bin/env python3
"""
Parcours de l'interface sur un émulateur Android 6 (1024×600, comme le Monsieur Cuisine
Connect) : chaque écran en clair puis en sombre, un programme lancé, mis en pause et arrêté,
une recette guidée, la cloche, le bouton rotatif, l'écran de veille. Captures dans OUT ; échoue
si Toque plante (« FATAL EXCEPTION » dans le journal) ou si un écran attendu n'apparaît pas.

    python3 tour.py OUT
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
PKG = "app.toque"
problems = []


def adb(*args, check=False, capture=False):
    r = subprocess.run(["adb", *args], capture_output=True)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} : {r.stderr.decode(errors='replace')}")
    return r.stdout if capture else r


def sh(cmd):
    return adb("shell", cmd, capture=True).decode(errors="replace")


def wait(s=1.5):
    time.sleep(s)


last_xml = [""]


def shot(name):
    png = adb("exec-out", "screencap", "-p", capture=True)
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(png)
    print("capture", name, len(png), "octets", flush=True)
    # L'arbre des vues de la capture (textes, positions) : pour comprendre un écran sans l'image.
    if dump() is not None:
        os.makedirs(os.path.join(OUT, "vues"), exist_ok=True)
        with open(os.path.join(OUT, "vues", name + ".xml"), "w", encoding="utf-8") as f:
            f.write(last_xml[0])


def dump():
    out = ""
    for _ in range(4):
        sh("rm -f /sdcard/ui.xml")
        out = sh("uiautomator dump /sdcard/ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", capture=True).decode(errors="replace")
        if xml.strip().startswith("<?xml"):
            try:
                root = ET.fromstring(xml)
                last_xml[0] = xml
                return root
            except ET.ParseError:
                pass
        wait(1)
    print("!! arbre des vues illisible :", out.strip()[:200], flush=True)
    return None


def center(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text=None, desc=None, prefix=False):
    root = dump()
    if root is None:
        return None
    best = None
    for n in root.iter("node"):
        t = n.get("text", "")
        d = n.get("content-desc", "")
        if text is not None and (t == text or (prefix and t.startswith(text))):
            best = n
            break
        if desc is not None and (d == desc or (prefix and d.startswith(desc))):
            best = n
            break
    return center(best) if best is not None else None


def tap_xy(x, y, pause=1.5):
    sh(f"input tap {x} {y}")
    wait(pause)


def tap(text=None, desc=None, prefix=False, fallback=None, pause=1.8, required=True):
    p = find(text, desc, prefix)
    if p is None:
        p = fallback
    if p is None:
        msg = f"introuvable à l'écran : {text or desc}"
        print("!!", msg, flush=True)
        if required:
            problems.append(msg)
        return False
    tap_xy(*p, pause=pause)
    return True


def key(code, times=1, pause=0.6):
    for _ in range(times):
        sh(f"input keyevent {code}")
        wait(pause)


def expect(text, prefix=False):
    if find(text=text, prefix=prefix) is None:
        problems.append(f"attendu à l'écran : {text}")
        print("!! attendu :", text, flush=True)


# Barre du bas : cinq sections, à hauteur fixe (1024×600, échelle 1).
NAV = {"Accueil": (125, 555), "Recettes": (318, 555), "Cuisson": (512, 555), "Programmes": (705, 555), "Balance": (899, 555)}


def nav(name):
    # Par l'étiquette de l'onglet : le mot seul existe aussi ailleurs (la carte « Balance » de Cuisson).
    tap(desc=name, fallback=NAV[name])


def crashed():
    log = sh("logcat -d -v brief AndroidRuntime:E *:S")
    return "FATAL EXCEPTION" in log, log


# ---------------------------------------------------------------- molette
# Les touches du bouton rotatif du Monsieur Cuisine Connect (relevées sur un SKMC 1200 B2) :
# F7 tourner à gauche, F6 à droite, F5 appuyer. La version de test note au journal (ToqueKnob)
# chaque position du cadre, chaque appui, chaque réglage.
KNOB_LEFT, KNOB_RIGHT, KNOB_PRESS = 137, 136, 135
knob_report = []


def turn(n, code=KNOB_RIGHT, slow=False):
    # Par paquets (rapide) ou cran par cran, à vitesse de main (les listes défilent entre deux crans).
    while n > 0:
        k = 1 if slow else min(n, 8)
        sh("input keyevent " + " ".join([str(code)] * k))
        n -= k
        wait(0.45 if slow else 0.3)


def knob_events():
    out = []
    for line in sh("logcat -d -v brief -s ToqueKnob:I").splitlines():
        if "ToqueKnob" not in line or "|" not in line:
            continue
        msg = line.split("): ", 1)[-1]
        parts = msg.split("|")
        if len(parts) != 3:
            continue
        try:
            box = tuple(int(x) for x in parts[2].split(","))
        except ValueError:
            continue
        out.append((parts[0], parts[1], box))
    return out


def bounds(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def node_label(n):
    if n.get("content-desc"):
        return n.get("content-desc")
    for c in n.iter("node"):
        if c.get("text"):
            return c.get("text")
    return n.get("class", "").split(".")[-1]


def close(a, b, tol=4):
    return all(abs(x - y) <= tol for x, y in zip(a, b))


def knob_audit(name, steps=45, slow=False):
    """Tourne la molette jusqu'au bout dans les deux sens et compare avec tout ce qui se touche."""
    sh("logcat -c")
    turn(steps, KNOB_RIGHT, slow)
    right = [e for e in knob_events() if e[0] == "focus"]
    turn(steps * 2, KNOB_LEFT, slow)
    wait(1)
    every = [e for e in knob_events() if e[0] == "focus"]
    left = every[len(right):]
    # Ordre de lecture : le passage vers la gauche, à l'envers (du premier au dernier).
    order = []
    for _, lab, box in list(reversed(left)) + right:
        if not any(close(box, b) and lab == l for l, b in order):
            order.append((lab, box))
    lines = ["== %s : %d éléments au bouton" % (name, len(order))]
    lines += ["   %2d. %s" % (i + 1, lab) for i, (lab, _) in enumerate(order)]
    root = dump()
    if root is None:
        lines.append("   (arbre des vues illisible : comparaison impossible)")
    else:
        missed = []
        for n in root.iter("node"):
            if n.get("clickable") != "true" or n.get("enabled") != "true" or n.get("package") != PKG:
                continue
            b = bounds(n)
            if not b or b[2] <= b[0] or b[3] <= b[1]:
                continue
            lab = node_label(n)
            if any(close(b, box) for _, box in order) or any(lab == l for l, _ in order):
                continue
            missed.append("%s %s" % (lab, b))
        lines.append("   touchable jamais atteint à la molette : " + ("; ".join(missed) if missed else "aucun"))
    knob_report.extend(lines)
    print("\n".join(lines), flush=True)
    return [(lab, box) for _, lab, box in right]


def texts_in(root, box):
    if root is None:
        return []
    out = []
    for n in root.iter("node"):
        b = bounds(n)
        if b and n.get("text") and b[0] >= box[0] - 2 and b[1] >= box[1] - 2 and b[2] <= box[2] + 2 and b[3] <= box[3] + 2:
            out.append(n.get("text"))
    return out


def knob_adjust(screen, path, match, turns=2):
    """Écran rouvert : tourner jusqu'à la valeur, appuyer, tourner, appuyer. La valeur doit changer."""
    idx = next((i for i, (lab, _) in enumerate(path) if match(lab)), None)
    if idx is None:
        msg = "molette : valeur à régler introuvable sur %s" % screen
        problems.append(msg)
        knob_report.append("== " + msg)
        return
    box = path[idx][1]
    nav("Accueil")
    nav(screen)
    before = texts_in(dump(), box)
    turn(idx + 1)  # le premier tour pose le cadre sur l'élément de départ
    key(KNOB_PRESS)
    turn(turns)
    key(KNOB_PRESS)
    wait(1)
    after = texts_in(dump(), box)
    line = "== réglage à la molette, %s › %s : %s → %s" % (screen, path[idx][0], " ".join(before), " ".join(after))
    if before == after:
        problems.append("molette : la valeur n'a pas changé (%s › %s)" % (screen, path[idx][0]))
        line += "  !! inchangé"
    knob_report.append(line)
    print(line, flush=True)


def knob_press(screen, path, match, expect_text):
    """Écran rouvert : tourner jusqu'à l'élément, appuyer ; [expect_text] doit apparaître."""
    idx = next((i for i, (lab, _) in enumerate(path) if match(lab)), None)
    if idx is None:
        problems.append("molette : élément introuvable sur %s" % screen)
        return
    nav("Accueil")
    if screen != "Accueil":
        nav(screen)
    turn(idx + 1)
    key(KNOB_PRESS, pause=2)
    ok = find(text=expect_text) is not None
    line = "== appui à la molette, %s › %s : %s" % (screen, path[idx][0], "ouvre « %s »" % expect_text if ok else "!! rien ne s'ouvre")
    if not ok:
        problems.append("molette : l'appui sur %s › %s n'a rien ouvert" % (screen, path[idx][0]))
    knob_report.append(line)
    print(line, flush=True)


def knob_tour():
    nav("Accueil")
    home = knob_audit("Accueil")
    knob_press("Accueil", home, lambda l: l == "Recettes", "Toutes")
    nav("Accueil")
    tap(desc="À savoir", prefix=True, fallback=(940, 29))
    knob_audit("À savoir (fenêtre)", steps=20)
    key(4)
    nav("Recettes")
    tap(text="Toutes", required=False)
    tap(text="Crêpes de la Chandeleur")
    knob_audit("Fiche recette")
    key(4)
    nav("Recettes")
    knob_audit("Recettes", steps=30, slow=True)
    nav("Programmes")
    progs = knob_audit("Programmes")
    # La durée du programme choisi (« 1 min 30 s », « 9 min »…), réglable au bouton.
    knob_adjust("Programmes", progs, lambda l: re.match(r"^\d+ min( \d+ s)?$", l) is not None)
    nav("Cuisson")
    manual = knob_audit("Cuisson manuelle")
    knob_adjust("Cuisson", manual, lambda l: l == "Durée")
    nav("Balance")
    knob_audit("Balance", steps=20)
    tap(desc="Réglages", fallback=(988, 29))
    knob_audit("Réglages", steps=90)
    nav("Accueil")
    cook_or_resume()
    knob_audit("Recette guidée")
    tap(desc="Retour", fallback=(36, 29))
    with open(os.path.join(OUT, "molette.txt"), "w") as f:
        f.write("\n".join(knob_report) + "\n")


def cook_or_resume():
    """La grande carte de l'accueil : « Cuisiner » le repas prévu, ou « Reprendre » la recette entamée."""
    if not tap(text="Cuisiner", required=False):
        tap(text="Reprendre")


def flip(name):
    """Lune ou soleil : l'écran courant est refait dans l'autre thème."""
    if not tap(desc="Passer en thème sombre", required=False, pause=3):
        tap(desc="Passer en thème clair", fallback=(892, 29), pause=3)
    shot(name)


def theme_flips():
    nav("Recettes")
    tap(text="Toutes", required=False)
    tap(desc="Chercher", fallback=(400, 89), required=False)
    sh("input text carotte")
    key(66)  # Entrée : ferme le clavier
    wait(2)
    flip("24-theme-recettes-recherche")
    expect("Soupe de carottes au cumin")
    tap(text="Soupe de carottes au cumin")
    wait(2)
    flip("25-theme-fiche-lidl")
    expect("Soupe de carottes au cumin")
    key(4)
    nav("Programmes")
    tap(text="Œufs")
    flip("26-theme-programmes")
    nav("Cuisson")
    flip("27-theme-cuisson")
    nav("Accueil")


def screens(theme):
    """Tous les écrans principaux dans le thème courant."""
    nav("Accueil")
    shot(f"01-accueil-{theme}")
    tap(desc="À savoir", prefix=True, fallback=(940, 29))
    shot(f"02-a-savoir-{theme}")
    key(4)  # retour : ferme le panneau
    nav("Recettes")
    expect("Crêpes de la Chandeleur")
    shot(f"03-recettes-{theme}")
    tap(text="Crêpes de la Chandeleur")
    shot(f"04-fiche-recette-{theme}")
    nav("Programmes")
    shot(f"05-programmes-{theme}")
    tap(text="Œufs")
    shot(f"06-programmes-oeufs-{theme}")
    nav("Cuisson")
    shot(f"07-cuisson-manuelle-{theme}")
    nav("Balance")
    shot(f"08-balance-{theme}")
    tap(desc="Réglages", fallback=(988, 29))
    shot(f"09-reglages-{theme}")
    nav("Accueil")


def main():
    wait(8)
    screens("clair")

    # Une recette guidée : Cuisiner depuis le repas prévu, Lancer, Pause, Arrêter. Pendant une
    # cuisson, l'arbre des vues se lit mal (le temps change chaque seconde) : positions de secours
    # relevées sur les captures 1024×600.
    nav("Accueil")
    cook_or_resume()
    shot("10-recette-guidee-clair")
    tap(text="Lancer", pause=4)
    shot("11-recette-guidee-en-cours-clair")
    nav_ok = tap(desc="Retour", fallback=(36, 29))
    if nav_ok:
        nav("Accueil")
    wait(2)
    shot("12-accueil-en-cours-clair")
    knob_audit("Accueil pendant une cuisson", steps=25)
    tap(text="Pause", fallback=(113, 460), pause=2.5)
    shot("13-accueil-en-pause-clair")
    tap(text="Arrêter", fallback=(257, 460), pause=2.5)
    shot("14-accueil-arrete-clair")
    tap(text="OK", required=False)

    # Un programme : Prélaver depuis Programmes, puis la barre « en cours » ailleurs.
    nav("Programmes")
    tap(text="Prélaver")
    tap(text="Démarrer", pause=2)
    # Bol encore chaud après la recette : Toque demande confirmation avant de mixer (sécurité).
    if find(text="Mixer quand même") is not None:
        knob_audit("Fenêtre « Contenu chaud »", steps=8)
    tap(text="Mixer quand même", required=False, pause=4)
    shot("15-programme-en-cours-clair")
    nav("Recettes")
    shot("16-barre-en-cours-recettes-clair")
    tap(desc="Arrêter", fallback=(960, 486), pause=2.5)
    tap(text="OK", required=False)

    # La molette, écran par écran : tout ce qui se touche doit se rejoindre au bouton.
    knob_tour()

    # Bouton rotatif : le cadre se pose et avance.
    nav("Accueil")
    key("DPAD_RIGHT", 3)
    shot("17-bouton-rotatif-clair")
    key("DPAD_CENTER")
    wait(1)

    # Thème sombre par la lune.
    nav("Accueil")
    tap(desc="Passer en thème sombre", fallback=(892, 29), pause=3)
    screens("sombre")
    nav("Accueil")
    cook_or_resume()
    shot("18-recette-guidee-sombre")
    tap(desc="Retour", fallback=(36, 29))
    nav("Accueil")

    # Écran de veille (Réglages › Voir l'écran de veille), puis un toucher le ferme.
    tap(desc="Réglages", fallback=(988, 29))
    for _ in range(6):
        if tap(text="Voir l'écran de veille", required=False, pause=3):
            break
        sh("input swipe 700 450 700 150 300")
        wait(1)
    shot("19-ecran-de-veille")
    tap_xy(512, 300, pause=2)
    shot("20-apres-veille")

    # L'outil de l'anneau lumineux (le bloc est juste sous l'écran de veille dans les Réglages).
    if tap(text="Trouver l'anneau", required=False, pause=3):
        shot("21-anneau-lumineux")
        tap(desc="Retour", fallback=(36, 29))

    # Le panneau vidéo d'une recette (hors réseau ici : il doit s'ouvrir et se fermer sans planter).
    nav("Recettes")
    tap(text="Crêpes de la Chandeleur")
    tap(text="Revoir la vidéo", pause=4)
    shot("22-video")
    key(4)
    wait(2)
    shot("23-apres-video")

    # Changer de thème sur des écrans qui gardent un état (la lune refait toute l'interface) :
    # Recettes avec une recherche tapée, une fiche Lidl (avis en ligne), Programmes, Cuisson.
    theme_flips()

    bad, log = crashed()
    with open(os.path.join(OUT, "logcat-crash.txt"), "w") as f:
        f.write(log)
    with open(os.path.join(OUT, "problemes.txt"), "w") as f:
        f.write("\n".join(problems) + ("\n" if problems else ""))
    if bad:
        print(log)
        raise SystemExit("Toque a planté pendant le parcours")
    if problems:
        print("\n".join(problems))
        raise SystemExit(f"{len(problems)} élément(s) attendu(s) introuvable(s)")
    print("parcours terminé sans plantage")


if __name__ == "__main__":
    main()
