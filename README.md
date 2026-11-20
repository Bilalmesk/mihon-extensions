# Mihon / Tachiyomi Arabic extensions

Three Arabic manga sources packaged as Mihon/Tachiyomi extensions:

| Source | Website | Package |
| --- | --- | --- |
| UniManga | https://unimanga.net | `eu.kanade.tachiyomi.extension.ar.unimanga` |
| MangaArabi | https://mangarabi.com | `eu.kanade.tachiyomi.extension.ar.mangarabi` |
| MangaCool | https://mangacool.net | `eu.kanade.tachiyomi.extension.ar.mangacool` |

All sources use extension API `1.6` (Mihon 0.19+/Tachiyomi 1.6 style suspend API).

## Install from the repository

1. Open **Mihon → Settings → Browse → Extension repos**.
2. Add this URL:

   ```
   https://raw.githubusercontent.com/Bilalmesk/mihon-extensions/repo/index.min.json
   ```

3. Open **Browse → Extensions**, refresh, and install the three sources.

Direct APKs are in the `repo` branch under `apk/`.

## Build

Requirements:

- JDK 17
- Android SDK with platform 35 and build-tools 35
- Gradle 8.14.3 (the wrapper is not committed)

```bash
gradle -g /path/to/gradle-home \
  :extensions:unimanga:assembleRelease \
  :extensions:mangarabi:assembleRelease \
  :extensions:mangacool:assembleRelease
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
- `apk/<package>-v1.6.1.apk`
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
