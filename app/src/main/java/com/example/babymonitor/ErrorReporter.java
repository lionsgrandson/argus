package com.example.babymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

public final class ErrorReporter {
    private static final String TAG = "ARGUS";
    private static final String CHANNEL = "argus_errors";
    private static final int NOTIF_PARENT = 1201;
    private static final int NOTIF_BABY = 1202;
    private static final int NOTIF_SETUP = 1203;
    private static volatile Thread.UncaughtExceptionHandler previousHandler;
    private static volatile boolean installed;

    public static final class ArgusException extends IOException {
        final String code;
        final String userMessage;

        ArgusException(String code, String userMessage, String technicalMessage) {
            super(technicalMessage);
            this.code = code;
            this.userMessage = userMessage;
        }

        ArgusException(String code, String userMessage, String technicalMessage, Throwable cause) {
            super(technicalMessage, cause);
            this.code = code;
            this.userMessage = userMessage;
        }
    }

    static void install(Context context) {
        ensureChannel(context);
        if (installed) return;
        synchronized (ErrorReporter.class) {
            if (installed) return;
            previousHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    report(context, "setup", "E500", "שגיאה לא צפויה באפליקציה", error);
                } catch (Throwable ignored) {
                }
                if (previousHandler != null) previousHandler.uncaughtException(thread, error);
            });
            installed = true;
        }
    }

    static void reportConnection(String role, Throwable error) {
        Context context = ArgusApp.context();
        if (context != null) reportConnection(context, role, error);
    }

    static void reportConnection(Context context, String role, Throwable error) {
        if (error instanceof ArgusException) {
            ArgusException ae = (ArgusException) error;
            report(context, role, ae.code, ae.userMessage, ae);
            return;
        }
        if (error instanceof UnknownHostException) {
            report(context, role, "E201", "לא ניתן למצוא את שרת החיבור", error);
        } else if (error instanceof SocketTimeoutException) {
            report(context, role, "E202", "החיבור לשרת לקח יותר מדי זמן", error);
        } else if (error instanceof SSLException) {
            report(context, role, "E203", "חיבור האבטחה לשרת נכשל", error);
        } else if (error instanceof ConnectException) {
            report(context, role, "E206", "לא ניתן להגיע לשרת החיבור", error);
        } else if (error instanceof EOFException) {
            report(context, role, "E207", "שרת החיבור סגר את החיבור", error);
        } else if (error instanceof IOException) {
            report(context, role, "E207", "שגיאת רשת בחיבור", error);
        } else if (error instanceof IllegalArgumentException) {
            report(context, role, "E200", "הגדרת שרת החיבור אינה תקינה", error);
        } else {
            report(context, role, "E500", "שגיאה לא צפויה בחיבור", error);
        }
    }

    public static void report(Context context, String role, String code, String userMessage, Throwable error) {
        if (context == null) return;
        String safeRole = role == null || role.isEmpty() ? "setup" : role;
        String rawDetail = technicalDetail(error);
        AppPrefs.saveError(context, safeRole, code, userMessage, rawDetail);
        int count = AppPrefs.lastErrorCount(context);
        if ("parent".equals(safeRole)) {
            AppPrefs.state(context, safeRole, "שגיאה " + code + ": " + userMessage);
        }
        Log.e(TAG, code + " [" + safeRole + "] " + userMessage + (rawDetail.isEmpty() ? "" : " | " + rawDetail), error);

        // The Child phone stays silent and unobtrusive. Errors remain in the
        // diagnostic log/preferences, but never create a toast or notification.
        boolean childPhone = "baby".equals(safeRole)
                || "baby".equals(AppPrefs.prefs(context).getString("setup_role", ""));
        if (childPhone) return;

        if (count == 1) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context.getApplicationContext(), "ARGUS " + code + ": " + userMessage, Toast.LENGTH_LONG).show());
        }
        showNotification(context, safeRole, code, userMessage, hebrewTechnicalSummary(error));
    }

    public static void report(String role, String code, String userMessage, Throwable error) {
        Context context = ArgusApp.context();
        if (context != null) report(context, role, code, userMessage, error);
    }

    static void clear(Context context, String role) {
        if (context == null) return;
        AppPrefs.clearError(context, role);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notificationId(role));
    }

    static void clear(String role) {
        Context context = ArgusApp.context();
        if (context != null) clear(context, role);
    }

    private static void showNotification(Context context, String role, String code, String message, String detail) {
        ensureChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, notificationId(role), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String big = "קוד שגיאה: " + code + "\n" + message;
        if (!detail.isEmpty()) big += "\nפרטים: " + detail;
        int count = AppPrefs.lastErrorCount(context);
        if (count > 1) big += "\nמספר חזרות: " + count;

        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("שגיאת ARGUS " + code)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setCategory(Notification.CATEGORY_ERROR)
                .setPriority(Notification.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
                .build();
        nm.notify(notificationId(role), notification);
    }

    private static void ensureChannel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "שגיאות ARGUS", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("קודי שגיאה ופרטי תקלות בחיבור ובשידור");
        nm.createNotificationChannel(channel);
    }

    private static int notificationId(String role) {
        if ("parent".equals(role)) return NOTIF_PARENT;
        if ("baby".equals(role)) return NOTIF_BABY;
        return NOTIF_SETUP;
    }

    private static String hebrewTechnicalSummary(Throwable error) {
        if (error == null) return "";
        if (error instanceof UnknownHostException) return "לא ניתן לאתר את כתובת שרת החיבור";
        if (error instanceof SocketTimeoutException) return "פג זמן ההמתנה לחיבור";
        if (error instanceof SSLException) return "כשל באימות חיבור מאובטח";
        if (error instanceof ConnectException) return "השרת אינו נגיש כרגע";
        if (error instanceof EOFException) return "השרת סגר את החיבור באופן לא צפוי";
        if (error instanceof SecurityException) return "בעיה בהרשאה או באבטחה";
        if (error instanceof IllegalArgumentException) return "התקבל נתון שאינו תקין";
        if (error instanceof IOException) return "אירעה שגיאת רשת";
        return "אירעה תקלה פנימית באפליקציה";
    }

    private static String technicalDetail(Throwable error) {
        if (error == null) return "";
        StringBuilder out = new StringBuilder(error.getClass().getSimpleName());
        if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
            out.append(": ").append(error.getMessage().trim());
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            out.append(" | cause=").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) {
                out.append(": ").append(cause.getMessage().trim());
            }
        }
        String value = out.toString();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private ErrorReporter() {}
}
