# rxhome

A phone app with fast controls for the home setup, part of [rxenv](https://github.com/RexSAS/rxenv):

- **rexserver**: online or not (LAN address first, then `rxfilms.rxsas.it`), CPU / temperature / RAM / uptime, and a power button that presses the server's SwitchBot Bot (works from anywhere, even with the server off).
- **TV**: Samsung (including 2010-2015 models), LG and Philips TVs, FireSticks and Sky Q boxes, in any home: add each once; the home screen shows one TV card at a time: swipe it for the next, in the order set with ▲ ▼ in Settings → TVs. Tap the card (or the TV in Settings) for its **TV info**: its name, and which FireStick and Sky box are plugged into it (bound by hand: a home can have several FireSticks). Remote and Apps work on the card you open them from; the remote's text bar (FireStick style) types on the TV as you go. Power on/off, a no-look remote (touchpad: swipe to move, tap for OK, hold for long press, draw ○ for HOME, ⊃ for BACK, C for play/pause; the other keys along the bottom edge, SOURCE for the TV's inputs at the top; the phone's volume buttons drive the TV's; holding any key holds it on the TV; with a FireStick or Sky box bound, buttons at the top (*TV · FireStick · Sky*) pick which one the arrows and touchpad drive, FireStick by default; in Sky style the bottom rows are Sky's: channel up/down, info, record, the four colours), and an Apps screen (a TV without a FireStick lists its own apps): RxFilms (Continue watching / Next up, after a one-time Jellyfin Quick Connect sign-in in Settings), Spotify (Liked Songs / DJ X), YouTube in SmartTube (Claude FM); tap an app to open it, a shortcut to go straight there (the TV is switched on first if needed).
- **Tapo lights**: on/off and brightness, straight over Wi-Fi without the Tapo cloud.
- **Scenes**: one tap for several things, e.g. *Movie night* = lights 20%, TV on, open RxFilms.

Anything it can't reach right now (away from home without WireGuard, device off) is greyed out. The server's power button still works then.

## Build and install

Plain Android SDK command-line tools, no Gradle (same setup as rxhome-tv):

```sh
./build.sh                                   # -> build/rxhome.apk
adb install -r build/rxhome.apk              # phone with USB debugging, or copy the APK over
./test.sh                                    # desktop tests
RXHOME_ADB=192.168.1.49 RXHOME_SERVER=http://192.168.1.166:8096 ./test.sh   # plus live device checks
```

Remote keys go through a tiny helper (`tv/…/Keys.java`, built by `keys.sh` and packed into the APK). The app copies it to the FireStick's `/data/local/tmp` and keeps it running, so a key takes ~10 ms instead of the ~1 s that Fire OS's `input` command needs. If the helper can't start, keys fall back to `input`.

`build.sh` creates `signing.keystore` on first run; keep it (git-ignored): updates must be signed with the same key.

## Updates

rxhome updates itself from this repo's GitHub releases, like rxVision. To ship a new version, commit, then:

```sh
./release.sh        # builds, pushes, creates release v<commit count> with the APK
```

When rxhome next opens (it checks at most once an hour) it asks to update, shows the download progress, then
Android's install confirmation (the first time, Android asks to allow installs from rxhome).

## First run

Open ⚙ Settings:

1. **Nothing to type for addresses.** The server (Jellyfin, port 8096) and every saved TV are found by scanning the Wi-Fi, and found again if they move. TVs are recognised by their own id (Samsung device id, UPnP uuid, or certificate), never by IP, so two homes using the same addresses don't mix. The scan never pairs anything, so no device gets a prompt by itself.
   **Away from home:** when neither the server nor the FireStick answers, rxhome switches on the WireGuard tunnel named `rxfilms` (WireGuard app → Settings → *Allow remote control apps* must be on; rxhome asks once for permission to control WireGuard). The public address `rxfilms.rxsas.it` is still tried too, but Cloudflare's allowlist blocks it before it reaches the server, so from mobile data it can't tell on from off.
   **Following the network:** while rxhome or Jellyfin is open (a notification shows), joining a Wi-Fi switches rxhome's tunnel off and checks whether home answers there (home Wi-Fi: stays off; any other: back on); losing Wi-Fi switches it on if home is out of reach. About 10 s after neither rxhome nor Jellyfin is in front (or the screen is off), rxhome's tunnel goes off and the watch ends. Needs *Usage access* for rxhome (Settings → Apps → Special app access). Tunnels you switch on yourself are left alone. With the server online and the Jellyfin app installed, the server card shows **Open Jellyfin**.
   For the CPU / temperature / RAM line, install node_exporter on rexserver once: `sudo apt install prometheus-node-exporter`.
2. **SwitchBot**: in the SwitchBot app, Profile → Preferences → tap *App Version* 10 times → Developer Options shows the token and secret. Paste both, then *Pick the Bot* and choose the one on the server's button.
3. **TVs**: with the TV on and the phone on its Wi-Fi, Settings → *Add a TV*, pick it from the list, then on the TV:
   - **Samsung** (Tizen, 2016+): choose *Allow*. Switching on from the phone needs the TV's network standby / Wake-on-LAN (on by default on most).
   - **LG** (webOS): choose *Accept*. To switch it on from the phone, turn on *Turn on via Wi-Fi* (or *Mobile TV On*) in the TV's settings.
   - **Philips** (JointSPACE): type the PIN it shows. This model can't report its power, so it's read from what the TV announces over HDMI-CEC to the FireStick.
   - **FireStick**: turn on ADB debugging (Settings → My Fire TV → Developer options), then on the TV tick *Always allow* and accept "Allow USB debugging?".
   - **Sky Q**: nothing to confirm. (Sky Glass and Sky Stream have no network remote.)
   - Then tap the TV in the list and pick its FireStick and Sky box in **TV info**. Nothing is attached by itself.
   A Samsung can't set an exact volume (a scene's volume step is skipped there) and its mute toggles.
