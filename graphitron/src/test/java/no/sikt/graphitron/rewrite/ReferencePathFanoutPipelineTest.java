package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the fan-out advisory reaches the report at an author's coordinate, and stays away from the
 * three places it has no business being.
 *
 * <p>The model module's suite proves the view answers the pair-coverage question and that the
 * producer decodes it; neither says a warning arrives at a field the author wrote, with the
 * position of the hop that multiplies in it. A rule nobody is shown is a rule nobody is subject to.
 *
 * <p>{@code film -> inventory -> store} is the fixture, and it is a real fan-out rather than a
 * contrived one: {@code inventory} is keyed on its own surrogate {@code inventory_id}, which
 * neither hop binds, so one film holds many copies at one store and the store list repeats it once
 * per copy. {@code film -> film_actor -> actor} beside it is the negative: that table's key is
 * exactly the two columns the hops bind, so each actor arrives once.
 */
@PipelineTier
class ReferencePathFanoutPipelineTest {

    /** The fan-out shape: a junction keyed on something neither hop binds. */
    private static final String FANNING = """
        type Store @table(name: "store") { storeId: Int @field(name: "store_id") }
        type Film @table(name: "film") {
          title: String
          stores: [Store!] @reference(path: [
            {key: "inventory_film_id_fkey"}, {key: "inventory_store_id_fkey"}])
        }
        type Query { film: Film }
        """;

    /** The same two-hop shape through a table whose key is exactly the pair the hops bind. */
    private static final String COVERED = """
        type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
        type Film @table(name: "film") {
          title: String
          actors: [Actor!] @reference(path: [
            {key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        }
        type Query { film: Film }
        """;

    /**
     * The warning arrives at the field, names the table that multiplies and which element of the
     * path it is, and suggests a relation with set semantics rather than a flag. Emitted SQL is
     * untouched: this rule says what the declared path does, it does not change it.
     */
    @Test
    void theFanningPathWarnsAtTheFieldNamingTheHopThatMultiplies(@TempDir Path tmp) throws IOException {
        var findings = fanoutFindings(report(tmp, FANNING, LintConfig.empty()));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().message())
            .contains("Film.stores")
            .contains("public.inventory")
            .contains("at element 0");
        assertThat(findings.getFirst().fix())
            .as("the remedy travels with the finding, for an editor to offer")
            .isPresent();
        assertThat(findings.getFirst().location())
            .as("the @reference application's own position, so an editor underlines what was"
                + " written")
            .isNotNull();
    }

    /**
     * A pure join table stays quiet, which is the property the whole rule rests on: this is the
     * most canonical {@code @reference} shape there is, and a rule that warned here would be
     * warning about every schema that has one.
     */
    @Test
    void aPureJoinTableStaysQuiet(@TempDir Path tmp) throws IOException {
        assertThat(fanoutFindings(report(tmp, COVERED, LintConfig.empty()))).isEmpty();
    }

    /**
     * The same fanning hops written as a filter produce nothing, and the reason is semantic rather
     * than a gap in the substrate. A filter path lowers to a correlated {@code EXISTS} semi-join,
     * so however many rows the path reaches, each parent row is matched once and there is no
     * multiset to warn about. Warning here would be a false positive by construction.
     */
    @Test
    void aFilterPathOverTheSameHopsStaysQuiet(@TempDir Path tmp) throws IOException {
        assertThat(fanoutFindings(report(tmp, """
            type Film @table(name: "film") { title: String }
            type Query {
              filmsInStore(storeId: Int @field(name: "store_id") @reference(path: [
                {key: "inventory_film_id_fkey"}, {key: "inventory_store_id_fkey"}])): [Film!]!
            }
            """, LintConfig.empty())))
            .isEmpty();
    }

    /**
     * The rule id in {@code disabledRules} removes the finding from the report, which is the list
     * every downstream surface reads: the suppression filter runs last over the combined warnings,
     * and the store copy in {@code lint_finding}, the editor replay and the MCP projection are all
     * written from what survives it, so a finding dropped here reaches none of them.
     */
    @Test
    void theRuleIdSuppressesTheFinding(@TempDir Path tmp) throws IOException {
        assertThat(fanoutFindings(report(tmp, FANNING,
            LintConfig.validated(Set.of("reference-path-fans-out"), List.of()))))
            .isEmpty();
    }

    /**
     * A type the consumer excluded by name is not linted by this rule either, which the engine's
     * own findings have always done and a finding minted outside the walk has not. The asymmetry
     * stays where it was for the classifier advisories, which carry no owning type to match.
     */
    @Test
    void anExcludedTypeIsNotLintedByThisRuleEither(@TempDir Path tmp) throws IOException {
        assertThat(fanoutFindings(report(tmp, FANNING,
            LintConfig.validated(Set.of(), List.of("Fi*m")))))
            .isEmpty();
    }

    /**
     * The guard on where the read sits. The store is a warm cache that survives a run, so a
     * producer reading it from outside the capture window would answer from the previous run's
     * rows: findings at coordinates the edited schema no longer has, and silence at the ones it
     * does. The dev loop, which reruns the pass on every save, is where that bites first.
     *
     * <p>This passes on the day it is written, the assembly point already sitting inside the
     * window with the live handle. Its job is to fail on the day someone moves it back out.
     */
    @Test
    void aSecondRunAgainstOneStoreDescribesTheSecondSchema(@TempDir Path tmp) throws IOException {
        Path store = Files.createDirectories(tmp.resolve("store"));

        assertThat(fanoutFindings(report(tmp, FANNING, LintConfig.empty(), store)))
            .as("the first run's schema fans out")
            .singleElement()
            .satisfies(f -> assertThat(f.message()).contains("Film.stores"));

        assertThat(fanoutFindings(report(tmp, COVERED, LintConfig.empty(), store)))
            .as("the second run reads its own rows, not the rows the first left behind")
            .isEmpty();
    }

    // ===== Helpers =====

    private static List<BuildWarning.LintFinding> fanoutFindings(ValidationReport report) {
        return report.warnings().stream()
            .filter(BuildWarning.LintFinding.class::isInstance)
            .map(BuildWarning.LintFinding.class::cast)
            .filter(f -> f.rule() == LintRule.REFERENCE_PATH_FANS_OUT)
            .toList();
    }

    private static ValidationReport report(Path tmp, String sdl, LintConfig lint) throws IOException {
        return report(tmp, sdl, lint, null);
    }

    private static ValidationReport report(Path tmp, String sdl, LintConfig lint, Path store)
        throws IOException {
        Path schema = Files.createTempFile(tmp, "schema", ".graphqls");
        Files.writeString(schema, sdl);
        var ctx = new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "ReferencePathFanoutPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE
        ).withLintConfig(lint);
        var run = store == null ? ctx : ctx.withStoreDirectory(store);
        try (var opened = GraphitronStore.captured(run)) {
            return new GraphQLRewriteGenerator(run,
                new StoreHandle(opened.dsl(), run.graphName())).buildOutput().report();
        }
    }
}
