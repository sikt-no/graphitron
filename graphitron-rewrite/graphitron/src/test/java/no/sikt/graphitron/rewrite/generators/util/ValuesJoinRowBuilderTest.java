package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the shared row-construction core consumed by both
 * {@link no.sikt.graphitron.rewrite.generators.LookupValuesJoinEmitter} and
 * {@link SelectMethodBody}.
 */
@UnitTier
class ValuesJoinRowBuilderTest {

    private static ValuesJoinRowBuilder.Slot slot(String sqlName, String javaName, String javaType) {
        return new ValuesJoinRowBuilder.Slot(new ColumnRef(sqlName, javaName, javaType));
    }

    @Test
    void rowTypeArgs_singleSlot_arity2WithIntegerIdxThenColumnType() {
        var slots = List.of(slot("film_id", "FILM_ID", "java.lang.Integer"));
        var args = ValuesJoinRowBuilder.rowTypeArgs(slots);
        assertThat(args).hasSize(2);
        assertThat(args[0].toString()).isEqualTo("java.lang.Integer");
        assertThat(args[1].toString()).isEqualTo("java.lang.Integer");
    }

    @Test
    void rowTypeArgs_fiveSlots_arity6() {
        var slots = IntStream.range(0, 5)
            .mapToObj(i -> slot("c" + i, "C" + i, "java.lang.String"))
            .toList();
        var args = ValuesJoinRowBuilder.rowTypeArgs(slots);
        assertThat(args).hasSize(6);
        assertThat(args[0].toString()).isEqualTo("java.lang.Integer");
        for (int i = 1; i < 6; i++) {
            assertThat(args[i].toString()).isEqualTo("java.lang.String");
        }
    }

    @Test
    void rowTypeArgs_arity21Plus1IdxIsTheLastAcceptedShape() {
        var slots = IntStream.range(0, 21)
            .mapToObj(i -> slot("c" + i, "C" + i, "java.lang.String"))
            .toList();
        var args = ValuesJoinRowBuilder.rowTypeArgs(slots);
        assertThat(args).hasSize(22);
    }

    @Test
    void rowTypeArgs_arity23Throws() {
        var slots = IntStream.range(0, 22)
            .mapToObj(i -> slot("c" + i, "C" + i, "java.lang.String"))
            .toList();
        assertThatThrownBy(() -> ValuesJoinRowBuilder.rowTypeArgs(slots))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exceeds jOOQ's typed Row/Record limit of 22");
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
        var slots = List.of(slot("film_id", "FILM_ID", "java.lang.Integer"));
        var b = CodeBlock.builder();
        ValuesJoinRowBuilder.emitRowArrayDecl(b, slots, "rows", "n");
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
        var slots = List.of(slot("c1", "C1", "java.lang.String"));
        var b = CodeBlock.builder();
        ValuesJoinRowBuilder.emitRowArrayDecl(b, slots, "rows", "bindings.size()");
        assertThat(b.build().toString()).contains("new org.jooq.Row2[bindings.size()]");
    }

    @Test
    void cellsCode_idxFollowedByTypedDslVal() {
        var slots = List.of(
            slot("film_id", "FILM_ID", "java.lang.Integer"),
            slot("title", "TITLE", "java.lang.String"));
        var idxExpr = CodeBlock.of("org.jooq.impl.DSL.inline(i)");
        var s = ValuesJoinRowBuilder.cellsCode(slots, idxExpr, "table",
            (slot, i) -> CodeBlock.of("v" + i)).toString();
        assertThat(s)
            .startsWith("org.jooq.impl.DSL.inline(i)")
            .contains("org.jooq.impl.DSL.val(v0, table.FILM_ID.getDataType())")
            .contains("org.jooq.impl.DSL.val(v1, table.TITLE.getDataType())");
    }

    @Test
    void cellsCode_acceptsBindingDerivedIdxExpression() {
        // Dispatcher's idx cell shape: DSL.val(idx, Integer.class) rather than DSL.inline(i).
        // Same SQL, different javapoet — helper must accept either via the idxCellExpr param.
        var slots = List.of(slot("c1", "C1", "java.lang.Integer"));
        var idxExpr = CodeBlock.of("org.jooq.impl.DSL.val(idx, java.lang.Integer.class)");
        var s = ValuesJoinRowBuilder.cellsCode(slots, idxExpr, "t",
            (slot, i) -> CodeBlock.of("cols[$L]", i)).toString();
        assertThat(s).startsWith("org.jooq.impl.DSL.val(idx, java.lang.Integer.class)");
        assertThat(s).contains("cols[0]");
        assertThat(s).contains("t.C1.getDataType()");
    }

    @Test
    void aliasArgs_idxFollowedBySqlNamesInOrder() {
        var slots = List.of(
            slot("film_id", "FILM_ID", "java.lang.Integer"),
            slot("language_id", "LANGUAGE_ID", "java.lang.Integer"));
        var s = ValuesJoinRowBuilder.aliasArgs(slots, "myInput").toString();
        assertThat(s).isEqualTo("\"myInput\", \"idx\", \"film_id\", \"language_id\"");
    }

    @Test
    void usingArgs_javaNamesInOrderQualifiedByTableLocal() {
        var slots = List.of(
            slot("film_id", "FILM_ID", "java.lang.Integer"),
            slot("language_id", "LANGUAGE_ID", "java.lang.Integer"));
        var s = ValuesJoinRowBuilder.usingArgs(slots, "table").toString();
        assertThat(s).isEqualTo("table.FILM_ID, table.LANGUAGE_ID");
    }

    @Test
    void rowArrayType_returnsArrayOfParameterisedRow() {
        var slots = List.of(slot("film_id", "FILM_ID", "java.lang.Integer"));
        var t = ValuesJoinRowBuilder.rowArrayType(slots).toString();
        assertThat(t).contains("org.jooq.Row2<java.lang.Integer, java.lang.Integer>[]");
    }

    @Test
    void inputTableType_returnsParameterisedTableOfRecord() {
        var slots = List.of(slot("film_id", "FILM_ID", "java.lang.Integer"));
        var t = ValuesJoinRowBuilder.inputTableType(slots).toString();
        assertThat(t).contains("org.jooq.Table<org.jooq.Record2<java.lang.Integer, java.lang.Integer>>");
    }
}
