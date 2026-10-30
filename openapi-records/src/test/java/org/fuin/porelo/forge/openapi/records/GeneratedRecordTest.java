package org.fuin.porelo.forge.openapi.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reads JSON into a record the build generated from {@code src/main/openapi/records.json}, as a
 * client would, in the strict reading of its tests and the lenient one of its callers.
 */
class GeneratedRecordTest {
    private static final String COMPLETE =
            """
            {"id": 7, "closed_at": "2026-10-30", "title": "Fix", "+1": 3, "-1": 1, "state": "open", "conclusion": "success",
             "status": "completed", "verdict": "approved"}""";

    private static final ObjectMapper STRICT =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ObjectMapper LENIENT =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static Stream<ObjectMapper> readings() {
        return Stream.of(STRICT, LENIENT);
    }

    // Prevents a forge's new field from passing the tests unnoticed, when the records no longer
    // match the forge.
    @Test
    void theStrictReadingRefusesAFieldTheRecordDoesNotKnowByName() {
        String json = COMPLETE.replace("\"state\"", "\"added\": 1, \"state\"");

        assertThatExceptionOfType(JsonProcessingException.class)
                .as("a forge's new field must fail the tests, naming it")
                .isThrownBy(() -> STRICT.readValue(json, Reaction.class))
                .withMessageContaining("\"added\"");
    }

    // Prevents a caller from breaking when a forge adds a field within one API version.
    @Test
    void theLenientReadingPassesAFieldTheRecordDoesNotKnow() throws Exception {
        String json = COMPLETE.replace("\"state\"", "\"added\": 1, \"state\"");

        assertThat(LENIENT.readValue(json, Reaction.class).id())
                .as("a forge adds fields within one API version, which must not break a caller")
                .isEqualTo(7L);
    }

    // Prevents a caller from reading null where the record promises a value.
    @ParameterizedTest
    @MethodSource("readings")
    void aMissingRequiredFieldFailsByItsName(ObjectMapper reading) {
        String json = COMPLETE.replace("\"id\": 7, ", "");

        assertThatExceptionOfType(JsonProcessingException.class)
                .as("a required field may not be missing in either reading")
                .isThrownBy(() -> reading.readValue(json, Reaction.class))
                .withMessageContaining("'id'");
    }

    // Prevents a caller from reading null where the record promises a value.
    @ParameterizedTest
    @MethodSource("readings")
    void aNullInARequiredFieldThatMayNotBeNullFailsByItsName(ObjectMapper reading) {
        String json = COMPLETE.replace("\"id\": 7", "\"id\": null");

        assertThatExceptionOfType(JsonProcessingException.class)
                .as("the description does not let id be null")
                .isThrownBy(() -> reading.readValue(json, Reaction.class))
                .withMessageContaining("id may not be null");
    }

    // Prevents a forge's answer that the description allows from failing to read.
    @ParameterizedTest
    @MethodSource("readings")
    void aRequiredFieldThatMayBeNullPassesAsNullButNotWhenMissing(ObjectMapper reading)
            throws Exception {
        assertThat(
                        reading.readValue(
                                        COMPLETE.replace("\"2026-10-30\"", "null"), Reaction.class)
                                .closedAt())
                .as("closed_at is required but its type allows null")
                .isNull();
        assertThatExceptionOfType(JsonProcessingException.class)
                .as("being nullable does not make a required field optional")
                .isThrownBy(
                        () ->
                                reading.readValue(
                                        COMPLETE.replace("\"closed_at\": \"2026-10-30\", ", ""),
                                        Reaction.class))
                .withMessageContaining("'closed_at'");
    }

    // Prevents a forge's answer that leaves out or nulls an optional field from failing to read.
    @ParameterizedTest
    @MethodSource("readings")
    void anOptionalFieldPassesWhenAbsentOrNull(ObjectMapper reading) throws Exception {
        Reaction absent =
                reading.readValue(
                        """
                        {"id": 7, "closed_at": null, "status": "queued", "verdict": null}""",
                        Reaction.class);
        Reaction nulled =
                reading.readValue(
                        """
                        {"id": 7, "closed_at": null, "title": null, "+1": null, "state": null,
                         "conclusion": null, "status": "queued", "verdict": null}""",
                        Reaction.class);

        assertThat(absent)
                .as("no optional field is required")
                .isEqualTo(
                        new Reaction(
                                7L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Reaction.StatusEnum.QUEUED,
                                null));
        assertThat(nulled)
                .as("an optional field of any type reads as absent when a forge sends null")
                .isEqualTo(absent);
    }

