package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.TestRunContext;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import no.sikt.graphitron.model.derive.StageAnswerAgreementTest;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SUPERGRAPH;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FEDERATION_KEY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.META_FAMILY;
import static no.sikt.graphitron.model.Tables.META_GATHERER;
import static no.sikt.graphitron.model.Tables.META_STAGE;
import static no.sikt.graphitron.model.Tables.META_PREFIXLESS_RELATION;
import static no.sikt.graphitron.model.Tables.META_RELATION_FAMILY;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;
import no.sikt.graphitron.model.diagnostics.BuildWarningFacts;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;

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
@org.junit.jupiter.api.Order(3)
class FactSchemaGateTest {

    /** The empty store the gates that read only the schema's own metadata share. */
    @org.junit.jupiter.api.extension.RegisterExtension
    static final FactStores.ClassStore STORE = FactStores.perClass();

    /**
     * The slice the supergraph case captures under each graph: extended types, both field
     * families, and repeated applications, so each graph's partition holds rows beside its
     * membership.
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

    /**
     * The slice the stage gate needs, which is a different slice: a table spelling for
     * {@code graphitron_spelled_table} to resolve against the catalog, a {@code @routine} application
     * carrying an {@code argMapping} so {@code graphitron_argmapping_site} has a row, and a
     * {@code @nodeId} argument whose decode actually walks a foreign key so the two decode targets
     * are non-empty. Separate from {@code FIXTURE} because that one wants breadth of declaration
     * sites, and this one wants every stage-written table to be non-empty.
     *
     * <p>Populating all of them is a property of this fixture and the fixture catalog together, and
     * it has to be maintained as stages are added: a table the fixture leaves empty makes
     * the gate assert nothing about it, which is why the gate names the relation rather than merely
     * comparing what it found.
     *
     * <p>{@code inCategory} is the one that walks. Its scope table is {@code film_category}, the
     * node type's is {@code category}, and exactly one foreign key connects them, which is what the
     * unwritten-path resolution demands. The obvious shorter pairing does not work: {@code film}
     * declares two foreign keys to {@code language}, so a decode between them is ambiguous and
     * contributes no hop at all. {@code sequelTo} stays for the instruction population's sake and
     * contributes no hop either, its own type being what it binds against.
     */
    private static final String STAGE_FIXTURE = """
        type Query {
          films(sequelTo: ID @nodeId(typeName: "Film")): [Film!]!
          filmCategories(
            inCategory: ID @nodeId(typeName: "Category"),
            filter: FilmCategoryFilter,
            byFilmTitle: String @reference(path: [{table: "film"}]) @field(name: "title")
          ): [FilmCategory!]!
          categories: [Category!]!
          filmsForActor(actorId: ID!, minLength: Int): [Film!]!
            @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
        }

        type Mutation {
          actorFilms(actorId: ID!, minLength: Int): ActorFilmsPayload
            @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
        }

        type ActorFilmsFailed @error(handlers: [{
            handler: GENERIC,
            className: "org.jooq.exception.IntegrityConstraintViolationException"
          }]) {
          path: [String!]!
          message: String!
        }

        union ActorFilmsError = ActorFilmsFailed

        type ActorFilmsPayload {
          films: [Film]
          errors: [ActorFilmsError]
        }

        input FilmCategoryFilter {
          lastUpdate: String @field(name: "last_update")
        }

        type Film @table(name: "film") @node {
          filmId: ID! @field(name: "film_id")
          title: String
        }

        type FilmCategory @table(name: "film_category") {
          film: Film @reference(path: [{table: "film"}])
        }

        type Category @table(name: "category") @node {
          categoryId: ID! @field(name: "category_id")
          name: String
        }
        """;

