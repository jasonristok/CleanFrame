# CleanFrame auf dem Fire TV

Diese Variante bringt die CleanFrame-Oberfläche als eigene App auf einen Fire TV Stick mit **Fire OS** (Android-basiert): Stick 4K, Stick 4K Max, Cube und die meisten Fernseher mit Fire TV. Die Oberfläche ist dieselbe wie auf dem Samsung (`app/`); nur der Geräteteil ist neu.

**Neu gegenüber der Samsung-Version**

- Deutsche Oberfläche (folgt automatisch der Fire-TV-Sprache, umschaltbar), 24-Stunden-Uhr
- Datum und aktuelles Wetter unter der Uhr (Ort wählbar, Daten von Open-Meteo, kein Konto nötig)
- Neue Einstellungsgruppe **Uhr, Wetter & Sprache**
- Erkennung deutscher Dienste (ARD Mediathek, ZDFmediathek, Joyn, RTL+, WOW, waipu.tv, MagentaTV, DAZN, ARTE …); beim ersten Start landen die installierten Dienste automatisch auf der Startseite
- Monochrome Logos für ZDF, RTL+, DAZN, Paramount+, Plex, Kodi, Jellyfin, Crunchyroll und Twitch; andere Apps zeigen ihr eigenes Fire-TV-Banner
- Die Menü-Taste (☰) öffnet die Einstellungen, Zurück funktioniert wie gewohnt
- **Smart Home** oben neben „Alle Apps“: öffnet Amazons Smart-Home-Dashboard des Fire TV
- **AirPlay eingebaut**, ohne Zusatz-App: Der Fire TV erscheint auf iPhone, iPad und Mac unter *Bildschirmsynchronisierung* wie ein Apple TV oder ein Samsung-/Sony-Fernseher. Bildschirm spiegeln mit Ton, Musik abspielen. Der Knopf **AirPlay** zwischen „Smart Home“ und „Einstellungen“ zeigt Status, Name und Code-Schutz
- **Eigene Fotos** als Hintergrund: vom Handy per QR-Code hochladen, Bildausschnitt wählen, auf dem Fernseher verwenden oder entfernen

**Grenzen, bitte vorher lesen**

