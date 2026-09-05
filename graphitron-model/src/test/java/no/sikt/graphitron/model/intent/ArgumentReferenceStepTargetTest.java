package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_METHOD_ROUTE_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceElement;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedConditionMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldSynthesis;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argument_reference_step_hop} and {@code intent_argument_reference_step_target}
 * return: the same resolution the field-site pair performs, at the coordinate an argument occupies.
 *
 * <p>The hop view has no test of its own here, on the reasoning
 * {@link ReferenceStepTargetTest} states for its own sibling: every row that matters is a row the
 * chain either reached or refused, so pinning it separately would pin the same joins twice.
 *
 * <p>Two things are this relation's own and the rest is agreement. The departure is the field's
 * named type's binding rather than the enclosing type's, which is what an argument filtering a
 * field's result means and what lets a root field's argument have a departure at all. And the
 * agreement itself is asserted rather than left to inspection: the two views are textually parallel
 * arm for arm, so a case that seeds one path shape at both sites and compares the answers is what
 * keeps them from drifting apart.
 */
class ArgumentReferenceStepTargetTest {

    // ===== The chain =====

    /**
     * The two-element path every hop in it oriented independently, departing from the table the
     * field's named type is bound to. {@code film} declares neither key and {@code film_actor}
     * declares both, so the first element travels against its foreign key and the second along it.
     */
    @Test
    void aKeyChainWalksEachElementFromTheFieldsNamedTypeBinding() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedKeyPath(dsl, "Query", "films", "inActor",
                "film_actor_film_id_fkey", "film_actor_actor_id_fkey");

