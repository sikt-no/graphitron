package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.test.internal.CapturedGenerator;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.lint.LintRule;

/**
 * Fixture warnings-as-errors gate: builds the sakila-example schema and asserts the
 * generator's warning channel emits <em>exactly</em> the expected set (three advisories).
 *
 * <p>Fixture builds treat generator warnings as errors unless the fixture's point is to assert
 * the warning path. This test declares which warnings the example fixture intentionally carries:
 * an accidental new warning fails the size assertion, a vanished expected warning fails the
 * content assertion, both over the {@code warnings()} list
 * {@link GraphQLRewriteGenerator#buildOutput()} exposes.
 *
 * <p>The first expected entry is the {@code @asConnection} + required same-table
 * {@code @nodeId} hygiene advisory on {@code Query.filmsConnectionByRequiredIds}. That field is
 * the execution-tier proof (in {@link GraphQLQueryTest#filmsConnectionByRequiredIds_idsSupplied_paginatesBoundedSet})
 * that the production shape ships a working WHERE-pk-IN connection; the shape intrinsically
 * warns, so the warning is pinned here rather than tolerated as log noise. The message format
 * itself is pinned on minimal SDL by {@code AsConnectionSameTableWarnFormatTest}.
 *
 * <p>The second is the {@code @table}-on-input deprecation advisory for {@code CityCountryFilter},
 * the fixture that proves the pass fires end-to-end through the plugin. Unlike the first it is a
 * plain {@link BuildWarning.NoRule} rather than a {@code LintFinding}, so it rides no rule source
 * and cannot be segregated by the filters below; that is why it is asserted here rather than
 * carved out. The message format is pinned on minimal SDL by
 * {@code TableOnInputDeprecationWarningTest}.
 *
 * <p>The third is the {@link LintRule#CONNECTION_ORDER_NOT_UNIQUE} advisory on
 * {@code Query.tilgangerConnection}, a connection over a table-valued function result: the result
 * has no key graphitron could append to make the order identify a row, so whether
 * {@code (organisasjonskode, rollekode)} is unique is the author's word. The fixture is the
 * end-to-end proof that a keyless connection still pages on its listed columns, so the warning is
 * pinned here. The message format is pinned on minimal SDL in {@code GraphitronSchemaBuilderTest}.
 *
 * <p>The same keyless tables also carry ordered lists, each of which raises
 * {@link LintRule#LIST_ORDER_NOT_UNIQUE}. That rule fires once per list, so the gate asserts it as a
 * set (present on the keyless routine list, absent from any keyed table) and does not count it
 * among the three.
 */
@PipelineTier
class FixtureWarningsGateTest {

    private static final Path FIXTURE_SCHEMA =
        Path.of("src/main/resources/graphql/schema.graphqls").toAbsolutePath();

    private static final String OUTPUT_PACKAGE = "no.sikt.graphitron.generated";
    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    private static List<BuildWarning> buildAllWarnings() {
        var ctx = new RunContext(
            List.of(new SchemaInput(SchemaSource.file(FIXTURE_SCHEMA), Optional.empty(), Optional.empty())),
            FIXTURE_SCHEMA.getParent(), "FixtureWarningsGateTest",
            FIXTURE_SCHEMA.getParent(),
            OUTPUT_PACKAGE,
            JOOQ_PACKAGE
        );
        return CapturedGenerator.with(ctx,
            generator -> generator.buildOutput().report().warnings());
    }

