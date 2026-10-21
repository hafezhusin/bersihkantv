package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, offline evaluator for launcher-local daily theme schedules.
 *
 * <p>Schema version 1 is:
 * <pre>
 * {"version":1,"timezone":"Area/City",
 *  "location":{"latitude":12.3,"longitude":45.6},
 *  "default_theme":{"background_color":"#000000"},
 *  "rules":[
 *   {"id":"evening","priority":10,
 *    "trigger":{"type":"fixed","time":"18:30"},
 *    "theme":{"active_collection":"0123456789abcdef0123456789abcdef"}},
 *   {"id":"sunset","priority":20,
 *    "trigger":{"type":"solar","event":"sunset","fallback_time":"19:00"},
 *    "theme":{"accent_color":"#ffffff"}}]}
 * }
 * </pre>
 * Theme objects are safe additive launcher overrides: background/accent colors, wallpaper enable,
 * dim percent, and an optional active collection id. Rotation settings are intentionally excluded.
 * Priorities are required, visible values from 0 through 100, and unique within a schedule.
 * Fixed local times in DST gaps move to the first valid local time; overlaps use the earlier offset.
 * Solar events use the NOAA approximation locally. Missing polar events resolve to the configured
 * fixed-time fallback and mark the resulting transition as such.
 */
final class ThemeSchedule {
    private static final Set<String> THEME_KEYS = new HashSet<>();
    static {
        Collections.addAll(THEME_KEYS, "background_color", "accent_color", "wallpaper",
                "wallpaper_dim", "active_collection");
    }

    private ThemeSchedule() {}

    static final class Rule {
        final String id;
        final int priority;
        final String type;
        final LocalTime time;
        final String solarEvent;
        final LocalTime fallbackTime;
        final JSONObject theme;

        Rule(String id, int priority, String type, LocalTime time, String solarEvent,
             LocalTime fallbackTime, JSONObject theme) {
            this.id = id;
            this.priority = priority;
            this.type = type;
            this.time = time;
            this.solarEvent = solarEvent;
            this.fallbackTime = fallbackTime;
            this.theme = theme;
        }
    }

    static final class Schedule {
        final ZoneId zone;
        final double latitude;
        final double longitude;
        final JSONObject defaultTheme;
        final List<Rule> rules;

        Schedule(ZoneId zone, double latitude, double longitude, JSONObject defaultTheme,
                 List<Rule> rules) {
            this.zone = zone;
            this.latitude = latitude;
            this.longitude = longitude;
            this.defaultTheme = defaultTheme;
            this.rules = Collections.unmodifiableList(rules);
        }
    }

    static final class Transition {
        final Instant instant;
        final String ruleId;
        final int priority;
        final JSONObject theme;
        final boolean fallback;

        Transition(Instant instant, Rule rule, JSONObject defaultTheme, boolean fallback) {
            this.instant = instant;
            this.ruleId = rule.id;
            this.priority = rule.priority;
            this.theme = merge(defaultTheme, rule.theme);
            this.fallback = fallback;
        }
    }

    static final class ManualOverride {
        final JSONObject theme;
        final Instant startedAt;

        ManualOverride(JSONObject theme, Instant startedAt) {
            validateTheme(theme, "manual override");
            if (startedAt == null) throw new IllegalArgumentException("manual override start is required");
            this.theme = copy(theme);
            this.startedAt = startedAt;
        }
    }

    static final class Effective {
        final JSONObject theme;
        final String ruleId;
        final Instant transitionAt;
        final boolean manual;
        final boolean fallback;

        Effective(JSONObject theme, String ruleId, Instant transitionAt, boolean manual,
                  boolean fallback) {
            this.theme = copy(theme);
            this.ruleId = ruleId;
            this.transitionAt = transitionAt;
            this.manual = manual;
            this.fallback = fallback;
        }
    }

