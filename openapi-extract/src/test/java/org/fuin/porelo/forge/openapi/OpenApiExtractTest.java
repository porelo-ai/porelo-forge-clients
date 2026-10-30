package org.fuin.porelo.forge.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenApiExtractTest {
    private static final String SOURCE = "https://example.org/api.json";
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;

    // Prevents an unverified download from replacing a trusted committed input.
    @Test
    void refusesDigestMismatchBeforeWritingAnyFile() throws Exception {
        byte[] source = fixture("openapi31.json");
        Path output = directory.resolve("extract.json");
        String wrong = "0".repeat(64);

        Result result =
                fetchWithDigest(source, output, wrong, "post /items", "--webhook", "created");

        assertThat(result.status()).as("An unverified input must fail").isEqualTo(1);
        assertThat(result.error())
                .as("Both digests identify the pin mismatch")
                .contains(wrong, digest(source))
                .hasLineCount(1);
        assertThat(output).as("A digest mismatch must not create output").doesNotExist();
        try (var files = Files.list(directory)) {
            assertThat(files.toList())
                    .as("No staging file is created before verification")
                    .isEmpty();
        }
        Files.writeString(output, "trusted input");
        fetchWithDigest(source, output, wrong, "post /items");
        assertThat(Files.readString(output))
                .as("A digest mismatch preserves an existing extract")
                .isEqualTo("trusted input");
    }

    // Prevents unwanted APIs and types from entering generated client contracts or looping on
    // recursion.
    @Test
    void keepsOnlySelectedOperationsWebhooksAndTransitiveComponents() throws Exception {
        Path output = directory.resolve("extract.json");
        Result result =
                fetch(fixture("openapi31.json"), output, "post /items", "--webhook", "created");
        assertThat(result)
                .as("The selected OpenAPI description must be extractable")
                .isEqualTo(new Result(0, ""));
        JsonNode extract = mapper.readTree(output.toFile());

        assertThat(names(extract.path("paths")))
                .as("Unselected paths must not generate APIs")
                .containsExactly("/items");
        assertThat(names(extract.at("/paths/~1items")))
                .as("Path metadata and parameters belong to a kept path")
                .containsExactly("summary", "parameters", "post");
        assertThat(names(extract.path("webhooks")))
                .as("Only the selected webhook is kept")
                .containsExactly("created");
        assertThat(extract.has("x-webhooks"))
                .as("Legacy webhook extensions must not reach the generator")
                .isFalse();
        assertThat(names(extract.at("/components/schemas")))
                .as("Chains, cycles, path parameters and webhooks determine the schema closure")
                .containsExactly(
                        "Envelope",
                        "Node",
                        "Context",
                        "Tenant",
                        "Event",
                        "Narrow",
                        "NarrowAny",
                        "Union",
                        "Cat",
                        "Dog");
        assertThat(extract.at("/components/schemas/Node/properties/next/$ref").asText())
                .as("A recursive reference remains usable")
                .isEqualTo("#/components/schemas/Node");
        assertThat(names(extract.at("/components/parameters")))
                .as("Shared path and operation parameters are retained")
                .containsExactly("Tenant", "Limit");
        for (String section : List.of("responses", "headers", "examples", "requestBodies")) {
            assertThat(names(extract.at("/components/" + section)))
                    .as("Every reusable section is pruned: %s", section)
                    .containsExactly(
                            switch (section) {
                                case "responses" -> "Items";
                                case "headers" -> "Rate";
                                case "examples" -> "Sample";
                                default -> "Create";
                            });
        }
        assertThat(names(extract.at("/components/securitySchemes")))
                .as("Entries with no reference are removed in any section")
                .isEmpty();
        assertThat(extract.at("/info/x-extracted-from/source").asText())
                .as("The source URL is recorded")
                .isEqualTo(SOURCE);
        assertThat(extract.at("/info/x-extracted-from/sha256").asText())
                .as("The raw input digest is recorded")
                .isEqualTo(digest(fixture("openapi31.json")));
    }

    // Prevents Swagger clients from silently pulling in unused shared responses and definitions.
    @Test
    void filtersSwaggerDefinitionsParametersAndResponsesWithoutLooping() throws Exception {
        Path output = directory.resolve("extract.json");
        Result result = fetch(fixture("swagger20.json"), output, "get /items");
        assertThat(result)
                .as("Swagger is supported without conversion")
                .isEqualTo(new Result(0, ""));
        JsonNode extract = mapper.readTree(output.toFile());

        assertThat(names(extract.path("paths")))
                .as("Swagger paths are selected exactly")
                .containsExactly("/items");
        assertThat(names(extract.at("/paths/~1items")))
                .as("Path-level parameters survive filtering")
                .containsExactly("parameters", "get");
        assertThat(names(extract.path("definitions")))
                .as("Only the full reached schema closure survives")
                .containsExactly("Envelope", "Node", "Context", "Tenant");
        assertThat(names(extract.path("parameters")))
                .as("Only reached shared parameters survive")
                .containsExactly("Tenant", "Limit");
        assertThat(names(extract.path("responses")))
                .as("Only reached shared responses survive")
                .containsExactly("Items");
        assertThat(check(output, "get /items"))
                .as("The Swagger result passes the build check")
                .isEqualTo(new Result(0, ""));
    }

    // Prevents a changed upstream API from silently generating an incomplete client.
    @Test
    void reportsEveryMissingOperationAndWebhookBeforeWriting() throws Exception {
        Path output = directory.resolve("extract.json");
        Result result =
                fetch(
                        fixture("openapi31.json"),
                        output,
                        "get /missing",
                        "--operation",
                        "delete /missing",
                        "--webhook",
                        "missing-event",
                        "--webhook",
                        "another-event");

        assertThat(result.status()).as("All requested API entries must exist").isEqualTo(1);
        assertThat(result.error())
                .as("Every missing entry has its own diagnostic")
                .contains("get /missing", "delete /missing", "missing-event", "another-event")
                .hasLineCount(4);
        assertThat(output).as("An incomplete API is never written").doesNotExist();
    }

    // Prevents the generator from making classes for narrowed records or breaking real polymorphic
    // schemas.
    @Test
    void removesOnlyTheDiscriminatorsOfRefFreeNarrowingSchemas() throws Exception {
        Path output = directory.resolve("extract.json");
        Result result = fetch(fixture("openapi31.json"), output, "post /items");
        assertThat(result.status()).as("Narrowing schemas can be extracted").isZero();
        JsonNode schemas = mapper.readTree(output.toFile()).at("/components/schemas");

        assertThat(schemas.path("Narrow").has("discriminator"))
                .as("A ref-free oneOf narrows its own properties")
                .isFalse();
        assertThat(schemas.path("NarrowAny").has("discriminator"))
                .as("A ref-free anyOf narrows its own properties")
                .isFalse();
        assertThat(schemas.path("Narrow").path("oneOf"))
                .as("The generator remains responsible for normalizing branches")
                .isEqualTo(
                        mapper.readTree(fixture("openapi31.json"))
                                .at("/components/schemas/Narrow/oneOf"));
        assertThat(schemas.path("Union"))
                .as("A union of references must be preserved in full")
                .isEqualTo(
                        mapper.readTree(fixture("openapi31.json")).at("/components/schemas/Union"));
    }

    // Prevents template substitutions from changing the bytes the pinned digest authenticates.
    @Test
    void appliesOrderedPlainReplacementsBeforeParsingTheVerifiedText() throws Exception {
        Path output = directory.resolve("extract.json");
        byte[] source = fixture("template.json");
        Result result =
                fetch(
                        source,
                        output,
                        "get /items",
                        "--replace",
                        "{{.SwaggerAppVer}}=intermediate",
                        "--replace",
                        "intermediate=1.0",
                        "--replace",
                        "{{.SwaggerAppSubUrl}}=/forge",
                        "--replace",
                        "\"Template\"=\"Template\", \"note\": \"literal $1 \\\\ value=ok\"");
        assertThat(result)
                .as("Substitutions may change JSON structure before parsing")
                .isEqualTo(new Result(0, ""));
        JsonNode extract = mapper.readTree(output.toFile());
        assertThat(extract.at("/info/version").asText())
                .as("Replacements run in command-line order")
                .isEqualTo("1.0");
        assertThat(extract.at("/basePath").asText())
                .as("Sub-URL placeholders are plain text")
                .isEqualTo("/forge/api");
        assertThat(extract.at("/info/note").asText())
                .as("Equals signs, dollars and backslashes are literal replacement text")
                .isEqualTo("literal $1 \\ value=ok");
        assertThat(extract.at("/info/x-extracted-from/sha256").asText())
                .as("Provenance pins raw bytes before substitution")
                .isEqualTo(digest(source));
    }

    // Prevents extract regeneration from introducing platform-dependent or unstable build inputs.
    @Test
    void writesByteIdenticalJsonInSourceKeyOrderWithLfAndATrailingNewline() throws Exception {
        Path first = directory.resolve("first.json");
        Path second = directory.resolve("second.json");
        byte[] source = fixture("openapi31.json");
        Result one = fetch(source, first, "post /items", "--webhook", "created");
        Result two = fetch(source, second, "post /items", "--webhook", "created");

        assertThat(List.of(one, two))
                .as("Both extractions succeed")
                .containsOnly(new Result(0, ""));
        assertThat(Files.readAllBytes(first))
                .as("The same pin and selection yield identical bytes")
                .isEqualTo(Files.readAllBytes(second));
        assertThat(Files.readString(first))
                .as("The committed format is two-space indentation and LF")
                .startsWith("{\n  \"openapi\": \"3.1.0\",\n  \"info\": {\n    \"title\":")
                .endsWith("}\n")
                .doesNotContain("\r");
    }

    // Prevents a committed extract from bypassing the operation allowlist or growing unused
    // generated types.
    @Test
    void checksTheExactSelectionAndNamesEachUnlistedEntryAndUnreachedSchema() throws Exception {
        Path output = directory.resolve("extract.json");
        Result fetched =
                fetch(fixture("openapi31.json"), output, "post /items", "--webhook", "created");
        assertThat(fetched.status()).as("The baseline extract is valid").isZero();
        assertThat(check(output, "post /items", "--webhook", "created"))
                .as("A filtered OpenAPI result passes check")
                .isEqualTo(new Result(0, ""));
        ObjectNode extract = (ObjectNode) mapper.readTree(output.toFile());
        ((ObjectNode) extract.at("/paths/~1items")).putObject("delete").putObject("responses");
        ((ObjectNode) extract.path("webhooks")).putObject("surprise");
        ((ObjectNode) extract.at("/components/schemas")).putObject("Unreached");
        mapper.writeValue(output.toFile(), extract);
        byte[] before = Files.readAllBytes(output);

        Result checked =
                check(
                        output,
                        "post /items",
                        "--webhook",
                        "created",
                        "--operation",
                        "get /missing",
                        "--webhook",
                        "absent");

        assertThat(checked.status()).as("Selection or closure drift fails the build").isEqualTo(1);
        assertThat(checked.error())
                .as("Each problem is actionable and printed separately")
                .contains(
                        "Unlisted operation: delete /items",
                        "Unlisted webhook: surprise",
                        "Unreached component: #/components/schemas/Unreached",
                        "Missing operation: get /missing",
                        "Missing webhook: absent")
                .hasLineCount(5);
        assertThat(Files.readAllBytes(output))
                .as("Check must never rewrite the committed extract")
                .isEqualTo(before);
    }

    // Prevents a dangling or external reference from yielding an extract that looks complete but
    // cannot generate.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "#/components/schemas/Absent",
                "https://example.org/other.json#/Node",
                "#/paths/~1items"
            })
    void refusesReferencesItCannotRetain(String reference) throws Exception {
        byte[] source =
                new String(fixture("openapi31.json"), StandardCharsets.UTF_8)
                        .replace("#/components/schemas/Envelope", reference)
                        .getBytes(StandardCharsets.UTF_8);
        Path output = directory.resolve("extract.json");
        Result result = fetch(source, output, "post /items");

        assertThat(result.status()).as("Invalid references fail before publication").isEqualTo(1);
        assertThat(result.error()).as("The broken reference is identified").contains(reference);
        assertThat(output).as("No unusable extract is written").doesNotExist();
    }

    // Prevents credentials or insecure URLs from reaching even an injected downloader.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.org/api.json",
                "https://account:password@example.org/api.json",
                "https://example.org/api.json#fragment",
                "not a URL"
            })
    void rejectsUnsafeSourcesBeforeDownloading(String source) throws Exception {
        List<String> args =
                fetchArguments(directory.resolve("extract.json"), "0".repeat(64), "get /items");
        args.set(args.indexOf("--source") + 1, source);
        Result result =
                run(
                        args,
                        uri -> {
                            throw new AssertionError("Invalid sources must not be downloaded");
                        });

        assertThat(result.status())
                .as("Only credential-free HTTPS sources are accepted")
                .isEqualTo(1);
        assertThat(result.error())
                .as("Invalid source diagnostics do not echo embedded credentials")
                .contains("HTTPS")
                .doesNotContain("account", "password")
                .hasLineCount(1);
    }

    // Prevents argument errors from silently changing the requested extraction or opening a socket.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "unknown",
                "missing-value",
                "duplicate-output",
                "missing-operation",
                "bad-method",
                "bad-digest",
                "bad-replacement"
            })
    void rejectsMalformedCommandsBeforeDownloading(String mistake) throws Exception {
        List<String> args =
                fetchArguments(directory.resolve("extract.json"), "0".repeat(64), "get /items");
        switch (mistake) {
            case "unknown" -> args.addAll(List.of("--unknown", "x"));
            case "missing-value" -> args.add("--webhook");
            case "duplicate-output" -> args.addAll(List.of("--out", "duplicate.json"));
            case "missing-operation" -> {
                args.removeLast();
                args.removeLast();
            }
            case "bad-method" -> args.set(args.size() - 1, "GET /items");
            case "bad-digest" -> args.set(args.indexOf("--sha256") + 1, "not-a-digest");
            case "bad-replacement" -> args.addAll(List.of("--replace", "=x"));
            default -> throw new AssertionError(mistake);
        }
        Result result =
                run(
                        args,
                        uri -> {
                            throw new AssertionError("Invalid commands must not download");
                        });
        assertThat(result.status())
                .as("Malformed arguments fail before effects: %s", mistake)
                .isEqualTo(1);
        assertThat(result.error())
                .as("An argument failure has one diagnostic: %s", mistake)
                .isNotBlank()
                .hasLineCount(1);
    }

    // Prevents an extract from rounding a constraint or losing a nested reference's sibling types.
    @Test
    void preservesPreciseConstraintsAndResolvesEscapedNestedPointers() throws Exception {
        Path output = directory.resolve("extract.json");
        Result result = fetch(fixture("pointers.json"), output, "get /items");
        assertThat(result)
                .as("OpenAPI 3.0 and escaped pointers are supported")
                .isEqualTo(new Result(0, ""));
        ObjectMapper exact =
                new ObjectMapper()
                        .enable(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode extract = exact.readTree(output.toFile());
        assertThat(names(extract.at("/components/schemas")))
                .as("A nested pointer keeps the whole entry and all its transitive references")
                .containsExactly("a/b~c d", "Other");
        assertThat(
                        extract.at("/components/schemas/a~1b~0c d/properties/value/minimum")
                                .decimalValue())
                .as("A numeric schema constraint must retain its exact value")
                .isEqualByComparingTo("0.12345678901234567890123456789");
        assertThat(check(output, "get /items"))
                .as("Nested references pass the closure check")
                .isEqualTo(new Result(0, ""));
    }

    // Prevents an unused shared Swagger response from bringing unwanted models into a client.
    @Test
    void namesUnreachedEntriesAcrossEverySwaggerReusableSection() throws Exception {
        Path output = directory.resolve("extract.json");
        Files.write(output, fixture("swagger20.json"));

        Result result = check(output, "get /items");

        assertThat(result.status())
                .as("An unfiltered Swagger description must fail check")
                .isEqualTo(1);
        assertThat(result.error())
                .as("Check names each unused Swagger entry and extra operation")
                .contains(
                        "#/definitions/Unused",
                        "#/parameters/Unused",
                        "#/responses/Unused",
                        "post /items",
                        "get /unused")
                .hasLineCount(5);
    }

    // Prevents literal payload data from becoming a dependency or being rewritten as a schema.
    @Test
    void preservesReferenceLookingDataAndFollowsOnlyActualReferences() throws Exception {
        Path output = directory.resolve("extract.json");
        byte[] source = fixture("reference-data.json");
        Result result = fetch(source, output, "get /items");
        assertThat(result)
                .as("Reference-looking example values and property names are valid data")
                .isEqualTo(new Result(0, ""));
        JsonNode extract = mapper.readTree(output.toFile());
        JsonNode original = mapper.readTree(source);
        assertThat(extract.at("/components/schemas/Payload"))
                .as("Literal schema data remains unchanged")
                .isEqualTo(original.at("/components/schemas/Payload"));
        assertThat(extract.at("/components/examples/Sample"))
                .as("Example values are never corrected or traversed")
                .isEqualTo(original.at("/components/examples/Sample"));
        assertThat(names(extract.at("/components/schemas")))
                .as("Literal references cannot retain unwanted types")
                .containsExactly("Payload", "Node");
        assertThat(check(output, "get /items"))
                .as("Check applies the same semantic reference rules")
                .isEqualTo(new Result(0, ""));
    }

    // Prevents extension metadata from adding operations or being removed as unused components.
    @Test
    void preservesStructuralExtensionsWithoutTreatingThemAsApiEntries() throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree(fixture("openapi31.json"));
        ((ObjectNode) source.path("paths")).put("x-label", "path metadata");
        ((ObjectNode) source.path("webhooks")).putObject("x-label").put("post", "webhook metadata");
        ((ObjectNode) source.path("components")).put("x-label", "component metadata");
        ((ObjectNode) source.path("components"))
                .putObject("x-data")
                .put("literal", "component entry metadata");
        Path output = directory.resolve("extract.json");

        Result result =
                fetch(
                        mapper.writeValueAsBytes(source),
                        output,
                        "post /items",
                        "--webhook",
                        "created");

        assertThat(result)
                .as("Valid extension fields must not prevent extraction")
                .isEqualTo(new Result(0, ""));
        JsonNode extract = mapper.readTree(output.toFile());
        for (String pointer :
                List.of(
                        "/paths/x-label",
                        "/webhooks/x-label",
                        "/components/x-label",
                        "/components/x-data")) {
            assertThat(extract.at(pointer))
                    .as("Extension metadata is preserved at %s", pointer)
                    .isEqualTo(source.at(pointer));
        }
        assertThat(check(output, "post /items", "--webhook", "created"))
                .as("Extensions do not count as unlisted operations or webhooks")
                .isEqualTo(new Result(0, ""));
    }

    // Prevents reusable path items from losing inherited parameters or retaining unlisted
    // operations.
    @Test
    void filtersOperationsInheritedThroughReusablePathItems() throws Exception {
        Path output = directory.resolve("extract.json");
        Result result = fetch(fixture("path-items.json"), output, "get /items");
        assertThat(result)
                .as("Referenced path item chains expose their operations")
                .isEqualTo(new Result(0, ""));
        JsonNode extract = mapper.readTree(output.toFile());
        assertThat(names(extract.at("/paths/~1items")))
                .as("Selected inherited operations keep their parameters")
                .containsExactly("parameters", "get");
        assertThat(names(extract.at("/components/schemas")))
                .as("Unselected inherited operations cannot retain types")
                .containsExactly("Item", "Tenant");
        assertThat(names(extract.at("/components/pathItems")))
                .as("Expanded path items no longer need their reusable entries")
                .isEmpty();
        assertThat(check(output, "get /items"))
                .as("The expanded selection passes check")
                .isEqualTo(new Result(0, ""));
    }

    // Prevents custom header names from being mistaken for metadata and losing their dependencies.
    @Test
    void keepsReferencesUnderCustomHeaderNames() throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree(fixture("openapi31.json"));
        ObjectNode headers = (ObjectNode) source.at("/components/responses/Items/headers");
        headers.set("x-rate-limit", headers.remove("Rate"));
        Path output = directory.resolve("extract.json");

        Result result = fetch(mapper.writeValueAsBytes(source), output, "post /items");

        assertThat(result).as("Custom HTTP header names are API data").isEqualTo(new Result(0, ""));
        JsonNode extract = mapper.readTree(output.toFile());
        assertThat(names(extract.at("/components/headers")))
                .as("A header reference under an x- name must retain its target")
                .containsExactly("Rate");
        assertThat(extract.at("/components/responses/Items/headers/x-rate-limit/$ref").asText())
                .as("The custom header still points to its retained component")
                .isEqualTo("#/components/headers/Rate");
        assertThat(check(output, "post /items"))
                .as("Custom header references pass closure validation")
                .isEqualTo(new Result(0, ""));
    }

    // Prevents custom header names from bypassing the record-generation correction.
    @Test
    void correctsInlineSchemasUnderCustomHeaderNames() throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree(fixture("openapi31.json"));
        ObjectNode headers = (ObjectNode) source.at("/components/responses/Items/headers");
        headers.putObject("x-rate-limit")
                .set("schema", source.at("/components/schemas/Narrow").deepCopy());
        headers.putObject("value")
                .set("schema", source.at("/components/schemas/NarrowAny").deepCopy());
        Path output = directory.resolve("extract.json");

        Result result = fetch(mapper.writeValueAsBytes(source), output, "post /items");

        assertThat(result.status())
                .as("Inline header schemas are valid extraction inputs")
                .isZero();
        JsonNode extract = mapper.readTree(output.toFile());
        for (String name : List.of("x-rate-limit", "value")) {
            assertThat(
                            extract.at("/components/responses/Items/headers/" + name + "/schema")
                                    .has("discriminator"))
                    .as("Header name %s must not bypass schema correction", name)
                    .isFalse();
        }
    }

    // Prevents schema-like literal link parameters from being changed during extraction.
    @Test
    void preservesSchemaLikeLiteralLinkParameters() throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree(fixture("openapi31.json"));
        ObjectNode links = ((ObjectNode) source.path("components")).putObject("links");
        links.putObject("Next")
                .putObject("parameters")
                .set("schema", source.at("/components/schemas/Narrow").deepCopy());
        ((ObjectNode) source.at("/components/responses/Items"))
                .putObject("links")
                .putObject("next")
                .put("$ref", "#/components/links/Next");
        Path output = directory.resolve("extract.json");

        Result result = fetch(mapper.writeValueAsBytes(source), output, "post /items");

        assertThat(result.status()).as("A reusable link can carry literal parameter data").isZero();
        assertThat(mapper.readTree(output.toFile()).at("/components/links/Next"))
                .as("Literal link parameters must survive extraction unchanged")
                .isEqualTo(source.at("/components/links/Next"));
    }

    // Prevents a new main package from silently escaping NullAway checking.
    @Test
    void marksEveryMainPackageForNullChecking() throws Exception {
        Path classes =
                Path.of(
                        OpenApiExtract.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Set<Package> packages = new java.util.HashSet<>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name =
                        classes.relativize(file)
                                .toString()
                                .replace(java.io.File.separatorChar, '.');
                Class<?> type =
                        Class.forName(
                                name.substring(0, name.length() - ".class".length()),
                                false,
                                OpenApiExtract.class.getClassLoader());
                packages.add(type.getPackage());
            }
        }
        assertThat(packages)
                .as("The reference set covers every compiled main package")
                .extracting(Package::getName)
                .containsExactly("org.fuin.porelo.forge.openapi");
        for (Package mainPackage : packages) {
            assertThat(mainPackage.isAnnotationPresent(NullMarked.class))
                    .as("NullAway must check package %s", mainPackage.getName())
                    .isTrue();
        }
    }

    private Result fetch(byte[] source, Path output, String operation, String... extra)
            throws Exception {
        return fetchWithDigest(source, output, digest(source), operation, extra);
    }

    private Result fetchWithDigest(
            byte[] source, Path output, String digest, String operation, String... extra)
            throws Exception {
        List<String> args = fetchArguments(output, digest, operation);
        args.addAll(List.of(extra));
        return run(args, uri -> source);
    }

    private List<String> fetchArguments(Path output, String digest, String operation) {
        return new ArrayList<>(
                List.of(
                        "fetch",
                        "--source",
                        SOURCE,
                        "--sha256",
                        digest,
                        "--out",
                        output.toString(),
                        "--operation",
                        operation));
    }

    private Result check(Path output, String operation, String... extra) throws Exception {
        List<String> args =
                new ArrayList<>(
                        List.of("check", "--extract", output.toString(), "--operation", operation));
        args.addAll(List.of(extra));
        return run(
                args,
                uri -> {
                    throw new AssertionError("Check must not download");
                });
    }

    private Result run(List<String> args, OpenApiExtract.Downloader downloader) throws Exception {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            int status = OpenApiExtract.run(args.toArray(String[]::new), downloader, stream);
            return new Result(status, error.toString(StandardCharsets.UTF_8));
        }
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/" + name)) {
            if (input == null) {
                throw new IOException("Missing fixture: " + name);
            }
            return input.readAllBytes();
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static List<String> names(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private record Result(int status, String error) {}
}
