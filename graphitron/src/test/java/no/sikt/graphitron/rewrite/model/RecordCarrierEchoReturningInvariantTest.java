package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.model.diagnostics.MatchedKey;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The record-carrier leaves' shared invariant: a {@code missingAsDeleted} DELETE's echo supplies
 * exactly the columns {@link Returning#nodeKeyTail()} captures beyond the primary key, in the same
 * order, so an echoed row and a deleted row are records of one shape. Both
 * {@link MutationField.MutationDmlRecordField} and {@link MutationField.MutationBulkDmlRecordField}
 * enforce it in their compact constructors; each case below runs against both.
 *
 * <p>The table is a primary key {@code (a, b)} with a node key {@code (a, b, x, y)}: the node id
 * decodes all four, the WHERE binds {@code a} and {@code b}, and {@code x} and {@code y} are dropped.
 */
@UnitTier
class RecordCarrierEchoReturningInvariantTest {

    private static final ColumnRef A = new ColumnRef("a", "A", "java.lang.Integer");
    private static final ColumnRef B = new ColumnRef("b", "B", "java.lang.Integer");
    private static final ColumnRef X = new ColumnRef("x", "X", "java.lang.Integer");
    private static final ColumnRef Y = new ColumnRef("y", "Y", "java.time.LocalDateTime");

    private static final TableRef TABLE = new TableRef(
        "t", "T", "fixture.T", "fixture.TRecord", "fixture.Tables", List.of(A, B), List.of(A, B, X, Y));

    private static final CallSiteExtraction ID_DECODE = new CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch(
        new HelperRef.Decode(ClassName.bestGuess("fixture.Enc"), "decodeT", List.of(A, B, X, Y), "TNode", TABLE));

    private static final KeyColumn KEY_A = new KeyColumn("id", A, ID_DECODE, 0);
    private static final KeyColumn KEY_B = new KeyColumn("id", B, ID_DECODE, 1);
    private static final KeyColumn DROPPED_X = new KeyColumn("id", X, ID_DECODE, 2);
    private static final KeyColumn DROPPED_Y = new KeyColumn("id", Y, ID_DECODE, 3);

    @Test
    void echoMatchingTheReturningTail_isAdmitted() {
        var returning = new Returning(List.of(A, B), List.of(X, Y));
        var echo = List.of(DROPPED_X, DROPPED_Y);

        assertThat(single(echo, returning).returning()).isEqualTo(returning);
        assertThat(bulk(echo, returning).returning()).isEqualTo(returning);
    }

    @Test
    void echoMissingATailColumn_isRefused() {
        assertRefusedByBothLeaves(List.of(DROPPED_X), new Returning(List.of(A, B), List.of(X, Y)));
    }

    @Test
    void echoWithAColumnTheReturningDoesNotCapture_isRefused() {
        assertRefusedByBothLeaves(List.of(DROPPED_X, DROPPED_Y), new Returning(List.of(A, B), List.of(X)));
    }

    @Test
    void echoInAnotherOrderThanTheTail_isRefused() {
        assertRefusedByBothLeaves(List.of(DROPPED_Y, DROPPED_X), new Returning(List.of(A, B), List.of(X, Y)));
    }

    @Test
    void noEchoUnderAWidenedReturning_isRefused() {
        // A missing row would be echoed with nothing for x, while a deleted row reads it back.
        assertRefusedByBothLeaves(List.of(), new Returning(List.of(A, B), List.of(X)));
    }

    @Test
    void identifiedDelete_isNotHeldToTheEchoShape() {
        // Without missingAsDeleted nothing is echoed, so a widened RETURNING needs no echo binding.
        var identified = new DeleteRows.Identified(
            new MatchedKey.PrimaryKey(List.of(A, B), "t_pkey"), List.of(KEY_A, KEY_B), List.of(DROPPED_X, DROPPED_Y));
        var returning = new Returning(List.of(A, B), List.of(X, Y));

        assertThat(singleLeaf(identified, returning).returning()).isEqualTo(returning);
        assertThat(bulkLeaf(identified, returning).returning()).isEqualTo(returning);
    }

    private static void assertRefusedByBothLeaves(List<KeyColumn> echo, Returning returning) {
        assertThatThrownBy(() -> single(echo, returning))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missingAsDeleted echo binds");
        assertThatThrownBy(() -> bulk(echo, returning))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missingAsDeleted echo binds");
    }

    private static DeleteRows.IdempotentByPrimaryKey idempotent(List<KeyColumn> echo) {
        return new DeleteRows.IdempotentByPrimaryKey(
            new MatchedKey.PrimaryKey(List.of(A, B), "t_pkey"),
            List.of(KEY_A, KEY_B), List.of(DROPPED_X, DROPPED_Y), echo);
    }

    private static MutationField.MutationDmlRecordField single(List<KeyColumn> echo, Returning returning) {
        return singleLeaf(idempotent(echo), returning);
    }

    private static MutationField.MutationBulkDmlRecordField bulk(List<KeyColumn> echo, Returning returning) {
        return bulkLeaf(idempotent(echo), returning);
    }

    private static MutationField.MutationDmlRecordField singleLeaf(DeleteRows deleteRows, Returning returning) {
        return new MutationField.MutationDmlRecordField(
            "Mutation", "deleteT", null, payload(false),
            new OperationMember.Write.Delete(new InputArgRef("in", "TDeleteInput", TABLE, false), deleteRows),
            returning, Optional.empty());
    }

    private static MutationField.MutationBulkDmlRecordField bulkLeaf(DeleteRows deleteRows, Returning returning) {
        return new MutationField.MutationBulkDmlRecordField(
            "Mutation", "deleteTs", null, payload(true),
            new OperationMember.Write.Delete(new InputArgRef("in", "TDeleteInput", TABLE, true), deleteRows),
            returning, Optional.empty());
    }

    private static ReturnTypeRef.ResultReturnType payload(boolean list) {
        return new ReturnTypeRef.ResultReturnType(list ? "DeletedTsPayload" : "DeletedTPayload",
            new FieldWrapper.Single(true), null, null);
    }
}
