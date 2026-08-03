package com.atakwatch.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.util.Set;

/**
 * ATAK plugin: bridges an EUD running ATAK to a Wear OS watch running ATAK Watch.
 *
 * <p>Two jobs:
 * <ol>
 *   <li><b>Onboarding.</b> Publishes the operator's identity — the values they
 *       already set in ATAK — as a Data Layer item, so the watch configures
 *       itself instead of asking the user to retype a callsign on a 1.4" screen.</li>
 *   <li><b>Relay.</b> Forwards CoT the phone already receives to the watch, so
 *       the watch sees the team picture without running its own mesh/server
 *       radios. That is a large battery saving on a 455 mAh device.</li>
 * </ol>
 *
 * <p>Identity is read straight from {@code com.atakmap.app_preferences} using
 * ATAK's own keys, so there is no second configuration to keep in sync:
 * <pre>
 *   locationCallsign   My Callsign
 *   locationTeam       My Team (colour)
 *   atakRoleType       My Role
 *   locationUnitType   My Display Type (self CoT type)
 * </pre>
 *
 * <p><b>Building.</b> This needs the ATAK Plugin SDK, which is distributed
 * through <a href="https://tak.gov">tak.gov</a> after registration and is not
 * available from Maven. Drop {@code main.jar} from the SDK into {@code libs/}
 * and build with the SDK's plugin Gradle template. See {@code README.md} in
 * this directory.
 */
public class WatchBridgeMapComponent extends DropDownMapComponent {

    private static final String TAG = "WatchBridge";

    // Contract shared with the watch — keep in step with EudProtocol.kt.
    private static final String CAPABILITY = "atak_eud_bridge";
    private static final String PATH_IDENTITY = "/atak/identity";
    private static final String PATH_COT = "/atak/cot";
    private static final String PATH_REQUEST_SYNC = "/atak/request-sync";
    private static final String KEY_PAYLOAD = "payload";

    // ATAK's own preference keys.
    private static final String PREF_CALLSIGN = "locationCallsign";
    private static final String PREF_TEAM = "locationTeam";
    private static final String PREF_ROLE = "atakRoleType";
    private static final String PREF_UNIT_TYPE = "locationUnitType";

    private Context pluginContext;
    private SharedPreferences prefs;
    private MessageClient messageClient;
    private CotMapComponent.CotEventListener cotListener;

    private final MessageClient.OnMessageReceivedListener syncListener = event -> {
        if (PATH_REQUEST_SYNC.equals(event.getPath())) {
            Log.d(TAG, "watch requested a sync");
            publishIdentity();
        }
    };

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        pluginContext = context;
        super.onCreate(context, intent, view);

        prefs = PreferenceManager.getDefaultSharedPreferences(view.getContext());
        messageClient = Wearable.getMessageClient(view.getContext());
        messageClient.addListener(syncListener);

        // Republish whenever the operator changes their identity in ATAK.
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener);

        publishIdentity();
        startCotRelay(view);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener =
            (sp, key) -> {
                if (PREF_CALLSIGN.equals(key) || PREF_TEAM.equals(key)
                        || PREF_ROLE.equals(key) || PREF_UNIT_TYPE.equals(key)) {
                    publishIdentity();
                }
            };

    /**
     * Publish the operator's identity as a Data Layer item. Data items persist
     * and re-sync, so a watch that was off or out of range still picks this up
     * the moment it reconnects.
     */
    private void publishIdentity() {
        try {
            JSONObject o = new JSONObject();
            put(o, "callsign", prefs.getString(PREF_CALLSIGN, null));
            put(o, "team", prefs.getString(PREF_TEAM, null));
            put(o, "role", prefs.getString(PREF_ROLE, null));
            put(o, "cotType", prefs.getString(PREF_UNIT_TYPE, "a-f-G-U-C"));
            put(o, "uid", MapView.getDeviceUid());

            // Streaming server, if the operator has one configured.
            put(o, "serverHost", prefs.getString("takServerHost", null));
            String port = prefs.getString("takServerPort", null);
            if (port != null) o.put("serverPort", Integer.parseInt(port));

            PutDataMapRequest req = PutDataMapRequest.create(PATH_IDENTITY);
            req.getDataMap().putString(KEY_PAYLOAD, o.toString());
            // Timestamp forces a change event even when the content repeats.
            req.getDataMap().putLong("ts", System.currentTimeMillis());

            PutDataRequest put = req.asPutDataRequest().setUrgent();
            Wearable.getDataClient(pluginContext).putDataItem(put);
            Log.d(TAG, "published identity: " + o);
        } catch (Exception e) {
            Log.w(TAG, "identity publish failed", e);
        }
    }

    private static void put(JSONObject o, String key, String value) throws Exception {
        if (value != null && !value.isEmpty()) o.put(key, value);
    }

    /**
     * Relay inbound CoT to the watch. Hooks ATAK's internal dispatcher, which
     * sees every event the phone has already accepted from any input — mesh,
     * TAK server, or another plugin.
     */
    private void startCotRelay(MapView view) {
        cotListener = (event, extra) -> forwardToWatch(event);
        CotMapComponent.getInstance().addOnCotEventListener(cotListener);
    }

    private void forwardToWatch(CotEvent event) {
        if (event == null || !event.isValid()) return;
        // Only atoms and map points are worth a radio wake-up on the watch;
        // chat and tasking are filtered out.
        String type = event.getType();
        if (type == null || !(type.startsWith("a-") || type.startsWith("b-m-p"))) return;

        final byte[] payload = event.toString().getBytes();
        Wearable.getNodeClient(pluginContext).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    for (Node n : nodes) {
                        messageClient.sendMessage(n.getId(), PATH_COT, payload);
                    }
                });
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        try {
            if (cotListener != null) {
                CotMapComponent.getInstance().removeOnCotEventListener(cotListener);
            }
            if (messageClient != null) messageClient.removeListener(syncListener);
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
        } catch (Exception e) {
            Log.w(TAG, "teardown", e);
        }
        super.onDestroyImpl(context, view);
    }
}
