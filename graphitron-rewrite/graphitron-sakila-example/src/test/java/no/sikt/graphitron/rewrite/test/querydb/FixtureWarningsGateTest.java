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
 * R294 fixture warnings-as-errors gate: builds the sakila-example schema and asserts the
 * generator's warning channel emits <em>exactly</em> the expected set — one advisory today.
 *
 * <p>The policy R294 establishes is that fixture builds treat generator warnings as errors
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
 * that R113's production shape ships a working WHERE-pk-IN connection; the shape intrinsically
 * warns, so the warning is pinned here rather than tolerated as log noise. The message format
 * itself is pinned on minimal SDL by {@code AsConnectionSameTableWarnFormatTest}; this gate
 * pins that the broad example emits this one and only this one.
 *
 * <p>The 11 redundant {@code @record} directives and the redundant {@code @splitQuery} the
 * example used to carry were removed in R294 (their warning paths are owned by
 * {@code R96RecordBindingPipelineTest} and
 * {@code SingleRecordTableFieldServiceProducerPipelineTest} on minimal SDL).
 */
@PipelineTier
class FixtureWarningsGateTest {

    private static final Path FIXTURE_SCHEMA =
        Path.of("src/main/resources/graphql/schema.graphqls").toAbsolutePath();

    private static final String OUTPUT_PACKAGE = "no.sikt.graphitron.generated";
    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    @Test
    void exampleSchemaEmitsExactlyTheExpectedWarningSet() {
        var ctx = new RewriteContext(
            List.of(new SchemaInput(FIXTURE_SCHEMA.toString(), Optional.empty(), Optional.empty())),
            FIXTURE_SCHEMA.getParent(),
            FIXTURE_SCHEMA.getParent(),
            OUTPUT_PACKAGE,
            JOOQ_PACKAGE,
            Map.of()
        );

        List<BuildWarning> warnings = new GraphQLRewriteGenerator(ctx).buildOutput().report().warnings();

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
        // sits near the top of schema.graphqls (line 187); fields added above it shift this line.
        // Update it if the field moves.
        assertThat(warning.location()).isNotNull();
        assertThat(warning.location().getSourceName())
            .as("warning is attributed to the example schema source")
            .endsWith("schema.graphqls");
        assertThat(warning.location().getLine())
            .as("warning is attributed to the filmsConnectionByRequiredIds field definition")
            .isEqualTo(187);
    }
}
