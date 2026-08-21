package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.ChildField.RecordReadField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.ValueLocator;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.schema;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record-read leaf's cross-axis gating rule: each {@link ValueLocator} arm is only
 * admissible under the parent source-object shape whose cast the emitter's corresponding read
 * arm performs. One positive and one negative case per constrained arm, plus the unconstrained
 * {@link ValueLocator.DefaultRead}.
 */
@UnitTier
class RecordReadFieldValidationTest {

    private static final ReturnTypeRef SCALAR_SINGLE =
        new ReturnTypeRef.ScalarReturnType("String", new FieldWrapper.Single(true));

    private static TableRef filmTable() {
        return new TableRef("film", "FILM",
            ClassName.bestGuess("fake.tables.Film"),
            ClassName.bestGuess("fake.tables.records.FilmRecord"),
            ClassName.bestGuess("fake.Tables"),
            List.of(), List.of());
    }

    private static AccessorResolution.Resolved anyAccessor() {
        try {
            return new AccessorResolution.BareName(String.class.getMethod("length"));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static RecordReadField field(ValueLocator locator) {
        return new RecordReadField("Film", "title", null, SCALAR_SINGLE, locator,
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct());
    }

    private static List<ValidationError> validateUnder(GraphitronType parent, ValueLocator locator) {
        return validate(schema(parent, "title", field(locator)));
    }

    @Test
    void typedColumnUnderTableRecordParent_passes() {
        var parent = new GraphitronType.JooqTableRecordType("Film", null, "fake.tables.records.FilmRecord", filmTable());
        var errors = validateUnder(parent, new ValueLocator.TypedColumn(new ColumnRef("title", "TITLE", "java.lang.String")));
        assertThat(errors).isEmpty();
    }

    @Test
    void typedColumnUnderClassBackedParent_rejected() {
        var parent = new GraphitronType.PojoResultType.Backed("Film", null, "fake.FilmDto");
        var errors = validateUnder(parent, new ValueLocator.TypedColumn(new ColumnRef("title", "TITLE", "java.lang.String")));
        assertThat(errors).extracting(ValidationError::message).anyMatch(m ->
            m.contains("Film.title") && m.contains("TypedColumn") && m.contains("Backed"));
    }

    @Test
    void typedColumnUnderTableRecordParentWithoutResolvedTable_rejected() {
        var parent = new GraphitronType.JooqTableRecordType("Film", null, "fake.tables.records.FilmRecord", null);
        var errors = validateUnder(parent, new ValueLocator.TypedColumn(new ColumnRef("title", "TITLE", "java.lang.String")));
        assertThat(errors).extracting(ValidationError::message).anyMatch(m ->
            m.contains("Film.title") && m.contains("TypedColumn"));
    }

    @Test
    void javaAccessorUnderClassBackedParents_passes() {
        var record = new GraphitronType.JavaRecordType("Film", null, "fake.FilmDto");
        var pojo = new GraphitronType.PojoResultType.Backed("Film", null, "fake.FilmDto");
        assertThat(validateUnder(record, new ValueLocator.JavaAccessor(anyAccessor()))).isEmpty();
        assertThat(validateUnder(pojo, new ValueLocator.JavaAccessor(anyAccessor()))).isEmpty();
    }

    @Test
    void javaAccessorUnderJooqRecordParent_rejected() {
        var parent = new GraphitronType.JooqRecordType("Film", null, "org.jooq.Record");
        var errors = validateUnder(parent, new ValueLocator.JavaAccessor(anyAccessor()));
        assertThat(errors).extracting(ValidationError::message).anyMatch(m ->
            m.contains("Film.title") && m.contains("JavaAccessor"));
    }

    @Test
    void byNameUnderJooqRecordCarrierParents_passes() {
        var plain = new GraphitronType.JooqRecordType("Film", null, "org.jooq.Record");
        var tableRecord = new GraphitronType.JooqTableRecordType("Film", null, "fake.tables.records.FilmRecord", null);
        assertThat(validateUnder(plain, new ValueLocator.ByName("title"))).isEmpty();
        assertThat(validateUnder(tableRecord, new ValueLocator.ByName("title"))).isEmpty();
    }

    @Test
    void byNameUnderClassBackedParent_rejected() {
        var parent = new GraphitronType.JavaRecordType("Film", null, "fake.FilmDto");
        var errors = validateUnder(parent, new ValueLocator.ByName("title"));
        assertThat(errors).extracting(ValidationError::message).anyMatch(m ->
            m.contains("Film.title") && m.contains("ByName"));
    }

    @Test
    void defaultReadIsUnconstrained() {
        // @error-type parent (not a ResultType) and a class-backed parent are both admissible:
        // graphitron locates nothing, so no cast depends on the parent shape.
        var errorParent = new GraphitronType.ErrorType("Film", null, List.of(), List.of());
        var classParent = new GraphitronType.JavaRecordType("Film", null, "fake.FilmDto");
        assertThat(validateUnder(errorParent, new ValueLocator.DefaultRead("title"))).isEmpty();
        assertThat(validateUnder(classParent, new ValueLocator.DefaultRead("title"))).isEmpty();
    }
}
