package no.sikt.graphitron.model.jooq;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The layout is {@link JooqCatalog}'s alone. jOOQ writes a codegen pinned to one schema into the
 * root package, and one reading several into a sub-package per schema; every question below is
 * asked of both, over tables of the same shape, and answers alike. Nothing outside the catalog
 * reads a layout, so these are the tests that it does not matter which one a consumer has.
 *
 * <p>The single-schema fixture is generated on its own, pinned to {@code singleschema}; the
 * fixture catalog reads every other schema of the database, and its node and child tables here
 * sit outside {@code public} so the sub-package is not only {@code public_}.
 */
class JooqCatalogLayoutTest {

    /**
     * One generated model and the tables of it the questions are asked over: a node table, a
     * child with a composite key and a foreign key to it, an index, and a table-valued function.
     */
    record Layout(
        String name,
        String jooqPackage,
        String schema,
        String schemaPackage,
        String nodeTable,
        String nodeTypeId,
        List<String> nodeKey,
        String childTable,
        String childClass,
        List<String> childKey,
        String foreignKey,
        List<String> foreignKeyColumns,
        List<String> foreignKeyTargetColumns,
        String indexTable,
        String index,
        List<String> indexColumns,
        String function,
        String functionPackage
    ) {
        JooqCatalog catalog() {
            return new JooqCatalog(jooqPackage);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final String SINGLE = "no.sikt.graphitron.rewrite.singleschemafixture";
    private static final String COMBINED = "no.sikt.graphitron.rewrite.test.jooq";

    static Stream<Layout> layouts() {
        return Stream.of(
            new Layout("one schema, in the root package", SINGLE, "singleschema", SINGLE,
                "solo_parent", "SoloParent", List.of("parent_id"),
                "solo_child", "SoloChild", List.of("parent_id", "seq"),
                "solo_child_parent_id_fkey", List.of("parent_id"), List.of("parent_id"),
                "solo_child", "solo_child_label_idx", List.of("label"),
                "solo_children", SINGLE),
            new Layout("several schemas, a sub-package each", COMBINED, "nodeidfixture",
                COMBINED + ".nodeidfixture",
                "baz", "Baz", List.of("id"),
                "bar", "Bar", List.of("id_1", "id_2"),
                "bar_id_1_fkey", List.of("id_1"), List.of("id"),
                "actor", "idx_actor_last_name", List.of("last_name"),
                "films_for_actor", COMBINED + ".public_"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void aTableIsFoundBareAndQualified(Layout layout) {
        var catalog = layout.catalog();
        var bare = catalog.findTable(layout.childTable());
        assertThat(bare).isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
        var entry = ((JooqCatalog.TableResolution.Resolved) bare).entry();
        assertThat(catalog.findTable(layout.schema(), layout.childTable()))
            .hasValueSatisfying(qualified -> assertThat(qualified.table()).isSameAs(entry.table()));
        assertThat(entry.tableClassName())
            .isEqualTo(layout.schemaPackage() + ".tables." + layout.childClass());
        assertThat(entry.recordClassName())
            .isEqualTo(layout.schemaPackage() + ".tables.records." + layout.childClass() + "Record");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void aSchemasClassesAreFoundInItsOwnPackage(Layout layout) {
        var catalog = layout.catalog();
        var schema = table(catalog, layout.childTable()).getSchema();
        assertThat(JooqCatalog.schemaPackageOf(schema)).hasValue(layout.schemaPackage());
        assertThat(catalog.keysClassFqn(schema)).hasValue(layout.schemaPackage() + ".Keys");
        assertThat(catalog.tablesClassFqn(schema)).hasValue(layout.schemaPackage() + ".Tables");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void aCompositePrimaryKeyKeepsItsOrder(Layout layout) {
        var catalog = layout.catalog();
        assertThat(catalog.candidateKeys(table(catalog, layout.childTable())))
            .filteredOn(JooqCatalog.KeyEntry::primary)
            .singleElement()
            .satisfies(pk -> assertThat(pk.columns()).extracting(JooqCatalog.ColumnEntry::sqlName)
                .containsExactlyElementsOf(layout.childKey()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void aForeignKeyNamesItsColumnsOnBothSides(Layout layout) {
        var catalog = layout.catalog();
        assertThat(catalog.foreignKeyFactsOf(table(catalog, layout.childTable())))
            .filteredOn(fk -> fk.constraintName().equals(layout.foreignKey()))
            .singleElement()
            .satisfies(fk -> {
                assertThat(fk.targetTable()).isEqualTo(layout.schema() + "." + layout.nodeTable());
                assertThat(fk.columns()).containsExactlyElementsOf(layout.foreignKeyColumns());
                assertThat(fk.targetColumns()).containsExactlyElementsOf(layout.foreignKeyTargetColumns());
            });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void anIndexNamesItsColumns(Layout layout) {
        var catalog = layout.catalog();
        assertThat(catalog.indexFactsOf(table(catalog, layout.indexTable())))
            .contains(new JooqCatalog.IndexFacts(layout.index(), layout.indexColumns()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void nodeMetadataIsReadOffTheTableClass(Layout layout) {
        var catalog = layout.catalog();
        assertThat(catalog.nodeMetadataOf(table(catalog, layout.nodeTable())))
            .isInstanceOfSatisfying(JooqCatalog.NodeIdMetadataLookup.Present.class, present -> {
                assertThat(present.metadata().typeId()).isEqualTo(layout.nodeTypeId());
                assertThat(present.metadata().keyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactlyElementsOf(layout.nodeKey());
            });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    void aTableValuedFunctionIsCalledThroughItsSchemasRoutines(Layout layout) {
        assertThat(layout.catalog().resolveTableValuedFunction(layout.function()))
            .isInstanceOfSatisfying(JooqCatalog.RoutineResolution.Resolved.class, resolved ->
                assertThat(resolved.routinesClassName())
                    .isEqualTo(layout.functionPackage() + ".Routines"));
    }

    private static org.jooq.Table<?> table(JooqCatalog catalog, String name) {
        var resolution = catalog.findTable(name);
        assertThat(resolution).as(name).isInstanceOf(JooqCatalog.TableResolution.Resolved.class);
        return ((JooqCatalog.TableResolution.Resolved) resolution).entry().table();
    }
}
