package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.jooq.generated.testdata.public_.Keys;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.record.field.NodeTypeRef.NoNodeDirectiveType;
import no.sikt.graphitron.record.field.NodeTypeRef.NotFoundNodeType;
import no.sikt.graphitron.record.field.NodeTypeRef.ResolvedNodeType;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.record.field.FieldWrapper;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.type.NodeRef.NodeDirective;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.CATEGORY;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.INVENTORY;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.LANGUAGE;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdReferenceFieldValidationTest {

    @BeforeAll
    static void setUpConfig() {
        TestConfiguration.setProperties();
    }

    private static final ResolvedNodeType NODE = new ResolvedNodeType(new NodeDirective(null, List.of()));

    enum Case implements ValidatorCase {

        IMPLICIT_SINGLE_FK("exactly one FK between tables — implicit join, no errors",
            new NodeIdReferenceField("Inventory", "filmId", null, "Film",
                new ReturnTypeRef.TableBoundReturnType("Film", new ResolvedTable("film", "FILM", FILM), new FieldWrapper.Single(true)),
                new ResolvedTable("inventory", "INVENTORY", INVENTORY),
                NODE,
                List.of()),
            List.of()),

        IMPLICIT_NO_FK("no FK between tables — error suggesting @reference",
            new NodeIdReferenceField("Film", "categoryId", null, "Category",
                new ReturnTypeRef.TableBoundReturnType("Category", new ResolvedTable("category", "CATEGORY", CATEGORY), new FieldWrapper.Single(true)),
                new ResolvedTable("film", "FILM", FILM),
                NODE,
                List.of()),
            List.of("Field 'categoryId': no foreign key found between tables 'film' and 'category'; add a @reference directive to specify the join path")),

        IMPLICIT_MULTIPLE_FKS("multiple FKs between tables — error suggesting @reference",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new ResolvedTable("language", "LANGUAGE", LANGUAGE), new FieldWrapper.Single(true)),
                new ResolvedTable("film", "FILM", FILM),
                NODE,
                List.of()),
            List.of("Field 'languageId': multiple foreign keys found between tables 'film' and 'language'; add a @reference directive to specify the join path")),

        TYPE_NOT_FOUND("typeName does not exist in the schema — specific not-found error",
            new NodeIdReferenceField("Film", "languageId", null, "UnknownType",
                new ReturnTypeRef.OtherReturnType("UnknownType", new FieldWrapper.Single(true)),
                null,
                new NotFoundNodeType(),
                List.of()),
            List.of("Field 'languageId': type 'UnknownType' does not exist in the schema")),

        TYPE_HAS_NO_NODE("typeName exists but has no @node directive — specific missing-@node error",
            new NodeIdReferenceField("Film", "languageId", null, "Film",
                new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)),
                null,
                new NoNodeDirectiveType(),
                List.of()),
            List.of("Field 'languageId': type 'Film' does not have @node")),

        WITH_EXPLICIT_PATH("explicit FK path leading to the correct table — no errors",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new ResolvedTable("language", "LANGUAGE", LANGUAGE), new FieldWrapper.Single(true)),
                new ResolvedTable("film", "FILM", FILM),
                NODE,
                List.of(new FkRef(Keys.FILM__FILM_LANGUAGE_ID_FKEY))),
            List.of()),

        PATH_WRONG_TABLE("explicit FK path leading to the wrong table — one error",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new ResolvedTable("language", "LANGUAGE", LANGUAGE), new FieldWrapper.Single(true)),
                new ResolvedTable("film", "FILM", FILM),
                NODE,
                List.of(new FkRef(Keys.FILM__SEQUEL_FKEY))),
            List.of("Field 'languageId': @reference path does not lead to the table of type 'Language'")),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog — one error",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new ResolvedTable("language", "LANGUAGE", LANGUAGE), new FieldWrapper.Single(true)),
                new ResolvedTable("film", "FILM", FILM),
                NODE,
                List.of(new UnresolvedKeyRef("FILM_LANGUAGE_FK"))),
            List.of("Field 'languageId': key 'FILM_LANGUAGE_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_CONDITION("condition method present but could not be resolved via reflection — one error",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new ResolvedTable("language", "LANGUAGE", LANGUAGE), new FieldWrapper.Single(true)),
                new ResolvedTable("film", "FILM", FILM),
                NODE,
                List.of(new UnresolvedConditionRef("com.example.Conditions.languageCondition"))),
            List.of("Field 'languageId': condition method 'com.example.Conditions.languageCondition' could not be resolved"));

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
