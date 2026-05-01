package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the shared row-construction core consumed by both
 * {@link no.sikt.graphitron.rewrite.generators.LookupValuesJoinEmitter} and
 * {@link SelectMethodBody}.
 *
 * <p>Tests use {@code List<ColumnRef>} with the identity projection. Real callers either pass
 * the same shape (dispatcher) or a richer caller-owned slot type with {@code Slot::targetColumn}
 * (lookup); the helper's behaviour is identical in both cases.
 */
@UnitTier
class ValuesJoinRowBuilderTest {

    private static final Function<ColumnRef, ColumnRef> ID = c -> c;
    private static final String CTX = "@test";

    private static ColumnRef col(String sqlName, String javaName, String javaType) {
        return new ColumnRef(sqlName, javaName, javaType);
    }

    @Test
    void rowTypeArgs_singleSlot_arity2WithIntegerIdxThenColumnType() {
        var slots = List.of(col("film_id", "FILM_ID", "java.lang.Integer"));
        var args = ValuesJoinRowBuilder.rowTypeArgs(slots, ID, CTX);
        assertThat(args).hasSize(2);
        assertThat(args[0].toString()).isEqualTo("java.lang.Integer");
        assertThat(args[1].toString()).isEqualTo("java.lang.Integer");
    }

    @Test
    void rowTypeArgs_fiveSlots_arity6() {
        var slots = IntStream.range(0, 5)
            .mapToObj(i -> col("c" + i, "C" + i, "java.lang.String"))
            .toList();
        var args = ValuesJoinRowBuilder.rowTypeArgs(slots, ID, CTX);
        assertThat(args).hasSize(6);
        assertThat(args[0].toString()).isEqualTo("java.lang.Integer");
        for (int i = 1; i < 6; i++) {
            assertThat(args[i].toString()).isEqualTo("java.lang.String");
        }
    }

    @Test
    void rowTypeArgs_arity21Plus1IdxIsTheLastAcceptedShape() {
        var slots = IntStream.range(0, 21)
            .mapToObj(i -> col("c" + i, "C" + i, "java.lang.String"))
            .toList();
        var args = ValuesJoinRowBuilder.rowTypeArgs(slots, ID, CTX);
        assertThat(args).hasSize(22);
    }

