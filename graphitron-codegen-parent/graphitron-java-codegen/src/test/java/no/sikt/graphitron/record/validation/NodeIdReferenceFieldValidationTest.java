package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.jooq.generated.testdata.public_.Keys;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkStep;
import no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.record.field.NodeTypeRef.ResolvedNodeType;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionStep;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyStep;
import no.sikt.graphitron.record.field.NodeTypeRef.UnresolvedNodeType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.CATEGORY;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.INVENTORY;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.LANGUAGE;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchemaWithNodeTarget;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdReferenceFieldValidationTest {

    @BeforeAll
    static void setUpConfig() {
        TestConfiguration.setProperties();
    }

    enum Case {

        IMPLICIT_SINGLE_FK("exactly one FK between tables — implicit join, no errors",
            inTableTypeSchemaWithNodeTarget("Inventory", INVENTORY, "Film", FILM, "filmId",
                new NodeIdReferenceField("Inventory", "filmId", null, "Film", new ResolvedNodeType(), List.of())),
            List.of()),

        IMPLICIT_NO_FK("no FK between tables — error suggesting @reference",
            inTableTypeSchemaWithNodeTarget("Film", FILM, "Category", CATEGORY, "categoryId",
                new NodeIdReferenceField("Film", "categoryId", null, "Category", new ResolvedNodeType(), List.of())),
            List.of("Field 'categoryId': no foreign key found between tables 'film' and 'category'; add a @reference directive to specify the join path")),

        IMPLICIT_MULTIPLE_FKS("multiple FKs between tables — error suggesting @reference",
            inTableTypeSchemaWithNodeTarget("Film", FILM, "Language", LANGUAGE, "languageId",
                new NodeIdReferenceField("Film", "languageId", null, "Language", new ResolvedNodeType(), List.of())),
            List.of("Field 'languageId': multiple foreign keys found between tables 'film' and 'language'; add a @reference directive to specify the join path")),

        UNRESOLVED_NODE_TYPE("typeName does not resolve to a @node type — one error",
            inTableTypeSchema("Film", "languageId",
                new NodeIdReferenceField("Film", "languageId", null, "UnknownType", new UnresolvedNodeType(), List.of())),
            List.of("Field 'languageId': type 'UnknownType' does not exist in the schema or does not have @node")),

        WITH_EXPLICIT_PATH("explicit FK path leading to the correct table — no errors",
            inTableTypeSchemaWithNodeTarget("Film", FILM, "Language", LANGUAGE, "languageId",
                new NodeIdReferenceField("Film", "languageId", null, "Language", new ResolvedNodeType(),
                    List.of(new FkStep(Keys.FILM__FILM_LANGUAGE_ID_FKEY)))),
            List.of()),

        PATH_WRONG_TABLE("explicit FK path leading to the wrong table — one error",
            inTableTypeSchemaWithNodeTarget("Film", FILM, "Language", LANGUAGE, "languageId",
                new NodeIdReferenceField("Film", "languageId", null, "Language", new ResolvedNodeType(),
                    List.of(new FkStep(Keys.FILM__SEQUEL_FKEY)))),
            List.of("Field 'languageId': @reference path does not lead to the table of type 'Language'")),

        UNRESOLVED_KEY("key name specified but FK could not be found in the jOOQ catalog — one error",
            inTableTypeSchemaWithNodeTarget("Film", FILM, "Language", LANGUAGE, "languageId",
                new NodeIdReferenceField("Film", "languageId", null, "Language", new ResolvedNodeType(),
                    List.of(new UnresolvedKeyStep("FILM_LANGUAGE_FK")))),
            List.of("Field 'languageId': key 'FILM_LANGUAGE_FK' could not be resolved in the jOOQ catalog")),

        UNRESOLVED_CONDITION("condition method present but could not be resolved via reflection — one error",
            inTableTypeSchemaWithNodeTarget("Film", FILM, "Language", LANGUAGE, "languageId",
                new NodeIdReferenceField("Film", "languageId", null, "Language", new ResolvedNodeType(),
                    List.of(new UnresolvedConditionStep("com.example.Conditions.languageCondition")))),
            List.of("Field 'languageId': condition method 'com.example.Conditions.languageCondition' could not be resolved"));

        private final String description;
        private final no.sikt.graphitron.record.GraphitronSchema schema;
        private final List<String> errors;

        Case(String description, no.sikt.graphitron.record.GraphitronSchema schema, List<String> errors) {
            this.description = description;
            this.schema = schema;
            this.errors = errors;
        }

        public List<String> errors() { return errors; }
        @Override public String toString() { return description; }

        public no.sikt.graphitron.record.GraphitronSchema schema() { return schema; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nodeIdReferenceFieldValidation(Case tc) {
        assertThat(validate(tc.schema()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
