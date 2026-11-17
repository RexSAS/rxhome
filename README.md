# rxhome

A phone app with fast controls for the home setup, part of [rxenv](https://github.com/RexSAS/rxenv):

- **rexserver**: online or not (LAN address first, then `rxfilms.rxsas.it`), CPU / temperature / RAM / uptime, and a power button that presses the server's SwitchBot Bot (works from anywhere, even with the server off).
- **TV**: puts the FireStick to sleep or wakes it, a full remote on the phone, and opening TV apps.
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

`build.sh` creates `signing.keystore` on first run; keep it (git-ignored): updates must be signed with the same key.

## First run

Open ⚙ Settings:

1. **Server**: the Jellyfin LAN address (default `http://192.168.1.166:8096`) and public address (default `https://rxfilms.rxsas.it`).
   For the CPU / temperature / RAM line, install node_exporter on rexserver once: `sudo apt install prometheus-node-exporter` (serves `:9100/metrics` on the LAN; the line is hidden when it can't be reached).
2. **SwitchBot**: in the SwitchBot app, Profile → Preferences → tap *App Version* 10 times → Developer Options shows the token and secret. Paste both, then *Pick the Bot* and choose the one on the server's button.
3. **TV**: the FireStick's IP (default `192.168.1.49`). The first time, the TV shows "Allow USB debugging?" for *rxhome*: tick *Always allow* and accept.
4. **Tapo**: your Tapo account email and password, and one light per line as `Name=IP` (IP from the Tapo app → device → Device info).
5. **Scenes**: one per line, `Name: step; step; …`. Steps: `light <name|all> on|off [0-100] [warm|cool]`, `tv on|off`, `tv app <package>`, `tv music` (Claude FM in SmartTube), `server press`, `wait <seconds>`. Names with spaces go in quotes: `light "LED strip" on 100`.

IPs can change: if your Poste Italiane router lets you reserve addresses (static DHCP), reserve the FireStick's and the lights'; otherwise update them in Settings when they move.

## Known limits

- **The Philips TV's own power isn't controlled by the app.** Fire OS never sends the "TV off" HDMI-CEC message, the Alexa remote's infrared can only be fired by Amazon's own system apps, and the TV has no network control. The power button sleeps and wakes the FireStick; for the TV to follow, turn on **EasyLink (HDMI-CEC)** and the Eco setting **switch off when there's no signal** on the Philips.
- For the same reason **VOL / MUTE** may not change the TV's volume.
- The server status can't tell "off" from "can't reach it" away from home (Cloudflare only lets in allowed IPs), so it shows "unknown" there.
- Tapo: tested against python-kasa's KLAP algorithm, not yet against your lights; older firmware uses a fallback login that hasn't been tested on a device.
