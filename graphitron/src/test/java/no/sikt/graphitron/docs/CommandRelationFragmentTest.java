package no.sikt.graphitron.docs;

import no.sikt.graphitron.rewrite.GuardScope;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the command-relation fragment: the universe it renders, the prose it lifts, and the floors
 * that make an empty or blank render a build failure rather than a plausible-looking table.
 *
 * <p>The fragment itself is never committed, so it has no verify diff to drift against. These are
 * the assertions that stand in for one: that the scan still finds every relation, that a renamed
 * relation appears rather than vanishing, and that the geography the table teaches is the real one.
 */
@UnitTier
class CommandRelationFragmentTest {

    private static Path sourceRoot() {
        return GuardScope.locateRepoRoot().resolve("graphitron/src/main/java");
    }

    @Test
    void everyCommandRelationOnTheClasspathIsRendered() throws IOException {
        List<CommandRelationFragment.Row> rows = CommandRelationFragment.rows(sourceRoot());

        assertThat(rows).extracting(CommandRelationFragment.Row::simpleName)
            .as("the universe comes from the classpath, so an added relation appears here without "
                + "anyone updating a list, and a renamed one fails the render rather than vanishing")
            .contains("ConditionRelation", "FetcherEdgeRelation", "KeyProjectionRelation",
                      "LauncherRelation", "ProjectionRelation", "RoutineWriteRelation",
                      "TypeUnitRelation");
    }

    @Test
    void theTriangleGeographyIsScannedRatherThanAssumed() throws IOException {
        List<CommandRelationFragment.Row> rows = CommandRelationFragment.rows(sourceRoot());

        assertThat(rows)
            .as("KeyProjectionRelation lives in command, not plan. A page teaching the "
                + "command/plan/render triangle must not state that the relations are all in plan, "
                + "so the scan covers both packages and this is what holds it to that.")
            .anySatisfy(row -> {
                assertThat(row.simpleName()).isEqualTo("KeyProjectionRelation");
                assertThat(row.packageName()).isEqualTo("no.sikt.graphitron.command");
            })
            .anySatisfy(row -> {
                assertThat(row.simpleName()).isEqualTo("LauncherRelation");
                assertThat(row.packageName()).isEqualTo("no.sikt.graphitron.plan");
            });
    }

    @Test
    void everyRenderedGrainIsASentenceFromTheRelationsOwnJavadoc() throws IOException {
        assertThat(CommandRelationFragment.rows(sourceRoot()))
            .allSatisfy(row -> assertThat(row.grain())
                .as("%s renders the first sentence of its own javadoc", row.simpleName())
                .isNotBlank()
                .doesNotContain("{@")
                .doesNotContain("<p>"));
    }

    @Test
    void aRelationWithNoJavadocFailsTheRender() {
        assertThat(CommandRelationFragment.grainOf("""
            package p;
            public record SilentRelation(int rows) {}
            """, "SilentRelation"))
            .as("a relation stating no grain renders no cell; rows() turns that into a failure")
            .isEmpty();
    }

    @Test
    void javadocMarkupBecomesAsciiDoc() {
        // The two inline-tag families read differently, and conflating them corrupts the text:
        // @code takes one span that is the content, commas and parentheses included, while @link
        // takes a reference optionally followed by a label.
        assertThat(CommandRelationFragment.plainText("""
             * One row per {@code (typeName, arm)} key, minted by {@link no.sikt.p.Producer}.
            """))
            .isEqualTo("One row per `(typeName, arm)` key, minted by `Producer`.");

        assertThat(CommandRelationFragment.plainText("""
             * The <em>launcher</em> relation, see {@link p.Commands#produce produce}.
            """))
            .isEqualTo("The launcher relation, see `produce`.");
    }

    @Test
    void onlyTheFirstParagraphIsRead() {
        assertThat(CommandRelationFragment.plainText("""
             * The grain sentence. A second sentence.
             *
             * <p>A paragraph of rationale that is not the grain.
            """))
            .as("the grain is the opening statement; the rationale below it belongs on the type")
            .isEqualTo("The grain sentence. A second sentence.");
    }

    @Test
    void aTooSmallUniverseFailsRatherThanRenderingAnAlmostEmptyTable() {
        assertThatThrownBy(() -> CommandRelationFragment.rows(Path.of("/nonexistent/source/root")))
            .as("a scan that stopped reaching the packages must fail loudly; an almost-empty "
                + "table is the plausible-looking wrong answer this fragment exists to prevent")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRenderedFragmentIsAnAsciiDocTable() throws IOException {
        String fragment = CommandRelationFragment.render(CommandRelationFragment.rows(sourceRoot()));

        assertThat(fragment)
            .as("markdown-shaped rows render as paragraph text with literal pipes, and the "
                + "roadmap-tool table check fails the build on one")
            .contains("[cols=")
            .contains("|===")
            .doesNotContain("|---");
        assertThat(fragment)
            .as("a generated fragment says so, since nobody should edit or commit it")
            .contains("Do not edit and do not commit");
    }

    @Test
    void theRenderIsStableAcrossRuns() throws IOException {
        assertThat(CommandRelationFragment.render(CommandRelationFragment.rows(sourceRoot())))
            .as("the fragment is regenerated on every build; an unstable order would churn the "
                + "rendered site for no reason")
            .isEqualTo(CommandRelationFragment.render(CommandRelationFragment.rows(sourceRoot())));
    }
}
