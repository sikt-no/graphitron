package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.model.config.OutboxConfig;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage for {@code @outbox}: the companion method each write's messages render into,
 * built from the store's message and leaf rows, and the call that places it inside the write's
 * transaction at every DML site.
 */
@PipelineTier
class OutboxPipelineTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
        input FilmInput { title: String! @field(name: "title") }
        input FilmKey { filmId: Int! @field(name: "film_id") }
        type Query { dummy: String }
        type Mutation {
            createFilm(in: FilmInput!): Film
                @mutation(typeName: INSERT)
                @outbox(event: "FilmCreated", argMapping: "filmId: $result.id, title: in.title")
            createFilms(in: [FilmInput!]!): [Film!]!
                @mutation(typeName: INSERT)
                @outbox(event: "FilmsCreated", argMapping: "ids: $result.id")
            deleteFilm(in: FilmKey!): ID
                @mutation(typeName: DELETE, table: "film")
                @outbox(event: "FilmDeleted", argMapping: "id: $result, by: $scope.alias")
            deleteFilms(in: [FilmKey!]!): [ID!]!
                @mutation(typeName: DELETE, table: "film")
                @outbox(event: "FilmsDeleted", argMapping: "ids: $result")
        }
        """;

    private static String method(java.util.List<com.palantir.javapoet.TypeSpec> types, String name) {
        return types.stream()
            .flatMap(t -> t.methodSpecs().stream())
            .filter(m -> name.equals(m.name()))
            .findFirst()
            .map(MethodSpec::toString)
            .orElseThrow(() -> new AssertionError("no method " + name));
    }

    private java.util.List<com.palantir.javapoet.TypeSpec> fetchers() {
        var ctx = TestConfiguration.testContext().withOutboxConfig(OutboxConfig.from(
            "outbox", null, Map.of(), Map.of(), "operation: $scope.operationName"));
        return TestSchemaHelper.storeBackedFetchers(tmp, SDL, ctx);
    }

    @Test
    void companionMethodInsertsOneRowPerMessageFromTheWrittenRows() {
        var body = method(fetchers(), "outboxCreateFilm");
        assertThat(body)
            .contains("if (written == null) return;")
            .contains("Tables.OUTBOX.TYPE, ")
            .contains("DSL.val(\"FilmCreated\")")
            .contains("DSL.jsonbObject(m0)")
            .as("the common payload entry leads the message's own")
            .containsSubsequence("DSL.key(\"operation\")", "DSL.key(\"filmId\")", "DSL.key(\"title\")")
            .as("the node id is encoded in Java from the captured key")
            .contains("NodeIdEncoder.encodeFilm(written.get(t.FILM_ID))")
            .as("a failing statement surfaces as the outbox exception, not a business error")
            .contains("catch (java.lang.RuntimeException e)")
            .contains("ErrorRouter.OutboxEmitException(e)");
    }

    @Test
    void functionTarget_callsTheRoutineOncePerMessageWithTheEvaluatedPayload() {
        var ctx = TestConfiguration.testContext().withOutboxConfig(OutboxConfig.from(
            null, "outbox_emit", Map.of(),
            Map.of("type", "p_type", "aggregatetype", "p_aggregatetype", "aggregateid", "p_aggregateid",
                "payload", "p_payload"),
            "operation: $scope.operationName"));
        var body = method(TestSchemaHelper.storeBackedFetchers(tmp, SDL, ctx), "outboxCreateFilm");

        assertThat(body)
            .as("the payload is evaluated once; this message reads no column of the written row, so the"
                + " select has no from")
            .contains("org.jooq.JSONB body0 = tx.select(org.jooq.impl.DSL.jsonbObject(m0)).fetchOne(0, org.jooq.JSONB.class)")
            .as("one call per message, the arguments at the positions of the parameters their roles landed in")
            .contains("Routines.outboxEmit(tx.configuration(), \"FilmCreated\", \"film\", (java.lang.String) null, body0)")
            .as("a payload that could not be evaluated is passed on to nobody")
            .contains("if (body0 != null)")
            .doesNotContain("insertInto(");
    }

    @Test
    void everyDmlSiteCallsItsCompanionInsideTheWriteTransaction() {
        var types = fetchers();
        for (var field : new String[] {"createFilm", "createFilms", "deleteFilm", "deleteFilms"}) {
            var fetcher = method(types, field);
            assertThat(fetcher)
                .as(field)
                .contains("transactionResult(tx ->")
                .contains("outbox" + Character.toUpperCase(field.charAt(0)) + field.substring(1)
                    + "(org.jooq.impl.DSL.using(tx), env, ");
        }
    }
}
