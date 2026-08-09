package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link BuildContext#synthesizeFkJoin} against a real jOOQ catalog FK whose own
 * referenced-column list ({@link org.jooq.ForeignKey#getKeyFields()}, parallel to
 * {@link org.jooq.ForeignKey#getFields()}) is ordered differently than the parent
 * {@link org.jooq.UniqueKey}'s declaration order
 * ({@link org.jooq.ForeignKey#getKey()}{@code .getFields()}).
 *
 * <p>The {@code reordered_fk_child → reordered_pk_parent} FK in the {@code nodeidfixture}
 * schema references {@code (pk_b, pk_c, pk_a)} (varchar, varchar, bigint) while the parent's
 * {@code PRIMARY KEY} is declared {@code (pk_a, pk_b, pk_c)}. Slot pairing must use the FK's
 * own list; zipping {@code getFields()} positionally against {@code getKey().getFields()}
 * pairs each {@code sourceSide()} column with the wrong slot's {@code targetSide()} column.
 *
 * <p>{@code JoinSlotOrientationTest} pins per-slot pairing structurally on hand-built
 * {@link JoinSlot.FkSlot}s but does not drive catalog-backed synthesis; this test exercises
 * the path that produces slots from a real jOOQ {@link org.jooq.ForeignKey}.
 */
@UnitTier
class SynthesizeFkJoinReorderedKeysTest {

    private static final String NODEID_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";

    private static JooqCatalog nodeIdCatalog() {
        return new JooqCatalog(NODEID_PACKAGE);
    }

    @Test
    void synthesizeFkJoin_pairsSlotsByFkOwnReferencedColumnList() {
        var ctx = new BuildContext(null, nodeIdCatalog(), stubRewriteContext());
        var fk = ((JooqCatalog.ForeignKeyLookup.Resolved) nodeIdCatalog()
            .findForeignKey("reordered_fk_child_parent_fkey", null)).fk();

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
        var fkJoin = ((BuildContext.FkJoinResolution.Resolved) result).hop();
        var pairs = (On.ColumnPairs) fkJoin.on();

        // Per-slot type pairing: for a real catalog FK, both sides of every slot share their
        // declared SQL type. A positional zip of the two non-parallel lists pairs
        // slot[0] as pk_a (bigint) ↔ fk_b (varchar) and slot[2] as pk_c (varchar) ↔ fk_a
        // (bigint); both observable as a Java-class mismatch in ColumnRef.columnClass.
        int i = 0;
        for (var slot : pairs.slots()) {
            assertThat(slot.sourceSide().columnClass())
                .as("slot %d source/target columns share their declared FK column type "
                    + "(source=%s, target=%s)", i, slot.sourceSide().sqlName(), slot.targetSide().sqlName())
                .isEqualTo(slot.targetSide().columnClass());
            i++;
        }

        // Per-slot SQL-name pairing: slot[i] pairs the FK's referenced column at position i
        // with its referencing column at position i, iterating the FK's own list.
        assertThat(pairs.sourceSideColumns()).extracting(c -> c.sqlName())
            .as("source-side (parent) columns iterate the FK's own referenced-column list, "
                + "not the parent UniqueKey's own field order")
            .containsExactly("pk_b", "pk_c", "pk_a");
        assertThat(pairs.targetSideColumns()).extracting(c -> c.sqlName())
            .as("target-side (child) columns iterate the FK's own referencing-column list")
            .containsExactly("fk_b", "fk_c", "fk_a");
    }

    private static RewriteContext stubRewriteContext() {
        return new RewriteContext(
            java.util.List.of(),
            java.nio.file.Path.of("."), "SynthesizeFkJoinReorderedKeysTest",
            java.nio.file.Path.of("."),
            "unused",
            "unused");
    }
}
