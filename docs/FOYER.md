# Toque and Foyer

Foyer is the household's app (groceries, kitchen, meal plans, home). Since Toque 1.0 the robot is
linked to it once, then:

- the household's **shared recipes** appear on the robot by themselves (checked every 5 minutes,
  nothing downloaded when nothing changed), with their photos and, when possible, the machine
  settings of each step;
- the **meals planned** in Foyer show on the home screen ("Ce soir · 20 h", one tap to cook);
- Foyer shows **what the robot is doing** (Maison screen) and the phones are told when a dish is
  ready, a step waits for someone or the robot stopped for safety;
- a household recipe cooked to the end is counted in Foyer ("déjà faite");
- a recipe that comes from a **reel** can be watched again on the robot ("Revoir la vidéo": a panel
  beside the recipe or the step, or full screen) or sent to a phone ("Voir sur le téléphone").

The robot never starts anything from the phone: the motor is only driven by a person standing at
the robot, with every safety rule of Toque.

## Linking, once

1. On the robot, the home screen (or Settings › Foyer) shows a 6-digit code.
2. On the phone, Foyer › Réglages › Monsieur Cuisine: type the code, tap "Relier". The robot then
   shows "Relié au foyer « … »": check it is yours.

That is all. The link has no expiry and survives restarts and updates. To undo it: Foyer ›
Réglages › Monsieur Cuisine › Délier, or on the robot Settings › Foyer › Link to another
household. Either way the robot erases the household's recipes and meals and shows a new code.

## How it is secured

- On its first start, Toque makes a 256-bit random secret and keeps it in its private files. Foyer
  stores only its SHA-256. Every call carries it as `Authorization: Bearer`, over HTTPS.
- The 6-digit code is valid 10 minutes and only links a robot when a signed-in member (password
  and authenticator code) types it; 10 tries per 10 minutes per member. Foyer may say that a robot
  waits on the same connection, never its code (someone sharing the public address, a guest or a
  fake robot is never one tap away). At most 200 robots wait to be linked across the service.
- A linked robot can read the household's shared recipes and planned meals, report its state and
  mark a recipe as cooked. Nothing else: no private items, no other module, 60 calls a minute,
  and it can cause at most 12 notifications an hour (3 between 21:00 and 08:00).
- Recipe photos: Foyer keeps its own copy of each household recipe's photo (the cover of an
  Instagram reel or a TikTok video, a recipe site's picture, or a photo taken in Foyer) and serves
  it at `/api/robot/photo/<recipe>?v=…`, to linked robots only: the robot sends its key with that
  request, and only that one (never on a redirect, never to another site). Older recipes may still
  point at their site's picture. Either way the robot only fetches photos over https, never from an
  address of the home network, 20 s and 4 MB at most each, on their own thread, and only JPEG, PNG
  or WebP of a sane size reach Android 6's image decoder; a bad one is skipped and tried again an
  hour later at most (a new Toque version re-reads the whole feed, photos included).
- The robot has no clock battery: after being switched off it believes it is 1970 or 2010, and
  no secure connection works until the time is set. Toque sets it as soon as the network answers
  (time servers, else the `Date` header of Foyer's http → https redirect, retried within seconds
  while the clock is wrong); meanwhile the Foyer card says the clock is not set yet instead of a
  certificate error. Recipes without a photo show an icon of their kind of dish (soup, cake,
  fish…, guessed from the title and category).
- The robot's secret is made once; if it cannot be read, the robot stops instead of making a new
  one (which would unlink it silently).
