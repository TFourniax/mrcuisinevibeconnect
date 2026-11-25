#!/usr/bin/env python3
"""
Données de démonstration pour la vérification de l'interface (émulateur Android 6, CI) :
des recettes du foyer, des repas prévus dans Foyer, des messages « À savoir », une mise à jour
proposée. Écrit dans OUT : toque.xml (réglages) et recettes.db (base des recettes, même schéma
que RecipeRepo). Rien n'est envoyé nulle part : tout reste sur l'émulateur.

    python3 seed.py OUT
"""
import json
import os
import sqlite3
import sys
import time
from datetime import datetime, timedelta, timezone
from xml.sax.saxutils import escape

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
now = datetime.now(timezone.utc)
now_ms = int(time.time() * 1000)


def recipe(rid, title, minutes, servings, tags, steps, ingredients=None, link="", video="", category="",
           source="foyer", rating=0.0, rating_count=0):
    r = {
        "id": rid, "title": title, "category": category, "tags": tags, "servings": servings,
        "prepMinutes": 10, "totalMinutes": minutes, "difficulty": 1, "source": source, "image": "",
        "favorite": False, "notes": "", "author": "", "rating": rating, "ratingCount": rating_count,
        "ingredients": ingredients or [], "steps": steps,
    }
    if link:
        r["link"] = link
    if video:
        r["video"] = video
    return r


def machine(program="manuel", seconds=0, speed=0, temp=0, reverse=False):
    return {"program": program, "seconds": seconds, "speed": speed, "tempC": temp, "reverse": reverse,
            "accessory": "blade", "pulses": 0}


recipes = [
    recipe("foyer-crepes", "Crêpes de la Chandeleur", 25, 4, ["Favori"], [
        {"text": "Verse le lait dans le bol, ajoute le beurre et le sucre, puis lance la chauffe.",
         "weigh": {"label": "lait", "grams": 500}, "machine": machine(seconds=360, speed=2, temp=90, reverse=True)},
        {"text": "Ajoute la farine et les œufs.", "weigh": {"label": "farine", "grams": 250}},
        {"text": "Mixe la pâte.", "machine": machine(seconds=30, speed=5)},
        {"text": "Laisse reposer la pâte 30 minutes, sans le robot."},
        {"text": "Fais cuire les crêpes dans une poêle chaude beurrée."},
    ], [{"group": "", "items": [
        {"qty": "500", "unit": "ml", "name": "lait", "note": ""},
        {"qty": "250", "unit": "g", "name": "farine", "note": ""},
        {"qty": "4", "unit": "", "name": "œufs", "note": ""},
        {"qty": "50", "unit": "g", "name": "beurre", "note": "fondu"},
    ]}], link="https://example.com/crepes", video="https://example.com/crepes.mp4"),
    recipe("foyer-dahl", "Dahl de lentilles corail", 35, 4, ["À tester"], [
        {"text": "Fais revenir l'oignon et les épices.", "machine": machine("rissoler", seconds=300)},
        {"text": "Ajoute les lentilles et le lait de coco.", "machine": machine(seconds=1200, speed=1, temp=100, reverse=True)},
    ], category="Plat"),
    recipe("foyer-tarte", "Tarte poireaux et chèvre", 50, 6, ["À tester"], [
        {"text": "Émince les poireaux.", "machine": machine(seconds=5, speed=5)},
        {"text": "Fais-les fondre.", "machine": machine(seconds=900, speed=1, temp=100, reverse=True)},
    ]),
    recipe("foyer-curry", "Poulet au curry doux", 40, 4, ["À tester"], [
        {"text": "Fais dorer le poulet.", "machine": machine("rissoler", seconds=420)},
    ]),
    recipe("foyer-risotto", "Risotto aux champignons", 30, 4, [], [
        {"text": "Nacre le riz.", "machine": machine("rissoler", seconds=180)},
        {"text": "Mouille au bouillon et laisse cuire.", "machine": machine(seconds=1080, speed=1, temp=100, reverse=True)},
    ]),
    recipe("foyer-veloute", "Velouté de potiron", 40, 4, [], [
        {"text": "Fais cuire le potiron.", "machine": machine(seconds=1500, speed=1, temp=100)},
        {"text": "Mixe.", "machine": machine(seconds=60, speed=8)},
    ], category="Soupe"),
    recipe("foyer-pizza", "Pizza maison", 90, 4, [], [
        {"text": "Pétris la pâte.", "machine": machine("petrir", seconds=120)},
    ]),
    recipe("foyer-smoothie", "Smoothie fraise-banane", 5, 2, [], [
        {"text": "Mixe le tout.", "machine": machine(seconds=60, speed=10)},
    ], category="Boisson"),
    # Une recette du site Lidl (identifiant « web- ») : sa fiche a une rubrique d'avis en ligne.
    recipe("web-12345", "Soupe de carottes au cumin", 30, 4, [], [
        {"text": "Mixe les carottes.", "machine": machine(seconds=10, speed=5)},
        {"text": "Fais cuire.", "machine": machine(seconds=1200, speed=1, temp=100)},
    ], category="Soupe", source="imported", rating=4.5, rating_count=12),
]

