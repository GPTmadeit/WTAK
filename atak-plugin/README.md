# WTAK Bridge — phone-side plugin

An ATAK plugin that lets a Wear OS watch running **WTAK** join an EUD's
existing TAK setup instead of being configured separately.

## What it does

1. **Onboards the watch.** Publishes the operator's identity — the values they
   already set in ATAK — over the Wearable Data Layer. The watch reads its
   callsign, team colour, role, self CoT type and server from the phone, so
   nothing has to be typed on a 1.4" screen and the two devices can't drift
   out of sync.
2. **Relays CoT.** Forwards events the phone has already received to the watch,
   so the watch shows the team picture without running its own mesh or server
   connection. On a 455 mAh watch that is a large battery saving.

Identity is read straight from ATAK's own preferences (`com.atakmap.app_preferences`)
using its documented keys, so there is no second configuration to maintain:

| ATAK preference | ATAK UI label | Used for |
|---|---|---|
| `locationCallsign` | My Callsign | Watch callsign |
| `locationTeam` | My Team | Team colour |
| `atakRoleType` | My Role | Team role |
| `locationUnitType` | My Display Type | Self CoT type → affiliation |

## Building

⚠️ **This directory does not build as part of the watch project.** ATAK plugins
compile against the **ATAK Plugin SDK**, which is distributed through
[tak.gov](https://tak.gov) after registration and is not published to Maven. The
source here is complete and ready to drop into the SDK's plugin template:

1. Register at tak.gov and download the ATAK-CIV SDK matching your ATAK version.
2. Create a plugin project from the SDK's template (`PluginTemplate`).
3. Copy `src/main/java/com/atakwatch/bridge/` into the template's source tree.
4. Add Play Services to the plugin's `build.gradle`:
   ```gradle
   implementation 'com.google.android.gms:play-services-wearable:18.2.0'
   ```
5. Declare the bridge capability so the watch can discover the phone —
   `res/values/wear.xml`:
   ```xml
   <resources>
     <string-array name="android_wear_capabilities">
       <item>atak_eud_bridge</item>
     </string-array>
   </resources>
   ```
6. Build and sign with your ATAK plugin signing key, then load it in ATAK.

## Wire protocol

Shared with the watch — the authoritative copy lives in
[`EudProtocol.kt`](../app/src/main/java/com/atakwatch/minimap/bridge/EudProtocol.kt).

| Transport | Path | Payload |
|---|---|---|
| DataClient | `/atak/identity` | JSON identity + server config (persists, re-syncs) |
| MessageClient | `/atak/cot` | One CoT event, XML or TAK protobuf |
| MessageClient | `/atak/request-sync` | Watch asks the phone to republish |

Identity JSON:

```json
{
  "callsign": "ALPHA",
  "team": "Cyan",
  "role": "Team Member",
  "cotType": "a-f-G-U-C",
  "uid": "ANDROID-…",
  "serverHost": "192.168.1.50",
  "serverPort": 8087
}
```

Every field is optional: the watch applies what it receives and leaves the rest
at its current value, so an older phone build still onboards what it can.

## ATAK APIs used

- `CotMapComponent.getInstance().addOnCotEventListener(...)` — inbound CoT the
  phone has already accepted from any input.
- `PreferenceManager.getDefaultSharedPreferences(mapView.getContext())` —
  ATAK's preference store.
- `MapView.getDeviceUid()` — the EUD's TAK UID.

## Without the plugin

The watch is a standalone TAK client and does not need this. It can join a CoT
mesh, connect to a TAK server, and enrol for a certificate on its own — the
plugin only removes the duplicate setup and saves watch battery.
