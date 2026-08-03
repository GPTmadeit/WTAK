# Releasing WTAK

## The one thing you must not get wrong

**Keep the signing key forever.** Android will only install an update over an
existing app if the new APK is signed with the *same* key. Lose it and there is
no recovery path: every user has to uninstall (losing their settings, waypoints
and enrolled certificate) before they can install again.

- Back up `release.jks` **and** its password somewhere durable and separate from
  this machine — a password manager, not a folder next to the key.
- `keystore.properties` and `*.jks` are gitignored. Keep them that way.

The production keystore lives in the project root:

| | |
|---|---|
| File | `release.jks` (PKCS12) |
| Alias | `atakwatch` |
| Key | RSA 4096, SHA256withRSA |
| Validity | 10,000 days (~27 years) |
| Subject | `CN=ATAK Watch, OU=ATAK Watch, O=ATAK Watch, C=US` |

Signing uses **v2 + v3** schemes. v1 (JAR) is off — unnecessary at `minSdk 30`.
v3 is the important one: it is the scheme that supports **signing key rotation**,
so a compromised key is recoverable rather than terminal.

To regenerate (only ever before your first public release):

```
keytool -genkeypair -alias atakwatch \
  -dname "CN=Your Name, O=Your Org, C=US" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000 -keystore release.jks -storetype PKCS12

cp keystore.properties.sample keystore.properties   # then fill it in
```

### CI signing

Environment variables take precedence over `keystore.properties`, so a build
server never needs the secret on disk:

```
ATAKWATCH_STORE_FILE     ATAKWATCH_STORE_PASSWORD
ATAKWATCH_KEY_ALIAS      ATAKWATCH_KEY_PASSWORD
```

With neither the file nor the variables, `assembleRelease` still succeeds and
produces an **unsigned** APK rather than failing the build.

## Version numbers

`versionCode` must increase on every release Android sees — it is what the
platform compares. `versionName` is the human string, and the About screen reads
it from `BuildConfig`, so it can't drift from the build.

Both live in [`app/build.gradle.kts`](app/build.gradle.kts).

| | |
|---|---|
| `versionCode` | integer, strictly increasing, never reused |
| `versionName` | e.g. `1.4.1` |

## Build

```
./gradlew :app:testDebugUnitTest     # 35 tests
./gradlew :app:assembleRelease       # minified + shrunk + signed
```

Output: `app/build/outputs/apk/release/app-release.apk` (~10 MB).

Verify the signature before shipping:

```
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Upgrade safety

Persisted state that must survive an update:

| What | Where | Notes |
|---|---|---|
| Settings | DataStore `atak_settings` | Unknown/removed keys are ignored; absent keys fall back to defaults |
| Waypoints | `files/waypoints.json` | Stable schema |
| Client certificate | `files/tak_client.p12` + `tak_trust.p12` | Re-enrolment needed if lost |

**Adding a setting?** Give it a default that is correct for someone upgrading,
not just for a fresh install. The `onboarded` flag is the cautionary example: it
defaults to `false`, which would have thrown every existing user back into
first-run setup on 1.4.0. It now falls back to "any stored preference exists →
already set up", pinned by `OnboardingMigrationTest`.

**Removing a setting?** Safe — DataStore ignores keys the code no longer reads.

## Release hardening already in place

- **Backup disabled** (`allowBackup=false` + `data_extraction_rules.xml`). The
  app holds an enrolled client certificate and private key; a cloud backup or
  device transfer of that would let another device impersonate the operator.
- **Debug hooks gated to debug builds.** `MainActivity` is exported (it is the
  launcher), so in release the `--ez enable_mesh` style extras are ignored —
  otherwise any installed app could change where this device transmits.
- **R8 + resource shrinking**, with keep rules for BouncyCastle, osmdroid, NGA
  MGRS and XmlPullParser. Verified by running the *minified* build: enrolment,
  mutual TLS and mesh all work.

## Pre-flight checklist

- [ ] `versionCode` bumped, `versionName` updated
- [ ] `./gradlew :app:testDebugUnitTest` green
- [ ] `assembleRelease` succeeds and is signed with the production key
- [ ] Installed **over** the previous version without uninstalling; settings and
      waypoints intact, no first-run setup
- [ ] New settings have upgrade-correct defaults
- [ ] Smoke test: GPS fix, map renders, mesh/server connect, certificate intact
