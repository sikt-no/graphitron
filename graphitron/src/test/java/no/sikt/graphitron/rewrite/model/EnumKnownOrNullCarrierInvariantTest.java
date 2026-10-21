package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The carrier invariants that keep {@link CallSiteCompaction.EnumKnownOrNull} to database reads:
 * {@link ChildField.RecordReadField} takes it only with a {@link ValueLocator.TypedColumn} or
 * {@link ValueLocator.ByName} locator, and {@link ErrorFieldRead.SourceAccessor} never carries
 * it. The classifier never builds the rejected combinations, and the emitter has no arm for them.
 */
@UnitTier
class EnumKnownOrNullCarrierInvariantTest {

    private static final SourceLocation LOC = new SourceLocation(1, 1, "schema.graphqls");
    private static final ReturnTypeRef ENUM_SINGLE =
        new ReturnTypeRef.ScalarReturnType("TextRating", new FieldWrapper.Single(true));
    private static final CallSiteCompaction.EnumKnownOrNull WRAP = new CallSiteCompaction.EnumKnownOrNull(
        new CallSiteCompaction.KnownOrNullRef("com.example.schema.TextRatingType", "knownOrNull"));

    private static ChildField.RecordReadField read(ValueLocator locator) {
        return new ChildField.RecordReadField("FilmRow", "textRating", LOC, ENUM_SINGLE, locator, WRAP);
    }

    private static AccessorResolution.Resolved anyAccessor() {
        try {
            return new AccessorResolution.BareName(String.class.getMethod("length"));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void recordRead_acceptsTheWrapOnATypedColumnRead() {
        assertThatCode(() -> read(new ValueLocator.TypedColumn(
                new ColumnRef("text_rating", "TEXT_RATING", "java.lang.String"))))
            .doesNotThrowAnyException();
    }

    @Test
    void recordRead_acceptsTheWrapOnAByNameRead() {
        assertThatCode(() -> read(new ValueLocator.ByName("text_rating"))).doesNotThrowAnyException();
    }

    @Test
    void recordRead_rejectsTheWrapOnAnAccessorRead() {
        assertThatThrownBy(() -> read(new ValueLocator.JavaAccessor(anyAccessor())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("textRating")
            .hasMessageContaining("JavaAccessor");
    }

    @Test
    void recordRead_rejectsTheWrapOnADefaultRead() {
        assertThatThrownBy(() -> read(new ValueLocator.DefaultRead("textRating")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DefaultRead");
    }

    @Test
    void errorSourceAccessor_rejectsTheWrap() {
        assertThatThrownBy(() -> new ErrorFieldRead.SourceAccessor("rating", "rating", WRAP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rating");
    }
}
