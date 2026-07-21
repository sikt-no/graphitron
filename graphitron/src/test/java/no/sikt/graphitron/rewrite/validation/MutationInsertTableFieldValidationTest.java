package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ArgumentRef;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class MutationInsertTableFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("insert mutation field, well-formed, no validation errors",
            new MutationInsertTableField(
                "Mutation", "createFilm", null,
                new DmlReturnExpression.ProjectedSingle("Film",
                    new ParentCorrelation.OnLiftedSlots(
                        TestFixtures.filmTableWithPk(), List.of(TestFixtures.filmIdCol()))),
                DialectRequirement.None.INSTANCE,
                ArgumentRef.InputTypeArg.TableInputArg.of(
                    "in", "FilmInput", true, false,
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    List.of(),
                    Optional.empty(),
                    List.of()),
                Optional.empty()),
            List.of()),

        // Row22-cap guard on the reentry key, mirroring the multi-table parent-PK pins in
        // InterfaceFieldValidationTest / UnionFieldValidationTest: the bulk arm's follow-up
        // SELECT joins a VALUES (idx, key...) table, so a 22-column primary key plus the idx
        // slot overflows jOOQ's typed Row22 and must reject at validate time.
        BULK_REENTRY_KEY_EXCEEDS_ROW22_CAP(
            "bulk projected return whose reentry key has 22 PK columns → Row22-cap rejection",
            new MutationInsertTableField(
                "Mutation", "createWides", null,
                new DmlReturnExpression.ProjectedList("Wide",
                    new ParentCorrelation.OnLiftedSlots(wideTable(), wideKey())),
                DialectRequirement.None.INSTANCE,
                ArgumentRef.InputTypeArg.TableInputArg.of(
                    "in", "WideInput", true, true,
                    wideTable(),
                    List.of(),
                    Optional.empty(),
                    List.of()),
                Optional.empty()),
            List.of("Field 'Mutation.createWides': bulk @mutation with a @table return re-fetches "
                + "the written rows through a keyed re-query whose key is table 'wide's primary key; "
                + "22 key columns exceeds jOOQ's typed Row22 cap (key + idx must fit in Row<N+1>). "
                + "Use a narrower primary key or return ID")),

        SINGLE_REENTRY_KEY_WIDE_PK_EXEMPT(
            "single-cardinality projected return with the same 22-column key → exempt (plain key equality, no idx slot)",
            new MutationInsertTableField(
                "Mutation", "createWide", null,
                new DmlReturnExpression.ProjectedSingle("Wide",
                    new ParentCorrelation.OnLiftedSlots(wideTable(), wideKey())),
                DialectRequirement.None.INSTANCE,
                ArgumentRef.InputTypeArg.TableInputArg.of(
                    "in", "WideInput", true, false,
                    wideTable(),
                    List.of(),
                    Optional.empty(),
                    List.of()),
                Optional.empty()),
            List.of());

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        private static List<ColumnRef> wideKey() {
            return java.util.stream.IntStream.rangeClosed(1, 22)
                .mapToObj(i -> TestFixtures.col("k" + i, "K" + i, "java.lang.Integer"))
                .toList();
        }

        private static TableRef wideTable() {
            return TestFixtures.tableRef("wide", "WIDE", "Wide", wideKey());
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void insertMutationFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
