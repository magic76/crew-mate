package com.crewpocket.mate.channel;

/** Local in-person participant resolver used by Crew Mate's physical handoff flow. */
public interface MessagingBackend {
    final class Contact {
        public final String id;
        public final String displayName;

        public Contact(String id, String displayName) {
            this.id = id == null ? "" : id;
            this.displayName = displayName == null ? "" : displayName;
        }
    }

    interface FindCallback {
        void onFound(Contact contact);
        void onError(String message);
    }

    void findContact(String query, FindCallback callback);
}
