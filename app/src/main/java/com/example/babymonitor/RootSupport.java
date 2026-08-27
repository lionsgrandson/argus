package com.example.babymonitor;

import android.content.Context;

import java.util.concurrent.TimeUnit;

final class RootSupport {
    private static final long ROOT_COMMAND_TIMEOUT_SECONDS = 5L;

    /**
     * Called during an ordinary, user-initiated ARGUS start. On a rooted phone this
     * gives the root manager a chance to ask for permanent su permission while the
     * user is already interacting with the app. On a non-rooted phone it simply
     * fails quietly and changes nothing.
     */
    static void preAuthorizeAsync() {
        new Thread(() -> {
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "id")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroy();
                }
            } catch (Exception ignored) {
                // Normal on non-rooted devices.
            } finally {
                if (process != null) closeProcessStreams(process);
            }
        }, "ArgusRootAuthorization").start();
    }

    /**
     * Uses an already-authorized su implementation (for example Magisk) to bring
     * ARGUS to the foreground after unlock. MainActivity then starts the normal
     * camera/microphone foreground service, which keeps the same visible ARGUS
     * active indicator as a non-rooted phone.
     */
    static boolean tryLaunchResumeActivity(Context context, String action) {
        Process process = null;
        try {
            String component = context.getPackageName() + "/" + MainActivity.class.getName();
            String command = "am start -a " + action + " -n " + component;

            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) closeProcessStreams(process);
        }
    }

    private static void closeProcessStreams(Process process) {
        try { process.getInputStream().close(); } catch (Exception ignored) { }
        try { process.getOutputStream().close(); } catch (Exception ignored) { }
        try { process.getErrorStream().close(); } catch (Exception ignored) { }
    }

    private RootSupport() {}
}
