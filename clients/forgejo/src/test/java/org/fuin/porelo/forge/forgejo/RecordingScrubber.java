package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Scrubs only in memory; no raw live payload reaches a file. */
final class RecordingScrubber {
    static final String TEST_SECRET = "public-fixture-key";
    private static final Set<String> CREDENTIAL_KEYS =
            Set.of(
                    "token",
                    "sha1",
                    "access_token",
                    "password",
                    "secret",
                    "authorization",
                    "authorization_header",
                    "registration_token");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>\\\\]+");
    private final ObjectMapper json =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS);
    private final List<String> secrets;

    RecordingScrubber(List<String> secrets) {
        if (secrets.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Cannot scrub an empty credential");
        }
        this.secrets =
                secrets.stream()
                        .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                        .toList();
    }

    JsonNode scrub(JsonNode input) {
        if (input.isObject()) {
            ObjectNode result = json.createObjectNode();
            input.properties()
                    .forEach(
                            field -> {
                                if (CREDENTIAL_KEYS.contains(
                                        field.getKey().toLowerCase(Locale.ROOT))) {
                                    result.put(
                                            field.getKey(),
                                            field.getKey().equalsIgnoreCase("secret")
                                                    ? TEST_SECRET
                                                    : "[redacted]");
                                } else {
                                    result.set(scrubText(field.getKey()), scrub(field.getValue()));
                                }
                            });
            return result;
        }
        if (input.isArray()) {
            var result = json.createArrayNode();
            input.forEach(value -> result.add(scrub(value)));
            return result;
        }
        if (input.isTextual()) {
            String value = input.textValue();
            // Bodies and Actions event payloads embed JSON as text; scrub their keys too.
            if (value.stripLeading().startsWith("{") || value.stripLeading().startsWith("[")) {
                try {
                    return TextNode.valueOf(json.writeValueAsString(scrub(json.readTree(value))));
                } catch (java.io.IOException ex) {
                    // Plain log output may start with JSON punctuation.
                }
            }
            return TextNode.valueOf(scrubText(value));
        }
        return input.deepCopy();
    }

    private String scrubText(String input) {
        String value = input;
        for (String secret : secrets) {
            value = value.replace(secret, "[redacted]");
        }
        var matcher = URL.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            try {
                URI uri = URI.create(matcher.group());
                replacement =
                        uri.getScheme()
                                + "://example.org"
                                + (uri.getRawPath() == null ? "" : uri.getRawPath());
                // Redirect query credentials must never enter the recording.
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("A recording address could not be scrubbed");
            }
            matcher.appendReplacement(
                    result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString()
                .replaceAll("[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+", "fixture@example.org")
                .replaceAll(
                        "(?<![A-Za-z0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]+)?", "example.org")
                .replace("host.containers.internal", "example.org");
    }
}
