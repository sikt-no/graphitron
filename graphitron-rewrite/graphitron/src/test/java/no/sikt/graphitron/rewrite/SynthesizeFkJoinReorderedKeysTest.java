package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link BuildContext#synthesizeFkJoin} against a real jOOQ catalog FK whose own
 * referenced-column list (third {@code TableField[]} arg to {@code Internal.createForeignKey},
 * exposed by jOOQ as {@link org.jooq.ForeignKey#getKeyFields()}) is in a different order than
 * the parent's {@link org.jooq.UniqueKey} declaration order (exposed by
 * {@link org.jooq.ForeignKey#getKey()}{@code .getFields()}).
 *
 * <p>The {@code reordered_fk_child → reordered_pk_parent} FK in the {@code nodeidfixture}
 * schema declares its referenced columns as {@code (pk_b, pk_c, pk_a)} (heterogeneous types
 * varchar, varchar, bigint), while the parent's {@code PRIMARY KEY} was declared as
 * {@code (pk_a, pk_b, pk_c)}. jOOQ's {@code getKeyFields()} returns the FK's own ordering
 * (parallel to {@code getFields()}); {@code getKey().getFields()} returns the PK's own
 * ordering. Synthesis-time slot pairing has to use the FK's own list — pairing {@code getFields()}
 * positionally against {@code getKey().getFields()} produces slots whose {@code sourceSide()}
 * column does not match {@code targetSide()} column by FK constraint.
 *
 * <p>This is the regression that surfaced in downstream consumer code as
 * {@code Field<Long>.eq(Field<String>)} compile errors in generated rows-methods (where the
 * @{@code splitQuery} prelude pairs FK source columns against {@code parentInput.field(name, type)}
 * by name lookup): the named lookup resolves the right typed {@code Field<T>}, but the
 * {@code targetSide()} column it's paired with is the wrong slot's column. {@code JoinSlotOrientationTest}
 * pins per-slot pairing structurally on hand-built {@link JoinSlot.FkSlot}s, but does not drive
 * the catalog-backed synthesis; this test exercises the path that produces slots from a real
 * jOOQ {@link org.jooq.ForeignKey}.
 */
@UnitTier
class SynthesizeFkJoinReorderedKeysTest {

    private static final String NODEID_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";

    private static JooqCatalog nodeIdCatalog() {
        return new JooqCatalog(NODEID_PACKAGE);
    }

    @Test
    void synthesizeFkJoin_pairsSlotsByFkOwnReferencedColumnList() {
        var ctx = new BuildContext(null, nodeIdCatalog(), null);
        var fk = nodeIdCatalog().findForeignKey("reordered_fk_child_parent_fkey").orElseThrow();

        // Sanity-check the FK shape: jOOQ exposes two distinct lists; this fixture provokes the
        // divergence the test guards against. If the next jOOQ upgrade folds these into one,
        // the divergence assertion fails fast and the test no longer probes the regression.
        assertThat(fk.getFields()).extracting(f -> f.getName())
            .as("FK referencing columns sit on the child in declaration order (parallel to getKeyFields())")
            .containsExactly("fk_b", "fk_c", "fk_a");
        assertThat(fk.getKeyFields()).extracting(f -> f.getName())
            .as("FK's own referenced-column list (getKeyFields) is parallel to getFields")
            .containsExactly("pk_b", "pk_c", "pk_a");
        assertThat(fk.getKey().getFields()).extracting(f -> f.getName())
            .as("Parent UniqueKey's own field order is the PK declaration order, not the FK's")
            .containsExactly("pk_a", "pk_b", "pk_c");

        var result = ctx.synthesizeFkJoin(fk, "reordered_pk_parent", "fieldName", 0, null,
            /*selfRefFkOnSource=*/false);
        assertThat(result).isInstanceOf(BuildContext.FkJoinResolution.Resolved.class);
        var fkJoin = ((BuildContext.FkJoinResolution.Resolved) result).fkJoin();

        // Per-slot type pairing: for a real catalog FK, both sides of every slot share their
        // declared SQL type. Under the regression (positional zip of two non-parallel lists),
        // slot[0] would pair pk_a (bigint) ↔ fk_b (varchar), slot[2] would pair pk_c (varchar)
        // ↔ fk_a (bigint) — both observable as a Java-class mismatch in ColumnRef.columnClass.
        int i = 0;
        for (var slot : fkJoin.slots()) {
            assertThat(slot.sourceSide().columnClass())
                .as("slot %d source/target columns share their declared FK column type "
                    + "(source=%s, target=%s)", i, slot.sourceSide().sqlName(), slot.targetSide().sqlName())
                .isEqualTo(slot.targetSide().columnClass());
            i++;
        }

        // Per-slot SQL-name pairing: in this fixture, the FK references its parent columns by the
        // same SQL name on both sides (pk_b ↔ fk_b… in spirit of the constraint declaration order).
        // The structural promise is: slot[i] pairs the FK referenced column at position i with the
        // FK referencing column at position i. Asserts the pairing in terms of the FK's own list.
        assertThat(fkJoin.sourceSideColumns()).extracting(c -> c.sqlName())
            .as("source-side (parent) columns iterate the FK's own referenced-column list, "
                + "not the parent UniqueKey's own field order")
            .containsExactly("pk_b", "pk_c", "pk_a");
        assertThat(fkJoin.targetSideColumns()).extracting(c -> c.sqlName())
            .as("target-side (child) columns iterate the FK's own referencing-column list")
            .containsExactly("fk_b", "fk_c", "fk_a");
    }
}
