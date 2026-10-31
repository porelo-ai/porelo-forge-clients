package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class GitHubJson {
    private GitHubJson() {}

    static ObjectMapper mapper(Strictness strictness) {
        Objects.requireNonNull(strictness);
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        // A null inside a list or map, at any depth, would reach a caller the records promise it
        // never does.
        mapper.setDefaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.FAIL));
        var unions = new com.fasterxml.jackson.databind.module.SimpleModule();
        unions.addDeserializer(Object.class, new NonNullUntypedReader());
        unions.addDeserializer(
                org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response.class,
                new UserReader());
        unions.addDeserializer(
                org.fuin.porelo.forge.github.model.IntegrationOwner.class, new OwnerReader());
        unions.addDeserializer(
                org.fuin.porelo.forge.github.model.CommitAuthor.class, new CommitAuthorReader());
        mapper.addMixIn(
                org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response.class,
                NoTypeId.class);
        return mapper.registerModule(unions)
                .registerModule(new JavaTimeModule())
                .disable(
                        com.fasterxml.jackson.databind.SerializationFeature
                                .WRITE_DATES_AS_TIMESTAMPS)
                .configure(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        strictness == Strictness.STRICT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static <T> T read(ObjectMapper mapper, String body, JavaType type, String token) {
        return read(mapper, body, type, List.of(token));
    }

    static <T> T read(ObjectMapper mapper, String body, JavaType type, List<String> credentials) {
        return read(() -> mapper.readValue(body, type), credentials);
    }

    static <T> T convert(
            ObjectMapper mapper, JsonNode tree, JavaType type, List<String> credentials) {
        return read(() -> mapper.treeToValue(tree, type), credentials);
    }

    @FunctionalInterface
    private interface Reading<T> {
        T read() throws java.io.IOException;
    }

    private static <T> T read(Reading<T> reading, List<String> credentials) {
        try {
            T result = reading.read();
            if (result == null) {
                throw new GitHubException(0, "Expected a JSON value");
            }
            return result;
        } catch (InvalidNullException ex) {
            throw new GitHubException(
                    0,
                    safe(
                            "Expected non-null JSON list elements and map values" + fieldPath(ex),
                            credentials));
        } catch (UnrecognizedPropertyException ex) {
            throw new GitHubException(
                    0, safe("Unknown JSON field: " + ex.getPropertyName(), credentials));
        } catch (JsonMappingException ex) {
            // Only property names and indexes are named: Jackson messages and causes can contain
            // the input, including credentials.
            throw new GitHubException(
                    0, safe("Invalid JSON or missing required field" + fieldPath(ex), credentials));
        } catch (java.io.IOException ex) {
            // Jackson messages and causes can contain the input, including credentials.
            throw new GitHubException(0, "Invalid JSON or missing required field");
        }
    }

    private static final Pattern MISSING_FIELD =
            Pattern.compile("([A-Za-z0-9_]{1,100})(?: may not be null)?");

    private static String fieldPath(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference step : ex.getPath()) {
            if (step.getFieldName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(step.getFieldName());
            } else if (step.getIndex() >= 0) {
                path.append('[').append(step.getIndex()).append(']');
            }
        }
        // Record constructors name a missing field; accept that name only if it is a property.
        if (ex.getCause() instanceof NullPointerException missing && missing.getMessage() != null) {
            var field = MISSING_FIELD.matcher(missing.getMessage());
            if (field.matches() && isRecordProperty(ex, field.group(1))) {
                path.append(path.isEmpty() ? "" : ".").append(field.group(1));
            }
        }
        return path.isEmpty() ? "" : ": " + path;
    }

    private static boolean isRecordProperty(JsonMappingException ex, String field) {
        if (!(ex instanceof ValueInstantiationException creation)) return false;
        var type = creation.getType();
        if (type == null || !type.isRecordType()) return false;
        for (var component : type.getRawClass().getRecordComponents()) {
            var property =
                    component
                            .getAccessor()
                            .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            String name =
                    property == null || property.value().isEmpty()
                            ? component.getName()
                            : property.value();
            if (field.equals(name)) return true;
        }
        return false;
    }

    private static final class NonNullUntypedReader
            extends com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializer {
        NonNullUntypedReader() {
            super((JavaType) null, (JavaType) null);
        }

        @Override
        public Object deserialize(
                com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            Object value = super.deserialize(parser, context);
            requireCollectionValues(value);
            return value;
        }

        private static void requireCollectionValues(
                @org.jspecify.annotations.Nullable Object value) {
            if (value instanceof java.util.Map<?, ?> map) {
                for (var item : map.values()) {
                    if (item == null)
                        throw new GitHubException(
                                0, "Expected non-null JSON list elements and map values");
                    requireCollectionValues(item);
                }
            } else if (value instanceof java.util.Collection<?> items) {
                for (var item : items) {
                    if (item == null)
                        throw new GitHubException(
                                0, "Expected non-null JSON list elements and map values");
                    requireCollectionValues(item);
                }
            }
        }
    }

    // GitHub's published examples omit the optional user_view_type discriminator.
    @com.fasterxml.jackson.annotation.JsonTypeInfo(
            use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NONE)
    private abstract static class NoTypeId {}

    private static final class UserReader
            extends com.fasterxml.jackson.databind.JsonDeserializer<
                    org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response> {
        @Override
        public org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response deserialize(
                com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode value = context.readTree(parser);
            com.fasterxml.jackson.databind.JsonNode given = value.path("user_view_type");
            // Only an absent or null view is told by the fields; any other must be one mapped.
            boolean absent = given.isMissingNode() || given.isNull();
            String view = absent ? "" : given.asText();
            if (!absent && !view.equals("private") && !view.equals("public")) {
                throw com.fasterxml.jackson.databind.JsonMappingException.from(
                        parser, "Unknown account view");
            }
            if (view.equals("private") || (absent && value.has("two_factor_authentication"))) {
                return context.readTreeAsValue(
                        value, org.fuin.porelo.forge.github.model.PrivateUser.class);
            }
            return context.readTreeAsValue(
                    value, org.fuin.porelo.forge.github.model.PublicUser.class);
        }
    }

    private static final class OwnerReader
            extends com.fasterxml.jackson.databind.JsonDeserializer<
                    org.fuin.porelo.forge.github.model.IntegrationOwner> {
        @Override
        public org.fuin.porelo.forge.github.model.IntegrationOwner deserialize(
                com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode value = context.readTree(parser);
            if (value.has("slug")) {
                return context.readTreeAsValue(
                        value, org.fuin.porelo.forge.github.model.Enterprise.class);
            }
            return context.readTreeAsValue(
                    value, org.fuin.porelo.forge.github.model.SimpleUser.class);
        }
    }

    /**
     * A commit's author or committer is an account, or no account at all, which GitHub sends as
     * null or as an empty object; both read as null.
     */
    private static final class CommitAuthorReader
            extends com.fasterxml.jackson.databind.JsonDeserializer<
                    org.fuin.porelo.forge.github.model.@Nullable CommitAuthor> {
        @Override
        public org.fuin.porelo.forge.github.model.@Nullable CommitAuthor deserialize(
                com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode value = context.readTree(parser);
            if (value.isObject() && value.isEmpty()) return null;
            return context.readTreeAsValue(
                    value, org.fuin.porelo.forge.github.model.SimpleUser.class);
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
