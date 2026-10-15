package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.jooq.JooqCatalog.ColumnEntry;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.test.jooq.nodeidfixture.tables.Bar;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;

/**
 * Coverage for {@link JooqCatalog#nodeIdMetadata(String)} — the synthesized-NodeId metadata probe.
 *
 * <p>Well-formed / absent / catalog-unavailable cases use the synthetic {@code nodeidfixture}
 * catalog: {@link Bar} carries the two metadata constants; {@code qux} does not.
 *
 * <p>Malformed-metadata cases call the package-private {@link JooqCatalog#validateNodeIdMetadata}
 * helper directly with synthetic raw values and a stub column lookup. That keeps each branch
 * testable without having to swap {@code static final} fields on the fixture class.
 */
@UnitTier
class JooqCatalogNodeIdMetadataTest {

    private JooqCatalog catalog() {
        return new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
    }

    // --- Well-formed (fixture) ---

    @Test
    void readsTypeIdAndKeyColumnsFromBar() {
        var result = catalog().nodeIdMetadata("bar");
        assertThat(result).isPresent();
        var meta = result.get();
        assertThat(meta.typeId()).isEqualTo("Bar");
        assertThat(meta.keyColumns())
            .extracting(ColumnRef::sqlName)
            .containsExactly("id_1", "id_2");
    }

    @Test
    void keyColumnOrderPreservesConstantDeclaration() {
        // The order of __NODE_KEY_COLUMNS is load-bearing — encode/decode must pair positionally.
        var meta = catalog().nodeIdMetadata("bar").orElseThrow();
        assertThat(meta.keyColumns()).hasSize(2);
        assertThat(meta.keyColumns().get(0).sqlName()).isEqualTo("id_1");
        assertThat(meta.keyColumns().get(1).sqlName()).isEqualTo("id_2");
    }

    // --- Absent / unavailable (fixture) ---

    @Test
    void returnsEmptyWhenTableHasNoConstants() {
        assertThat(catalog().nodeIdMetadata("qux")).isEmpty();
    }

    @Test
    void returnsEmptyWhenTableIsNotInCatalog() {
        assertThat(catalog().nodeIdMetadata("nonexistent")).isEmpty();
    }

    @Test
    void returnsEmptyWhenCatalogIsUnavailable() {
        assertThat(new JooqCatalog("no.such.package").nodeIdMetadata("bar")).isEmpty();
    }

    // --- Malformed — direct validator coverage ---

    private static final Function<String, Optional<ColumnEntry>> RESOLVE_ID_COLUMNS = name -> {
        if ("id_1".equalsIgnoreCase(name)) return Optional.of(new ColumnEntry("ID_1", "java.lang.String", "id_1", false, "java.lang.String"));
        if ("id_2".equalsIgnoreCase(name)) return Optional.of(new ColumnEntry("ID_2", "java.lang.String", "id_2", false, "java.lang.String"));
        return Optional.empty();
    };

    private static Field<?> field(String name) {
        return DSL.field(DSL.name(name), SQLDataType.VARCHAR);
    }

    private static final Field<?>[] VALID_KEYS = { field("id_1"), field("id_2") };

    @Test
    void malformed_typeIdNull() {
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", null, VALID_KEYS, RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void malformed_typeIdEmptyString() {
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", "", VALID_KEYS, RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void malformed_typeIdWrongType() {
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", 42, VALID_KEYS, RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void malformed_keyColumnsNull() {
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", "Bar", null, RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void malformed_keyColumnsEmpty() {
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", "Bar", new Field<?>[0], RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void malformed_keyColumnsWrongType() {
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", "Bar", "not-an-array", RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void malformed_keyColumnEntryNull() {
        var withNullEntry = new Field<?>[] { field("id_1"), null };
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", "Bar", withNullEntry, RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void malformed_keyColumnDoesNotBelongToTable() {
        var mismatch = new Field<?>[] { field("id_1"), field("not_a_column_on_bar") };
        assertThat(JooqCatalog.validateNodeIdMetadata("bar", "Bar", mismatch, RESOLVE_ID_COLUMNS))
            .isEmpty();
    }

