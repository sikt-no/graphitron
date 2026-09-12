package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.derive.ClassifiedRun;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The port's other capture: the gatherers handed the run's configuration, which find their own
 * sources, against whichever store the port is on.
 *
 * <p>Two claims, and neither is visible from what either capture produces alone. The families land
 * in the store the pass used rather than in one this call opened, which is why the method is on the
 * port. And they survive the next pass, which is what lets a caller capture before its pass instead
 * of after: a build the pass refuses still has what they wrote to say for it, the pass's own clear
 * and rewrite being one transaction that a refusal rolls back whole.
 *
 * <p>The second claim used to be about the clear disclaiming what these gatherers own, and it is
 * not any more. The disclaim went when the document gatherer started running inside the pass: while
 * it ran only outside, the clear was emptying the {@code graphql_} anchors from under exempt rows
 * that key into them, so the exemption was not protecting those rows, it was breaking them. What
 * holds the claim up now is that the pass writes them.
 */
class ModelCapturePortTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film @table(name: "film") { title: String }
        """;

    /**
     * One store and three steps, which is a dev session's cadence: a pass, the model capture beside
     * it, and the next pass.
     */
    @Test
    @DisplayName("a model capture lands beside the pass, and outlives the next one")
    void itLandsBesideThePassAndOutlivesTheNextOne(@TempDir Path tmp) {
        try (var lent = FactStores.fileBacked(tmp.resolve("home"));
             var port = CapturePort.over(lent)) {
            port.capture(request(tmp));
            port.captureModel(CapturedStore.graph(tmp), configOver(tmp), List.of(), null);

            assertThat(lent.dsl().select(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME)
                    .from(GRAPHQL_AST_TYPE_DECLARATION_ENTRY)
                    .fetch(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME))
                .as("the entries are in the lender's store, which is where the pass wrote too")
                .contains("Film");
            assertThat(lent.dsl().select(GRAPHITRON_AST_TABLE_ENTRY.TABLE_REF)
                    .from(GRAPHITRON_AST_TABLE_ENTRY)
                    .fetch(GRAPHITRON_AST_TABLE_ENTRY.TABLE_REF))
                .as("and so is what the directive application decoded to")
                .containsExactly("film");
            assertThat(lent.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                    .fetch(GRAPHQL_TYPE.TYPE_NAME))
                .as("beside the pass's own families, neither having displaced the other")
                .contains("Film");

            port.capture(request(tmp));

            assertThat(lent.dsl().select(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME)
                    .from(GRAPHQL_AST_TYPE_DECLARATION_ENTRY)
                    .fetch(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME))
                .as("and the next pass still has them, now because it writes them itself rather "
                    + "than because its clear stepped around them")
                .contains("Film");
        }
    }

    /**
     * One pass's whole capture under {@link CapturedStore#GRAPH}, from {@code baseDir}.
     *
     * <p>Carrying the same corpus the model capture is given, which used to be
     * {@code SubjectConfig.none()}: a merged registry was the whole of what the pass read, so where
     * the documents were did not come into it. It does now. The pass runs the document gatherer,
     * whose rows are keyed by a position in one file and which therefore re-reads the corpus from
     * configuration, and a pass handed a registry with no corpus beside it would clear that
     * gatherer's relations and have nothing to write back into them.
     */
    private static CaptureRequest request(Path baseDir) {
        var registry = CapturedStore.registryOf(baseDir, SDL);
        return CaptureRequest.unseeded(CapturedStore.graph(baseDir), configOver(baseDir), registry,
            SchemaAssembly.of(registry), SdlVerdicts.none(), CapturedStore.attributionOf(baseDir),
            null, List.of(), ClassifiedRun.absent());
    }

    /** A recipe over the file {@link CapturedStore#registryOf} wrote, which is what the gatherers find. */
    private static SubjectConfig configOver(Path baseDir) {
        return SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }
}
