package no.sikt.graphitron.rewrite.test.internal;

import graphql.GraphQLContext;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.schema.ErrorRouter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.exception.DataException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage for how the generated {@link ErrorRouter} reads a {@code DATABASE} handler's
 * {@code matches}: against the message of the matched {@link SQLException} and of every exception
 * wrapping it, so a handler can key on the SQL text jOOQ puts in its {@link DataException}. The
 * throwables mirror an Oracle {@code ORA-01438} on an over-long column, whose driver text names no
 * column; only jOOQ's SQL text does.
 */
@UnitTier
class ErrorRouterDatabaseMatchesTest {

    private static final String SQL = "SQL [update \"KJERNEAPI\".\"PERSON\" set \"BANKKONTONR\" = ? where \"ID\" = ?]; ";
    private static final String ORA_ENGLISH =
        "ORA-01438: value 1111111111111111 greater than specified precision (11, 0) for column";
    private static final String ORA_NORWEGIAN =
        "ORA-01438: verdien er større enn presisjonsangivelsen som er tillatt for denne kolonnen";

    private static final ErrorRouter.DatabaseMapping BANKKONTONR =
        new ErrorRouter.DatabaseMapping(null, "1438", "BANKKONTONR", "Bankkontonummer kan ha høyst 11 sifre");
    private static final ErrorRouter.DatabaseMapping PROSENTTALL =
        new ErrorRouter.DatabaseMapping(null, "1438", "PROSENTTALL", "Prosenttall kan ikke overstige 100");

    @Test
    void wrapperCarriesTheText_claimReturnsTheSqlException() {
        var sqlException = oracle(ORA_ENGLISH);
        var thrown = new DataException(SQL + ORA_ENGLISH, sqlException);

        assertThat(ErrorRouter.claim(thrown, new ErrorRouter.Mapping[] {BANKKONTONR}, environment()))
            .isSameAs(sqlException);
    }

    @Test
    void norwegianDriverText_stillMatchesOnTheWrapper() {
        var sqlException = oracle(ORA_NORWEGIAN);
        var thrown = new DataException(SQL + ORA_NORWEGIAN, sqlException);

        assertThat(ErrorRouter.claim(thrown, new ErrorRouter.Mapping[] {BANKKONTONR}, environment()))
            .isSameAs(sqlException);
    }

    @Test
    void twoMappingsOnOneCode_pickByText() {
        ErrorRouter.Mapping[] table = {PROSENTTALL, BANKKONTONR};
        var bank = oracle(ORA_ENGLISH);
        var env = environment();
        assertThat(ErrorRouter.claim(new DataException(SQL + ORA_ENGLISH, bank), table, env)).isSameAs(bank);
        assertThat(ErrorRouter.matchesSource(PROSENTTALL, bank, env.getGraphQlContext())).isFalse();
        assertThat(ErrorRouter.matchesSource(BANKKONTONR, bank, env.getGraphQlContext())).isTrue();

        var percent = oracle(ORA_ENGLISH);
        var percentSql = "SQL [update \"KJERNEAPI\".\"AVTALE\" set \"PROSENTTALL\" = ?]; ";
        assertThat(ErrorRouter.claim(new DataException(percentSql + ORA_ENGLISH, percent), table, env))
            .isSameAs(percent);
        assertThat(ErrorRouter.matchesSource(PROSENTTALL, percent, env.getGraphQlContext())).isTrue();
        assertThat(ErrorRouter.matchesSource(BANKKONTONR, percent, env.getGraphQlContext())).isFalse();
    }

    @Test
    void neitherMessageCarriesTheText_claimReturnsNull_andTheUnclaimedThrowRedacts() {
        var thrown = new DataException("SQL [update \"PERSON\" set \"NAVN\" = ?]; " + ORA_ENGLISH,
            oracle(ORA_ENGLISH));

        assertThat(ErrorRouter.claim(thrown, new ErrorRouter.Mapping[] {BANKKONTONR}, environment())).isNull();
        var result = ErrorRouter.surfaceClientErrorOrRedact(thrown, environment());
        assertThat(result.getData()).isNull();
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> assertThat(error.getMessage()).startsWith("An error occurred. Reference: "));
    }

    @Test
    void textOnlyBelowTheCandidate_doesNotMatch() {
        var sqlException = oracle(ORA_ENGLISH);
        sqlException.initCause(new IllegalStateException("BANKKONTONR"));
        var thrown = new DataException("SQL [update \"PERSON\" set \"NAVN\" = ?]; " + ORA_ENGLISH, sqlException);

        assertThat(ErrorRouter.claim(thrown, new ErrorRouter.Mapping[] {BANKKONTONR}, environment())).isNull();
    }

    @Test
    void matchesSource_withANullContext_evaluatesTheSourceAlone() {
        var sqlException = oracle(ORA_ENGLISH);
        var env = environment();
        ErrorRouter.claim(new DataException(SQL + ORA_ENGLISH, sqlException), new ErrorRouter.Mapping[] {BANKKONTONR}, env);

        assertThat(ErrorRouter.matchesSource(BANKKONTONR, sqlException, env.getGraphQlContext())).isTrue();
        assertThat(ErrorRouter.matchesSource(BANKKONTONR, sqlException, null)).isFalse();
        var noCode = new ErrorRouter.DatabaseMapping(null, "1438", null, null);
        assertThat(ErrorRouter.matchesSource(noCode, sqlException, null)).isTrue();
    }

    @Test
    void twoRootsForOneSource_answerTheSameInEitherOrder() {
        var sqlException = oracle(ORA_ENGLISH);
        var withText = new DataException(SQL + ORA_ENGLISH, sqlException);
        var withoutText = new RuntimeException("batch failed", sqlException);
        ErrorRouter.Mapping[] any = {new ErrorRouter.DatabaseMapping(null, "1438", null, null)};

        var first = environment();
        ErrorRouter.claim(withText, any, first);
        ErrorRouter.claim(withoutText, any, first);
        var second = environment();
        ErrorRouter.claim(withoutText, any, second);
        ErrorRouter.claim(withText, any, second);

        assertThat(ErrorRouter.matchesSource(BANKKONTONR, sqlException, first.getGraphQlContext())).isTrue();
        assertThat(ErrorRouter.matchesSource(BANKKONTONR, sqlException, second.getGraphQlContext())).isTrue();
        assertThat(ErrorRouter.matchesSource(PROSENTTALL, sqlException, first.getGraphQlContext())).isFalse();
        assertThat(ErrorRouter.matchesSource(PROSENTTALL, sqlException, second.getGraphQlContext())).isFalse();
    }

    @Test
    void genericExceptionMapping_onTheWrapperClass_stillMatchesTheWrapperByItsOwnMessage() {
        var thrown = new DataException(SQL + ORA_ENGLISH, oracle(ORA_ENGLISH));
        ErrorRouter.Mapping[] generic = {new ErrorRouter.ExceptionMapping(DataException.class, "BANKKONTONR", null)};

        assertThat(ErrorRouter.claim(thrown, generic, environment())).isSameAs(thrown);
        ErrorRouter.Mapping[] elsewhere = {new ErrorRouter.ExceptionMapping(DataException.class, "PROSENTTALL", null)};
        assertThat(ErrorRouter.claim(thrown, elsewhere, environment())).isNull();
    }

    private static SQLException oracle(String message) {
        return new SQLException(message, "22003", 1438);
    }

    private static DataFetchingEnvironment environment() {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .mergedField(MergedField.newMergedField(Field.newField("person").build()).build())
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("person"))
                .build())
            .build();
    }
}
