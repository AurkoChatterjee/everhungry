package com.everhungry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

public final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    static String str(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode parse(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        if (bytes.length == 0) return MAPPER.createObjectNode();
        return MAPPER.readTree(bytes);
    }
}
