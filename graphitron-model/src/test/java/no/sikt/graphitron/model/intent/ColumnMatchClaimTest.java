package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.tables.records.IntentBoundTableRecord;
import no.sikt.graphitron.model.tables.records.IntentColumnMatchClaimRecord;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedBoundTable;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_column_match_claim} returns: which column a field's own name resolves to on
 * the table its site navigates to, no directive involved. It pins {@code intent_bound_table} with
 * it, the resolution the claim's table witness comes through, and reaches
 * {@code intent_resolved_field_claim} where the question is what the reduction does with a claim
 * rather than what the claim says.
 *
 * <p>Every input is stated as rows, which is what puts the arrangements the classifier actually
 * decides between in one fixture. The two-tier name match only shows its precedence where a
 * column's generated Java name and its SQL name disagree, and a catalog generated from real DDL has
 * them agree everywhere; an ambiguous binding needs one table name in two schemas; a sibling graph
 * needs a second partition holding the same names. Those are catalog states, and a fixture that
 * reaches them through a generator is choosing its inputs by what the generator happens to emit.
 *
 * <p>Whether the walk and this view agree is a different question with a different lifetime, and it
 * lives beside the walk in {@code no.sikt.graphitron.rewrite.derive.ColumnMatchShadowTest}.
 */
class ColumnMatchClaimTest {

    // ===== The claim's own row =====

