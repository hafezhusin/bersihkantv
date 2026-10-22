package com.lionlev.tv.cpu;

/** Permission/lifecycle gate: unavailable sources never become a fabricated percentage. */
final class CpuReading {
    interface Source { long[] read() throws Exception; }
    private final CpuSample sample = new CpuSample();
    int percent(boolean active, boolean authorized, Source source) {
        if (!active || !authorized || source == null) { sample.reset(); return -1; }
        try { return sample.percent(source.read()); }
        catch (Exception e) { sample.reset(); return -1; }
    }
}
