package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The named join site for the retired {@code ResultRowWalk(OUTCOME_SUCCESS)} ⇒
 * {@code Wrap.TableRecord} invariant (the "hard one" in the decompose-sourcekey census): after
 * the envelope axis moved to the first-class {@link SourceEnvelope} and the reader seal died,
 * the two facts share no key-level carrier — so the coupling is re-asserted where they join,
 * {@link ChildField.SingleRecordIdField}'s compact constructor, which requires the typed
 * {@code Wrap.TableRecord} <em>unconditionally</em> (strictly stronger than the retired
 * conditional: this leaf is the only envelope-bearing typed-record read, and its sibling
 * envelope carrier {@link ChildField.RecordCompositeField} is a composite passthrough that
 * holds no key at all).
 */
@UnitTier
class SingleRecordIdFieldKeyShapeInvariantTest {

    private static final ColumnRef FILM_ID =
        new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final TableRef FILM_TABLE =
        TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID));
    private static final SourceLocation LOC = new SourceLocation(1, 1, "schema.graphqls");
    private static final ReturnTypeRef.ScalarReturnType ID_RETURN =
        new ReturnTypeRef.ScalarReturnType("ID", new FieldWrapper.Single(false));
    private static final CallSiteCompaction.NodeIdEncodeKeys ENCODE =
        new CallSiteCompaction.NodeIdEncodeKeys(new HelperRef.Encode(
            ClassName.bestGuess("com.example.NodeIds"), "encodeFilm", List.of(FILM_ID)));

    @Test
    void acceptsTypedTableRecordWrapUnderEitherEnvelope() {
        var typedKey = new SourceKey(
            List.of(FILM_ID), new SourceKey.Wrap.TableRecord(FILM_TABLE.recordClass()));
        assertThatCode(() -> new ChildField.SingleRecordIdField(
                "FilmIdsPayload", "filmIds", LOC, ID_RETURN, FILM_TABLE,
                typedKey, SourceEnvelope.OUTCOME_SUCCESS, ENCODE))
            .doesNotThrowAnyException();
        assertThatCode(() -> new ChildField.SingleRecordIdField(
                "FilmIdsPayload", "filmIds", LOC, ID_RETURN, FILM_TABLE,
                typedKey, SourceEnvelope.DIRECT, ENCODE))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsUntypedWrapRegardlessOfEnvelope() {
        var rowKey = new SourceKey(List.of(FILM_ID), new SourceKey.Wrap.Row());
        assertThatThrownBy(() -> new ChildField.SingleRecordIdField(
                "FilmIdsPayload", "filmIds", LOC, ID_RETURN, FILM_TABLE,
                rowKey, SourceEnvelope.OUTCOME_SUCCESS, ENCODE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Wrap.TableRecord");
        var recordKey = new SourceKey(List.of(FILM_ID), new SourceKey.Wrap.Record());
        assertThatThrownBy(() -> new ChildField.SingleRecordIdField(
                "FilmIdsPayload", "filmIds", LOC, ID_RETURN, FILM_TABLE,
                recordKey, SourceEnvelope.DIRECT, ENCODE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Wrap.TableRecord");
    }
}
