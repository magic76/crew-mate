package com.crewpocket.mate.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.channel.TelegramMessagingBackend;
import com.crewpocket.mate.config.AppConfig;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.storage.SessionStore;
import com.crewpocket.mate.ui.MainActivity;

/**
 * Short-lived foreground handoff used while a delegated Telegram task is waiting for a reply.
 * It does not run an agent loop or Gemini Live in the background. It only captures the next
 * external reply, persists it into the correct CommunicationSession, notifies the user, then stops.
 */
public final class CommunicationContinuationService extends Service {
    public static final String EXTRA_SESSION_ID = "crew_mate_session_id";
    private static final String CHANNEL_ACTIVE = "crew_mate_active_tasks";
    private static final String CHANNEL_REPLY = "crew_mate_replies";
    private static final int ACTIVE_NOTIFICATION_ID = 4101;
    private static final long MAX_WAIT_MS = 4L * 60L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MessagingBackend backend;
    private String sessionId = "";
    private boolean stopped;

    private final Runnable timeout = new Runnable() {
        @Override public void run() { stopCleanly(); }
    };

    public static void start(Context context, String sessionId) {
        if (context == null || sessionId == null || sessionId.trim().isEmpty()) return;
        Intent intent = new Intent(context, CommunicationContinuationService.class)
                .putExtra(EXTRA_SESSION_ID, sessionId.trim());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sessionId = intent == null ? "" : clean(intent.getStringExtra(EXTRA_SESSION_ID));
        if (sessionId.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final SessionStore store = new SessionStore(this);
        final CommunicationSession session = store.loadById(sessionId);
        if (session == null
                || session.targetPersonId().isEmpty()
                || !AppConfig.PROVIDER_TELEGRAM.equals(AppConfig.getMessagingProvider(this))
                || AppConfig.getTelegramBotToken(this).isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(ACTIVE_NOTIFICATION_ID, buildWaitingNotification(session));
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, MAX_WAIT_MS);

        backend = new TelegramMessagingBackend(this, AppConfig.getTelegramBotToken(this));
        MessagingBackend.Contact contact = new MessagingBackend.Contact(
                session.targetPersonId(), session.targetPerson());
        final long afterTimestamp = session.latestMessageTimestamp();
        backend.watchIncoming(contact, afterTimestamp, new MessagingBackend.IncomingCallback() {
            @Override public void onMessage(MessagingBackend.RemoteMessage remote) {
                if (remote == null || stopped) return;
                CommunicationSession fresh = store.loadById(sessionId);
                if (fresh == null) {
                    stopCleanly();
                    return;
                }
                Message message = new Message(
                        remote.id,
                        Message.Sender.OTHER_PERSON,
                        "MATE",
                        remote.content,
                        remote.timestamp,
                        Message.Status.RECEIVED);
                fresh.addMessage(message);
                fresh.setStatus(CommunicationSession.Status.REPLY_RECEIVED);
                store.save(fresh);
                showReplyNotification(fresh, remote.content);
                stopCleanly();
            }

            @Override public void onError(String message) {
                stopCleanly();
            }
        });
        return START_NOT_STICKY;
    }

    private Notification buildWaitingNotification(CommunicationSession session) {
        String person = session.targetPerson().isEmpty() ? "the other person" : session.targetPerson();
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_SESSION_ID, session.sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, session.sessionId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Notification.Builder builder = notificationBuilder(CHANNEL_ACTIVE)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Crew Mate is handling it")
                .setContentText("Waiting for " + person + " to reply")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending);
        return builder.build();
    }

    private void showReplyNotification(CommunicationSession session, String content) {
        String person = session.targetPerson().isEmpty() ? "Someone" : session.targetPerson();
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_SESSION_ID, session.sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, session.sessionId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Notification notification = notificationBuilder(CHANNEL_REPLY)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(person + " replied")
                .setContentText(trimPreview(content))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(4200 + Math.abs(session.sessionId.hashCode() % 500), notification);
    }

    private Notification.Builder notificationBuilder(String channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel active = new NotificationChannel(
                CHANNEL_ACTIVE, "Active communication tasks", NotificationManager.IMPORTANCE_LOW);
        active.setDescription("Shows when Crew Mate is waiting for a reply on a delegated task.");
        manager.createNotificationChannel(active);
        NotificationChannel replies = new NotificationChannel(
                CHANNEL_REPLY, "Communication replies", NotificationManager.IMPORTANCE_DEFAULT);
        replies.setDescription("Notifies you when the other person replies to Crew Mate.");
        manager.createNotificationChannel(replies);
    }

    private void stopCleanly() {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacks(timeout);
        if (backend != null) backend.shutdown();
        backend = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        if (backend != null) backend.shutdown();
        backend = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static String trimPreview(String value) {
        String text = clean(value);
        return text.length() <= 100 ? text : text.substring(0, 97) + "...";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
