package org.fuin.porelo.forge.openapi.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Each template of this module is a copy of the pinned generator's own with a change; this fails
 * when the generator's template is no longer the one the override was copied from, so moving the
 * pin re-copies it.
 */
class TemplateOverrideTest {
    /**
     * The SHA-256 of the generator's template each override was copied from, at the pinned version.
     */
    private static final Map<String, String> COPIED_FROM =
            Map.of(
                    "additionalProperties.mustache",
                            "42cec46b2435f34081298e91af5d43c1cab8458372c04e8fe2df3786001ce496",
                    "enumClass.mustache",
                            "0746287359be6c0ea1e65bd3468170dfeb8195f8c9a227fe516464f516d9cbb8",
                    "enumOuterClass.mustache",
                            "96de8d26e73a186f40f7f01d2a77600ae17196a066c3bfc586dd819df0c7d056",
                    "generatedAnnotation.mustache",
                            "b31ccf37d4b7dc149b1a8ec9548c1e1f73bf9bf0142cba2d5922ea0e7caa4d06",
                    "jackson_annotations.mustache",
                            "9ce6242195a668d32316925b99d65aab51d91bb66ccf7aa7c1692b3db9da5177",
                    "model.mustache",
                            "85d31955013795448737b7df71562925f19082bfb742b33d508c1db56ef7f334",
                    "oneof_interface.mustache",
                            "82f42afc4a6780ab99aa13354bb37c76aa8adc9ce7745b146bcf91f0db0a04e9",
                    "pojo.mustache",
                            "c77509816ea11b734ef971e10e501a2f8aeb82fb3b48933924e1d74327ceef21",
                    "record.mustache",
                            "b5ffed7837c04cb5a6000a513bb5bb278daa3703a88c35ef560312bda814b900");

    // Prevents an override whose original nobody checks from drifting from the generator unseen.
    @Test
    void everyOverrideIsListedWithTheTemplateItWasCopiedFrom() throws IOException {
        Path directory = Path.of("src/main/resources", OpenApiTemplates.RESOURCE_PATH);
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.map(file -> file.getFileName().toString()).collect(Collectors.toSet()))
                    .as("an override without its original's digest could drift unseen")
                    .isEqualTo(COPIED_FROM.keySet());
        }
    }

    // Prevents a moved pin from generating with overrides of templates the generator changed.
    @Test
    void thePinnedGeneratorStillHasTheTemplatesTheOverridesWereCopiedFrom() throws Exception {
        for (Map.Entry<String, String> copied : COPIED_FROM.entrySet()) {
            String resource = "/JavaSpring/" + copied.getKey();
            try (InputStream original = getClass().getResourceAsStream(resource)) {
                assertThat(original).as("the generator has %s", resource).isNotNull();
                String digest =
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(original.readAllBytes()));
                assertThat(digest)
                        .as(
                                "%s changed in the generator; copy it again into this module and redo the change",
                                copied.getKey())
                        .isEqualTo(copied.getValue());
            }
        }
    }

    // Prevents a consumer from pinning a generator the templates were not copied from.
    @Test
    void theTemplatesNameTheGeneratorReleaseTheBuildPins() {
        assertThat(OpenApiTemplates.GENERATOR_VERSION)
                .as("a consumer reads which generator release the templates fit from here")
                .isEqualTo(System.getProperty("openapi.generator.version"));
    }

    // Prevents a consumer's generation from missing the templates in the jar.
    @Test
    void theResourcePathHoldsTheTemplates() {
        assertThat(OpenApiTemplates.RESOURCE_PATH)
                .as(
                        "the generator resolves a template in a jar only without a leading or trailing slash")
                .doesNotStartWith("/")
                .doesNotEndWith("/");
        for (String template : COPIED_FROM.keySet()) {
            assertThat(
                            getClass()
                                    .getClassLoader()
                                    .getResource(OpenApiTemplates.RESOURCE_PATH + "/" + template))
                    .as("a consumer's templateResourcePath finds %s", template)
                    .isNotNull();
        }
    }
}