    @Test
    void validatorReturnsColumnRefsInDeclarationOrder() {
        var meta = JooqCatalog.validateNodeIdMetadata("bar", "Bar", VALID_KEYS, RESOLVE_ID_COLUMNS).orElseThrow();
        assertThat(meta.typeId()).isEqualTo("Bar");
        assertThat(meta.keyColumns())
            .extracting(ColumnRef::javaName, ColumnRef::sqlName, ColumnRef::columnClass)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("ID_1", "id_1", "java.lang.String"),
                org.assertj.core.groups.Tuple.tuple("ID_2", "id_2", "java.lang.String"));
    }

    // --- Diagnostic sibling (Step 2): malformed-metadata surfacing ---

    @Test
    void diagnosticEmptyForWellFormedTable() {
        assertThat(catalog().nodeIdMetadataDiagnostic("bar")).isEmpty();
    }

    @Test
    void diagnosticEmptyForAbsentConstants() {
        assertThat(catalog().nodeIdMetadataDiagnostic("qux")).isEmpty();
    }

    @Test
    void diagnosticEmptyForTableNotInCatalog() {
        assertThat(catalog().nodeIdMetadataDiagnostic("nonexistent")).isEmpty();
    }

    // Malformed-diagnostic integration coverage is handled by the direct validateLookup unit
    // tests above. A dedicated "table class with deliberately bad constants" fixture is not
    // worth the maintenance cost — the real NodeIdFixtureGenerator models well-behaved output
    // and nodeIdMetadataDiagnostic itself is a three-line instanceof dispatch over the same
    // NodeIdMetadataLookup sum type the unit tests already exercise.

    // --- The capture's reading, the store's half ---
    //
    // Same technique and the same reason as the validator cases above: the decision is a static
    // over stated values, so every outcome is reachable with synthetic constants instead of a
    // generated class deliberately broken one way per case. What it adds to the validator is that
    // declaring half the pair is an attempt that failed, where the probe reads it as nothing.

    private static JooqCatalog.StatedConstant stated(Object value) {
        return JooqCatalog.StatedConstant.of(value);
    }

    private static final JooqCatalog.StatedConstant ABSENT = JooqCatalog.StatedConstant.absent();

    private static JooqCatalog.NodeIdMetadataLookup state(
            JooqCatalog.StatedConstant typeId, JooqCatalog.StatedConstant keyColumns) {
        return JooqCatalog.stateNodeMetadata("bar", typeId, keyColumns,
            field -> RESOLVE_ID_COLUMNS.apply(field.getName()));
    }

    @Test
    void aUsablePairIsPresentWithItsColumnsInOrder() {
        assertThat(state(stated("Bar"), stated(VALID_KEYS)))
            .isInstanceOfSatisfying(JooqCatalog.NodeIdMetadataLookup.Present.class, p -> {
                assertThat(p.metadata().typeId()).isEqualTo("Bar");
                assertThat(p.metadata().keyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            });
    }

    @Test
    void declaringNeitherConstantIsAbsent() {
        assertThat(state(ABSENT, ABSENT)).isInstanceOf(JooqCatalog.NodeIdMetadataLookup.Absent.class);
    }

    @Test
    void declaringHalfThePairIsAnAttemptThatFailed() {
        assertThat(state(stated("Bar"), ABSENT))
            .isInstanceOf(JooqCatalog.NodeIdMetadataLookup.Malformed.class);
        assertThat(state(ABSENT, stated(VALID_KEYS)))
            .isInstanceOf(JooqCatalog.NodeIdMetadataLookup.Malformed.class);
    }

    @Test
    void aDeclaredPairTheValidatorRefusesIsAnAttemptThatFailed() {
        assertThat(state(stated(""), stated(VALID_KEYS)))
            .isInstanceOf(JooqCatalog.NodeIdMetadataLookup.Malformed.class);
        assertThat(state(stated("Bar"), stated(new Field<?>[] { field("not_a_column_on_bar") })))
            .isInstanceOf(JooqCatalog.NodeIdMetadataLookup.Malformed.class);
    }

    // --- How capture resolves one element: as the column it is, never as a name it spells ---

    private org.jooq.Table<?> table(String name) {
        return catalog().findTable(name).asEntry().orElseThrow().table();
    }

    @Test
    void anElementSpellingAColumnExactlyResolvesToIt() {
        assertThat(JooqCatalog.columnOf(table("bar"), field("id_1")))
            .map(ColumnEntry::sqlName).contains("id_1");
    }

    /** No fold: an element spelled in another case names no column, where the walk's lookup would. */
    @Test
    void anElementSpelledInAnotherCaseResolvesNothing() {
        assertThat(JooqCatalog.columnOf(table("bar"), field("ID_1"))).isEmpty();
    }

    /** A field of another table is no column of this one, whatever it is named. */
    @Test
    void aFieldOfAnotherTableResolvesNothing() {
        assertThat(JooqCatalog.columnOf(table("bar"), table("baz").field("id"))).isEmpty();
    }

    /** The fixture classes: one publishing the pair, one publishing neither. */
    @Test
    void theFixtureClassesReadAsPresentAndAbsent() {
        var catalog = catalog();
        assertThat(catalog.nodeMetadataOf(catalog.findTable("bar").asEntry().orElseThrow().table()))
            .isInstanceOf(JooqCatalog.NodeIdMetadataLookup.Present.class);
        assertThat(catalog.nodeMetadataOf(catalog.findTable("qux").asEntry().orElseThrow().table()))
            .isInstanceOf(JooqCatalog.NodeIdMetadataLookup.Absent.class);
    }
}
