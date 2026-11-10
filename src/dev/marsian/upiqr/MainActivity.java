package dev.marsian.upiqr;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.nayuki.qrcodegen.QrCode;

import java.io.OutputStream;

public class MainActivity extends Activity {
    static final String TAG = "UPIQR";
    static final int REQ_FORWARD = 1;

    String upi;          // the full upi:// URI we were handed
    boolean fromIntent;  // launched by a merchant app (vs. from the launcher)
    long capturedAt;     // when the link arrived, for the elapsed/expiry clock
    TextView clock;
    final Handler ticker = new Handler(Looper.getMainLooper());
    final Runnable tick = new Runnable() { public void run() { updateClock(); ticker.postDelayed(this, 1000); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences prefs = getSharedPreferences("p", MODE_PRIVATE);

        Intent in = getIntent();
        Uri data = in.getData();
        if (data != null && "upi".equalsIgnoreCase(data.getScheme())) {
            upi = data.toString();
            fromIntent = true;
            Log.i(TAG, "caller=" + getCallingPackage() + " uri=" + upi);
            capturedAt = System.currentTimeMillis();
            prefs.edit().putLong("lastAt", capturedAt).putString("last", upi).putString("lastCaller", String.valueOf(getCallingPackage())).apply();
            // full brightness so the other phone can scan it easily
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1f;
            getWindow().setAttributes(lp);
        } else {
            upi = prefs.getString("last", null);
            capturedAt = prefs.getLong("lastAt", 0);
        }
        render(prefs.getString("lastCaller", null));
    }

    void render(String lastCaller) {
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(40), pad, pad);
        col.setBackgroundColor(Color.WHITE);

        if (upi == null) {
            col.addView(text("No UPI request captured yet.\n\nPick \"UPI QR\" as the UPI app at checkout, or paste a upi:// link below.", 16, false));
        } else {
            Uri u = Uri.parse(upi);
            String pn = u.getQueryParameter("pn"), pa = u.getQueryParameter("pa"),
                   am = u.getQueryParameter("am"), tn = u.getQueryParameter("tn");
            col.addView(text(am != null ? "₹" + am : "(no amount)", 32, true));
            col.addView(text((pn != null ? pn : "") + "\n" + (pa != null ? pa : ""), 16, false));
            if (tn != null) col.addView(text(tn, 14, false));
            clock = text("", 15, true);
            col.addView(clock);
            if (!fromIntent && lastCaller != null) col.addView(text("(last captured, from " + lastCaller + ")", 12, false));

            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr(upi, 10));
            iv.setAdjustViewBounds(true);
            iv.setPadding(0, dp(12), 0, dp(12));
            col.addView(iv, new LinearLayout.LayoutParams(-1, -2));

            TextView raw = text(upi, 11, false);
            raw.setTypeface(Typeface.MONOSPACE);
            raw.setTextIsSelectable(true);
            col.addView(raw);

            col.addView(button("Copy link", v -> {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("upi", upi));
                toast("Copied");
            }));
            col.addView(button("Share link", v -> {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, upi);
                startActivity(Intent.createChooser(s, "Share UPI link"));
            }));
            col.addView(button("Save QR to gallery", v -> saveQr()));
            col.addView(button("Open in another UPI app", v -> forward()));
            if (fromIntent) {
                col.addView(button("Paid — back to merchant", v -> finishWith(true)));
                col.addView(button("Cancel", v -> finishWith(false)));
            }
        }

        // manual entry, for testing or links copied from elsewhere
        EditText et = new EditText(this);
        et.setHint("upi://pay?pa=...");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setTextColor(Color.BLACK);
        col.addView(et);
        col.addView(button("Show QR for pasted link", v -> {
            String s = et.getText().toString().trim();
            if (!s.toLowerCase().startsWith("upi:")) { toast("Not a upi: link"); return; }
            upi = s;
            fromIntent = false;
            capturedAt = System.currentTimeMillis();
            render(null);
        }));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.addView(col);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); ticker.post(tick); }
    @Override protected void onPause() { super.onPause(); ticker.removeCallbacks(tick); }

    void updateClock() {
        if (clock == null || upi == null || capturedAt == 0) return;
        long now = System.currentTimeMillis();
        String txt = "Captured " + mmss(now - capturedAt) + " ago";
        long exp = expiry(Uri.parse(upi));
        if (exp > 0) {
            long left = exp - now;
            txt += left > 0 ? "\nExpires in " + mmss(left) : "\nEXPIRED " + mmss(-left) + " ago";
            clock.setTextColor(left > 60000 ? Color.rgb(0, 120, 0) : Color.RED);
        } else {
            txt += "\n(link has no expiry; merchant usually allows ~5-10 min)";
            clock.setTextColor(now - capturedAt > 5 * 60000 ? Color.RED : Color.DKGRAY);
        }
        clock.setText(txt);
    }

    // Signed UPI 2.0 links may carry QRexpire (and QRts); formats vary, so try
    // epoch seconds/millis and ISO-8601.
    static long expiry(Uri u) {
        for (String k : u.getQueryParameterNames()) {
            String lk = k.toLowerCase();
            if (!(lk.equals("qrexpire") || lk.equals("expiry") || lk.equals("expiretime") || lk.equals("validityend"))) continue;
            String v = u.getQueryParameter(k);
            if (v == null) continue;
            v = v.trim();
            try {
                long n = Long.parseLong(v);
                return n < 100000000000L ? n * 1000 : n;
            } catch (NumberFormatException ignored) {}
            for (String f : new String[]{"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"}) {
                try { return new java.text.SimpleDateFormat(f, java.util.Locale.US).parse(v).getTime(); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    static String mmss(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    // Merchant SDKs only trust their server-side status check; returning
    // Status=SUBMITTED makes them poll it instead of treating it as a failure.
    void finishWith(boolean paid) {
        Intent r = new Intent();
        if (paid) {
            String tr = Uri.parse(upi).getQueryParameter("tr");
            String resp = "txnId=&responseCode=&Status=SUBMITTED&txnRef=" + (tr != null ? tr : "");
            r.putExtra("response", resp).putExtra("Status", "SUBMITTED").putExtra("txnRef", tr);
            setResult(RESULT_OK, r);
        } else {
            r.putExtra("response", "Status=FAILURE").putExtra("Status", "FAILURE");
            setResult(RESULT_CANCELED, r);
        }
        finish();
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

    void saveQr() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "upi-" + System.currentTimeMillis() + ".png");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UPIQR");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                qr(upi, 12).compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            toast("Saved to Pictures/UPIQR");
        } catch (Exception e) {
            toast("Save failed: " + e);
        }
    }

    static Bitmap qr(String s, int scale) {
        QrCode q = QrCode.encodeText(s, QrCode.Ecc.MEDIUM);
        int border = 4, n = (q.size + border * 2) * scale;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                px[y * n + x] = q.getModule(x / scale - border, y / scale - border) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.BLACK);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
