package no.sikt.graphitron.generated.fetchers;

import graphql.GraphQLContext;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.schema.ErrorRouter;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionHandleRecord;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.CloseableDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The failure companion {@code @outbox(on: ERROR)} generates for
 * {@code Mutation.insertFilmReportingFailure}, called directly against a real PostgreSQL, as the
 * connection carrier calls it once the field's transaction rolled back: on a connection with the
 * session handle mounted on it, with the error the field's {@code @error} channel claimed the failure
 * as, or {@code null}. Rows are found by a title only the case wrote, and removed afterwards.
 *
 * <p>Lives in the generated fetchers' package because the companion is package-private.
 */
@UnitTier
class OutboxFailureCompanionTest {

    private static final String EVENT = "Mutation.insertFilmReportingFailure.failed";

    static CloseableDSLContext dsl;

    @BeforeAll
    static void start() {
        dsl = SakilaDatabase.dsl();
    }

    @AfterAll
    static void removeTheMessagesWritten() {
        dsl.execute("delete from outbox where type = ? and payload ->> 'title' like 'failure-companion-%'", EVENT);
        dsl.close();
    }

    /** A connection of its own with the session handle the carrier would have mounted on it. */
    private static CloseableDSLContext mounted(String principal) {
        var mounted = org.jooq.impl.DSL.using(SakilaDatabase.url(), SakilaDatabase.user(), SakilaDatabase.password());
        mounted.configuration().data("no.sikt.graphitron.session.handle", new SessionHandleRecord(principal, 1));
        return mounted;
    }

    private static DataFetchingEnvironment env(Map<String, Object> arguments) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .operationDefinition(OperationDefinition.newOperationDefinition()
                .operation(OperationDefinition.Operation.MUTATION).name("Report").build())
            .arguments(() -> graphql.collect.ImmutableMapWithNullValues.copyOf(arguments))
            .build();
    }

    private static List<Map<String, Object>> messages(String title) {
        return dsl.fetch("select payload -> 'kind' is not null as has_kind, payload -> 'detail' is not null as has_detail,"
                + " payload ->> 'kind' as kind, payload ->> 'detail' as detail, payload ->> 'by' as by,"
                + " payload ->> 'operation' as operation from outbox where type = ? and payload ->> 'title' = ?",
                EVENT, title)
            .intoMaps();
    }

    @Test
    void aClaimedFailure_writesTheTypeAndMessageTheResponseShows_withTheMountedPrincipal() {
        var title = "failure-companion-" + UUID.randomUUID();
        var violation = new SQLException("insert or update on table \"film\" violates foreign key constraint", "23503");

        try (var mounted = mounted("alice")) {
            MutationFetchers.outboxFailedInsertFilmReportingFailure(mounted,
                env(Map.of("in", Map.of("title", title, "languageId", 999999))), violation);
        }

        assertThat(messages(title)).singleElement().satisfies(row -> assertThat(row)
            .as("the foreign key violation is the union member mapped to 23503")
            .containsEntry("kind", "FilmInsertLanguageMissing")
            .as("and its message is the one the member's fetcher reads, the exception's own")
            .containsEntry("detail", violation.getMessage())
            .containsEntry("by", "alice")
            .containsEntry("operation", "Report"));
    }

    @Test
    void anotherMember_isChosenAsTheResolverChoosesIt() {
        var title = "failure-companion-" + UUID.randomUUID();
        var violation = new SQLException("null value in column \"rental_duration\" violates not-null constraint", "23502");

        try (var mounted = mounted("alice")) {
            MutationFetchers.outboxFailedInsertFilmReportingFailure(mounted, env(Map.of("in", Map.of("title", title))),
                violation);
        }

        assertThat(messages(title)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "FilmInsertValueMissing"));
    }

    @Test
    void nothingClaimed_leavesEveryErrorKeyOut() {
        var title = "failure-companion-" + UUID.randomUUID();

        try (var mounted = mounted("bob")) {
            MutationFetchers.outboxFailedInsertFilmReportingFailure(mounted, env(Map.of("in", Map.of("title", title))),
                null);
        }

        assertThat(messages(title)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("has_kind", false)
            .containsEntry("has_detail", false)
            .containsEntry("by", "bob"));
    }

    @Test
    void aMessageTheOutboxRefuses_surfacesAsTheOutboxException_andLeavesNoRow() {
        // The outbox table refuses a message whose title is this marker, as it is written.
        var title = "outbox-failure-refused-on-write";

        try (var mounted = mounted("alice")) {
            assertThatThrownBy(() -> MutationFetchers.outboxFailedInsertFilmReportingFailure(mounted,
                    env(Map.of("in", Map.of("title", title))), null))
                .isInstanceOf(ErrorRouter.OutboxEmitException.class);
        }
        assertThat(messages(title)).isEmpty();
    }

    @Test
    void withoutAMountedSession_nothingIsWritten() {
        var title = "failure-companion-" + UUID.randomUUID();

        assertThatThrownBy(() -> MutationFetchers.outboxFailedInsertFilmReportingFailure(dsl,
                env(Map.of("in", Map.of("title", title))), null))
            .as("a $session key needs the handle the carrier mounted")
            .isInstanceOf(ErrorRouter.OutboxEmitException.class);
        assertThat(messages(title)).isEmpty();
    }
}
