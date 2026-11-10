package org.fuin.porelo.forge.openapi.records.boot4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Reads JSON into the records generated with {@code useSpringBoot4}, where a schema with {@code
 * additionalProperties} becomes a record.
 */
class SpringBoot4RecordTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String JSON =
            """
            {"checks": "write", "added": %s}""";

    // Prevents a null in an open map, which its schema does not allow, from reaching a consumer the
    // record promises a map without nulls.
    @Test
    void aNullInARecordsOpenMapFails() throws Exception {
        assertThat(Permissions.class.isRecord())
                .as("under useSpringBoot4 the record template, not the class one, makes it")
                .isTrue();
        assertThat(
                        MAPPER.readValue(JSON.formatted("\"read\""), Permissions.class)
                                .getAdditionalProperty("added"))
                .as("an undeclared property is read")
                .isEqualTo("read");
        assertThatExceptionOfType(JsonProcessingException.class)
                .as("a null the open map's schema does not allow fails, naming the property")
                .isThrownBy(() -> MAPPER.readValue(JSON.formatted("null"), Permissions.class))
                .withMessageContaining("Additional property added is null");
    }

    // Prevents a consumer from putting a null into the open map after the record refused one.
    @Test
    void aRecordsOpenMapCannotBeChangedAfterwards() throws Exception {
        Permissions permissions = MAPPER.readValue(JSON.formatted("\"read\""), Permissions.class);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("the open map of a record holds what was read and nothing put later")
                .isThrownBy(() -> permissions.additionalProperties().put("later", "x"));
    }

    // Prevents a record read without undeclared properties from handing a consumer a null map.
    @Test
    void aRecordWithoutUndeclaredPropertiesHasAnEmptyOpenMap() throws Exception {
        assertThat(
                        MAPPER.readValue("{\"checks\": \"write\"}", Permissions.class)
                                .additionalProperties())
                .as("no undeclared property gives an empty map, not null")
                .isEmpty();
    }
}
