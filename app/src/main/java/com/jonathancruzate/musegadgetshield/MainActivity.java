package com.jonathancruzate.musegadgetshield;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Couch UI: status plus the "Voice relay to phone" toggle (v0.5.0).
 * There is deliberately NO Talk button (Jonathan's design change,
 * 2026-11-08): the toggle flip itself is the relay signal. Switching
 * the toggle ON persists the mode, strips this app's own RECORD_AUDIO
 * access, gates off every capture path, and fires GadgetService's
 * relay-activation action, which sends the relay context message
 * exactly once for that enable. Switching OFF sends nothing. The
 * background service (started here, as before) owns the BLE/link
 * stack and performs the send.
 */
public class MainActivity extends Activity {
    private TextView statusView;
    // Living-room board area (v0.10.0): refreshed from BoardLink state
    // on a light timer while this screen is up; the lane itself lives
    // in GadgetService and stays inert with no board attached.
    private TextView boardView;
    private final Handler boardHandler = new Handler(Looper.getMainLooper());
    private final Runnable boardRefresh = new Runnable() {
        @Override public void run() {
            updateBoardView();
            boardHandler.postDelayed(this, 2000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TV geometry (learned live on v0.7.0): this build's window
        // is 960x540 dp tall — the vertical budget below is trimmed
        // to fit it (~525 dp), so the status line is never clipped.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        root.setPadding(pad, dp(24), pad, dp(24));
        root.setBackgroundColor(Color.rgb(16, 18, 24));

        // Brand header (v0.6.0): the authentic Muse logo — extracted
        // from the Muse app itself, see res/drawable/muse_logo.xml —
        // on its white chip, next to the Muse wordmark.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout chip = new FrameLayout(this);
        chip.setBackgroundResource(R.drawable.brand_chip_bg);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.muse_logo);
        int logoPad = dp(6);
        logo.setPadding(logoPad, logoPad, logoPad, logoPad);
        chip.addView(logo, new FrameLayout.LayoutParams(dp(52), dp(52)));
        header.addView(chip, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView wordmark = new TextView(this);
        wordmark.setText("Muse");
        wordmark.setTextSize(30);
        wordmark.setTextColor(Color.WHITE);
        wordmark.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wordParams.leftMargin = dp(14);
        header.addView(wordmark, wordParams);
        root.addView(header);

        TextView title = new TextView(this);
        title.setText("Muse Gadget — Shield TV");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(10);
        root.addView(title, titleParams);

        // Voice mode entry (v0.6.0; promoted v0.7.0): the FIRST,
        // largest, auto-focused element on this screen — after the
        // v0.6.0 live test, where it sat below the relay controls
        // and was effectively lost. The remote's mic button also
        // reaches voice mode, via the default-assist role
        // (MuseVoiceSession); this button is the visible path.
        final Button voiceButton = new Button(this);
        voiceButton.setText("Voice mode");
        voiceButton.setTextSize(34);
        voiceButton.setFocusable(true);
        voiceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, VoiceActivity.class));
            }
        });
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96));
        voiceParams.topMargin = dp(16);
        root.addView(voiceButton, voiceParams);

        TextView info = new TextView(this);
        // The version line reads the manifest's versionName so it
        // can never go stale again (it sat at v0.7.0 through two
        // releases as a hardcoded string).
        String version = "unknown";
        try {
            version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        info.setText("Muse Gadget for Shield TV v" + version + "\n"
                + "Full gadget stack: pairing v5 setup over BLE + Noise link session.\n"
                + "Unpaired: pair window opens for 10 minutes (MuseGadget-FA76F8).\n"
                + "Paired: the link session runs in the background service.\n"
                + "Watch logcat tag MuseGadgetShield for state.");
        info.setTextSize(20);
        info.setTextColor(Color.rgb(190, 196, 208));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.topMargin = dp(12);
        root.addView(info, infoParams);

        final Switch relaySwitch = new Switch(this);
        relaySwitch.setText("Voice relay to phone");
        relaySwitch.setTextSize(26);
        relaySwitch.setTextColor(Color.WHITE);
        relaySwitch.setFocusable(true);
        relaySwitch.setPadding(0, dp(10), 0, dp(10));
        relaySwitch.setChecked(VoiceRelay.isOn(this));
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        switchParams.topMargin = dp(16);
        root.addView(relaySwitch, switchParams);

        statusView = new TextView(this);
        statusView.setTextSize(22);
        statusView.setTextColor(Color.rgb(150, 220, 160));
        statusView.setText(stateText(VoiceRelay.isOn(this)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(12);
        root.addView(statusView, statusParams);

        // Attach the listener AFTER the initial setChecked and after
        // statusView exists: restoring a persisted ON state at startup
        // must NOT re-fire the enable side effects — the relay message
        // is sent only on a real OFF→ON flip in this UI.
        relaySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                VoiceRelay.setOn(MainActivity.this, isChecked);
                if (isChecked) {
                    // Give up being a microphone endpoint: strip our own
                    // RECORD_AUDIO access; capture paths gate on the flag.
                    VoiceRelay.stripMicPermission(MainActivity.this);
                    // The flip IS the relay signal: have the service send
                    // the relay activation message (once, for this enable).
                    Intent relay = new Intent(MainActivity.this, GadgetService.class);
                    relay.setAction(GadgetService.ACTION_RELAY_ON);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(relay);
                    else startService(relay);
                    statusView.setText("Voice relay ON — microphone stripped on "
                            + "this TV; voice activation sent to your phone.");
                } else {
                    // Switching OFF sends nothing.
                    statusView.setText(stateText(false));
                }
            }
        });

        // Living-room board status (v0.10.0): compact area under the
        // relay status — connection state, last STATUS_RESP fields,
        // last button gesture, last mode — plus Ping / Status buttons
        // that call BoardLink's requestStatus() / ping(). Inert text
        // ("no board") when nothing is attached. v0.11.0 adds the
        // LED / mode rows below (BoardLink's setLed() / setMode()
        // had no app surface in v0.10.0).
        boardView = new TextView(this);
        boardView.setTextSize(16);
        boardView.setTextColor(Color.rgb(170, 200, 230));
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        boardParams.topMargin = dp(12);
        root.addView(boardView, boardParams);

        LinearLayout boardButtons = new LinearLayout(this);
        boardButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button boardPing = new Button(this);
        boardPing.setText("Board ping");
        boardPing.setTextSize(18);
        boardPing.setFocusable(true);
        boardPing.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BoardLink link = GadgetService.activeBoardLink;
                if (link != null) link.ping();
                updateBoardView();
            }
        });
        boardButtons.addView(boardPing, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button boardStatus = new Button(this);
        boardStatus.setText("Board status");
        boardStatus.setTextSize(18);
        boardStatus.setFocusable(true);
        boardStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BoardLink link = GadgetService.activeBoardLink;
                if (link != null) link.requestStatus();
                updateBoardView();
            }
        });
        LinearLayout.LayoutParams boardStatusParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        boardStatusParams.leftMargin = dp(12);
        boardButtons.addView(boardStatus, boardStatusParams);
        LinearLayout.LayoutParams boardButtonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        boardButtonsParams.topMargin = dp(8);
        root.addView(boardButtons, boardButtonsParams);

        // Board LED + mode controls (v0.11.0): v0.10.0 surfaced only
        // Ping / Status, so BoardLink's setLed() / setMode() stayed
        // code-only and the Phase 3 gate 5 check (LED_SET / MODE_SET
        // effective FROM THE APP) had no control to drive. Two
        // compact rows in the same style wire them up: LED patterns
        // 0-4 (the firmware's lr.led_set ids: 0 link-down, 1 idle,
        // 2 pilot, 3 night, 4 ack) and the protocol modes
        // (BoardProtocol.MODE_*). A mode tap proves its round trip
        // on screen when the board's MODE_EVENT push updates the
        // "Mode:" field in the status text above.
        LinearLayout ledRow = new LinearLayout(this);
        ledRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] ledLabels = {
                "LED down", "LED idle", "LED pilot", "LED night", "LED ack"};
        for (int i = 0; i < ledLabels.length; i++) {
            final int pattern = i;
            Button ledButton = boardControlButton(ledLabels[i], 16);
            ledButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    BoardLink link = GadgetService.activeBoardLink;
                    if (link != null) link.setLed(pattern);
                    updateBoardView();
                }
            });
            ledRow.addView(ledButton, boardRowButtonParams(i == 0));
        }
        LinearLayout.LayoutParams ledRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ledRowParams.topMargin = dp(8);
        root.addView(ledRow, ledRowParams);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] modeLabels = {"Mode idle", "Mode pilot", "Mode night"};
        final int[] modeValues = {
                BoardProtocol.MODE_IDLE,
                BoardProtocol.MODE_PILOT,
                BoardProtocol.MODE_NIGHT};
        for (int i = 0; i < modeLabels.length; i++) {
            final int mode = modeValues[i];
            Button modeButton = boardControlButton(modeLabels[i], 18);
            modeButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    BoardLink link = GadgetService.activeBoardLink;
                    if (link != null) link.setMode(mode);
                    updateBoardView();
                }
            });
            modeRow.addView(modeButton, boardRowButtonParams(i == 0));
        }
        LinearLayout.LayoutParams modeRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        modeRowParams.topMargin = dp(8);
        root.addView(modeRow, modeRowParams);

        // The board area pushes the column past the 960x540 dp window,
        // so the column scrolls rather than clipping the relay status.
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        // Voice mode is the primary action: focus lands on it.
        voiceButton.requestFocus();
        Intent svc = new Intent(this, GadgetService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        updateBoardView();
    }

    @Override protected void onResume() {
        super.onResume();
        boardHandler.removeCallbacks(boardRefresh);
        boardHandler.post(boardRefresh);
    }

    @Override protected void onPause() {
        boardHandler.removeCallbacks(boardRefresh);
        super.onPause();
    }

    private void updateBoardView() {
        if (boardView == null) return;
        BoardLink link = GadgetService.activeBoardLink;
        boardView.setText(link != null
                ? link.boardSummary()
                : "Living-room board: service starting…");
    }

    /** Small focusable button in the board area's style (v0.11.0). */
    private Button boardControlButton(String text, float textSize) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(textSize);
        b.setFocusable(true);
        return b;
    }

    /** Equal-weight slot in a board control row; gaps between buttons. */
    private LinearLayout.LayoutParams boardRowButtonParams(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (!first) p.leftMargin = dp(8);
        return p;
    }

    private static String stateText(boolean relayOn) {
        return relayOn
                ? "Voice relay is ON — this TV will not use a microphone; "
                        + "voice mode runs on your phone."
                : "Voice relay is OFF — normal gadget; microphone permission intact.";
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
