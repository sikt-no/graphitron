package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedDsl;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.selectOne;

/**
 * The shadow reader of the column-match classifier: whether {@code intent_column_match_claim} and
 * the reduction it feeds say what the classification walk says, over a real capture of the
 * spec-by-example corpus. The walk is the other side of every assertion here, which is what keeps
 * this half in the module the walk lives in: it retires when the walk does.
 *
 * <p>What the relations return given rows is a question about the model's own SQL and is pinned
 * where that SQL is declared, in {@code no.sikt.graphitron.model.intent.ColumnMatchClaimTest}. The
 * three cases below are the ones that cannot move, each naming a {@code graphitron} type as the
 * expected value rather than as scenery: the corpus sweep compares against the walk's
 * {@code ColumnBackedField} carrying {@code CallSiteCompaction.Direct}, the path case compares a
 * decline against the walk's {@code UnclassifiedField}, and the vocabulary case reads
 * {@link AuthoredClaim} as the closed set the store's classifier strings must sit inside.
 *
 * <p>The acceptance is agreement with the legacy arm, the fall-through of
 * {@code FieldBuilder.classifyChildFieldOnTableType}, whose product is the only
 * {@code ColumnBackedField} carrying {@code CallSiteCompaction.Direct}, over the corpus, walked and
 * captured side by side.
 */
@PipelineTier
class ColumnMatchShadowTest {

    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";

    @TempDir
    Path tmp;

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
    void maskedClaimsAgreeWithTheColumnMatchArmOverTheCorpus() {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var examples = CorpusDocuments.documents();
        int comparedCoordinates = 0;
        try (var captured = CapturedStore.ofCatalog(tmp, examples.getFirst().id(),
                fullSdl(examples.getFirst()), jooq)) {
            for (CorpusDocuments.Document example : examples.subList(1, examples.size())) {
                captured.andCatalogGraph(example.id(), fullSdl(example), jooq);
            }
            var maskedByGraph = maskedClaimsByGraph(captured.dsl());
            for (CorpusDocuments.Document example : examples) {
                var schema = TestSchemaHelper.buildSchema(fullSdl(example));

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
                for (MaskedClaim claim : maskedByGraph.getOrDefault(example.id(), List.of())) {
                    var previous = masked.put(key(claim.typeName(), claim.fieldName()),
                        witness(claim.tableName(), claim.columnName()));
                    assertThat(previous)
                        .as("one claim per coordinate (%s.%s in %s)", claim.typeName(),
                            claim.fieldName(), example.id())
                        .isNull();
                }
                // Soundness compares inside the walked domain; a claim outside it (an interface or
                // input parent's field, an unreached type) is the demand question, not this arm's
                // disagreement.
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

    /**
     * The residue this class used to record, now agreement. {@code @reference} routes the walk to
     * the reference arm, which rejects here: the terminal table has no {@code title} column. The
     * view once claimed the coincidental column on the parent instead, because it read the parent's
     * binding directly rather than where the site resolves; reading the scope makes it look for
     * {@code title} on {@code language}, find nothing, and decline exactly as the walk does.
     *
     * <p>The sweep's anti-join still names {@code reference}, for a reason that is not a
     * disagreement: a path the walk resolves produces a {@code ColumnBackedField} whose compaction
     * is not {@code Direct}, so the coordinate sits outside the arm the sweep compares rather than
     * inside it wrongly.
     */
    @Test
    void aPathWhoseTerminalLacksTheColumnDeclinesAsTheWalkDoes() {
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
        withCatalogStore(sdl, dsl -> {
            assertThat(dsl.fetchCount(INTENT_COLUMN_MATCH_CLAIM,
                INTENT_COLUMN_MATCH_CLAIM.TYPE_NAME.eq("Film")
                    .and(INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME.eq("title"))))
                .as("no title column on the terminal table, so the structural reading declines")
                .isZero();
            assertThat(dsl.fetchCount(INTENT_RESOLVED_FIELD_CLAIM,
                INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.eq("Film")
                    .and(INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME.eq("title"))))
                .as("nothing to reduce where nothing claimed")
                .isZero();
        });
    }

    /**
     * The value vocabularies are closed; the reading side decodes against exactly these. The
     * classifier strings the reduction can carry are {@link AuthoredClaim}'s own names plus the one
     * structural classifier, which is what makes this case belong beside the enum rather than
     * beside the SQL: it is the enum that has to stay a superset.
     */
    @Test
    void valueVocabulariesAreClosed() {
        var sdl = """
            type Film @table(name: "film") {
                title: String
                rating: String @service(service: {className: "%s", method: "get"})
            }
            type Query { films: [Film] }
            """.formatted(SERVICE_STUB);
        withCatalogStore(sdl, dsl -> {
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

    /** One corpus example as the parser sees it: the shared prelude in front of the example's own SDL. */
    private static String fullSdl(CorpusDocuments.Document example) {
        return CorpusDocuments.prelude() + "\n" + example.sdl();
    }

    /** One masked claim: the classifier's coordinate and the witness it resolved. */
    private record MaskedClaim(String typeName, String fieldName, String tableName,
            String columnName) {}

    /**
     * The masked reading the sweep compares against, for every graph in the store at once: the
     * classifier view behind the reduction's authored anti-join, less the coordinates a diverting
     * application owns. The residue names live in one place, this query, and shrink to dead weight
     * (never drift) as the diverting arms migrate to authored claims and the authored anti-join
     * takes over.
     *
     * <p>Read whole and paired on {@code graph_name} by the caller rather than read once per graph,
     * which is the rule
     * {@code docs/architecture/explanation/fact-model.adoc} states for a derivation this deep:
     * {@code intent_column_match_claim} collapses its matches with a window over
     * {@code intent_field_column_scope}, and a window sees its whole partition whatever predicate
     * the reader applies outside it, so a per-graph read pays every graph's rows once per graph.
     * The rows are identical either way: both anti-joins already correlate on {@code graph_name}
     * themselves, so the outer graph predicate only ever chose which of these rows a caller looked
     * at.
     */
    private static Map<String, List<MaskedClaim>> maskedClaimsByGraph(DSLContext dsl) {
        var i = INTENT_COLUMN_MATCH_CLAIM;
        var a = INTENT_AUTHORED_FIELD_CLAIM;
        var d = GRAPHQL_FIELD_DIRECTIVE;
        return dsl.select(i.GRAPH_NAME, i.TYPE_NAME, i.FIELD_NAME, i.TABLE_NAME, i.COLUMN_NAME)
            .from(i)
            .whereNotExists(selectOne().from(a)
                .where(a.GRAPH_NAME.eq(i.GRAPH_NAME)).and(a.TYPE_NAME.eq(i.TYPE_NAME))
                .and(a.FIELD_NAME.eq(i.FIELD_NAME)))
            .andNotExists(selectOne().from(d)
                .where(d.GRAPH_NAME.eq(i.GRAPH_NAME)).and(d.TYPE_NAME.eq(i.TYPE_NAME))
                .and(d.FIELD_NAME.eq(i.FIELD_NAME))
                .and(d.DIRECTIVE_NAME.in("reference", "pivot", "sourceRow")))
            .fetchGroups(i.GRAPH_NAME, r -> new MaskedClaim(r.get(i.TYPE_NAME),
                r.get(i.FIELD_NAME), r.get(i.TABLE_NAME), r.get(i.COLUMN_NAME)));
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

    private void withCatalogStore(String sdl, java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var captured = CapturedStore.ofCatalog(tmp, sdl, jooq)) {
            body.accept(captured.dsl());
        }
    }
}
