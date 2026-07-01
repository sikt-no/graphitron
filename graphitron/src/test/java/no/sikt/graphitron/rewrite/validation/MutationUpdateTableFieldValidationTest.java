package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ArgumentRef;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationUpdateTableField;
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
class MutationUpdateTableFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("update mutation field, well-formed, no validation errors",
            new MutationUpdateTableField(
                "Mutation", "updateFilm", null,
                new DmlReturnExpression.ProjectedSingle("Film"),
                no.sikt.graphitron.rewrite.model.DialectRequirement.None.INSTANCE,
                new no.sikt.graphitron.rewrite.model.InputArgRef(
                    "in", "FilmInput",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()), false),
                new no.sikt.graphitron.rewrite.model.UpdateRows.Identified(
                    new no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey(
                        List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
                        "film_pkey"),
                    List.of(new no.sikt.graphitron.rewrite.model.SetColumn(
                        "title",
                        new no.sikt.graphitron.rewrite.model.ColumnRef("title", "TITLE", "java.lang.String"),
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct())),
                    List.of(new no.sikt.graphitron.rewrite.model.KeyColumn(
                        "filmId",
                        new no.sikt.graphitron.rewrite.model.ColumnRef("film_id", "FILM_ID", "java.lang.Integer"),
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()))),
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
