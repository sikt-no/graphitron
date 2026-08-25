package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_FILTER_ROLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentLookupKey;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOrderBy;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argument_filter_role} states: which rule resolves one argument's contribution
 * to the filter surface of the field it sits on.
 *
 * <p>The relation is a ranked fork, so most cases here are about precedence rather than about a
 * rule in isolation. An argument that only one rule could answer for would pass with the ranks in
 * any order, so wherever two rules can both reach a site the case arranges exactly that and asserts
 * which one won. The {@code @orderBy} argument named for a real column is the shape of it.
 *
 * <p>The two modifier columns get cases of their own for the same reason they are columns: a fixture
 * that only ever read the role would pass with {@code lookup_key} folded into the vocabulary, which
 * is the modelling this relation deliberately does not do.
 *
 * <p>Absence is asserted rather than inferred. Every silence here is a site the classifier rejects,
 * and a case that showed only the roles would leave a reader unable to tell a rejected site from an
 * unmodelled one.
 */
class ArgumentFilterRoleTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== The ranked fork =====

    /**
     * {@code @orderBy} wins over the name match, and the case makes the two contest: the argument is
     * named for a real column of the field's own table, so a relation reading the rules in the wrong
     * order would call it a predicate.
     */
    @Test
    void anOrderByArgumentIsTheOrderingRuleEvenWhereItsNameReachesAColumn() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedOrderBy(dsl, GRAPH, "Query", "films", "title");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("title ORDER_BY");
        });
    }

    /**
     * The four reserved names are the pagination rule's, and they are a generator constant rather
     * than a captured fact: nothing marks them in the store, so the relation is where they are
     * stated. The case makes {@code first} contest a real column of that name.
     */
    @Test
    void theFourReservedNamesAreThePaginationRule() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "first", 4, "FIRST");
            for (String name : List.of("first", "last", "after", "before")) {
                seedArgument(dsl, GRAPH, "Query", "films", name, "String");
            }

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactlyInAnyOrder("first PAGINATE", "last PAGINATE",
                                           "after PAGINATE", "before PAGINATE");
        });
    }

    /**
     * An input-object argument contributes its own type's fields at their coordinates, so the row
     * here says only that the reader changes grain. It is not a predicate and it is not an absence.
     */
    @Test
    void anInputObjectArgumentIsAnExpansionAndNotAPredicate() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedType(dsl, GRAPH, "FilmFilter", "INPUT_OBJECT");
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "FilmFilter");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("filter INPUT_EXPANSION");
        });
    }

    /**
     * An authored {@code @nodeId} resolves by the decode, and the role is read off the node-id
     * instruction relation rather than off the directive, so the node-type resolution and every
     * decline it makes are inherited here rather than restated.
     */
    @Test
    void anAuthoredNodeIdResolvesByTheDecode() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", "Film");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("filmId NODE_ID");
        });
    }

    /**
     * {@code @nodeId} beside {@code @field(name:)} names two binding axes at once and the classifier
     * rejects it, so no rule answers here. The row is absent rather than carrying either reading.
     */
    @Test
    void aNodeIdArgumentAlsoNamingAColumnResolvesToNothing() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", "Film");
            seedArgumentBinding(dsl, GRAPH, "Query", "films", "filmId", "title");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The implicit node-id reading, and its three exits =====

    /**
     * An {@code ID} argument literally named {@code id} on a field returning a node type is that
     * node's id, with no directive to say so. The one rule here no relation stated before.
     */
    @Test
    void anIdArgumentNamedIdOnANodeReturningFieldIsReadAsANodeId() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            seedArgument(dsl, GRAPH, "Query", "films", "id", "ID");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("id NODE_ID");
        });
    }

    /**
     * A real column of that name shadows the implicit reading, and shadowing is a rejection rather
     * than a contest either reading wins, so the site resolves to nothing at all. The case above is
     * the same fixture without the column, which is what makes this one about the column.
     */
    @Test
    void aColumnOfThatNameShadowsTheImplicitReadingIntoSilence() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "id", 5, "ID");
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            seedArgument(dsl, GRAPH, "Query", "films", "id", "ID");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A list at arity one without {@code @lookupKey} is unwired as a node-id filter and the
     * classifier lets it fall through to the name match, so a column of that name answers here where
     * the scalar case above went silent. The two cases differ in the list wrapper alone.
     */
    @Test
    void aListImplicitReadingAtArityOneFallsThroughToTheNameMatch() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "id", 5, "ID");
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            listArgument(dsl, "id", "ID");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("id NAME_MATCHED");
        });
    }

    /**
     * A composite key without {@code @lookupKey} is unwired for a filter and rejected, so the site
     * resolves to nothing. Arity is the whole of the difference from the first implicit case.
     */
    @Test
    void aCompositeKeyImplicitReadingWithoutALookupKeyResolvesToNothing() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 1, "language_id");
            seedArgument(dsl, GRAPH, "Query", "films", "id", "ID");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The same composite key with {@code @lookupKey} is wired, and reads as the node-id rule. */
    @Test
    void aCompositeKeyImplicitReadingWithALookupKeyIsWired() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 1, "language_id");
            seedArgument(dsl, GRAPH, "Query", "films", "id", "ID");
            seedArgumentLookupKey(dsl, GRAPH, "Query", "films", "id");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("id NODE_ID lookupKey");
        });
    }

    /**
     * A field returning a type nothing declares a node reads its {@code id} argument as an ordinary
     * name, which is the premise the implicit cases above rest on: without a node type there is no
     * implicit reading to prefer.
     */
    @Test
    void anIdArgumentOnANonNodeReturningFieldIsAnOrdinaryName() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedColumn(dsl, PKG, PUBLIC, "film", "id", 5, "ID");
            seedArgument(dsl, GRAPH, "Query", "films", "id", "ID");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("id NAME_MATCHED");
        });
    }

    // ===== The name match, and what it covers =====

    /** An argument whose name reaches a column on the field's own table is a predicate. */
    @Test
    void anArgumentWhoseNameReachesAColumnIsAPredicate() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("title NAME_MATCHED");
        });
    }

    /**
     * A {@code @reference}-pathed argument is the same role as a plain one, and that is the payoff
     * of resolving the column first: where the predicate lands is the scope's basis, so the two
     * shapes need no separate arm here.
     */
    @Test
    void aPathedArgumentIsTheSameRuleAsAPlainOne() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "name", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "films", "name", 0);
            seedArgumentReferenceStep(dsl, GRAPH, "Query", "films", "name", 0, 0, "language", null);

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("name NAME_MATCHED");
        });
    }

    /** A name that reaches no column is a rejection's site, and no rule answers for it. */
    @Test
    void anArgumentWhoseNameReachesNoColumnResolvesToNothing() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "runtime", "String");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The two modifiers =====

    /**
     * {@code @lookupKey} rides beside the role rather than replacing it: the argument is still
     * resolved by the name match, and what changes is that the value is keyed rather than compared.
     * Folding it into the vocabulary would have split this role in two.
     */
    @Test
    void aLookupKeyIsAModifierAndNotARole() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentLookupKey(dsl, GRAPH, "Query", "films", "title");

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("title NAME_MATCHED lookupKey");
        });
    }

    /** A field-level {@code override} suppresses the generated predicate of every argument on it. */
    @Test
    void aFieldLevelOverrideSuppressesEveryArgumentOnTheField() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgument(dsl, GRAPH, "Query", "films", "description", "String");
            seedFieldCondition(dsl, GRAPH, "Query", "films", true);

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactlyInAnyOrder("title NAME_MATCHED suppressed",
                                           "description NAME_MATCHED suppressed");
        });
    }

    /**
     * An argument-level {@code override} suppresses that argument alone, and the sibling beside it is
     * what makes the case about the cascade's reach rather than about its existence.
     */
    @Test
    void anArgumentLevelOverrideSuppressesThatArgumentAlone() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgument(dsl, GRAPH, "Query", "films", "description", "String");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "title", true);

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactlyInAnyOrder("title NAME_MATCHED suppressed",
                                           "description NAME_MATCHED");
        });
    }

    /**
     * An {@code @condition} the author wrote without {@code override:} suppresses nothing: the
     * omitted spelling is NULL and the default it stands for is false, which a relation reading the
     * column as a truth value has to get right rather than treat as unknown.
     */
    @Test
    void anOmittedOverrideSuppressesNothing() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "title", null);

            assertThat(rows(dsl).map(ArgumentFilterRoleTest::render))
                .containsExactly("title NAME_MATCHED");
        });
    }

    /** The graph partition holds: a sibling graph's arguments are none of this one's. */
    @Test
    void aSiblingGraphResolvesNoneOfTheseArguments() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    /**
     * {@code film} carries {@code title} and {@code description}, and {@code language} carries
     * {@code name}, so an argument can reach a column on the field's own table or one a path away.
     * Neither table has an {@code id} column; the cases about shadowing seed one, which is what makes
     * the shadow the case's subject rather than the fixture's.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "film");
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 0, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "description", 1, "DESCRIPTION");
            seedTable(dsl, PKG, PUBLIC, "language");
            seedConstraint(dsl, PKG, PUBLIC, "language", "language_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "language", "name", 0, "NAME");
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_language_id_fkey", "FOREIGN KEY", null);
            seedReferentialConstraint(dsl, PKG, PUBLIC, "film", "film_language_id_fkey",
                PKG, PUBLIC, "language", "language_pkey");
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    /** {@code Query.films: [Film]} with {@code Film} bound to {@code film}: the site every case uses. */
    private static void filmsField(DSLContext dsl) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedField(dsl, GRAPH, "Query", "films", "Film", true);
    }

    /** One list-valued argument, the wrapper being what two of the implicit-reading cases differ in. */
    private static void listArgument(DSLContext dsl, String argumentName, String namedType) {
        seedArgument(dsl, GRAPH, "Query", "films", argumentName, namedType);
        dsl.update(GRAPHQL_ARGUMENT)
            .set(GRAPHQL_ARGUMENT.IS_LIST, true)
            .set(GRAPHQL_ARGUMENT.ITEM_NON_NULL, false)
            .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(GRAPH)
                .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq("Query"))
                .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq("films"))
                .and(GRAPHQL_ARGUMENT.ARGUMENT_NAME.eq(argumentName)))
            .execute();
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select().from(INTENT_ARGUMENT_FILTER_ROLE)
            .where(INTENT_ARGUMENT_FILTER_ROLE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_ARGUMENT_FILTER_ROLE.ARGUMENT_NAME)
            .fetch();
    }

    /** {@code argument ROLE} plus whichever modifiers are set, so a case reads all three at once. */
    private static String render(Record row) {
        return row.get(INTENT_ARGUMENT_FILTER_ROLE.ARGUMENT_NAME)
            + " " + row.get(INTENT_ARGUMENT_FILTER_ROLE.ROLE)
            + (Boolean.TRUE.equals(row.get(INTENT_ARGUMENT_FILTER_ROLE.LOOKUP_KEY)) ? " lookupKey" : "")
            + (Boolean.TRUE.equals(row.get(INTENT_ARGUMENT_FILTER_ROLE.SUPPRESSED)) ? " suppressed" : "");
    }
}
