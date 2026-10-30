package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Live checks are enabled only by the forgejo.live Maven property. */
class ForgejoLiveIT {
    @Test
    void everyOperationReadsASeededForgejoStrictly() throws Exception {
        try (ForgejoContainer fixture = ForgejoContainer.start()) {
            assertThat(new ForgejoScenario(fixture, Strictness.STRICT).run())
                    .as("the full operation inventory must execute against the live server")
                    .hasSize(ForgejoOperations.values().length);
            ForgejoRecorder.verifyDeliveries(ForgejoRecorder.awaitDeliveries(fixture));
        }
    }
}
