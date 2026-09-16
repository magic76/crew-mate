package com.crewpocket.mate.channel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Telegram Bot API provider for Crew Mate.
 *
 * Telegram bots cannot initiate a private conversation with an arbitrary Telegram user.
 * The target must first open the bot and send /start (or any message). Once Crew Mate has
 * observed that update, the contact becomes resolvable by display name, @username or chat id.
 *
 * Bot API does not expose arbitrary private-chat history, so getConversation returns the
 * locally observed history cached by this provider.
 */
public final class TelegramMessagingBackend implements MessagingBackend {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String PREF_OFFSET = "offset";
    private static final String PREF_CONTACTS = "contacts";
    private static final String PREF_HISTORY = "history";
    private static final int MAX_HISTORY = 200;

    private static final class ContactRecord {
        final String id;
        String displayName;
        String username;

        ContactRecord(String id, String displayName, String username) {
            this.id = clean(id);
            this.displayName = clean(displayName);
            this.username = clean(username);
        }

        Contact asContact() { return new Contact(id, displayName); }
    }

    private static final class IncomingWatch {
        final long afterTimestamp;
        final IncomingCallback callback;

        IncomingWatch(long afterTimestamp, IncomingCallback callback) {
            this.afterTimestamp = Math.max(0L, afterTimestamp);
            this.callback = callback;
        }
    }

    private final String token;
    private final String baseUrl;
    private final SharedPreferences preferences;
    private final OkHttpClient httpClient;
    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService pollExecutor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private final Map<String, ContactRecord> contacts = new LinkedHashMap<String, ContactRecord>();
    private final Map<String, List<RemoteMessage>> history = new LinkedHashMap<String, List<RemoteMessage>>();
    private final Map<String, SendCallback> waitingReplies = new LinkedHashMap<String, SendCallback>();
    private final Map<String, IncomingWatch> incomingWatches = new LinkedHashMap<String, IncomingWatch>();

    private volatile boolean running = true;
    private volatile long nextOffset;
    private volatile String healthError = "";

    public TelegramMessagingBackend(Context context, String token) {
        this(context, token, "https://api.telegram.org");
    }

    TelegramMessagingBackend(Context context, String token, String baseUrl) {
        this.token = clean(token);
        this.baseUrl = clean(baseUrl).isEmpty() ? "https://api.telegram.org" : clean(baseUrl);
        String suffix = Integer.toHexString(this.token.hashCode());
        preferences = context.getApplicationContext().getSharedPreferences(
                "crew_mate_telegram_" + suffix, Context.MODE_PRIVATE);
        nextOffset = preferences.getLong(PREF_OFFSET, 0L);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        loadCache();
        if (!this.token.isEmpty()) {
            pollExecutor.execute(new Runnable() {
                @Override public void run() { pollLoop(); }
            });
        } else {
            healthError = "Telegram bot token is empty.";
        }
    }