- **Nicht für Vega OS.** Der Fire TV Stick 4K Select und neuere Sticks laufen mit Vega OS; dort lassen sich keine Android-Apps mehr seitlich installieren. Nachsehen: *Einstellungen → Mein Fire TV → Info*. Steht dort „Fire OS 7“ oder „Fire OS 8“, passt es.
- **Amazon erlaubt keinen echten Ersatz des Startbildschirms.** Die Home-Taste wird mit der Open-Source-App [Home on Fire](https://github.com/toolicious/home-on-fire) auf CleanFrame umgeleitet. Dabei blitzt Amazons Startseite bei jedem Druck kurz auf (unter Fire OS 8 etwa eine Viertelsekunde). Ein Fire-OS-Update kann das jederzeit wieder blockieren.
- Getestet ist hier die Oberfläche im Browser (inklusive simulierter Fire-TV-Brücke) und der Android-Code per Kompilierung. Auf einem echten Stick musst du das Ergebnis selbst ansehen. Nutzung auf eigenes Risiko.

## 1. APK bauen (ohne eigenen Computer-Aufwand)

Die APK baut GitHub für dich, in deinem eigenen Fork.

1. Auf [github.com/nthnclrk/CleanFrame](https://github.com/nthnclrk/CleanFrame) oben rechts **Fork** wählen.
2. Die Fire-TV-Änderungen in deinen Fork übernehmen (Branch hochladen oder per Pull Request).
3. Im Fork den Tab **Actions** öffnen und Workflows einmalig erlauben („I understand my workflows, go ahead and enable them“).
4. Optional, aber empfohlen: einen festen Signierschlüssel hinterlegen (siehe [Updates](#updates-und-signierschlüssel)). Ohne ihn musst du vor jedem Update die alte App deinstallieren, und deine Einstellungen gehen dabei verloren.
5. **Actions → Fire TV APK → Run workflow**. Nach etwa fünf Minuten liegt die Datei unter **Releases → firetv-latest** bereit:

   `https://github.com/DEIN-NAME/CleanFrame/releases/download/firetv-latest/CleanFrame-FireTV.apk`

   Jeder neue Commit auf `main`, der `app/` oder `android/` ändert, baut die APK automatisch neu, und die Adresse bleibt gleich.

Lieber lokal? Ordner `android/` in Android Studio öffnen und **Build → Build APK(s)** wählen, oder im Terminal `cd android && ./gradlew assembleRelease` (Java 17 und Android-SDK nötig).

## 2. Auf dem Fire TV installieren

1. Entwickleroptionen freischalten: *Einstellungen → Mein Fire TV → Info*, dann den Gerätenamen siebenmal anklicken.
2. Aus dem Appstore die App **Downloader** installieren.
3. *Einstellungen → Mein Fire TV → Entwickleroptionen → Apps unbekannter Herkunft installieren* → **Downloader** einschalten.
4. In Downloader die Adresse von oben eingeben (Tipp: vorher am Handy mit einem Link-Kürzer kürzen), herunterladen, **Installieren**.
5. CleanFrame erscheint unter *Apps*. Für den schnellen Zugriff kannst du sie dort mit der Menü-Taste nach vorne verschieben.

Mit Computer geht es auch per ADB: *Entwickleroptionen → ADB-Debugging* an, IP-Adresse unter *Mein Fire TV → Info → Netzwerk*, dann

```sh
adb connect IP-DES-FIRE-TV:5555
adb install -r CleanFrame-FireTV.apk
```

## 3. Home-Taste auf CleanFrame legen (Home on Fire)

1. Home on Fire genauso mit Downloader installieren:
   `https://github.com/toolicious/home-on-fire/releases/latest/download/home-on-fire.apk`
2. Prüfen, ob *Einstellungen → Bedienungshilfen* eine Liste mit Schaltern zeigt.
   - **Ja:** Home on Fire dort einschalten.
   - **Nein (leere Seite, bei Fire OS 8 häufig):** einmalig per ADB freigeben, danach in Home on Fire den Schalter **Accessibility service** umlegen:

     ```sh
     adb connect IP-DES-FIRE-TV:5555
     adb shell pm grant io.github.toolicious.homeonfire android.permission.WRITE_SECURE_SETTINGS
     ```
     Ohne Computer geht ADB auch vom Android-Handy aus (z. B. mit Termux und dem Paket `android-tools`).
3. In Home on Fire **Choose target app…** wählen und **CleanFrame** auswählen. Wer möchte, schaltet dort auch den Start beim Einschalten ein.
4. Home drücken: Nach kurzem Aufblitzen erscheint CleanFrame.

**Zurück zu Amazons Startseite:** Home lange drücken (oder zweimal schnell). In CleanFrame führt außerdem *Einstellungen → Fire TV → Amazon-Startseite* dorthin; ist die Umleitung aktiv, kann Home on Fire dich von dort gleich wieder zurückholen.

## Bedienung

| Taste | Wirkung |
| --- | --- |
| Steuerkreuz, OK | Navigieren und öffnen |
| Zurück | Eine Ebene zurück; auf der Startseite fragt CleanFrame, ob du zu Amazons Startseite willst |
| Menü (☰) | Einstellungen öffnen |
| Home | Mit Home on Fire: zurück zu CleanFrame (Startseite, egal wo du warst) |

Unter **Einstellungen → Apps auf der Startseite** wählst und sortierst du bis zu neun Apps: App markieren, **OK** hebt sie an, mit **↑/↓** verschieben, **OK** setzt sie ab (**Zurück** bricht ab). Rechts neben einer App steht **Entfernen**; unten stehen alle weiteren installierten Apps zum Hinzufügen. **Alle Apps** zeigt alles Installierte.

## Smart Home

Der Knopf **Smart Home** öffnet die Smart-Home-Ansicht, die Fire OS selbst mitbringt: Licht (an/aus, Helligkeit), Steckdosen, Thermostate und Kamerabilder aller Geräte deines Amazon-Kontos. Mit **Zurück** bist du wieder in CleanFrame.

Amazon dokumentiert nicht, wie eine App diese Ansicht öffnet. CleanFrame sucht deshalb auf dem Gerät nach der Dashboard-App (auf Fire OS 7 heißt sie „Alexa Smart Home“). Findet es keine, erscheint stattdessen der Hinweis: Alexa-Taste gedrückt halten und „Alexa, zeig mein Smart Home“ sagen. Eine eigene Geräteliste direkt in CleanFrame gibt es nicht, weil Amazon dafür keine offizielle Schnittstelle anbietet.

## AirPlay: iPhone-Bildschirm spiegeln

CleanFrame bringt einen eigenen AirPlay-Empfänger mit, wie ihn Samsung-, Sony- oder LG-Fernseher eingebaut haben. Er startet mit dem Fire TV und läuft im Hintergrund, auch wenn gerade Netflix oder eine andere App offen ist. Eine Empfänger-App aus dem Appstore ist nicht nötig.

**Spiegeln**

1. Am iPhone oder iPad das Kontrollzentrum öffnen (von oben rechts nach unten wischen) → **Bildschirmsynchronisierung** → den Fire TV wählen (Standardname: der Name deines Fire TV, z. B. „Jasons Fire TV“). Am Mac: Kontrollzentrum → Bildschirmsynchronisierung.
2. Der Fernseher zeigt das iPhone-Bild im Vollbild, mit Ton. Dreht man das iPhone, dreht das Bild mit.
3. Beenden: am iPhone **Synchronisierung beenden** oder am Fernseher **Zurück** drücken.

**Musik:** In Musik, Spotify, Podcasts & Co. auf das AirPlay-Symbol tippen und den Fire TV wählen. Der Ton (verlustfrei, Apple Lossless) läuft über den Fernseher.

**Der Knopf AirPlay in CleanFrame** öffnet eine Übersicht:

- **AirPlay-Empfänger An/Aus** – aus heißt: Der Fire TV taucht auf keinem iPhone mehr auf.
- **Name auf dem iPhone** – der eigene Fire-TV-Name, „Fire TV“ oder ein Raum (Wohnzimmer, Schlafzimmer, Küche …).
- **Code: Ohne Code / Mit Code** – mit Code zeigt der Fernseher beim Verbinden vier Ziffern, die man am iPhone eingibt. Sinnvoll, wenn Nachbarn, Gäste oder Mitbewohner dasselbe WLAN nutzen.
- Während ein iPhone verbunden ist: **Anzeigen** (holt das gespiegelte Bild nach vorn) und **Trennen**.
- Rechts stehen der aktuelle Zustand und eine kurze Anleitung.

**Gut zu wissen**

- iPhone und Fire TV müssen im selben WLAN sein (kein Gäste-WLAN, kein VPN am iPhone). Das Bild hat eine kleine Verzögerung; für Fotos, Videos, Präsentationen, Webseiten und Apps passt das, für schnelle Spiele weniger. Ton und Bild können minimal versetzt sein.
- Geschützte Inhalte aus Netflix, Disney+, Apple TV & Co. bleiben beim Spiegeln schwarz (das macht das iPhone so, auch bei echten Apple TVs). Diese Apps öffnest du direkt auf dem Fire TV.
- **Spiegeln, während eine andere App läuft:** Fire OS 7 öffnet das Bild sofort. Fire OS 8 erlaubt Apps im Hintergrund nicht, sich nach vorn zu holen; dort erscheint ein kurzer Hinweis „… spiegelt gerade. Drücke Home, um es zu sehen.“ Ein Druck auf Home (CleanFrame) zeigt das Bild dann sofort, ohne Neustart der Spiegelung. Wer das Bild immer automatisch sehen möchte, erlaubt es einmalig per ADB vom Computer: `adb shell appops set com.cleanframe.firetv SYSTEM_ALERT_WINDOW allow`.
- Unterstützt wird AirPlay wie bei einem Apple TV 3 („AirPlay 1“): Bildschirmsynchronisierung bis 1080p mit 30 Bildern/s und Audio-Streaming. Nicht dabei sind AirPlay-Video aus einzelnen Apps („nur das Video senden, iPhone frei bedienen“) und Mehrraum-Audio (AirPlay 2); solche Apps spiegeln stattdessen den ganzen Bildschirm oder bieten den Fire TV nicht an.
- Ein echtes iPhone stand mir zum Ausprobieren nicht zur Verfügung. Geprüft ist die Technik gegen ein simuliertes iPhone (siehe *Technik in Kürze*). Falls dein iPhone den Fire TV zwar sieht, aber nicht verbindet, hilft die Ausgabe von `adb logcat -s CleanFrameAirPlay` bei der Fehlersuche.

## Eigene Fotos als Hintergrund

1. *Einstellungen → Hintergrund → Eigene Fotos* öffnen. Der Fernseher zeigt einen QR-Code, eine Adresse und einen vierstelligen Code.
2. Mit dem Handy (im selben WLAN) den QR-Code scannen oder die Adresse im Browser öffnen.
3. **Fotos auswählen**, bei Hoch- oder Panoramafotos mit dem Schieberegler den Bildausschnitt wählen, **Auf den Fernseher senden**.
4. Der Fernseher bereitet jedes Foto einmalig vor (Glas- und Unschärfe-Varianten wie bei den mitgelieferten Bildern, einige Sekunden pro Foto). Danach steht es unter *Eigene Fotos* und am Ende der Liste *Hintergrundbild*; es läuft auch beim automatischen Wechsel mit.
5. Ein eigenes Foto auswählen und **OK** drücken: **Als Hintergrund** oder **Entfernen**. Entfernen geht auch am Handy.

Bis zu acht eigene Fotos passen auf den Fernseher. Die Upload-Seite läuft nur, solange *Eigene Fotos* am Fernseher geöffnet ist (spätestens nach 15 Minuten ohne Aktivität endet sie), und sie nimmt nur Anfragen mit dem angezeigten Code an. Nach 20 falschen Codes schaltet sie sich ab. Die Fotos liegen im Speicher der App; beim Deinstallieren werden sie gelöscht, bei Updates mit demselben Signierschlüssel bleiben sie erhalten.

## Anpassen

| Was | Wo |
| --- | --- |
| Wetter-Orte (eigener Wohnort) | `app/assets/places.js`: Name und Koordinaten eintragen, etwa sechs Einträge passen auf den Bildschirm. Der erste ist die Voreinstellung. |
| Welche Apps erkannt und zuerst auf die Startseite gelegt werden | `BRANDS` in `app/device-firetv.js` (Reihenfolge = Vorrang; `label` erkennt die App am Namen) |
| Texte und Übersetzungen | `app/assets/strings.js` |
| Neue Logos | weißes SVG nach `app/assets/icons/<id>.svg`, dann `node scripts/build-icon-metrics.mjs` und `mark: true` in `BRANDS` |

Die Browser-Vorschau zeigt Änderungen sofort (`npm run dev:public`). `npm run check:firetv` prüft die Fire-TV-Oberfläche mit einer simulierten Android-Brücke und legt Screenshots in `.local/qa/firetv/` ab.

## Updates und Signierschlüssel

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte App. Für dauerhafte Updates einmalig einen eigenen Schlüssel anlegen:

```sh
keytool -genkeypair -keystore cleanframe.keystore -storetype PKCS12 -alias cleanframe \
  -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=CleanFrame"
base64 -w0 cleanframe.keystore > cleanframe.keystore.b64   # macOS: base64 -i cleanframe.keystore
```

Dann im Fork unter *Settings → Secrets and variables → Actions* zwei Secrets anlegen:

- `CF_KEYSTORE_BASE64`: Inhalt von `cleanframe.keystore.b64`
- `CF_KEYSTORE_PASSWORD`: das gewählte Passwort

Den Schlüssel selbst sicher aufbewahren und **nicht** ins Repository legen (`.gitignore` schließt `*.keystore` bereits aus). Wechselst du später den Schlüssel, musst du die App einmal deinstallieren.

## Datenschutz

CleanFrame nutzt das Internet nur für das Wetter: Es fragt bei Open-Meteo die aktuellen Werte für die Koordinaten des gewählten Orts ab. Mit *Wetter → Aus* entfällt auch das. App-Liste, Einstellungen, Banner und eigene Fotos bleiben auf dem Gerät. Die Foto-Upload-Seite ist nur im Heimnetz erreichbar und nur, solange *Eigene Fotos* geöffnet ist. AirPlay läuft direkt zwischen iPhone und Fire TV im Heimnetz, verschlüsselt wie bei einem Apple TV; nichts davon geht ins Internet. Der Empfänger ist erreichbar, solange AirPlay eingeschaltet ist. Mit *Code: Mit Code* kann sich nur verbinden, wer den Code auf dem Fernseher sieht.

## Fehlerbehebung

- **Eine App fehlt in „Alle Apps“:** Nur Apps mit einem Starteintrag erscheinen. Fire-OS-Systembildschirme sind bewusst ausgeblendet (`HIDDEN` in `app/device-firetv.js`).
- **„Diese App ist nicht mehr installiert“:** Die App wurde deinstalliert. Neu installieren oder unter *Apps auf der Startseite* entfernen.
- **Smart Home zeigt nur den Sprach-Hinweis:** Auf diesem Gerät ließ sich das Dashboard nicht direkt öffnen. Der Sprachbefehl funktioniert trotzdem. Wer ADB nutzt, findet mit `adb shell pm list packages | grep -i smart` den Paketnamen; dann lässt er sich in `android/app/src/main/java/com/cleanframe/firetv/SmartHome.java` eintragen.
- **iPhone findet den Fire TV nicht:** In CleanFrame **AirPlay** öffnen: Steht dort „Bereit für AirPlay“? Beide Geräte müssen im selben WLAN sein; manche Router trennen WLAN-Geräte voneinander („Client Isolation“, „AP-Isolierung“) – das muss aus sein. Hilft das nicht, AirPlay einmal aus- und wieder einschalten.
- **Bild bleibt schwarz, Ton läuft:** Die App am iPhone ist kopiergeschützt (Netflix, Disney+ …). Das Video direkt am Fire TV öffnen.
- **Der Fernseher zeigt nichts, obwohl das iPhone spiegelt:** Unter Fire OS 8 einmal Home drücken (siehe oben) oder in CleanFrame **AirPlay → Anzeigen** wählen.
- **Handy erreicht die Upload-Seite nicht:** Handy und Fernseher müssen im selben WLAN sein (kein Gäste-WLAN, kein VPN am Handy). *Eigene Fotos* am Fernseher geöffnet lassen.
- **Home-Taste führt wieder zu Amazon:** Nach Fire-OS-Updates den Bedienungshilfe-Schalter in Home on Fire prüfen; ein Update kann die Umleitung auch dauerhaft verhindern.
- **Darstellung prüfen am Computer:** Debug-Builds lassen sich per ADB in Chrome unter `chrome://inspect` untersuchen.

## Technik in Kürze

`android/` ist eine kleine Android-App ohne Bibliotheken: ein Vollbild-WebView lädt `app/` unter `https://appassets.androidplatform.net/assets/`, damit Speicherung und Schriften wie im Browser funktionieren. `window.CleanFrameAndroid` (`Bridge.java`) liefert die installierten Apps, startet sie und öffnet Fire-TV-Einstellungen; Banner kommen über `/appicon/<paket>.png`. Zurück und Menü erreicht die Seite über `window.CleanFrameRemote`. `app/device-firetv.js` übernimmt nur, wenn diese Brücke existiert; auf dem Samsung bleibt `app/device.js` zuständig.

Smart Home: `SmartHome.java` sucht die Dashboard-App (dafür braucht die App die Berechtigung `QUERY_ALL_PACKAGES`, die für seitlich installierte Apps unproblematisch ist). Eigene Fotos: `PhotoServer.java` ist ein kleiner Webserver ohne Bibliotheken (Port 8765, Code-Schutz, Größen- und Anzahlgrenzen), `PhotoStore.java` legt die Dateien im App-Speicher ab, die Handy-Seite liegt in `android/app/src/main/assets/phone/`. Die Vorbereitung der Bilder erledigt `app/photos.js` im Fernseher mit demselben Rezept wie `scripts/build-materials.mjs`; die Feldvarianten sind pixelgleich mit dem Werkzeug. Den QR-Code zeichnet `app/assets/vendor/qrcode.js` (MIT).

AirPlay: Das Paket `android/app/src/main/java/com/cleanframe/firetv/airplay/` ist ein eigenständiger AirPlay-Empfänger ohne Bibliotheken, nach dem Vorbild des Open-Source-Projekts [UxPlay](https://github.com/FDH2/UxPlay) (GPL-3.0). Er meldet sich über Androids `NsdManager` als `_airplay._tcp` und `_raop._tcp` (Port 7000) im WLAN an und spricht das RTSP-Protokoll eines Apple TV 3: Kopplung mit Ed25519/X25519 (`Curve25519.java`, reines Java, weil Fire OS diese Verfahren nicht mitbringt), FairPlay-Schlüsselaustausch (`Playfair.java`/`FairPlay.java`, Tabellen in `assets/airplay/playfair.bin`, erzeugt mit `scripts/airplay/dump_tables.c`), binäre Plists (`BPlist.java`). Das Bild (H.264, AES-CTR) entschlüsselt `MirrorReceiver.java`; `VideoDecoder.java` dekodiert es in Hardware über `MediaCodec`, zuerst unsichtbar, dann auf der Fläche von `MirrorActivity`, sobald sie offen ist. Den Ton (AES-CBC über RTP/UDP) liefert `AudioReceiver.java` an `AudioPlayer.java`: AAC-ELD beim Spiegeln über Androids AAC-Decoder, Apple Lossless für Musik über `AlacDecoder.java`. `AirPlayService` hält den Empfänger im Hintergrund (Vordergrunddienst, startet mit dem Gerät über `BootReceiver`), `AirPlayHub` verbindet alles mit der Oberfläche (`airplay*`-Methoden in `Bridge.java`, Ereignisse über `CleanFrameRemote.airplayEvent`).

`bash scripts/airplay/test.sh` prüft den Empfänger am Computer (JDK 15+, optional `ffmpeg` und `python3`); GitHub Actions führt es vor jedem APK-Build aus. Die Tests vergleichen FairPlay mit Vektoren aus dem C-Original, Ed25519/X25519 mit RFC-Vektoren und dem JDK, Plists mit Python, Apple Lossless Sample für Sample mit FFmpeg und spielen eine komplette Sitzung mit einem simulierten iPhone durch (Kopplung, FairPlay, verschlüsselter H.264-Stream, dreifach gesendeter Ton mit Nachforderung, Lautstärke, Code, Übernahme durch ein zweites Gerät).

**Lizenz:** Der AirPlay-Teil steht unter der GPL-3.0-or-later (wie UxPlay, von dem er abgeleitet ist). Deshalb gilt für die gebaute Fire-TV-APK als Ganzes die GPL-3.0; der übrige CleanFrame-Code bleibt MIT. Details in `THIRD_PARTY_NOTICES.md`.
