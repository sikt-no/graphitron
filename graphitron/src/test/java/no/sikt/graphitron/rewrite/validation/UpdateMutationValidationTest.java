package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ArgumentRef;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField.DmlTableField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.model.jooq.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.diagnostics.MatchedKey;

@UnitTier
class UpdateMutationValidationTest {

    enum Case implements ValidatorCase {

        VALID("update mutation field, well-formed, no validation errors",
            new DmlTableField(
                "Mutation", "updateFilm", null,
                new DmlReturnExpression.ProjectedSingle("Film",
                    new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots(
                        TestFixtures.filmTableWithPk(), List.of(TestFixtures.filmIdCol()))),
                new OperationMember.Write.Update(new no.sikt.graphitron.rewrite.model.InputArgRef(
                    "in", "FilmInput",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()), false),
                new no.sikt.graphitron.rewrite.model.UpdateRows.Identified(
                    new no.sikt.graphitron.model.diagnostics.MatchedKey.PrimaryKey(
                        List.of(new no.sikt.graphitron.model.jooq.ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
                        "film_pkey"),
                    List.of(new no.sikt.graphitron.rewrite.model.SetColumn(
                        "title",
                        new no.sikt.graphitron.model.jooq.ColumnRef("title", "TITLE", "java.lang.String"),
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct(), 0)),
                    List.of(new no.sikt.graphitron.rewrite.model.KeyColumn(
                        "filmId",
                        new no.sikt.graphitron.model.jooq.ColumnRef("film_id", "FILM_ID", "java.lang.Integer"),
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct(), 0)),
                    List.of(),
                    List.of(new no.sikt.graphitron.rewrite.model.CarrierNullRule(
                        "title", new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct(),
                        new no.sikt.graphitron.rewrite.model.CarrierNullRule.OnExplicitNull.Clears())))),
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

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void updateMutationFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
