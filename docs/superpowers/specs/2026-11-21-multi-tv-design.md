# Multi-brand TVs: Samsung, LG, Philips, FireStick, in any home

Date: 2026-11-21. Status: approved.

## Intent

Today rxhome drives one TV setup: the user's own FireStick (ADB: keys, apps, power state over CEC) plus
their Philips (JointSPACE: volume, mute, power). The user wants to control TVs in **other people's homes
too**, from the major brands, **easy to set up** and with **all the commands** (power, volume, mute,
d-pad/OK/back/home, apps, inputs). Brands now: **Samsung, LG, Philips, and the FireStick**. More may come later.

Success: walk into a friend's home, join their Wi-Fi, open rxhome, it finds their TV, one confirmation
on the TV (Allow / Accept / PIN), and the remote screen works. Next visit: nothing to do.

## Goals

- Samsung (Tizen, 2016+), LG (webOS), Philips (JointSPACE, as today) and FireStick (ADB, as today).
- One set of commands for all of them; the existing screens (home TV card, Remote, Apps, scenes) work
  on whichever TV is active.
- Pair once per TV; the app remembers every TV it has paired, across homes.
- Found automatically on the current Wi-Fi: no IP addresses, no typing except a Philips PIN.

## Non-goals (for now)

- Android TV / Google TV remote protocol (Sony, TCL, Hisense, newer Philips). Can be added later as
  one more brand behind the same interface.
- Controlling another home's TV from outside that home. Other homes: same Wi-Fi only. The user's own
  home keeps working through the existing WireGuard tunnel.
- Cloud APIs (SmartThings, ThinQ), hubs, IR. (Phone has no IR blaster; checked 2026-11-21, Nothing A069P.)

## Anti-goals

- Accounts, cloud sign-ins or anything installed in the other home.
- Prompts on devices that aren't being paired (today's rule: the scan never offers the ADB key).
- Breaking the user's current setup: the existing FireStick + Philips pairing carries over with no re-pairing.

## Approaches (picked: 1)

1. **Direct per-brand clients on the phone** (picked). One small class per brand, like `Philips.java`
   today, behind one interface. Samsung and LG need a WebSocket; Android has none built in and the
   project has no Gradle/deps, so a minimal hand-written client (~150 lines). No accounts, no cloud, no deps.
2. Google's Android TV Remote v2 protocol plus Samsung/LG: covers more brands but none the user named;
   TLS + PIN + protobuf. Deferred; fits behind the same interface later.
3. Brand clouds / hubs: works away from home but needs accounts or a hub per home. Rejected (not easy).

## Design

### Model: a "TV" = a screen, plus optionally a FireStick

A saved TV is one entry with:
- `name` (from the TV; editable later is out of scope),
- `brand`: samsung | lg | philips | none (a FireStick on a TV that isn't paired),
- brand pairing data (Samsung token + cert pin; LG client-key + cert pin; Philips as today: user/pass/cert pin),
- `id`: the brand's unique id (Samsung `device.id`, LG/Philips UUID from SSDP or their info endpoint),
- `mac` for Wake-on-LAN,
- optional `stick`: a FireStick on that TV (identified by its ADB serial / that it trusts our key).

Routing when both exist (today's behaviour, generalised):
- **power, volume, mute, inputs** → the TV's own API;
- **d-pad keys, touchpad, apps, RxFilms / Spotify / Claude FM** → the stick if there is one, else the TV;
- **power state** → the TV's own API where it can tell (Samsung `PowerState`, LG socket answering),
  else the stick's CEC reading (Philips, as today).

**Remote switch (user's decision, 2026-11-21).** When a saved TV has both a paired TV and a stick, the
Remote screen shows a two-way switch *FireStick | TV* that picks where the input controls go: d-pad,
OK, back, home, touchpad gestures, hold/repeat, play/pause. **Default: FireStick.** Controls only one
side can do ignore the switch and always go there (volume/mute/power/inputs → TV; FireStick apps,
RxFilms, Spotify, Claude FM → stick). The switch is hidden when there's only one of the two.
Builder's call: the choice is remembered per saved TV and starts as FireStick.

### Which TV is active

On start (and when Wi-Fi changes) Discovery scans the /24 as today and matches what answers to saved
TVs **by `id`**, not by IP (many homes use the same 192.168.1.x). One match → it's the active TV.
Several saved TVs found on this Wi-Fi → a small picker on the TV card; the choice is remembered per
network. None found → the TV card shows "No TV on this Wi-Fi" with *Add a TV*. No location permission
needed (no SSID reading).

### Adding a TV (Settings → *Add a TV*)

The scan lists every TV found on this Wi-Fi with brand and name (Samsung port 8001, LG 3000/3001,
Philips 1925/1926, FireStick 5555). The user taps one:
- **Samsung**: open wss :8002 with app name "rxhome"; the TV shows *Allow*; the token it returns is saved.
- **LG**: wss :3001 `register` with the standard manifest; the TV shows *Accept*; `client-key` saved.
- **Philips**: today's PIN flow.
- **FireStick**: connect to ADB, which makes the stick show "Allow USB debugging?" (only now, on the
  user's tap). Needs ADB debugging switched on on that stick first; the screen says how.
  The stick is then attached to the TV found in the same home (or saved alone if none).

TLS on Samsung/LG/Philips is self-signed: pin the certificate's SHA-256 at pairing, as Philips does now.

### Commands (one interface, each brand maps it)

`power on/off/state, volume set/up/down, mute, key(name) incl. hold (down/repeat/up), launch(app), input(source)`.
- Samsung: `ms.remote.control` with `KEY_*` (Click / Press / Release for hold); apps via
  `ed.apps.launch`; power on = Wake-on-LAN (TV must allow it: on by default on most Samsungs).
- LG: SSAP requests (`audio/setVolume`, `setMute`, `system/turnOff`, `launch`, `switchInput`), d-pad via
  the pointer input socket; power on = Wake-on-LAN (needs *Mobile TV On / Turn on via Wi-Fi* in the TV's
  settings: the add flow says so).
- Philips: as today.
- FireStick: as today (helper for fast keys, `input` fallback).
A command a brand can't do is hidden/greyed in the UI, never silently ignored.

### Apps screen and scenes

- With a stick: unchanged (RxFilms, Spotify, SmartTube/Claude FM, installed apps).
- TV alone (Samsung/LG): the TV's own installed-apps list, tap to launch. RxFilms/Spotify deep links and
  Claude FM are FireStick-only and hidden.
- Scenes keep their text format; TV steps run on the active TV. A step the active TV can't do is skipped
  and reported, like an unreachable light today.

### Migration

On first start of the new version, the current `firetv` + `philips_*` settings become saved TV #1
(brand philips, with stick). No re-pairing. Old keys are removed after the move.

## Constraints

- Plain Android SDK, no Gradle, no new dependencies.
- Plain-Java logic stays free of `android.*` so `test.sh` runs it on the desktop JVM: WebSocket framing,
  Samsung/LG message building and parsing, routing, id matching and migration get unit tests there.
  Live tests only with env vars set, like `RXHOME_ADB` today.

## The builder's calls (not decided by the user)

- How the code is split: one `Tv` interface plus `Samsung`, `Lg`, `Philips`, `FireTv`, and a small
  composite that does the routing above; `Config.tv()` returns the active one.
- How saved TVs are stored (one JSON list in the existing prefs is fine).
- Exact LG manifest and Samsung app-name/token details: follow the open-source clients
  (samsungtvws, aiowebostv/pywebostv) as Philips follows pylips.
- Whether identity comes from SSDP or each brand's info endpoint.
