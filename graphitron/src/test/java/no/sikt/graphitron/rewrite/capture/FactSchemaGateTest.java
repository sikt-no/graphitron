package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.META_FAMILY;
import static no.sikt.graphitron.model.Tables.META_PREFIXLESS_RELATION;
import static no.sikt.graphitron.model.Tables.META_RELATION_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/**
 * The fact schema's gate family: the invariants the DDL itself cannot state, each as its own
 * named query against a bootstrapped store.
 *
 * <p>These are siblings of the comment-coverage gate rather than detections. Every one of them
 * ranges over something capture controls, so a failure here is a capture bug or a DDL defect,
 * never an author error. The most-cited counterexample is deliberately absent: a repeated
 * application of a non-repeatable directive is author-reachable under registry capture, so it is
 * a detection and has no business failing a build.
 */
@UnitTier
class FactSchemaGateTest {

    /**
     * A slice broad enough for the structural gates: extended types, both field families, a
     * repeatable federation application with its verbatim twin, and applications at all five
     * element sites.
     */
    private static final String FIXTURE = """
        directive @audit(note: String) repeatable on OBJECT | FIELD_DEFINITION | ARGUMENT_DEFINITION | ENUM_VALUE
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT

        type Query {
          films(first: Int = 10, title: String @audit(note: "filter")): [Film!]!
          node: Node
        }

        interface Node { id: ID! }

        type Film implements Node @table(name: "film") @audit(note: "one") @audit(note: "two")
                                  @key(fields: "filmId") @key(fields: "title year") {
          id: ID!
          filmId: ID! @field(name: "film_id")
          title: String @audit
          year: Int
        }

        extend type Film {
          rating: Rating
        }

        enum Rating {
          G @audit(note: "general")
          PG @field(name: "pg")
        }

        input FilmFilter {
          title: String = "any"
          year: Int
        }

        union Searchable = Film
        """;

