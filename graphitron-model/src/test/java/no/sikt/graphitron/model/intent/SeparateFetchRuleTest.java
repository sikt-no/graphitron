package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SEPARATE_FETCH;
import static no.sikt.graphitron.model.test.SeededStore.seedBoundTable;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedRootOperation;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSplitQuery;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedTenantFanOut;
import static no.sikt.graphitron.model.test.SeededStore.seedTypeBackingClass;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the separate-fetch relation returns: which coordinates cost a statement of their own rather
 * than a column of the statement their parent is already running. A field with a row here is a
 * second trip to the database; a field with none that resolves against its parent's table costs
 * nothing beyond that parent's own SELECT.
 *
 * <p>Five rules answer, and each is its own arm over its own base relation rather than a branch of
 * one. Two are markers an author writes on the field ({@code @splitQuery} and {@code @tenantFanOut}),
 * which stay two literals because which marker forced the split is what an author reads back. One is
 * the non-root {@code @service} contract. One is membership of a bound root operation type, keyed by
 * the binding and never by the conventional name, so a renamed root and an unbound type named
 * {@code Query} are the pair that tells the two readings apart. The fifth is the implicit split no
 * author writes, and it is the one with joins to get wrong.
 *
 * <p>That fifth arm reaches a field of a type the backing closure grounds on a class, where the
 * field names a type of its own that is bound to a table. There is no enclosing statement for such a
 * field to be projected out of, the parent being a Java object a producer handed back. It draws two
 * boundaries. A parent both populations answer is read as a table row and hands nothing separately,
 * which is an anti-join over the {@code @table} population and a precedence transcribed rather than
 * invented. And the closure holds input objects beside objects, while an input coordinate is not a
 * fetch at all, so the parent's kind is guarded where the child's is not.
 *
 * <p>A coordinate several rules reach is several rows, the arity being the answer rather than a
 * precedence this relation holds an opinion about. A rule that reaches one coordinate twice, because
 * one type serves two root slots or because a child's spelling resolves to two candidate tables, is
 * still one row: the reach is not a count.
 *
 * <p>Absence carries weight here and is still not the complement's claim. Two populations are
 * deliberately unreached (a child behind a connection wrapper, and the polymorphic fan-in), so a
 * reader may say a field with a row is separately fetched and may not say a field without one is
 * inlined. The cases below assert the absences the arms do claim, and nothing wider.
 *
 * <p>Whether real SDL reaches these arms in the shape they read, and whether the two marker arms
 * name the coordinates the classification walk's own delivery gather names, is a different question
 * with a different home: {@code no.sikt.graphitron.rewrite.derive.SeparateFetchTest} runs that
 * differential over a real capture, and pins a producer-handed parent reaching the fifth arm through
 * the closure a writer derived rather than one a fixture stated.
 */
class SeparateFetchRuleTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";
    private static final String SERVICE_CLASS = "app.Service";
    private static final String HANDED_CLASS = "app.PayloadRow";

    // ===== The five rules =====

    /**
     * One coordinate per arm on one schema, plus fields no arm reaches, so an over-reaching arm
     * fails as loudly as a missing one. The two markers sit on sibling fields of one parent, which
     * is what keeps them two literals rather than one.
     */
    @Test
    void everyRuleArmAnswersWithItsOwnLiteral() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);

            bindTable(dsl, GRAPH, "Film", "film");
            bindTable(dsl, GRAPH, "Language", "language");
            seedField(dsl, GRAPH, "Film", "title");
            seedField(dsl, GRAPH, "Film", "inlined", "Language", false);
            seedField(dsl, GRAPH, "Film", "deferred", "Language", false);
            seedSplitQuery(dsl, GRAPH, "Film", "deferred");
            seedField(dsl, GRAPH, "Film", "fanned", "Language", true);
            seedTenantFanOut(dsl, GRAPH, "Film", "fanned");
            seedField(dsl, GRAPH, "Film", "rated");
            seedService(dsl, GRAPH, "Film", "rated", SERVICE_CLASS, "get");

            seedTypeBackingClass(dsl, GRAPH, "Payload", HANDED_CLASS);
            seedField(dsl, GRAPH, "Payload", "name");
            seedField(dsl, GRAPH, "Payload", "film", "Film", false);

            assertThat(rules(dsl))
                .as("a column of the parent's own row and a child inlined into its statement are "
                    + "the population this relation exists to exclude")
                .containsExactlyInAnyOrder(
                    "Query.films=ROOT_OPERATION",
                    "Film.deferred=SPLIT_QUERY",
                    "Film.fanned=TENANT_FAN_OUT",
                    "Film.rated=SERVICE",
                    "Payload.film=RECORD_HANDED_PARENT");
        });
    }

    /**
     * The service arm's root guard, against the root arm it defers to. A root field's fetch is
     * already its own entry point, so the service there is not a second reason for the same thing.
     * Both sides are stated on a binding rather than on a name: the root is a type named nothing in
     * particular, and the type named {@code Query} is bound to no slot.
     */
    @Test
    void aServiceOnARootFieldIsTheRootsOwnEntryPoint() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Api");
            seedField(dsl, GRAPH, "Api", "films");
            seedService(dsl, GRAPH, "Api", "films", SERVICE_CLASS, "get");

            seedDeclaredType(dsl, GRAPH, "Query", "OBJECT");
            seedField(dsl, GRAPH, "Query", "rated");
            seedService(dsl, GRAPH, "Query", "rated", SERVICE_CLASS, "get");

            assertThat(rulesFor(dsl, "Api", "films"))
                .as("named nothing in particular, and a root all the same")
                .containsExactly("ROOT_OPERATION");
            assertThat(rulesFor(dsl, "Query", "rated"))
                .as("named for a root, and bound to no slot, so its service is an ordinary one")
                .containsExactly("SERVICE");
        });
    }

    /**
     * A coordinate four rules reach is four rows. The arity is the answer, so no rule wins a
     * precedence contest this relation would have to hold an opinion about, and each rule's witness
     * stays one join away in the arm's own base relation.
     */
    @Test
    void aCoordinateSeveralRulesReachIsSeveralRows() {
        withSeededStore(GRAPH, dsl -> {
            seedTypeBackingClass(dsl, GRAPH, "Payload", HANDED_CLASS);
            bindTable(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Payload", "films", "Film", true);
            seedSplitQuery(dsl, GRAPH, "Payload", "films");
            seedTenantFanOut(dsl, GRAPH, "Payload", "films");
            seedService(dsl, GRAPH, "Payload", "films", SERVICE_CLASS, "get");

            assertThat(rulesFor(dsl, "Payload", "films")).containsExactlyInAnyOrder(
                "SPLIT_QUERY", "TENANT_FAN_OUT", "SERVICE", "RECORD_HANDED_PARENT");
        });
    }

    /**
     * The other side of that arity: a rule reaching one coordinate by several routes is still one
     * row, because what the relation states is that the fetch is its own and not how many ways it
     * came to be. Two routes, one per arm that can have them: a type serving both root slots, and a
     * handed parent's child whose spelling two schemas both answer.
     */
    @Test
    void aRuleThatReachesACoordinateTwiceStillAnswersOnce() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Api");
            seedRootOperation(dsl, GRAPH, "MUTATION", "Api");
            seedField(dsl, GRAPH, "Api", "films", "Film", true);

            seedTypeBackingClass(dsl, GRAPH, "Payload", HANDED_CLASS);
            seedField(dsl, GRAPH, "Payload", "film", "Film", false);
            bindContestedTable(dsl, GRAPH, "Film", "film");

            assertThat(rules(dsl))
                .as("an ambiguously bound child splits, and its ambiguity is a fact about the "
                    + "binding rather than a second fetch")
                .containsExactlyInAnyOrder(
                    "Api.films=ROOT_OPERATION",
                    "Payload.film=RECORD_HANDED_PARENT");
        });
    }

    // ===== The handed parent =====

    /**
     * The implicit split and both ends of the join it stands on. Under one handed parent are the
     * three shapes the arm has to tell apart: a scalar read off the member it came with, a child
     * whose own type is bound to no table and is another object in the same handed graph, and a
     * child that names a table and so is a trip of its own. The fourth shape is next door: the same
     * table-bound child under a parent nothing hands, where the enclosing statement exists and the
     * child is projected out of it.
     */
    @Test
    void aHandedParentSplitsOnlyTheChildrenThatNameATableOfTheirOwn() {
        withSeededStore(GRAPH, dsl -> {
            bindTable(dsl, GRAPH, "Film", "film");
            seedDeclaredType(dsl, GRAPH, "Plain", "OBJECT");

            seedTypeBackingClass(dsl, GRAPH, "Payload", HANDED_CLASS);
            seedField(dsl, GRAPH, "Payload", "name");
            seedField(dsl, GRAPH, "Payload", "plain", "Plain", false);
            seedField(dsl, GRAPH, "Payload", "film", "Film", false);

            seedDeclaredType(dsl, GRAPH, "Bare", "OBJECT");
            seedField(dsl, GRAPH, "Bare", "film", "Film", false);

            assertThat(rules(dsl))
                .as("the arm is keyed on the coordinate's own parent and the coordinate's own "
                    + "named type, neither of which any other row in this graph supplies")
                .containsExactly("Payload.film=RECORD_HANDED_PARENT");
        });
    }

    /**
     * A parent both populations answer is read as a table row. {@code Film} carries {@code @table}
     * and the closure also grounds it on a class, so its table-bound child would split if the arm
     * read the closure alone. The walk resolves that pair by reading the binding and never
     * consulting the class; the anti-join is that precedence transcribed, which is why the
     * disagreement stays observable on the backing conflict relation instead of being folded in
     * here.
     */
    @Test
    void aParentBothPopulationsBackIsReadAsATableRow() {
        withSeededStore(GRAPH, dsl -> {
            bindTable(dsl, GRAPH, "Film", "film");
            bindTable(dsl, GRAPH, "Language", "language");
            seedTypeBackingClass(dsl, GRAPH, "Film", "app.FilmRow");
            seedField(dsl, GRAPH, "Film", "language", "Language", false);

            seedTypeBackingClass(dsl, GRAPH, "Payload", HANDED_CLASS);
            seedField(dsl, GRAPH, "Payload", "film", "Film", false);

            assertThat(rules(dsl))
                .as("the premise: without the handed parent beside it the silence would be vacuous")
                .containsExactly("Payload.film=RECORD_HANDED_PARENT");
        });
    }

    /**
     * The parent's kind, which is guarded where the child's is not. The closure backs input objects
     * beside objects, a producer's parameter grounding the type of the argument that feeds it, and
     * an input coordinate is not a fetch: nothing runs a statement to deliver an argument. The
     * object parent beside it carries the identical shape, so what separates them is the kind and
     * nothing else.
     */
    @Test
    void anInputObjectTheClosureBacksIsNoFetchAtAll() {
        withSeededStore(GRAPH, dsl -> {
            bindTable(dsl, GRAPH, "Film", "film");

            seedDeclaredType(dsl, GRAPH, "Filter", "INPUT_OBJECT");
            seedTypeBackingClass(dsl, GRAPH, "Filter", "app.FilterDto");
            seedField(dsl, GRAPH, "Filter", "film", "Film", false);

            seedTypeBackingClass(dsl, GRAPH, "Payload", HANDED_CLASS);
            seedField(dsl, GRAPH, "Payload", "film", "Film", false);

            assertThat(rules(dsl)).containsExactly("Payload.film=RECORD_HANDED_PARENT");
        });
    }

    // ===== The partition =====

    /**
     * One workspace's graphs do not read each other's rules, on every arm that joins at all. The
     * sibling supplies exactly what each of this graph's arms would have to reach across for: a
     * root binding on the type carrying the service, a backing for the parent that has none here, a
     * table for the child that names none here, and a {@code @table} on the handed parent whose
     * absence is what lets it hand anything.
     */
    @Test
    void aGraphSplitsNothingOnItsSiblingsBehalf() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, OTHER_GRAPH);

            seedField(dsl, GRAPH, "Thing", "rated");
            seedService(dsl, GRAPH, "Thing", "rated", SERVICE_CLASS, "get");
            bindTable(dsl, GRAPH, "Film", "film");
            seedDeclaredType(dsl, GRAPH, "Plain", "OBJECT");
            seedTypeBackingClass(dsl, GRAPH, "Payload", HANDED_CLASS);
            seedField(dsl, GRAPH, "Payload", "film", "Film", false);
            seedField(dsl, GRAPH, "Payload", "plain", "Plain", false);
            seedDeclaredType(dsl, GRAPH, "Bare", "OBJECT");
            seedField(dsl, GRAPH, "Bare", "film", "Film", false);

            seedRootOperation(dsl, OTHER_GRAPH, "QUERY", "Thing");
            seedField(dsl, OTHER_GRAPH, "Thing", "listed");
            seedTypeBackingClass(dsl, OTHER_GRAPH, "Bare", "app.BareRow");
            bindTable(dsl, OTHER_GRAPH, "Plain", "plain");
            bindTable(dsl, OTHER_GRAPH, "Payload", "payload");

            assertThat(allRules(dsl)).containsExactlyInAnyOrder(
                GRAPH + " Thing.rated=SERVICE",
                GRAPH + " Payload.film=RECORD_HANDED_PARENT",
                OTHER_GRAPH + " Thing.listed=ROOT_OPERATION");
        });
    }

    // ===== Readings =====

    /** Every separately fetched coordinate of this graph, as {@code Type.field=RULE}. */
    private static List<String> rules(DSLContext dsl) {
        return dsl.selectFrom(INTENT_FIELD_SEPARATE_FETCH)
            .where(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.getTypeName() + "." + r.getFieldName() + "=" + r.getRule());
    }

    /** Every rule reaching one coordinate, which is where the arity is read. */
    private static List<String> rulesFor(DSLContext dsl, String typeName, String fieldName) {
        return dsl.select(INTENT_FIELD_SEPARATE_FETCH.RULE)
            .from(INTENT_FIELD_SEPARATE_FETCH)
            .where(INTENT_FIELD_SEPARATE_FETCH.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_FIELD_SEPARATE_FETCH.TYPE_NAME.eq(typeName))
            .and(INTENT_FIELD_SEPARATE_FETCH.FIELD_NAME.eq(fieldName))
            .fetch(INTENT_FIELD_SEPARATE_FETCH.RULE);
    }

    /** The same over the whole store, graph first, so the partition is read as a value. */
    private static List<String> allRules(DSLContext dsl) {
        return dsl.selectFrom(INTENT_FIELD_SEPARATE_FETCH)
            .fetch(r -> r.getGraphName() + " " + r.getTypeName() + "." + r.getFieldName()
                + "=" + r.getRule());
    }

    // ===== Fixtures =====

    /**
     * A type bound to a table of the name given, under a catalog partition of this graph's own, so
     * two graphs binding the same name are two tables and not one shared row.
     */
    private static void bindTable(DSLContext dsl, String graphName, String typeName, String tableName) {
        seedBoundTable(dsl, graphName, typeName, tableName, catalogOf(graphName), "public", tableName);
    }

    /**
     * The same binding resolving to two tables, which is what one name two schemas both declare
     * looks like from the type's side: two candidates, and no decline.
     */
    private static void bindContestedTable(DSLContext dsl, String graphName, String typeName,
                                           String tableName) {
        seedTableBinding(dsl, graphName, typeName, tableName);
        seedSource(dsl, catalogOf(graphName), "JOOQ_SCHEMA");
        seedGraphSource(dsl, graphName, catalogOf(graphName));
        seedTable(dsl, catalogOf(graphName), "public", tableName);
        seedTable(dsl, catalogOf(graphName), "archive", tableName);
    }

    private static String catalogOf(String graphName) {
        return "jooq." + graphName;
    }
}
