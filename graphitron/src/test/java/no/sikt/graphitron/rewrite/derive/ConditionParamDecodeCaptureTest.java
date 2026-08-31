package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_PARAM_DECODE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a real capture populates {@code intent_condition_param_decode}: the exemption a
 * {@code @condition} parameter bound to a {@code @nodeId} slot takes from the declared-type
 * extraction rule, and the shape of the key it receives instead.
 *
 * <p>What the relation returns given rows is pinned where the SQL is declared, in
 * {@code no.sikt.graphitron.model.intent.ConditionParamDecodeTest}, against a store seeded row by
 * row. That tier cannot catch this one's subject, which is whether the two captures a real run
 * performs actually meet: the {@code @nodeId} instruction and the {@code @condition} application
 * are captured from different directives at coordinates that have to line up exactly, and the node
 * type has to resolve key columns out of the live catalog. Any of those missing and the join finds
 * nothing, which reads identically to a slot that carries no exemption.
 */
@PipelineTier
class ConditionParamDecodeCaptureTest {

    private static final String COND = "no.sikt.graphitron.rewrite.TestConditionStub";

    /**
     * All three ways a slot is named, in one schema, so a capture that reaches one coordinate and
     * not another fails rather than passing on the half it found: the argument-site directive is
     * captured at a three-part coordinate, the input-field one at the shared field coordinate, and
     * the field-level one at that same shared coordinate under an object type, where the slot it
     * exempts is an argument one level below the directive.
     */
    private static final String SDL = """
        type Language implements Node @table(name: "language") @node {
          id: ID! @nodeId
          name: String
        }

        input FilmFilter {
          languageId: ID @nodeId(typeName: "Language")
            @condition(condition: {
              className: "no.sikt.graphitron.rewrite.TestConditionStub",
              method: "languageIdDecodedKeyCondition"
            }, override: true)
        }

        type Film @table(name: "film") { title: String }

        type Query {
          filmsByFilter(filter: FilmFilter): [Film!]!
          filmsByArgument(
            languageId: ID @nodeId(typeName: "Language")
              @condition(condition: {
                className: "no.sikt.graphitron.rewrite.TestConditionStub",
                method: "languageIdDecodedKeyCondition"
              }, override: true)
          ): [Film!]!
          filmsByFieldCondition(
            languageId: ID @nodeId(typeName: "Language")
          ): [Film!]!
            @condition(condition: {
              className: "no.sikt.graphitron.rewrite.TestConditionStub",
              method: "languageIdDecodedKeyCondition"
            }, override: true)
        }
        """;

    @Test
    @DisplayName("every directive site lands an exemption row carrying the node type's key shape")
    void everyDirectiveSiteLandsAnExemptionRowOverARealCapture(@TempDir Path tmp) {
        withCatalogStore(tmp, dsl ->
            assertThat(decodes(dsl)).containsExactlyInAnyOrder(
                "ARGUMENT Query.filmsByArgument(languageId) Language 1 false",
                "ARGUMENT Query.filmsByFieldCondition(languageId) Language 1 false",
                "INPUT_FIELD Query.filmsByFilter(filter)/languageId Language 1 false"));
    }

    /** Each row as "site useSite nodeType arity listValued". */
    private static List<String> decodes(DSLContext dsl) {
        var d = INTENT_CONDITION_PARAM_DECODE;
        return dsl.select(d.fields())
            .from(d)
            .where(d.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(d.CLASS_NAME.eq(COND))
            .fetch(row -> row.get(d.SITE) + " " + row.get(d.USE_SITE) + " "
                + row.get(d.NODE_TYPE_NAME) + " " + row.get(d.KEY_ARITY) + " "
                + row.get(d.LIST_VALUED));
    }

    private static void withCatalogStore(Path tmp, Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var captured = CapturedStore.ofCatalog(tmp, CapturedStore.GRAPH, SDL, jooq)) {
            body.accept(captured.dsl());
        }
    }
}
