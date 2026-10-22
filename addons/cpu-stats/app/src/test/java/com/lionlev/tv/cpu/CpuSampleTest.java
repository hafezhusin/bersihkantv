package com.lionlev.tv.cpu;
import org.junit.Test;
import static org.junit.Assert.*;
public class CpuSampleTest {
    @Test public void excludesGuestAndIncludesSteal() {
        assertArrayEquals(new long[]{360, 220}, CpuSample.parse("cpu 100 10 20 200 20 2 3 5 999 999"));
    }
    @Test public void requiresTwoSamplesAndCalculatesBusyPercent() {
        CpuSample sample = new CpuSample();
        assertEquals(-1, sample.percent(new long[]{100, 50}));
        assertEquals(25, sample.percent(new long[]{200, 125}));
    }
    @Test public void rejectsMalformedCountersAndOverflow() {
        for (String line : new String[]{null, "cpu0 1 2 3 4", "cpu x 2 3 4", "cpu -1 2 3 4", "cpu 9223372036854775807 1 1 1"})
            assertEquals(0, CpuSample.parse(line).length);
    }
    @Test public void sourceLossRequiresNewBaseline() {
        CpuSample sample = new CpuSample();
        sample.percent(new long[]{100,50});
        assertEquals(-1, sample.percent(new long[0]));
        assertEquals(-1, sample.percent(new long[]{200,100}));
        assertEquals(0, sample.percent(new long[]{300,200}));
    }
    @Test public void resetCountersAndImpossibleDeltasAreNotFabricated() {
        CpuSample sample = new CpuSample();
        sample.percent(new long[]{100,50});
        assertEquals(-1,sample.percent(new long[]{100,50}));
        assertEquals(-1,sample.percent(new long[]{50,25}));
        assertEquals(-1,sample.percent(new long[]{60,50}));
        assertEquals(-1,sample.percent(new long[]{10,20}));
    }
}