    @Test
    void rowTypeArgs_arity23Throws_messageIncludesDirectiveContext() {
        var slots = IntStream.range(0, 22)
            .mapToObj(i -> col("c" + i, "C" + i, "java.lang.String"))
            .toList();
        assertThatThrownBy(() -> ValuesJoinRowBuilder.rowTypeArgs(slots, ID, "@lookupKey"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@lookupKey")
            .hasMessageContaining("Row/Record limit of 22");
    }

    @Test
    void rowTypeArgs_emptySlots_throwsWithDirectiveContext() {
        // The dispatcher and lookup-site classifiers reject empty key columns upstream, but the
        // helper guards against the silent Row1<Integer> shape (idx-only, no key) just in case.
        assertThatThrownBy(() -> ValuesJoinRowBuilder.rowTypeArgs(List.<ColumnRef>of(), ID, "@key"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@key")
            .hasMessageContaining("no key columns");
    }

    @Test
    void rowClass_pickedFromSlotCount() {
        assertThat(ValuesJoinRowBuilder.rowClass(1).simpleName()).isEqualTo("Row2");
        assertThat(ValuesJoinRowBuilder.rowClass(5).simpleName()).isEqualTo("Row6");
        assertThat(ValuesJoinRowBuilder.rowClass(21).simpleName()).isEqualTo("Row22");
    }

    @Test
    void recordClass_pickedFromSlotCount() {
        assertThat(ValuesJoinRowBuilder.recordClass(1).simpleName()).isEqualTo("Record2");
        assertThat(ValuesJoinRowBuilder.recordClass(5).simpleName()).isEqualTo("Record6");
    }

    @Test
    void emitRowArrayDecl_includesSuppressWarningsAndTypedArrayCast() {
        var slots = List.of(col("film_id", "FILM_ID", "java.lang.Integer"));
        var b = CodeBlock.builder();
        ValuesJoinRowBuilder.emitRowArrayDecl(b, slots, ID, CTX, "rows", "n");
        var s = b.build().toString();
        assertThat(s).contains("@java.lang.SuppressWarnings");
        assertThat(s).contains("\"unchecked\"");
        assertThat(s).contains("\"rawtypes\"");
        assertThat(s).contains("Row2<");
        assertThat(s).contains("rows = (");
        assertThat(s).contains("new org.jooq.Row2[n]");
    }

    @Test
    void emitRowArrayDecl_acceptsArbitrarySizeExpressions() {
        var slots = List.of(col("c1", "C1", "java.lang.String"));
        var b = CodeBlock.builder();
        ValuesJoinRowBuilder.emitRowArrayDecl(b, slots, ID, CTX, "rows", "bindings.size()");
        assertThat(b.build().toString()).contains("new org.jooq.Row2[bindings.size()]");
    }

    @Test
    void cellsCode_idxFollowedByTypedDslVal() {
        var slots = List.of(
            col("film_id", "FILM_ID", "java.lang.Integer"),
            col("title", "TITLE", "java.lang.String"));
        var idxExpr = CodeBlock.of("org.jooq.impl.DSL.inline(i)");
        var s = ValuesJoinRowBuilder.cellsCode(slots, ID, idxExpr, "table",
            (_, i) -> CodeBlock.of("v" + i)).toString();
        assertThat(s)
            .startsWith("org.jooq.impl.DSL.inline(i)")
            .contains("org.jooq.impl.DSL.val(v0, table.FILM_ID.getDataType())")
            .contains("org.jooq.impl.DSL.val(v1, table.TITLE.getDataType())");
    }

    @Test
    void cellsCode_acceptsBindingDerivedIdxExpression() {
        // Dispatcher's idx cell shape: DSL.val(idx, Integer.class) rather than DSL.inline(i).
        // Same SQL, different javapoet — helper must accept either via the idxCellExpr param.
        var slots = List.of(col("c1", "C1", "java.lang.Integer"));
        var idxExpr = CodeBlock.of("org.jooq.impl.DSL.val(idx, java.lang.Integer.class)");
        var s = ValuesJoinRowBuilder.cellsCode(slots, ID, idxExpr, "t",
            (_, i) -> CodeBlock.of("cols[$L]", i)).toString();
        assertThat(s).startsWith("org.jooq.impl.DSL.val(idx, java.lang.Integer.class)");
        assertThat(s).contains("cols[0]");
        assertThat(s).contains("t.C1.getDataType()");
    }

    @Test
    void cellsCode_passesCallerSlotBackThroughCallback() {
        // The lookup site uses a richer Slot type and reads context off it inside the callback.
        // Verify the helper passes the caller's slot back so callers can keep their own slot
        // shape without a parallel-list bridge.
        record RichSlot(ColumnRef columnRef, String tag) {}
        var slots = List.of(
            new RichSlot(col("a", "A", "java.lang.Integer"), "tagA"),
            new RichSlot(col("b", "B", "java.lang.String"), "tagB"));
        var s = ValuesJoinRowBuilder.cellsCode(slots, RichSlot::columnRef,
            CodeBlock.of("org.jooq.impl.DSL.inline(i)"), "table",
            (slot, i) -> CodeBlock.of(slot.tag())).toString();
        assertThat(s)
            .contains("org.jooq.impl.DSL.val(tagA, table.A.getDataType())")
            .contains("org.jooq.impl.DSL.val(tagB, table.B.getDataType())");
    }

    @Test
    void aliasArgs_idxFollowedBySqlNamesInOrder() {
        var slots = List.of(
            col("film_id", "FILM_ID", "java.lang.Integer"),
            col("language_id", "LANGUAGE_ID", "java.lang.Integer"));
        var s = ValuesJoinRowBuilder.aliasArgs(slots, ID, "myInput").toString();
        assertThat(s).isEqualTo("\"myInput\", \"idx\", \"film_id\", \"language_id\"");
    }

    @Test
    void usingArgs_javaNamesInOrderQualifiedByTableLocal() {
        var slots = List.of(
            col("film_id", "FILM_ID", "java.lang.Integer"),
            col("language_id", "LANGUAGE_ID", "java.lang.Integer"));
        var s = ValuesJoinRowBuilder.usingArgs(slots, ID, "table").toString();
        assertThat(s).isEqualTo("table.FILM_ID, table.LANGUAGE_ID");
    }

    @Test
    void rowArrayType_returnsArrayOfParameterisedRow() {
        var slots = List.of(col("film_id", "FILM_ID", "java.lang.Integer"));
        var t = ValuesJoinRowBuilder.rowArrayType(slots, ID, CTX).toString();
        assertThat(t).contains("org.jooq.Row2<java.lang.Integer, java.lang.Integer>[]");
    }

    @Test
    void inputTableType_returnsParameterisedTableOfRecord() {
        var slots = List.of(col("film_id", "FILM_ID", "java.lang.Integer"));
        var t = ValuesJoinRowBuilder.inputTableType(slots, ID, CTX).toString();
        assertThat(t).contains("org.jooq.Table<org.jooq.Record2<java.lang.Integer, java.lang.Integer>>");
    }
}
