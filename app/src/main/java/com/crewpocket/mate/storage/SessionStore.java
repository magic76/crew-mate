package com.crewpocket.mate.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Small local JSON store for in-person Crew Mate sessions. */
public final class SessionStore {
    private static final String PREFS = "crew_mate_sessions";
    private static final String KEY_SESSIONS = "sessions";
    private static final int MAX_SESSIONS = 30;

    private final SharedPreferences preferences;

    public SessionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(CommunicationSession session) {
        if (session == null) return;
        try {
            JSONArray existing = readArray();
            JSONArray next = new JSONArray();
            next.put(toJson(session));
            for (int i = 0; i < existing.length() && next.length() < MAX_SESSIONS; i++) {
                JSONObject item = existing.optJSONObject(i);
                if (item == null) continue;
                if (session.sessionId.equals(item.optString("session_id"))) continue;
                next.put(item);
            }
            preferences.edit().putString(KEY_SESSIONS, next.toString()).apply();
        } catch (Exception ignored) {}
    }

    public synchronized CommunicationSession loadLatest() {
        List<CommunicationSession> sessions = loadAll();
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    public synchronized CommunicationSession loadById(String sessionId) {
        String target = sessionId == null ? "" : sessionId.trim();
        if (target.isEmpty()) return null;
        JSONArray array = readArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && target.equals(item.optString("session_id"))) return fromJson(item);
        }
        return null;
    }

    public synchronized List<CommunicationSession> loadAll() {
        List<CommunicationSession> sessions = new ArrayList<CommunicationSession>();
        JSONArray array = readArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            CommunicationSession session = fromJson(item);
            if (session != null) sessions.add(session);
        }
        Collections.sort(sessions, new Comparator<CommunicationSession>() {
            @Override public int compare(CommunicationSession a, CommunicationSession b) {
                return Long.compare(b.updatedAt(), a.updatedAt());
            }
        });
        return sessions;
    }

    private JSONArray readArray() {
        try { return new JSONArray(preferences.getString(KEY_SESSIONS, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject toJson(CommunicationSession session) throws Exception {
        JSONObject root = new JSONObject();
        root.put("session_id", session.sessionId);
        root.put("target_person", session.targetPerson());
        root.put("target_person_id", session.targetPersonId());
        root.put("goal", session.goal());
        root.put("outcome_summary", session.outcomeSummary());
        root.put("user_language", session.userLanguage());
        root.put("other_person_language", session.otherPersonLanguage());
        root.put("user_direct_control", session.userDirectControl());
        root.put("status", session.status().name());
        root.put("pending_user_question", session.pendingUserQuestion());
        root.put("updated_at", session.updatedAt());

        JSONArray messages = new JSONArray();
        for (Message message : session.messages()) {
            JSONObject item = new JSONObject();
            item.put("id", message.id);
            item.put("sender", message.sender.name());
            item.put("recipient", message.recipient);
            item.put("content", message.content());
            item.put("timestamp", message.timestamp);
            item.put("status", message.status().name());
            messages.put(item);
        }
        root.put("messages", messages);
        return root;
    }

    private static CommunicationSession fromJson(JSONObject root) {
        try {
            CommunicationSession session = new CommunicationSession(root.optString("session_id"));
            session.setTarget(root.optString("target_person_id"), root.optString("target_person"));
            session.setGoal(root.optString("goal"));
            session.setOutcomeSummary(root.optString("outcome_summary"));
            session.setLanguages(
                    root.optString("user_language", "AUTO"),
                    root.optString("other_person_language", "AUTO"));
            session.setUserDirectControl(root.optBoolean("user_direct_control", false));
            session.setPendingUserQuestion(root.optString("pending_user_question"));

            JSONArray messages = root.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject item = messages.optJSONObject(i);
                    if (item == null) continue;
                    Message.Sender sender = enumValue(Message.Sender.class, item.optString("sender"), Message.Sender.SYSTEM);
                    Message.Status status = enumValue(Message.Status.class, item.optString("status"), Message.Status.INFO);
                    session.addMessage(new Message(
                            item.optString("id"), sender, item.optString("recipient"), item.optString("content"),
                            item.optLong("timestamp", System.currentTimeMillis()), status));
                }
            }

            CommunicationSession.Status restoredStatus = enumValue(
                    CommunicationSession.Status.class, root.optString("status"), CommunicationSession.Status.STOPPED);
            if (restoredStatus == CommunicationSession.Status.THINKING) restoredStatus = CommunicationSession.Status.STOPPED;
            session.setStatus(restoredStatus);
            session.setUpdatedAtForRestore(root.optLong("updated_at", session.updatedAt()));
            return session;
        } catch (Exception ignored) { return null; }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try { return Enum.valueOf(type, value == null ? "" : value); }
        catch (Exception ignored) { return fallback; }
    }
}
