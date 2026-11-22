package com.jonathancruzate.musegadgetshield;

import android.content.Context;
import android.graphics.Color;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The shared Muse voice UI: one view hierarchy hosted by both
 * VoiceActivity (the explicit voice-mode screen) and
 * MuseVoiceSession (the system voice-interaction plate shown when
 * the remote's mic button invokes this app as the default
 * assistant). VoiceController drives these views; the hosts only
 * own lifecycle.
 *
 * Layout (v0.8.1): NO brand header on the voice screen (Jonathan's
 * order — the MainActivity branding is untouched): the avatar as
 * the voice face, the badge slot, the state line, and the
 * captions. The mic glyph pair drawn in the avatar view itself
 * carries the mic states — "instead of the rectangle and
 * text" (Jonathan's orders): MUTED (v0.9.3) shows the slashed
 * mic-off glyph with no badge and no state text, and LISTENING
 * (v0.9.4) shows the same glyph without the slash, so the
 * badge (kept in the layout, INVISIBLE in every state, purely
 * so nothing below it moves) no longer appears for listening
 * either; the "Listening" hint line stays (v0.9.5: Jonathan
 * had "— just speak" removed; the line reads simply
 * "Listening"). The
 * reply caption lives in a bounded, auto-scrolling region sized
 * to the TV-safe area: it follows the sentence currently being
 * spoken (the sentence-streaming sync point), earlier text
 * scrolls above, and nothing ever renders off-screen — the
 * v0.7.0 layout let long replies run off the bottom.
 */
final class VoiceUi {
    final LinearLayout root;
    final SdkAvatarView avatarView;
    final TextView listeningBadge;
    final TextView stateView;
    final TextView heardView;
    final TextView replyView;
    final ScrollView replyScroll;

    private VoiceUi(LinearLayout root, SdkAvatarView avatarView, TextView listeningBadge,
            TextView stateView, TextView heardView, TextView replyView,
            ScrollView replyScroll) {
        this.root = root;
        this.avatarView = avatarView;
        this.listeningBadge = listeningBadge;
        this.stateView = stateView;
        this.heardView = heardView;
        this.replyView = replyView;
        this.replyScroll = replyScroll;
    }

    static VoiceUi build(Context context) {
        // TV geometry (learned live on v0.7.0): this build's window
        // is 960x540 dp (1920x1080 at 320 dpi) — NOT 1080 dp tall.
        // Vertical budget below sums to exactly 540 dp:
        //   root padding 14+16, avatar 236+10, badge 48+10,
        //   state ~34+8, heard ≤52+8, reply region 98+6.
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        // 48 dp sides keep every caption inside TV overscan.
        int pad = dp(context, 48);
        root.setPadding(pad, dp(context, 14), pad, dp(context, 16));
        // ACTUAL black (v0.9.2, Jonathan's spec): pure #000000 —
        // no gradient, no blue. The avatar's background cells use
        // the same pure black (SdkAvatarView), so the face, the
        // badge, and the captions render directly on black.
        root.setBackgroundColor(Color.BLACK);

        // The face is the Muse Gadget SDK's avatar system
        // (SdkAvatarView — the procedural, state-driven renderer
        // ported from esp32/avatar/muse_pixel.c), NOT a static
        // picture: it listens, thinks and speaks with the loop.
        // The character it draws is the paired Muse's own
        // avatar, Stef (v0.8.0, per the SDK's AVATAR_RECIPE) —
        // never the SDK's default creature.
        SdkAvatarView avatarView = new SdkAvatarView(context);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                dp(context, 236), dp(context, 236));
        avatarParams.topMargin = dp(context, 10);
        root.addView(avatarView, avatarParams);

        // The former listening badge (it was the unmissable
        // indicator through v0.9.3): since v0.9.4 the mic glyph
        // in the avatar view carries LISTENING as well as MUTED,
        // so VoiceController never makes this view visible — it
        // stays in the layout so the spacing below is unchanged.
        TextView listeningBadge = new TextView(context);
        listeningBadge.setText("● LISTENING");
        listeningBadge.setTextSize(26);
        listeningBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        listeningBadge.setTextColor(Color.rgb(8, 24, 38));
        listeningBadge.setBackgroundColor(Color.rgb(120, 200, 255));
        listeningBadge.setGravity(Gravity.CENTER);
        listeningBadge.setSingleLine(true);
        listeningBadge.setIncludeFontPadding(true);
        int badgePadH = dp(context, 22);
        listeningBadge.setPadding(badgePadH, 0, badgePadH, 0);
        listeningBadge.setVisibility(View.INVISIBLE);
        // Fixed height: the badge's text can never be squeezed.
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 48));
        badgeParams.topMargin = dp(context, 10);
        root.addView(listeningBadge, badgeParams);

        TextView stateView = new TextView(context);
        stateView.setTextSize(23);
        stateView.setTextColor(Color.rgb(120, 200, 255));
        stateView.setGravity(Gravity.CENTER);
        stateView.setSingleLine(true);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stateParams.topMargin = dp(context, 8);
        root.addView(stateView, stateParams);

        TextView heardView = new TextView(context);
        heardView.setTextSize(20);
        heardView.setTextColor(Color.rgb(190, 196, 208));
        heardView.setGravity(Gravity.CENTER);
        // Bounded: at most two lines, so a long transcript can
        // never push the reply region off-screen.
        heardView.setMaxLines(2);
        heardView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams heardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        heardParams.topMargin = dp(context, 8);
        root.addView(heardView, heardParams);

        // The reply caption: a bounded scrolling region, not an
        // ever-growing text view. scrollReplyTo() keeps the
        // currently spoken sentence in view.
        TextView replyView = new TextView(context);
        replyView.setTextSize(22);
        replyView.setTextColor(Color.WHITE);
        replyView.setGravity(Gravity.CENTER_HORIZONTAL);
        ScrollView replyScroll = new ScrollView(context);
        replyScroll.addView(replyView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 98));
        scrollParams.topMargin = dp(context, 6);
        root.addView(replyScroll, scrollParams);

        return new VoiceUi(root, avatarView, listeningBadge, stateView,
                heardView, replyView, replyScroll);
    }

    /**
     * Scroll the reply region so the sentence starting at
     * {@code charOffset} in the caption text is visible. Called
     * from the controller when an utterance starts — the spoken
     * sentence is always on screen, earlier text above it.
     */
    void scrollReplyTo(final int charOffset) {
        replyScroll.post(new Runnable() {
            @Override public void run() {
                Layout layout = replyView.getLayout();
                CharSequence text = replyView.getText();
                if (layout == null || text == null || text.length() == 0) return;
                int offset = Math.max(0, Math.min(charOffset, text.length() - 1));
                int line = layout.getLineForOffset(offset);
                int y = layout.getLineTop(line);
                replyScroll.smoothScrollTo(0, Math.max(0, y - dp(replyView.getContext(), 4)));
            }
        });
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, context.getResources().getDisplayMetrics());
    }
}