- Android 6 lacks the newer root certificates: since 1.0.5 every https call of Toque (Foyer,
  recipe photos, GitHub for updates, reels) trusts the system's roots plus ISRG Root X1, DigiCert
  Global Root G2 and G3, GTS Root R1, USERTrust ECC and RSA, and Sectigo Public Server
  Authentication Root E46 and R46, bundled in `app/src/main/assets/certs/foyer-roots.pem` (taken
  from Mozilla's store, SHA-256 fingerprints listed in the file). Host names are checked as usual.
  Before 1.0.5 the update check could not reach GitHub from Android 6 ("Connexion sécurisée à
  GitHub impossible"): such a robot is updated once from the Mac (`update` step), then it updates
  itself.
- Foyer can only add, change or remove recipes whose id starts with `foyer-`: the Lidl catalogue is
  never touched, and the Lidl syncs never touch household recipes (`recettes.db` column `origin`).

## Reels: on the robot, or on a phone

Foyer sends each recipe's source link (`link`) and, for a social video, the same link as `video`.

- **Revoir la vidéo** (recipe and cooking screens): the robot itself reads the reel's public
  "embed" page (`https://www.instagram.com/p/<code>/embed/captioned/`), from home, without the
  Foyer key: https and instagram.com only (redirects included), never a home-network address, 6 s,
  3 MB. When the page gives the video's address (https, not a local address), Android's player
  streams it from Instagram, in a panel on half the screen (the machine panel and the step buttons
  stay free while cooking) or full screen; play/pause, ±10 s, sound. Nothing is downloaded to keep,
  on the robot or by Foyer. Without an account Instagram does not always give the video, and
  TikTok or YouTube are not read on the robot: the panel then shows the cover and the phone button.
- **Voir sur le téléphone**: `POST /api/robot/open`. Foyer notifies the phone of the member with
  notifications (if several, the robot asks whose); the notification opens the recipe in Foyer,
  whose "Voir la vidéo" opens the reel in Instagram or the browser. 30 a day per robot.

## Screensaver

After a few minutes with nobody at the robot (no touch, no knob, nothing moving at the machine)
and only when it is idle (never while a job starts, runs, pauses or stops, nor with a step
waiting, a safety stop shown, a dialog or a reel open), Toque shows a screensaver. It is chosen in
Foyer › Réglages › Monsieur Cuisine: the clock (time, date, tonight's meal from Foyer; it drifts
slightly every minute so nothing marks the screen), the household's animations, or nothing; the
delay (1 to 60 minutes) and how long each animation stays (5 to 300 seconds). Without a Foyer
setting (not linked, older Foyer) it is the clock after 5 minutes. Any touch, knob turn or press,
or change at the machine (lid, bowl, weight, a job, a fault) wakes it, and that gesture does
nothing else. Settings › Écran de veille shows what Foyer chose and tries it at once.

- **Nothing is bundled with Toque**: no characters, no pictures (its code is public). The
  animations are the household's own files (GIF, MP4 in H.264, JPEG, PNG or WebP, 15 MB at most,
  60 per household), uploaded in Foyer and kept in a private storage bucket of the household.
- The feed lists each one as `/api/robot/screensaver/<id>?v=…`. The robot asks it with its key
  (only on that first request to Foyer's exact host), Foyer answers with a redirect to a storage
  link valid two minutes, and the robot follows it after the same checks as photos (https, no
  home-network address). Each file is written straight to the robot's private storage (never whole
  in memory), 15 MB and 2 minutes at most, kept only if its first bytes match the announced kind,
  downloaded once on its own thread, and deleted when Foyer no longer lists it. A file that fails
  is tried again after an hour.
- GIFs are played with Android's Movie (fit to the screen, 20 images a second at most), videos
  muted and looped with Android's player, pictures decoded at the screen's size. One that cannot be
  read is skipped; if none can, the clock shows.

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
| `GET /api/robot/sync` | Recipes in the `toque-recettes-1` format (ids `foyer-<uuid>`), planned meals of the next 7 days, `pendingSteps`, and the `screensaver` block (mode, delay, seconds, files); `ETag` / 304 |
| `POST /api/robot/status` | `{ state, recipeId, title, stepIndex, stepCount, stepText, tempC, speed, remainingSeconds, alert, notify }` (the end time is computed with the server's clock) |
| `POST /api/robot/cooked` | `{ recipeId }` |
| `GET /api/robot/photo/<recipe>?v=…` | The photo Foyer keeps for a shared household recipe (JPEG, PNG or WebP); 401 unlinked, 404 none |
| `GET /api/robot/screensaver/<id>?v=…` | One of the household's screensaver files: 302 to a storage link valid 2 minutes; 401 unlinked, 404 gone |
| `POST /api/robot/open` | `{ recipeId, memberId? }` → `{ status: "sent" }`, `{ status: "choose", phones: [{ id, name }] }`, `{ status: "no_phone" }`; 404 no link, 429 too many |

401 means the robot is not (or no longer) linked: Toque shows a code again. The code is in
`app/src/main/kotlin/app/toque/foyer/` (Android) and `core/.../foyer/` (feed reader and state
tracker, unit tested in `FoyerTest`).

## Updates

Toque checks this repository's releases every night (03:30). A new version shows on the home
screen and installs in one tap from Settings › Updates, by someone at the robot who can check it
works. Settings › Updates › "Install new versions by itself at night" installs them unattended
instead, on a rooted robot with the machine stopped, and only versions published at least 2 days
earlier (time to withdraw a bad one). Off by default.

A robot still on a release of the original repository is switched once from the Mac it was
installed from, then ADB over Wi-Fi can be closed (nothing needs it day to day):

```
bash tools/setup/toque-setup.sh update --ip <robot IP>
bash tools/setup/toque-setup.sh adb-off
```

The release signing key is public in this repository (every build installs over the previous
one): what protects the robot is who can publish a release here. Keep two-factor
authentication on the GitHub account, and on Netlify for Foyer.
