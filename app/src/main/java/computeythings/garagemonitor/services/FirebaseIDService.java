package computeythings.garagemonitor.services;

import android.util.Log;

import com.google.firebase.iid.FirebaseInstanceIdService;
import com.google.firebase.messaging.FirebaseMessaging;

import computeythings.garagemonitor.preferences.ServerPreferences;

public class FirebaseIDService extends FirebaseInstanceIdService {
    private static final String TAG = "InstanceIDService";
    /*
        Subscriptions refer to tokens so any time the token is refreshed, subscriptions
        should be updated
     */
    @Override
    public void onTokenRefresh() {
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();

        // subscribe to all known servers
        ServerPreferences prefs = new ServerPreferences(this);
        for (String server : prefs.getServerList()) {
            String refId = prefs.getServerInfo(server).get(ServerPreferences.SERVER_REFID);
            if (refId != null) {
                Log.e(TAG, "Subscribed to server at " + refId);
                messaging.subscribeToTopic(refId);
            }
        }
    }
}