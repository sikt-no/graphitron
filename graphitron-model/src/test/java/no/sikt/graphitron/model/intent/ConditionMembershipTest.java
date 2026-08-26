package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_MEMBERSHIP;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentLookupKey;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedImplements;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedInputFieldLookupKey;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedRootOperation;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the condition membership fold states: which tables a coordinate contributes a WHERE clause
 * against. Membership is presence and nothing else, so every case here asks one question, whether a
 * row exists, and the interesting content is entirely in what makes one exist and what does not.
 *
 * <p>Five sources contribute and the cases state them one at a time, because they are five
 * different rules and a fixture carrying two of them at once would pass with either working. Three
 * are authored, at the field, at an argument and at an input field, and cannot be suppressed. Two
 * are generated, at an argument and at an input field, and can be.
 *
 * <p>Suppression is the part of this relation most likely to be misread, so one case states it
 * outright. An override that suppresses a generated predicate is itself an authored
 * {@code @condition}, so it contributes under one of the three authored sources and the coordinate
 * stays a member. Suppression moves what the glue contains and never whether it exists; a reader
 * expecting {@code override: true} to remove a row is expecting the wrong grain.
 *
 * <p>Four kinds of coordinate are excluded outright, and each gets its own case because they are
 * four different reasons: a mutation, whose predicates come from the write partition; a
 * {@code @service} field, which generates no SQL; the relay node field, which resolves a node; and
 * anything under a {@code @lookupKey} argument, which is the VALUES-and-join path. The last is the
 * one that propagates, so its case puts the marker on the argument and the contribution two steps
 * below it.
 *
 * <p>What this relation cannot see is not testable here and is stated in its own comment: a
 * coordinate whose classification is refused has a row here and no glue, the store carrying no
 * read-side refusal. On a schema that builds, that population is empty.
 */
class ConditionMembershipTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== The three authored sources =====

    /**
     * A {@code @condition} on the field itself, at a coordinate carrying no arguments at all. The
     * ordinary static filter, and the case that fixes the grain: there is no argument to hang this
     * contribution on, which is why membership is stated at the field and not at an argument.
     */
    @Test
    void aFieldsOwnConditionMakesItAMemberWithNoArgumentInvolved() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedFieldCondition(dsl, GRAPH, "Query", "films", false);

            assertThat(members(dsl)).containsExactly("Query.films@film");
        });
    }

    /**
     * A {@code @condition} on an argument, whatever that argument's own role resolves. The
     * argument here binds no column and names no rule of its own, so the authored method is the
     * whole contribution and the generated sources have nothing to add.
     */
    @Test
    void anArgumentsConditionMakesItAMember() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "by", "String");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "by", false);

            assertThat(members(dsl)).containsExactly("Query.films@film");
        });
    }

    /**
     * A {@code @condition} on an input field an argument reaches. The condition sits two steps down
     * from the use site, on a nested input object rather than on a leaf, which is the shape whose
     * contribution the fold reads off the capture directly rather than off the role relation's
     * modifier.
     */
    @Test
    void anInputFieldsConditionMakesTheUseSiteAMember() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedDeclaredType(dsl, GRAPH, "Filter", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "Range", "INPUT_OBJECT");
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "Filter");
            seedInputField(dsl, GRAPH, "Filter", "range", "Range", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Range", "from", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "Filter",
                new OccurrenceStep("Filter", "range", "Range"),
                new OccurrenceStep("Range", "from", "String"));
            seedFieldCondition(dsl, GRAPH, "Filter", "range", false);

            assertThat(members(dsl)).containsExactly("Query.films@film");
        });
    }

    // ===== The two generated sources =====

    /** An argument whose written name reaches a column: the generated predicate, at the use site. */
    @Test
    void aNameMatchedArgumentMakesItAMember() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentBinding(dsl, GRAPH, "Query", "films", "title", "title");

            assertThat(members(dsl)).containsExactly("Query.films@film");
        });
    }

    /** An input field whose written name reaches a column on the table its argument handed down. */
    @Test
    void aNameMatchedInputFieldMakesTheUseSiteAMember() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedDeclaredType(dsl, GRAPH, "Filter", "INPUT_OBJECT");
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "Filter");
            seedInputField(dsl, GRAPH, "Filter", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "Filter",
                new OccurrenceStep("Filter", "title", "String"));

            assertThat(members(dsl)).containsExactly("Query.films@film");
        });
    }

    /**
     * A field whose arguments resolve nothing has no row. Absence is the ordinary case and the one
     * every other case here is measured against, so it is stated rather than assumed: the argument
     * is named for no column of the table and carries no condition.
     */
    @Test
    void anArgumentThatResolvesNothingLeavesTheFieldOutright() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "unresolvable", "String");

            assertThat(members(dsl)).isEmpty();
        });
    }

    // ===== Suppression, which does not remove a row =====

    /**
     * An override suppresses the generated predicate and the coordinate stays a member, because
     * whatever set the override is itself an authored {@code @condition} and contributes under its
     * own source. The two facts are asserted together, the role relation saying the argument is
     * suppressed and this relation still admitting the coordinate, so the case cannot pass by the
     * suppression quietly not having happened.
     */
    @Test
    void anOverrideSuppressesThePredicateAndLeavesTheCoordinateAMember() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentBinding(dsl, GRAPH, "Query", "films", "title", "title");
            seedFieldCondition(dsl, GRAPH, "Query", "films", true);

            assertThat(suppressedArguments(dsl))
                .as("the generated predicate really is suppressed")
                .containsExactly("title");
            assertThat(members(dsl))
                .as("and the authored condition that suppressed it is itself a contribution")
                .containsExactly("Query.films@film");
        });
    }

    // ===== The four exclusions =====

    /** A mutation's predicates come from the write partition, so it is never a member here. */
    @Test
    void aMutationContributesNothing() {
        withCatalog(dsl -> {
            seedRootOperation(dsl, GRAPH, "MUTATION", "Mutation");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "deleteFilms", "Film", true);
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilms", "DELETE", "film");
            seedArgument(dsl, GRAPH, "Mutation", "deleteFilms", "title", "String");
            seedArgumentBinding(dsl, GRAPH, "Mutation", "deleteFilms", "title", "title");

            assertThat(members(dsl)).isEmpty();
        });
    }

    /** A {@code @service} field generates no SQL, so its arguments filter nothing. */
    @Test
    void aServiceBackedFieldContributesNothing() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedArgumentBinding(dsl, GRAPH, "Query", "films", "title", "title");
            seedService(dsl, GRAPH, "Query", "films", "com.example.Svc", "films");

            assertThat(members(dsl)).isEmpty();
        });
    }

    /**
     * The relay node field resolves a node and never filters, whatever its {@code id} argument
     * classifies as. Its shape carries the node-resolve member and no condition member, so the
     * exclusion is on the coordinate rather than on the argument.
     */
    @Test
    void theRelayNodeFieldContributesNothing() {
        withCatalog(dsl -> {
            seedDeclaredType(dsl, GRAPH, "Node", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "node", "Node", false);
            seedImplements(dsl, GRAPH, "Film", "Node");
            seedArgument(dsl, GRAPH, "Query", "node", "title", "String");
            seedArgumentBinding(dsl, GRAPH, "Query", "node", "title", "title");

            assertThat(members(dsl)).isEmpty();
        });
    }

    /**
     * The node exclusion is about the root and not about the return type. The same interface
     * returned by a child field is an ordinary polymorphic coordinate, so it keeps its membership;
     * without this case the exclusion would pass just as well if it had been written to fire
     * wherever {@code Node} is returned.
     */
    @Test
    void anInterfaceReturnedBelowTheRootIsNotTheNodeField() {
        withCatalog(dsl -> {
            seedDeclaredType(dsl, GRAPH, "Node", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Customer", "customer");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedField(dsl, GRAPH, "Film", "related", "Node", false);
            seedImplements(dsl, GRAPH, "Customer", "Node");
            seedFieldCondition(dsl, GRAPH, "Film", "related", false);

            assertThat(members(dsl)).containsExactly("Film.related@customer");
        });
    }

    /**
     * A {@code @lookupKey} on the argument consumes the whole expansion beneath it, at any depth.
     * The contribution is placed two steps down so the case states the propagation rather than the
     * argument's own exclusion, which the role relation already carries as a modifier.
     */
    @Test
    void aLookupKeyArgumentConsumesItsWholeExpansion() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedDeclaredType(dsl, GRAPH, "Key", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "Inner", "INPUT_OBJECT");
            seedArgument(dsl, GRAPH, "Query", "films", "key", "Key");
            seedArgumentLookupKey(dsl, GRAPH, "Query", "films", "key");
            seedInputField(dsl, GRAPH, "Key", "inner", "Inner", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Inner", "title", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "key", "Key",
                new OccurrenceStep("Key", "inner", "Inner"),
                new OccurrenceStep("Inner", "title", "String"));

            assertThat(members(dsl)).isEmpty();
        });
    }

    /** A {@code @lookupKey} on the input field itself, which is the same rule one rung down. */
    @Test
    void aLookupKeyInputFieldContributesNothing() {
        withCatalog(dsl -> {
            filmsField(dsl);
            seedDeclaredType(dsl, GRAPH, "Filter", "INPUT_OBJECT");
            seedArgument(dsl, GRAPH, "Query", "films", "filter", "Filter");
            seedInputField(dsl, GRAPH, "Filter", "title", "String", 0, false, false, null);
            seedInputFieldLookupKey(dsl, GRAPH, "Filter", "title");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "filter", "Filter",
                new OccurrenceStep("Filter", "title", "String"));

            assertThat(members(dsl)).isEmpty();
        });
    }

    // ===== The branch fan-out =====

    /**
     * A multi-table polymorphic root is one row per participant table, and the fan-out is the scope
     * table's rather than this relation's: membership is decided once at the coordinate and the
     * table side multiplies it. The two participants bind different tables, so a fold that decided
     * membership per branch and a fold that decided it once would still agree on the count and
     * disagree on nothing this fixture can show; what it does show is that both branches arrive,
     * which a coordinate-grained fold joined to a coordinate-grained table side would not give.
     */
    @Test
    void aPolymorphicRootIsOneRowPerParticipantTable() {
        withCatalog(dsl -> {
            seedDeclaredType(dsl, GRAPH, "Occupant", "UNION");
            seedTableBinding(dsl, GRAPH, "Customer", "customer");
            seedTableBinding(dsl, GRAPH, "Staff", "staff");
            seedUnionMember(dsl, GRAPH, "Occupant", "Customer", 0);
            seedUnionMember(dsl, GRAPH, "Occupant", "Staff", 1);
            seedField(dsl, GRAPH, "Query", "occupants", "Occupant", true);
            seedFieldCondition(dsl, GRAPH, "Query", "occupants", false);

            assertThat(members(dsl)).containsExactlyInAnyOrder(
                "Query.occupants@customer",
                "Query.occupants@staff");
        });
    }

    // ===== The partition =====

    /**
     * A graph admits nothing on its sibling's behalf. The two graphs carry the same coordinate and
     * only one of them carries the condition, so a partition that lost a graph would have to change
     * an answer rather than merely produce extra rows.
     */
    @Test
    void aGraphAdmitsNothingOnItsSiblingsBehalf() {
        withCatalog(dsl -> {
            seedGraph(dsl, OTHER_GRAPH);
            seedGraphSource(dsl, OTHER_GRAPH, PKG);
            seedType(dsl, OTHER_GRAPH, "String", "SCALAR");
            for (String graph : List.of(GRAPH, OTHER_GRAPH)) {
                seedTableBinding(dsl, graph, "Film", "film");
                seedField(dsl, graph, "Query", "films", "Film", true);
            }
            seedFieldCondition(dsl, GRAPH, "Query", "films", false);

            assertThat(allMembers(dsl)).containsExactly(GRAPH + " Query.films@film");
        });
    }

    // ===== Fixtures =====

    /** The catalog every case resolves against: {@code film}, plus two tables for the union case. */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "film");
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 0, "TITLE");
            seedTable(dsl, PKG, PUBLIC, "customer");
            seedConstraint(dsl, PKG, PUBLIC, "customer", "customer_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "customer", "name", 0, "NAME");
            seedTable(dsl, PKG, PUBLIC, "staff");
            seedConstraint(dsl, PKG, PUBLIC, "staff", "staff_pkey", "PRIMARY KEY", null);
            seedColumn(dsl, PKG, PUBLIC, "staff", "name", 0, "NAME");
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            body.accept(dsl);
        });
    }

    /** {@code Query.films: [Film]} with {@code Film} bound to {@code film}. */
    private static void filmsField(DSLContext dsl) {
        seedTableBinding(dsl, GRAPH, "Film", "film");
        seedField(dsl, GRAPH, "Query", "films", "Film", true);
    }

    // ===== Readings =====

    /** The primary graph's members as {@code <type>.<field>@<table>}. */
    private static List<String> members(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_CONDITION_MEMBERSHIP)
            .where(INTENT_CONDITION_MEMBERSHIP.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_CONDITION_MEMBERSHIP.TYPE_NAME,
                INTENT_CONDITION_MEMBERSHIP.FIELD_NAME,
                INTENT_CONDITION_MEMBERSHIP.TABLE_NAME)
            .fetch(r -> r.getTypeName() + "." + r.getFieldName() + "@" + r.getTableName());
    }

    /** The same, graph first, so the partition is read as a value rather than filtered away. */
    private static List<String> allMembers(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_CONDITION_MEMBERSHIP)
            .orderBy(INTENT_CONDITION_MEMBERSHIP.GRAPH_NAME,
                INTENT_CONDITION_MEMBERSHIP.TYPE_NAME,
                INTENT_CONDITION_MEMBERSHIP.FIELD_NAME)
            .fetch(r -> r.getGraphName() + " " + r.getTypeName() + "." + r.getFieldName()
                + "@" + r.getTableName());
    }

    /** The arguments the role relation reports a suppressed generated predicate for. */
    private static List<String> suppressedArguments(DSLContext dsl) {
        derive(dsl);
        return dsl.select(no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_FILTER_ROLE.ARGUMENT_NAME)
            .from(no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_FILTER_ROLE)
            .where(no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_FILTER_ROLE.GRAPH_NAME.eq(GRAPH))
            .and(no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_FILTER_ROLE.SUPPRESSED.isTrue())
            .fetch(org.jooq.Record1::value1);
    }
}
