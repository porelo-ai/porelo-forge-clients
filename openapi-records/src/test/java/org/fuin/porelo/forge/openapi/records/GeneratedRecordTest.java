package org.fuin.porelo.forge.openapi.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
            {"id": 7, "closed_at": "2026-10-30", "title": "Fix", "+1": 3, "-1": 1, "state": "open"}""";

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

        assertThatThrownBy(() -> STRICT.readValue(json, Reaction.class))
                .as("a forge's new field must fail the tests, naming it")
                .hasMessageContaining("\"added\"");
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

        assertThatThrownBy(() -> reading.readValue(json, Reaction.class))
                .as("a required field may not be missing in either reading")
                .hasMessageContaining("'id'");
    }

    // Prevents a caller from reading null where the record promises a value.
    @ParameterizedTest
    @MethodSource("readings")
    void aNullInARequiredFieldThatMayNotBeNullFailsByItsName(ObjectMapper reading) {
        String json = COMPLETE.replace("\"id\": 7", "\"id\": null");

        assertThatThrownBy(() -> reading.readValue(json, Reaction.class))
                .as("the description does not let id be null")
                .hasMessageContaining("id may not be null");
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
        assertThatThrownBy(
                        () ->
                                reading.readValue(
                                        COMPLETE.replace("\"closed_at\": \"2026-10-30\", ", ""),
                                        Reaction.class))
                .as("being nullable does not make a required field optional")
                .hasMessageContaining("'closed_at'");
    }

    // Prevents a forge's answer that leaves out or nulls an optional field from failing to read.
    @ParameterizedTest
    @MethodSource("readings")
    void anOptionalFieldPassesWhenAbsentOrNull(ObjectMapper reading) throws Exception {
        Reaction absent = reading.readValue("{\"id\": 7, \"closed_at\": null}", Reaction.class);
        Reaction nulled =
                reading.readValue(
                        "{\"id\": 7, \"closed_at\": null, \"title\": null}", Reaction.class);

        assertThat(absent.title()).as("title is not required").isNull();
        assertThat(nulled.title())
                .as("an optional field reads as absent when a forge sends null")
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
                .as("only id is required and may not be null")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "id", false,
                                "closedAt", true,
                                "title", true,
                                "plus1", true,
                                "_1", true,
                                "state", true));
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
