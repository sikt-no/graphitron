package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_OUTCOME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The root producers {@code graphitron_producer_outcome} says catch into an outcome, against the
 * walk's own account: a root field whose classified channel is {@link ErrorChannel.Mapped}, and an
 * errors field on the {@link ChildField.Transport.WrapperArm} transport, which reads what that field
 * caught.
 *
 * <p>One schema is classified by the walk and captured into a store, so the two answers are about
 * the same written fields. It holds a root service under each root operation, a root write with a
 * payload, and a payload no root producer returns, whose errors field the walk reads off the value
 * and the store states no outcome for. What the outcome rule makes of each shape it refuses is the
 * fact document {@code producer-outcome.graphqls}.
 */
@PipelineTier
class OutcomeAgreementTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
            path: [String!]!
            message: String!
        }
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union SakError = ValidationErr | DbErr
        type SakPayload {
            data: String
            errors: [SakError]
        }
        type SimpleErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
            path: [String!]!
            message: String!
        }
        union CarrierError = SimpleErr
        type Film @table(name: "film") { title: String }
        input FilmInput { title: String }
        type FilmPayload { films: [Film!] errors: [CarrierError!] }
        type Unreturned { note: String errors: [CarrierError] }
        type Query {
            sak: SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSak"})
        }
        type Mutation {
            behandleSak: SakPayload
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSak"})
            createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT, table: "film")
        }
        """;

    @Test
    void theStoreCatchesWhereTheWalkDoes() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var walkProducers = schema.fields().values().stream()
            .filter(f -> f instanceof WithErrorChannel w
                && w.errorChannel().filter(ErrorChannel.Mapped.class::isInstance).isPresent())
            .map(f -> f.qualifiedName())
            .toList();
        var walkOutcomes = schema.fields().values().stream()
            .filter(f -> f instanceof ChildField.ErrorsField ef
                && ef.transport() instanceof ChildField.Transport.WrapperArm)
            .map(f -> f.qualifiedName())
            .toList();
        assertThat(walkProducers)
            .as("the fixture gives the walk an outcome at each root producer, so the comparison is not vacuous")
            .containsExactlyInAnyOrder("Query.sak", "Mutation.behandleSak", "Mutation.createFilm");

        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL, jooq(),
                ClasspathCorpus.entries())) {
            var outcome = GRAPHITRON_PRODUCER_OUTCOME;
            var rows = store.dsl()
                .select(outcome.TYPE_NAME, outcome.FIELD_NAME, outcome.OUTCOME_TYPE_NAME,
                    outcome.ERRORS_FIELD_NAME)
                .from(outcome)
                .where(outcome.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .fetch();
            assertThat(rows.map(r -> r.value1() + "." + r.value2()))
                .containsExactlyInAnyOrderElementsOf(walkProducers);
            assertThat(rows.map(r -> r.value3() + "." + r.value4()).stream().distinct().toList())
                .containsExactlyInAnyOrderElementsOf(walkOutcomes);
        }
    }

    private static CatalogInput jooq() {
        return CatalogInput.of(testContext());
    }
}