    /** Validates the complete version 1 schedule and returns an immutable parsed schedule. */
    static Schedule validate(JSONObject json) {
        if (json == null) throw new IllegalArgumentException("schedule is required");
        exactKeys(json, "version", "timezone", "location", "default_theme", "rules");
        if (integer(json.opt("version"), 1, 1) != 1) throw new IllegalArgumentException("unsupported schedule version");
        ZoneId zone;
        try {
            zone = ZoneId.of(requiredString(json, "timezone"));
        } catch (DateTimeException failure) {
            throw new IllegalArgumentException("invalid timezone", failure);
        }
        JSONObject location = json.optJSONObject("location");
        if (location == null) throw new IllegalArgumentException("location is required");
        exactKeys(location, "latitude", "longitude");
        double latitude = coordinate(location.opt("latitude"), -90, 90, "latitude");
        double longitude = coordinate(location.opt("longitude"), -180, 180, "longitude");
        JSONObject defaultTheme = json.optJSONObject("default_theme");
        validateTheme(defaultTheme, "default theme");
        JSONArray sourceRules = json.optJSONArray("rules");
        if (sourceRules == null || sourceRules.length() > 64) throw new IllegalArgumentException("rules must be an array with at most 64 entries");

        List<Rule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<LocalTime> fixedTimes = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        for (int i = 0; i < sourceRules.length(); i++) {
            JSONObject source = sourceRules.optJSONObject(i);
            if (source == null) throw new IllegalArgumentException("each rule must be an object");
            exactKeys(source, "id", "priority", "trigger", "theme");
            String id = requiredString(source, "id");
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) throw new IllegalArgumentException("invalid or duplicate rule id");
            int priority = integer(source.opt("priority"), 0, 100);
            if (priority < 0 || !priorities.add(priority)) throw new IllegalArgumentException("priority must be a unique integer from 0 to 100");
            JSONObject trigger = source.optJSONObject("trigger");
            if (trigger == null) throw new IllegalArgumentException("rule trigger is required");
            String type = requiredString(trigger, "type");
            LocalTime time = null;
            String solarEvent = null;
            LocalTime fallback = null;
            if ("fixed".equals(type)) {
                exactKeys(trigger, "type", "time");
                time = parseTime(requiredString(trigger, "time"));
                if (!fixedTimes.add(time)) throw new IllegalArgumentException("duplicate fixed-time boundary");
            } else if ("solar".equals(type)) {
                exactKeys(trigger, "type", "event", "fallback_time");
                solarEvent = requiredString(trigger, "event");
                if (!"sunrise".equals(solarEvent) && !"sunset".equals(solarEvent)) throw new IllegalArgumentException("solar event must be sunrise or sunset");
                fallback = parseTime(requiredString(trigger, "fallback_time"));
            } else {
                throw new IllegalArgumentException("trigger type must be fixed or solar");
            }
            JSONObject theme = source.optJSONObject("theme");
            validateTheme(theme, "rule theme");
            rules.add(new Rule(id, priority, type, time, solarEvent, fallback, copy(theme)));
        }
        return new Schedule(zone, latitude, longitude, copy(defaultTheme), rules);
    }

    /** Returns the scheduled theme at now, or a manual theme until the first later transition. */
    static Effective effective(Schedule schedule, Instant now, ManualOverride manualOverride) {
        if (schedule == null || now == null) throw new IllegalArgumentException("schedule and time are required");
        Transition active = latest(schedule, now);
        if (manualOverride != null && !now.isBefore(manualOverride.startedAt)) {
            Transition next = nextAfter(schedule, manualOverride.startedAt);
            if (next == null || now.isBefore(next.instant)) {
                JSONObject base = active == null ? schedule.defaultTheme : active.theme;
                return new Effective(merge(base, manualOverride.theme), "manual", manualOverride.startedAt, true, false);
            }
        }
        if (active == null) return new Effective(schedule.defaultTheme, "default", null, false, false);
        return new Effective(active.theme, active.ruleId, active.instant, false, active.fallback);
    }

    static Effective effective(Schedule schedule, Instant now) {
        return effective(schedule, now, null);
    }

    static ManualOverride manualOverride(JSONObject json) {
        if (json == null) return null;
        exactKeys(json, "theme", "started_at");
        JSONObject theme = json.optJSONObject("theme");
        validateTheme(theme, "manual override");
        Object timestamp = json.opt("started_at");
        if (!(timestamp instanceof Number)) throw new IllegalArgumentException("manual override start must be epoch milliseconds");
        double millis = ((Number) timestamp).doubleValue();
        if (millis < 0 || millis > 4102444800000d || millis != Math.floor(millis)) throw new IllegalArgumentException("invalid manual override start");
        return new ManualOverride(theme, Instant.ofEpochMilli((long) millis));
    }

    /** Creates the preview payload used by both TV state and the companion protocol. */
    static JSONObject preview(JSONObject json, Instant now, int count) {
        return preview(json, now, count, null);
    }

    static JSONObject preview(JSONObject json, Instant now, int count, ManualOverride manualOverride) {
        try {
            Schedule schedule = validate(json);
            Effective current = effective(schedule, now, manualOverride);
            JSONObject effective = new JSONObject().put("theme", current.theme)
                    .put("rule_id", current.ruleId).put("manual", current.manual)
                    .put("fallback", current.fallback);
            if (current.transitionAt != null) effective.put("transition_at", current.transitionAt.toEpochMilli());
            JSONArray next = new JSONArray();
            for (Transition transition : upcoming(schedule, now, count)) {
                next.put(new JSONObject().put("at", transition.instant.toEpochMilli())
                        .put("rule_id", transition.ruleId).put("priority", transition.priority)
                        .put("theme", transition.theme).put("fallback", transition.fallback));
            }
            return new JSONObject().put("timezone", schedule.zone.getId())
                    .put("effective", effective).put("upcoming", next);
        } catch (Exception failure) {
            throw new IllegalArgumentException("could not preview schedule", failure);
        }
    }

    /** Returns the next count distinct effective boundaries after from, with collisions resolved. */
    static List<Transition> upcoming(Schedule schedule, Instant from, int count) {
        if (schedule == null || from == null || count < 0 || count > 256) throw new IllegalArgumentException("invalid upcoming transition request");
        if (count == 0) return Collections.emptyList();
        List<Transition> candidates = new ArrayList<>();
        LocalDate first = from.atZone(schedule.zone).toLocalDate();
        for (int day = 0; day < 370 && candidates.size() < count * 4 + 4; day++) {
            candidates.addAll(transitionsOn(schedule, first.plusDays(day)));
            List<Transition> resolved = resolve(candidates);
            int future = 0;
            for (Transition transition : resolved) if (transition.instant.isAfter(from)) future++;
            if (future >= count) return firstTransitions(resolved, from, count);
        }
        return firstTransitions(resolve(candidates), from, count);
    }

    private static List<Transition> firstTransitions(List<Transition> resolved, Instant from, int count) {
        List<Transition> result = new ArrayList<>();
        for (Transition transition : resolved) {
            if (transition.instant.isAfter(from)) result.add(transition);
            if (result.size() == count) break;
        }
        return Collections.unmodifiableList(result);
    }

    private static Transition latest(Schedule schedule, Instant now) {
        LocalDate date = now.atZone(schedule.zone).toLocalDate();
        List<Transition> candidates = new ArrayList<>();
        for (int offset = -2; offset <= 0; offset++) candidates.addAll(transitionsOn(schedule, date.plusDays(offset)));
        Transition latest = null;
        for (Transition transition : resolve(candidates)) {
            if (!transition.instant.isAfter(now) && (latest == null || transition.instant.isAfter(latest.instant))) latest = transition;
        }
        return latest;
    }

    private static Transition nextAfter(Schedule schedule, Instant instant) {
        List<Transition> transitions = upcoming(schedule, instant, 1);
        return transitions.isEmpty() ? null : transitions.get(0);
    }

    private static List<Transition> transitionsOn(Schedule schedule, LocalDate date) {
        List<Transition> transitions = new ArrayList<>();
        for (Rule rule : schedule.rules) {
            if ("fixed".equals(rule.type)) {
                transitions.add(new Transition(resolveLocal(date.atTime(rule.time), schedule.zone), rule, schedule.defaultTheme, false));
            } else {
                Instant solar = solarEvent(date, schedule.latitude, schedule.longitude, rule.solarEvent);
                boolean fallback = solar == null;
                if (fallback) solar = resolveLocal(date.atTime(rule.fallbackTime), schedule.zone);
                transitions.add(new Transition(solar, rule, schedule.defaultTheme, fallback));
            }
        }
        return transitions;
    }

    private static List<Transition> resolve(List<Transition> candidates) {
        candidates.sort(Comparator.comparing((Transition transition) -> transition.instant)
                .thenComparing((Transition transition) -> -transition.priority)
                .thenComparing(transition -> transition.ruleId));
        List<Transition> result = new ArrayList<>();
        for (Transition candidate : candidates) {
            if (!result.isEmpty() && result.get(result.size() - 1).instant.equals(candidate.instant)) continue;
            result.add(candidate);
        }
        return result;
    }

    private static Instant resolveLocal(LocalDateTime local, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.size() == 1) return local.toInstant(offsets.get(0));
        if (offsets.size() >= 2) return local.toInstant(offsets.get(0)); // earlier occurrence
        ZoneOffsetTransition gap = rules.getTransition(local);
        return gap.getDateTimeAfter().toInstant(gap.getOffsetAfter());
    }

    /** NOAA sunrise/sunset approximation; null means the event is absent for this local date. */
    private static Instant solarEvent(LocalDate date, double latitude, double longitude, String event) {
        double radians = Math.PI / 180.0;
        double gamma = 2.0 * Math.PI / 365.0 * (date.getDayOfYear() - 1);
        double equation = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double declination = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        double lat = latitude * radians;
        double cosHourAngle = (Math.cos(90.833 * radians) - Math.sin(lat) * Math.sin(declination))
                / (Math.cos(lat) * Math.cos(declination));
        if (cosHourAngle > 1.0 || cosHourAngle < -1.0 || Double.isNaN(cosHourAngle)) return null;
        double hourAngle = Math.acos(cosHourAngle) / radians;
        double minutes = 720 - 4 * longitude - equation
                + ("sunset".equals(event) ? 4 * hourAngle : -4 * hourAngle);
        long millis = Math.round(minutes * 60_000.0);
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis(millis);
    }

    private static void validateTheme(JSONObject theme, String label) {
        if (theme == null || theme.length() == 0) throw new IllegalArgumentException(label + " must be a nonempty object");
        for (String key : keys(theme)) {
            if (!THEME_KEYS.contains(key)) throw new IllegalArgumentException("unsupported theme key: " + key);
            Object value = theme.opt(key);
            boolean valid;
            switch (key) {
                case "wallpaper": valid = value instanceof Boolean; break;
                case "wallpaper_dim": valid = integer(value, 0, 100) >= 0; break;
                case "active_collection": valid = value instanceof String && ((String) value).matches("[a-f0-9]{32}"); break;
                default: valid = value instanceof String && ((String) value).matches("#[A-Fa-f0-9]{6}");
            }
            if (!valid) throw new IllegalArgumentException("invalid theme value: " + key);
        }
    }

    private static JSONObject merge(JSONObject base, JSONObject override) {
        JSONObject result = copy(base);
        for (String key : keys(override)) {
            try { result.put(key, override.get(key)); }
            catch (Exception failure) { throw new IllegalArgumentException("invalid theme", failure); }
        }
        return result;
    }

    private static void exactKeys(JSONObject object, String... allowed) {
        Set<String> keys = new HashSet<>(); Collections.addAll(keys, allowed);
        for (String key : keys(object)) if (!keys.contains(key)) throw new IllegalArgumentException("unknown field: " + key);
        for (String key : allowed) if (!object.has(key)) throw new IllegalArgumentException("missing field: " + key);
    }

    private static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    private static String requiredString(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return ((String) value).trim();
    }

    private static LocalTime parseTime(String value) {
        try {
            if (!value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) throw new DateTimeException("format");
            return LocalTime.parse(value);
        } catch (DateTimeException failure) {
            throw new IllegalArgumentException("time must be HH:mm", failure);
        }
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number)) return -1;
        double number = ((Number) value).doubleValue();
        return number >= min && number <= max && number == Math.floor(number) ? (int) number : -1;
    }

    private static double coordinate(Object value, double min, double max, String name) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("invalid " + name);
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < min || number > max) throw new IllegalArgumentException("invalid " + name);
        return number;
    }

    private static JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); }
        catch (Exception failure) { throw new IllegalArgumentException("invalid JSON object", failure); }
    }
}
