package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural pins for the unified emission seams: every batched-rows DataFetcher emission site
 * routes through {@link DataLoaderFetcherEmitter#build}, and every launcher (root and rows
 * method alike) renders through {@code RootLauncherRenderer.render} over a launcher command row
 * ({@code fetcherEmitter_unifiedDispatch}, {@code launcherRenderer_unifiedRenderSites}).
 *
 * <p>Implementation: source-file scan. The unified-emitter call count is asserted against the
 * generators package's source files (excluding the unified emitters themselves). A handcrafted
 * regression — a fourth DataFetcher builder that bypasses {@code DataLoaderFetcherEmitter.build},
 * or a launcher body built inline instead of through the renderer — moves one
 * call site, moves the count, and trips the assertion.
 *
 * <p>If a new emission site is legitimately added, update the expected count here; the
 * deliberate moment of
 * touching this test is the architectural review point the pin is designed to create.
 *
 * <p>Note: this pin doesn't cover {@link MultiTablePolymorphicEmitter}'s batched fetcher
 * family, which is its own emit family with its own structural axes. The unified seam targets
 * the service-permit rows-method shape (the SQL-shaped bodies render through the
 * launcher-command path); the polymorphic seam is separate work.
 */
@UnitTier
class UnifiedEmissionPinsTest {

    private static final Path GENERATORS_DIR =
        Path.of("src/main/java/no/sikt/graphitron/rewrite/generators");

    @Test
    void fetcherEmitter_unifiedDispatch() throws IOException {
        // Every DataFetcher MethodSpec emit site in the generators package routes through
        // DataLoaderFetcherEmitter.build. Current sites (3): TypeFetcherGenerator's
        // buildServiceDataFetcher and buildBatchedDataFetcher (the former
        // buildSplitQueryDataFetcher / buildRecordBasedDataFetcher pair was merged onto the one
        // source-shape-gated builder; generated output stayed byte-identical), plus
        // buildPivotBatchedDataFetcher (the @pivot specialisation: empty prelude, Record value,
        // no NULL-key short-circuit).
        long unifiedCalls = countAcrossGenerators(
            Pattern.compile("\\bDataLoaderFetcherEmitter\\.build\\b"),
            "DataLoaderFetcherEmitter.java");
        assertThat(unifiedCalls)
            .as("Every R38 DataFetcher emit site outside DataLoaderFetcherEmitter itself routes "
                + "through DataLoaderFetcherEmitter.build. The three sites — "
                + "buildServiceDataFetcher, buildBatchedDataFetcher, and "
                + "buildPivotBatchedDataFetcher — are the current enumeration. A handcrafted "
                + "bypass replaces one call here with inline "
                + "DataFetcher MethodSpec construction; the count drop trips this pin.")
            .isEqualTo(3);
    }

    @Test
    void launcherRenderer_unifiedRenderSites() throws IOException {
        // The rows-method skeleton retired with the service fold: every rows-method (and root
        // launcher) body now renders through RootLauncherRenderer.render over a launcher
        // command row. This pin is the skeleton pin's successor with a live failure mode
        // (asserting zero RowsMethodSkeleton calls after its deletion could never fail again):
        // it counts the render call sites in the generators package. Current sites (9), all in
        // TypeFetcherGenerator's dispatch: the four root arms (table, routine, interface,
        // lookup) and the five child arms (batched table, batched lookup, batched pivot,
        // service table lift, service record delegate). A handcrafted bypass replaces one call
        // with inline MethodSpec construction and drops the count; a legitimately new launcher
        // family raises it, and touching this number is the review point.
        long renderSites = countAcrossGenerators(
            Pattern.compile("RootLauncherRenderer\\s*\\.render\\("),
            "RootLauncherRenderer.java");
        assertThat(renderSites)
            .as("Every launcher emit site in generators/ routes through "
                + "RootLauncherRenderer.render; a count move in either direction is a "
                + "deliberate edit here")
            .isEqualTo(9);
    }

    private static long countAcrossGenerators(Pattern pattern, String excludeFile) throws IOException {
        try (var stream = Files.list(GENERATORS_DIR)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals(excludeFile))
                .mapToLong(p -> {
                    try {
                        return pattern.matcher(Files.readString(p)).results().count();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .sum();
        }
    }
}
