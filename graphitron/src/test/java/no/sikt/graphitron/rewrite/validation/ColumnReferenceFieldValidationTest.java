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

    // ColumnReferenceField + condition-join now classifies and emits a real scalar
    // subquery via InlineColumnReferenceFieldEmitter; the validator no longer surfaces a
    // deferred-rejection.

    private static final String DEFERRED_NODEID_ENCODE =
        "Field 'Film.languageName': "
        + "ColumnReferenceField NodeIdEncodeKeys (rooted-at-parent NodeId reference) not yet implemented"
        + " — requires JOIN-with-projection emission";

    private static final List<JoinStep> FK_PATH = List.of(TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(),
        TestFixtures.joinTarget("language"), List.of(), null, ""));
    private static final List<JoinStep> CONDITION_PATH = List.of(TestFixtures.conditionJoin(
        TestFixtures.staticServiceMethodRef("com.example.Conditions", "languageCondition",
            ClassName.get("org.jooq", "Condition"), List.of()),
        TestFixtures.languageTable(), ""));

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name; Direct + FK-only path",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                FK_PATH,
                new CallSiteCompaction.Direct(),
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name; Direct + FK-only path",
            new ColumnReferenceField("Film", "languageName", null, "language_name", new ColumnRef("NAME", "", ""),
                FK_PATH,
                new CallSiteCompaction.Direct(),
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of()),

        CONDITION_METHOD("path resolved via condition method instead of a FK — classifies and emits a scalar subquery (R232)",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                CONDITION_PATH,
                new CallSiteCompaction.Direct(),
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable())),
            List.of()),

        RESOLVED_NODEID_ENCODE("NodeIdEncodeKeys compaction on a FK-only path — deferred to JOIN-with-projection slug",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""),
                FK_PATH,
                new CallSiteCompaction.NodeIdEncodeKeys(
                    new HelperRef.Encode(ClassName.bestGuess("com.example.NodeIds"), "encodeLanguage",
                        List.of(new ColumnRef("ID", "java.lang.Integer", "")))),
                TestFixtures.pcFor(FK_PATH, TestFixtures.filmTable())),
            List.of(DEFERRED_NODEID_ENCODE)),

        MISSING_PATH("no @reference directive — path is empty",
            new ColumnReferenceField("Film", "languageName", null, "languageName", new ColumnRef("NAME", "", ""), List.of(),
                new CallSiteCompaction.Direct(),
                /* parentCorrelation */ null),
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
