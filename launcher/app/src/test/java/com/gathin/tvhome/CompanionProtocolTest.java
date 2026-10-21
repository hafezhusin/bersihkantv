package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class CompanionProtocolTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test
    public void decodesAndRejectsOnlyBase64urlWithoutPadding() throws Exception {
        JSONObject request = new JSONObject().put("protocol_version", 1).put("request_id", ID);
        assertEquals(ID, CompanionProtocol.decode(CompanionProtocol.encode(request)).getString("request_id"));
        for (String bad : Arrays.asList(null, "", "not base64!", "abc=", "a b", "%2F")) {
            try {
                CompanionProtocol.decode(bad);
                throw new AssertionError("accepted " + bad);
            } catch (CompanionProtocol.Failure failure) {
                assertEquals("invalid_request", failure.code);
            }
        }
    }

    @Test
    public void envelopeOver256KiBIsRejected() throws Exception {
        String tooLarge = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[CompanionProtocol.MAX_ENVELOPE + 1]);
        try {
            CompanionProtocol.decode(tooLarge);
            throw new AssertionError("accepted oversize envelope");
        } catch (CompanionProtocol.Failure failure) {
            assertEquals("invalid_request", failure.code);
        }
    }

    @Test
    public void requestRequiresVersionOneAndAHexRequestId() throws Exception {
        CompanionProtocol.request("state", new JSONObject().put("protocol_version", 1).put("request_id", ID));
        assertFailure("invalid_request", "state", new JSONObject().put("protocol_version", 1).put("request_id", "nope"));
        assertFailure("invalid_request", "state", new JSONObject().put("protocol_version", 1));
        assertFailure("unsupported_protocol", "state", new JSONObject().put("protocol_version", 2).put("request_id", ID));
        assertFailure("invalid_request", "stage", new JSONObject().put("protocol_version", 1).put("request_id", ID));
    }

    private static void assertFailure(String code, String method, JSONObject request) throws Exception {
        try {
            CompanionProtocol.request(method, request);
            throw new AssertionError("accepted " + request);
        } catch (CompanionProtocol.Failure failure) {
            assertEquals(code, failure.code);
        }
    }

    @Test
    public void replyHasExactlyOneOfDataOrError() throws Exception {
        JSONObject data = CompanionProtocol.reply(ID, new JSONObject().put("features", 1), null);
        assertEquals(1, data.getInt("protocol_version"));
        assertEquals(ID, data.getString("request_id"));
        assertTrue(data.getBoolean("ok"));
        assertTrue(data.has("data"));
        assertFalse(data.has("error"));

        JSONObject error = CompanionProtocol.reply(ID, null, "access_denied");
        assertFalse(error.getBoolean("ok"));
        assertTrue(error.has("error"));
        assertEquals("access_denied", error.getJSONObject("error").getString("code"));
        assertFalse(error.getJSONObject("error").getString("message").contains("/"));
    }

    @Test
    public void canonicalJsonSortsKeysSoRevisionIsStable() throws Exception {
        JSONObject one = new JSONObject().put("b", 2).put("a", new JSONObject().put("d", 4).put("c", 3));
        JSONObject two = new JSONObject().put("a", new JSONObject().put("c", 3).put("d", 4)).put("b", 2);
        assertEquals(CompanionProtocol.canonical(one), CompanionProtocol.canonical(two));
        assertEquals(64, CompanionProtocol.sha256(CompanionProtocol.canonical(one)).length());
    }

    @Test
    public void malformedRequestTouchesNoFile() throws Exception {
        File root = Files.createTempDirectory("companion-read").toFile();
        File active = new File(root, "active.json");
        Files.write(active.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(active.toPath());
        try {
            CompanionProtocol.request("state", CompanionProtocol.decode("%%%"));
        } catch (CompanionProtocol.Failure expected) {
            // rejected before any store was constructed
        }
        assertTrue(Arrays.equals(before, Files.readAllBytes(active.toPath())));
        assertEquals(1, root.listFiles().length);
    }
}
