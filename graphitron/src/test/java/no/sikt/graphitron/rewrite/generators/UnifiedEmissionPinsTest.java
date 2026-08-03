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
 * <p>Note: the dispatch and render pins don't cover {@link MultiTablePolymorphicEmitter}'s
 * batched fetcher family, which is its own emit family with its own structural axes. The
 * unified seam targets the service-permit rows-method shape (the SQL-shaped bodies render
 * through the launcher-command path); the polymorphic family reads its registration facts off
 * the batched leaves' {@code BatchKeyField} capability but keeps its own emission, which is
 * why {@code dataLoaderFactory_registrationHomes} names it as a distinct registration home.
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
        // it counts the render call sites in the generators package. Current sites (8), all in
        // TypeFetcherGenerator: the three root arms (table, routine, interface; the lookup
        // fold merged the keyed-lookup root onto the table arm), the four child arms (batched
        // table with the keyed-lookup fork inside, batched pivot, service table lift, service
        // record delegate), and the DML reentry companion's one shared write-arm call
        // (emitReentry; the projected and discriminated arms converge on it, so the fold added
        // one site, not two). A handcrafted bypass replaces one call with inline MethodSpec
        // construction and drops the count; a legitimately new launcher family raises it, and
        // touching this number is the review point.
        long renderSites = countAcrossGenerators(
            Pattern.compile("RootLauncherRenderer\\s*\\.render\\("),
            "RootLauncherRenderer.java");
        assertThat(renderSites)
            .as("Every launcher emit site in generators/ routes through "
                + "RootLauncherRenderer.render; a count move in either direction is a "
                + "deliberate edit here")
            .isEqualTo(8);
    }

    @Test
    void dataLoaderFactory_registrationHomes() throws IOException {
        // The DataLoader-regime census: every source file (recursively under generators/) that
        // emits a DataLoaderFactory.newDataLoader / newMappedDataLoader call is a registration
        // home, and the homes are named exactly so a new hand-rolled regime cannot hide. The
        // three: DataLoaderFetcherEmitter (the unified seam; container-forked factory name),
        // MultiTablePolymorphicEmitter (the batched polymorphic family's list and connection
        // fetchers; positional-list by the batched leaves' constructor entailment), and
        // util/QueryNodeFetcherClassGenerator (the node-resolution batch loader). A fourth
        // match means a fourth regime: fold it through the seam or name it here deliberately.
        Pattern factoryCall = Pattern.compile("\\$T\\.new(Mapped)?DataLoader\\(|\"new(Mapped)?DataLoader\"");
        var homes = new java.util.TreeMap<String, Long>();
        try (var stream = Files.walk(GENERATORS_DIR)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        long n = factoryCall.matcher(Files.readString(p)).results().count();
                        if (n > 0) homes.put(p.getFileName().toString(), n);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
        assertThat(homes)
            .as("DataLoaderFactory emission homes under generators/ (file -> factory-call "
                + "emission count); a new entry is a new DataLoader regime and a deliberate "
                + "review point")
            .containsExactly(
                java.util.Map.entry("DataLoaderFetcherEmitter.java", 2L),
                java.util.Map.entry("MultiTablePolymorphicEmitter.java", 2L),
                java.util.Map.entry("QueryNodeFetcherClassGenerator.java", 1L));
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
