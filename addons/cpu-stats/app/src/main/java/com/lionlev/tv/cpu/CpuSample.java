package com.lionlev.tv.cpu;

/** Aggregate system CPU ticks; guest time is already counted in user/nice. */
final class CpuSample {
    private long total = -1, idle;
    static long[] parse(String line) {
        if (line == null) return new long[0];
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 5 || !fields[0].equals("cpu")) return new long[0];
        try {
            long sum = 0, idle = 0;
            for (int i = 1; i < Math.min(fields.length, 9); i++) {
                long n = Long.parseLong(fields[i]);
                if (n < 0) return new long[0];
                sum = Math.addExact(sum, n);
                if (i == 4 || i == 5) idle = Math.addExact(idle, n);
            }
            return new long[]{sum, idle};
        } catch (NumberFormatException | ArithmeticException e) { return new long[0]; }
    }
    int percent(long[] sample) {
        if (sample == null || sample.length != 2 || sample[0] < 0 || sample[1] < 0 || sample[1] > sample[0]) {
            reset(); return -1;
        }
        long dt = sample[0] - total, di = sample[1] - idle;
        boolean first = total < 0;
        total = sample[0]; idle = sample[1];
        if (first || dt <= 0 || di < 0 || di > dt) return -1;
        return (int) Math.round(100.0 * (dt - di) / dt);
    }
    void reset() { total = -1; idle = 0; }
}
