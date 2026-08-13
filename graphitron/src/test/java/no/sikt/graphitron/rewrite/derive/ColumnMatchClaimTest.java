package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedDsl;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.selectOne;

/**
 * The shadow reader of the column-match classifier view: {@code intent_column_match_claim} is the
 * first structural classifier reified as a derivation, and this test is its registered agreement
 * anchor (with {@code intent_resolved_field_claim}, the reduction it feeds). The acceptance is
 * agreement with the legacy arm, the fall-through of
 * {@code FieldBuilder.classifyChildFieldOnTableType}, whose product is the only
 * {@code ColumnBackedField} carrying {@code CallSiteCompaction.Direct}, over the spec-by-example
 * corpus, walked and captured side by side. Beside the sweep sit the targeted pins: witness
 * content, the {@code @field} rename, the tier precedence and ambiguity semantics the catalog
 * fixtures cannot reach (seeded rows exercise the view's own SQL), the authored-coverage mask
 * with the raw reading surviving, membership scoping, the closed value vocabularies, and the two
 * recorded transitional residues.
 */
@PipelineTier
class ColumnMatchClaimTest {

    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";

    @TempDir
    Path tmp;

    // ===== The corpus sweep =====

    /**
     * Two-way agreement over the walked domain, for every corpus example captured as its own graph
     * in one store (the partition dimension carries the sweep, so sibling scoping is a property of
     * the sweep rather than a fixture to believe). Completeness: every coordinate the walk
     * classified through the column-match arm has exactly one masked claim naming the walk's
     * resolved table and column. Soundness: every masked claim at a walked-domain coordinate is
     * such a classification. The mask is the authored anti-join (the reduction's rule) plus the
     * transitional-residue anti-join against the diverting applications ({@code @reference},
     * {@code @pivot}, {@code @sourceRow} divert the walk ahead of the column arm but are not
     * authored claims until their arms migrate); expressed against the store's own application
     * rows so a new diverting directive announces itself as a sweep failure here, not as silent
     * drift in a Java skip-list.
     */
    @Test
    void maskedClaimsAgreeWithTheColumnMatchArmOverTheCorpus() throws IOException {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        int comparedCoordinates = 0;
        try (var store = GraphitronModelStore.open()) {
            for (ClassifiedCorpus.Example example : ClassifiedCorpus.examples()) {
                String full = ClassifiedDsl.PRELUDE + "\n" + example.sdl();
                Path dir = Files.createDirectories(tmp.resolve(example.id()));
                var schemaFile = write(dir, full);
                var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
                FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(example.id(), dir),
                    FactCapture.SubjectConfig.none(), registry,
                    TestSchemaHelper.attribution(schemaFile), jooq, List.of(), new NodeDeclaration(null));

                var schema = TestSchemaHelper.buildSchema(full);

                // The walk's side: the column-match arm's product, keyed "Type.field", valued by
                // the resolved witness (table, column), lower-cased for the case-loose compare.
                var expected = new LinkedHashMap<String, String>();
                schema.fields().forEach((coordinate, field) -> {
                    if (field instanceof ColumnBackedField cbf
                            && cbf.compaction() instanceof CallSiteCompaction.Direct) {
                        var parent = schema.types().get(coordinate.getTypeName());
                        assertThat(parent)
                            .as("a Direct column carrier's parent is table-backed (%s)", coordinate)
                            .isInstanceOf(TableBackedType.class);
                        expected.put(key(coordinate.getTypeName(), coordinate.getFieldName()),
                            witness(((TableBackedType) parent).table().tableName(),
                                cbf.columns().getFirst().sqlName()));
                    }
                });

                var masked = new LinkedHashMap<String, String>();
                maskedClaims(store.dsl(), example.id()).forEach(row -> {
                    var previous = masked.put(key(row.value1(), row.value2()),
                        witness(row.value3(), row.value4()));
                    assertThat(previous)
                        .as("one claim per coordinate (%s.%s in %s)", row.value1(), row.value2(),
                            example.id())
                        .isNull();
                });
                // Soundness compares inside the walked domain; a claim outside it (an interface or
                // input parent's field, an unreached type) is slice 4's demand question, not this
                // arm's disagreement.
                masked.keySet().removeIf(coordinate -> !expected.containsKey(coordinate)
                    && !schema.fields().containsKey(coordinatesOf(coordinate)));
                assertThat(masked)
                    .as("masked column-match claims vs the walk's column-match arm (%s)", example.id())
                    .containsExactlyInAnyOrderEntriesOf(expected);
                comparedCoordinates += expected.size();
            }
        }
        assertThat(comparedCoordinates)
            .as("the corpus reaches the column-match arm, so the sweep pinned something")
            .isGreaterThan(10);
    }

    // ===== Targeted pins: capture-based =====

    @Test
    void witnessRowCarriesTheResolvedJoin() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> {
            var rows = dsl.selectFrom(INTENT_COLUMN_MATCH_CLAIM)
                .where(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(GRAPH)).fetch();
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.getTypeName()).isEqualTo("Film");
            assertThat(row.getFieldName()).isEqualTo("title");
            assertThat(row.getClassifier()).isEqualTo("TABLE_COLUMN");
            assertThat(row.getMatchedName()).isEqualTo("title");
            assertThat(row.getMatchedBy()).isEqualTo("JOOQ_NAME");
            assertThat(row.getTableName()).isEqualToIgnoringCase("film");
            assertThat(row.getColumnName()).isEqualToIgnoringCase("title");
            assertThat(row.getTableSourceName()).isNotNull();
            assertThat(row.getSourceName()).endsWith("fixture.graphqls");
            assertThat(row.getSourceLine()).isNotNull();
        });
    }

    @Test
    void fieldBindingSuppliesTheEffectiveName() {
        var sdl = """
            type Film @table(name: "film") { year: Int @field(name: "release_year") }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> {
            var rows = dsl.selectFrom(INTENT_COLUMN_MATCH_CLAIM)
                .where(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(GRAPH)).fetch();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getFieldName()).isEqualTo("year");
            assertThat(rows.getFirst().getMatchedName()).isEqualTo("release_year");
            assertThat(rows.getFirst().getColumnName()).isEqualToIgnoringCase("release_year");
        });
    }

    /**
     * The reduction's rule, both halves: the authored claim wins the coordinate, and the masked
     * structural reading survives in the classifier view as data: the row a hover reads to say
     * "would classify as a table column; {@code @service} overrides it".
     */
    @Test
    void authoredCoverageMasksInTheReductionAndTheRawReadingSurvives() {
        var sdl = """
            type Film @table(name: "film") {
                title: String @service(service: {className: "%s", method: "get"})
            }
            type Query { films: [Film] }
            """.formatted(SERVICE_STUB);
        withCapturedStore(sdl, dsl -> {
            assertThat(dsl.fetchCount(INTENT_COLUMN_MATCH_CLAIM,
                INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(GRAPH)
                    .and(INTENT_COLUMN_MATCH_CLAIM.TYPE_NAME.eq("Film"))
                    .and(INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME.eq("title"))))
                .as("the raw structural reading survives the authored coverage")
                .isEqualTo(1);
            var resolved = dsl.select(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER,
                    INTENT_RESOLVED_FIELD_CLAIM.TIER)
                .from(INTENT_RESOLVED_FIELD_CLAIM)
                .where(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(GRAPH))
                .and(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.eq("Film"))
                .and(INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME.eq("title"))
                .fetch(r -> r.value1() + ":" + r.value2());
            assertThat(resolved).containsExactly("SERVICE:AUTHORED");
        });
    }

    /**
     * First recorded residue: a diverting directive that is not yet an authored claim.
     * {@code @reference} routes the walk to the reference arm (which here rejects: no
     * {@code title} column on the terminal table), while the classifier view claims the
     * coincidental local column and the reduction has nothing authored to mask it with. The
     * disagreement is transitional by design (when the reference arm migrates to an authored
     * claim under the umbrella, the same coordinate-grain anti-join masks this row with no view
     * edit), and the corpus sweep excludes exactly these coordinates through its relational
     * residue anti-join.
     */
    @Test
    void divertedReferenceFieldIsTheRecordedResidue() {
        var sdl = """
            type Film @table(name: "film") {
                title: String @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        assertThat(schema.field("Film", "title"))
            .as("the walk diverts to the reference arm and rejects at the terminal table")
            .isInstanceOf(UnclassifiedField.class);
        withCapturedStore(sdl, dsl -> {
            assertThat(dsl.fetchCount(INTENT_COLUMN_MATCH_CLAIM,
                INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(GRAPH)
                    .and(INTENT_COLUMN_MATCH_CLAIM.TYPE_NAME.eq("Film"))
                    .and(INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME.eq("title"))))
                .as("the structural reading claims the coincidental local column")
                .isEqualTo(1);
            assertThat(dsl.fetchCount(INTENT_RESOLVED_FIELD_CLAIM,
                INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(GRAPH)
                    .and(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.eq("Film"))
                    .and(INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME.eq("title"))
                    .and(INTENT_RESOLVED_FIELD_CLAIM.TIER.eq("INFERRED"))))
                .as("nothing authored masks a directive that does not claim yet")
                .isEqualTo(1);
            assertThat(maskedClaims(dsl, GRAPH))
                .as("the sweep's residue anti-join excludes the diverted coordinate")
                .noneMatch(row -> "Film".equals(row.value1()) && "title".equals(row.value2()));
        });
    }

    // ===== Targeted pins: seeded rows (view semantics the test catalog cannot reach) =====

    /**
     * The two-tier match transcribing {@code JooqCatalog.findColumn}: the generated Java name is
     * tried first, so a column whose jOOQ name equals the sought name beats a different column
     * whose SQL name does. The test catalog's jOOQ names all shadow their SQL names, so the
     * precedence is only reachable through seeded rows.
     */
    @Test
    void jooqNameTierBeatsTheSqlNameTier() {
        withSeededStore(dsl -> {
            seedField(dsl, "g", "Actor", "tiered");
            seedTable(dsl, "g", "Actor", "actor", "pkg.a", "public", "actor");
            seedColumn(dsl, "pkg.a", "public", "actor", "x_tiered", 1, "TIERED");
            seedColumn(dsl, "pkg.a", "public", "actor", "tiered", 2, "X_TIERED");
            var rows = dsl.selectFrom(INTENT_COLUMN_MATCH_CLAIM)
                .where(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq("g")).fetch();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getColumnName()).isEqualTo("x_tiered");
            assertThat(rows.getFirst().getMatchedBy()).isEqualTo("JOOQ_NAME");
        });
    }

    /** A qualified {@code @table} reference splits on its first dot, schema half included. */
    @Test
    void qualifiedTableRefSplitsOnItsFirstDot() {
        withSeededStore(dsl -> {
            seedField(dsl, "g", "Actor", "name");
            seedTable(dsl, "g", "Actor", "other.actor", "pkg.a", "other", "actor");
            seedColumn(dsl, "pkg.a", "other", "actor", "name", 1, "NAME");
            // The same table name in another schema must not shadow the qualified pick.
            seedSchema(dsl, "pkg.a", "public");
            dsl.insertInto(SQL_TABLE)
                .values("pkg.a", "public", "actor", "ACTOR", "pkg.a.tables.actor", null)
                .execute();
            var rows = dsl.selectFrom(INTENT_COLUMN_MATCH_CLAIM)
                .where(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq("g")).fetch();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getTableSchema()).isEqualTo("other");
        });
    }

    /**
     * An unqualified reference matching tables in two member sources is the walk's
     * {@code TableResolution.Ambiguous} transcribed: no claim, rather than an arbitrary pick.
     */
    @Test
    void ambiguousUnqualifiedTableYieldsNoClaim() {
        withSeededStore(dsl -> {
            seedField(dsl, "g", "Dup", "title");
            seedTable(dsl, "g", "Dup", "dup", "pkg.a", "public", "dup");
            seedColumn(dsl, "pkg.a", "public", "dup", "title", 1, "TITLE");
            seedSource(dsl, "pkg.b");
            dsl.insertInto(STORE_GRAPH_SOURCE).values("g", "pkg.b").execute();
            seedSchema(dsl, "pkg.b", "legacy");
            dsl.insertInto(SQL_TABLE)
                .values("pkg.b", "legacy", "dup", "DUP", "pkg.b.tables.dup", null)
                .execute();
            assertThat(dsl.fetchCount(INTENT_COLUMN_MATCH_CLAIM,
                INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq("g"))).isZero();
        });
    }

    /**
     * The membership relation doing its job: two graphs, one shared store, the same unqualified
     * table name in each graph's own source. Neither resolution sees the sibling's table: no
     * ambiguity, and each witness names the graph's own catalog partition.
     */
    @Test
    void siblingGraphsResolveThroughTheirOwnMembership() {
        withSeededStore(dsl -> {
            seedField(dsl, "g", "Film", "title");
            seedTable(dsl, "g", "Film", "film", "pkg.a", "public", "film");
            seedColumn(dsl, "pkg.a", "public", "film", "title", 1, "TITLE");

            seedGraph(dsl, "g2");
            seedField(dsl, "g2", "Film", "title");
            seedTable(dsl, "g2", "Film", "film", "pkg.b", "public", "film");
            seedColumn(dsl, "pkg.b", "public", "film", "title", 1, "TITLE");

            var bySource = dsl.select(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME,
                    INTENT_COLUMN_MATCH_CLAIM.TABLE_SOURCE_NAME)
                .from(INTENT_COLUMN_MATCH_CLAIM)
                .fetchMap(r -> r.value1(), r -> r.value2());
            assertThat(bySource).containsExactlyInAnyOrderEntriesOf(
                Map.of("g", "pkg.a", "g2", "pkg.b"));
        });
    }

    /**
     * Second recorded residue, not the same shape as the directive one: a {@code Node.id} field
     * over a table with a literal {@code id} column carries no directive at all, so nothing
     * authored can mask the column reading. The walk rejects the coordinate
     * ({@code FieldBuilder.rejectShadowedIdColumn}: two readings, different wire values, the
     * author must pick), while the view shows the column claim alone, one claim where the target
     * model shows two and a violation, because the node-id structural arm is not modelled yet.
     * When it lands, this coordinate becomes the structural-ambiguity rule's first live case and
     * {@code BuildContext.rejectShadowedNodeId} its migration target.
     */
    @Test
    void nodeIdShadowedColumnIsTheUnmodelledSecondStructuralClaim() {
        withSeededStore(dsl -> {
            seedField(dsl, "g", "Customer", "id");
            seedTable(dsl, "g", "Customer", "customer", "pkg.a", "public", "customer");
            seedColumn(dsl, "pkg.a", "public", "customer", "id", 1, "ID");
            var rows = dsl.selectFrom(INTENT_COLUMN_MATCH_CLAIM)
                .where(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq("g")).fetch();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getColumnName()).isEqualTo("id");
        });
    }

    /** The value vocabularies are closed; the reading side decodes against exactly these. */
    @Test
    void valueVocabulariesAreClosed() {
        var sdl = """
            type Film @table(name: "film") {
                title: String
                rating: String @service(service: {className: "%s", method: "get"})
            }
            type Query { films: [Film] }
            """.formatted(SERVICE_STUB);
        withCapturedStore(sdl, dsl -> {
            assertThat(dsl.selectDistinct(INTENT_COLUMN_MATCH_CLAIM.CLASSIFIER)
                .from(INTENT_COLUMN_MATCH_CLAIM).fetch(0, String.class))
                .containsExactly("TABLE_COLUMN");
            assertThat(dsl.selectDistinct(INTENT_COLUMN_MATCH_CLAIM.MATCHED_BY)
                .from(INTENT_COLUMN_MATCH_CLAIM).fetch(0, String.class))
                .isSubsetOf("JOOQ_NAME", "SQL_NAME");
            assertThat(dsl.selectDistinct(INTENT_RESOLVED_FIELD_CLAIM.TIER)
                .from(INTENT_RESOLVED_FIELD_CLAIM).fetch(0, String.class))
                .isSubsetOf("AUTHORED", "INFERRED");
            var classifiers = dsl.selectDistinct(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER)
                .from(INTENT_RESOLVED_FIELD_CLAIM).fetch(0, String.class);
            var vocabulary = new java.util.ArrayList<String>();
            for (AuthoredClaim claim : AuthoredClaim.values()) {
                vocabulary.add(claim.name());
            }
            vocabulary.add("TABLE_COLUMN");
            assertThat(classifiers).isSubsetOf(vocabulary);
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "ColumnMatchClaimTest";

    /**
     * The masked reading the sweep and the residue pin share: the classifier view behind the
     * reduction's authored anti-join, less the coordinates a diverting application owns. The
     * residue names live in one place, this query, and shrink to dead weight (never drift) as
     * the diverting arms migrate to authored claims and the authored anti-join takes over.
     */
    private static List<org.jooq.Record4<String, String, String, String>> maskedClaims(
            DSLContext dsl, String graphName) {
        var i = INTENT_COLUMN_MATCH_CLAIM;
        var a = INTENT_AUTHORED_FIELD_CLAIM;
        var d = GRAPHQL_FIELD_DIRECTIVE;
        return dsl.select(i.TYPE_NAME, i.FIELD_NAME, i.TABLE_NAME, i.COLUMN_NAME)
            .from(i)
            .where(i.GRAPH_NAME.eq(graphName))
            .andNotExists(selectOne().from(a)
                .where(a.GRAPH_NAME.eq(i.GRAPH_NAME)).and(a.TYPE_NAME.eq(i.TYPE_NAME))
                .and(a.FIELD_NAME.eq(i.FIELD_NAME)))
            .andNotExists(selectOne().from(d)
                .where(d.GRAPH_NAME.eq(i.GRAPH_NAME)).and(d.TYPE_NAME.eq(i.TYPE_NAME))
                .and(d.FIELD_NAME.eq(i.FIELD_NAME))
                .and(d.DIRECTIVE_NAME.in("reference", "pivot", "sourceRow")))
            .fetch();
    }

    private static String key(String typeName, String fieldName) {
        return typeName + "." + fieldName;
    }

    private static FieldCoordinates coordinatesOf(String key) {
        int dot = key.indexOf('.');
        return FieldCoordinates.coordinates(key.substring(0, dot), key.substring(dot + 1));
    }

    private static String witness(String tableName, String columnName) {
        return (tableName + "|" + columnName).toLowerCase(Locale.ROOT);
    }

    private void withCapturedStore(String sdl, java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, sdl);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
                jooq, List.of(), new NodeDeclaration(null));
            body.accept(store.dsl());
        }
    }

    /** A store with graph {@code g} anchored and one catalog source {@code pkg.a} in membership. */
    private static void withSeededStore(java.util.function.Consumer<DSLContext> body) {
        try (var store = GraphitronModelStore.open()) {
            var dsl = store.dsl();
            seedGraph(dsl, "g");
            body.accept(dsl);
        }
    }

    private static void seedGraph(DSLContext dsl, String graphName) {
        dsl.insertInto(STORE_GRAPH)
            .set(STORE_GRAPH.GRAPH_NAME, graphName)
            .set(STORE_GRAPH.BASE_DIR, "/seeded")
            .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
            .execute();
        dsl.insertInto(GRAPHQL_TYPE).values(graphName, "String", "SCALAR", null).execute();
    }

    private static void seedSource(DSLContext dsl, String sourceName) {
        dsl.insertInto(STORE_SOURCE)
            .set(STORE_SOURCE.SOURCE_NAME, sourceName)
            .set(STORE_SOURCE.SOURCE_KIND, "JOOQ_SCHEMA")
            .set(STORE_SOURCE.LAST_SEEN, LocalDateTime.now())
            .onDuplicateKeyIgnore()
            .execute();
    }

    /** One scalar output field {@code typeName.fieldName: String} with its declaration site. */
    private static void seedField(DSLContext dsl, String graphName, String typeName, String fieldName) {
        if (dsl.fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.GRAPH_NAME.eq(graphName)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(typeName))) == 0) {
            dsl.insertInto(GRAPHQL_TYPE).values(graphName, typeName, "OBJECT", null).execute();
            dsl.insertInto(GRAPHQL_TYPE_DECLARATION)
                .values(graphName, typeName, "seed.graphqls", 1, 1, 0, false, "OBJECT")
                .execute();
        }
        dsl.insertInto(GRAPHQL_FIELD)
            .set(GRAPHQL_FIELD.GRAPH_NAME, graphName)
            .set(GRAPHQL_FIELD.TYPE_NAME, typeName)
            .set(GRAPHQL_FIELD.FIELD_NAME, fieldName)
            .set(GRAPHQL_FIELD.ORDINAL, 0)
            .set(GRAPHQL_FIELD.DECLARATION_LINE, 1)
            .set(GRAPHQL_FIELD.DECLARATION_COLUMN, 1)
            .set(GRAPHQL_FIELD.TYPE_SDL, "String")
            .set(GRAPHQL_FIELD.NAMED_TYPE, "String")
            .set(GRAPHQL_FIELD.NON_NULL, false)
            .set(GRAPHQL_FIELD.IS_LIST, false)
            .set(GRAPHQL_FIELD.SOURCE_NAME, "seed.graphqls")
            .set(GRAPHQL_FIELD.SOURCE_LINE, 2)
            .set(GRAPHQL_FIELD.SOURCE_COLUMN, 3)
            .execute();
    }

    /** Binds {@code typeName} to {@code tableRef} and puts the catalog table in membership. */
    private static void seedTable(DSLContext dsl, String graphName, String typeName, String tableRef,
                                  String sourceName, String tableSchema, String tableName) {
        if (dsl.fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.GRAPH_NAME.eq(graphName)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(typeName))) == 0) {
            dsl.insertInto(GRAPHQL_TYPE).values(graphName, typeName, "OBJECT", null).execute();
            dsl.insertInto(GRAPHQL_TYPE_DECLARATION)
                .values(graphName, typeName, "seed.graphqls", 1, 1, 0, false, "OBJECT")
                .execute();
        }
        dsl.insertInto(GRAPHITRON_TABLE)
            .values(graphName, typeName, "seed.graphqls", 1, 1, 1, 20, tableRef)
            .execute();
        seedSource(dsl, sourceName);
        dsl.insertInto(STORE_GRAPH_SOURCE).values(graphName, sourceName).execute();
        seedSchema(dsl, sourceName, tableSchema);
        dsl.insertInto(SQL_TABLE)
            .values(sourceName, tableSchema, tableName, tableName.toUpperCase(Locale.ROOT),
                sourceName + ".tables." + tableName, null)
            .execute();
    }

    /**
     * The schema row {@code sql_table} references. Idempotent, because several seeded tables share a
     * schema and a seed helper is called once per table.
     */
    private static void seedSchema(DSLContext dsl, String sourceName, String tableSchema) {
        if (dsl.fetchCount(SQL_SCHEMA, SQL_SCHEMA.SOURCE_NAME.eq(sourceName)
                .and(SQL_SCHEMA.TABLE_SCHEMA.eq(tableSchema))) == 0) {
            dsl.insertInto(SQL_SCHEMA)
                .values(sourceName, tableSchema, sourceName + ".Keys")
                .execute();
        }
    }

    private static void seedColumn(DSLContext dsl, String sourceName, String tableSchema,
                                   String tableName, String columnName, int ordinal, String jooqName) {
        dsl.insertInto(SQL_COLUMN)
            .values(sourceName, tableSchema, tableName, columnName, ordinal, jooqName,
                "character varying", "java.lang.String", true, null)
            .execute();
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
