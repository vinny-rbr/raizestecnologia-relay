package com.raizestecnologia.relay.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Envelope padrao da API: {success, data, message, timestamp}.
 */
public final class ApiEnvelope {

    private ApiEnvelope() {}

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        m.put("data", data);
        m.put("message", "");
        m.put("timestamp", Instant.now().toString());
        return m;
    }

    public static Map<String, Object> fail(String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("data", null);
        m.put("message", message == null ? "" : message);
        m.put("timestamp", Instant.now().toString());
        return m;
    }
}
