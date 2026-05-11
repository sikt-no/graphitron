package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.HelperRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.ColumnRef;

@UnitTier
class ColumnReferenceFieldValidationTest {

    private static final String DEFERRED_CONDITION_JOIN =
        "Field 'Film.languageName': "
        + "ColumnReferenceField with @condition-method step in path not yet implemented"
        + " — pending classification-vocabulary item 5"
        + " — see graphitron-rewrite/roadmap/column-reference-on-scalar-field-condition-join.md";

    private static final String DEFERRED_NODEID_ENCODE =
        "Field 'Film.languageName': "
        + "ColumnReferenceField NodeIdEncodeKeys (rooted-at-parent NodeId reference) not yet implemented"
        + " — requires JOIN-with-projection emission"
        + " — see graphitron-rewrite/roadmap/nodeidreferencefield-join-projection-form.md";

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name; Direct + FK-only path",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(), TestFixtures.joinTarget("language"), List.of(), null, "")),
                new CallSiteCompaction.Direct()),
            List.of()),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name; Direct + FK-only path",
            new ColumnReferenceField("Film", "languageName", null, "language_name", new ColumnRef("NAME", "", ""),
                List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(), TestFixtures.joinTarget("language"), List.of(), null, "")),
                new CallSiteCompaction.Direct()),
            List.of()),

        CONDITION_METHOD("path resolved via condition method instead of a FK — deferred to condition-join slug",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(new JoinStep.ConditionJoin(TestFixtures.staticServiceMethodRef("com.example.Conditions", "languageCondition", ClassName.get("org.jooq", "Condition"), List.of()), "")),
                new CallSiteCompaction.Direct()),
            List.of(DEFERRED_CONDITION_JOIN)),

        RESOLVED_NODEID_ENCODE("NodeIdEncodeKeys compaction on a FK-only path — deferred to JOIN-with-projection slug",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(), TestFixtures.joinTarget("language"), List.of(), null, "")),
                new CallSiteCompaction.NodeIdEncodeKeys(
                    new HelperRef.Encode(ClassName.bestGuess("com.example.NodeIds"), "encodeLanguage",
                        List.of(new ColumnRef("ID", "java.lang.Integer", ""))))),
            List.of(DEFERRED_NODEID_ENCODE)),

        MISSING_PATH("no @reference directive — path is empty",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""), List.of(),
                new CallSiteCompaction.Direct()),
            List.of("Field 'Film.languageName': @reference path is required"));

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
    void columnReferenceFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
