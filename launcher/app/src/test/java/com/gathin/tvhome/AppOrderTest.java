package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class AppOrderTest {

    @Test
    public void unknownValuesMeanCustom() {
        assertEquals(AppOrder.AZ, AppOrder.parse("az"));
        assertEquals(AppOrder.NEWEST, AppOrder.parse("newest"));
        assertEquals(AppOrder.CUSTOM, AppOrder.parse(null));
        assertEquals(AppOrder.CUSTOM, AppOrder.parse("AZ"));
    }

    @Test
    public void theDockKeepsItsOrder() {
        List<String> tiles = new ArrayList<>(Arrays.asList("YouTube", "Hotstar", "Prime", "MovieBox",
                "Plex", "AIDA64", "mx Player", "Cricfy"));
        AppOrder.sortFrom(tiles, 4, String.CASE_INSENSITIVE_ORDER);
        assertEquals(Arrays.asList("YouTube", "Hotstar", "Prime", "MovieBox",
                "AIDA64", "Cricfy", "mx Player", "Plex"), tiles);
    }

    @Test
    public void shortListsAndOddBoundaries() {
        List<String> two = new ArrayList<>(Arrays.asList("b", "a"));
        AppOrder.sortFrom(two, 4, Comparator.naturalOrder());
        assertEquals(Arrays.asList("b", "a"), two);
        AppOrder.sortFrom(two, -1, Comparator.naturalOrder());
        assertEquals(Arrays.asList("a", "b"), two);
    }
}
