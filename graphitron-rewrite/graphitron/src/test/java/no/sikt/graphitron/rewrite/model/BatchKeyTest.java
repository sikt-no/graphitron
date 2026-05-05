package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.TestFixtures;

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
        TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID));
    private static final TableRef LANGUAGE_TABLE =
        TestFixtures.tableRef("language", "LANGUAGE", "Language", List.of(LANGUAGE_ID));

    private static final AccessorRef SINGLE_ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.Payload"),
        "getOwner",
        ClassName.bestGuess("com.example.jooq.tables.records.LanguageRecord"));

    private static final AccessorRef MANY_ACCESSOR = new AccessorRef(
        ClassName.bestGuess("com.example.CreateFilmsPayload"),
        "films",
        ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord"));

    @Test
    void accessorKeyedSingleJavaTypeNameForSingleColumnPk() {
        var hop = TestFixtures.liftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0");
        var bk = new BatchKey.AccessorKeyedSingle(hop, SINGLE_ACCESSOR);
        assertThat(bk.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
    }

    @Test
    void accessorKeyedManyJavaTypeNameForCompositePk() {
        var compositePkA = new ColumnRef("a_id", "A_ID", "java.lang.Long");
        var compositePkB = new ColumnRef("b_id", "B_ID", "java.lang.Integer");
        var compositeTable = TestFixtures.tableRef("composite", "COMPOSITE", "Composite",
            List.of(compositePkA, compositePkB));
        var hop = TestFixtures.liftedHop(compositeTable, compositeTable.primaryKeyColumns(), "items_0");
        var bk = new BatchKey.AccessorKeyedMany(hop, MANY_ACCESSOR);
        assertThat(bk.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record2<java.lang.Long, java.lang.Integer>>");
    }

    @Test
    void accessorKeyedSingleTargetKeyColumnsDelegatesToHop() {
        var hop = TestFixtures.liftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0");
        var bk = new BatchKey.AccessorKeyedSingle(hop, SINGLE_ACCESSOR);
        // Single source of truth: the BatchKey's targetKeyColumns must round-trip to the hop's
        // own list, not a separate copy. LiftedHop's canonical constructor wraps with
        // List.copyOf, so identity is the test under the hop.targetColumns() projection.
        assertThat(bk.targetKeyColumns()).isEqualTo(hop.targetSideColumns());
    }

    @Test
    void accessorKeyedManyTargetKeyColumnsDelegatesToHop() {
        var hop = TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "films_0");
        var bk = new BatchKey.AccessorKeyedMany(hop, MANY_ACCESSOR);
        assertThat(bk.targetKeyColumns()).isEqualTo(hop.targetSideColumns());
    }

    /**
     * Pins the shape map across the {@link BatchKey} variants:
     * <ul>
     *   <li>{@link BatchKey.RowKeyed}, {@link BatchKey.MappedRowKeyed}, {@link BatchKey.LifterRowKeyed}
     *       → {@code RowN<...>} (developer-facing Row source on the @service classifier path; the
     *       FK-derived @record-parent face on RowKeyed; the lifter contract on LifterRowKeyed).</li>
     *   <li>{@link BatchKey.RecordKeyed}, {@link BatchKey.MappedRecordKeyed},
     *       {@link BatchKey.AccessorKeyedSingle}, {@link BatchKey.AccessorKeyedMany}
     *       → {@code RecordN<...>} (developer-facing Record source on @service; auto-derived from
     *       typed accessors on @record parents).</li>
     *   <li>{@link BatchKey.TableRecordKeyed}, {@link BatchKey.MappedTableRecordKeyed}
     *       → typed {@code TableRecord} subtype (developer wrote {@code Set<X>} / {@code List<X>}
     *       where {@code X extends TableRecord}; the variant carries the typed class).</li>
     * </ul>
     * Captures both the {@code keyElementType()} TypeName and the {@code javaTypeName()} string
     * so a regression on either projection surfaces here, not at a downstream consumer.
     */
    @Test
    void keyElementTypeShapeMapAcrossVariants() {
        var lifterHop = TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "f_0");
        var lifterRef = new LifterRef(ClassName.bestGuess("com.example.Lifters"), "extract");

        BatchKey rowKeyed = new BatchKey.RowKeyed(List.of(FILM_ID));
        BatchKey mappedRowKeyed = new BatchKey.MappedRowKeyed(List.of(FILM_ID));
        BatchKey recordKeyed = new BatchKey.RecordKeyed(List.of(FILM_ID));
        BatchKey mappedRecordKeyed = new BatchKey.MappedRecordKeyed(List.of(FILM_ID));
        BatchKey lifterRowKeyed = new BatchKey.LifterRowKeyed(lifterHop, lifterRef);
        BatchKey accessorSingle = new BatchKey.AccessorKeyedSingle(
            TestFixtures.liftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0"),
            SINGLE_ACCESSOR);
        BatchKey accessorMany = new BatchKey.AccessorKeyedMany(
            TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "films_0"),
            MANY_ACCESSOR);
        BatchKey tableRecordKeyed = new BatchKey.TableRecordKeyed(List.of(FILM_ID),
            no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class);
        BatchKey mappedTableRecordKeyed = new BatchKey.MappedTableRecordKeyed(List.of(FILM_ID),
            no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class);

        // RowN-keyed arms.
        assertThat(rowKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Row1<java.lang.Integer>");
        assertThat(mappedRowKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Row1<java.lang.Integer>");
        assertThat(lifterRowKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Row1<java.lang.Integer>");

        // RecordN-keyed arms.
        assertThat(recordKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(mappedRecordKeyed.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(accessorSingle.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");
        assertThat(accessorMany.keyElementType().toString())
            .isEqualTo("org.jooq.Record1<java.lang.Integer>");

        // Typed-TableRecord-keyed arms reflect the developer's source class identity.
        assertThat(tableRecordKeyed.keyElementType().toString())
            .isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");
        assertThat(mappedTableRecordKeyed.keyElementType().toString())
            .isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");

        // javaTypeName() mirrors the same shape map; container axis preserved per variant.
        assertThat(rowKeyed.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Row1<java.lang.Integer>>");
        assertThat(mappedRowKeyed.javaTypeName())
            .isEqualTo("java.util.Set<org.jooq.Row1<java.lang.Integer>>");
        assertThat(lifterRowKeyed.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Row1<java.lang.Integer>>");
        assertThat(recordKeyed.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
        assertThat(mappedRecordKeyed.javaTypeName())
            .isEqualTo("java.util.Set<org.jooq.Record1<java.lang.Integer>>");
        assertThat(accessorSingle.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
        assertThat(accessorMany.javaTypeName())
            .isEqualTo("java.util.List<org.jooq.Record1<java.lang.Integer>>");
        assertThat(tableRecordKeyed.javaTypeName())
            .isEqualTo("java.util.List<no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord>");
        assertThat(mappedTableRecordKeyed.javaTypeName())
            .isEqualTo("java.util.Set<no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord>");
    }

    /**
     * The sealed switch over {@link BatchKey.RecordParentBatchKey} is exhaustive across the
     * four permits at compile time. This test is a pattern-matching switch with no
     * {@code default} arm: if a future fifth permit is added without updating the switch, the
     * test source fails to compile, surfacing the gap at the same site (and on the same build)
     * the production switch does. A redundant runtime {@code default -> fail()} would only
     * fire on shapes the compiler already rejects, so it adds no signal.
     */
    @Test
    void recordParentBatchKeyExhaustiveSwitchCompilesAcrossAllFourPermits() {
        BatchKey.RecordParentBatchKey row = new BatchKey.RowKeyed(List.of(FILM_ID));
        BatchKey.RecordParentBatchKey lifter = new BatchKey.LifterRowKeyed(
            TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "f_0"),
            new LifterRef(ClassName.bestGuess("com.example.Lifters"), "extract"));
        BatchKey.RecordParentBatchKey accSingle = new BatchKey.AccessorKeyedSingle(
            TestFixtures.liftedHop(LANGUAGE_TABLE, List.of(LANGUAGE_ID), "owner_0"),
            SINGLE_ACCESSOR);
        BatchKey.RecordParentBatchKey accMany = new BatchKey.AccessorKeyedMany(
            TestFixtures.liftedHop(FILM_TABLE, List.of(FILM_ID), "films_0"),
            MANY_ACCESSOR);

        for (var bk : List.of(row, lifter, accSingle, accMany)) {
            String label = switch (bk) {
                case BatchKey.RowKeyed _ -> "row";
                case BatchKey.LifterRowKeyed _ -> "lifter";
                case BatchKey.AccessorKeyedSingle _ -> "accSingle";
                case BatchKey.AccessorKeyedMany _ -> "accMany";
            };
            assertThat(label).isNotNull();
        }
    }
}
