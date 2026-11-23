# rxhome — design

Android phone app: one screen of fast controls for the home setup (server, TV, Tapo lights) plus one-tap scenes. Part of [rxenv](https://github.com/RexSAS/rxenv).

## Why

Open the app, see what's on, change it in one tap. Today each thing has its own app (Tapo app, SwitchBot app, FireStick remote) and nothing combines them ("lights down + TV on + start music" is three apps).

## Goals

- **Server (rexserver):** shows online / offline; one power button.
- **TV (FireStick):** power on/off; a remote screen on the phone (d-pad, OK, back, home, volume, play/pause, launch apps).
- **Lights (TP-Link Tapo):** per light on/off and brightness.
- **Scenes:** buttons that run several actions in sequence (e.g. Movie night: lights 20%, TV on, open RxFilms).
- **Works from anywhere.** Each card checks whether its device is reachable right now; if not, it is **greyed out** (not an error). At home or with WireGuard on, everything works.
- Look: **"Stealth"** — matte black, a single red accent (`#ff1e3c`), big flat tiles, Chakra Petch type (mockup D, chosen by the user).

## Non-goals

- Controlling TV/lights from outside without WireGuard (would need a server-side helper; rejected, see Approaches).
- LG webOS TV, other device brands, automations/schedules, widgets, multiple users. Can come later.
- Play Store release. Sideloaded APK like the other rxenv apps.

## Anti-goals

- Slow to open. The home screen must render instantly from last-known state, then refresh; a dead device must never block the others (each check has its own short timeout, ~1.5 s).
- Pretending: a grey card means "can't reach it", never a fake "off".
- Pressing the server's power button by accident (it is a hard power press). Server power needs a confirm (long-press or tap-then-confirm).

## Constraints (found in the existing setup)

- **Server power** is a SwitchBot Bot pressing the server's physical button. Controlled through the official SwitchBot cloud API v1.1 (token + secret, HMAC-SHA256 signed request, `press` command), so it works from anywhere and while the server is off. The Bot is paired to the user's **SwitchBot Hub Mini** (cloud control needs it). A press toggles: the app does not know if a press will turn it on or off, so it labels the button by current known state.
- **Server stats (added 2026-11-17 at the user's request):** CPU %, hottest core temperature, RAM used/total and uptime from Prometheus node_exporter at `http://<server LAN host>:9100/metrics` (Ubuntu package `prometheus-node-exporter`). LAN/WireGuard only; the line is hidden when unreachable.
- **Server status:** HTTP GET Jellyfin `/System/Info/Public` on the LAN address (works at home and over WireGuard). The public `https://rxfilms.rxsas.it` is behind a Cloudflare IP allowlist (home WAN + one DDNS name), so from mobile data it is blocked and can't tell off from blocked: in that case show status **unknown** (grey) while the power button stays usable.
- **TV:** FireStick controlled over ADB on TCP 5555 (ADB debugging is already enabled for rxVision). Device: Fire TV Stick `AFTMA08C15`, Fire OS 8.1.8.5, at `192.168.1.49` (set in settings; home router is a stock Poste Italiane box: reserve the address there if it offers static DHCP, otherwise the user updates the IP in settings; Tapo IPs likewise). **Tested 2026-11-17:** keys must be sent with the d-pad source: `input dpad keyevent <code>`. Plain `input keyevent KEYCODE_SLEEP` / `KEYCODE_POWER` are received but ignored. Sleep = `input dpad keyevent 223`, wake = `input dpad keyevent 224`. Never send `POWER` (26): it toggles. Stick state is read from `dumpsys power` → `mWakefulness=Awake|Asleep`. The stick stays reachable over ADB while asleep (checked at 2 and ~5 min).
- **TV (Philips Ambilight, CEC vendor `00:90:3E`) power — not controllable by the app (decided 2026-11-17):** the FireStick **never sends CEC `<Standby>`** on sleep (not even from the real remote's Sleep), and its wake does not reliably send `<Text View On>`. The user rejected IR from the SwitchBot Hub Mini. The Alexa remote's own IR (Equipment Control) can only be fired through `com.amazon.tv.devicecontrol` receivers guarded by `signature|privileged` permissions (`devicecontrol.WRITE`, `SEND_ALEXA_DIRECTIVE`, `SEND_DATA_TO_ALEXA`), so not from adb. The TV exposes no network control (no JointSPACE 1925/1926, no Android TV Remote 6466/6467).
  - The TV card's power button sleeps/wakes the **FireStick**. The Philips follows through its own settings: EasyLink (HDMI-CEC) on, and Eco "switch off with no signal". README tells the user.
  - Remote/apps are LAN-only (ADB). First connection needs the user to accept the phone's ADB key on the TV once. The ADB wire protocol (CNXN / AUTH with an RSA key / OPEN `shell:`) is implemented in the app.
- **Lights:** Tapo local API over the LAN (KLAP protocol: handshake with the Tapo account email/password hash, then AES-encrypted JSON `set_device_info` / `get_device_info`). No Tapo cloud. Models are not known yet, so (my call) the app supports both handshakes: KLAP first, falling back to the older RSA "securePassthrough". It reads `model` and capabilities from `get_device_info` on first contact: plugs (P1xx) get an on/off switch only, bulbs/strips (L5xx/L9xx) get brightness, and colour temperature only where the device reports it. Risk: Tapo firmware updates have changed the local protocol before; if a device refuses both handshakes, its card goes grey with "protocol not supported".
- **Build:** same as rxVision: plain Java, Android SDK command-line tools, `build.sh`, no Gradle, minSdk 26. Reuse rxVision's build script and code where it fits (ClaudeFm lookup).
- **Secrets on the phone:** SwitchBot token/secret and Tapo credentials live in the app's private storage; never logged, never in the repo.

## Approaches

1. **Phone app only** — *chosen.* Each card talks to its device directly; SwitchBot via its cloud. Nothing new runs at home; server power works even when the server is off; matches "grey when not reachable".
2. Phone app + small helper service on rexserver — TV/lights from outside without WireGuard, but a new internet-facing service needing auth, and it's down whenever the server is off.
3. Home Assistant on rexserver + rxhome as front-end — least device code, but heavy for the i3 box and also down when the server is off.

The user picked 1.

## Screens

**Home** (single scrolling screen, order as mockup D):

```
rxhome                     21:47
┃ rexserver        ● ONLINE  [⏻]
┃ Living room TV   Standby   [⏻]
┃ [REMOTE] [APPS] [VOL]
┃ Lights
┃ Ceiling   ▬▬▬▬▬▬▬▬░░   (on)
┃ Desk      ▬▬▬▬░░░░░░   (on)
┃ LED strip ░░░░░░░░░░   (off)
[Movie night][Music]
[All off]    [Gaming]
```

- Pull to refresh; also refresh on app resume.
- Grey card = unreachable; tap on it shows why in one line ("Not on home Wi-Fi or WireGuard").

**Remote:** d-pad + OK, back, home, menu, play/pause, volume up/down/mute, power. Haptic on press. Optional: swipe pad instead of d-pad (builder's call, d-pad first).

**Apps:** row of TV apps to launch (RxFilms `org.rxfilms.tv`, SmartTube, YouTube, …), from `pm list packages` once, launched with `monkey -p <pkg> 1` or `am start`.

**Settings:** server LAN URL, SwitchBot token/secret + Bot device ID (picked from the API's device list), FireStick IP, Tapo email/password + light IPs and names, scenes (see below).

## Scenes

A scene is an ordered list of steps; a step is one of:
`light <name|all> on|off [brightness] [warm|cool|<kelvin>]`, `tv on|off`, `tv app <package>`, `tv music` (ClaudeFm), `server press`, `wait <seconds>`.

`all` means every configured light, LED strip included; a later step can override one light (Gaming = `all 30%` then `LED strip on 100%`). Colour temperature (`warm` = 2700 K, `cool` = 6500 K) is sent only to lights that support it and ignored by the rest; the light cards themselves stay on/off + brightness (my call).

Stored as plain text in settings, one scene per line (`Name: step; step; …`), editable in the app (my call: cheapest editor that still lets the user change scenes on the phone; format in the plan, Task 2). Seeded with:

- **Movie night:** lights 20%, TV on, wait 5, open RxFilms
- **Music:** lights warm 60%, TV on, wait 5, ClaudeFm
- **Gaming:** LED strip on, other lights 30%
- **All off:** TV off, all lights off

Steps whose device is unreachable are skipped; the scene button shows "3/4 done" briefly instead of failing silently.

`tv music` reuses rxVision's ClaudeFm lookup (YouTube live search for the Claude FM stream, fallback id) and sends `am start -a android.intent.action.VIEW -d https://www.youtube.com/watch?v=<id>` to the FireStick, which opens it in SmartTube.

## Left to the builder

- ADB: own implementation vs vendored library.
- Tapo: discovery is not required (IPs entered in settings, fixed by DHCP reservation).
- Package name (`com.andrea.rxhome` to match `com.andrea.tvlauncher`).
- Exact layout spacing and icons within the Stealth look.
- Tests: plain-Java self-checks for the pure parts (SwitchBot request signing against a known vector, scene JSON parsing, Tapo KLAP hash/crypto against python-kasa's known values).

## Repo

New repo `rxhome/` beside the others in rxenv, git-ignored in rxenv and added to rxenv's README table.
