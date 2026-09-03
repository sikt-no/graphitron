package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_SLOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the coordinate a real capture writes for a condition site meets the coordinate the same
 * capture writes for the document's slots.
 *
 * <p>What the relation returns given rows is pinned in
 * {@code no.sikt.graphitron.model.intent.ConditionSlotTest}. What that tier cannot say is whether
 * the two readings line up: the site's type and field come from the directive's own capture, the
 * slots from the SDL walk, and an arm whose join disagreed on a spelling would answer with no rows
 * rather than with wrong ones.
 */
@PipelineTier
class ConditionSlotCaptureTest {

    private static final String CONDITION =
        "@condition(condition: {className: \"no.sikt.graphitron.rewrite.TestConditionRoutes\","
        + " method: \"filmByRating\"})";

    private static final String SDL = """
        type Query {
          films(rating: String, title: String): [Film!]! %s
          byRating(rating: String %s): [Film!]!
          byInput(filter: FilmFilter): [Film!]!
        }

        input FilmFilter {
          rating: String %s
          title: String
        }

        type Film @table(name: "film") {
          id: ID!
        }
        """.formatted(CONDITION, CONDITION, CONDITION);

    @Test
    @DisplayName("each spelling's slots come out of a real capture at the coordinate it names")
    void everyArmMeetsTheDocumentOverARealCapture(@TempDir Path tmp) {
        withCatalogStore(tmp, dsl ->
            assertThat(slots(dsl))
                .as("the field condition sees both of its field's arguments, the argument"
                    + " condition only its own, and the input-field condition only itself")
                .containsExactly(
                    "ARGUMENT_CONDITION Query.byRating(rating) rating ARGUMENT",
                    "FIELD_CONDITION Query.films rating ARGUMENT",
                    "FIELD_CONDITION Query.films title ARGUMENT",
                    "INPUT_FIELD_CONDITION FilmFilter.rating rating INPUT_FIELD"));
    }

    /** Every slot the capture put in scope, rendered as its key plus the kind. */
    private static List<String> slots(DSLContext dsl) {
        var t = INTENT_CONDITION_SLOT;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(t.SITE, t.USE_SITE, t.SLOT_NAME)
            .fetch(row -> row.get(t.SITE) + " " + row.get(t.USE_SITE) + " "
                + row.get(t.SLOT_NAME) + " " + row.get(t.SLOT_KIND));
    }

    private static void withCatalogStore(Path tmp, Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var captured = CapturedStore.ofCatalog(tmp, CapturedStore.GRAPH, SDL, jooq, List.of())) {
            body.accept(captured.dsl());
        }
    }
}