4. **Tapo**: your Tapo account email and password. Lights and plugs are found on the Wi-Fi automatically (UDP broadcast, port 20002) and named as in the Tapo app; the list in Settings (`Name=IP`) can be renamed and follows lights whose IP changes. Put `#` before a name (`#Desk strip=…`) to hide that light from rxhome and its scenes; discovery won't add it back.
   Lights on 2025 firmware (TPAP login, e.g. the L900-5 strips) only accept rxhome's local login with **Third-Party Compatibility** on: Tapo app → Me → Third-Party Services → Third-Party Compatibility. Until then they show as "L900 .10" and stay grey; tap one to see why.
5. **Scenes**: Settings lists your scenes; tap one to edit it, or *Add new scene*. The editor builds a scene by tapping: a name, then steps (lights: which, on/off, brightness, warm/cool white or any colour from the wheel; TV on/off; open RxFilms / Spotify / YouTube; Claude FM; the server's power button; wait), each movable and removable. Saved in the same text format as before.


## Known limits

- A FireStick without its TV added only sleeps/wakes itself (Fire OS never sends CEC standby; the Alexa remote's infrared is closed to apps), and volume goes through its keys.
- Other homes work only on their Wi-Fi (WireGuard reaches your own home only).
- Android/Google TVs (Sony, TCL, Hisense, newer Philips) aren't supported yet.

- The server status can't tell "off" from "can't reach it" away from home (Cloudflare only lets in allowed IPs), so it shows "unknown" there.
- Tapo: KLAP (and the older passthrough login) only. TPAP, the SPAKE2+ login of 2025 firmware, isn't implemented; with Third-Party Compatibility on, those devices accept KLAP.

## Signal to the TV launcher

When a scene runs, rxhome tells each TV it uses, through the stick, with an explicit broadcast to the launcher (`com.andrea.tvlauncher`), so the launcher can fade the picture:

```
am broadcast -a com.andrea.rxhome.SCENE -p com.andrea.tvlauncher
  --es event   start | swap | off | ready | tv_off
  --es scene   <scene name>
  --ei fade_ms 3000
  --es from    <previous scene>        (swap only)
  --es id      <id>                    (ready only)
```

`start`, `swap` and `off` come right before a scene (starting, swapping from another one, or turning off) first changes that TV's picture; rxhome then waits `fade_ms` before changing it, so the launcher can fade to black. `ready` comes once the scene's last step has run: the launcher fades the new picture in when the screen settles and logs `reveal <id> <ms>`, which rxhome follows to bring the TV's volume (paired Philips) back up over the same time; it faded out with the picture. `tv_off` comes just before rxhome switches that TV off: rxhome fades the sound and waits `fade_ms` before sending standby. Without a receiver, scenes still run; the sound just comes back after 30 s.
