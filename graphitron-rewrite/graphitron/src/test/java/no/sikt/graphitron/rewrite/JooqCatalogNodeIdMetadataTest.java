package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.JooqCatalog.ColumnEntry;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.nodeidfixture.tables.Bar;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

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

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";

    private JooqCatalog catalog() {
        return new JooqCatalog(FIXTURE_JOOQ_PACKAGE);
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
        if ("id_1".equalsIgnoreCase(name)) return Optional.of(new ColumnEntry("ID_1", "java.lang.String", "id_1", false));
        if ("id_2".equalsIgnoreCase(name)) return Optional.of(new ColumnEntry("ID_2", "java.lang.String", "id_2", false));
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
}
