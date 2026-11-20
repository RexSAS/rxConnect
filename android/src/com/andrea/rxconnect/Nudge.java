package com.andrea.rxconnect;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * A nudge on her screen, over whatever app is open (mockup: the heart buddy): a heart peeks up from the bottom, wiggles
 * with a little message from you, then jumps and pops into tiny hearts. Needs "Display over other apps" (the setup
 * check asks); without it, or with Do Not Disturb on, it's a quiet notification instead.
 */
final class Nudge {
    // They take turns, so the same one never comes twice in a row.
    static final String[] LINES = {"thinking of you", "hi! 💗", "miss you"};

    static void show(Context c, String from) {
        SharedPreferences p = Widgets.prefs(c);
        int turn = p.getInt("line", 0);
        p.edit().putInt("line", turn + 1).apply();
        String line = LINES[turn % LINES.length];
        boolean dnd = c.getSystemService(NotificationManager.class).getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
        if (dnd || !Settings.canDrawOverlays(c)) {
            PushService.love(c, from + ": " + line);
            return;
        }
        Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> play(app, from, line));
    }

    static void play(Context c, String from, String line) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(c);
        // Only the strip the heart moves in, fully opaque. Android 12+ blocks touches under an untrusted overlay that's
        // more than 80% opaque, so for these ~3 s taps right around the heart don't reach her app; the rest of the
        // screen works as usual. (A full-screen overlay would have to stay see-through.)
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams((int) (300 * d), (int) (460 * d),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        int base = (int) (110 * d); // the heart's bottom, above the navigation bar
        ImageView heart = new ImageView(c);
        heart.setImageResource(R.drawable.heart_on);
        heart.setElevation(12 * d);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams((int) (120 * d), (int) (120 * d), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = base;
        root.addView(heart, hp);
        heart.setPivotX(60 * d);
        heart.setPivotY(120 * d); // squash and stretch from its bottom
        heart.setTranslationY(260 * d);

        TextView bubble = new TextView(c);
        SpannableStringBuilder t = new SpannableStringBuilder(from + "\n" + line);
        t.setSpan(new AbsoluteSizeSpan(11, true), 0, from.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setSpan(new ForegroundColorSpan(0xFF8B91A1), 0, from.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setSpan(new StyleSpan(Typeface.BOLD), from.length(), t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        bubble.setText(t);
        bubble.setTextColor(0xFFE0457B);
        bubble.setTextSize(16);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackgroundResource(R.drawable.bubble);
        bubble.setPadding((int) (14 * d), (int) (8 * d), (int) (14 * d), (int) (8 * d));
        bubble.setElevation(8 * d);
        bubble.setAlpha(0);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = base + (int) (135 * d);
        root.addView(bubble, bp);

        ImageView[] minis = new ImageView[8];
        for (int i = 0; i < minis.length; i++) { // the pop: tiny hearts from where the heart is after its jump
            ImageView m = minis[i] = new ImageView(c);
            m.setImageResource(R.drawable.heart_mini);
            m.setAlpha(0f);
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams((int) (18 * d), (int) (18 * d), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            mp.bottomMargin = base + (int) ((60 + 70 - 9) * d);
            root.addView(m, mp);
        }
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) { // permission taken back meanwhile
            PushService.love(c, from + ": " + line);
            return;
        }

        Animator rise = anim(heart, 450, new OvershootInterpolator(1.1f), "translationY", 260 * d, 0);
        Animator squash = together(anim(heart, 350, null, "scaleX", 1, 1.08f, .96f, 1), anim(heart, 350, null, "scaleY", 1, .9f, 1.05f, 1));
        Animator wiggle = anim(heart, 700, null, "rotation", 0, -7, 7, 0);
        Animator crouch = together(anim(heart, 200, null, "scaleX", 1, 1.1f), anim(heart, 200, null, "scaleY", 1, .86f),
                anim(heart, 200, null, "translationY", 0, 6 * d));
        crouch.setStartDelay(250);
        Animator jump = together(anim(heart, 260, new DecelerateInterpolator(), "translationY", 6 * d, -70 * d),
                anim(heart, 260, null, "scaleX", 1.1f, .95f), anim(heart, 260, null, "scaleY", .86f, 1.08f));
        Animator pop = together(anim(heart, 220, null, "scaleX", .95f, 1.35f), anim(heart, 220, null, "scaleY", 1.08f, 1.35f),
                anim(heart, 220, null, "alpha", 1, 0));
        AnimatorSet heartSet = new AnimatorSet();
        heartSet.playSequentially(rise, squash, wiggle, crouch, jump, pop);

        Animator bubbleIn = together(anim(bubble, 300, new OvershootInterpolator(), "alpha", 0, 1),
                anim(bubble, 300, new OvershootInterpolator(), "scaleX", .5f, 1), anim(bubble, 300, new OvershootInterpolator(), "scaleY", .5f, 1));
        bubbleIn.setStartDelay(650);
        Animator bubbleOut = together(anim(bubble, 250, null, "alpha", 1, 0), anim(bubble, 250, null, "translationY", 0, -10 * d));
        bubbleOut.setStartDelay(900);
        AnimatorSet bubbleSet = new AnimatorSet();
        bubbleSet.playSequentially(bubbleIn, bubbleOut);

        long burstAt = 450 + 350 + 700 + 250 + 200 + 260; // when the pop starts
        AnimatorSet burst = new AnimatorSet();
        AnimatorSet.Builder b = null;
        for (int i = 0; i < minis.length; i++) {
            double a = i * 2 * Math.PI / minis.length;
            float r = (70 + (i % 2) * 20) * d;
            Animator one = together(anim(minis[i], 900, new DecelerateInterpolator(), "translationX", 0, (float) (Math.cos(a) * r)),
                    anim(minis[i], 900, new DecelerateInterpolator(), "translationY", 0, (float) (Math.sin(a) * r - 30 * d)),
                    anim(minis[i], 900, null, "alpha", 0, 1, 1, 0));
            b = b == null ? burst.play(one) : b.with(one);
        }
        burst.setStartDelay(burstAt);

        AnimatorSet all = new AnimatorSet();
        all.playTogether(heartSet, bubbleSet, burst);
        all.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                try { wm.removeView(root); } catch (RuntimeException ignored) {}
            }
        });
        all.start();
    }

    /**
     * Your side of a nudge: a pink pulse and little hearts flying up from the heart you tapped on the widget (where
     * the launcher says the tap was). Needs the same overlay permission; without it the widget's pink flash is all.
     */
    static void sent(Context c, android.graphics.Rect at) {
        if (at == null || !Settings.canDrawOverlays(c)) return;
        WindowManager wm = c.getSystemService(WindowManager.class);
        float d = c.getResources().getDisplayMetrics().density;
        int w = (int) (220 * d), h = (int) (260 * d), size = Math.min(at.width(), at.height());
        FrameLayout root = new FrameLayout(c);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = at.centerX() - w / 2;
        lp.y = at.centerY() - h + (int) (60 * d); // the tapped heart sits near the bottom of this window
        int cy = h - (int) (60 * d); // its center, inside the window

        ImageView pulse = new ImageView(c);
        pulse.setImageResource(R.drawable.heart_on);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pp.topMargin = cy - size / 2;
        root.addView(pulse, pp);
        ImageView[] minis = new ImageView[7];
        int m = (int) (16 * d);
        for (int i = 0; i < minis.length; i++) {
            minis[i] = new ImageView(c);
            minis[i].setImageResource(R.drawable.heart_mini);
            minis[i].setAlpha(0f);
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(m, m, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            mp.topMargin = cy - m / 2;
            root.addView(minis[i], mp);
        }
        try { wm.addView(root, lp); } catch (RuntimeException e) { return; }

        AnimatorSet all = new AnimatorSet();
        AnimatorSet.Builder b = all.play(together(anim(pulse, 500, new DecelerateInterpolator(), "scaleX", .7f, 1.6f),
                anim(pulse, 500, new DecelerateInterpolator(), "scaleY", .7f, 1.6f), anim(pulse, 500, null, "alpha", 1, 0)));
        for (int i = 0; i < minis.length; i++) { // a fan of hearts rising up, a little apart in time
            float dx = (i - (minis.length - 1) / 2f) * 22 * d, rise = (140 + (i % 3) * 30) * d;
            Animator one = together(anim(minis[i], 900, new DecelerateInterpolator(), "translationX", 0, dx),
                    anim(minis[i], 900, new DecelerateInterpolator(), "translationY", 0, -rise),
                    anim(minis[i], 900, null, "alpha", 0, 1, 1, 0),
                    anim(minis[i], 900, null, "scaleX", .5f, 1.1f), anim(minis[i], 900, null, "scaleY", .5f, 1.1f));
            one.setStartDelay(i * 40L);
            b.with(one);
        }
        all.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                try { wm.removeView(root); } catch (RuntimeException ignored) {}
            }
        });
        all.start();
    }

    static Animator anim(View v, long ms, android.animation.TimeInterpolator in, String prop, float... values) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(prop, values));
        a.setDuration(ms);
        if (in != null) a.setInterpolator(in);
        return a;
    }

    static Animator together(Animator... parts) {
        AnimatorSet s = new AnimatorSet();
        s.playTogether(parts);
        return s;
    }
}
