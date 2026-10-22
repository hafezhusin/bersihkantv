package com.lionlev.tv.cpu;
import org.junit.Test;
import static org.junit.Assert.*;
public class CpuReadingTest {
    @Test public void stoppedAndDeniedNeverRead() {
        CpuReading reading = new CpuReading();
        CpuReading.Source forbidden = () -> { fail("Source must not run"); return null; };
        assertEquals(-1, reading.percent(false,true,forbidden));
        assertEquals(-1, reading.percent(true,false,forbidden));
        assertEquals(-1, reading.percent(true,true,null));
    }
    @Test public void denialOrServiceFailureResetsTheBaseline() {
        CpuReading reading = new CpuReading();
        assertEquals(-1,reading.percent(true,true,() -> new long[]{100,50}));
        assertEquals(-1,reading.percent(true,false,() -> new long[]{200,100}));
        assertEquals(-1,reading.percent(true,true,() -> new long[]{300,150}));
        assertEquals(50,reading.percent(true,true,() -> new long[]{400,200}));
        assertEquals(-1,reading.percent(true,true,() -> { throw new IllegalStateException("Service died"); }));
        assertEquals(-1,reading.percent(true,true,() -> new long[]{500,250}));
    }
    @Test public void pauseRequiresFreshBaselineOnResume() {
        CpuReading reading = new CpuReading();
        reading.percent(true,true,() -> new long[]{100,50});
        assertEquals(-1,reading.percent(false,true,null));
        assertEquals(-1,reading.percent(true,true,() -> new long[]{300,150}));
    }
}
