# Mihon / Tachiyomi Arabic extensions

Five Arabic manga sources packaged as Mihon/Tachiyomi extensions:

| Source | Website | Package |
| --- | --- | --- |
| UniManga | https://unimanga.net | `eu.kanade.tachiyomi.extension.ar.unimanga` |
| MangaArabi | https://mangarabi.com | `eu.kanade.tachiyomi.extension.ar.mangarabi` |
| MangaArabia | https://mangarabia.com | `eu.kanade.tachiyomi.extension.ar.mangarabia` |
| MangaCool | https://mangacool.net | `eu.kanade.tachiyomi.extension.ar.mangacool` |
| ProChan | https://procomic.net | `eu.kanade.tachiyomi.extension.ar.prochan` |

All sources use extension API `1.6` (Mihon 0.19+/Tachiyomi 1.6 style suspend API).

## Install from the repository

1. Open **Mihon → Settings → Browse → Extension repos**.
2. Add this URL:

   ```
   https://raw.githubusercontent.com/Bilalmesk/mihon-extensions/repo/index.min.json
   ```

3. Open **Browse → Extensions**, refresh, and install the five sources.

Direct APKs are in the `repo` branch under `apk/`.

> **Signing key:** 1.6.4 is signed with the original key. If you installed
> 1.6.3, uninstall it first because it used a temporary key. Users on 1.6.2 or
> earlier can update normally.

> **ProChan package change:** this repo no longer ships the old
> `eu.kanade.tachiyomi.extension.ar.procomic` package. If you installed the
> old Manga Pro/ProChan or Keiyoushi ProComic extension, uninstall or disable
> it before installing ProChan, so Mihon does not show two sources for the
> same site.

## Changelog

### 1.6.12

- ProChan: start the exact-page WebView fallback when the source is created,
  off the main thread. This keeps already-cached loopback page URLs working
  after reopening Mihon.

### 1.6.11

- ProChan: fixed AVIF page decoding in Mihon. The host decoder's factory is
  on `tachiyomi.decoder.ImageDecoder.Companion` (field `Companion`), not on
  `INSTANCE` or the outer class. All known signatures are still probed.
- ProChan: `Open in WebView` on a failed page now shows that exact page.
  The website reader has no page URL parameter, so protected pages are served
  by a loopback-only fallback page that uses the same renderer.
- ProChan: decoder errors now include the platform and BitmapFactory
  fallback failures too.

### 1.6.10

- ProChan: fixed Mihon showing a permanent pending update. Mihon derives the
  extension-lib version from the first two parts of the version name, so
  `1.7.x` was read as ext-lib `1.7` while the APK declares `1.6`. That made
  every installed copy look out of date forever. Version names now stay on
  `1.6.x`; versionCode 9 replaces 1.7.2. This is a version-name correction,
  not a downgrade.

### 1.7.2

- ProChan: include HTTP error bodies in page/deferred errors, and pause
  briefly before retrying after a 403 so batch downloads do not hit the
  reader's short rate limit.

### 1.7.1

- ProChan: use the reader page's own media token before the Android token,
  and only refresh the Android token if the page token fails. This reduces
  HTTP 403 during downloads.
- ProChan: probe all Mihon/Tachiyomi host-decoder signatures and include the
  failing decoder names in the error if decoding still fails.

### 1.7.0

- ProChan: fixed the host decoder reflection for Mihon. Mihon's
  `tachiyomi.decoder.ImageDecoder` exposes its factory on a companion object
  with extra default arguments; the previous call used the wrong signature.
  This is the decoder that actually decodes AVIF on Samsung/Android 14.

### 1.6.9

- ProChan: added Tachiyomi/Mihon HDR frame decoding fallbacks, platform
  software/hardware AVIF attempts, and a real chapter URL for
  `Open in WebView` instead of the internal `__map__` URL.

### 1.6.8

- ProChan: decode protected AVIF pieces with Mihon's bundled image decoder
  first, fixing Samsung/Android 14 devices where the platform AVIF decoder
  fails. Platform AVIF/BitmapFactory remain as fallbacks.

### 1.6.7

- ProChan: use the series CDN path (`cdn2`/`cdn3`) when preparing protected
  pages. This fixes `Piece HTTP 404` and only the first 3 pages loading on
  manhua/manhwa titles.

### 1.6.6

- ProChan: mark coin/shortlink-locked chapters as `(مقفل)` and show a clear
  "chapter locked" message instead of a raw HTTP 403. Also fixed chapter
  titles that rendered as `null`.

### 1.6.5

- Added ProChan (`procomic.net` / reader `procomic.pro`), the new home of
  the old ProChan source. The source uses the app API and reconstructs
  protected reader pages from the server's image pieces. It is packaged
  as `...prochan` to avoid colliding with the separate ProComic extension
  in the Keiyoushi repository.

### 1.6.4

- Re-signed all four extensions with the original signing key.
- Includes the MangaArabi full-page reader fix and the MangaArabia extension.

### 1.6.3

- MangaArabi: load the complete reader page list. The site only
  server-renders the first 4 pages, so chapters used to stop at page 4.
- Added MangaArabia (`mangarabia.com`), a separate Arabic manhwa catalog.
- Signed with a temporary key (superseded by 1.6.4).

## Build

Requirements:

- JDK 17
- Android SDK with platform 35 and build-tools 35
- Gradle 8.14.3 (the wrapper is not committed)

```bash
gradle -g /path/to/gradle-home \
  :extensions:unimanga:assembleRelease \
  :extensions:mangarabi:assembleRelease \
  :extensions:mangarabia:assembleRelease \
  :extensions:mangacool:assembleRelease \
  :extensions:prochan:assembleRelease
```

Create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=mihon.keystore
storePassword=...
keyAlias=mihon
keyPassword=...
```

Then package, align, sign, and generate the legacy repository files:

```bash
python scripts/package_repo.py
```

The result is written to `dist/`:

- `index.min.json`
- `repo.json`
- `apk/<package>-v<version>.apk`
- `icon/<package>.png`

## Publish the repository branch

The `repo` branch is an orphan branch containing only the repository files:

```bash
git checkout --orphan repo
cp -r dist/* .
git add apk icon index.min.json repo.json
git commit -m "Update extension repository"
git push origin repo
```

`repo.json` contains the SHA-256 fingerprint of the signing certificate. Keep
`mihon.keystore` and `keystore.properties` safe: every future update must be
signed with the same key, otherwise Mihon will treat the extension as untrusted.

## Notes

- MangaCool hides reader pages behind a short-lived token and a client-side
  proof. The source reads the token from the reader page, computes the proof,
  and calls the same page endpoint the website uses.
- UniManga's detail page renders the first 50 chapters; the rest are loaded
  from `/titles/<slug>/chapter-rows`.
- MangaArabi and MangaCool use their public JSON APIs for lists, search, and
  details.
- ProChan's API requires Android-app request signatures. The source
  registers a per-process device key automatically and signs its API calls.
  Reader pages are AVIF; they are decoded and composited (Android 12+ for
  AVIF support).
