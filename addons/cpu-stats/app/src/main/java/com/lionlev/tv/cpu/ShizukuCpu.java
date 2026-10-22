package com.lionlev.tv.cpu;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import rikka.shizuku.Shizuku;

/** Bound only during visible add-on screen. Permission is requested exclusively from this add-on. */
final class ShizukuCpu implements AutoCloseable {
    static final String PREFS = "stats_privileges", ENABLED = "shizuku_cpu";
    private volatile ICpuReader reader;
    private volatile boolean active;
    private boolean binding;
    private final CpuReading reading = new CpuReading();
    private final Shizuku.UserServiceArgs args;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (active) reader = ICpuReader.Stub.asInterface(binder);
        }
        @Override public void onServiceDisconnected(ComponentName name) { reader = null; binding = false; }
    };
    private final Shizuku.OnBinderReceivedListener received = this::bind;
    private final Shizuku.OnBinderDeadListener dead = () -> { reader = null; binding = false; };

    ShizukuCpu(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, CpuUserService.class))
                .daemon(false).processNameSuffix("cpu_stats").tag("cpu-stats").version(1);
    }
    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }
    static boolean authorized() {
        try { return Shizuku.pingBinder() && !Shizuku.isPreV11()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }
    void start() {
        active = true;
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
    }
    private void bind() {
        if (!active || binding || !authorized()) return;
        try { binding = true; Shizuku.bindUserService(args, connection); }
        catch (RuntimeException e) { binding = false; reader = null; }
    }
    int percent() {
        ICpuReader current = reader;
        int result = reading.percent(active, authorized(), current == null ? null : current::readCounters);
        return active && reader == current ? result : -1;
    }
    @Override public void close() {
        active = false;
        reader = null;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        try { if (binding) Shizuku.unbindUserService(args, connection, true); }
        catch (RuntimeException ignored) { }
        binding = false;
    }
}
