package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.AliasOwner;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.LookupResolution;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
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

@UnitTier
class InlineLookupValidationTest {

    private static final TableRef FILM_TABLE = TestFixtures.tableRef("film", "FILM", "Film", List.of());
    // A minimal one-arg mapping: these cases pin wrapper/ordering verdicts, not the key
    // payload, and ColumnMapping rejects an empty arg list (the vacuous mapping is
    // LookupResolution.None, which never reaches a lookup leaf).
    private static final LookupMapping SCALAR_LOOKUP = new LookupMapping.ColumnMapping(
        List.of(new LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg(
            "film_id", new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"),
            new CallSiteExtraction.Direct(), false)),
        FILM_TABLE);
    private static final LookupResolution KEYED_LOOKUP = new LookupResolution.Keyed(SCALAR_LOOKUP);
    private static final OrderBySpec.Fixed PK_ORDER = new OrderBySpec.Fixed(
        List.of(new OrderBySpec.ColumnOrderEntry(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"), null, OrderBySpec.SortDirection.ASC)), true);

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
    }

    // The inline lookup-keyed TableField + condition-join shape now classifies and emits a real
    // correlated subquery via the renderer's lookup multiset arm; the validator no longer
    // surfaces a deferred-rejection for it.

    private static final List<JoinStep> CONDITION_PATH = List.of(TestFixtures.conditionJoin(
        TestFixtures.staticServiceMethodRef("com.example.Conditions", "filmCondition",
            ClassName.get("org.jooq", "Condition"), List.of()),
        TestFixtures.filmTable(), ""));

    enum Case implements ValidatorCase {

        // Single-cardinality @lookupKey is now rejected at classifier time (argres Phase 2a C1);
        // it cannot reach the validator. Kept as a structural-validator smoke test: the model
        // record itself is constructible, and the validator has no extra errors to add.
        SINGLE_NOW_PROJECTED("single return — no validator errors; classifier rejection prevents reaching this state",
            new TableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null,
                KEYED_LOOKUP,
                /* parentCorrelation */ null, AliasOwner.shared()),
            List.of()),

        LIST_PROJECTED("list return — inline-projected, no validator errors",
            new TableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)), List.of(), List.of(), PK_ORDER, null,
                KEYED_LOOKUP,
                /* parentCorrelation */ null, AliasOwner.shared()),
            List.of()),

        LIST_WITH_CONDITION_ONLY("list cardinality with condition-only join step — classifies and emits a correlated subquery (R232)",
            new TableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)),
                CONDITION_PATH,
                List.of(), PK_ORDER, null, KEYED_LOOKUP,
                TestFixtures.pcFor(CONDITION_PATH, TestFixtures.filmTable()), AliasOwner.shared()),
            List.of()),

        CONNECTION_BLOCKED("connection return — not valid on lookup field (validator mirror of classifier rejection)",
            new TableField("Language", "films", null, filmReturn(new FieldWrapper.Connection(true, 100)), List.of(), List.of(), new OrderBySpec.None(), null,
                KEYED_LOOKUP,
                /* parentCorrelation */ null, AliasOwner.shared()),
            List.of("Field 'Language.films': lookup fields must not return a connection"));

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
    void lookupTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
