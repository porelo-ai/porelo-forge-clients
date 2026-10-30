package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Live-only recording entry point. It persists scrubbed responses and re-signed deliveries. */
public final class ForgejoRecorder {
    private static final Set<String> PROVISIONAL_EVENTS =
            Set.of("pull_request", "pull_request_approved", "action_run_success");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ForgejoRecorder() {}

    /** Writes recordings into the one directory argument; raw credentials remain in memory. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected a recordings directory");
        }
        Path destination = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(destination);
        if (Files.isSymbolicLink(destination)) {
            throw new IllegalArgumentException("The recordings directory must not be a symlink");
        }
        try (ForgejoContainer fixture = ForgejoContainer.start()) {
            var captures = new ForgejoScenario(fixture, Strictness.LENIENT).run();
            RecordingScrubber scrubber = new RecordingScrubber(fixture.secrets());
            for (var capture : captures) {
                write(
                        destination.resolve(capture.operation() + ".json"),
                        scrubber.scrub(capture.recording()));
            }
            List<WebhookReceiver.Delivery> deliveries = awaitDeliveries(fixture);
            writeDeliveries(destination, deliveries, scrubber);
            verifyDeliveries(deliveries);
        }
    }

    static void writeDeliveries(
            Path destination, List<WebhookReceiver.Delivery> deliveries, RecordingScrubber scrubber)
            throws Exception {
        // The first delivery of each event is kept, so a recording again changes one file per
        // event.
        Set<String> recorded = new HashSet<>();
        for (var delivery : deliveries) {
            if (!recorded.add(delivery.event())) {
                continue;
            }
            if (!delivery.event().matches("[a-z_]{1,80}")) {
                throw new IllegalStateException("Invalid delivered event header");
            }
            String body = scrubDelivery(delivery.body(), scrubber);
            ObjectNode recording = JSON.createObjectNode();
            recording.put("event", delivery.event());
            recording.put("body", body);
            recording.put(
                    "signature",
                    HexFormat.of()
                            .formatHex(
                                    ForgejoWebhook.hmac(
                                            body.getBytes(StandardCharsets.UTF_8),
                                            RecordingScrubber.TEST_SECRET.getBytes(
                                                    StandardCharsets.UTF_8))));
            write(destination.resolve("webhook-" + delivery.event() + ".json"), recording);
        }
    }

    private static String scrubDelivery(String body, RecordingScrubber scrubber) {
        try {
            var payload = JSON.readTree(body);
            if (payload == null) {
                throw new IllegalStateException("A live webhook has no JSON payload");
            }
            return JSON.writeValueAsString(scrubber.scrub(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot parse or serialize a live webhook payload");
        }
    }

    static List<WebhookReceiver.Delivery> awaitDeliveries(ForgejoContainer fixture) {
        for (int attempt = 0; attempt < 30; attempt++) {
            var deliveries = fixture.deliveries();
            if (deliveries.stream()
                    .map(WebhookReceiver.Delivery::event)
                    .collect(Collectors.toSet())
                    .containsAll(PROVISIONAL_EVENTS)) {
                return deliveries;
            }
            ForgejoContainer.pause();
        }
        return fixture.deliveries();
    }

    static void verifyDeliveries(List<WebhookReceiver.Delivery> deliveries) {
        Set<String> events =
                deliveries.stream()
                        .map(WebhookReceiver.Delivery::event)
                        .collect(Collectors.toSet());
        Set<String> missing = new java.util.TreeSet<>(PROVISIONAL_EVENTS);
        missing.removeAll(events);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Provisional webhook names need confirmation; missing "
                            + missing
                            + ", received "
                            + events.stream()
                                    .filter(value -> value.matches("[a-z_]{1,80}"))
                                    .sorted()
                                    .toList());
        }
        ForgejoWebhook reader = new ForgejoWebhook(WebhookReceiver.LIMIT, Strictness.LENIENT);
        for (var delivery : deliveries) {
            if (PROVISIONAL_EVENTS.contains(delivery.event())) {
                byte[] bytes = delivery.body().getBytes(StandardCharsets.UTF_8);
                byte[] key = RecordingScrubber.TEST_SECRET.getBytes(StandardCharsets.UTF_8);
                reader.read(
                        new ByteArrayInputStream(bytes),
                        HexFormat.of().formatHex(ForgejoWebhook.hmac(bytes, key)),
                        delivery.event(),
                        key);
            }
        }
    }

    private static void write(Path path, com.fasterxml.jackson.databind.JsonNode value)
            throws Exception {
        Path temporary = Files.createTempFile(path.getParent(), "scrubbed-", ".json");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
