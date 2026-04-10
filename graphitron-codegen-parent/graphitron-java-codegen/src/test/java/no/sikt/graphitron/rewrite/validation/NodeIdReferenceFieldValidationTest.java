package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.rewrite.field.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.type.NodeRef;
import no.sikt.graphitron.rewrite.type.TableRef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdReferenceFieldValidationTest {

    @BeforeAll
    static void setUpConfig() {
        TestConfiguration.setProperties();
    }

    private static final NodeRef NODE = new NodeRef(null, List.of());

    enum Case implements ValidatorCase {

        IMPLICIT_SINGLE_FK("exactly one FK between tables — implicit join, no errors",
            new NodeIdReferenceField("Inventory", "filmId", null, "Film",
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", true, List.of(), List.of()), new FieldWrapper.Single(true)),
                new TableRef("inventory", "INVENTORY", "Inventory", true, List.of(), List.of()),
                NODE,
                List.of()),
            List.of()),

        IMPLICIT_NO_FK("no FK between tables — error suggesting @reference",
            new NodeIdReferenceField("Film", "categoryId", null, "Category",
                new ReturnTypeRef.TableBoundReturnType("Category", new TableRef("category", "CATEGORY", "Category", true, List.of(), List.of()), new FieldWrapper.Single(true)),
                new TableRef("film", "FILM", "Film", true, List.of(), List.of()),
                NODE,
                List.of()),
            List.of("Field 'categoryId': no foreign key found between tables 'film' and 'category'; add a @reference directive to specify the join path")),

        IMPLICIT_MULTIPLE_FKS("multiple FKs between tables — error suggesting @reference",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new TableRef("language", "LANGUAGE", "Language", true, List.of(), List.of()), new FieldWrapper.Single(true)),
                new TableRef("film", "FILM", "Film", true, List.of(), List.of()),
                NODE,
                List.of()),
            List.of("Field 'languageId': multiple foreign keys found between tables 'film' and 'language'; add a @reference directive to specify the join path")),

        WITH_EXPLICIT_PATH("explicit FK path leading to the correct table — no errors",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new TableRef("language", "LANGUAGE", "Language", true, List.of(), List.of()), new FieldWrapper.Single(true)),
                new TableRef("film", "FILM", "Film", true, List.of(), List.of()),
                NODE,
                List.of(new FkRef("film_language_id_fkey", "language", "film", List.of(), List.of()))),
            List.of()),

        PATH_WRONG_TABLE("explicit FK path leading to the wrong table — one error",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new TableRef("language", "LANGUAGE", "Language", true, List.of(), List.of()), new FieldWrapper.Single(true)),
                new TableRef("film", "FILM", "Film", true, List.of(), List.of()),
                NODE,
                List.of(new FkRef("sequel_fkey", "film", "film", List.of(), List.of()))),
            List.of("Field 'languageId': @reference path does not lead to the table of type 'Language'"));

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
    void nodeIdReferenceFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