    @Test
    @DisplayName("every table and every column carries a COMMENT ON")
    void commentCoverageIsTotal() {
        try (var store = GraphitronModelStore.open()) {
            var tables = store.dsl()
                .select(field(name("TABLE_NAME"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "TABLES")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("REMARKS"), String.class).isNull())
                .fetch(0, String.class);
            assertThat(tables).as("uncommented relations").isEmpty();

            var columns = store.dsl()
                .select(field(name("TABLE_NAME"), String.class)
                    .concat(".").concat(field(name("COLUMN_NAME"), String.class)))
                .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("REMARKS"), String.class).isNull())
                .fetch(0, String.class);
            assertThat(columns).as("uncommented columns").isEmpty();
        }
    }

    @Test
    @DisplayName("every relation resolves to one family page or carries an exemption row")
    void everyRelationHasExactlyOneDocumentationHome() {
        try (var store = GraphitronModelStore.open()) {
            var unhoused = store.dsl()
                .select(META_RELATION_FAMILY.RELATION_NAME)
                .from(META_RELATION_FAMILY)
                .where(META_RELATION_FAMILY.PREFIX.isNull())
                .and(META_RELATION_FAMILY.EXEMPTED.isFalse())
                .fetch(0, String.class);
            assertThat(unhoused)
                .as("relations outside every family and carrying no exemption row; add the"
                    + " meta_family row or argue the meta_prefixless_relation exemption in")
                .isEmpty();

            var doublyHoused = store.dsl()
                .select(META_RELATION_FAMILY.RELATION_NAME)
                .from(META_RELATION_FAMILY)
                .where(META_RELATION_FAMILY.PREFIX.isNotNull())
                .and(META_RELATION_FAMILY.EXEMPTED.isTrue())
                .fetch(0, String.class);
            assertThat(doublyHoused)
                .as("exemption rows for relations a family already covers")
                .isEmpty();

            var duplicated = store.dsl()
                .select(META_RELATION_FAMILY.RELATION_NAME)
                .from(META_RELATION_FAMILY)
                .groupBy(META_RELATION_FAMILY.RELATION_NAME)
                .having(count().gt(1))
                .fetch(0, String.class);
            assertThat(duplicated)
                .as("relations the census matched more than once; no family prefix may prefix another")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("every family row covers at least one observed relation")
    void everyFamilyRowHasAnObservedRelation() {
        try (var store = GraphitronModelStore.open()) {
            var vacant = store.dsl()
                .select(META_FAMILY.PREFIX)
                .from(META_FAMILY)
                .whereNotExists(select().from(META_RELATION_FAMILY)
                    .where(META_RELATION_FAMILY.PREFIX.eq(META_FAMILY.PREFIX)))
                .fetch(0, String.class);
            assertThat(vacant)
                .as("family rows no observed relation carries; a roster entry outlived its family")
                .isEmpty();
        }
    }

    /**
     * The keys a table would take from the engine, held by gate instead: the meta relations are
     * views over row values, so prefix and ordinal uniqueness cannot be a PRIMARY KEY, and the
     * census's exact-prefix match is only unambiguous while no family prefix prefixes another.
     */
    @Test
    @DisplayName("the family roster is well-formed: unique prefixes and ordinals, exact-match safe")
    void theFamilyRosterIsWellFormed() {
        try (var store = GraphitronModelStore.open()) {
            var rows = store.dsl()
                .select(META_FAMILY.PREFIX, META_FAMILY.ORDINAL)
                .from(META_FAMILY)
                .fetch();
            assertThat(rows).as("family rows to gate").isNotEmpty();
            var prefixes = rows.map(org.jooq.Record2::value1);
            assertThat(prefixes)
                .as("family prefixes, the roster's key")
                .doesNotHaveDuplicates();
            assertThat(prefixes)
                .as("family prefixes end with their underscore")
                .allSatisfy(prefix -> assertThat(prefix).endsWith("_"));
            var nested = new java.util.ArrayList<String>();
            for (String outer : prefixes) {
                for (String inner : prefixes) {
                    if (!outer.equals(inner) && outer.startsWith(inner)) {
                        nested.add(inner + " prefixes " + outer);
                    }
                }
            }
            assertThat(nested)
                .as("family prefixes that prefix another; the census's exact match relies on none")
                .isEmpty();
            assertThat(rows.map(org.jooq.Record2::value2))
                .as("family ordinals, the reference's page order")
                .doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("every exemption row names an observed relation and a resolvable page")
    void everyExemptionRowResolves() {
        try (var store = GraphitronModelStore.open()) {
            var danglingRelations = store.dsl()
                .select(META_PREFIXLESS_RELATION.RELATION_NAME)
                .from(META_PREFIXLESS_RELATION)
                .whereNotExists(select().from(META_RELATION_FAMILY)
                    .where(META_RELATION_FAMILY.RELATION_NAME
                        .eq(META_PREFIXLESS_RELATION.RELATION_NAME)))
                .fetch(0, String.class);
            assertThat(danglingRelations)
                .as("exemption rows naming relations the schema does not declare")
                .isEmpty();

            var danglingPages = store.dsl()
                .select(META_PREFIXLESS_RELATION.RELATION_NAME)
                .from(META_PREFIXLESS_RELATION)
                .where(META_PREFIXLESS_RELATION.PAGE.isNotNull())
                .andNotExists(select().from(META_FAMILY)
                    .where(META_FAMILY.PREFIX.eq(META_PREFIXLESS_RELATION.PAGE)))
                .fetch(0, String.class);
            assertThat(danglingPages)
                .as("exemption rows whose page is not a family prefix; NULL means the index,"
                    + " anything else must resolve")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("merge ordinals are dense from zero within each type")
    void mergeOrdinalsAreDense(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var gaps = store.dsl()
                .select(GRAPHQL_TYPE_DECLARATION.TYPE_NAME)
                .from(GRAPHQL_TYPE_DECLARATION)
                .groupBy(GRAPHQL_TYPE_DECLARATION.TYPE_NAME)
                .having(max(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL)
                    .ne(count().minus(1)))
                .fetch(0, String.class);
            assertThat(gaps).as("types whose declaration sites do not number densely from 0").isEmpty();
        }
    }

    @Test
    @DisplayName("application ordinals are dense from zero within each coordinate")
    void applicationOrdinalsAreDense(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .groupBy(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME)
                .having(max(GRAPHQL_TYPE_DIRECTIVE.ORDINAL).ne(count().minus(1)))
                .fetch()).as("type-level application ordinals").isEmpty();

            assertThat(store.dsl()
                .select(GRAPHQL_FIELD_DIRECTIVE.TYPE_NAME, GRAPHQL_FIELD_DIRECTIVE.FIELD_NAME,
                    GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME)
                .from(GRAPHQL_FIELD_DIRECTIVE)
                .groupBy(GRAPHQL_FIELD_DIRECTIVE.TYPE_NAME, GRAPHQL_FIELD_DIRECTIVE.FIELD_NAME,
                    GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME)
                .having(max(GRAPHQL_FIELD_DIRECTIVE.ORDINAL).ne(count().minus(1)))
                .fetch()).as("field-level application ordinals").isEmpty();
        }
    }

    @Test
    @DisplayName("the wrapping decode agrees with the captured type expression")
    void wrappingDecodeAgreesWithTypeSdl(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            // The correspondences SQL can express: an outermost '!' is non_null, a leading '['
            // is is_list, and the named type is a substring of the expression. Deeper interior
            // structure is out of a LIKE's reach and stays the decode's own business.
            var disagreements = store.dsl()
                .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.TYPE_SDL)
                .from(GRAPHQL_FIELD)
                .where(GRAPHQL_FIELD.NON_NULL.ne(GRAPHQL_FIELD.TYPE_SDL.like("%!"))
                    .or(GRAPHQL_FIELD.IS_LIST.ne(GRAPHQL_FIELD.TYPE_SDL.like("[%")))
                    .or(GRAPHQL_FIELD.TYPE_SDL.contains(GRAPHQL_FIELD.NAMED_TYPE).isFalse()))
                .fetch();
            assertThat(disagreements).as("fields whose decode contradicts type_sdl").isEmpty();
        }
    }

    @Test
    @DisplayName("default values appear only under INPUT_OBJECT parents")
    void defaultValuesOnlyOnInputFields(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var offenders = store.dsl()
                .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME)
                .from(GRAPHQL_FIELD)
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME))
                .where(GRAPHQL_FIELD.DEFAULT_VALUE_SDL.isNotNull())
                .and(GRAPHQL_TYPE.KIND.ne("INPUT_OBJECT"))
                .fetch();
            assertThat(offenders).as("output fields carrying a default value").isEmpty();
        }
    }

    /**
     * The transcription is total, so an application's name always resolves to a definition. This is
     * the invariant that replaced an exclusion: the graphitron namespace used to be withheld from
     * these relations, which made the family a transcription with a hole in it and pushed a
     * question about {@code source_name} into the choice of table.
     */
    @Test
    @DisplayName("every directive application resolves to a captured definition")
    void everyApplicationResolvesToItsDefinition(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var defined = store.dsl().select(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME).from(GRAPHQL_DIRECTIVE);
            assertThat(store.dsl().fetchCount(GRAPHQL_SCHEMA_DIRECTIVE,
                GRAPHQL_SCHEMA_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_TYPE_DIRECTIVE,
                GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_FIELD_DIRECTIVE,
                GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_ARGUMENT_DIRECTIVE,
                GRAPHQL_ARGUMENT_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_ENUM_VALUE_DIRECTIVE,
                GRAPHQL_ENUM_VALUE_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
        }
    }

    /**
     * The decode is an addition to the transcription, never a substitute for it, so a graphitron
     * application is reachable from both families and a consumer never has to know which one holds
     * a given directive.
     */
    @Test
    @DisplayName("a decoded graphitron application keeps its verbatim row too")
    void theDecodeDoesNotReplaceTheTranscription(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var decoded = store.dsl()
                .select(GRAPHITRON_TABLE.TYPE_NAME)
                .from(GRAPHITRON_TABLE)
                .fetch(GRAPHITRON_TABLE.TYPE_NAME);
            assertThat(decoded).as("the fixture applies @table, so the gate has something to pin")
                .isNotEmpty();
            var verbatim = store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .where(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.eq("table"))
                .fetch(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME);
            assertThat(verbatim).containsExactlyInAnyOrderElementsOf(decoded);
        }
    }

    @Test
    @DisplayName("the federation dual projection agrees with its verbatim twin")
    void federationKeyProjectionsAgree(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var decoded = store.dsl()
                .select(GRAPHITRON_FEDERATION_KEY.TYPE_NAME, GRAPHITRON_FEDERATION_KEY.ORDINAL)
                .from(GRAPHITRON_FEDERATION_KEY)
                .fetch();
            assertThat(decoded).as("the fixture applies @key twice, so the gate has something to pin")
                .hasSize(2);
            var verbatim = store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, GRAPHQL_TYPE_DIRECTIVE.ORDINAL)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .where(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.eq("key"))
                .fetch();
            assertThat(verbatim).containsExactlyInAnyOrderElementsOf(decoded);
        }
    }

    @Test
    @DisplayName("every element hangs off a declaration site of its own type")
    void elementSiteReferencesAreTotal(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            // The FK already guarantees this; the gate exists because a NULL source_name would
            // silently disable it under MATCH SIMPLE, which is the reason that column is NOT NULL.
            var orphans = store.dsl()
                .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME)
                .from(GRAPHQL_FIELD)
                .whereNotExists(select(GRAPHQL_TYPE_DECLARATION.TYPE_NAME)
                    .from(GRAPHQL_TYPE_DECLARATION)
                    .where(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME))
                    .and(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME.eq(GRAPHQL_FIELD.SOURCE_NAME))
                    .and(GRAPHQL_TYPE_DECLARATION.SOURCE_LINE.eq(GRAPHQL_FIELD.DECLARATION_LINE))
                    .and(GRAPHQL_TYPE_DECLARATION.SOURCE_COLUMN.eq(GRAPHQL_FIELD.DECLARATION_COLUMN)))
                .fetch();
            assertThat(orphans).as("fields with no declaration site").isEmpty();
        }
    }

    /**
     * The graph partition dimension, in exemption polarity: every base relation leads its primary
     * key with {@code graph_name} unless its family is deliberately graph-free, so a new family is
     * covered by default and its exemption has to be argued in. Three prefixes are exempt, each
     * for a stated reason. {@code sql_} and {@code jvm_} partition by <em>source</em> rather than
     * by graph (a jar or a generated package is shared between graphs, and which sources make up
     * a graph is a membership question deferred with its first consumer), so the same gate holds
     * them to leading with {@code source_name} instead: the exemption is not key-freedom.
     * {@code store_} is the store's own bookkeeping and answers the question per relation rather
     * than per prefix: {@code store_graph} is keyed on {@code graph_name} and its two recipe
     * children lead with it, while {@code store_source} and {@code store_stamp} carry neither
     * dimension, being store-global by design.
     */
    @Test
    @DisplayName("every base relation leads its key with its partition dimension")
    void everyRelationLeadsWithItsPartitionDimension() {
        try (var store = GraphitronModelStore.open()) {
            var leading = leadingPrimaryKeyColumns(store);
            var offenders = new java.util.ArrayList<String>();
            for (var entry : leading.entrySet()) {
                String table = entry.getKey();
                String column = entry.getValue();
                String expected;
                if (table.startsWith("sql_") || table.startsWith("jvm_")) {
                    expected = "source_name";
                } else if (table.startsWith("store_")) {
                    expected = switch (table) {
                        case "store_graph", "store_graph_schema_input", "store_graph_schema_extension"
                            -> "graph_name";
                        case "store_source" -> "source_name";
                        case "store_stamp" -> "singleton";
                        default -> "graph_name";
                    };
                } else {
                    expected = "graph_name";
                }
                if (!expected.equals(column)) {
                    offenders.add(table + " leads with " + column + ", expected " + expected);
                }
            }
            assertThat(offenders).as("relations keyed without their partition dimension").isEmpty();
        }
    }

    /**
     * A {@code graph_name} column with no foreign-key path to {@code store_graph} is a column the
     * database will not defend: it admits rows naming a graph that was never captured, and the
     * ownership-scoped delete would rely on a value nothing constrains. The presence gate above
     * checks the column is there and leading; this one checks it means something. Walked as a
     * closure over the declared foreign keys (the generated model's own rendering of them,
     * regenerated from the DDL every build) rather than compared against a hand-kept list,
     * because the parentless roots are exactly what an eye misses: nothing references them
     * either, so they read as leaves. Only an edge that itself threads {@code graph_name} counts
     * towards the closure: a graph-keyed relation reaching the anchor solely through some other,
     * graph-free reference would still leave its own {@code graph_name} column unconstrained,
     * which is exactly the hole this gate exists to close. {@code store_graph} itself is the one
     * excluded row, the anchor being unable to reach itself by a foreign key; excluding it
     * silently would read as a gate that passes because it never ran, so it is stated.
     */
    @Test
    @DisplayName("every graph-keyed relation reaches store_graph by foreign key")
    void everyGraphKeyedRelationReachesTheAnchor() {
        var reaches = new java.util.HashSet<org.jooq.Table<?>>();
        reaches.add(no.sikt.graphitron.model.Tables.STORE_GRAPH);
        boolean grew = true;
        var tables = no.sikt.graphitron.model.Public.PUBLIC.getTables();
        while (grew) {
            grew = false;
            for (org.jooq.Table<?> table : tables) {
                if (reaches.contains(table)) {
                    continue;
                }
                var graphField = table.field("GRAPH_NAME", String.class);
                if (graphField == null) {
                    continue;
                }
                for (var reference : table.getReferences()) {
                    // Only a foreign key that itself threads graph_name counts: a graph-keyed
                    // relation reaching its anchor through some other, graph-free reference would
                    // still leave the graph_name column unconstrained, which is the failure this
                    // gate exists to catch.
                    if (reference.getFields().contains(graphField)
                        && reaches.contains(reference.getKey().getTable())) {
                        reaches.add(table);
                        grew = true;
                        break;
                    }
                }
            }
        }
        var unanchored = new java.util.ArrayList<String>();
        for (org.jooq.Table<?> table : tables) {
            boolean graphKeyed = table.field("GRAPH_NAME", String.class) != null
                && table.getOptions().type() != org.jooq.TableOptions.TableType.VIEW
                && !table.equals(no.sikt.graphitron.model.Tables.STORE_GRAPH);
            if (graphKeyed && !reaches.contains(table)) {
                unanchored.add(table.getName());
            }
        }
        assertThat(unanchored).as("graph-keyed relations with no FK path to store_graph").isEmpty();
    }

    /**
     * The two-graph fusion test the partition dimension's motivation promises, doubling as the
     * enforcer for per-run single-graph capture now that a store legitimately holds many graphs:
     * a run writes only under its own graph, and a pre-seeded sibling partition comes out of the
     * run byte-identical.
     */
    @Test
    @DisplayName("a run writes only under its own graph")
    void aRunWritesOnlyUnderItsOwnGraph(@TempDir Path tmp) throws java.io.IOException {
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity("sibling", siblingDir),
                CapturedStore.registryOf(siblingDir, "type Query { actors: [String!]! }"));
            var before = partitionSnapshot(store, "sibling");

            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity("own", ownDir),
                CapturedStore.registryOf(ownDir, FIXTURE));

            assertThat(partitionSnapshot(store, "sibling"))
                .as("the sibling's partition, after another graph's capture")
                .isEqualTo(before);
            for (org.jooq.Table<?> table : no.sikt.graphitron.model.Public.PUBLIC.getTables()) {
                var graphField = table.field("GRAPH_NAME", String.class);
                if (graphField == null
                    || table.getOptions().type() == org.jooq.TableOptions.TableType.VIEW) {
                    continue;
                }
                assertThat(store.dsl().selectDistinct(graphField).from(table).fetch(0, String.class))
                    .as("graph names present in %s", table.getName())
                    .isSubsetOf("sibling", "own");
            }
        }
    }

    /** Every graph-keyed relation's rows for {@code graphName}, rendered stably for comparison. */
    private static java.util.List<String> partitionSnapshot(GraphitronModelStore store, String graphName) {
        var rows = new java.util.ArrayList<String>();
        for (org.jooq.Table<?> table : no.sikt.graphitron.model.Public.PUBLIC.getTables()) {
            var graphField = table.field("GRAPH_NAME", String.class);
            if (graphField == null
                || table.getOptions().type() == org.jooq.TableOptions.TableType.VIEW) {
                continue;
            }
            store.dsl().selectFrom(table).where(graphField.eq(graphName)).fetch()
                .forEach(record -> rows.add(table.getName() + "|" + record));
        }
        java.util.Collections.sort(rows);
        return rows;
    }

    /** The first primary-key column of every base relation, from the booted store's metadata. */
    private static java.util.Map<String, String> leadingPrimaryKeyColumns(GraphitronModelStore store) {
        var leading = new java.util.LinkedHashMap<String, String>();
        store.dsl()
            .select(field(name("TC", "TABLE_NAME"), String.class),
                field(name("KCU", "COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("TC"))
            .join(table(name("INFORMATION_SCHEMA", "KEY_COLUMN_USAGE")).as("KCU"))
            .on(field(name("KCU", "CONSTRAINT_NAME"), String.class)
                .eq(field(name("TC", "CONSTRAINT_NAME"), String.class)))
            .where(field(name("TC", "CONSTRAINT_TYPE"), String.class).eq("PRIMARY KEY"))
            .and(field(name("TC", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("KCU", "ORDINAL_POSITION"), Integer.class).eq(1))
            .fetch()
            .forEach(row -> leading.put(row.value1().toLowerCase(java.util.Locale.ROOT),
                row.value2().toLowerCase(java.util.Locale.ROOT)));
        return leading;
    }
}
