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

    // --- The as-stated reduction, the store's half ---
    //
    // Same technique and the same reason as the validator cases above: the reduction is a static
    // over stated values, so every form is reachable with synthetic constants instead of a
    // generated class deliberately broken one way per case. What it must not do is judge, which
    // is what the empty-string, null-entry and unresolvable-name cases pin.

    private static JooqCatalog.StatedConstant stated(Object value) {
        return JooqCatalog.StatedConstant.of(value);
    }

    private static final JooqCatalog.StatedConstant ABSENT = JooqCatalog.StatedConstant.absent();

    @Test
    void reductionRecordsBothConstantsAsStated() {
        var facts = JooqCatalog.reduceNodeMetadata(stated("Bar"), stated(VALID_KEYS));
        assertThat(facts.typeIdForm()).isEqualTo(JooqCatalog.TypeIdForm.STRING);
        assertThat(facts.typeId()).isEqualTo("Bar");
        assertThat(facts.typeIdClass()).isNull();
        assertThat(facts.keyColumnsForm()).isEqualTo(JooqCatalog.KeyColumnsForm.FIELD_ARRAY);
        assertThat(facts.keyColumnsClass()).isNull();
        assertThat(facts.keyColumnNames()).containsExactly("id_1", "id_2");
    }

    @Test
    void reductionKeepsAnUndeclaredConstantApartFromADeclaredNullOne() {
        var undeclared = JooqCatalog.reduceNodeMetadata(ABSENT, stated(VALID_KEYS));
        assertThat(undeclared.typeIdForm()).isEqualTo(JooqCatalog.TypeIdForm.ABSENT);

        var declaredNull = JooqCatalog.reduceNodeMetadata(stated(null), stated(VALID_KEYS));
        assertThat(declaredNull.typeIdForm()).isEqualTo(JooqCatalog.TypeIdForm.NULL);

        assertThat(undeclared.typeId()).isNull();
        assertThat(declaredNull.typeId())
            .as("both arms carry no value; the form is what tells them apart")
            .isNull();
    }

    @Test
    void reductionNamesTheClassOfAConstantOfAnotherType() {
        var facts = JooqCatalog.reduceNodeMetadata(stated(42), stated("not-an-array"));
        assertThat(facts.typeIdForm()).isEqualTo(JooqCatalog.TypeIdForm.OTHER);
        assertThat(facts.typeIdClass()).isEqualTo("java.lang.Integer");
        assertThat(facts.keyColumnsForm()).isEqualTo(JooqCatalog.KeyColumnsForm.OTHER);
        assertThat(facts.keyColumnsClass()).isEqualTo("java.lang.String");
        assertThat(facts.keyColumnNames()).isEmpty();
    }

    @Test
    void reductionKeepsAnUndeclaredArrayApartFromADeclaredNullOne() {
        assertThat(JooqCatalog.reduceNodeMetadata(stated("Bar"), ABSENT).keyColumnsForm())
            .isEqualTo(JooqCatalog.KeyColumnsForm.ABSENT);
        assertThat(JooqCatalog.reduceNodeMetadata(stated("Bar"), stated(null)).keyColumnsForm())
            .isEqualTo(JooqCatalog.KeyColumnsForm.NULL);
    }

    /** The empty string is a value, not a rejection: the store records it and a derivation judges it. */
    @Test
    void reductionRecordsAnEmptyTypeIdAsTheValueItIs() {
        var facts = JooqCatalog.reduceNodeMetadata(stated(""), stated(VALID_KEYS));
        assertThat(facts.typeIdForm()).isEqualTo(JooqCatalog.TypeIdForm.STRING);
        assertThat(facts.typeId()).isEmpty();
    }

    /** An empty array is the array form with no entries, which is what makes the children structural. */
    @Test
    void reductionRecordsAnEmptyArrayAsTheArrayItIs() {
        var facts = JooqCatalog.reduceNodeMetadata(stated("Bar"), stated(new Field<?>[0]));
        assertThat(facts.keyColumnsForm()).isEqualTo(JooqCatalog.KeyColumnsForm.FIELD_ARRAY);
        assertThat(facts.keyColumnNames()).isEmpty();
    }

    @Test
    void reductionPreservesANullEntryAtItsOwnPosition() {
        var facts = JooqCatalog.reduceNodeMetadata(
            stated("Bar"), stated(new Field<?>[] { field("id_1"), null }));
        assertThat(facts.keyColumnNames()).containsExactly("id_1", null);
    }

    /** A name belonging to no column of the table reduces as spelled; resolving it is not this step. */
    @Test
    void reductionRecordsAnUnresolvableNameAsSpelled() {
        var facts = JooqCatalog.reduceNodeMetadata(
            stated("Bar"), stated(new Field<?>[] { field("not_a_column_on_bar") }));
        assertThat(facts.keyColumnNames()).containsExactly("not_a_column_on_bar");
    }

    /** The one population with no row at all: a class publishing neither constant. */
    @Test
    void aTableStatingNeitherConstantHasNoFactsToRecord() {
        var catalog = catalog();
        assertThat(catalog.findTable("qux").asEntry()).isPresent();
        assertThat(catalog.nodeMetadataFactsOf(catalog.findTable("qux").asEntry().orElseThrow().table()))
            .isEmpty();
        assertThat(catalog.nodeMetadataFactsOf(catalog.findTable("bar").asEntry().orElseThrow().table()))
            .isPresent();
    }
}
