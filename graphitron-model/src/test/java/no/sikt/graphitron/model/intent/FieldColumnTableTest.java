package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_COLUMN_SCOPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_COLUMN_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedError;
import static no.sikt.graphitron.model.test.SeededStore.seedExternalField;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldSynthesis;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedPivot;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_field_column_table} returns: which table a column name written at a field's
 * site resolves against, when that table is not the one the field's own parent is bound to. It pins
 * {@code intent_field_column_scope} with it, the navigation the override reads and the structural
 * column-match classifier reads too. Two relations in one class because they are one derivation seen
 * twice: the cases above pin the override's boundary, and the section near the tail pins the third
 * rule that boundary drops, the precedence among the three, and the one place the two deliberately
 * disagree.
 *
 * <p>Half of these cases assert that a coordinate produces <em>no</em> row, which is the view's
 * central claim rather than a gap in it. The relation only overrides a parent's own scope, so
 * absence is the answer "the parent's scope stands", and a case that pins absence is pinning the
 * boundary of the override. Reading those cases as untested behaviour gets the relation backwards.
 *
 * <p>Every input is stated as rows: a small catalog, the two type bindings the cases depart from and
 * arrive at, and then whatever one field the case is about. Several of the states a case needs are
 * ones no author writes directly. A macro-rewritten field is a row whose effective type and authored
 * type disagree; a contested coordinate is two claims plus the walk's own registration of the
 * coordinate, which no capture produces on its own; a path reaching nothing is an element spelling a
 * table the catalog does not hold. Stating them is what puts all three in one fixture. That the
 * pipeline above reaches these states from real SDL is pinned beside the pipeline.
 */
class FieldColumnTableTest {

    // ===== A path names the table =====