    @Test
    void exampleSchemaEmitsExactlyTheExpectedWarningSet() {
        List<BuildWarning> allWarnings = buildAllWarnings();

        // SDL lint findings (the engine's syntactic visitors) ride this same warning channel
        // and are exercised by LintRuleCasesTest. This gate pins the classifier/generator advisory
        // set specifically, so it filters the engine lint findings out; a CLASSIFIER advisory (the
        // same-table @asConnection one below) is itself a LintFinding but stays in scope here.
        List<BuildWarning> warnings = allWarnings.stream()
            .filter(w -> !(w instanceof BuildWarning.LintFinding lf
                && lf.rule().source() == no.sikt.graphitron.model.lint.LintRule.Source.ENGINE))
            // Codegen advisories (whole-build facts folded in at report assembly): this gate's
            // RunContext has no <sessionState>, so the no-session-state advisory fires. The
            // category is owned by SessionStateWarningsTest and DependencyVersionWarningsTest, so
            // segregate it like the ENGINE lint findings above.
            .filter(w -> !(w instanceof BuildWarning.LintFinding lf
                && lf.rule().source() == no.sikt.graphitron.model.lint.LintRule.Source.CODEGEN))
            // The list-order-not-unique advisory fires once per ordered list over a keyless table, and
            // the fixture's keyless views and tables carry several; the count is not what this gate
            // pins, so they are segregated here and asserted as a set below.
            .filter(w -> !(w instanceof BuildWarning.LintFinding lf
                && lf.rule() == LintRule.LIST_ORDER_NOT_UNIQUE))
            .toList();
        List<BuildWarning> listWarnings = allWarnings.stream()
            .filter(w -> w instanceof BuildWarning.LintFinding lf
                && lf.rule() == LintRule.LIST_ORDER_NOT_UNIQUE)
            .toList();

        assertThat(warnings)
            .as("the sakila-example fixture must emit exactly three generator warnings; "
                + "a new entry means a fixture started tripping an advisory that is not "
                + "declared/asserted (see R294)")
            .hasSize(3);

        assertThat(listWarnings)
            .as("the list-order-not-unique advisory on the keyless routine list, and on no keyed list")
            .anySatisfy(warning -> assertThat(warning.message())
                .contains("list 'Query.tilganger'")
                .contains("rows that tie on every listed column come back in an order the database chooses"))
            .allSatisfy(warning -> assertThat(warning.message())
                .containsAnyOf("'tilganger_for_feidebruker_med_fs_fiktivt_fnr'", "'films_for_actor'",
                    "'role_assignment'"));

        assertThat(warnings)
            .as("the @asConnection same-table @nodeId advisory")
            .anySatisfy(warning -> {
                assertThat(warning.message())
                    .contains("field 'filmsConnectionByRequiredIds'")
                    .contains("@nodeId(typeName: 'Film')")
                    .contains("'ids'")
                    .contains("every page of @asConnection would equal the input set");

                // The field sits at schema.graphqls line 781; fields added above it shift this
                // line, and so does prose, a comment above it counting as much as a field.
                // Update the expected line if the field moves.
                assertThat(warning.location()).isNotNull();
                assertThat(warning.location().getSourceName())
                    .as("warning is attributed to the example schema source")
                    .endsWith("schema.graphqls");
                assertThat(warning.location().getLine())
                    .as("warning is attributed to the filmsConnectionByRequiredIds field definition")
                    .isEqualTo(789);
            });

        assertThat(warnings)
            .as("the connection-order-not-unique advisory on the keyless routine connection")
            .anySatisfy(warning -> {
                assertThat(warning)
                    .isInstanceOfSatisfying(BuildWarning.LintFinding.class, lf ->
                        assertThat(lf.rule()).isEqualTo(LintRule.CONNECTION_ORDER_NOT_UNIQUE));
                assertThat(warning.message())
                    .contains("connection 'Query.tilgangerConnection'")
                    .contains("rows that tie on every listed column can be skipped between pages");
                assertThat(warning.location()).isNotNull();
                assertThat(warning.location().getSourceName()).endsWith("schema.graphqls");
            });

        assertThat(warnings)
            .as("the @table-on-input deprecation advisory for the CityCountryFilter fixture")
            .anySatisfy(warning -> {
                assertThat(warning)
                    .as("a plain NoRule advisory, not a lint finding: a deprecation announcement "
                        + "carries no rule and no fix")
                    .isInstanceOf(BuildWarning.NoRule.class);
                assertThat(warning.message())
                    .contains("`@table` on input type 'CityCountryFilter'")
                    .contains("was ignored")
                    .contains("will be rejected in a future release")
                    .as("filter-only wording: no consuming mutation verb to name")
                    .contains("resolve against each consuming field's table");
                assertThat(warning.location()).isNotNull();
                assertThat(warning.location().getSourceName()).endsWith("schema.graphqls");
            });
    }

}
