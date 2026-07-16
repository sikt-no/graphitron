package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R38 structural pins: post-flip, every batched-rows DataFetcher emission site routes through
 * {@link DataLoaderFetcherEmitter#build}, and every batched-rows method emission site routes
 * through {@link RowsMethodSkeleton#build}. The pins enforce the unified-seam invariant the
 * spec promises in its Tests section
 * ({@code fetcherEmitter_unifiedDispatch}, {@code rowsMethodEmitter_unifiedSkeleton}).
 *
 * <p>Implementation: source-file scan. The unified-emitter call count is asserted against the
 * generators package's source files (excluding the unified emitters themselves). A handcrafted
 * regression — a fourth DataFetcher builder that bypasses {@code DataLoaderFetcherEmitter.build},
 * or a fifth rows-method builder that bypasses {@code RowsMethodSkeleton.build} — removes one
 * call site, drops the count, and trips the assertion.
 *
 * <p>If a new emission site is legitimately added (e.g. a sixth rows-method body permit for
 * R75's {@code ResultRowWalk}), update the expected count here; the deliberate moment of
 * touching this test is the architectural review point the pin is designed to create.
 *
 * <p>Note: this pin doesn't cover {@link MultiTablePolymorphicEmitter}'s batched fetcher
 * family, which is its own emit family with its own structural axes. R38's seam targets the
 * five-permit rows-method shape on table-bound batched fields; the polymorphic seam is
 * separate work.
 */
@UnitTier
class UnifiedEmissionPinsTest {

    private static final Path GENERATORS_DIR =
        Path.of("src/main/java/no/sikt/graphitron/rewrite/generators");

    @Test
    void fetcherEmitter_unifiedDispatch() throws IOException {
        // Every DataFetcher MethodSpec emit site in the generators package routes through
        // DataLoaderFetcherEmitter.build. Current sites (2): TypeFetcherGenerator's
        // buildServiceDataFetcher and buildBatchedDataFetcher (R314 slice 2 merged the former
        // buildSplitQueryDataFetcher / buildRecordBasedDataFetcher pair onto the one
        // source-shape-gated builder; generated output stayed byte-identical).
        long unifiedCalls = countAcrossGenerators(
            Pattern.compile("\\bDataLoaderFetcherEmitter\\.build\\b"),
            "DataLoaderFetcherEmitter.java");
        assertThat(unifiedCalls)
            .as("Every R38 DataFetcher emit site outside DataLoaderFetcherEmitter itself routes "
                + "through DataLoaderFetcherEmitter.build. The two sites — "
                + "buildServiceDataFetcher and buildBatchedDataFetcher — are the post-R314-slice-2 "
                + "enumeration. A handcrafted bypass replaces one call here with inline "
                + "DataFetcher MethodSpec construction; the count drop trips this pin.")
            .isEqualTo(2);
    }

    @Test
    void rowsMethodEmitter_unifiedSkeleton() throws IOException {
        // Every rows-method MethodSpec emit site in the generators package routes through
        // RowsMethodSkeleton.build. Current sites (5): SplitRowsMethodEmitter's three internal
        // builders (buildListMethod, buildSingleMethod, buildConnectionMethod — the dissolved
        // record-table-method shape routes through the first two since R314 slice 2b) plus
        // TypeFetcherGenerator.buildServiceRowsMethod (ServiceRecordField verbatim return) plus
        // SplitRowsMethodEmitter.buildServiceTableLift (R285 ServiceTableField lift-back
        // re-projection). Together they cover all three RowsMethodBody permits (the two SQL
        // permits route through the three internal SQL builders by cardinality; the Service
        // permit routes via the two service emit sites).
        long unifiedCalls = countAcrossGenerators(
            Pattern.compile("\\bRowsMethodSkeleton\\.build\\b"),
            "RowsMethodSkeleton.java");
        assertThat(unifiedCalls)
            .as("Every R38 rows-method emit site outside RowsMethodSkeleton itself routes through "
                + "RowsMethodSkeleton.build. A handcrafted bypass replaces one call here with "
                + "inline rows-method MethodSpec construction; the count drop trips this pin.")
            .isEqualTo(5);
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
