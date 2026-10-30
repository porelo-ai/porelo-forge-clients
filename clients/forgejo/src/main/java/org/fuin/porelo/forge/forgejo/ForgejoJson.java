package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Objects;

final class ForgejoJson {
    private ForgejoJson() {}

    static ObjectMapper mapper(Strictness strictness) {
        Objects.requireNonNull(strictness);
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        strictness == Strictness.STRICT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static <T> T read(ObjectMapper mapper, String body, JavaType type, String token) {
        return read(mapper, body, type, List.of(token));
    }

    static <T> T read(ObjectMapper mapper, String body, JavaType type, List<String> credentials) {
        try {
            T result = mapper.readValue(body, type);
            if (result == null) {
                throw new ForgejoException(0, "Expected a JSON value");
            }
            if (result instanceof List<?> values && values.stream().anyMatch(Objects::isNull)) {
                throw new ForgejoException(0, "Expected non-null JSON list elements");
            }
            return result;
        } catch (UnrecognizedPropertyException ex) {
            throw new ForgejoException(
                    0, safe("Unknown JSON field: " + ex.getPropertyName(), credentials));
        } catch (JsonProcessingException ex) {
            // Jackson messages and causes can contain the input, including credentials.
            throw new ForgejoException(0, "Invalid JSON or missing required field");
        }
    }

    static String safe(String message, List<String> credentials) {
        String result = message;
        for (String credential : credentials) {
            if (!credential.isEmpty()) {
                result = result.replace(credential, "[redacted]");
            }
        }
        return result;
    }
}
