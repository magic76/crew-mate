package com.crewpocket.mate.channel;

import java.util.Locale;

/** Resolves the visible person for an in-person Gemini Live conversation. */
public final class InPersonMessagingBackend implements MessagingBackend {
    @Override
    public void findContact(String query, FindCallback callback) {
        if (callback == null) return;
        String name = query == null ? "" : query.trim();
        if (name.isEmpty()) {
            callback.onError("Person name is empty");
            return;
        }
        String id = "local-" + name.toLowerCase(Locale.US).replace(' ', '-');
        callback.onFound(new Contact(id, name));
    }
}
