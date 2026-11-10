package dev.marsian.upiqr;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Polls Razorpay's unauthenticated status endpoint (the one their own checkout
 * polls) for links whose tr is a Razorpay payment id minus the "pay_" prefix.
 * Status goes created -> authorized/captured, or failed.
 */
final class RazorpayWatcher {
    interface Listener {
        void onSupported();
        void onStatus(String status);
    }

    static final long INTERVAL_MS = 3000;

    final String paymentId;
    final Listener listener;
    volatile boolean running, supported;
    volatile String last;
    Thread thread;

    RazorpayWatcher(String tr, Listener l) {
        paymentId = "pay_" + tr;
        listener = l;
    }

    static boolean looksLikeRazorpay(String tr) {
        return tr != null && tr.matches("[A-Za-z0-9]{14}");
    }

    static boolean isPaid(String s) {
        return "authorized".equals(s) || "captured".equals(s);
    }

    void start() {
        running = true;
        thread = new Thread(this::loop, "razorpay-watch");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        int errors = 0;
        while (running) {
            try {
                String s = fetch();
                if (s == null) {
                    // not a Razorpay id after all (404/401) — give up quietly
                    if (!supported) { Log.i(MainActivity.TAG, "razorpay: not supported"); return; }
                } else {
                    if (!supported) { supported = true; listener.onSupported(); }
                    if (!s.equals(last)) Log.i(MainActivity.TAG, "razorpay: " + s);
                    last = s;
                    listener.onStatus(s);
                    if (isPaid(s) || "failed".equals(s)) return;
                }
                errors = 0;
            } catch (Exception e) {
                // network blips: keep trying, but bail if it never worked
                if (++errors > 5 && !supported) return;
            }
            try { Thread.sleep(INTERVAL_MS); } catch (InterruptedException e) { return; }
        }
    }

    private String fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.razorpay.com/v1/payments/" + paymentId + "/status").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
                return new JSONObject(bo.toString("UTF-8")).optString("status", null);
            }
        } finally {
            c.disconnect();
        }
    }
}
