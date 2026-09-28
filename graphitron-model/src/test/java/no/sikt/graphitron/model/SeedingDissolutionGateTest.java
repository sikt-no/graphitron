package no.sikt.graphitron.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeding fixture only loses callers.
 *
 * <p>A seeded case states the rows a gatherer would have written, which was the only way to reach a
 * derivation while this module could not run the gatherers. It can run them now, so a case states
 * its facts as a document and capture produces the rows; a case that seeds them instead is testing
 * the fixture. The gate is here because nothing else notices: a new seeded case is written by
 * copying an existing one, and the file it copies never gets opened.
 *
 * <p>A ceiling rather than a roster of names, because conversions run in several sessions at once
 * and a roster would collide on every one of them. The count is allowed to be stale on the low
 * side; what it may not do is rise.
 */
class SeedingDissolutionGateTest {

    private static final Path TESTS = Path.of("src/test/java/no/sikt/graphitron/model");

    private static final List<String> NOT_CALLERS =
        List.of("SeededStore.java", "SeedingDissolutionGateTest.java");

    /** Callers on 2026-09-28. Lower it when you convert one. */
    private static final int CEILING = 83;

    @Test
    @DisplayName("no case starts seeding rows it could state as a document")
    void theSeedingFixtureOnlyLosesCallers() throws IOException {
        var callers = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(TESTS)) {
            List<Path> sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path source : sources) {
                // The fixture itself, and this gate, which names it to count it.
                if (NOT_CALLERS.contains(source.getFileName().toString())) {
                    continue;
                }
                if (Files.readString(source).contains("SeededStore")) {
                    callers.add(source.getFileName().toString());
                }
            }
        }
        assertThat(callers)
            .as("seeding is dissolving: state the facts as a document under "
                + "src/test/resources/facts and let capture produce the rows")
            .hasSizeLessThanOrEqualTo(CEILING);
    }
}
