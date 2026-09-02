package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.model.jooq.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

/**
 * The mutation twin of {@link QueryServiceTableFieldValidationTest}: the same three guards, at the
 * mutation leaf. Both leaves delegate to one validator method, so the pair is what pins that the
 * delegation is in place at both rather than only at the query side. The coordinate matters on its
 * own account too: a mutation service that deletes rows and hands the deleted records back is the
 * shape most likely to hold keys the table no longer has, so the guards it inherits are the ones
 * an author is most likely to meet.
 */
@UnitTier
class MutationServiceTableFieldValidationTest {

    private static final ReturnTypeRef.TableBoundReturnType FILM_RETURN =
        new ReturnTypeRef.TableBoundReturnType("Film",
            TestFixtures.filmTableWithPk(),
            new FieldWrapper.Single(true));

    enum Case implements ValidatorCase {

        VALID("service mutation field with resolved method — passes validation (Invariants §1/§2 enforced at classifier time)",
            new MutationServiceTableField("Mutation", "externalMutation", null,
                FILM_RETURN,
                                TestFixtures.stubServiceCall(TestFixtures.staticServiceMethodRef("com.example.Service", "method", TypeName.VOID, List.of())),
                Optional.empty()),
            List.of()),

        RETURN_TABLE_NO_PK(
            "key-less return table → missing-PK rejection naming the table",
            field("externalMutation",
                new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.filmTable(),
                    TestFixtures.single()),
                Optional.empty()),
            List.of("Field 'Mutation.externalMutation': @service on a table-bound return type "
                + "requires the returned table 'film' to have a primary key for the keyed "
                + "re-projection of the returned records")),

        LIST_KEY_ARITY_EXCEEDS_ROW22_CAP(
            "list return whose key has 22 PK columns → Row22-cap rejection",
            field("externalMutations",
                new ReturnTypeRef.TableBoundReturnType("Wide", wideTable(),
                    TestFixtures.nonNullList()),
                Optional.empty()),
            List.of("Field 'Mutation.externalMutations': a list-returning @service on a @table "
                + "type re-fetches the returned rows through a keyed re-query whose key is table "
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
            field("externalMutation", FILM_RETURN, Optional.of(mappedChannel())),
            List.of("Field 'Mutation.externalMutation': a root @service field with a @table-bound "
                + "return carrying an error channel is not supported — the keyed re-projection "
                + "emit inlines its channel arms on the single-channel premise; widening channel "
                + "resolution to table-bound payloads requires designing that arm first"));

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        private static MutationServiceTableField field(String name,
                ReturnTypeRef.TableBoundReturnType returnType,
                Optional<ErrorChannel.Mapped> errorChannel) {
            return new MutationServiceTableField("Mutation", name, null, returnType,
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

        /** See the query twin: the channel slot is unreachable from SDL at this coordinate. */
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
    void mutationServiceTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
