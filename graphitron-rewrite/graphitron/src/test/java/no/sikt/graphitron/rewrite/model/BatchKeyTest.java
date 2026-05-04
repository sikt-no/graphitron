package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures the invariants of the {@link BatchKey} sealed hierarchy, with focus on the two
 * accessor-derived permits added in R60. Sibling of {@link RejectionRenderingTest}: covers the
 * model-level surface (variant identity, component delegation, {@code javaTypeName()} format)
 * that emitter and classifier sites rely on without round-tripping through the build.
 */
@UnitTier
class BatchKeyTest {

    private static final ColumnRef FILM_ID =
        new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef LANGUAGE_ID =
        new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer");
    private static final TableRef FILM_TABLE =
        new TableRef("film", "FILM", "Film", List.of(FILM_ID));
    private static final TableRef LANGUAGE_TABLE =
        new TableRef("language", "LANGUAGE", "Language", List.of(LANGUAGE_ID));

    private static final AccessorRef SINGLE_ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.Payload"),
        "getOwner",
        ClassName.bestGuess("com.example.jooq.tables.records.LanguageRecord"));

    private static final AccessorRef MANY_ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.CreateFilmsPayload"),
        "films",
        ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord"));

    @Test
    void accessorRowKeyedSingleJavaTypeNameForSingleColumnPk() {
        var hop = new JoinStep.LiftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0");
        var bk = new BatchKey.AccessorRowKeyedSingle(hop, SINGLE_ACCESSOR);
        assertThat(bk.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
    }

    @Test
    void accessorRowKeyedManyJavaTypeNameForCompositePk() {
        var compositePkA = new ColumnRef("a_id", "A_ID", "java.lang.Long");
        var compositePkB = new ColumnRef("b_id", "B_ID", "java.lang.Integer");
        var compositeTable = new TableRef("composite", "COMPOSITE", "Composite",
            List.of(compositePkA, compositePkB));
        var hop = new JoinStep.LiftedHop(compositeTable, compositeTable.primaryKeyColumns(), "items_0");
        var bk = new BatchKey.AccessorRowKeyedMany(hop, MANY_ACCESSOR);
        assertThat(bk.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record2<java.lang.Long, java.lang.Integer>>");
    }

    @Test
    void accessorRowKeyedSingleTargetKeyColumnsDelegatesToHop() {
        var hop = new JoinStep.LiftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0");
        var bk = new BatchKey.AccessorRowKeyedSingle(hop, SINGLE_ACCESSOR);
        // Single source of truth: the BatchKey's targetKeyColumns must round-trip to the hop's
        // own list, not a separate copy. LiftedHop's canonical constructor wraps with
        // List.copyOf, so identity is the test under the hop.targetColumns() projection.
        assertThat(bk.targetKeyColumns()).isSameAs(hop.targetColumns());
    }

    @Test
    void accessorRowKeyedManyTargetKeyColumnsDelegatesToHop() {
        var hop = new JoinStep.LiftedHop(FILM_TABLE, List.of(FILM_ID), "films_0");
        var bk = new BatchKey.AccessorRowKeyedMany(hop, MANY_ACCESSOR);
        assertThat(bk.targetKeyColumns()).isSameAs(hop.targetColumns());
    }

    /**
     * The sealed switch over {@link BatchKey.RecordParentBatchKey} is exhaustive across the
     * four permits at compile time. This test is a pattern-matching switch with no
     * {@code default} arm: if a future fifth permit is added without updating the switch, the
     * test source fails to compile, surfacing the gap at the same site (and on the same build)
     * the production switch does. A redundant runtime {@code default -> fail()} would only
     * fire on shapes the compiler already rejects, so it adds no signal.
     */
    /**
     * Pins the post-R61 asymmetry across the seven {@link BatchKey} variants:
     * {@link BatchKey.LifterRowKeyed} stays on {@code RowN<...>} (the lifter return-type
     * contract pins it; deferred to R71); the other six arms produce {@code RecordN<...>}.
     * Captures both the {@code keyElementType()} TypeName and the {@code javaTypeName()} string
     * so a regression on either projection surfaces here, not at a downstream consumer.
     */
    @Test
    void keyElementTypeAsymmetryAcrossSevenVariants() {
        var lifterHop = new JoinStep.LiftedHop(FILM_TABLE, List.of(FILM_ID), "f_0");
        var lifterRef = new LifterRef(ClassName.bestGuess("com.example.Lifters"), "extract");

        BatchKey rowKeyed = new BatchKey.RowKeyed(List.of(FILM_ID));
        BatchKey mappedRowKeyed = new BatchKey.MappedRowKeyed(List.of(FILM_ID));
        BatchKey recordKeyed = new BatchKey.RecordKeyed(List.of(FILM_ID));
        BatchKey mappedRecordKeyed = new BatchKey.MappedRecordKeyed(List.of(FILM_ID));
        BatchKey lifterRowKeyed = new BatchKey.LifterRowKeyed(lifterHop, lifterRef);
        BatchKey accessorSingle = new BatchKey.AccessorRowKeyedSingle(
            new JoinStep.LiftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0"),
            SINGLE_ACCESSOR);
        BatchKey accessorMany = new BatchKey.AccessorRowKeyedMany(
            new JoinStep.LiftedHop(FILM_TABLE, List.of(FILM_ID), "films_0"),
            MANY_ACCESSOR);

        // Six arms produce RecordN keys.
        assertThat(rowKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(mappedRowKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(recordKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(mappedRecordKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(accessorSingle.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(accessorMany.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");

        // LifterRowKeyed stays on RowN until R71.
        assertThat(lifterRowKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Row1<java.lang.Integer>");

        // javaTypeName() mirrors the same asymmetry, with each arm's container axis preserved.
        assertThat(rowKeyed.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
        assertThat(mappedRowKeyed.javaTypeName())
            .isEqualTo("java.util.Set<org.jooq.Record1<java.lang.Integer>>");
        assertThat(recordKeyed.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
        assertThat(mappedRecordKeyed.javaTypeName())
            .isEqualTo("java.util.Set<org.jooq.Record1<java.lang.Integer>>");
        assertThat(accessorSingle.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
        assertThat(accessorMany.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
        assertThat(lifterRowKeyed.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Row1<java.lang.Integer>>");
    }

    @Test
    void recordParentBatchKeyExhaustiveSwitchCompilesAcrossAllFourPermits() {
        BatchKey.RecordParentBatchKey row = new BatchKey.RowKeyed(List.of(FILM_ID));
        BatchKey.RecordParentBatchKey lifter = new BatchKey.LifterRowKeyed(
            new JoinStep.LiftedHop(FILM_TABLE, List.of(FILM_ID), "f_0"),
            new LifterRef(ClassName.bestGuess("com.example.Lifters"), "extract"));
        BatchKey.RecordParentBatchKey accSingle = new BatchKey.AccessorRowKeyedSingle(
            new JoinStep.LiftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0"),
            SINGLE_ACCESSOR);
        BatchKey.RecordParentBatchKey accMany = new BatchKey.AccessorRowKeyedMany(
            new JoinStep.LiftedHop(FILM_TABLE, List.of(FILM_ID), "films_0"),
            MANY_ACCESSOR);

        for (var bk : List.of(row, lifter, accSingle, accMany)) {
            String label = switch (bk) {
                case BatchKey.RowKeyed _ -> "row";
                case BatchKey.LifterRowKeyed _ -> "lifter";
                case BatchKey.AccessorRowKeyedSingle _ -> "accSingle";
                case BatchKey.AccessorRowKeyedMany _ -> "accMany";
            };
            assertThat(label).isNotNull();
        }
    }
}
