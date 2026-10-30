package org.fuin.porelo.forge.openapi;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line entry point for fetching pinned extracts and checking their exact selection. */
public final class OpenApiExtract {
    private OpenApiExtract() {}

    /**
     * Runs fetch or check, printing one line per problem and exiting with status 1 on failure.
     *
     * @param args command and its named options
     */
    public static void main(String[] args) {
        System.exit(run(args, HttpsSource::download, System.err));
    }

    @FunctionalInterface
    interface Downloader {
        byte[] download(URI source) throws IOException, InterruptedException;
    }

    static int run(String[] args, Downloader downloader, PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(args);
            ExtractService service = new ExtractService(downloader);
            if (arguments.command().equals("fetch")) {
                service.fetch(
                        arguments.source(),
                        arguments.one("--sha256"),
                        Path.of(arguments.one("--out")),
                        arguments.replacements(),
                        arguments.selection());
            } else {
                service.check(Path.of(arguments.one("--extract")), arguments.selection());
            }
            return 0;
        } catch (Problems ex) {
            ex.problems().forEach(problem -> printProblem(error, problem));
        } catch (IOException | IllegalArgumentException ex) {
            printProblem(error, String.valueOf(ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            printProblem(error, "Download interrupted");
        }
        return 1;
    }

    private static void printProblem(PrintStream error, String problem) {
        error.println(problem.replaceAll("\\R", " "));
    }

    private record Arguments(String command, Map<String, List<String>> options) {
        static Arguments parse(String[] args) {
            if (args.length == 0 || !(args[0].equals("fetch") || args[0].equals("check"))) {
                throw new IllegalArgumentException("Expected command: fetch or check");
            }
            Set<String> allowed =
                    args[0].equals("fetch")
                            ? Set.of(
                                    "--source",
                                    "--sha256",
                                    "--out",
                                    "--replace",
                                    "--operation",
                                    "--webhook")
                            : Set.of("--extract", "--operation", "--webhook");
            Map<String, List<String>> options = new LinkedHashMap<>();
            for (int index = 1; index < args.length; index += 2) {
                String option = args[index];
                if (!allowed.contains(option)) {
                    throw new IllegalArgumentException("Unknown option: " + option);
                }
                if (index + 1 == args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + option);
                }
                options.computeIfAbsent(option, ignored -> new ArrayList<>()).add(args[index + 1]);
            }
            Arguments result = new Arguments(args[0], options);
            for (String option : allowed) {
                if (!Set.of("--operation", "--webhook", "--replace").contains(option)) {
                    result.one(option);
                }
            }
            return result;
        }

        String one(String option) {
            List<String> values = options.getOrDefault(option, List.of());
            if (values.size() != 1 || values.getFirst().isBlank()) {
                throw new IllegalArgumentException("Expected exactly one value for " + option);
            }
            return values.getFirst();
        }

        URI source() {
            try {
                return HttpsSource.validate(URI.create(one("--source")));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "Source must be an HTTPS URL without user information or a fragment");
            }
        }

        Selection selection() {
            return new Selection(
                    new LinkedHashSet<>(options.getOrDefault("--operation", List.of())),
                    new LinkedHashSet<>(options.getOrDefault("--webhook", List.of())));
        }

        List<ExtractService.Replacement> replacements() {
            List<ExtractService.Replacement> replacements = new ArrayList<>();
            for (String replacement : options.getOrDefault("--replace", List.of())) {
                int separator = replacement.indexOf('=');
                if (separator < 1) {
                    throw new IllegalArgumentException("Replacement must be <nonempty from>=<to>");
                }
                replacements.add(
                        new ExtractService.Replacement(
                                replacement.substring(0, separator),
                                replacement.substring(separator + 1)));
            }
            return replacements;
        }
    }
}
