package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture warnings-as-errors gate: builds the sakila-example schema and asserts the
 * generator's warning channel emits <em>exactly</em> the expected set (one advisory).
 *
 * <p>Fixture builds treat generator warnings as errors unless the fixture's point is to assert
 * the warning path. This test declares which warnings the example fixture intentionally carries:
 * an accidental new warning fails the size assertion, a vanished expected warning fails the
 * content assertion, both over the {@code warnings()} list
 * {@link GraphQLRewriteGenerator#buildOutput()} exposes.
 *
 * <p>The expected entry is the {@code @asConnection} + required same-table
 * {@code @nodeId} hygiene advisory on {@code Query.filmsConnectionByRequiredIds}. That field is
 * the execution-tier proof (in {@link GraphQLQueryTest#filmsConnectionByRequiredIds_idsSupplied_paginatesBoundedSet})
 * that the production shape ships a working WHERE-pk-IN connection; the shape intrinsically
 * warns, so the warning is pinned here rather than tolerated as log noise. The message format
 * itself is pinned on minimal SDL by {@code AsConnectionSameTableWarnFormatTest}.
 *
 * <p>The per-usage {@code @table}-on-input deprecation advisories ({@link BuildWarning.NoRule})
 * are segregated from the exactly-one assertion, like the ENGINE lint findings: their behavior
 * is owned on minimal SDL by {@code TableOnInputDeprecationWarningTest}, and their presence on
 * the real example is pinned by {@link #tableOnInputDeprecationsFireForEveryTableInput}. An
 * exact count is deliberately not pinned; it would churn with every {@code @table} input a
 * fixture author adds or removes, for a signal already exhaustively covered on minimal SDL.
 */
@PipelineTier
class FixtureWarningsGateTest {

    /**
     * The {@code @table}-on-input deprecation advisory signature. Untyped ({@link BuildWarning.NoRule}),
     * so matched by its stable message shape rather than a rule tag; see {@code TableOnInputDeprecationWarningTest}.
     */
    private static boolean isTableOnInputDeprecation(BuildWarning w) {
        return w instanceof BuildWarning.NoRule
            && w.message().contains("`@table` on input type '")
            && w.message().contains("is deprecated and will be removed in a future release");
    }

    private static final Path FIXTURE_SCHEMA =
        Path.of("src/main/resources/graphql/schema.graphqls").toAbsolutePath();

    private static final String OUTPUT_PACKAGE = "no.sikt.graphitron.generated";
    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    private static List<BuildWarning> buildAllWarnings() {
        var ctx = new RewriteContext(
            List.of(new SchemaInput(FIXTURE_SCHEMA.toString(), Optional.empty(), Optional.empty())),
            FIXTURE_SCHEMA.getParent(),
            FIXTURE_SCHEMA.getParent(),
            OUTPUT_PACKAGE,
            JOOQ_PACKAGE,
            Map.of()
        );
        return new GraphQLRewriteGenerator(ctx).buildOutput().report().warnings();
    }

    @Test
    void exampleSchemaEmitsExactlyTheExpectedWarningSet() {
        List<BuildWarning> allWarnings = buildAllWarnings();

        // SDL lint findings (the engine's syntactic visitors) ride this same warning channel
        // and are exercised by LintEngineTest. This gate pins the classifier/generator advisory
        // set specifically, so it filters the engine lint findings out; a CLASSIFIER advisory (the
        // same-table @asConnection one below) is itself a LintFinding but stays in scope here.
        List<BuildWarning> warnings = allWarnings.stream()
            .filter(w -> !(w instanceof BuildWarning.LintFinding lf
                && lf.rule().source() == no.sikt.graphitron.rewrite.lint.LintRule.Source.ENGINE))
            // @table-on-input deprecations: segregated; see the class javadoc and
            // tableOnInputDeprecationsFireForEveryTableInput below.
            .filter(w -> !isTableOnInputDeprecation(w))
            // codegen-config <sessionState> advisories: this gate's RewriteContext has no
            // <sessionState>, so the no-session-state advisory fires; the category is owned by
            // SessionStateWarningsTest, so segregate it like the ENGINE lint findings above.
            .filter(w -> !(w instanceof BuildWarning.LintFinding lf
                && lf.rule().source() == no.sikt.graphitron.rewrite.lint.LintRule.Source.CODEGEN))
            .toList();

        assertThat(warnings)
            .as("the sakila-example fixture must emit exactly one generator warning; "
                + "a new entry means a fixture started tripping an advisory that is not "
                + "declared/asserted (see R294)")
            .hasSize(1);

        BuildWarning warning = warnings.get(0);

        assertThat(warning.message())
            .as("the one expected warning is the @asConnection same-table @nodeId advisory")
            .contains("field 'filmsConnectionByRequiredIds'")
            .contains("@nodeId(typeName: 'Film')")
            .contains("'ids'")
            .contains("every page of @asConnection would equal the input set");

        // The field sits at schema.graphqls line 284; fields added above it shift this line.
        // Update the expected line if the field moves.
        assertThat(warning.location()).isNotNull();
        assertThat(warning.location().getSourceName())
            .as("warning is attributed to the example schema source")
            .endsWith("schema.graphqls");
        assertThat(warning.location().getLine())
            .as("warning is attributed to the filmsConnectionByRequiredIds field definition")
            .isEqualTo(284);
    }

    /**
     * Pins the {@code @table}-on-input deprecation advisories on the broad example (the
     * minimal-SDL behavior lives in {@code TableOnInputDeprecationWarningTest}). The example
     * keeps {@code @table} on its mutation / lookup inputs (removing them is the consumers'
     * migration, out of scope here), so the category must be present and every entry
     * deprecation-shaped and attributed to the schema. No input is carved out: DELETE and INSERT
     * both have field-relative write-target paths, so every author-written {@code @table} input
     * warns, including the projected-return INSERT ({@code FilmCreateInput}) and the encoded-ID
     * INSERT ({@code CreateKeyedNodeInput}). The INSERT-consumed advisory names the
     * return-derived fix and {@code @mutation(table:)}; the DELETE-consumed advisory names
     * {@code @mutation(table:)}.
     */
    @Test
    void tableOnInputDeprecationsFireForEveryTableInput() {
        List<BuildWarning> deprecations = buildAllWarnings().stream()
            .filter(FixtureWarningsGateTest::isTableOnInputDeprecation)
            .toList();

        assertThat(deprecations)
            .as("the example keeps @table on input types, so the deprecation category must fire")
            .isNotEmpty();

        assertThat(deprecations)
            .allSatisfy(w -> {
                assertThat(w.location()).isNotNull();
                assertThat(w.location().getSourceName())
                    .as("each @table-on-input advisory is attributed to the example schema source")
                    .endsWith("schema.graphqls");
            });

        assertThat(deprecations).extracting(BuildWarning::message)
            .as("a projected-return INSERT input (FilmCreateInput -> Film / FilmPayload) warns")
            .anyMatch(m -> m.contains("'FilmCreateInput'"))
            .as("the encoded-ID INSERT input (CreateKeyedNodeInput -> ID) now warns too (carve-out retired)")
            .anyMatch(m -> m.contains("'CreateKeyedNodeInput'"));

        assertThat(deprecations)
            .filteredOn(w -> w.message().contains("'CreateKeyedNodeInput'"))
            .as("the encoded-ID INSERT @table-on-input warns, naming the INSERT field-relative replacement")
            .isNotEmpty()
            .allSatisfy(w -> assertThat(w.message())
                .contains("@mutation(typeName: INSERT)")
                .contains("@mutation(table:"));

        assertThat(deprecations)
            .filteredOn(w -> w.message().contains("'FilmDeleteInput'"))
            .as("a DELETE @table-on-input (FilmDeleteInput) now warns, naming @mutation(table:)")
            .isNotEmpty()
            .allSatisfy(w -> assertThat(w.message()).contains("@mutation(table:"));
    }
}