    // Prevents an enum value a forge adds from passing the tests unnoticed where the description
    // does not let the enum be null.
    @ParameterizedTest
    @MethodSource("readings")
    void anUnknownValueOfAnEnumThatMayNotBeNullFailsNamingIt(ObjectMapper reading) {
        String json = COMPLETE.replace("\"open\"", "\"merged\"");

        assertThatExceptionOfType(JsonProcessingException.class)
                .as("the generator's fromValue throws for a value it does not know")
                .isThrownBy(() -> reading.readValue(json, Reaction.class))
                .withMessageContaining("'merged'");
    }

    // Prevents a caller from failing on a value a forge adds to an enum the description lets be
    // null; that the strict reading does not catch it either is FC01's to decide.
    @ParameterizedTest
    @MethodSource("readings")
    void anUnknownValueOfAnEnumThatMayBeNullReadsAsNull(ObjectMapper reading) throws Exception {
        String json = COMPLETE.replace("\"success\"", "\"skipped\"");

        assertThat(reading.readValue(json, Reaction.class).conclusion())
                .as("the generator's fromValue returns null for a value it does not know")
                .isNull();
    }

    // Prevents a caller from dereferencing a field that may be absent or null without NullAway
    // noticing.
    @Test
    void aComponentIsNullableExactlyWhenTheDescriptionLetsItBeAbsentOrNull() {
        Map<String, Boolean> nullable =
                Arrays.stream(Reaction.class.getRecordComponents())
                        .collect(
                                Collectors.toMap(
                                        RecordComponent::getName,
                                        component ->
                                                component
                                                        .getAnnotatedType()
                                                        .isAnnotationPresent(Nullable.class)));

        assertThat(nullable)
                .as("only id and status are required and may not be null")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "id", false,
                                "closedAt", true,
                                "title", true,
                                "plus1", true,
                                "_1", true,
                                "state", true,
                                "conclusion", true,
                                "status", false,
                                "verdict", true));
    }

    // Prevents a caller from reading null from a required enum the description does not let be
    // null.
    @ParameterizedTest
    @MethodSource("readings")
    void aNullInARequiredEnumThatMayNotBeNullFailsByItsName(ObjectMapper reading) {
        String json = COMPLETE.replace("\"completed\"", "null");

        assertThatExceptionOfType(JsonProcessingException.class)
                .as("the description does not let status be null")
                .isThrownBy(() -> reading.readValue(json, Reaction.class))
                .withMessageContaining("status may not be null");
    }

    // Prevents a forge's answer that the description allows, a required enum set to null, from
    // failing to read, and its absence from passing.
    @ParameterizedTest
    @MethodSource("readings")
    void aRequiredEnumThatMayBeNullPassesAsNullButNotWhenMissing(ObjectMapper reading)
            throws Exception {
        assertThat(
                        reading.readValue(COMPLETE.replace("\"approved\"", "null"), Reaction.class)
                                .verdict())
                .as("verdict is required but its type allows null")
                .isNull();
        assertThatExceptionOfType(JsonProcessingException.class)
                .as("being nullable does not make a required enum optional")
                .isThrownBy(
                        () ->
                                reading.readValue(
                                        COMPLETE.replace(", \"verdict\": \"approved\"", ""),
                                        Reaction.class))
                .withMessageContaining("'verdict'");
    }

    // Prevents NullAway from silently skipping the generated records, whose @Nullable only counts
    // in a @NullMarked package.
    @Test
    void theGeneratedPackageIsNullMarked() {
        assertThat(Reaction.class.getPackage().isAnnotationPresent(NullMarked.class))
                .as("the generator's package-info.java marks the package")
                .isTrue();
    }

    // Prevents GitHub's reactions from being lost or written back under names the forge does not
    // know.
    @Test
    void jsonNamesThatAreNotJavaNamesAreReadAndWrittenBack() throws Exception {
        Reaction reaction = STRICT.readValue(COMPLETE, Reaction.class);

        assertThat(reaction.plus1()).as("GitHub's \"+1\" reaction").isEqualTo(3);
        assertThat(reaction._1()).as("GitHub's \"-1\" reaction").isEqualTo(1);
        assertThat(STRICT.readTree(STRICT.writeValueAsString(reaction)))
                .as("writing the record gives the forge's own names back")
                .isEqualTo(STRICT.readTree(COMPLETE));
    }
}
