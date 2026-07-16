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
 * generator's warning channel emits <em>exactly</em> the expected set — one advisory today.
 *
 * <p>The policy this gate enforces is that fixture builds treat generator warnings as errors
 * unless the fixture's point is to assert the warning path. This test is the canonical
 * declaration of which warnings the example fixture intentionally carries and the regression
 * gate for new ones: an accidental new warning (any unexpected entry) fails the size
 * assertion, and a vanished expected warning fails the content assertion. Symmetric drift
 * protection over the same {@code warnings()} list the generator already exposes via
 * {@link GraphQLRewriteGenerator#buildOutput()} — no Mojo flag, no typed warning-kind allowlist.
 *
 * <p>The single surviving entry is the {@code @asConnection} + required same-table
 * {@code @nodeId} hygiene advisory on {@code Query.filmsConnectionByRequiredIds}. That field is
 * the execution-tier proof (in {@link GraphQLQueryTest#filmsConnectionByRequiredIds_idsSupplied_paginatesBoundedSet})
 * that the production shape ships a working WHERE-pk-IN connection; the shape intrinsically
 * warns, so the warning is pinned here rather than tolerated as log noise. The message format
 * itself is pinned on minimal SDL by {@code AsConnectionSameTableWarnFormatTest}; this gate
 * pins that the broad example emits this one and only this one.
 *
 * <p>The 11 redundant {@code @record} directives and the redundant {@code @splitQuery} the
 * example used to carry have been removed (their warning paths are owned by
 * {@code R96RecordBindingPipelineTest} and
 * {@code SingleRecordTableFieldServiceProducerPipelineTest} on minimal SDL).
 *
 * <p>A per-usage {@code @table}-on-input deprecation advisory ({@link BuildWarning.NoRule}) is also
 * emitted. The example is a broad fixture that legitimately keeps {@code @table} on its mutation / lookup
 * input types (removing them is the consumer-derived migration, out of scope here), so it emits one
 * such advisory per {@code @table}-on-input type except the encoded-ID / scalar-return INSERT/UPSERT
 * carve-out. That advisory's per-usage behavior and its carve-out are owned and asserted on minimal
 * SDL by {@code TableOnInputDeprecationWarningTest}; this gate therefore segregates that category
 * (exactly as it segregates the ENGINE lint findings above) so the "exactly one classifier/generator
 * advisory" assertion stays precise, while still pinning the category's presence and its load-bearing
 * carve-out on the real example ({@link #tableOnInputDeprecationsCarveOutEncodedIdInsert}). Pinning an
 * exact count of these is deliberately avoided: it would churn with every {@code @table} input a
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
            // the @table-on-input deprecation advisories are a category owned and asserted on
            // minimal SDL by TableOnInputDeprecationWarningTest, segregated here just like the ENGINE
            // lint findings above. Their presence and carve-out on the real example are pinned by
            // tableOnInputDeprecationsCarveOutEncodedIdInsert below.
            .filter(w -> !isTableOnInputDeprecation(w))
            // the codegen-config <sessionState> advisories are derived from the Mojo config (this
            // gate constructs its own RewriteContext with no <sessionState>, so the no-session-state
            // advisory fires) and are owned and asserted directly on SessionStateConfig by
            // SessionStateWarningsTest, so segregate them here like the ENGINE lint findings above.
            .filter(w -> !(w instanceof BuildWarning.LintFinding lf
                && lf.rule().source() == no.sikt.graphitron.rewrite.lint.LintRule.Source.CODEGEN))
            .toList();

        // Exactly one expected advisory: a new accidental warning grows this list and fails here.
        assertThat(warnings)
            .as("the sakila-example fixture must emit exactly one generator warning; "
                + "a new entry means a fixture started tripping an advisory that is not "
                + "declared/asserted (see R294)")
            .hasSize(1);

        BuildWarning warning = warnings.get(0);

        // Content: the expected entry is the @asConnection same-table hygiene advisory on
        // filmsConnectionByRequiredIds. A reworded or vanished advisory fails these substrings.
        assertThat(warning.message())
            .as("the one expected warning is the @asConnection same-table @nodeId advisory")
            .contains("field 'filmsConnectionByRequiredIds'")
            .contains("@nodeId(typeName: 'Film')")
            .contains("'ids'")
            .contains("every page of @asConnection would equal the input set");

        // Coordinate: attributed to the field's declaration in the example schema. The field
        // sits near the top of schema.graphqls (line 274); fields added above it shift this line.
        // Update it if the field moves.
        assertThat(warning.location()).isNotNull();
        assertThat(warning.location().getSourceName())
            .as("warning is attributed to the example schema source")
            .endsWith("schema.graphqls");
        assertThat(warning.location().getLine())
            .as("warning is attributed to the filmsConnectionByRequiredIds field definition")
            .isEqualTo(274);
    }

    /**
     * Pins the {@code @table}-on-input deprecation advisory on the broad example (the minimal-SDL
     * behavior lives in {@code TableOnInputDeprecationWarningTest}). The example keeps {@code @table} on
     * many mutation / lookup inputs, so the category must be present and every entry must be
     * deprecation-shaped and attributed to the schema. The load-bearing assertion is the carve-out: the
     * one encoded-ID INSERT the fixture declares ({@code createKeyedNode(in: CreateKeyedNodeInput!): ID},
     * whose {@code KeyedNode @node} return carries no {@code @table} for a field-relative derivation to
     * collapse to) must <em>not</em> warn, while a projected consumer like {@code FilmCreateInput} must.
     * The DELETE cutover made DELETE-consumed inputs warn again (the field-relative {@code @mutation(table:)}
     * path now exists as the replacement), so {@code FilmDeleteInput} warns and the advisory names
     * {@code @mutation(table:)}. When a future phase makes the INSERT/UPSERT write target field-relative
     * and empties the encoded carve-out, the {@code CreateKeyedNodeInput} exclusion here flips, which is
     * the intended signal.
     */
    @Test
    void tableOnInputDeprecationsCarveOutEncodedIdInsert() {
        List<BuildWarning> deprecations = buildAllWarnings().stream()
            .filter(FixtureWarningsGateTest::isTableOnInputDeprecation)
            .toList();

        assertThat(deprecations)
            .as("the example keeps @table on input types, so the R332 deprecation category must fire")
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
            .as("the encoded-ID INSERT input (CreateKeyedNodeInput -> ID) is carved out and must not warn")
            .noneMatch(m -> m.contains("'CreateKeyedNodeInput'"));

        // DELETE cutover: a DELETE @table-on-input now warns, and the advisory names @mutation(table:)
        // as the replacement (the earlier suppression is retired now that the field-relative path exists).
        assertThat(deprecations)
            .filteredOn(w -> w.message().contains("'FilmDeleteInput'"))
            .as("a DELETE @table-on-input (FilmDeleteInput) now warns, naming @mutation(table:)")
            .isNotEmpty()
            .allSatisfy(w -> assertThat(w.message()).contains("@mutation(table:"));
    }
}