    /**
     * The single-element case: the column named on the field lives on the path's terminal table,
     * not on the parent's own, which is the whole reason this relation exists.
     */
    @Test
    void aReferencePathResolvesItsTerminalTable() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languageName");
            seedPathOn(dsl, "languageName", "language");

            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("PATH_TERMINAL");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .isEqualToIgnoringCase("language");
            assertThat(scopeRow(dsl, "Film", "languageName").orElseThrow()
                .get(INTENT_FIELD_COLUMN_SCOPE.TABLE_NAME))
                .as("film declares two keys to language, and one destination is one row")
                .isEqualToIgnoringCase("language");
        });
    }

    /**
     * A terminal naming a table two schemas both declare reaches two of them and so answers
     * nothing, which the override reads as the same silence a spelling matching nothing gets. One
     * boundary with two causes: the rule demands a destination, and two destinations is not one.
     */
    @Test
    void aTerminalReachingTwoTablesIsTheSameSilence() {
        withCollidingTable(dsl -> {
            seedField(dsl, GRAPH, "Film", "languageName");
            seedPathOn(dsl, "languageName", "language");

            assertThat(scopeRow(dsl, "Film", "languageName")).isEmpty();
            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("UNRESOLVED_PATH");
        });
    }

    /** A path of several elements resolves the last one; the elements between are not answers. */
    @Test
    void aMultiElementPathResolvesItsLastElement() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "actorName");
            seedPathOn(dsl, "actorName", "film_actor", "actor");

            var row = row(dsl, "Film", "actorName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("PATH_TERMINAL");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isEqualToIgnoringCase("actor");
        });
    }

    /**
     * The repeatable directive collapsed to one answer: a field carrying two {@code @reference}
     * applications resolves the first one's terminal and not the second's, and contributes one row
     * rather than one per application. Which application answers has to be picked somewhere, and a
     * relation whose grain is the field is where; leaving both would hand every consumer two tables
     * for one site and no rule for choosing between them.
     */
    @Test
    void twoApplicationsResolveTheFirstOnesTerminal() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languageName");
            seedPathOn(dsl, "languageName", "language");
            seedFieldReference(dsl, GRAPH, "Film", "languageName", 1);
            seedFieldReferenceStep(dsl, GRAPH, "Film", "languageName", 1, 0, "film_actor", null);

            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("PATH_TERMINAL");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .as("the second application reaches film_actor and is not the answer")
                .isEqualToIgnoringCase("language");
        });
    }

    /**
     * An authored path outranks the field's own named type: the path is where the author said the
     * value comes from, so a field whose named type is bound elsewhere still reads the terminal.
     */
    @Test
    void anAuthoredPathOutranksTheNamedTypesOwnTable() {
        withBoundTypes(dsl -> {
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Film", "credits", "Actor", false);
            seedPathOn(dsl, "credits", "film_actor");

            var row = row(dsl, "Film", "credits").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS))
                .as("both rules could fire here; the path is the one that wins")
                .isEqualTo("PATH_TERMINAL");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .isEqualToIgnoringCase("film_actor");
            assertThat(scopeRow(dsl, "Film", "credits").orElseThrow()
                .get(INTENT_FIELD_COLUMN_SCOPE.BASIS))
                .as("and it wins by the other rule standing down, not by outranking it")
                .isEqualTo("PATH_TERMINAL");
        });
    }

    /**
     * A path naming a table the catalog does not hold reaches nothing, and the silence is the point:
     * without it the parent's own table would stand in and offer columns from the wrong end of a
     * join the author is still writing.
     */
    @Test
    void aPathReachingNoTableIsSilentRatherThanFallingBack() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languageName");
            seedPathOn(dsl, "languageName", "no_such_table");

            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("UNRESOLVED_PATH");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isNull();
        });
    }

    // ===== A named type names the table =====

    /**
     * The no-path case: a field navigating to a table-bound type resolves that type's table, which
     * is where an ordering column named on a list field lives.
     */
    @Test
    void aFieldNavigatingToATableBoundTypeResolvesThatTypesTable() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languages", "Language", true);

            var row = row(dsl, "Film", "languages").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("NAMED_TYPE_TABLE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .isEqualToIgnoringCase("language");
        });
    }

    /**
     * A macro that rewrote the field's type expression does not move the answer: the rule reads the
     * type the author wrote, so a connection field still resolves its element's table rather than
     * the synthesized wrapper's, which is bound to nothing. The two spellings are stated apart here,
     * the field naming the wrapper and the synthesis row carrying {@code [Language!]!}.
     */
    @Test
    void aConnectionFieldResolvesItsElementsTable() {
        withBoundTypes(dsl -> {
            seedType(dsl, GRAPH, "LanguageConnection", "OBJECT");
            seedField(dsl, GRAPH, "Film", "languages", "LanguageConnection", false);
            seedFieldSynthesis(dsl, GRAPH, "Film", "languages", "CONNECTION", "[Language!]!");

            var row = row(dsl, "Film", "languages").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS)).isEqualTo("NAMED_TYPE_TABLE");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME))
                .as("the field's own named type is now the connection wrapper")
                .isEqualToIgnoringCase("language");
        });
    }

    /**
     * A scalar field contributes no row. Its column lives on its parent's own table, and a reader
     * already holding that binding needs no relation to be told so.
     */
    @Test
    void aScalarFieldLeavesItsParentsScopeStanding() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "title");
            assertThat(row(dsl, "Film", "title")).isEmpty();
        });
    }

    /** A root's field navigates from no scope of its own, so the named-type rule does not fire. */
    @Test
    void aRootFieldNavigatesFromNoScope() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            assertThat(row(dsl, "Query", "films")).isEmpty();
        });
    }

    /**
     * A named type of any kind but OBJECT is a different question. A table-bound interface is one
     * table per participant, not one table, so the field's column names do not resolve against it.
     */
    @Test
    void aTableBoundInterfaceIsADifferentQuestion() {
        withBoundTypes(dsl -> {
            seedDeclaredType(dsl, GRAPH, "Media", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "Media", "film");
            seedField(dsl, GRAPH, "Film", "related", "Media", false);

            assertThat(row(dsl, "Film", "related")).isEmpty();
        });
    }

    /**
     * An authored claim diverts the field: its value comes from the claim, not from the type it
     * names, so the named-type rule stands down. The guard is an anti-join against the claims
     * rather than a list of directives, so it covers every claim the vocabulary carries.
     */
    @Test
    void anAuthoredClaimDivertsTheFieldFromItsNamedType() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languages", "Language", true);
            seedService(dsl, GRAPH, "Film", "languages", "no.example.Svc", "langs");

            assertThat(row(dsl, "Film", "languages")).isEmpty();
        });
    }

    /**
     * A pivot diverts the field the same way, and is named in the rule directly rather than reached
     * through the claims: the vocabulary has no arm for it yet, and a pivoted field reads its
     * columns off the pivot rather than off the type it names. The explicit guard folds into the
     * anti-join above the day that arm lands, and this case is what would notice it going missing
     * instead.
     */
    @Test
    void aPivotDivertsTheFieldThoughNoClaimNamesIt() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languages", "Language", true);
            seedPivot(dsl, GRAPH, "Film", "languages", "kind", "value");

            assertThat(row(dsl, "Film", "languages")).isEmpty();
        });
    }

    /**
     * An ambiguous binding answers on neither binding rule: a type bound to a name two schemas both
     * declare gives a field navigating to it no scope, and gives its own scalar fields none either.
     * The demand is what carries the one-row-per-site property the column-match classifier joins on,
     * a site with two tables being two rows at one coordinate.
     */
    @Test
    void anAmbiguousBindingAnswersOnNeitherRule() {
        withCollidingTable(dsl -> {
            seedTableBinding(dsl, GRAPH, "Language", "language");
            seedField(dsl, GRAPH, "Language", "name");
            seedField(dsl, GRAPH, "Film", "languages", "Language", true);

            assertThat(scopeRow(dsl, "Language", "name")).isEmpty();
            assertThat(scopeRow(dsl, "Film", "languages")).isEmpty();
        });
    }

    // ===== The coordinate's claims are contested =====

    /**
     * A conflicted coordinate is silent even where a rule would otherwise resolve a table: while
     * the author's claims disagree, no column name at the site has a settled scope to resolve in.
     */
    @Test
    void aConflictedCoordinateIsSilentEvenWhereARuleWouldResolve() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languageName");
            seedPathOn(dsl, "languageName", "language");
            seedContestedClaims(dsl, "languageName");

            var row = row(dsl, "Film", "languageName").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.BASIS))
                .as("the path resolves to language, and the contested claims still win")
                .isEqualTo("CONFLICTED");
            assertThat(row.get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isNull();
        });
    }

    /**
     * A coordinate both silences reach reports the contested one. The two are not interchangeable
     * even though the disposition is: the basis is what a consumer explains its answer with, and
     * "the claims here disagree" is the sentence an author acts on, an unwalkable path being the
     * lesser complaint while the site's classification is still contested.
     */
    @Test
    void contestedClaimsOutrankAnUnwalkablePath() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languageName");
            seedPathOn(dsl, "languageName", "no_such_table");
            seedContestedClaims(dsl, "languageName");

            assertThat(row(dsl, "Film", "languageName").orElseThrow()
                .get(INTENT_FIELD_COLUMN_TABLE.BASIS))
                .isEqualTo("CONFLICTED");
        });
    }

    /**
     * A conflict the author declared on the type is not this relation's, whose grain is the field.
     * The two grains share one conflict relation and it keys them apart with a null field name, so
     * the arm reading it has to say which grain it wants; carrying a type-grain row through would
     * put a row in this relation that names no site at all.
     */
    @Test
    void aTypeGrainConflictIsNotASiteHere() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "title");
            seedError(dsl, GRAPH, "Film");

            assertThat(dsl.fetchCount(INTENT_FIELD_COLUMN_TABLE,
                INTENT_FIELD_COLUMN_TABLE.FIELD_NAME.isNull()))
                .as("@table and @error contest the type, and no row here names the type alone")
                .isZero();
        });
    }

    // ===== The navigation underneath, and the rule this view drops =====

    /**
     * The third rule, the one the override view exists to drop: a leaf field's column names resolve
     * in its parent's own scope. The scope says so with a row; the override says so with none, and
     * {@link #aScalarFieldLeavesItsParentsScopeStanding} pins that half. Both readings are the same
     * navigation, which is the point of the two relations sharing one.
     */
    @Test
    void aScalarFieldResolvesInItsParentsOwnScope() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "title");

            var row = scopeRow(dsl, "Film", "title").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_SCOPE.BASIS)).isEqualTo("PARENT_BINDING");
            assertThat(row.get(INTENT_FIELD_COLUMN_SCOPE.TABLE_NAME)).isEqualToIgnoringCase("film");
        });
    }

    /**
     * A field of a parent nothing binds resolves nowhere, which is absence rather than a silence:
     * the scope's contract is that a row means "names resolve here", so a plain SDL object's field
     * contributing none is the whole answer.
     */
    @Test
    void aFieldOfAnUnboundParentResolvesNowhere() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Note", "text");
            assertThat(scopeRow(dsl, "Note", "text")).isEmpty();
        });
    }

    /**
     * The parent must not stand in for a path that reaches nothing, and the scope is where that
     * refusal lives: a path-carrying field takes the path's terminal or no row at all. The override
     * view's {@code UNRESOLVED_PATH} silence is read off exactly this absence.
     */
    @Test
    void aPathReachingNoTableResolvesNowhereRatherThanInTheParent() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languageName");
            seedPathOn(dsl, "languageName", "no_such_table");

            assertThat(scopeRow(dsl, "Film", "languageName")).isEmpty();
        });
    }

    /**
     * The boundary between the two rules a bound parent's field could otherwise land in both of. A
     * field navigating to a table-bound type reads that type's table, because the columns named at
     * such a site are the ones the field's own rows have, and the parent rule does not reach it at
     * all: that rule is a leaf field's, so the two are disjoint by the named type's kind rather than
     * ranked against each other. The single-row assertion in {@link #scopeRow} is what would catch a
     * fixture where they overlapped after all.
     */
    @Test
    void aFieldNavigatingToABoundTypeReadsItRatherThanItsParent() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languages", "Language", true);

            var row = scopeRow(dsl, "Film", "languages").orElseThrow();
            assertThat(row.get(INTENT_FIELD_COLUMN_SCOPE.BASIS))
                .as("an object-typed field is the named-type rule's, never the parent rule's")
                .isEqualTo("NAMED_TYPE_TABLE");
            assertThat(row.get(INTENT_FIELD_COLUMN_SCOPE.TABLE_NAME))
                .isEqualToIgnoringCase("language");
        });
    }

    /**
     * The guard asymmetry between the two relations, run. A contested coordinate is silent in the
     * override view and still resolves in the scope, because the structural column-match classifier
     * reads the scope and its reading at a contested coordinate is what lets a diagnostic say which
     * claim overrode a column. Folding the conflict silence one relation down would take that away.
     */
    @Test
    void aContestedCoordinateStillResolvesInTheScope() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "title");
            seedContestedClaims(dsl, "title");

            assertThat(row(dsl, "Film", "title").orElseThrow().get(INTENT_FIELD_COLUMN_TABLE.BASIS))
                .as("the override view goes silent while the claims disagree")
                .isEqualTo("CONFLICTED");
            assertThat(scopeRow(dsl, "Film", "title").orElseThrow()
                .get(INTENT_FIELD_COLUMN_SCOPE.BASIS))
                .as("the navigation is unchanged by what claims the field")
                .isEqualTo("PARENT_BINDING");
        });
    }

    // ===== Partition =====

    /**
     * The graph partition: one workspace's graphs do not read each other's resolutions. Both graphs
     * declare {@code Film.languages} and both bind {@code Language}, to different tables and off the
     * one catalog they share, so a rule that lost its graph predicate would resolve each coordinate
     * against both bindings rather than quietly against the wrong one.
     */
    @Test
    void aSiblingGraphResolvesItsOwnBinding() {
        withBoundTypes(dsl -> {
            seedField(dsl, GRAPH, "Film", "languages", "Language", true);

            seedGraph(dsl, OTHER);
            seedGraphSource(dsl, OTHER, PKG);
            seedTableBinding(dsl, OTHER, "Film", "film");
            seedTableBinding(dsl, OTHER, "Language", "actor");
            seedField(dsl, OTHER, "Film", "languages", "Language", true);

            assertThat(row(dsl, GRAPH, "Film", "languages").orElseThrow()
                .get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isEqualToIgnoringCase("language");
            assertThat(row(dsl, OTHER, "Film", "languages").orElseThrow()
                .get(INTENT_FIELD_COLUMN_TABLE.TABLE_NAME)).isEqualToIgnoringCase("actor");
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String OTHER = "other";
    private static final String PKG = "pkg";
    private static final String PUBLIC = "public";

    /**
     * A catalog of four tables connected so that every path a case writes has a foreign key to walk:
     * {@code film} to {@code language} directly, and to {@code actor} through the join table that
     * declares keys to both of its ends.
     *
     * <p>{@code film} declares two keys to {@code language} rather than one, which is what the
     * terminal rule's own demand is stated in terms of: one destination reached by two routes is a
     * resolved terminal, where one route reaching two destinations is not. A single-key catalog
     * would let the rule demand either and no case would notice.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "language", "actor", "film_actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            foreignKey(dsl, "film", "film_language_id_fkey", "language");
            foreignKey(dsl, "film", "film_original_language_id_fkey", "language");
            foreignKey(dsl, "film_actor", "film_actor_film_id_fkey", "film");
            foreignKey(dsl, "film_actor", "film_actor_actor_id_fkey", "actor");
            body.accept(dsl);
        });
    }

    /** One foreign key from {@code table} to {@code referencedTable}'s primary key. */
    private static void foreignKey(DSLContext dsl, String table, String constraintName,
                                   String referencedTable) {
        seedConstraint(dsl, PKG, PUBLIC, table, constraintName, "FOREIGN KEY", null);
        seedReferentialConstraint(dsl, PKG, PUBLIC, table, constraintName,
            PKG, PUBLIC, referencedTable, referencedTable + "_pkey");
    }

    /**
     * The catalog plus the two bindings the cases depart from and arrive at,
     * {@code type Film @table(name: "film")} and {@code type Language @table(name: "language")}.
     * Each case then states the one field it is about, so what a case adds is what it turns on.
     */
    private static void withBoundTypes(Consumer<DSLContext> body) {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Language", "language");
            body.accept(dsl);
        });
    }

    /**
     * A second schema declaring {@code language}, with a foreign key from {@code film} reaching each
     * of them, so an unqualified spelling of the name has two destinations. Its own store rather
     * than a shape folded into the catalog above, an ambiguous {@code language} being what every
     * other case departs from having resolved.
     */
    private static void withCollidingTable(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "film");
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_pkey", "PRIMARY KEY", null);
            for (String schema : List.of(PUBLIC, "legacy")) {
                seedTable(dsl, PKG, schema, "language");
                seedConstraint(dsl, PKG, schema, "language", "language_pkey", "PRIMARY KEY", null);
                seedConstraint(dsl, PKG, PUBLIC, "film", "film_" + schema + "_fkey",
                    "FOREIGN KEY", null);
                seedReferentialConstraint(dsl, PKG, PUBLIC, "film", "film_" + schema + "_fkey",
                    PKG, schema, "language", "language_pkey");
            }
            seedTableBinding(dsl, GRAPH, "Film", "film");
            body.accept(dsl);
        });
    }

    /** One {@code @reference} on a {@code Film} field, its elements each spelling a table. */
    private static void seedPathOn(DSLContext dsl, String fieldName, String... tableRefs) {
        seedFieldReference(dsl, GRAPH, "Film", fieldName, 0);
        for (int position = 0; position < tableRefs.length; position++) {
            seedFieldReferenceStep(dsl, GRAPH, "Film", fieldName, 0, position,
                tableRefs[position], null);
        }
    }

    /**
     * Two mutually exclusive claims on a {@code Film} field. The conflict relation is total over
     * the authored claims and gates on nothing further, so the two directives are the whole
     * fixture and the disposition under assertion is the view's answer to a conflict rather than
     * its silence about a coordinate no population reached.
     */
    private static void seedContestedClaims(DSLContext dsl, String fieldName) {
        seedService(dsl, GRAPH, "Film", fieldName, "no.example.Svc", "get");
        seedExternalField(dsl, GRAPH, "Film", fieldName, "no.example.Fields", "rating");
    }

    /** The navigation row for a coordinate, at the same grain as the override above it. */
    private static Optional<Record> scopeRow(DSLContext dsl, String typeName, String fieldName) {
        var rows = dsl.select(INTENT_FIELD_COLUMN_SCOPE.fields())
            .from(INTENT_FIELD_COLUMN_SCOPE)
            .where(INTENT_FIELD_COLUMN_SCOPE.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_COLUMN_SCOPE.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_COLUMN_SCOPE.FIELD_NAME.eq(fieldName))
            .fetch();
        assertThat(rows.size())
            .as("the navigation carries at most one row per coordinate")
            .isLessThanOrEqualTo(1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** The one row for a coordinate of the graph under assertion. */
    private static Optional<Record> row(DSLContext dsl, String typeName, String fieldName) {
        return row(dsl, GRAPH, typeName, fieldName);
    }

    /** The one row for a coordinate of a named graph, the relation's grain being the field. */
    private static Optional<Record> row(DSLContext dsl, String graphName, String typeName,
                                        String fieldName) {
        var rows = dsl.select(INTENT_FIELD_COLUMN_TABLE.fields())
            .from(INTENT_FIELD_COLUMN_TABLE)
            .where(INTENT_FIELD_COLUMN_TABLE.GRAPH_NAME.eq(graphName))
            .and(INTENT_FIELD_COLUMN_TABLE.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_COLUMN_TABLE.FIELD_NAME.eq(fieldName))
            .fetch();
        assertThat(rows.size())
            .as("the relation carries at most one row per coordinate")
            .isLessThanOrEqualTo(1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
