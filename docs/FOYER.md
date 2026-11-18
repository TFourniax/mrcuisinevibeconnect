# Toque and Foyer

Foyer is the household's app (groceries, kitchen, meal plans, home). Since Toque 1.0 the robot is
linked to it once, then:

- the household's **shared recipes** appear on the robot by themselves (checked every 5 minutes,
  nothing downloaded when nothing changed), with their photos and, when possible, the machine
  settings of each step;
- the **meals planned** in Foyer show on the home screen ("Ce soir · 20 h", one tap to cook);
- Foyer shows **what the robot is doing** (Maison screen) and the phones are told when a dish is
  ready, a step waits for someone or the robot stopped for safety;
- a household recipe cooked to the end is counted in Foyer ("déjà faite").

The robot never starts anything from the phone: the motor is only driven by a person standing at
the robot, with every safety rule of Toque.

## Linking, once

1. On the robot, the home screen (or Settings › Foyer) shows a 6-digit code.
2. On the phone, Foyer › Réglages › Monsieur Cuisine: on the same Wi-Fi, a card shows the same
   code, tap "Relier"; otherwise type the code.

That is all. The link has no expiry and survives restarts and updates. To undo it: Foyer ›
Réglages › Monsieur Cuisine › Délier (the robot keeps the recipes it has and shows a new code), or
on the robot Settings › Foyer › Link to another household.

## How it is secured

- On its first start, Toque makes a 256-bit random secret and keeps it in its private files. Foyer
  stores only its SHA-256. Every call carries it as `Authorization: Bearer`, over HTTPS.
- The 6-digit code is valid 10 minutes and only links a robot when a signed-in member (password
  and authenticator code) enters it or taps the card; 10 tries per 10 minutes per member. The card
  is offered only to a phone on the same home connection (same public IPv4 address, or same IPv6
  /64), and it shows the code so it can be checked against the robot's screen.
- A linked robot can read the household's shared recipes and planned meals, report its state and
  mark a recipe as cooked. Nothing else: no private items, no other module, 60 calls a minute.
- Android 6 lacks the newer root certificates: connections to Foyer and to recipe photos also
  trust ISRG Root X1, DigiCert Global Root G2 and GTS Root R1, bundled in
  `app/src/main/assets/certs/foyer-roots.pem` (SHA-256 fingerprints listed in the file). Host
  names are checked as usual.
- Foyer can only add, change or remove recipes whose id starts with `foyer-`: the Lidl catalogue is
  never touched, and the Lidl syncs never touch household recipes (`recettes.db` column `origin`).

## Machine settings of household recipes

Foyer works them out once per recipe and keeps them while the recipe is unchanged:

1. recipes already written for a kitchen robot ("3 min / 120 °C / vitesse 1", "Pétrir 2 min",
   "Turbo 3 fois") are read as they are;
2. otherwise Claude adapts the recipe to the Monsieur Cuisine Connect. Each setting it proposes
   is marked **to check**: the cooking screen asks for a tap on "J'ai vérifié ce réglage" before it
   can run.

Every setting is brought within Toque's limits before it reaches the robot (speed 3 at most when
heating or in reverse, 4 with the whisk, durations, temperatures of the machine's table), and the
robot checks it again before running it.

## The API (Foyer side)

| Call | What |
|---|---|
| `POST /api/robot/hello` | `{ version }` → `{ status: "paired", household }` or `{ status: "pending", code, expiresAt }` |
| `GET /api/robot/sync` | Recipes in the `toque-recettes-1` format (ids `foyer-<uuid>`), planned meals of the next 7 days, `pendingSteps`; `ETag` / 304 |
| `POST /api/robot/status` | `{ state, recipeId, title, stepIndex, stepCount, stepText, tempC, speed, endsAt, alert, notify }` |
| `POST /api/robot/cooked` | `{ recipeId }` |

401 means the robot is not (or no longer) linked: Toque shows a code again. The code is in
`app/src/main/kotlin/app/toque/foyer/` (Android) and `core/.../foyer/` (feed reader and state
tracker, unit tested in `FoyerTest`).

## Updates

Toque 1.0 updates from this repository's releases, by itself at night (03:30) on a rooted robot
with the machine stopped (Settings › Updates › Install updates at night). A robot still on a
release of the original repository is switched once from the Mac it was installed from:

```
bash tools/setup/toque-setup.sh update --ip <robot IP>
```