db_path = os.path.join(out, "recettes.db")
if os.path.exists(db_path):
    os.remove(db_path)
db = sqlite3.connect(db_path)
db.execute("CREATE TABLE recipes (id TEXT PRIMARY KEY, title TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL, origin TEXT NOT NULL DEFAULT 'lidl')")
db.execute("CREATE TABLE favorites (id TEXT PRIMARY KEY)")
for r in recipes:
    origin = "foyer" if r["source"] == "foyer" else "lidl"
    db.execute("INSERT INTO recipes VALUES (?, ?, ?, ?, ?)", (r["id"], r["title"], json.dumps(r, ensure_ascii=False), now_ms, origin))
db.execute("INSERT INTO favorites VALUES ('foyer-crepes')")
db.execute("PRAGMA user_version = 2")
db.commit()
db.close()


def day(offset):
    return (now + timedelta(days=offset)).strftime("%Y-%m-%d")


tonight = "19:30" if now.hour < 19 else "23:45"
plans = [
    {"day": day(0), "time": tonight, "title": "Crêpes", "recipeId": "foyer-crepes"},
    {"day": day(1), "time": "12:30", "title": "Risotto aux champignons", "recipeId": "foyer-risotto"},
    {"day": day(3), "time": "19:30", "title": "Velouté de potiron", "recipeId": None},
    {"day": day(5), "time": "20:00", "title": "Pizza maison", "recipeId": "foyer-pizza"},
]
plans_json = json.dumps(plans, ensure_ascii=False, separators=(",", ":"))
log = [
    {"key": "recipes:seed", "kind": "recipes", "items": ["Dahl de lentilles corail", "Tarte poireaux et chèvre"], "at": now_ms - 2 * 3600_000, "read": False},
    {"key": "menu:seed", "kind": "menu", "items": [day(3) + "|19:30|Velouté de potiron"], "at": now_ms - 6 * 3600_000, "read": False},
    {"key": "saver:2", "kind": "saver", "items": ["2"], "at": now_ms - 26 * 3600_000, "read": True},
]

strings = {
    "mode": "demo",
    "language": "fr",
    "themeMode": os.environ.get("THEME", "light"),
    "foyerHousehold": "Maison",
    "foyerRobotName": "Monsieur Cuisine",
    "foyerPlans": plans_json,
    "noticePlans": plans_json,
    "noticeLog": json.dumps(log, ensure_ascii=False, separators=(",", ":")),
    "noticeRecipes": "\n".join(["v1"] + [r["id"] for r in recipes if r["source"] == "foyer"]),
    "updateAvailable": "1.0.99",
    "lastSyncResult": "1 240 recettes Lidl à jour",
}
booleans = {"foyerEnabled": False, "welcomeDone": True}
longs = {"lastSyncMs": now_ms - 8 * 3600_000, "foyerLastSyncMs": now_ms - 600_000}
ints = {"noticeSaverItems": 0}

with open(os.path.join(out, "toque.xml"), "w", encoding="utf-8") as f:
    f.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
    for k, v in strings.items():
        f.write(f'    <string name="{k}">{escape(v)}</string>\n')
    for k, v in booleans.items():
        f.write(f'    <boolean name="{k}" value="{str(v).lower()}" />\n')
    for k, v in longs.items():
        f.write(f'    <long name="{k}" value="{v}" />\n')
    for k, v in ints.items():
        f.write(f'    <int name="{k}" value="{v}" />\n')
    f.write("</map>\n")
print("seed written to", out)
