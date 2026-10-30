package org.fuin.porelo.forge.openapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class ExtractService {
    private final OpenApiExtract.Downloader downloader;
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    ExtractService(OpenApiExtract.Downloader downloader) {
        this.downloader = downloader;
    }

    record Replacement(String from, String to) {
        Replacement {
            if (from.isEmpty()) {
                throw new IllegalArgumentException("Replacement source must not be empty");
            }
        }
    }

    /**
     * A property a forge sends that its own description leaves out, added to the extract by name so
     * the strict reading of the tests keeps refusing every other unknown field.
     */
    record Addition(String schema, String property, String json) {
        Addition {
            if (schema.isBlank() || property.isBlank()) {
                throw new IllegalArgumentException(
                        "Addition schema and property names must not be blank");
            }
        }
    }

    void fetch(
            URI source,
            String expectedDigest,
            Path output,
            List<Replacement> replacements,
            List<Addition> additions,
            Selection selection)
            throws IOException, InterruptedException {
        HttpsSource.validate(source);
        if (!expectedDigest.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException(
                    "SHA-256 must contain exactly 64 hexadecimal digits");
        }
        byte[] bytes = downloader.download(source);
        String actualDigest = sha256(bytes);
        if (!actualDigest.equalsIgnoreCase(expectedDigest)) {
            throw new Problems(
                    List.of(
                            "SHA-256 mismatch: expected "
                                    + expectedDigest
                                    + ", actual "
                                    + actualDigest));
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (Replacement replacement : replacements) {
            text = text.replace(replacement.from(), replacement.to());
        }
        OpenApiDocument document = new OpenApiDocument(parse(text));
        ObjectNode extract = document.extract(selection);
        add(extract, additions);
        new OpenApiDocument(extract).check(selection);
        ObjectNode info = requireObject(extract.path("info"), "info");
        ObjectNode origin =
                info.putObject("x-extracted-from")
                        .put("source", source.toString())
                        .put("sha256", expectedDigest.toLowerCase(Locale.ROOT));
        if (!additions.isEmpty()) {
            var added = origin.putArray("added");
            additions.forEach(addition -> added.add(addition.schema() + "." + addition.property()));
        }
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer =
                new DefaultPrettyPrinter()
                        .withObjectIndenter(indenter)
                        .withArrayIndenter(indenter)
                        .withSeparators(
                                Separators.createDefaultInstance()
                                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        String json = mapper.writer(printer).writeValueAsString(extract) + "\n";
        write(output, json);
    }

    private void add(ObjectNode extract, List<Addition> additions) throws IOException {
        List<String> problems = new java.util.ArrayList<>();
        for (Addition addition : additions) {
            JsonNode schema = extract.path("components").path("schemas").path(addition.schema());
            if (schema.isMissingNode()) {
                schema = extract.path("definitions").path(addition.schema());
            }
            if (!(schema.path("properties") instanceof ObjectNode properties)) {
                problems.add("No schema with properties to add to: " + addition.schema());
            } else if (properties.has(addition.property())) {
                problems.add(
                        "The description already has "
                                + addition.schema()
                                + "."
                                + addition.property()
                                + "; drop the addition");
            } else {
                properties.set(
                        addition.property(),
                        requireObject(mapper.readTree(addition.json()), "addition"));
            }
        }
        if (!problems.isEmpty()) {
            throw new Problems(problems);
        }
    }

    void check(Path extract, Selection selection) throws IOException {
        new OpenApiDocument(parse(Files.readString(extract, StandardCharsets.UTF_8)))
                .check(selection);
    }

    private ObjectNode parse(String text) throws IOException {
        return requireObject(mapper.readTree(text), "description");
    }

    static ObjectNode requireObject(JsonNode node, String name) throws IOException {
        if (!(node instanceof ObjectNode object)) {
            throw new IOException("Expected an object for " + name);
        }
        return object;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void write(Path output, String json) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null) {
            throw new IOException("Output has no directory: " + output);
        }
        Path temporary = Files.createTempFile(directory, "openapi-extract-", ".json");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
