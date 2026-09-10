package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.derive.ClassifiedRun;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SdlVerdicts;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
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
 * <p>Two claims. The families land in the store the pass used rather than in one this call opened,
 * which is why the method is on the port. And they last only until the next pass, which clears this
 * graph's rows from every relation carrying a graph column, chosen by shape rather than by a list,
 * so it owns relations it has never heard of. Neither is visible from what either capture produces
 * alone, and the second is why every caller captures after its pass rather than before.
 */
class ModelCapturePortTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film @table(name: "film") { title: String }
        """;

    /**
     * One store and three steps, which is a dev session's cadence: a pass, the model capture after
     * it, and the next pass. Both claims are in that sequence, and neither is visible from what
     * either capture produces alone.
     */
    @Test
    @DisplayName("a model capture lands beside the pass, and the next pass clears it")
    void itLandsBesideThePassAndTheNextPassClearsIt(@TempDir Path tmp) {
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

            assertThat(lent.dsl().fetchCount(GRAPHQL_AST_TYPE_DECLARATION_ENTRY))
                .as("and the next pass takes them, clearing this graph from every relation "
                    + "carrying one, which is why every caller captures after its pass rather "
                    + "than before")
                .isZero();
        }
    }

    /** One pass's whole capture under {@link CapturedStore#GRAPH}, from {@code baseDir}. */
    private static CaptureRequest request(Path baseDir) {
        var registry = CapturedStore.registryOf(baseDir, SDL);
        return CaptureRequest.unseeded(CapturedStore.graph(baseDir), SubjectConfig.none(), registry,
            SchemaAssembly.of(registry), SdlVerdicts.none(), CapturedStore.attributionOf(baseDir),
            null, List.of(), ClassifiedRun.absent());
    }

    /** A recipe over the file {@link CapturedStore#registryOf} wrote, which is what the gatherers find. */
    private static SubjectConfig configOver(Path baseDir) {
        return SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }
}