    @Test
    @DisplayName("every table and every column carries a COMMENT ON")
    void commentCoverageIsTotal() {
        var store = STORE.handle();
        var tables = store.dsl()
            .select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("REMARKS"), String.class).isNull())
            .fetch(0, String.class);
        assertThat(tables).as("uncommented relations").isEmpty();

        // A view's columns are left out: H2 2.5 refuses a comment on a view's column, and the
        // graph node "A view's column prose lives where the store keeps it" widens this back.
        var columns = store.dsl()
            .select(field(name("TABLE_NAME"), String.class)
                .concat(".").concat(field(name("COLUMN_NAME"), String.class)))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).notIn(
                select(field(name("TABLE_NAME"), String.class))
                    .from(table(name("INFORMATION_SCHEMA", "TABLES")))
                    .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                    .and(field(name("TABLE_TYPE"), String.class).eq("VIEW"))))
            .and(field(name("REMARKS"), String.class).isNull())
            .fetch(0, String.class);
        assertThat(columns).as("uncommented columns").isEmpty();
    }

    @Test
    @DisplayName("every relation resolves to one family page or carries an exemption row")
    void everyRelationHasExactlyOneDocumentationHome() {
        var store = STORE.handle();
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

    @Test
    @DisplayName("every family row covers at least one observed relation")
    void everyFamilyRowHasAnObservedRelation() {
        var store = STORE.handle();
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

    /**
     * The keys a table would take from the engine, held by gate instead: the meta relations are
     * views over row values, so prefix and ordinal uniqueness cannot be a PRIMARY KEY, and the
     * census's exact-prefix match is only unambiguous while no family prefix prefixes another.
     */
    @Test
    @DisplayName("the family roster is well-formed: unique prefixes and ordinals, exact-match safe")
    void theFamilyRosterIsWellFormed() {
        var store = STORE.handle();
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

    @Test
    @DisplayName("every exemption row names an observed relation and a resolvable page")
    void everyExemptionRowResolves() {
        var store = STORE.handle();
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

    /**
     * The gatherer roster names each gatherer's class precisely so it cannot become a second
     * vocabulary that outlives the code, and this is the gate that makes the claim true: a
     * renamed or deleted gatherer fails here until its row is repointed. The roster and the
     * classes it names are both this module's, so the gate resolves each name on the test
     * classpath.
     */
    @Test
    @DisplayName("every gatherer row names a Java class that exists")
    void everyGathererRowNamesAClassThatExists() {
        var store = STORE.handle();
        var rows = store.dsl()
            .select(META_GATHERER.GATHERER_NAME, META_GATHERER.GATHERER_CLASS)
            .from(META_GATHERER)
            .fetch();
        assertThat(rows).as("gatherer rows to gate").isNotEmpty();
        var dangling = new java.util.ArrayList<String>();
        for (var row : rows) {
            try {
                Class.forName(row.value2(), false, FactSchemaGateTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                dangling.add(row.value1() + " names " + row.value2());
            }
        }
        assertThat(dangling)
            .as("gatherer rows naming classes the reactor no longer declares;"
                + " repoint the row at the class that took the job")
            .isEmpty();
    }

    /**
     * The stage roster is held to the code as the gatherer roster is: a renamed or deleted stage
     * class fails here until its {@code meta_stage} row is repointed.
     */
    @Test
    @DisplayName("every stage row names a Java class that exists")
    void everyStageRowNamesAClassThatExists() {
        var store = STORE.handle();
        var rows = store.dsl()
            .select(META_STAGE.STAGE_NAME, META_STAGE.STAGE_CLASS)
            .from(META_STAGE)
            .fetch();
        assertThat(rows).as("stage rows to gate").isNotEmpty();
        var dangling = new java.util.ArrayList<String>();
        for (var row : rows) {
            try {
                Class.forName(row.value2(), false, FactSchemaGateTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                dangling.add(row.value1() + " names " + row.value2());
            }
        }
        assertThat(dangling)
            .as("stage rows naming classes the reactor no longer declares;"
                + " repoint the row at the class that took the job")
            .isEmpty();
    }

    /**
     * Everything hanging off an anchor the corpus can stop declaring goes when the anchor goes,
     * and the key is what says so.
     *
     * <p>The document gatherers sweep the {@code graphql_} coordinates near the head of the pass:
     * a coordinate this reading did not find is deleted there, before most of the writers keyed
     * into it have run. Nothing downstream can order itself against that, so a dependent row whose
     * key merely restricts turns an author deleting a field into a referential failure, and the
     * run dies rather than recording the deletion. Four consecutive builds found this one relation
     * at a time, each fix exposing the next key one level further out, which is what a rule stated
     * as a list of fixes does.
     *
     * <p>So it is stated as a closure instead: transitively, from the anchors outward. Keys inside
     * the {@code graphql_} family itself are out of scope, being one gatherer's own sweep to order
     * against itself, which it does. {@code store_graph} is out of scope too and for a stronger
     * reason: its row is upserted and never deleted, so there is nothing for its keys to cascade.
     */
    @Test
    @DisplayName("every key reachable from the graphql_ anchors deletes with them")
    void everyKeyReachableFromTheAnchorsCascades() {
        var store = STORE.handle();
        var parents = new java.util.HashMap<String, List<String>>();
        var rules = new java.util.HashMap<String, String>();
        store.dsl().resultQuery("""
                select fk.table_name, pk.table_name, rc.delete_rule, rc.constraint_name
                from information_schema.referential_constraints rc
                join information_schema.table_constraints fk
                  on fk.constraint_name = rc.constraint_name
                join information_schema.table_constraints pk
                  on pk.constraint_name = rc.unique_constraint_name
                """)
            .fetch()
            .forEach(row -> {
                String child = String.valueOf(row.get(0)).toLowerCase(Locale.ROOT);
                String parent = String.valueOf(row.get(1)).toLowerCase(Locale.ROOT);
                parents.computeIfAbsent(child, key -> new java.util.ArrayList<>()).add(parent);
                if (!"CASCADE".equals(String.valueOf(row.get(2)))) {
                    rules.put(child + " -> " + parent, String.valueOf(row.get(3)));
                }
            });
        assertThat(parents)
            .as("the schema's foreign keys, read back; an empty read would pass vacuously")
            .isNotEmpty();

        var closure = parents.keySet().stream().filter(t -> t.startsWith("graphql_"))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        parents.values().forEach(list -> list.stream().filter(t -> t.startsWith("graphql_"))
            .forEach(closure::add));
        for (boolean grew = true; grew; ) {
            grew = false;
            for (var entry : parents.entrySet()) {
                if (!closure.contains(entry.getKey())
                    && entry.getValue().stream().anyMatch(closure::contains)) {
                    closure.add(entry.getKey());
                    grew = true;
                }
            }
        }

        var restricting = rules.keySet().stream()
            .filter(key -> {
                String child = key.substring(0, key.indexOf(" -> "));
                String parent = key.substring(key.indexOf(" -> ") + 4);
                return !child.startsWith("graphql_") && closure.contains(parent)
                    && !"store_graph".equals(parent);
            })
            .sorted()
            .toList();
        assertThat(restricting)
            .as("keys into a relation the anchor sweep can delete, which merely restrict. The"
                + " sweep runs before the writers keyed into it, so a restricting key here"
                + " fails the run on the author edit it is supposed to record. Declare"
                + " ON DELETE CASCADE on each.")
            .isEmpty();
    }

    /**
     * The graph partition dimension, in exemption polarity: every base relation leads its primary
     * key with {@code graph_name} unless its family is deliberately graph-free, so a new family is
     * covered by default and its exemption has to be argued in. Five prefixes are exempt, each
     * for a stated reason. {@code sql_} and {@code code_} partition by <em>source</em>
     * rather than by graph (a jar or a generated package is shared between graphs, and which
     * sources make up a graph is a membership question deferred with its first consumer), so the
     * same gate holds them to leading with {@code source_name} instead: the exemption is not
     * key-freedom. For {@code code_} the argument is the sharing itself: what a classpath entry
     * declares is true of the entry whoever reads it, so a second graph reading the same entry
     * reads the same rows rather than a copy of them, and a reading replaces the rows of the
     * entries it read and no others. The one graph-keyed relation in the family is
     * {@code code_graph_class}, which class a graph's name resolves to: a question about the graph's
     * classpath order rather than about any entry, so it leads with {@code graph_name}, written by
     * the code gatherer's anchor stage over the entry rows.
     * {@code federation_} leads with {@code source_name} on the same terms: the federation library
     * is one source every graph claims, and what it supports is true of it whoever reads it.
     * {@code java_} partitions by source too, at the grain its refresh runs at, and its dimension
     * is spelled {@code file} rather than {@code source_name} on purpose: a source file is not a
     * {@code store_source} row, and two columns with one name that never join would read as though
     * they did. {@code java_source_root} leads with {@code source_root}, the walked root being the
     * scope a walk owns and the grain that relation is at.
     * {@code store_} is the store's own bookkeeping and answers the question per relation rather
     * than per prefix: {@code store_graph} is keyed on {@code graph_name} and its two recipe
     * children lead with it, while {@code store_source} and {@code store_stamp} carry neither
     * dimension, being store-global by design.
     * {@code meta_} answers per relation for the same reason: its rows describe the schema itself
     * and no run's partition, so a keyed one leads with its own subject. The family had no keyed
     * relation at all until a register since dropped, so this arm is a hole that register opened
     * rather than a rule it breaks.
     */
    @Test
    @DisplayName("every base relation leads its key with its partition dimension")
    void everyRelationLeadsWithItsPartitionDimension() {
        var store = STORE.handle();
        var leading = leadingPrimaryKeyColumns(store);
        var stated = statedRelations(store);
        var offenders = new java.util.ArrayList<String>();
        for (var entry : leading.entrySet()) {
            String table = entry.getKey();
            String column = entry.getValue();
            String expected;
            // A stated relation holds rows this file supplies, so there is no graph to keep
            // apart and its key is its own vocabulary's: which one that is varies by relation
            // and is not this gate's to know. The claim inverts rather than lapsing, because
            // leading with the partition dimension here would mean a relation partitioned by
            // something its rows cannot vary by.
            if (stated.contains(table)) {
                if ("graph_name".equals(column)) {
                    offenders.add(table + " states its own rows yet leads with graph_name");
                }
                continue;
            }
            if ("code_graph_class".equals(table)) {
                expected = "graph_name";
            } else if (table.startsWith("sql_")
                || table.startsWith("code_")
                || table.startsWith("federation_")) {
                expected = "source_name";
            } else if (table.startsWith("java_")) {
                expected = "java_source_root".equals(table) ? "source_root" : "file";
            } else if (table.startsWith("store_")) {
                expected = switch (table) {
                    case "store_graph", "store_graph_schema_input", "store_graph_schema_extension"
                        -> "graph_name";
                    case "store_source", "store_class_file" -> "source_name";
                    case "store_stamp" -> "singleton";
                    default -> "graph_name";
                };
            } else if (table.startsWith("meta_")) {
                expected = switch (table) {
                    case "meta_corpus" -> "corpus_name";
                    case "meta_gatherer", "meta_gatherer_corpus", "meta_gatherer_dependency"
                        -> "gatherer_name";
                    case "meta_grain" -> "grain_name";
                    case "meta_stage", "meta_stage_write" -> "stage_name";
                    case "meta_relation" -> "relation_name";
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

    /** The relations the schema supplies the rows of, which carry no graph partition. */
    private static java.util.Set<String> statedRelations(GraphitronModelStore store) {
        return new java.util.HashSet<>(store.dsl()
            .select(no.sikt.graphitron.model.Tables.META_STATED_RELATION.RELATION_NAME)
            .from(no.sikt.graphitron.model.Tables.META_STATED_RELATION)
            .fetch(0, String.class));
    }

    /**
     * A content stamp says what an input's bytes were; it cannot say when they were read, and a
     * reader deciding whether it may skip re-reading needs both. The two therefore travel as a
     * pair: a relation that partitions by input and records a stamp records a {@code read_at}
     * beside it.
     *
     * <p>Stated as a gate rather than left to each family, because the failure mode is silent. A
     * family that gains a stamp later and no currency column beside it does not break: it simply
     * falls outside the mechanism, is re-read on every round for the life of every session, and
     * nothing says so.
     */
    @Test
    @DisplayName("every relation carrying a content stamp carries a read_at beside it")
    void currencyAccompaniesEveryStamp() {
        var offenders = new java.util.ArrayList<String>();
        for (org.jooq.Table<?> table : no.sikt.graphitron.model.Public.PUBLIC.getTables()) {
            if (column(table, "stamp") && !column(table, "read_at")) {
                offenders.add(table.getName());
            }
        }
        assertThat(offenders)
            .as("relations whose stamp has no currency beside it, so nothing can ever skip"
                + " re-reading them")
            .isEmpty();
    }

    /** Whether {@code table} declares a column of that name, however the dialect cases it. */
    private static boolean column(org.jooq.Table<?> table, String name) {
        for (org.jooq.Field<?> field : table.fields()) {
            if (field.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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
     * run byte-identical, its marks included.
     *
     * <p>This is where a stage's scoping is held: both graphs capture the stage fixture over one
     * catalog, so the sibling's stage-written tables hold rows and the snapshot covers them, inputs
     * and stages alike. An unpopulated sibling cannot tell "left the sibling alone" from "there was
     * nothing to disturb", which the populated-stages assertion refuses. That each stage table holds
     * exactly its rule's rows, per graph, is {@code StageAnswerAgreementTest}'s, over every fact
     * document's graph.
     *
     * <p>It holds a refresh too: a second capture of the own graph, under a revised recipe, leaves
     * the sibling's partition as it was, its {@code store_graph} row and recipe included, while
     * the own graph's recipe is rewritten by its own run. And it holds a catalog source no run
     * reads: rows seeded under a foreign source keep their whole partition through both captures.
     */
    @Test
    @DisplayName("a run writes only under its own graph")
    void aRunWritesOnlyUnderItsOwnGraph(@TempDir Path tmp) throws java.io.IOException {
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        try (var store = GraphitronModelStore.open()) {
            var dsl = store.dsl();
            captureStageFixture(dsl, "sibling", siblingDir);
            // The tables whose rows name the stage that wrote them are the defects, which the walk
            // writes beside the rules; the rest a capture fills from its rules alone.
            var stages = StageAnswerAgreementTest.stages(dsl).stream()
                .filter(stage -> !columnNames(dsl, stage.target()).contains("STAGE"))
                .filter(stage -> !StageAnswerAgreementTest.WALK_WRITTEN.contains(stage.target()))
                .toList();
            assertThat(stages.stream()
                    .filter(stage -> !materializedRows(dsl, List.of(stage.target()), stage, "sibling").isEmpty())
                    .count())
                .as("stages the fixture populates in the sibling graph; with none, the snapshot below"
                    + " has no stage rows to disturb")
                .isPositive();
            var before = partitionSnapshot(store, "sibling");
            var siblingGraph = graphRow(dsl, "sibling");
            var siblingRecipe = recipeRows(dsl, "sibling");
            assertThat(siblingRecipe).as("the sibling's recipe rows, which the refresh must leave")
                .isNotEmpty();

            // Rows of a catalog source no run reads: well-formed and foreign is all they need be.
            String foreign = "com.example.othermodule.jooq";
            dsl.insertInto(STORE_SOURCE, STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.SOURCE_KIND)
                .values(foreign, "JOOQ_SCHEMA")
                .execute();
            dsl.insertInto(SQL_SCHEMA, SQL_SCHEMA.SOURCE_NAME, SQL_SCHEMA.TABLE_SCHEMA,
                    SQL_SCHEMA.SCHEMA_PACKAGE)
                .values(foreign, "public", "com.example.othermodule.jooq.public_").execute();
            dsl.insertInto(SQL_ROUTINE, SQL_ROUTINE.SOURCE_NAME, SQL_ROUTINE.TABLE_SCHEMA,
                    SQL_ROUTINE.ROUTINE_NAME, SQL_ROUTINE.ROUTINE_TYPE)
                .values(foreign, "public", "reported_films", "FUNCTION").execute();

            captureStageFixture(dsl, "own", ownDir);

            assertThat(partitionSnapshot(store, "sibling"))
                .as("the sibling's partition, after another graph's capture")
                .isEqualTo(before);
            var ownRecipe = recipeRows(dsl, "own");
            assertThat(ownRecipe).as("the own graph's recipe rows, before its refresh").isNotEmpty();

            // The refresh: the same file under a tag, so the recipe the run writes differs.
            var tagged = SubjectConfig.of(new SchemaRecipe(ownDir.resolve("pom.xml"),
                List.of(new SchemaRecipe.Binding(
                    new SchemaRecipe.Entry.Literal(SchemaSource.file(CapturedStore.fixtureFile(ownDir))),
                    java.util.Optional.of("v2"), java.util.Optional.empty())),
                List.of("graphqls")));
            captureStageFixture(dsl, "own", ownDir, tagged);

            assertThat(partitionSnapshot(store, "sibling"))
                .as("the sibling's partition, after another graph's refresh")
                .isEqualTo(before);
            assertThat(graphRow(dsl, "sibling"))
                .as("the sibling's store_graph row, after another graph's refresh")
                .isEqualTo(siblingGraph);
            assertThat(recipeRows(dsl, "sibling"))
                .as("the sibling's recipe rows, after another graph's refresh")
                .isEqualTo(siblingRecipe);
            assertThat(dsl.select(STORE_GRAPH_SCHEMA_INPUT.TAG).from(STORE_GRAPH_SCHEMA_INPUT)
                .where(STORE_GRAPH_SCHEMA_INPUT.GRAPH_NAME.eq("own")).fetch(STORE_GRAPH_SCHEMA_INPUT.TAG))
                .as("the own graph's recipe, written fresh by its own run")
                .containsExactly("v2");
            assertThat(dsl.fetchCount(SQL_SCHEMA, SQL_SCHEMA.SOURCE_NAME.eq(foreign)))
                .as("the unread source's schema row, after two captures that never read it")
                .isEqualTo(1);
            assertThat(dsl.fetchCount(SQL_ROUTINE, SQL_ROUTINE.SOURCE_NAME.eq(foreign)))
                .as("and the routine row under it, on the same terms; a partition kept by halves"
                    + " reads as a schema that declares no routine")
                .isEqualTo(1);
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

    /**
     * A capture that populates the stage-written tables, which is a property of the fixture SDL and
     * the fixture catalog together. The catalog
     * is what {@code graphitron_spelled_table} resolves its table spellings against; the
     * {@code @routine} application is what gives {@code graphitron_argmapping_site} a row; the
     * {@code @reference} whose element names a table a
     * foreign key reaches is what puts one in {@code graphitron_join_path_step}, as the
     * {@code byFilmTitle} argument's own {@code @reference} does from the argument's side. Without any
     * of them {@link #aRunWritesOnlyUnderItsOwnGraph} would snapshot empty
     * stage tables, which is exactly what its populated-stages assertion refuses to let happen quietly.
     */
    private static void captureStageFixture(DSLContext dsl, String graphName,
                                                      Path directory) {
        captureStageFixture(dsl, graphName, directory, CapturedStore.corpusOf(directory));
    }

    /** {@link #captureStageFixture(DSLContext, String, Path)} under the recipe {@code corpus} states. */
    private static void captureStageFixture(DSLContext dsl, String graphName, Path directory,
                                            SubjectConfig corpus) {
        CapturedStore.registryOf(directory, STAGE_FIXTURE);
        CapturedStore.capture(dsl, new GraphIdentity(graphName, directory), corpus, fixtureCatalog());
    }

    /** The generated catalog the stage fixture resolves against; a composite key lives in it. */
    private static CatalogInput fixtureCatalog() {
        var ctx = TestRunContext.of();
        return CatalogInput.of(ctx);
    }

    /**
     * The rows the relations hold for one graph together, distinct and sorted, as strings, over the
     * columns the table and every rule view writing it carry. Content rather than order: a target is
     * the set its views fill, and neither side promises an order; a supertype several rules write
     * carries the rule's key and not its payload, which its subtype compares.
     */
    private static List<String> materializedRows(DSLContext dsl, List<String> relationNames,
                                                 StageAnswerAgreementTest.Stage stage, String graphName) {
        var shared = new java.util.ArrayList<>(columnNames(dsl, stage.target()));
        stage.ruleViews().forEach(view -> shared.retainAll(columnNames(dsl, view)));
        var columns = shared.stream().map(column -> field(name(column))).toList();
        var graph = field(name("GRAPH_NAME"), String.class);
        var rows = new java.util.TreeSet<String>();
        for (String relationName : relationNames) {
            var relation = table(name(relationName.toUpperCase(Locale.ROOT)));
            // A rule view read through the projection its table's NOT NULL columns state.
            var held = relationName.equals(stage.target()) ? org.jooq.impl.DSL.noCondition()
                : StageAnswerAgreementTest.projection(dsl, stage.target(), columns);
            var query = dsl.select(columns).from(relation).where(held);
            rows.addAll(columnNames(dsl, relationName).contains("GRAPH_NAME")
                ? query.and(graph.eq(graphName)).fetch(Record::toString)
                : query.fetch(Record::toString));
        }
        return List.copyOf(rows);
    }

    private static List<String> columnNames(DSLContext dsl, String relationName) {
        return dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(relationName.toUpperCase(Locale.ROOT)))
            .orderBy(field(name("ORDINAL_POSITION")))
            .fetch(0, String.class);
    }

    /**
     * The supergraph declaration's enforcer, extending the fusion fixture above because the claim it
     * pins is about several graphs in one store. Three graphs, two declaring one supergraph and one
     * declaring nothing: the membership rows round-trip through capture, the peer question asked at
     * the SQL level (a self-join over {@code supergraph_name}) returns exactly the declared sibling
     * for each of the two and nothing for the third, and the presence semantics are pinned where the
     * join holds them, a graph without a row grouping with nothing.
     *
     * <p>The presence semantics are pinned at the SQL level rather than through a view on purpose: no
     * view lands ahead of its reader, and the peer surface's projection is that reader item's design
     * space. What this case owes it is that the join it will be built on already behaves.
     */
    @Test
    @DisplayName("declared supergraph membership scopes the peer set, and absence groups with nothing")
    void supergraphMembershipScopesThePeerSet(@TempDir Path tmp) throws java.io.IOException {
        Path aDir = java.nio.file.Files.createDirectories(tmp.resolve("a"));
        Path bDir = java.nio.file.Files.createDirectories(tmp.resolve("b"));
        Path loneDir = java.nio.file.Files.createDirectories(tmp.resolve("lone"));
        try (var store = GraphitronModelStore.open()) {
            captureUnder(store, "a", aDir, "checkout-supergraph");
            captureUnder(store, "b", bDir, "checkout-supergraph");
            captureUnder(store, "lone", loneDir, null);

            assertThat(store.dsl().select(STORE_GRAPH_SUPERGRAPH.GRAPH_NAME,
                    STORE_GRAPH_SUPERGRAPH.SUPERGRAPH_NAME)
                .from(STORE_GRAPH_SUPERGRAPH).orderBy(STORE_GRAPH_SUPERGRAPH.GRAPH_NAME).fetch())
                .as("only graphs with a declared supergraph are registered")
                .extracting(r -> r.value1() + "->" + r.value2())
                .containsExactly("a->checkout-supergraph", "b->checkout-supergraph");

            assertThat(peersOf(store, "a")).containsExactly("b");
            assertThat(peersOf(store, "b")).containsExactly("a");
            assertThat(peersOf(store, "lone"))
                .as("a graph with no row has no row to join, so it groups with nothing and two "
                    + "standalone graphs never group by accident")
                .isEmpty();

            // The write path's two directions. A diagnostics preamble after capture leaves the
            // declaration standing, so a compile-facts run can neither erase nor invent membership...
            new no.sikt.graphitron.model.diagnostics.BuildWarningFacts(
                store.dsl(), new GraphIdentity("a", aDir)).write(java.util.List.of());
            assertThat(peersOf(store, "a"))
                .as("a diagnostics preamble never touches the relation").containsExactly("b");

            // ...and a warm recapture without the declaration leaves none, removal propagating
            // through the ownership-scoped clear with no both-arms upsert subtlety to get wrong.
            CapturedStore.writeSource(aDir, FIXTURE);
            CapturedStore.capture(store.dsl(), new GraphIdentity("a", aDir),
                CapturedStore.corpusOf(aDir), null);
            assertThat(store.dsl().select(STORE_GRAPH_SUPERGRAPH.GRAPH_NAME)
                .from(STORE_GRAPH_SUPERGRAPH).fetch(0, String.class))
                .as("a pom that drops the element leaves no row on the next capture")
                .containsExactly("b");
        }
    }

    private static void captureUnder(GraphitronModelStore store, String graphName, Path dir,
                                     String supergraph) {
        var config = new SubjectConfig(java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.ofNullable(supergraph), java.util.Optional.empty(),
            java.util.Optional.empty(), no.sikt.graphitron.model.lint.LintConfig.empty(),
            no.sikt.graphitron.model.config.SessionStateConfig.none(),
            java.util.OptionalInt.empty());
        CapturedStore.writeSource(dir, FIXTURE);
        CapturedStore.capture(store.dsl(), new GraphIdentity(graphName, dir), config, null);
    }

    /** The peer set as the store spells it: a self-join over supergraph_name between non-null values. */
    private static java.util.List<String> peersOf(GraphitronModelStore store, String graphName) {
        var mine = STORE_GRAPH_SUPERGRAPH.as("mine");
        var theirs = STORE_GRAPH_SUPERGRAPH.as("theirs");
        return store.dsl().select(theirs.GRAPH_NAME)
            .from(mine).join(theirs).on(theirs.SUPERGRAPH_NAME.eq(mine.SUPERGRAPH_NAME))
            .where(mine.GRAPH_NAME.eq(graphName))
            .and(theirs.GRAPH_NAME.ne(mine.GRAPH_NAME))
            .orderBy(theirs.GRAPH_NAME)
            .fetch(0, String.class);
    }

    /** The graph's {@code store_graph} row. */
    private static Record graphRow(DSLContext dsl, String graphName) {
        return dsl.selectFrom(STORE_GRAPH).where(STORE_GRAPH.GRAPH_NAME.eq(graphName)).fetchOne();
    }

    /** The graph's recipe rows, in recipe order, rendered for comparison. */
    private static List<String> recipeRows(DSLContext dsl, String graphName) {
        var r = STORE_GRAPH_SCHEMA_INPUT;
        return dsl.select(r.ORDINAL, r.KIND, r.ENTRY_VALUE, r.TAG, r.DESCRIPTION_NOTE, r.TOUCHED_AT)
            .from(r)
            .where(r.GRAPH_NAME.eq(graphName))
            .orderBy(r.ORDINAL)
            .fetch(Record::toString);
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