    /**
     * The projection: the classifier's own product (the name it matched and which tier matched it)
     * beside the witness naming the {@code sql_column} key it landed on, and the field's own
     * declaration position carried through so a diagnostic reading this row needs no second join.
     */
    @Test
    void theRowNamesTheMatchItsTierAndItsWitness() {
        withFilm(dsl -> {
            seedField(dsl, GRAPH, "Film", "title");
            var rows = claims(dsl);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.getTypeName()).isEqualTo("Film");
            assertThat(row.getFieldName()).isEqualTo("title");
            assertThat(row.getClassifier()).isEqualTo("TABLE_COLUMN");
            assertThat(row.getMatchedName()).isEqualTo("title");
            assertThat(row.getMatchedBy()).isEqualTo("JOOQ_NAME");
            assertThat(row.getTableSourceName()).isEqualTo(PKG);
            assertThat(row.getTableSchema()).isEqualTo(PUBLIC);
            assertThat(row.getTableName()).isEqualTo("film");
            assertThat(row.getColumnName()).isEqualTo("title");

            var field = dsl.selectFrom(GRAPHQL_FIELD)
                .where(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHQL_FIELD.TYPE_NAME.eq("Film"))
                .and(GRAPHQL_FIELD.FIELD_NAME.eq("title")).fetchSingle();
            var binding = dsl.selectFrom(GRAPHITRON_TABLE)
                .where(GRAPHITRON_TABLE.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHITRON_TABLE.TYPE_NAME.eq("Film")).fetchSingle();
            assertThat(row.getSourceName()).isEqualTo(field.getSourceName());
            assertThat(row.getSourceLine()).isEqualTo(field.getSourceLine());
            assertThat(row.getSourceColumn())
                .as("the field's own declaration position, and the binding sits elsewhere, "
                    + "so the compare discriminates")
                .isEqualTo(field.getSourceColumn())
                .isNotEqualTo(binding.getSourceColumn());
        });
    }

    /**
     * The effective name is the {@code @field} binding where one is written, so the match runs on a
     * name the field itself never carries. Neither field below matches a column under its own name,
     * so the two bindings are the whole of what decides both rows, and a binding read at the wrong
     * coordinate would answer with the other field's column rather than with nothing.
     */
    @Test
    void aFieldBindingSuppliesTheEffectiveName() {
        withFilm(dsl -> {
            seedField(dsl, GRAPH, "Film", "year");
            seedField(dsl, GRAPH, "Film", "certificate");
            assertThat(claims(dsl))
                .as("no column is named year or certificate")
                .isEmpty();
            seedFieldBinding(dsl, GRAPH, "Film", "year", "release_year");
            seedFieldBinding(dsl, GRAPH, "Film", "certificate", "rating");
            assertThat(claims(dsl).map(r -> r.getFieldName() + " " + r.getMatchedName()
                    + " " + r.getColumnName()))
                .containsExactlyInAnyOrder("year release_year release_year",
                    "certificate rating rating");
        });
    }

    /**
     * Both sides of the effective-name match are compared case-insensitively, and the authored side
     * is a GraphQL identifier where the catalog side is a SQL one. Worth its own case because the
     * fold on the authored side is the one a reader doubts: a {@code @field} spelling that differs
     * from its column only in case still claims the column, and so does a field name standing in
     * for one where no binding decoded.
     */
    @Test
    void anEffectiveNameClaimsItsColumnAcrossCase() {
        withFilm(dsl -> {
            seedField(dsl, GRAPH, "Film", "heading");
            seedFieldBinding(dsl, GRAPH, "Film", "heading", "TITLE");
            seedField(dsl, GRAPH, "Film", "Rating");
            assertThat(claims(dsl).map(r -> r.getFieldName() + " " + r.getColumnName()))
                .containsExactlyInAnyOrder("heading title", "Rating rating");
        });
    }

    /**
     * The two-tier match: the generated Java name is tried first, so a column whose jOOQ name equals
     * the sought name beats a different column whose SQL name does. A catalog generated from DDL has
     * every jOOQ name shadow its SQL name, so the precedence is only reachable from stated rows.
     *
     * <p>The losing column is declared first, so the tier is what decides this and not the ordinal
     * the tie breaks on.
     */
    @Test
    void jooqNameTierBeatsTheSqlNameTier() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Actor", "tiered");
            seedBoundTable(dsl, GRAPH, "Actor", "actor", PKG, PUBLIC, "actor");
            seedColumn(dsl, PKG, PUBLIC, "actor", "tiered", 1, "X_TIERED");
            seedColumn(dsl, PKG, PUBLIC, "actor", "x_tiered", 2, "TIERED");
            var rows = claims(dsl);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getColumnName()).isEqualTo("x_tiered");
            assertThat(rows.getFirst().getMatchedBy()).isEqualTo("JOOQ_NAME");
        });
    }

    /**
     * The witness is a three-part {@code sql_column} key and each part decides the answer. Three
     * decoy tables each differ from the bound one in exactly one part and each offers a
     * generated-name match, which outranks the tier the real column matches on; so a join that
     * dropped any one part answers with a decoy's column rather than falling silent. The decoy in
     * the second source is reachable because catalog rows are not scoped by graph membership, only
     * the resolution above them is, and the binding is written qualified so the decoy schema does
     * not make the spelling ambiguous instead.
     */
    @Test
    void everyPartOfTheWitnessKeyDecidesTheMatch() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "title");
            seedBoundTable(dsl, GRAPH, "Film", "public.film", PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "X_TITLE");

            seedSource(dsl, OTHER_PKG, "JOOQ_SCHEMA");
            seedTable(dsl, OTHER_PKG, PUBLIC, "film");
            seedColumn(dsl, OTHER_PKG, PUBLIC, "film", "other_source", 1, "TITLE");
            seedTable(dsl, PKG, "legacy", "film");
            seedColumn(dsl, PKG, "legacy", "film", "other_schema", 1, "TITLE");
            seedTable(dsl, PKG, PUBLIC, "film_archive");
            seedColumn(dsl, PKG, PUBLIC, "film_archive", "other_table", 1, "TITLE");

            var rows = claims(dsl);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getColumnName()).isEqualTo("title");
            assertThat(rows.getFirst().getMatchedBy())
                .as("the bound table's own column matches on its SQL name, which is the losing tier")
                .isEqualTo("SQL_NAME");
            assertThat(rows.getFirst().getTableSourceName()).isEqualTo(PKG);
            assertThat(rows.getFirst().getTableSchema()).isEqualTo(PUBLIC);
            assertThat(rows.getFirst().getTableName()).isEqualTo("film");
        });
    }

    /**
     * A field whose named type is an object is not a column carrier, whatever its name matches. The
     * leaf gate is the arm's own precondition rather than something the site resolution filters
     * out, so the same coordinate claims or declines on the kind of the type it names alone.
     */
    @Test
    void onlyAScalarOrEnumLeafCanCarryAColumn() {
        withFilm(dsl -> {
            seedTableBinding(dsl, GRAPH, "Language", "language");
            seedField(dsl, GRAPH, "Film", "name", "Language", false);
            seedType(dsl, GRAPH, "Rating", "ENUM");
            seedField(dsl, GRAPH, "Film", "rating", "Rating", false);
            seedField(dsl, GRAPH, "Film", "title");
            assertThat(claims(dsl).map(r -> r.getFieldName()))
                .as("an enum leaf carries a column on the same terms a scalar does; an object leaf "
                    + "carries none, even where the site it navigates to has a column of its name")
                .containsExactlyInAnyOrder("rating", "title");
        });
    }

    /** A qualified {@code @table} reference splits on its first dot, schema half included. */
    @Test
    void qualifiedTableRefSplitsOnItsFirstDot() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Actor", "name");
            seedBoundTable(dsl, GRAPH, "Actor", "other.actor", PKG, "other", "actor");
            seedColumn(dsl, PKG, "other", "actor", "name", 1, "NAME");
            // The same table name in another schema must not shadow the qualified pick.
            seedTable(dsl, PKG, PUBLIC, "actor");
            var rows = claims(dsl);
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
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Dup", "title");
            seedBoundTable(dsl, GRAPH, "Dup", "dup", PKG, PUBLIC, "dup");
            seedColumn(dsl, PKG, PUBLIC, "dup", "title", 1, "TITLE");
            seedSource(dsl, OTHER_PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, OTHER_PKG);
            seedTable(dsl, OTHER_PKG, "legacy", "dup");
            assertThat(claims(dsl)).isEmpty();
        });
    }

    /**
     * The membership relation doing its job: two graphs, one shared store, the same unqualified
     * table name in each graph's own source. Neither resolution sees the sibling's table: no
     * ambiguity, and each witness names the graph's own catalog partition.
     */
    @Test
    void siblingGraphsResolveThroughTheirOwnMembership() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "title");
            seedBoundTable(dsl, GRAPH, "Film", "film", PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");

            seedGraph(dsl, OTHER_GRAPH);
            seedField(dsl, OTHER_GRAPH, "Film", "title");
            seedBoundTable(dsl, OTHER_GRAPH, "Film", "film", OTHER_PKG, PUBLIC, "film");
            seedColumn(dsl, OTHER_PKG, PUBLIC, "film", "title", 1, "TITLE");

            derive(dsl);
            var bySource = dsl.select(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME,
                    INTENT_COLUMN_MATCH_CLAIM.TABLE_SOURCE_NAME)
                .from(INTENT_COLUMN_MATCH_CLAIM)
                .fetchMap(r -> r.value1(), r -> r.value2());
            assertThat(bySource).containsExactlyInAnyOrderEntriesOf(
                Map.of(GRAPH, PKG, OTHER_GRAPH, OTHER_PKG));
        });
    }

    /**
     * The match runs where the site navigates, so an authored path moves it to the path's terminal
     * table. Nothing about the coordinate says "column on the parent" once the author has written
     * where the value comes from, and resolving there is what makes the witness the table the value
     * is actually read out of. The parent carries a column of the same name, so a view resolving
     * against the parent would answer this case too, with the wrong table.
     *
     * <p>The sibling field with no path is here because the scope is read per coordinate: both
     * tables carry both names, so a scope paired with the wrong field would move this one too.
     */
    @Test
    void anAuthoredPathMovesTheMatchToItsTerminalTable() {
        withFilm(dsl -> {
            seedField(dsl, GRAPH, "Film", "name");
            seedField(dsl, GRAPH, "Film", "title");
            seedFieldReference(dsl, GRAPH, "Film", "name", 0);
            seedFieldReferenceStep(dsl, GRAPH, "Film", "name", 0, 0, null, FILM_LANGUAGE_KEY);
            assertThat(claims(dsl).map(r -> r.getFieldName() + " " + r.getTableName()
                    + " " + r.getColumnName()))
                .as("the path's terminal for the field that wrote one, the parent's own for the "
                    + "field that did not")
                .containsExactlyInAnyOrder("name language name", "title film title");
        });
    }

    /**
     * The reduction's rule, both halves: the authored claim wins the coordinate, and the masked
     * structural reading survives in the classifier view as data, the row a hover reads to say
     * "would classify as a table column; {@code @service} overrides it".
     *
     * <p>The mask is per coordinate, which the sibling field states: one authored claim on a type
     * does not stand for the type, and the field beside it keeps its inferred reading.
     */
    @Test
    void authoredCoverageMasksInTheReductionAndTheRawReadingSurvives() {
        withFilm(dsl -> {
            seedField(dsl, GRAPH, "Film", "title");
            seedField(dsl, GRAPH, "Film", "rating");
            seedService(dsl, GRAPH, "Film", "title", "no.example.Svc", "get");
            assertThat(claims(dsl).map(r -> r.getFieldName()))
                .as("the raw structural reading survives the authored coverage")
                .containsExactlyInAnyOrder("title", "rating");
            derive(dsl);
            assertThat(dsl.select(INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME,
                    INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER, INTENT_RESOLVED_FIELD_CLAIM.TIER)
                .from(INTENT_RESOLVED_FIELD_CLAIM)
                .where(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(GRAPH))
                .and(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.eq("Film"))
                .fetch(r -> r.value1() + " " + r.value2() + ":" + r.value3()))
                .containsExactlyInAnyOrder("title SERVICE:AUTHORED", "rating TABLE_COLUMN:INFERRED");
        });
    }

    /**
     * The recorded residue that is left: a {@code Node.id} field over a table with a literal
     * {@code id} column carries no directive at all, so nothing authored can mask the column
     * reading. The walk rejects the coordinate ({@code FieldBuilder.rejectShadowedIdColumn}: two
     * readings, different wire values, the author must pick), while the view shows the column claim
     * alone, one claim where the target model shows two and a violation, because the node-id
     * structural arm is not modelled yet. When it lands, this coordinate becomes the
     * structural-ambiguity rule's first live case and {@code BuildContext.rejectShadowedNodeId} its
     * migration target.
     */
    @Test
    void nodeIdShadowedColumnIsTheUnmodelledSecondStructuralClaim() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Customer", "id");
            seedBoundTable(dsl, GRAPH, "Customer", "customer", PKG, PUBLIC, "customer");
            seedColumn(dsl, PKG, PUBLIC, "customer", "id", 1, "ID");
            var rows = claims(dsl);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getColumnName()).isEqualTo("id");
        });
    }

    // ===== The table resolution the classifier stands on =====

    /**
     * The resolution's own row: which catalog table the type's {@code @table} binds to, named by the
     * whole {@code sql_table} key, with the arity that says the binding is unambiguous. A binding
     * under which no field matches a column is still a binding, which is the case a column claim
     * cannot speak for and the reason this view is not the classifier's private CTE.
     */
    @Test
    void theBindingNamesTheResolvedCatalogTable() {
        withFilm(dsl -> {
            seedField(dsl, GRAPH, "Film", "unmatched");
            var rows = boundTables(dsl);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.getTypeName()).isEqualTo("Film");
            assertThat(row.getTableName()).isEqualTo("film");
            assertThat(row.getCandidates()).isEqualTo(1);
            assertThat(dsl.fetchExists(SQL_TABLE,
                SQL_TABLE.SOURCE_NAME.eq(row.getTableSourceName())
                    .and(SQL_TABLE.TABLE_SCHEMA.eq(row.getTableSchema()))
                    .and(SQL_TABLE.TABLE_NAME.eq(row.getTableName()))))
                .as("the three witness columns are a sql_table key")
                .isTrue();
            assertThat(claims(dsl))
                .as("no field of the bound table matched, so no claim, and still one binding")
                .isEmpty();
        });
    }

    /** The omitted argument's fallback, which {@code graphitron_table} defers: the type's own name. */
    @Test
    void anOmittedNameResolvesFromTheTypeName() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", null);
            assertThat(boundTables(dsl).map(r -> r.getTableName())).containsExactly("film");
        });
    }

    /**
     * The population the column-match arm declines on, kept as data rather than dropped: two
     * candidates are two rows, each saying there were two, which is what lets one reader refuse the
     * binding and another offer both without either re-deriving the resolution. The unambiguous
     * binding beside it is what makes the arity a property of the spelling rather than a count of
     * everything the graph resolved.
     */
    @Test
    void anAmbiguousReferenceYieldsEveryCandidateWithItsArity() {
        withSeededStore(GRAPH, dsl -> {
            seedBoundTable(dsl, GRAPH, "Dup", "dup", PKG, PUBLIC, "dup");
            seedBoundTable(dsl, GRAPH, "Solo", "solo", PKG, PUBLIC, "solo");
            seedSource(dsl, OTHER_PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, OTHER_PKG);
            seedTable(dsl, OTHER_PKG, "legacy", "dup");
            assertThat(boundTables(dsl).map(r -> r.getTypeName() + " " + r.getTableSchema()
                    + " " + r.getCandidates()))
                .containsExactlyInAnyOrder("Dup public 2", "Dup legacy 2", "Solo public 1");
        });
    }

    /** A root's binding is masked, the walk classifying a root before it reads one. */
    @Test
    void aRootTypesBindingIsMasked() {
        withSeededStore(GRAPH, dsl -> {
            seedBoundTable(dsl, GRAPH, "Query", "film", PKG, PUBLIC, "film");
            assertThat(boundTables(dsl)).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";
    private static final String PKG = "pkg.a";
    private static final String OTHER_PKG = "pkg.b";
    private static final String PUBLIC = "public";
    private static final String FILM_LANGUAGE_KEY = "film_language_id_fkey";

    /**
     * A one-source catalog holding {@code film} and {@code language}, with a key from the first to
     * the second so a case can write a path.
     *
     * <p>Both tables declare {@code title} and {@code name}, and the language copies sit at the
     * lower ordinals. That is the arrangement that makes a site's scope observable: the match
     * collapses to the first candidate in tier-then-ordinal order, so a rule pairing a field with
     * the wrong site's scope answers with the wrong table here rather than falling silent, and
     * silence is what a fixture with the column on one table only would have reported.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "name", 1, "NAME");
            seedColumn(dsl, PKG, PUBLIC, "film", "release_year", 2, "RELEASE_YEAR");
            seedColumn(dsl, PKG, PUBLIC, "film", "rating", 3, "RATING");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 4, "TITLE");
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_pkey", "PRIMARY KEY", null);
            seedTable(dsl, PKG, PUBLIC, "language");
            seedColumn(dsl, PKG, PUBLIC, "language", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "language", "name", 2, "NAME");
            seedConstraint(dsl, PKG, PUBLIC, "language", "language_pkey", "PRIMARY KEY", null);
            seedConstraint(dsl, PKG, PUBLIC, "film", FILM_LANGUAGE_KEY, "FOREIGN KEY", null);
            seedReferentialConstraint(dsl, PKG, PUBLIC, "film", FILM_LANGUAGE_KEY,
                PKG, PUBLIC, "language", "language_pkey");
            body.accept(dsl);
        });
    }

    /** The catalog with {@code type Film @table(name: "film")} on it, which is where most cases start. */
    private static void withFilm(Consumer<DSLContext> body) {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            body.accept(dsl);
        });
    }

    /** Every claim the graph under test carries, the population most cases assert the whole of. */
    private static Result<IntentColumnMatchClaimRecord> claims(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_COLUMN_MATCH_CLAIM)
            .where(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(GRAPH)).fetch();
    }

    /** The same for the resolution underneath, at the type grain the binding is keyed on. */
    private static Result<IntentBoundTableRecord> boundTables(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_BOUND_TABLE)
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(GRAPH)).fetch();
    }
}
