package com.lionlev.tv.cpu;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import android.text.BidiFormatter;

public final class StatsActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private volatile Handler background;
    private TextView reading;
    private LinearLayout column;
    private volatile ShizukuCpu cpu;
    private boolean resumed;
    private LinearLayout controls;
    private Button toggle;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(48, 32, 48, 32);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        scroll.setBackgroundColor(Color.rgb(24,33,41));
        scroll.addView(column);
        setContentView(scroll);
        text(getString(R.string.app_name), 30);
        reading = text(getString(R.string.loading), 24);
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        column.addView(controls);
        toggle = button("", () -> {
            boolean enabled = !ShizukuCpu.enabled(this);
            getSharedPreferences(ShizukuCpu.PREFS, MODE_PRIVATE).edit().putBoolean(ShizukuCpu.ENABLED, enabled).apply();
            restartReader(); refreshControls();
        });
        column.removeView(toggle); controls.addView(toggle);
        Button authorize = button(getString(R.string.authorize), this::authorize);
        column.removeView(authorize); controls.addView(authorize);
        Button setup = button(getString(R.string.setup), () -> {
            try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/guide/setup/"))); }
            catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, R.string.help, Toast.LENGTH_LONG).show(); }
        });
        column.removeView(setup); controls.addView(setup);
        text(getString(R.string.help), 18);
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(permission);

        button(getString(R.string.back), this::finish);
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.START); view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        view.setPadding(12,18,12,18);
        column.addView(view, new LinearLayout.LayoutParams(-1,-2));
        return view;
    }
    private Button button(String value, Runnable action) {
        Button view = new Button(this); view.setText(value);
        view.setOnClickListener(v -> action.run());
        column.addView(view, new LinearLayout.LayoutParams(-1,-2));
        return view;
    }
    private String number(String value) { return BidiFormatter.getInstance().unicodeWrap(value, android.text.TextDirectionHeuristics.LTR); }
    @Override public void onResume() {
        super.onResume();
        resumed = true;
        refreshControls(); restartReader();
        worker = new HandlerThread("addon-stats"); worker.start();
        Handler current = new Handler(worker.getLooper()); background = current;
        current.post(new Runnable() {
            @Override public void run() {
                if (background != current) return;
                String value = sample();
                ui.post(() -> { if (background == current) reading.setText(value); });
                if (background == current) current.postDelayed(this, 10000);
            }
        });
    }
    @Override public void onPause() {
        resumed = false;
        background = null;
        if (worker != null) { worker.quitSafely(); worker = null; }
        ui.removeCallbacksAndMessages(null);
        if (cpu != null) { cpu.close(); cpu = null; }
        super.onPause();
    }
    private String sample() {
        ShizukuCpu source = cpu;
        int percent = source == null ? -1 : source.percent();
        return percent >= 0 ? getString(R.string.cpu, percent) : "";
    }
    private void restartReader() {
        if (cpu != null) { cpu.close(); cpu = null; }
        if (resumed && ShizukuCpu.enabled(this) && installed()) { cpu = new ShizukuCpu(this); cpu.start(); }
        reading.setText("");
    }
    private boolean installed() {
        try { getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }
    private void refreshControls() {
        controls.setVisibility(installed() ? View.VISIBLE : View.GONE);
        toggle.setText(getString(R.string.enable, getString(ShizukuCpu.enabled(this) ? R.string.on : R.string.off)));
    }
    private void authorize() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) Toast.makeText(this, R.string.help, Toast.LENGTH_LONG).show();
            else if (!ShizukuCpu.authorized()) rikka.shizuku.Shizuku.requestPermission(7301);
            else restartReader();
        } catch (RuntimeException e) { Toast.makeText(this, R.string.help, Toast.LENGTH_LONG).show(); }
    }
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener permission = (code, grant) -> {
        if (code == 7301) { refreshControls(); restartReader(); }
    };
    @Override public void onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permission);
        super.onDestroy();
    }

}
