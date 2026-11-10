package dev.marsian.upiqr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import io.nayuki.qrcodegen.QrCode;

/** Small view kit so the activity reads like a layout, not a pile of setters. */
final class Ui {
    private Ui() {}

    static final class Palette {
        final boolean dark;
        final int bg, surface, fg, fg2, accent, accentSoft, onAccent, success, warn, danger, ripple;

        Palette(boolean dark) {
            this.dark = dark;
            if (dark) {
                bg = 0xFF000000; surface = 0xFF1C1C1E; fg = 0xFFF5F5F7; fg2 = 0xFF8E8E93;
                accent = 0xFF3E91FF; accentSoft = 0x333E91FF; onAccent = Color.WHITE;
                success = 0xFF30D158; warn = 0xFFFF9F0A; danger = 0xFFFF453A; ripple = 0x33FFFFFF;
            } else {
                bg = 0xFFF2F2F7; surface = 0xFFFFFFFF; fg = 0xFF1C1C1E; fg2 = 0xFF6E6E73;
                accent = 0xFF0A6CFF; accentSoft = 0x1F0A6CFF; onAccent = Color.WHITE;
                success = 0xFF34C759; warn = 0xFFFF9500; danger = 0xFFFF3B30; ripple = 0x22000000;
            }
        }
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static TextView label(Context c, String s, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView primaryButton(Context c, Palette p, String s) {
        TextView b = label(c, s, 17, 600, p.onAccent);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), rounded(p.accent, dp(c, 27)), null));
        b.setClickable(true);
        return b;
    }

    static TextView circleButton(Context c, Palette p, String glyph) {
        TextView b = label(c, glyph, 17, 500, p.fg);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), rounded(p.surface, dp(c, 20)), null));
        b.setClickable(true);
        return b;
    }

    static Bitmap qrBitmap(String s, int scale) {
        QrCode q = QrCode.encodeText(s, QrCode.Ecc.MEDIUM);
        int border = 4, n = (q.size + border * 2) * scale;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                px[y * n + x] = q.getModule(x / scale - border, y / scale - border) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    /** Draws the code with whole-pixel modules so it stays crisp at any size. */
    static final class QrView extends View {
        final QrCode qr;
        final Paint ink = new Paint();

        QrView(Context c, String text) {
            super(c);
            qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
            ink.setColor(0xFF111111);
            setLongClickable(true);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int side = Math.min(getWidth(), getHeight());
            int m = Math.max(1, side / qr.size);
            int ox = (getWidth() - m * qr.size) / 2, oy = (getHeight() - m * qr.size) / 2;
            for (int y = 0; y < qr.size; y++)
                for (int x = 0; x < qr.size; x++)
                    if (qr.getModule(x, y))
                        cv.drawRect(ox + x * m, oy + y * m, ox + (x + 1) * m, oy + (y + 1) * m, ink);
        }
    }

    /** Filled circle that pops in, then a check stroke that draws itself. */
    static final class CheckView extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path check = new Path(), partial = new Path();
        float circle, tick;

        CheckView(Context c, int color) {
            super(c);
            fill.setColor(color);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        void play() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(380);
            a.setInterpolator(new OvershootInterpolator(1.6f));
            a.addUpdateListener(v -> { circle = (float) v.getAnimatedValue(); invalidate(); });
            ValueAnimator b = ValueAnimator.ofFloat(0f, 1f).setDuration(320);
            b.setStartDelay(220);
            b.setInterpolator(new DecelerateInterpolator());
            b.addUpdateListener(v -> { tick = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
            b.start();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.42f;
            cv.drawCircle(cx, cy, r * circle, fill);
            if (tick <= 0) return;
            stroke.setStrokeWidth(r * 0.16f);
            check.reset();
            check.moveTo(cx - r * 0.42f, cy + r * 0.02f);
            check.lineTo(cx - r * 0.1f, cy + r * 0.34f);
            check.lineTo(cx + r * 0.46f, cy - r * 0.3f);
            PathMeasure pm = new PathMeasure(check, false);
            partial.reset();
            pm.getSegment(0, pm.getLength() * tick, partial, true);
            cv.drawPath(partial, stroke);
        }
    }
}
