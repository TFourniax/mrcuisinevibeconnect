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
    tap(text="Mixer quand même", required=False, pause=4)
    shot("15-programme-en-cours-clair")
    nav("Recettes")
    shot("16-barre-en-cours-recettes-clair")
    tap(desc="Arrêter", fallback=(960, 486), pause=2.5)
    tap(text="OK", required=False)

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