            var rows = chain(dsl, GRAPH);
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.POSITION)))
                .containsExactly(0, 1);
            assertThat(rows.map(ArgumentReferenceStepTargetTest::hop))
                .containsExactly("film->film_actor", "film_actor->actor");
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.FK_ON_FROM)))
                .as("film declares neither key; film_actor declares both")
                .containsExactly(false, true);
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.VIA)))
                .containsExactly("KEY", "KEY");
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.CANDIDATES)))
                .containsExactly(1, 1);
        });
    }

    /**
     * The exit condition stated as one case: the same path shape written at both sites resolves to
     * the same hops, in the same orientations, by the same arms, with the same arities. The two
     * views are separate relations because their coordinates are different lengths, and this is
     * what says the split cost nothing but the coordinate.
     */
    @Test
    void thePathShapeResolvesTheSameAtBothSites() {
        withCatalog(dsl -> {
            // Film's own field-site path, departing from Film's binding.
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedFieldKeyPath(dsl, "Film", "actors",
                "film_actor_film_id_fkey", "film_actor_actor_id_fkey");
            // The same two elements on an argument of a field returning Film, which departs from
            // Film's binding too, by the other rule.
            seedQueryField(dsl, "films", "Film");
            seedKeyPath(dsl, "Query", "films", "inActor",
                "film_actor_film_id_fkey", "film_actor_actor_id_fkey");

            derive(dsl);
            assertThat(argumentSiteShape(dsl))
                .as("the argument-site chain, arm by arm")
                .isEqualTo(fieldSiteShape(dsl))
                .isNotEmpty();
        });
    }

    /**
     * A root field's parent type is bound to nothing, and its argument's path departs anyway. This
     * is the rule the field-site view does not have: it would look for a binding on {@code Query}
     * and find none, where what an argument filters is the field's result.
     */
    @Test
    void aRootFieldsArgumentDepartsWhereTheParentBindsNothing() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedKeyPath(dsl, "Query", "films", "inActor", "film_actor_film_id_fkey");

            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(hop(rows.getFirst())).isEqualTo("film->film_actor");
            // The same elements written on Query's own field reach nothing, Query binding no table.
            seedFieldKeyPath(dsl, "Query", "films", "film_actor_film_id_fkey");
            derive(dsl);
            assertThat(dsl.fetchCount(INTENT_FIELD_REFERENCE_STEP_TARGET,
                INTENT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(GRAPH)))
                .as("the enclosing type's binding is what the field-site rule needs")
                .isZero();
        });
    }

    /**
     * A connection-returning field departs its element type's table and not the wrapper's. The
     * named type is read the way every other view navigating to one reads it, off the authored type
     * expression the macro rewrote, so the wrapper type never has to be bound to anything.
     */
    @Test
    void aConnectionFieldDepartsItsElementTypesTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "FilmConnection");
            seedFieldSynthesis(dsl, GRAPH, "Query", "films", "CONNECTION", "[Film!]!");
            seedKeyPath(dsl, "Query", "films", "inActor", "film_actor_film_id_fkey");

            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(hop(rows.getFirst())).isEqualTo("film->film_actor");
        });
    }

    /**
     * The chain stops where an element does not resolve, so a path whose second element is fine
     * contributes nothing when its first names an unknown key. Absence means "not reached" here as
     * it does at the field site.
     */
    @Test
    void anUnresolvableFirstElementEndsTheChain() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedKeyPath(dsl, "Query", "films", "inActor",
                "no_such_fkey", "film_actor_actor_id_fkey");

            assertThat(dsl.fetchCount(GRAPHITRON_ARGUMENT_REFERENCE_STEP,
                GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME.eq(GRAPH)))
                .as("both elements were authored; only their resolution declines")
                .isEqualTo(2);
            assertThat(chain(dsl, GRAPH)).isEmpty();
        });
    }

    /** A field whose named type binds nothing gives its arguments' paths nowhere to start. */
    @Test
    void aPathOnAFieldReturningAnUnboundTypeStartsNowhere() {
        withCatalog(dsl -> {
            seedQueryField(dsl, "films", "Film");
            seedKeyPath(dsl, "Query", "films", "inActor", "film_actor_film_id_fkey");
            assertThat(chain(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * The two arities apart, on the case that separates them: {@code film} declares two foreign keys
     * to {@code language}, so one destination is reached by two routes.
     */
    @Test
    void twoForeignKeysToOneTableAreOneDestinationByTwoRoutes() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedTablePath(dsl, "Query", "films", "inLanguage", "language");

            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(2);
            assertThat(rows.map(ArgumentReferenceStepTargetTest::hop)).containsOnly("film->language");
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.TARGETS)))
                .containsExactly(1, 1);
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.CANDIDATES)))
                .containsExactly(2, 2);
        });
    }

    // ===== The condition arm =====

    /**
     * An element carrying a condition and naming neither a key nor a table is a hop, routed by the
     * method's own signature: parameter 0 the departure and parameter 1 the arrival, read through
     * {@code intent_condition_method_route}. The element joins on an authored predicate rather than
     * on a foreign key, so it names no constraint and carries no direction.
     */
    @Test
    void aBareConditionElementIsAHopThroughTheMethodsParameters() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedConditionMethod(dsl, JAR, CONDITIONS, "filmToActor",
                tableClass("film"), tableClass("actor"));
            seedConditionPath(dsl, "Query", "films", "inActor", "filmToActor");

            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(hop(rows.getFirst())).isEqualTo("film->actor");
            assertThat(rows.getFirst().get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.VIA))
                .isEqualTo("CONDITION");
            assertThat(rows.getFirst().get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.CONSTRAINT_NAME))
                .as("an authored predicate is not a foreign key")
                .isNull();
            assertThat(rows.getFirst().get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.FK_ON_FROM))
                .isNull();
            assertThat(rows.getFirst().get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.KEY_MATCHED_BY))
                .isNull();
        });
    }

    /**
     * The composition the recursion already had, with a condition hop as its second element: an FK
     * hop to the junction, then the condition's own route out of it. Nothing in the walk changed to
     * admit this; the arm supplies a hop row at position 1 whose departure the previous arrival
     * matches, which is all the recursive term ever asked of an arm.
     */
    @Test
    void anFkHopThenAConditionHopComposes() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedConditionMethod(dsl, JAR, CONDITIONS, "junctionToActor",
                tableClass("film_actor"), tableClass("actor"));
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "films", "inActor", 0);
            seedArgumentReferenceStep(dsl, GRAPH, "Query", "films", "inActor", 0, 0,
                null, "film_actor_film_id_fkey");
            seedArgumentReferenceCall(dsl, GRAPH, "Query", "films", "inActor", 0, 1,
                CONDITIONS, "junctionToActor");

            var rows = chain(dsl, GRAPH);
            assertThat(rows.map(ArgumentReferenceStepTargetTest::hop))
                .containsExactly("film->film_actor", "film_actor->actor");
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.VIA)))
                .containsExactly("KEY", "CONDITION");
        });
    }

    /**
     * A condition written beside a key is the KEY arm's row and not the condition arm's: the
     * condition there is that hop's filter and the key is its route, so the signature is never
     * consulted and the hop keeps its constraint and its direction.
     */
    @Test
    void aConditionBesideAKeyStaysTheKeyArmsRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedConditionMethod(dsl, JAR, CONDITIONS, "filmToActor",
                tableClass("film"), tableClass("actor"));
            seedArgument(dsl, GRAPH, "Query", "films", "inActor", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "films", "inActor", 0);
            seedArgumentReferenceElement(dsl, GRAPH, "Query", "films", "inActor", 0, 0,
                null, "film_actor_film_id_fkey", CONDITIONS, "filmToActor");

            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(hop(rows.getFirst()))
                .as("the key routes it; the condition method's own target is not read")
                .isEqualTo("film->film_actor");
            assertThat(rows.getFirst().get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.VIA))
                .isEqualTo("KEY");
        });
    }

    /**
     * A wildcard target parameter resolves no route, so the element is no hop and the chain does not
     * reach it. This is the silence that would otherwise be indistinguishable from "not reached",
     * and the defect relation beside the route is what names it instead.
     */
    @Test
    void aWildcardTargetParameterResolvesNoHop() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedConditionMethod(dsl, JAR, CONDITIONS, "filmToAnything",
                tableClass("film"), "org.jooq.Table");
            seedConditionPath(dsl, "Query", "films", "inActor", "filmToAnything");

            assertThat(chain(dsl, GRAPH)).isEmpty();
            assertThat(dsl.fetchCount(INTENT_CONDITION_METHOD_ROUTE_DEFECT,
                INTENT_CONDITION_METHOD_ROUTE_DEFECT.GRAPH_NAME.eq(GRAPH)
                    .and(INTENT_CONDITION_METHOD_ROUTE_DEFECT.VERDICT
                        .eq("WILDCARD_TARGET_PARAMETER"))))
                .as("the silence is named next door rather than left to a reader")
                .isEqualTo(1);
        });
    }

    /**
     * Overload multiplicity lands in the arities where it lands at all, and the departure slot is
     * where that is. Two overloads of one name arriving at two different tables agree on nothing the
     * route needs, so the pair routes nothing and the chain does not reach the element: the build
     * admits the set and then has one joined table to emit and no consumer call site to defer the
     * choice to, so it rejects, and the census reads the disagreement the same way. The silence is
     * named next door as {@code TARGET_DISAGREEMENT_ACROSS_OVERLOADS}.
     */
    @Test
    void twoOverloadsArrivingAtTwoTablesResolveNoHop() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("film"), tableClass("actor"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("film"), tableClass("language"));
            seedConditionPath(dsl, "Query", "films", "inActor", "bridge");

            assertThat(chain(dsl, GRAPH)).isEmpty();
            assertThat(dsl.fetchCount(INTENT_CONDITION_METHOD_ROUTE_DEFECT,
                INTENT_CONDITION_METHOD_ROUTE_DEFECT.GRAPH_NAME.eq(GRAPH)
                    .and(INTENT_CONDITION_METHOD_ROUTE_DEFECT.VERDICT
                        .eq("TARGET_DISAGREEMENT_ACROSS_OVERLOADS"))))
                .as("the silence is named next door rather than left to a reader")
                .isEqualTo(1);
        });
    }

    /**
     * Where the overloads agree on the arrival they route, and the departure slot is where their
     * multiplicity shows: two declarations arriving at {@code actor} from two tables are two route
     * rows, of which the chain reaches the one departing the element's own standing table, so the
     * element has one certain landing.
     */
    @Test
    void twoOverloadsAgreeingOnTheArrivalRouteFromTheirOwnDepartures() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("film"), tableClass("actor"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("language"), tableClass("actor"));
            seedConditionPath(dsl, "Query", "films", "inActor", "bridge");

            var rows = chain(dsl, GRAPH);
            assertThat(rows.map(ArgumentReferenceStepTargetTest::hop))
                .containsExactly("film->actor");
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.TARGETS)))
                .containsExactly(1);
        });
    }

    /**
     * Two arguments of one field are two chains, which is what the coordinate column is for: the
     * same field's second argument resolves on its own and neither row is keyed by the other's
     * spelling.
     */
    @Test
    void twoArgumentsOfOneFieldAreTwoChains() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedKeyPath(dsl, "Query", "films", "inActor", "film_actor_film_id_fkey");
            seedTablePath(dsl, "Query", "films", "titled", "film_translation");

            var rows = chain(dsl, GRAPH);
            assertThat(rows.map(r -> r.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.ARGUMENT_NAME)))
                .containsExactly("inActor", "titled");
            assertThat(rows.map(ArgumentReferenceStepTargetTest::hop))
                .containsExactly("film->film_actor", "film->film_translation");
        });
    }

    /** The graph partition, on a relation whose catalog side is scoped through membership. */
    @Test
    void aSiblingGraphReadsNoneOfTheChain() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedQueryField(dsl, "films", "Film");
            seedKeyPath(dsl, "Query", "films", "inActor", "film_actor_film_id_fkey");
            assertThat(chain(dsl, GRAPH)).hasSize(1);
            assertThat(chain(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String PUBLIC = "public";
    private static final String CONDITIONS = "com.example.Conditions";

    /**
     * The sibling test's catalog, which is the point: the agreement case needs both sites resolved
     * against one set of catalog rows, and every other case here turns on a shape that catalog
     * already holds.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, JAR);
            for (String table : List.of("film", "actor", "language", "film_actor",
                    "film_translation")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            foreignKey(dsl, "film", "film_language_id_fkey", "language");
            foreignKey(dsl, "film", "film_original_language_id_fkey", "language");
            foreignKey(dsl, "film_actor", "film_actor_film_id_fkey", "film");
            foreignKey(dsl, "film_actor", "film_actor_actor_id_fkey", "actor");
            foreignKey(dsl, "film_translation", "film_translation_film_id_fkey", "film");
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

    /** {@code Query.<fieldName>: <namedType>}, the field an argument-site path hangs off. */
    private static void seedQueryField(DSLContext dsl, String fieldName, String namedType) {
        seedField(dsl, GRAPH, "Query", fieldName, namedType, false);
    }

    /** An argument carrying one {@code @reference} whose elements each spell a key. */
    private static void seedKeyPath(DSLContext dsl, String typeName, String fieldName,
                                    String argumentName, String... keyRefs) {
        seedPath(dsl, typeName, fieldName, argumentName, null, keyRefs);
    }

    /** The same, with each element spelling a table instead. */
    private static void seedTablePath(DSLContext dsl, String typeName, String fieldName,
                                      String argumentName, String... tableRefs) {
        seedPath(dsl, typeName, fieldName, argumentName, tableRefs, null);
    }

    private static void seedPath(DSLContext dsl, String typeName, String fieldName,
                                 String argumentName, String[] tableRefs, String[] keyRefs) {
        seedArgument(dsl, GRAPH, typeName, fieldName, argumentName, "String");
        seedArgumentReference(dsl, GRAPH, typeName, fieldName, argumentName, 0);
        int elements = tableRefs != null ? tableRefs.length : keyRefs.length;
        for (int position = 0; position < elements; position++) {
            seedArgumentReferenceStep(dsl, GRAPH, typeName, fieldName, argumentName, 0, position,
                tableRefs == null ? null : tableRefs[position],
                keyRefs == null ? null : keyRefs[position]);
        }
    }

    /** The generated table class the seeded catalog names for a table, its own join key. */
    private static String tableClass(String table) {
        return PKG + ".tables." + table;
    }

    /** An argument carrying one {@code @reference} whose single element is a bare condition. */
    private static void seedConditionPath(DSLContext dsl, String typeName, String fieldName,
                                          String argumentName, String method) {
        seedArgument(dsl, GRAPH, typeName, fieldName, argumentName, "String");
        seedArgumentReference(dsl, GRAPH, typeName, fieldName, argumentName, 0);
        seedArgumentReferenceCall(dsl, GRAPH, typeName, fieldName, argumentName, 0, 0,
            CONDITIONS, method);
    }

    /** The field-site path the agreement case compares against. */
    private static void seedFieldKeyPath(DSLContext dsl, String typeName, String fieldName,
                                         String... keyRefs) {
        if (!dsl.fetchExists(GRAPHQL_FIELD_ELEMENT,
                GRAPHQL_FIELD_ELEMENT.GRAPH_NAME.eq(GRAPH)
                    .and(GRAPHQL_FIELD_ELEMENT.TYPE_NAME.eq(typeName))
                    .and(GRAPHQL_FIELD_ELEMENT.FIELD_NAME.eq(fieldName)))) {
            seedField(dsl, GRAPH, typeName, fieldName);
        }
        seedFieldReference(dsl, GRAPH, typeName, fieldName, 0);
        for (int position = 0; position < keyRefs.length; position++) {
            seedFieldReferenceStep(dsl, GRAPH, typeName, fieldName, 0, position,
                null, keyRefs[position]);
        }
    }

    private static Result<Record> chain(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.fields())
            .from(INTENT_ARGUMENT_REFERENCE_STEP_TARGET)
            .where(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.ARGUMENT_NAME,
                INTENT_ARGUMENT_REFERENCE_STEP_TARGET.POSITION,
                INTENT_ARGUMENT_REFERENCE_STEP_TARGET.TO_SCHEMA,
                INTENT_ARGUMENT_REFERENCE_STEP_TARGET.CONSTRAINT_NAME)
            .fetch();
    }

    /**
     * One chain rendered as the columns the two views share, so an argument-site answer and a
     * field-site answer over the same path shape compare as values. The coordinate columns are
     * deliberately not in it: they are what differs by construction.
     */
    private static List<String> argumentSiteShape(DSLContext dsl) {
        var t = INTENT_ARGUMENT_REFERENCE_STEP_TARGET;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.POSITION, t.TO_SCHEMA, t.CONSTRAINT_NAME)
            .fetch(r -> shape(r.get(t.POSITION), r.get(t.VIA), r.get(t.KEY_MATCHED_BY),
                r.get(t.FROM_TABLE), r.get(t.TO_TABLE), r.get(t.CONSTRAINT_NAME),
                r.get(t.FK_ON_FROM), r.get(t.TARGETS), r.get(t.CANDIDATES)));
    }

    private static List<String> fieldSiteShape(DSLContext dsl) {
        var t = INTENT_FIELD_REFERENCE_STEP_TARGET;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.POSITION, t.TO_SCHEMA, t.CONSTRAINT_NAME)
            .fetch(r -> shape(r.get(t.POSITION), r.get(t.VIA), r.get(t.KEY_MATCHED_BY),
                r.get(t.FROM_TABLE), r.get(t.TO_TABLE), r.get(t.CONSTRAINT_NAME),
                r.get(t.FK_ON_FROM), r.get(t.TARGETS), r.get(t.CANDIDATES)));
    }

    private static String shape(Integer position, String via, String keyMatchedBy, String fromTable,
                                String toTable, String constraintName, Boolean fkOnFrom,
                                Integer targets, Integer candidates) {
        return position + " " + via + " " + keyMatchedBy + " " + fromTable + "->" + toTable
            + " on " + constraintName + " fkOnFrom=" + fkOnFrom
            + " targets=" + targets + " candidates=" + candidates;
    }

    private static String hop(Record row) {
        return row.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.FROM_TABLE) + "->"
            + row.get(INTENT_ARGUMENT_REFERENCE_STEP_TARGET.TO_TABLE);
    }
}
