# rxhome

A phone app with fast controls for the home setup, part of [rxenv](https://github.com/RexSAS/rxenv):

- **rexserver**: online or not (LAN address first, then `rxfilms.rxsas.it`), CPU / temperature / RAM / uptime, and a power button that presses the server's SwitchBot Bot (works from anywhere, even with the server off).
- **TV**: puts the FireStick to sleep or wakes it, a no-look remote (touchpad: swipe to move, tap for OK, hold for long press, draw ○ for HOME, ⊃ for BACK, C for play/pause; the other keys along the bottom edge; the phone's volume buttons drive the TV's; holding any key holds it on the TV), and opening TV apps.
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

## First run

Open ⚙ Settings:

1. **Server**: the Jellyfin LAN address (default `http://192.168.1.166:8096`) and public address (default `https://rxfilms.rxsas.it`).
   For the CPU / temperature / RAM line, install node_exporter on rexserver once: `sudo apt install prometheus-node-exporter` (serves `:9100/metrics` on the LAN; the line is hidden when it can't be reached).
2. **SwitchBot**: in the SwitchBot app, Profile → Preferences → tap *App Version* 10 times → Developer Options shows the token and secret. Paste both, then *Pick the Bot* and choose the one on the server's button.
3. **TV**: the FireStick's IP (default `192.168.1.49`). The first time, the TV shows "Allow USB debugging?" for *rxhome*: tick *Always allow* and accept.
   **Philips TV** (volume, mute, real power): with the TV on, tap *Pair with the TV* and type the PIN it shows (its own JointSPACE API, as pylips does it). To switch it on from standby, enable *Switch on with Wi-Fi (WoWLAN)* in the TV's network settings; rxhome sends Wake-on-LAN to the MAC it reads at pairing.
4. **Tapo**: your Tapo account email and password. Lights and plugs are found on the Wi-Fi automatically (UDP broadcast, port 20002) and named as in the Tapo app; the list in Settings (`Name=IP`) can be renamed and follows lights whose IP changes. Put `#` before a name (`#Desk strip=…`) to hide that light from rxhome and its scenes; discovery won't add it back.
   Lights on 2025 firmware (TPAP login, e.g. the L900-5 strips) only accept rxhome's local login with **Third-Party Compatibility** on: Tapo app → Me → Third-Party Services → Third-Party Compatibility. Until then they show as "L900 .10" and stay grey; tap one to see why.
5. **Scenes**: one per line, `Name: step; step; …`. Steps: `light <name|all> on|off [0-100] [warm|cool]`, `tv on|off`, `tv app <package>`, `tv music` (Claude FM in SmartTube), `server press`, `wait <seconds>`. Names with spaces go in quotes: `light "LED strip" on 100`.

IPs can change: if your Poste Italiane router lets you reserve addresses (static DHCP), reserve the FireStick's and the lights'; otherwise update them in Settings when they move.

## Known limits

- Without pairing the Philips TV, power only sleeps/wakes the FireStick (Fire OS never sends CEC standby; the Alexa remote's infrared is closed to apps), and volume/mute do nothing.

- The server status can't tell "off" from "can't reach it" away from home (Cloudflare only lets in allowed IPs), so it shows "unknown" there.
- Tapo: KLAP (and the older passthrough login) only. TPAP, the SPAKE2+ login of 2025 firmware, isn't implemented; with Third-Party Compatibility on, those devices accept KLAP.