    @Override
    public void findContact(final String query, final FindCallback callback) {
        if (callback == null) return;
        requestExecutor.execute(new Runnable() {
            @Override public void run() {
                ContactRecord match = findCachedContact(query);
                if (match == null && running) {
                    try { Thread.sleep(900L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    match = findCachedContact(query);
                }
                if (match != null) {
                    callback.onFound(match.asContact());
                    return;
                }
                String error = healthError;
                if (!error.isEmpty()) {
                    callback.onError(error);
                    return;
                }
                callback.onError("Telegram contact not found. Ask the person to open your Crew Mate bot and send /start, then retry.");
            }
        });
    }

    @Override
    public void getConversation(final Contact contact, final ConversationCallback callback) {
        if (callback == null) return;
        requestExecutor.execute(new Runnable() {
            @Override public void run() {
                if (contact == null || clean(contact.id).isEmpty()) {
                    callback.onError("Telegram contact is missing.");
                    return;
                }
                synchronized (lock) {
                    List<RemoteMessage> values = history.get(contact.id);
                    callback.onLoaded(values == null
                            ? Collections.<RemoteMessage>emptyList()
                            : Collections.unmodifiableList(new ArrayList<RemoteMessage>(values)));
                }
            }
        });
    }

    @Override
    public void sendMessage(final Contact contact, final String content, final SendCallback callback) {
        if (callback == null) return;
        requestExecutor.execute(new Runnable() {
            @Override public void run() {
                if (contact == null || clean(contact.id).isEmpty()) {
                    callback.onError("Telegram contact is missing.");
                    return;
                }
                String text = clean(content);
                if (text.isEmpty()) {
                    callback.onError("Telegram message is empty.");
                    return;
                }
                try {
                    JSONObject request = new JSONObject()
                            .put("chat_id", contact.id)
                            .put("text", text);
                    JSONObject response = api("sendMessage", request);
                    JSONObject result = response.optJSONObject("result");
                    if (result == null) throw new IOException("Telegram returned no sent message.");
                    String providerId = remoteId(contact.id, result.optLong("message_id", 0L));
                    long timestamp = result.optLong("date", System.currentTimeMillis() / 1000L) * 1000L;
                    RemoteMessage outgoing = new RemoteMessage(providerId, "Mate", text, timestamp, true);
                    addHistory(contact.id, outgoing);
                    synchronized (lock) { waitingReplies.put(contact.id, callback); }
                    callback.onDelivered(providerId);
                } catch (Exception error) {
                    synchronized (lock) {
                        if (waitingReplies.get(contact.id) == callback) waitingReplies.remove(contact.id);
                    }
                    callback.onError(safeError(error));
                }
            }
        });
    }

    @Override
    public void watchIncoming(final Contact contact, final long afterTimestamp, final IncomingCallback callback) {
        if (callback == null) return;
        requestExecutor.execute(new Runnable() {
            @Override public void run() {
                if (contact == null || clean(contact.id).isEmpty()) {
                    callback.onError("Telegram contact is missing.");
                    return;
                }
                RemoteMessage cached = newestIncomingAfter(contact.id, afterTimestamp);
                if (cached != null) {
                    callback.onMessage(cached);
                    return;
                }
                synchronized (lock) {
                    incomingWatches.put(contact.id, new IncomingWatch(afterTimestamp, callback));
                }
            }
        });
    }

    @Override
    public void shutdown() {
        running = false;
        synchronized (lock) {
            incomingWatches.clear();
            waitingReplies.clear();
        }
        pollExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        httpClient.dispatcher().cancelAll();
    }

    public int knownContactCount() {
        synchronized (lock) { return contacts.size(); }
    }

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                JSONObject request = new JSONObject()
                        .put("offset", nextOffset)
                        .put("timeout", 15)
                        .put("allowed_updates", new JSONArray().put("message"));
                JSONObject response = api("getUpdates", request);
                healthError = "";
                JSONArray updates = response.optJSONArray("result");
                if (updates == null) continue;
                for (int i = 0; i < updates.length(); i++) {
                    JSONObject update = updates.optJSONObject(i);
                    if (update == null) continue;
                    long updateId = update.optLong("update_id", -1L);
                    if (updateId >= 0) {
                        nextOffset = Math.max(nextOffset, updateId + 1L);
                        preferences.edit().putLong(PREF_OFFSET, nextOffset).apply();
                    }
                    ingestIncoming(update.optJSONObject("message"));
                }
            } catch (Exception error) {
                if (!running) break;
                healthError = safeError(error);
                try { Thread.sleep(1200L); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void ingestIncoming(JSONObject message) {
        if (message == null) return;
        JSONObject chat = message.optJSONObject("chat");
        if (chat == null) return;
        String chatId = String.valueOf(chat.optLong("id", 0L));
        if ("0".equals(chatId)) return;

        JSONObject from = message.optJSONObject("from");
        String username = from == null ? chat.optString("username", "") : from.optString("username", "");
        String displayName = displayName(chat, from);
        cacheContact(new ContactRecord(chatId, displayName, username));

        String text = message.optString("text", "");
        if (text.isEmpty()) text = message.optString("caption", "");
        if (text.isEmpty()) return;

        long messageId = message.optLong("message_id", 0L);
        long timestamp = message.optLong("date", System.currentTimeMillis() / 1000L) * 1000L;
        RemoteMessage incoming = new RemoteMessage(
                remoteId(chatId, messageId), displayName, text, timestamp, false);
        if (!addHistory(chatId, incoming)) return;

        SendCallback sendCallback;
        IncomingCallback incomingCallback = null;
        synchronized (lock) {
            sendCallback = waitingReplies.remove(chatId);
            IncomingWatch watch = incomingWatches.get(chatId);
            if (watch != null && incoming.timestamp > watch.afterTimestamp) {
                incomingWatches.remove(chatId);
                incomingCallback = watch.callback;
            }
        }
        if (sendCallback != null) sendCallback.onReply(incoming);
        if (incomingCallback != null) incomingCallback.onMessage(incoming);
    }

    private RemoteMessage newestIncomingAfter(String chatId, long afterTimestamp) {
        synchronized (lock) {
            List<RemoteMessage> values = history.get(chatId);
            if (values == null) return null;
            for (int i = values.size() - 1; i >= 0; i--) {
                RemoteMessage message = values.get(i);
                if (!message.outgoing && message.timestamp > afterTimestamp) return message;
            }
            return null;
        }
    }

    private ContactRecord findCachedContact(String query) {
        String needle = normalizeQuery(query);
        if (needle.isEmpty()) return null;
        synchronized (lock) {
            ContactRecord partial = null;
            for (ContactRecord contact : contacts.values()) {
                String id = normalizeQuery(contact.id);
                String username = normalizeQuery(contact.username);
                String name = normalizeQuery(contact.displayName);
                if (needle.equals(id) || needle.equals(username) || needle.equals(name)) return contact;
                if (name.contains(needle) || (!username.isEmpty() && username.contains(needle))) {
                    if (partial != null && !partial.id.equals(contact.id)) return null;
                    partial = contact;
                }
            }
            return partial;
        }
    }

    private void cacheContact(ContactRecord contact) {
        if (contact == null || contact.id.isEmpty()) return;
        synchronized (lock) {
            ContactRecord existing = contacts.get(contact.id);
            if (existing == null) contacts.put(contact.id, contact);
            else {
                if (!contact.displayName.isEmpty()) existing.displayName = contact.displayName;
                if (!contact.username.isEmpty()) existing.username = contact.username;
            }
            persistCacheLocked();
        }
    }

    private boolean addHistory(String chatId, RemoteMessage message) {
        if (message == null) return false;
        synchronized (lock) {
            List<RemoteMessage> values = history.get(chatId);
            if (values == null) {
                values = new ArrayList<RemoteMessage>();
                history.put(chatId, values);
            }
            for (RemoteMessage existing : values) {
                if (existing.id.equals(message.id)) return false;
            }
            values.add(message);
            while (values.size() > MAX_HISTORY) values.remove(0);
            persistCacheLocked();
            return true;
        }
    }

    private JSONObject api(String method, JSONObject payload) throws Exception {
        if (token.isEmpty()) throw new IOException("Telegram bot token is empty.");
        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "/bot" + token + "/" + method)
                .post(body)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            JSONObject json = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            if (!response.isSuccessful() || !json.optBoolean("ok", false)) {
                String description = json.optString("description", "Telegram API request failed.");
                throw new IOException(description);
            }
            return json;
        }
    }

    private void loadCache() {
        synchronized (lock) {
            try {
                JSONArray savedContacts = new JSONArray(preferences.getString(PREF_CONTACTS, "[]"));
                for (int i = 0; i < savedContacts.length(); i++) {
                    JSONObject item = savedContacts.optJSONObject(i);
                    if (item == null) continue;
                    ContactRecord record = new ContactRecord(
                            item.optString("id"), item.optString("name"), item.optString("username"));
                    if (!record.id.isEmpty()) contacts.put(record.id, record);
                }
                JSONArray savedHistory = new JSONArray(preferences.getString(PREF_HISTORY, "[]"));
                for (int i = 0; i < savedHistory.length(); i++) {
                    JSONObject item = savedHistory.optJSONObject(i);
                    if (item == null) continue;
                    String chatId = item.optString("chat_id");
                    if (chatId.isEmpty()) continue;
                    List<RemoteMessage> values = history.get(chatId);
                    if (values == null) {
                        values = new ArrayList<RemoteMessage>();
                        history.put(chatId, values);
                    }
                    values.add(new RemoteMessage(
                            item.optString("id"),
                            item.optString("sender"),
                            item.optString("content"),
                            item.optLong("timestamp"),
                            item.optBoolean("outgoing", false)));
                }
            } catch (Exception ignored) {}
        }
    }

    private void persistCacheLocked() {
        try {
            JSONArray savedContacts = new JSONArray();
            for (ContactRecord contact : contacts.values()) {
                savedContacts.put(new JSONObject()
                        .put("id", contact.id)
                        .put("name", contact.displayName)
                        .put("username", contact.username));
            }
            JSONArray savedHistory = new JSONArray();
            for (Map.Entry<String, List<RemoteMessage>> entry : history.entrySet()) {
                for (RemoteMessage message : entry.getValue()) {
                    savedHistory.put(new JSONObject()
                            .put("chat_id", entry.getKey())
                            .put("id", message.id)
                            .put("sender", message.sender)
                            .put("content", message.content)
                            .put("timestamp", message.timestamp)
                            .put("outgoing", message.outgoing));
                }
            }
            preferences.edit()
                    .putString(PREF_CONTACTS, savedContacts.toString())
                    .putString(PREF_HISTORY, savedHistory.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    private static String displayName(JSONObject chat, JSONObject from) {
        JSONObject source = from == null ? chat : from;
        String first = clean(source.optString("first_name", ""));
        String last = clean(source.optString("last_name", ""));
        String title = clean(chat.optString("title", ""));
        String username = clean(source.optString("username", chat.optString("username", "")));
        String name = clean((first + " " + last).trim());
        if (name.isEmpty()) name = title;
        if (name.isEmpty()) name = username.isEmpty() ? String.valueOf(chat.optLong("id", 0L)) : "@" + username;
        return name;
    }

    private static String remoteId(String chatId, long messageId) {
        return "telegram:" + clean(chatId) + ":" + messageId;
    }

    private static String normalizeQuery(String value) {
        String normalized = clean(value).toLowerCase(Locale.US);
        return normalized.startsWith("@") ? normalized.substring(1) : normalized;
    }

    private static String safeError(Throwable error) {
        if (error == null) return "Telegram request failed.";
        String value = clean(error.getMessage());
        return value.isEmpty() ? "Telegram request failed." : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
