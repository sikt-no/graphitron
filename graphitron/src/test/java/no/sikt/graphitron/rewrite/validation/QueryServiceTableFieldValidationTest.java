package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

/**
 * The three guards a table-bound root {@code @service} return owes, at the query leaf. Each fires
 * on a hand-built field beside the control that must stay silent, which is the only way to reach
 * them: all three mirror an invariant the classifier or the channel resolver already guarantees,
 * so a schema cannot produce a field that arrives here carrying one of these defects.
 *
 * <p>The missing-key guard's primary diagnostic is the classifier's, pinned end to end from SDL by
 * {@code RootServiceReturnTablePkRejectionTest}; this pins the validator's mirror of it, which is
 * what keeps the invariant enforced if the classify arm ever stops firing. The emitter runs on it:
 * the lift hands the return table's primary key to {@code ParentCorrelation.OnLiftedSlots}, which
 * refuses an empty column tuple, so an unguarded key-less return is a generator-side throw rather
 * than the build error the coordinate promises.
 *
 * <p>The mutation twin shares the validator method and pins the same four rows; see
 * {@link MutationServiceTableFieldValidationTest}.
 */
@UnitTier
class QueryServiceTableFieldValidationTest {

    private static final ReturnTypeRef.TableBoundReturnType FILM_RETURN =
        new ReturnTypeRef.TableBoundReturnType("Film",
            TestFixtures.filmTableWithPk(),
            new FieldWrapper.Single(true));

    enum Case implements ValidatorCase {

        VALID("service query field with resolved method — passes validation (Invariants §1/§2 enforced at classifier time)",
            new QueryServiceTableField("Query", "externalFilm", null,
                FILM_RETURN,
                                TestFixtures.stubServiceCall(TestFixtures.staticServiceMethodRef("com.example.Service", "method", TypeName.VOID, List.of())),
                Optional.empty()),
            List.of()),

        RETURN_TABLE_NO_PK(
            "key-less return table → missing-PK rejection naming the table",
            field("externalFilm",
                new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.filmTable(),
                    TestFixtures.single()),
                Optional.empty()),
            List.of("Field 'Query.externalFilm': @service on a table-bound return type requires "
                + "the returned table 'film' to have a primary key for the keyed re-projection of "
                + "the returned records")),

        LIST_KEY_ARITY_EXCEEDS_ROW22_CAP(
            "list return whose key has 22 PK columns → Row22-cap rejection",
            field("externalFilms",
                new ReturnTypeRef.TableBoundReturnType("Wide", wideTable(),
                    TestFixtures.nonNullList()),
                Optional.empty()),
            List.of("Field 'Query.externalFilms': a list-returning @service on a @table type "
                + "re-fetches the returned rows through a keyed re-query whose key is table "
                + "'wide's primary key; 22 key columns exceeds jOOQ's typed Row22 cap (key + idx "
                + "must fit in Row<N+1>). Use a narrower primary key, or drop @table from the "
                + "return type to keep reading the columns off the returned record")),

        SINGLE_KEY_ARITY_EXEMPT(
            "the same 22-column key at single cardinality → exempt (plain key equality, no idx slot)",
            field("externalWide",
                new ReturnTypeRef.TableBoundReturnType("Wide", wideTable(), TestFixtures.single()),
                Optional.empty()),
            List.of()),

        PRESENT_ERROR_CHANNEL(
            "table-bound return carrying an error channel → rejected on the single-channel premise",
            field("externalFilm", FILM_RETURN, Optional.of(mappedChannel())),
            List.of("Field 'Query.externalFilm': a root @service field with a @table-bound return "
                + "carrying an error channel is not supported — the keyed re-projection emit "
                + "inlines its channel arms on the single-channel premise; widening channel "
                + "resolution to table-bound payloads requires designing that arm first"));

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        private static QueryServiceTableField field(String name,
                ReturnTypeRef.TableBoundReturnType returnType,
                Optional<ErrorChannel.Mapped> errorChannel) {
            return new QueryServiceTableField("Query", name, null, returnType,
                TestFixtures.stubServiceCall(TestFixtures.staticServiceMethodRef(
                    "com.example.Service", "method", TypeName.VOID, List.of())),
                errorChannel);
        }

        /** A 22-column primary key: one column past what {@code Row22} leaves for the idx slot. */
        private static List<ColumnRef> wideKey() {
            return java.util.stream.IntStream.rangeClosed(1, 22)
                .mapToObj(i -> TestFixtures.col("k" + i, "K" + i, "java.lang.Integer"))
                .toList();
        }

        private static TableRef wideTable() {
            return TestFixtures.tableRef("wide", "WIDE", "Wide", wideKey());
        }

        /**
         * The channel slot is provably empty on every classifier-built instance at this coordinate
         * ({@code FieldBuilder.resolveErrorChannel} answers no-channel for anything but a
         * class-backed result return, and a {@code @table}-bound return is not one), so a
         * hand-built channel is the only way to exercise the guard. That is the point: the premise
         * behind inlining the fetcher's channel arms is enforced rather than assumed.
         */
        private static ErrorChannel.Mapped mappedChannel() {
            var errorType = new GraphitronType.ErrorType("FilmError", null,
                List.of(new GraphitronType.ErrorType.ExceptionHandler(
                    "java.lang.RuntimeException", Optional.empty(),
                    new GraphitronType.ErrorType.ClientMessage.FromSource())),
                List.of());
            return new ErrorChannel.Mapped(List.of(errorType), "FILM_PAYLOAD");
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void queryServiceTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
