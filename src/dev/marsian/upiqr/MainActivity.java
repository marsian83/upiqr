package dev.marsian.upiqr;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.NumberFormat;
import java.util.Locale;

import static dev.marsian.upiqr.Ui.*;

public class MainActivity extends Activity {
    static final String TAG = "UPIQR";
    static final int REQ_FORWARD = 1;

    String upi;          // the full upi:// URI we were handed
    boolean fromIntent;  // launched by a merchant app (vs. from the launcher)
    long capturedAt;     // when the link arrived, for the elapsed/expiry clock
    String caller;       // package that asked us to pay

    Ui.Palette p;
    TextView statusText, hint;
    View statusDot;
    FrameLayout qrSlot;
    ObjectAnimator pulse;
    RazorpayWatcher watcher;
    boolean done;

    final Handler ticker = new Handler(Looper.getMainLooper());
    final Runnable tick = new Runnable() { public void run() { updateStatus(); ticker.postDelayed(this, 1000); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        p = new Ui.Palette(dark);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(p.bg);
        w.setNavigationBarColor(p.bg);
        if (!dark) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        SharedPreferences prefs = getSharedPreferences("p", MODE_PRIVATE);
        Uri data = getIntent().getData();
        if (data != null && "upi".equalsIgnoreCase(data.getScheme())) {
            upi = data.toString();
            fromIntent = true;
            caller = getCallingPackage();
            capturedAt = System.currentTimeMillis();
            Log.i(TAG, "caller=" + caller + " uri=" + upi);
            prefs.edit().putLong("lastAt", capturedAt).putString("last", upi).putString("lastCaller", caller).apply();
            // full brightness so the other phone can scan it easily
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = 1f;
            w.setAttributes(lp);
        } else {
            upi = prefs.getString("last", null);
            capturedAt = prefs.getLong("lastAt", 0);
            caller = prefs.getString("lastCaller", null);
        }
        render();
        startWatcher();
    }

    // ---------------------------------------------------------------- layout

    void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);
        root.setPadding(dp(this, 20), dp(this, 12), dp(this, 20), dp(this, 20));

