package org.fuin.porelo.forge.forgejo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps subprocess output private; a failure exposes only a fixed action and exit status. */
final class Podman {
    private static final int MAX_OUTPUT = 2 * 1024 * 1024;

    private Podman() {}

    static List<String> command(String... arguments) {
        if (arguments.length > 0 && (arguments[0].equals("run") || arguments[0].equals("create"))) {
            List<String> drivers =
                    java.util.Arrays.stream(arguments)
                            .filter(value -> value.startsWith("--log-driver"))
                            .toList();
            if (!drivers.equals(List.of("--log-driver=none"))) {
                throw new IllegalArgumentException(
                        "Fixture containers must disable persistent output logging");
            }
        }
        List<String> command = new ArrayList<>(List.of("podman"));
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    static String run(String action, String input, String... arguments) {
        List<String> command = command(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            AtomicReference<byte[]> output = new AtomicReference<>();
            AtomicReference<IOException> failure = new AtomicReference<>();
            Thread reader =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var stream = process.getInputStream()) {
                                            byte[] bytes = stream.readNBytes(MAX_OUTPUT + 1);
                                            if (bytes.length > MAX_OUTPUT) {
                                                process.destroyForcibly();
                                                failure.set(
                                                        new IOException(
                                                                "Subprocess output exceeds its bound"));
                                            } else {
                                                output.set(bytes);
                                            }
                                        } catch (IOException ex) {
                                            failure.set(ex);
                                        }
                                    });
            try {
                try (var stream = process.getOutputStream()) {
                    stream.write(input.getBytes(StandardCharsets.UTF_8));
                }
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(action + " timed out");
                }
                if (!reader.join(Duration.ofSeconds(5)) || failure.get() != null) {
                    throw new IllegalStateException(action + " output could not be read");
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException(
                            action + " failed with exit " + process.exitValue());
                }
                byte[] bytes = output.get();
                if (bytes == null) {
                    throw new IllegalStateException(action + " produced no readable output");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            } finally {
                process.destroyForcibly();
                reader.join(Duration.ofSeconds(5));
            }
        } catch (IOException ex) {
            throw new IllegalStateException(action + " could not start or exchange input");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + " was interrupted");
        }
    }
}
