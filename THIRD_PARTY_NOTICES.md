# Third-party notices

The root MIT licence covers CleanFrame's original code and documentation. Third-party material retains the terms below. Keep these notices and the adjacent licence texts when sharing source or a build.

## Fonts

| Material | Terms and source record |
| --- | --- |
| Manrope | SIL Open Font License 1.1; `app/assets/manrope-OFL.txt` |
| Geist and Newsreader | SIL Open Font License 1.1; licences beside the files in `app/assets/fonts/`, with exact sources and hashes in `app/assets/fonts/SOURCES.json` |
| Optional PP Neue Montreal, PP Mori and PP Pangram Sans | Proprietary. Binaries stay in ignored `.local/` storage and are excluded from Git and public exports. The optional local overlay requires the user's own permission to use and embed those files. |

The public app uses Manrope, Geist and Newsreader. A personal widget containing proprietary fonts must stay private. See [Pangram's licensing information](https://pangrampangram.com/pages/eula).

## Icons and trademarks

| Material | Provenance and terms |
| --- | --- |
| Simple Icons service marks | CC0; exact revisions and adaptations in `app/assets/icons/SOURCES.json` and `ADDITIONAL-SOURCES.json`. The CC0 text is retained beside the icons. |
| Disney+, ABC and SBS marks | [Custom Brand Icons](https://github.com/elax46/custom-brand-icons), MIT. The retained licence is `app/assets/icons/CUSTOM-BRAND-ICONS-LICENSE.txt`. ABC and SBS sources were replaced during the public-source review. Exact revisions, source files and extraction/recolour notes are in `SOURCES.json`. |
| Art, apps, settings and home outlines | Original CleanFrame drawings, covered by the project's MIT licence. |
| Weather glyphs (sun, moon, cloud, fog, rain, snow, storm) | Original CleanFrame drawings on the same 24-unit grid, covered by the project's MIT licence. |

Brand marks identify services. Their owners retain trademark rights; this project claims no endorsement or affiliation. The older Australian TV icon archive's GPL text is retained for historical provenance only; no current icon uses that archive.

## Photographs and derived materials

The fifteen photo pages were individually checked on 20 October 2026. Each displayed its credited author and free use under the [Unsplash License](https://unsplash.com/license). The [asset source register](docs/asset-sources.md) links every page. Runtime paths are in `app/assets/wallpapers/SOURCES.json`.

Photographers retain their rights. Wallpapers, thumbnails, panel backgrounds and material fields derive from the credited photographs and retain their terms. The Unsplash licence permits copying, modification and distribution subject to its restrictions, including on selling unmodified images and building a competing image service.

## Weather data

When a weather place is chosen (the Fire TV default), CleanFrame requests current conditions for that place's coordinates from [Open-Meteo](https://open-meteo.com/). No account or key is used; no other data is sent. Open-Meteo's data is provided under [CC BY 4.0](https://open-meteo.com/en/license); the Weather place screen credits it. Open-Meteo's free API is for non-commercial use.

## Fire TV app

`android/` contains original CleanFrame code (MIT) and the Gradle wrapper (Apache License 2.0, from the official Gradle distribution). The Own photos screen draws its QR code with [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) by Kazuhiko Arase (MIT), vendored unchanged as `app/assets/vendor/qrcode.js` with its licence beside it; "QR Code" is a registered trademark of DENSO WAVE INCORPORATED. Photos people add from their phone stay on their device and are not part of this project. AirPlay is a trademark of Apple Inc.; this project is not affiliated with or endorsed by Apple. The AirPlay control uses an original screen-and-phone outline, not Apple's AirPlay symbol. The APK bundles a copy of `app/`, so the notices above apply to it too. App banners and icons of other installed apps are read from the device at run time and are not part of this project.

## AirPlay receiver (Fire TV app) — GPL-3.0-or-later

`android/app/src/main/java/com/cleanframe/firetv/airplay/`, `android/app/src/main/assets/airplay/` and `scripts/airplay/` implement an AirPlay screen-mirroring and audio receiver. They are a Java port and adaptation of [UxPlay](https://github.com/FDH2/UxPlay) (GPL-3.0; F. Duncanh and contributors), which builds on [RPiPlay](https://github.com/FD-/RPiPlay) (GPL-3.0; dsafa22, Florian Draschbacher) and [shairplay](https://github.com/juhovh/shairplay) (LGPL-2.1-or-later; Juho Vähä-Herttua, Jaslo Ziska and others). The FairPlay key exchange (`Playfair.java`, `FairPlay.java` and the tables in `assets/airplay/playfair.bin`, generated from UxPlay's `lib/playfair/omg_hax.h` by `scripts/airplay/dump_tables.c`) is a port of the playfair code distributed with those projects under the GPL-3.0. The Apple Lossless decoder (`AlacDecoder.java`) follows the published ALAC algorithm as in Apple's open-source ALAC decoder (Apache License 2.0) and FFmpeg's `alac.c` (LGPL-2.1-or-later); it contains no copied code from either. Ed25519/X25519 (`Curve25519.java`) follow RFC 8032 and RFC 7748.

These files are licensed under the GNU General Public License, version 3 or (at your option) any later version; the text is in `LICENSES/GPL-3.0-or-later.txt` and is shipped inside the APK as `assets/airplay/COPYING.txt`. Because the APK combines them with CleanFrame's MIT code, **the Fire TV APK as a whole is distributed under the GPL-3.0**: whoever passes the APK on must also offer the corresponding source (this repository at the same revision). CleanFrame's own files outside these paths remain available under MIT. `scripts/airplay/fixtures/` holds test data generated from the playfair C code, an FFmpeg-encoded test picture and a Python-written property list.

## Development dependencies and exports

Development dependencies retain the licences in their npm packages. They are not copied into the TV JavaScript runtime.

`npm run export:public` excludes private fonts and includes this notice and the root MIT licence. Preserve the included font and icon licence texts. Review [public release preparation](docs/public-release.md) before publishing a repository or download.