        // top bar: close · title · more
        FrameLayout bar = new FrameLayout(this);
        TextView close = circleButton(this, p, "✕");
        close.setOnClickListener(v -> finishWith(false));
        bar.addView(close, new FrameLayout.LayoutParams(dp(this, 40), dp(this, 40), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView title = label(this, fromIntent ? "Scan to pay" : "UPI QR", 15, 600, p.fg);
        bar.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        if (upi != null) {
            TextView more = circleButton(this, p, "⋯");
            more.setOnClickListener(this::showMenu);
            bar.addView(more, new FrameLayout.LayoutParams(dp(this, 40), dp(this, 40), Gravity.END | Gravity.CENTER_VERTICAL));
        }
        if (!fromIntent) close.setVisibility(View.INVISIBLE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(this, 48)));

        if (upi == null) {
            renderEmpty(root);
            setContentView(root);
            return;
        }

        Uri u = Uri.parse(upi);
        String pn = u.getQueryParameter("pn"), pa = u.getQueryParameter("pa"),
               am = u.getQueryParameter("am"), tn = u.getQueryParameter("tn");

        // merchant header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(0, dp(this, 16), 0, 0);

        Drawable icon = null;
        String name = prettyName(pn);
        if (caller != null) {
            try {
                PackageManager pm = getPackageManager();
                icon = pm.getApplicationIcon(caller);
                name = pm.getApplicationLabel(pm.getApplicationInfo(caller, 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        View avatar;
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setClipToOutline(true);
            iv.setBackground(rounded(Color.TRANSPARENT, dp(this, 14)));
            avatar = iv;
        } else {
            TextView t = label(this, name.isEmpty() ? "₹" : name.substring(0, 1).toUpperCase(), 22, 600, p.accent);
            t.setGravity(Gravity.CENTER);
            t.setBackground(rounded(p.accentSoft, dp(this, 14)));
            avatar = t;
        }
        head.addView(avatar, new LinearLayout.LayoutParams(dp(this, 52), dp(this, 52)));

        TextView merchant = label(this, name, 17, 600, p.fg);
        merchant.setPadding(0, dp(this, 12), 0, 0);
        head.addView(merchant);
        TextView vpa = label(this, pa != null ? pa : "", 13, 400, p.fg2);
        vpa.setPadding(0, dp(this, 2), 0, 0);
        head.addView(vpa);

        TextView amount = label(this, formatAmount(am), 48, 600, p.fg);
        amount.setLetterSpacing(-0.02f);
        amount.setPadding(0, dp(this, 14), 0, 0);
        head.addView(amount);
        if (tn != null && !tn.trim().isEmpty()) {
            TextView note = label(this, tn, 13, 400, p.fg2);
            note.setPadding(0, dp(this, 2), 0, 0);
            head.addView(note);
        }
        root.addView(head);

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // QR card — always white for scanner contrast, in both themes
        qrSlot = new FrameLayout(this);
        qrSlot.setBackground(rounded(Color.WHITE, dp(this, 28)));
        qrSlot.setElevation(dark() ? 0 : dp(this, 6));
        int pad = dp(this, 18);
        qrSlot.setPadding(pad, pad, pad, pad);
        Ui.QrView qr = new Ui.QrView(this, upi);
        qr.setOnLongClickListener(v -> { copy(); return true; });
        qrSlot.addView(qr, new FrameLayout.LayoutParams(-1, -1));
        int side = Math.min(getResources().getDisplayMetrics().widthPixels - dp(this, 72), dp(this, 300));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(side, side);
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(qrSlot, qlp);
        qrSlot.setAlpha(0f);
        qrSlot.setScaleX(0.94f);
        qrSlot.setScaleY(0.94f);
        qrSlot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        // status pill
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(rounded(p.surface, dp(this, 16)));
        pill.setPadding(dp(this, 12), dp(this, 7), dp(this, 14), dp(this, 7));
        statusDot = new View(this);
        statusDot.setBackground(rounded(p.accent, dp(this, 4)));
        pill.addView(statusDot, new LinearLayout.LayoutParams(dp(this, 8), dp(this, 8)));
        statusText = label(this, "", 13, 500, p.fg);
        statusText.setPadding(dp(this, 8), 0, 0, 0);
        pill.addView(statusText);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(this, 18);
        root.addView(pill, plp);
        pulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.25f);
        pulse.setDuration(900);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        if (fromIntent) {
            TextView paid = primaryButton(this, p, "I've paid");
            paid.setOnClickListener(v -> finishWith(true));
            root.addView(paid, new LinearLayout.LayoutParams(-1, dp(this, 54)));
            hint = label(this, "Scan with any UPI app, then come back here", 12, 400, p.fg2);
            hint.setPadding(0, dp(this, 10), 0, 0);
            root.addView(hint);
        } else {
            TextView paste = primaryButton(this, p, "Paste UPI link");
            paste.setOnClickListener(v -> pasteLink());
            root.addView(paste, new LinearLayout.LayoutParams(-1, dp(this, 54)));
            hint = label(this, "Last request" + (capturedAt > 0 ? " · " + DateUtils.getRelativeTimeSpanString(capturedAt) : ""), 12, 400, p.fg2);
            hint.setPadding(0, dp(this, 10), 0, 0);
            root.addView(hint);
        }
        setContentView(root);
        updateStatus();
    }

    void renderEmpty(LinearLayout root) {
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView glyph = label(this, "₹", 40, 600, p.accent);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(rounded(p.accentSoft, dp(this, 32)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(this, 80), dp(this, 80));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(glyph, glp);
        TextView h = label(this, "Nothing to pay", 22, 600, p.fg);
        h.setPadding(0, dp(this, 20), 0, 0);
        root.addView(h);
        TextView s = label(this, "At checkout, pick UPI QR as your UPI app.\nThe payment will open here as a QR code.", 15, 400, p.fg2);
        s.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), 0);
        s.setLineSpacing(0, 1.15f);
        root.addView(s);
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView paste = primaryButton(this, p, "Paste UPI link");
        paste.setOnClickListener(v -> pasteLink());
        root.addView(paste, new LinearLayout.LayoutParams(-1, dp(this, 54)));
    }

    void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Copy link");
        m.getMenu().add(0, 2, 0, "Share link");
        m.getMenu().add(0, 3, 0, "Save QR to Photos");
        m.getMenu().add(0, 4, 0, "Pay with another app");
        m.setOnMenuItemClickListener(i -> {
            switch (i.getItemId()) {
                case 1: copy(); break;
                case 2: startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, upi), null)); break;
                case 3: saveQr(); break;
                case 4: forward(); break;
            }
            return true;
        });
        m.show();
    }

    // ---------------------------------------------------------------- status

    @Override protected void onResume() { super.onResume(); ticker.post(tick); }
    @Override protected void onPause() { super.onPause(); ticker.removeCallbacks(tick); }
    @Override protected void onDestroy() { super.onDestroy(); if (watcher != null) watcher.stop(); }

    void startWatcher() {
        if (!fromIntent || upi == null) return;
        String tr = Uri.parse(upi).getQueryParameter("tr");
        if (!RazorpayWatcher.looksLikeRazorpay(tr)) return;
        watcher = new RazorpayWatcher(tr, new RazorpayWatcher.Listener() {
            public void onSupported() {
                runOnUiThread(() -> { if (hint != null && !done) hint.setText("Confirms automatically once the payment lands"); });
            }
            public void onStatus(String s) {
                runOnUiThread(() -> {
                    if (done) return;
                    if (RazorpayWatcher.isPaid(s)) onPaid();
                    else if ("failed".equals(s)) onFailed();
                });
            }
        });
        watcher.start();
    }

    void updateStatus() {
        if (statusText == null || done || upi == null || capturedAt == 0) return;
        long now = System.currentTimeMillis();
        long exp = expiry(Uri.parse(upi));
        long age = now - capturedAt;
        String txt;
        int dot;
        if (!fromIntent) {
            txt = "Not waiting";
            dot = p.fg2;
            if (pulse != null) pulse.cancel();
        } else if (exp > 0) {
            long left = exp - now;
            txt = left > 0 ? "Expires in " + mmss(left) : "Expired";
            dot = left > 60000 ? p.accent : p.danger;
        } else {
            txt = (watcher != null && watcher.supported ? "Waiting for payment · " : "Open for ") + mmss(age);
            dot = age > 5 * 60000 ? p.warn : p.accent;
        }
        statusText.setText(txt);
        ((android.graphics.drawable.GradientDrawable) statusDot.getBackground()).setColor(dot);
    }

    void onPaid() {
        done = true;
        if (pulse != null) pulse.cancel();
        statusDot.setAlpha(1f);
        ((android.graphics.drawable.GradientDrawable) statusDot.getBackground()).setColor(p.success);
        statusText.setText("Payment received");
        if (hint != null) hint.setText("Returning to " + (caller != null ? appName(caller) : "the merchant") + "…");
        Ui.CheckView check = new Ui.CheckView(this, p.success);
        check.setAlpha(0f);
        qrSlot.addView(check, new FrameLayout.LayoutParams(-1, -1));
        qrSlot.getChildAt(0).animate().alpha(0.08f).setDuration(250).start();
        check.animate().alpha(1f).setDuration(200).start();
        check.play();
        qrSlot.postDelayed(() -> finishWith(true), 1600);
    }

    void onFailed() {
        if (pulse != null) pulse.cancel();
        statusDot.setAlpha(1f);
        ((android.graphics.drawable.GradientDrawable) statusDot.getBackground()).setColor(p.danger);
        statusText.setText("Payment failed");
        done = true;
    }

    // ---------------------------------------------------------------- actions

    // Merchant SDKs only trust their server-side status check; we report
    // SUCCESS when Razorpay confirmed it, otherwise SUBMITTED so they poll.
    void finishWith(boolean paid) {
        if (isFinishing()) return;
        Intent r = new Intent();
        if (paid) {
            String tr = Uri.parse(upi).getQueryParameter("tr");
            String st = (watcher != null && RazorpayWatcher.isPaid(watcher.last)) ? "SUCCESS" : "SUBMITTED";
            r.putExtra("response", "txnId=&responseCode=00&Status=" + st + "&txnRef=" + (tr != null ? tr : ""))
             .putExtra("Status", st).putExtra("txnRef", tr);
            setResult(RESULT_OK, r);
        } else {
            r.putExtra("response", "Status=FAILURE").putExtra("Status", "FAILURE");
            setResult(RESULT_CANCELED, r);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (fromIntent) finishWith(false); else super.onBackPressed();
    }

    void forward() {
        Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse(upi));
        Intent ch = Intent.createChooser(v, "Pay with");
        ch.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{new ComponentName(this, MainActivity.class)});
        try {
            startActivityForResult(ch, REQ_FORWARD);
        } catch (Exception e) {
            toast("No other UPI app");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_FORWARD) return;
        Log.i(TAG, "forward result=" + res + " extras=" + (data != null && data.getExtras() != null ? data.getExtras().toString() : null));
        if (fromIntent) {
            // pass the real UPI app's answer straight back to the merchant
            setResult(res, data);
            finish();
        }
    }

    void copy() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("upi", upi));
        toast("Link copied");
    }

    void pasteLink() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData c = cm.getPrimaryClip();
        String s = c != null && c.getItemCount() > 0 ? String.valueOf(c.getItemAt(0).coerceToText(this)).trim() : "";
        if (!s.toLowerCase(Locale.ROOT).startsWith("upi:")) { toast("Clipboard has no upi:// link"); return; }
        upi = s;
        capturedAt = System.currentTimeMillis();
        caller = null;
        render();
    }

    void saveQr() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "upi-" + System.currentTimeMillis() + ".png");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UPIQR");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                Ui.qrBitmap(upi, 12).compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            toast("Saved to Photos");
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    boolean dark() { return p.dark; }

    String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String prettyName(String pn) {
        if (pn == null || pn.trim().isEmpty()) return "UPI payment";
        String s = pn.trim().replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String formatAmount(String am) {
        if (am == null) return "Any amount";
        try {
            double v = Double.parseDouble(am);
            NumberFormat f = NumberFormat.getNumberInstance(new Locale("en", "IN"));
            boolean whole = v == Math.floor(v);
            f.setMinimumFractionDigits(whole ? 0 : 2);
            f.setMaximumFractionDigits(2);
            return "₹" + f.format(v);
        } catch (NumberFormatException e) {
            return "₹" + am;
        }
    }

    // Signed UPI 2.0 links may carry QRexpire (and QRts); formats vary, so try
    // epoch seconds/millis and ISO-8601.
    static long expiry(Uri u) {
        for (String k : u.getQueryParameterNames()) {
            String lk = k.toLowerCase(Locale.ROOT);
            if (!(lk.equals("qrexpire") || lk.equals("expiry") || lk.equals("expiretime") || lk.equals("validityend"))) continue;
            String v = u.getQueryParameter(k);
            if (v == null) continue;
            v = v.trim();
            try {
                long n = Long.parseLong(v);
                return n < 100000000000L ? n * 1000 : n;
            } catch (NumberFormatException ignored) {}
            for (String f : new String[]{"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"}) {
                try { return new java.text.SimpleDateFormat(f, Locale.US).parse(v).getTime(); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    static String mmss(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
