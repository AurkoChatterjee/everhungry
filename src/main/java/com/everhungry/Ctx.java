package com.everhungry;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** One request: path parameters, JSON body and the signed-in user. */
final class Ctx {
    final HttpExchange ex;
    final Map<String, String> params = new HashMap<>();
    final JsonNode body;
    Map<String, Object> user;

    Ctx(HttpExchange ex) throws IOException {
        this.ex = ex;
        this.body = "GET".equals(ex.getRequestMethod()) ? Json.MAPPER.createObjectNode() : Json.parse(ex.getRequestBody());
    }

    long id(String name) {
        return Long.parseLong(params.get(name));
    }

    long uid() {
        return Db.num(user.get("id"));
    }

    String role() {
        return (String) user.get("role");
    }

    String text(String key) {
        JsonNode n = body.get(key);
        if (n == null || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    Double dbl(String key) {
        JsonNode n = body.get(key);
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.asDouble();
        try {
            return Double.parseDouble(n.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    Boolean bool(String key) {
        JsonNode n = body.get(key);
        return (n == null || n.isNull()) ? null : n.asBoolean();
    }
}
