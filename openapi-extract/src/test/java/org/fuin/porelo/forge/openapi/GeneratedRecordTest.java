package org.fuin.porelo.forge.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fuin.porelo.forge.openapi.fixture.Reaction;
import org.junit.jupiter.api.Test;

/**
 * Reads JSON into a record the build generated from {@code src/test/openapi/records.json}, as a
 * client would.
 */
class GeneratedRecordTest {
    private static final String COMPLETE =
            """
            {"id": 7, "closed_at": "2026-10-30", "title": "Fix", "+1": 3, "-1": 1, "state": "open"}""";

    private final ObjectMapper strict =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ObjectMapper lenient =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void theStrictReadingRefusesAFieldTheRecordDoesNotKnowByName() {
        String json = COMPLETE.replace("\"state\"", "\"added\": 1, \"state\"");

        assertThatThrownBy(() -> strict.readValue(json, Reaction.class))
                .as("a forge's new field must fail the tests, naming it")
                .hasMessageContaining("\"added\"");
    }

    @Test
    void theLenientReadingPassesAFieldTheRecordDoesNotKnow() throws Exception {
        String json = COMPLETE.replace("\"state\"", "\"added\": 1, \"state\"");

        assertThat(lenient.readValue(json, Reaction.class).id())
                .as("a forge adds fields within one API version, which must not break a caller")
                .isEqualTo(7L);
    }

    @Test
    void aMissingRequiredFieldFailsByItsName() {
        String json = COMPLETE.replace("\"id\": 7, ", "");

        assertThatThrownBy(() -> lenient.readValue(json, Reaction.class))
                .as("a required field may not be missing, even in the lenient reading")
                .hasMessageContaining("'id'");
    }

    @Test
    void aNullInARequiredFieldThatMayNotBeNullFailsByItsName() {
        String json = COMPLETE.replace("\"id\": 7", "\"id\": null");

        assertThatThrownBy(() -> lenient.readValue(json, Reaction.class))
                .as("the description does not let id be null")
                .hasMessageContaining("id may not be null");
    }

    @Test
    void aRequiredFieldThatMayBeNullPassesAsNullButNotWhenMissing() throws Exception {
        assertThat(
                        strict.readValue(COMPLETE.replace("\"2026-10-30\"", "null"), Reaction.class)
                                .closedAt())
                .as("closed_at is required but its type allows null")
                .isNull();
        assertThatThrownBy(
                        () ->
                                strict.readValue(
                                        COMPLETE.replace("\"closed_at\": \"2026-10-30\", ", ""),
                                        Reaction.class))
                .as("being nullable does not make a required field optional")
                .hasMessageContaining("'closed_at'");
    }

    @Test
    void anOptionalFieldPassesWhenAbsent() throws Exception {
        Reaction reaction = strict.readValue("{\"id\": 7, \"closed_at\": null}", Reaction.class);

        assertThat(reaction.title()).as("title is not required").isNull();
        assertThat(reaction.state()).as("state is not required").isNull();
    }

    @Test
    void jsonNamesThatAreNotJavaNamesAreReadAndWrittenBack() throws Exception {
        Reaction reaction = strict.readValue(COMPLETE, Reaction.class);

        assertThat(reaction.plus1()).as("GitHub's \"+1\" reaction").isEqualTo(3);
        assertThat(reaction._1()).as("GitHub's \"-1\" reaction").isEqualTo(1);
        assertThat(strict.readTree(strict.writeValueAsString(reaction)))
                .as("writing the record gives the forge's own names back")
                .isEqualTo(strict.readTree(COMPLETE));
    }
}
