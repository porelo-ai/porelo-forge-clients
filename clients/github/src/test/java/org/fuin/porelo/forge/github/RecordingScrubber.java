package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
                    "temp_clone_token",
                    "password",
                    "secret",
                    "authorization",
                    "authorization_header",
                    "registration_token");
    // A URI template such as {/other_user} ends the address and stays in the text unchanged.
    private static final Pattern URL =
            Pattern.compile(
                    "(https?)://([^/?#\\s\"'<>\\\\{]*)([^?#\\s\"'<>\\\\{]*)(?:[?#][^\\s\"'<>\\\\{]*)?",
                    Pattern.CASE_INSENSITIVE);
    private static final String API_HOST = "api.github.com";
    private static final Set<String> GIT_IDENTITIES = Set.of("author", "committer", "tagger");
    // What an account's profile says of the person behind it.
    private static final Set<String> PROFILE =
            Set.of("location", "bio", "company", "blog", "twitter_username", "email", "hireable");
    private final ObjectMapper json =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS);
    private final List<String> secrets;
    private final java.util.Map<String, String> identities;
    private final java.util.Map<String, String> hosts = new java.util.HashMap<>();
    // A real account's or App's id, node id, slug and name, and the fixture text that replaces it.
    private final java.util.Map<String, String> aliases = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> fixtureNumbers = new java.util.HashMap<>();

    RecordingScrubber(List<String> secrets, java.util.Map<String, String> identities) {
        this.identities = java.util.Map.copyOf(identities);
        if (secrets.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Cannot scrub an empty credential");
        }
        this.secrets =
                secrets.stream()
                        .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                        .toList();
    }

    JsonNode scrub(JsonNode input) {
        return scrubAll(List.of(input)).getFirst();
    }

    /** Learns the identities of every input before scrubbing any, so all lose all of them. */
    List<JsonNode> scrubAll(List<JsonNode> inputs) {
        inputs.forEach(this::collect);
        return inputs.stream().map(input -> scrub(input, "")).toList();
    }

    /** Learns every account's and App's identity first, so text before it is scrubbed too. */
    private void collect(JsonNode input) {
        if (input.isObject()) {
            if (input.path("login").isTextual()) {
                String fixture = identities.get(input.path("login").asText());
                if (fixture == null)
                    throw new IllegalStateException(
                            "A recording names an account it was not told about");
                alias(input, fixture, "FIXTURE_NODE_");
            }
            if (isApp(input)) {
                alias(input, "fixture-app", "FIXTURE_APP_NODE_");
                aliasText(input.path("slug"), "fixture-app");
                aliasText(input.path("client_id"), "fixture-client");
                // Without a space, so a name replaced inside an address keeps it one address.
                aliasText(input.path("name"), "fixture-app");
            }
            input.forEach(this::collect);
        } else if (input.isArray()) {
            input.forEach(this::collect);
        } else if (input.isTextual()) {
            String value = input.textValue().stripLeading();
            if (value.startsWith("{") || value.startsWith("[")) {
                try {
                    collect(json.readTree(input.textValue()));
                } catch (java.io.IOException ex) {
                    // Plain log output may start with JSON punctuation.
                }
            }
        }
    }

    private static boolean isApp(JsonNode input) {
        return input.path("slug").isTextual() && input.has("client_id");
    }

    private int fixtureNumber(String fixture) {
        return fixtureNumbers.computeIfAbsent(fixture, key -> fixtureNumbers.size() + 1);
    }

    private void alias(JsonNode input, String fixture, String nodePrefix) {
        int number = fixtureNumber(fixture);
        if (input.path("id").isIntegralNumber())
            aliases.put(input.path("id").asText(), String.valueOf(number));
        aliasText(input.path("node_id"), nodePrefix + number);
    }

    private void aliasText(JsonNode value, String fixture) {
        // An App's name is often its slug; the slug's alias, set first, keeps URLs valid.
        if (value.isTextual() && !value.asText().isBlank())
            aliases.putIfAbsent(value.asText(), fixture);
    }

    private JsonNode scrub(JsonNode input, String parentKey) {
        if (input.isObject()) {
            boolean gitIdentity = GIT_IDENTITIES.contains(parentKey) && !input.has("login");
            boolean account = input.path("login").isTextual();
            boolean app = isApp(input);
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
                                } else if (field.getKey().equals("login")
                                        && field.getValue().isTextual()) {
                                    result.put(
                                            field.getKey(),
                                            identities.get(field.getValue().asText()));
                                } else if ((account || app)
                                        && field.getKey().equals("id")
                                        && field.getValue().isIntegralNumber()) {
                                    result.put(
                                            field.getKey(),
                                            Long.parseLong(aliases.get(field.getValue().asText())));
                                } else if (account
                                        && PROFILE.contains(field.getKey())
                                        && !field.getValue().isNull()) {
                                    result.putNull(field.getKey());
                                } else if (field.getKey().equals("name")
                                        && (input.has("login") || gitIdentity)) {
                                    result.put(field.getKey(), "Fixture User");
                                } else if (field.getKey().equals("email") && gitIdentity) {
                                    result.put(field.getKey(), "fixture@example.org");
                                } else {
                                    result.set(
                                            scrubText(field.getKey()),
                                            scrub(field.getValue(), field.getKey()));
                                }
                            });
            return result;
        }
        if (input.isArray()) {
            var result = json.createArrayNode();
            input.forEach(value -> result.add(scrub(value, parentKey)));
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
            // A redirect may be relative, which the URL pattern does not see; its query may be
            // signed.
            if (parentKey.equals("location")) value = value.replaceFirst("[?#].*", "");
            return TextNode.valueOf(scrubText(value));
        }
        return input.deepCopy();
    }

    /**
     * The API host becomes {@code example.org}; every other host gets a host of its own below it,
     * the same one throughout a run, so a redirect that left the origin still leaves it on replay.
     */
    private String fictionalHost(String authority) {
        java.net.URI address;
        try {
            address = java.net.URI.create("https://" + authority);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("A recording host could not be scrubbed");
        }
        String host = address.getHost();
        if (host == null) throw new IllegalStateException("A recording host could not be scrubbed");
        host = host.toLowerCase(Locale.ROOT);
        String port = address.getPort() < 0 ? "" : ":" + address.getPort();
        if (host.equals(API_HOST)) {
            return "example.org" + port;
        }
        String fictional = hosts.get(host);
        if (fictional == null) {
            fictional = "host-" + (hosts.size() + 1) + ".example.org";
            hosts.put(host, fictional);
        }
        return fictional + port;
    }

    private String scrubText(String input) {
        String value = input;
        for (String secret : secrets) {
            value = value.replace(secret, "[redacted]");
        }
        for (var alias :
                aliases.entrySet().stream()
                        .sorted(
                                java.util.Comparator.comparingInt(
                                                (java.util.Map.Entry<String, String> entry) ->
                                                        entry.getKey().length())
                                        .reversed())
                        .toList()) {
            // A number counts only between non-alphanumerics, never inside a hash. After a dash
            // or an underscore it is still replaced: a leaked identity weighs more than a broken
            // sample.
            String edge = alias.getKey().matches("[0-9]+") ? "A-Za-z0-9" : "A-Za-z0-9_-";
            value =
                    value.replaceAll(
                            "(?<!["
                                    + edge
                                    + "])"
                                    + Pattern.quote(alias.getKey())
                                    + "(?!["
                                    + edge
                                    + "])",
                            java.util.regex.Matcher.quoteReplacement(alias.getValue()));
        }
        for (var identity :
                identities.entrySet().stream()
                        .sorted(
                                java.util.Comparator.comparingInt(
                                                (java.util.Map.Entry<String, String> entry) ->
                                                        entry.getKey().length())
                                        .reversed())
                        .toList()) {
            value =
                    value.replaceAll(
                            "(?<![A-Za-z0-9_-])"
                                    + Pattern.quote(identity.getKey())
                                    + "(?![A-Za-z0-9_-])",
                            java.util.regex.Matcher.quoteReplacement(identity.getValue()));
        }
        var matcher = URL.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            // The query and fragment are dropped: redirect query credentials must never enter the
            // recording.
            String replacement =
                    matcher.group(1) + "://" + fictionalHost(matcher.group(2)) + matcher.group(3);
            matcher.appendReplacement(
                    result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString()
                .replaceAll(
                        "(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|"
                                + "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)",
                        "[redacted]")
                .replaceAll("[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+", "fixture@example.org")
                .replaceAll(
                        "(?<![A-Za-z0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]+)?", "example.org")
                .replace("host.containers.internal", "example.org");
    }
}
