package org.fuin.porelo.forge.openapi.templates;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

class NullMarkedTest {
    // Prevents the module's one package from bypassing NullAway checks.
    @Test
    void theTemplatesPackageHasACheckedNullContract() {
        assertThat(OpenApiTemplates.class.getPackage().isAnnotationPresent(NullMarked.class))
                .as("%s must be checked by NullAway", OpenApiTemplates.class.getPackageName())
                .isTrue();
    }
}
