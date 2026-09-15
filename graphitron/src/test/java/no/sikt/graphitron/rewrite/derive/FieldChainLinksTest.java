package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chain between a field's two endpoints, one link per written element in written order.
 *
 * <p>Captured for real rather than seeded: the order this relation states lives in the entry
 * stratum, which a capture writes and a fixture cannot seed a coherent version of by hand.
 *
 * <p>The claim worth testing is the one no other relation can make. {@code @routine} and
 * {@code @reference} each number their own applications from zero, so neither ordinal says which was
 * written first; the fixture below writes one of each on one field and the positions have to come
 * out in the order they were typed.
 *
 * <p>Keyed at the coordinate and not at a target, so a field draws its links once however many
 * tables its rows may come from: what the chain is was written, and where each link lands is
 * resolved per target elsewhere.
 */
@PipelineTier
class FieldChainLinksTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Film @table(name: "film") { title: String }
        type Language @table(name: "language") { name: String }
        type Query {
          films: [Film!]!
          hopped(actorId: Int!, minLength: Int!): [Film!]!
            @routine(name: "films_for_actor",
                     argMapping: "pActorId: actorId, pMinLength: minLength")
            @reference(path: [{table: "film"}])
            @defaultOrder(primaryKey: true)
          twoSteps: [Language!]!
            @reference(path: [{table: "film"}, {key: "film_language_id_fkey"}])
        }
        """;

    /**
     * The case the ordinals cannot answer. A routine and a reference on one field are each the first
     * application of their own directive, so both carry ordinal 0; only the written position tells
     * them apart, and the chain is numbered from it.
     */
    @Test
    @DisplayName("a routine and a reference on one field are two links in written order")
    void twoDirectivesAreOrderedAsWritten() {
        withCaptured(dsl -> assertThat(links(dsl, "hopped"))
            .as("the routine was written first, so it is link 0 whatever either ordinal says")
            .containsExactly("0", "1"));
    }

    /**
     * Several elements of one application, ordered among themselves by their authored index inside
     * the {@code path:} list.
     */
    @Test
    @DisplayName("the elements of one path are links in the order written inside it")
    void oneApplicationsElementsAreOrdered() {
        withCaptured(dsl -> assertThat(links(dsl, "twoSteps"))
            .containsExactly("0", "1"));
    }

    @Test
    @DisplayName("a field with no chain has no links")
    void aFieldWithoutAChainHasNoLinks() {
        withCaptured(dsl -> {
            assertThat(links(dsl, "hopped"))
                .as("an empty relation would satisfy the absence below without meaning it")
                .isNotEmpty();
            assertThat(links(dsl, "films")).isEmpty();
        });
    }

    /**
     * The links name the elements they are, which is what a payload relation joins on. Stated as the
     * element being a position in the file rather than as any fact about what the link does: what a
     * link does is its own relation's to say.
     */
    @Test
    @DisplayName("every link names the written element it is")
    void everyLinkNamesItsElement() {
        withCaptured(dsl -> {
            var t = GRAPHITRON_FIELD_CHAIN_LINK;
            assertThat(dsl.select(t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN)
                    .from(t).where(t.GRAPH_NAME.eq(CapturedStore.GRAPH)).fetch())
                .isNotEmpty()
                .allSatisfy(row -> {
                    assertThat(row.value1()).isNotBlank();
                    assertThat(row.value2()).isPositive();
                    assertThat(row.value3()).isPositive();
                });
        });
    }

    private static List<String> links(DSLContext dsl, String fieldName) {
        var t = GRAPHITRON_FIELD_CHAIN_LINK;
        return dsl.select(t.POSITION)
            .from(t)
            .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(t.FIELD_NAME.eq(fieldName))
            .orderBy(t.POSITION)
            .fetch(r -> String.valueOf(r.value1()));
    }

    private void withCaptured(Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            body.accept(store.dsl());
        }
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
