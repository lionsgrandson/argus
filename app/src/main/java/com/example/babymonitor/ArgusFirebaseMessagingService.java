package com.example.babymonitor;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public final class ArgusFirebaseMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) {
        super.onNewToken(token);
        WakeManager.onNewToken(this, token);
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        if (message == null) return;
        String action = message.getData().get("action");
        if (!"ARGUS_RECONNECT".equals(action)) return;
        WakeManager.handleRemoteWake(this);
    }
}
