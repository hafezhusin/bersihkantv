package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThemeScheduleTest {
    private static JSONObject theme(String color) throws Exception {
        return new JSONObject().put("background_color", color);
    }

    private static JSONObject fixed(String id, int priority, String time, String color) throws Exception {
        return new JSONObject().put("id", id).put("priority", priority)
                .put("trigger", new JSONObject().put("type", "fixed").put("time", time))
                .put("theme", theme(color));
    }

    private static JSONObject solar(String id, int priority, String event, String fallback,
                                    String color) throws Exception {
        return new JSONObject().put("id", id).put("priority", priority)
                .put("trigger", new JSONObject().put("type", "solar").put("event", event)
                        .put("fallback_time", fallback)).put("theme", theme(color));
    }

    private static JSONObject schedule(String zone, double latitude, double longitude,
                                       JSONArray rules) throws Exception {
        return new JSONObject().put("version", 1).put("timezone", zone)
                .put("location", new JSONObject().put("latitude", latitude).put("longitude", longitude))
                .put("default_theme", theme("#000000")).put("rules", rules);
    }

    @Test public void polarMissingSunsetUsesLocalFallbackAndMarksIt() throws Exception {
        ThemeSchedule.Schedule parsed = ThemeSchedule.validate(schedule("Europe/Oslo", 69.65, 18.96,
                new JSONArray().put(solar("polar_sunset", 5, "sunset", "22:15", "#ffffff"))));
        Instant before = Instant.parse("2026-06-21T18:00:00Z");
        List<ThemeSchedule.Transition> next = ThemeSchedule.upcoming(parsed, before, 1);
        assertEquals(1, next.size());
        assertEquals(Instant.parse("2026-06-21T20:15:00Z"), next.get(0).instant);
        assertTrue(next.get(0).fallback);
        ThemeSchedule.Effective effective = ThemeSchedule.effective(parsed,
                Instant.parse("2026-06-21T20:16:00Z"));
        assertEquals("polar_sunset", effective.ruleId);
        assertTrue(effective.fallback);
    }

    @Test public void dstGapMovesToFirstValidTimeAndRepeatedTimeUsesEarlierOffsetOnce() throws Exception {
        ThemeSchedule.Schedule gap = ThemeSchedule.validate(schedule("America/New_York", 40.7, -74,
                new JSONArray().put(fixed("gap", 1, "02:30", "#111111"))));
        ThemeSchedule.Transition gapTransition = ThemeSchedule.upcoming(gap,
                Instant.parse("2026-03-08T05:00:00Z"), 1).get(0);
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), gapTransition.instant);

        ThemeSchedule.Schedule overlap = ThemeSchedule.validate(schedule("America/New_York", 40.7, -74,
                new JSONArray().put(fixed("repeat", 1, "01:30", "#222222"))));
        List<ThemeSchedule.Transition> transitions = ThemeSchedule.upcoming(overlap,
                Instant.parse("2026-11-01T04:00:00Z"), 2);
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), transitions.get(0).instant);
        assertFalse(transitions.get(1).instant.equals(Instant.parse("2026-11-01T06:30:00Z")));
    }

    @Test public void midnightAndExplicitTimezoneDetermineCurrentTheme() throws Exception {
        ThemeSchedule.Schedule parsed = ThemeSchedule.validate(schedule("Asia/Kolkata", 12.9, 77.6,
                new JSONArray().put(fixed("midnight", 1, "00:15", "#abcdef"))));
        ThemeSchedule.Effective before = ThemeSchedule.effective(parsed,
                Instant.parse("2026-01-01T18:40:00Z")); // Jan 2, 00:10 in the saved timezone.
        assertEquals("midnight", before.ruleId); // The prior day's transition remains effective.
        ThemeSchedule.Effective after = ThemeSchedule.effective(parsed,
                Instant.parse("2026-01-01T18:50:00Z"));
        assertEquals("midnight", after.ruleId);
        assertEquals(Instant.parse("2026-01-01T18:45:00Z"), after.transitionAt);
        assertEquals("#abcdef", after.theme.getString("background_color"));
    }

    @Test public void coincidentSolarAndFixedBoundariesUseHigherVisiblePriorityOnce() throws Exception {
        ThemeSchedule.Schedule solarOnly = ThemeSchedule.validate(schedule("America/Chicago", 41.9, -87.6,
                new JSONArray().put(solar("sunrise", 20, "sunrise", "06:00", "#eeeeee"))));
        Instant from = Instant.parse("2026-04-10T00:00:00Z");
        Instant rise = ThemeSchedule.upcoming(solarOnly, from, 1).get(0).instant;
        String matchingLocalMinute = LocalTime.ofInstant(rise, solarOnly.zone).withSecond(0)
                .withNano(0).toString();
        JSONObject combined = schedule("America/Chicago", 41.9, -87.6,
                new JSONArray().put(fixed("fixed", 10, matchingLocalMinute, "#111111"))
                        .put(solar("sunrise", 20, "sunrise", "06:00", "#eeeeee")));
        ThemeSchedule.Schedule parsed = ThemeSchedule.validate(combined);
        ThemeSchedule.Effective effective = ThemeSchedule.effective(parsed, rise.plusSeconds(1));
        assertEquals("sunrise", effective.ruleId);
        assertEquals("#eeeeee", effective.theme.getString("background_color"));
        assertEquals(1, ThemeSchedule.upcoming(parsed, rise.minusSeconds(1), 1).size());
    }

    @Test public void manualOverrideExpiresAtTheNextTransition() throws Exception {
        ThemeSchedule.Schedule parsed = ThemeSchedule.validate(schedule("UTC", 0, 0,
                new JSONArray().put(fixed("morning", 1, "08:00", "#112233"))));
        Instant started = Instant.parse("2026-05-04T07:00:00Z");
        ThemeSchedule.ManualOverride override = new ThemeSchedule.ManualOverride(theme("#445566"), started);
        ThemeSchedule.Effective active = ThemeSchedule.effective(parsed,
                Instant.parse("2026-05-04T07:59:59Z"), override);
        assertTrue(active.manual);
        assertEquals("#445566", active.theme.getString("background_color"));
        ThemeSchedule.Effective expired = ThemeSchedule.effective(parsed,
                Instant.parse("2026-05-04T08:00:00Z"), override);
        assertFalse(expired.manual);
        assertEquals("morning", expired.ruleId);
    }

    @Test public void ruleThemeMergesOnTopOfDefaultAndManualFieldsLayerOnCurrentTheme() throws Exception {
        JSONObject base = new JSONObject().put("background_color", "#101010")
                .put("accent_color", "#202020");
        JSONObject ruleTheme = new JSONObject().put("active_collection", "0123456789abcdef0123456789abcdef");
        JSONObject rule = new JSONObject().put("id", "accent").put("priority", 1)
                .put("trigger", new JSONObject().put("type", "fixed").put("time", "08:00"))
                .put("theme", ruleTheme);
        ThemeSchedule.Schedule parsed = ThemeSchedule.validate(new JSONObject()
                .put("version", 1).put("timezone", "UTC")
                .put("location", new JSONObject().put("latitude", 0).put("longitude", 0))
                .put("default_theme", base).put("rules", new JSONArray().put(rule)));
        ThemeSchedule.Effective scheduled = ThemeSchedule.effective(parsed,
                Instant.parse("2026-05-04T09:00:00Z"));
        assertEquals("#101010", scheduled.theme.getString("background_color"));
        assertEquals("#202020", scheduled.theme.getString("accent_color"));
        assertEquals("0123456789abcdef0123456789abcdef", scheduled.theme.getString("active_collection"));

        ThemeSchedule.ManualOverride override = new ThemeSchedule.ManualOverride(
                new JSONObject().put("accent_color", "#303030"), Instant.parse("2026-05-04T09:30:00Z"));
        ThemeSchedule.Effective manual = ThemeSchedule.effective(parsed,
                Instant.parse("2026-05-04T09:45:00Z"), override);
        assertEquals("#101010", manual.theme.getString("background_color"));
        assertEquals("#303030", manual.theme.getString("accent_color"));
        assertEquals("0123456789abcdef0123456789abcdef", manual.theme.getString("active_collection"));
    }

    @Test public void manualOverrideWithNoRulesLastsUntilCancellation() throws Exception {
        ThemeSchedule.Schedule parsed = ThemeSchedule.validate(schedule("UTC", 0, 0, new JSONArray()));
        ThemeSchedule.ManualOverride override = new ThemeSchedule.ManualOverride(theme("#445566"),
                Instant.parse("2026-05-04T07:00:00Z"));
        ThemeSchedule.Effective manual = ThemeSchedule.effective(parsed,
                Instant.parse("2030-01-01T00:00:00Z"), override);
        assertTrue(manual.manual);
        assertEquals("#445566", manual.theme.getString("background_color"));
        assertFalse(ThemeSchedule.effective(parsed, Instant.parse("2030-01-01T00:00:00Z")).manual);
    }

    @Test public void fixedTransitionsRecomputeAgainstTheSavedTimezone() throws Exception {
        ThemeSchedule.Schedule parsed = ThemeSchedule.validate(schedule("Pacific/Auckland", -36.85, 174.76,
                new JSONArray().put(fixed("morning", 1, "08:00", "#123456"))));
        ThemeSchedule.Transition first = ThemeSchedule.upcoming(parsed,
                Instant.parse("2026-01-01T00:00:00Z"), 1).get(0);
        ThemeSchedule.Schedule changedZone = ThemeSchedule.validate(schedule("America/Los_Angeles", -36.85, 174.76,
                new JSONArray().put(fixed("morning", 1, "08:00", "#123456"))));
        ThemeSchedule.Transition second = ThemeSchedule.upcoming(changedZone,
                Instant.parse("2026-01-01T00:00:00Z"), 1).get(0);
        assertNotNull(first);
        assertNotNull(second);
        assertFalse(first.instant.equals(second.instant));
    }

    @Test public void invalidSchemaDuplicateFixedTimeAndInvalidPriorityAreRejected() throws Exception {
        expectInvalid(() -> ThemeSchedule.validate(schedule("UTC", 0, 0,
                new JSONArray().put(fixed("one", 1, "08:00", "#111111"))
                        .put(fixed("two", 2, "08:00", "#222222")))));
        JSONObject invalidPriority = fixed("bad", 101, "08:00", "#111111");
        expectInvalid(() -> ThemeSchedule.validate(schedule("UTC", 0, 0,
                new JSONArray().put(invalidPriority))));
        expectInvalid(() -> ThemeSchedule.validate(schedule("UTC", 0, 0,
                new JSONArray().put(fixed("first", 5, "08:00", "#111111"))
                        .put(fixed("second", 5, "09:00", "#222222")))));
        expectInvalid(() -> ThemeSchedule.validate(schedule("Not/AZone", 0, 0, new JSONArray())));
        expectInvalid(() -> ThemeSchedule.validate(schedule("UTC", 91, 0, new JSONArray())));
    }

    private static void expectInvalid(Throwing action) throws Exception {
        try {
            action.run();
            fail("expected invalid schedule");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private interface Throwing { void run() throws Exception; }
}
