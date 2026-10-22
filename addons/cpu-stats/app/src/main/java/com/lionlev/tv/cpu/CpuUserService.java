package com.lionlev.tv.cpu;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Shizuku user service: fixed read-only operation, no paths or commands accepted from clients. */
public final class CpuUserService extends ICpuReader.Stub {
    public CpuUserService() { }
    @Override public long[] readCounters() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            return CpuSample.parse(reader.readLine());
        } catch (IOException | SecurityException e) { return new long[0]; }
    }
    @Override public void destroy() { System.exit(0); }
}
