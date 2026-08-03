# Notices

WTAK — a standalone Wear OS client for the TAK ecosystem.
Copyright 2026 NEO207. Licensed under the Apache License, Version 2.0
(see [LICENSE](LICENSE)).

## Not affiliated with TAK

WTAK is an independent implementation. It is **not** produced, endorsed by, or
affiliated with the TAK Product Center, the U.S. Government, or any of its
agencies. "ATAK", "WinTAK" and "TAK" are used only to describe the ecosystem
this app interoperates with.

It is likewise not affiliated with the Meshtastic project. "Meshtastic" is a
registered trademark of Meshtastic LLC and is used here only to name the
protocol and hardware this app talks to.

## Wire formats

The TAK and Meshtastic codecs in this repository are clean-room
implementations written against publicly published schemas. No code was copied
from either project:

- **Cursor-on-Target** and **TAK Protocol Version 1** — implemented from the
  protobuf schemas published in `AndroidTacticalAssaultKit-CIV`
  (`commoncommo/core/impl/protobuf/`), Apache License 2.0.
- **Meshtastic protobufs** — implemented from the schemas published in
  `meshtastic/protobufs`, GPL-3.0. Only the field numbering and message
  structure are used, which is interface information rather than source.

## Third-party dependencies

Fetched at build time; none are vendored into this repository.

| Component | Licence | Used for |
|---|---|---|
| [osmdroid](https://github.com/osmdroid/osmdroid) | Apache-2.0 | Map tiles, offline archives |
| [AndroidX / Jetpack Compose for Wear OS](https://developer.android.com/jetpack/androidx) | Apache-2.0 | UI, navigation, tiles, lifecycle |
| [Kotlin & kotlinx.coroutines](https://kotlinlang.org) | Apache-2.0 | Language and concurrency |
| [Bouncy Castle](https://www.bouncycastle.org/) | MIT-style | PKCS#10 CSR generation for TLS enrollment |
| [NGA MGRS](https://github.com/ngageoint/mgrs-java) | MIT | MGRS coordinate conversion |
| [Google Play services (Wearable)](https://developers.google.com/android/guides/setup) | Proprietary, redistributable | Data Layer bridge to a paired phone |

## Map data

Map tiles come from [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors, © OpenStreetMap contributors, available under the Open Database
License (ODbL). The topographic source is
[OpenTopoMap](https://opentopomap.org/), CC-BY-SA.

Tiles are fetched directly from public tile servers. If you deploy this to a
team of any size, please read and respect the
[OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
and consider an offline archive or your own tile server instead.

## Symbology

Affiliation frames follow MIL-STD-2525, a public U.S. Department of Defense
standard. They are drawn from scratch by this app; no symbol artwork is
redistributed.
