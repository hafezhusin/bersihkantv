package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The small, transport-free half of the companion protocol: envelope limits, canonical JSON for
 * revisions and the stable reply shape. Kept out of {@link CompanionProvider} so plain JVM tests can
 * exercise it without Android.
 */
final class CompanionProtocol {
    static final int VERSION = 1;
    /** Decoded request and reply envelopes are capped at 256 KiB. */
    static final int MAX_ENVELOPE = 256 * 1024;
    /** Base64url of 256 KiB, without padding. */
    static final int MAX_ARG = ((MAX_ENVELOPE + 2) / 3) * 4;

    private CompanionProtocol() {}

    static final class Failure extends Exception {
        final String code;

        Failure(String code) {
            super(code);
            this.code = code;
        }
    }

    /** Decodes one base64url request. A malformed envelope never reaches a provider mutation. */
    static JSONObject decode(String arg) throws Failure {
        if (arg == null || arg.isEmpty() || arg.length() > MAX_ARG || !arg.matches("[A-Za-z0-9_-]+")) {
            throw new Failure("invalid_request");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(arg);
        } catch (IllegalArgumentException malformed) {
            throw new Failure("invalid_request");
        }
        if (bytes.length > MAX_ENVELOPE) throw new Failure("invalid_request");
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException malformed) {
            throw new Failure("invalid_request");
        }
    }

    static String encode(JSONObject reply) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(reply.toString().getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject reply(String requestId, JSONObject data, String error) throws JSONException {
        JSONObject reply = new JSONObject().put("protocol_version", VERSION);
        if (hex(requestId, 32)) reply.put("request_id", requestId);
        if (error == null) {
            reply.put("ok", true).put("data", data == null ? new JSONObject() : data);
        } else {
            reply.put("ok", false).put("error", new JSONObject().put("code", error)
                    .put("message", "The launcher could not complete this operation."));
        }
        return reply;
    }

    static boolean hex(String value, int length) {
        return value != null && value.matches("[a-f0-9]{" + length + "}");
    }

    static boolean integer(Object value, long min, long max) {
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return number >= min && number <= max && number == Math.floor(number);
    }

    /** Validates the request envelope without touching persisted state. */
    static JSONObject request(String method, JSONObject request) throws Failure {
        String requestId = request.optString("request_id", "");
        if (!hex(requestId, 32)) throw new Failure("invalid_request");
        if (!integer(request.opt("protocol_version"), VERSION, VERSION)) {
            throw new Failure("unsupported_protocol");
        }
        if (!"capabilities".equals(method) && !"state".equals(method)) {
            throw new Failure("invalid_request");
        }
        return request;
    }

    /** Stable canonical JSON: sorted keys, no whitespace. Unknown settings stay in the digest. */
    static String canonical(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            object.keys().forEachRemaining(keys::add);
            Collections.sort(keys);
            List<String> values = new ArrayList<>();
            for (String key : keys) values.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            return "{" + String.join(",", values) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) values.add(canonical(array.get(i)));
            return "[" + String.join(",", values) + "]";
        }
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Number) return JSONObject.numberToString((Number) value);
        return value.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder();
            for (byte b : digest) text.append(String.format(Locale.ROOT, "%02x", b & 255));
            return text.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject copy(JSONObject object) throws JSONException {
        return new JSONObject(object.toString());
    }
}
