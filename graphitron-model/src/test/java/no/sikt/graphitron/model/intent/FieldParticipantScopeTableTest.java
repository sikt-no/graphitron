package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PARTICIPANT_SCOPE_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_SCOPE_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldSynthesis;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedImplements;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_field_participant_scope_table} resolves: the table each branch of a field
 * returning a multi-table polymorphic container is rooted in. The classifier lowers such a field
 * once per table-bound participant, each against that participant's own table, so a coordinate that
 * looks like one statement is several and each one needs a root.
 *
 * <p>Every case asserts the participant beside the table, because the participant is the grain here
 * and not provenance: a consumer minting the per-participant condition method is naming the
 * participant, so a case asserting the tables alone would pass with the participants transposed.
 *
 * <p>Two sections carry what makes this an arm rather than a rung. One is the pair of preconditions
 * that keep it disjoint from both of {@code intent_field_scope_table}'s ranked rungs, and the cases
 * there are declines: the container that binds its own table is the single-table discriminated
 * interface, whose participants share one filter surface, and the field naming a resolving
 * {@code @mutation(table:)} has an author's answer already. The other is the projection into that
 * relation, which is distinct on the table because its grain is the table where this one's is the
 * participant.
 */
class FieldParticipantScopeTableTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== The population =====

    /**
     * The ordinary case: a field returns a union whose members each bind a table, and each member is
     * a branch rooted in its own.
     */
    @Test
    void aUnionsMembersAreEachABranchRootedInItsOwnTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(rows(dsl).map(FieldParticipantScopeTableTest::render))
                .containsExactly("Query.documents Actor actor", "Query.documents Film film");
        });
    }

    /**
     * The interface arm answers the same way, the container axis being what makes membership one
     * relation: whether the SDL spelled the membership on the container or on the member is
     * {@code intent_poly_member}'s business and not this relation's.
     */
    @Test
    void anUnboundInterfacesImplementorsAreEachABranch() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Searchable", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedImplements(dsl, GRAPH, "Film", "Searchable");
            seedImplements(dsl, GRAPH, "Actor", "Searchable");
            seedField(dsl, GRAPH, "Query", "search", "Searchable", true);

            assertThat(rows(dsl).map(FieldParticipantScopeTableTest::render))
                .containsExactly("Query.search Actor actor", "Query.search Film film");
        });
    }

    /**
     * A connection field over a container navigates as its element type rather than as its edge
     * wrapper, which is the reading the authored type expression carries and the same one the named
     * type rung takes.
     */
    @Test
    void aConnectionFieldOverAContainerReadsItsElementType() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedType(dsl, GRAPH, "DocumentConnection", "OBJECT");
            seedField(dsl, GRAPH, "Query", "documents", "DocumentConnection", false);
            seedFieldSynthesis(dsl, GRAPH, "Query", "documents", "CONNECTION", "[Document!]!");

            assertThat(rows(dsl).map(FieldParticipantScopeTableTest::render))
                .containsExactly("Query.documents Film film");
        });
    }

    /**
     * The population is the schema's shape and not the emitter's reach: a child field returning a
     * multi-table container has the same branches a root field with that return type has. Narrowing
     * the rows to the coordinates a per-participant filter surface exists for today would make this
     * relation track emitter maturity, so a shape acquiring an emitter would change rows in a graph
     * nobody edited.
     */
    @Test
    void aChildFieldReturningAContainerCarriesItsBranchesToo() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedTableBinding(dsl, GRAPH, "Language", "language");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
            seedField(dsl, GRAPH, "Language", "documents", "Document", true);

            assertThat(rows(dsl).map(FieldParticipantScopeTableTest::render))
                .containsExactly("Language.documents Actor actor", "Language.documents Film film");
        });
    }

    /** The graph partition holds: a sibling graph's coordinates read none of these branches. */
    @Test
    void aSiblingGraphReadsNoneOfTheBranches() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Where the arm declines =====

    /**
     * A container carrying its own {@code @table} is the single-table discriminated interface: its
     * participants share one table, so there is one statement and one filter surface, and the named
     * type rung answers it. This decline is what keeps the arm disjoint from that rung rather than
     * ranked below it.
     */
    @Test
    void aContainerThatBindsItsOwnTableHasNoBranchesHere() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "MediaItem", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "MediaItem", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedImplements(dsl, GRAPH, "Actor", "MediaItem");
            seedField(dsl, GRAPH, "Query", "media", "MediaItem", true);

            assertThat(rows(dsl)).isEmpty();
            assertThat(scopeRows(dsl)).containsExactly("Query.media NAMED_TYPE_TABLE film");
        });
    }

    /**
     * A field naming a {@code @mutation(table:)} that resolves has the author's own answer for where
     * its statement is rooted, so the arm declines rather than contending with the rung that reads
     * it. The symmetric decline to the one above, and the second half of the disjointness.
     */
    @Test
    void aFieldNamingAMutationTableHasNoBranchesHere() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedField(dsl, GRAPH, "Mutation", "deleteDocument", "Document", false);
            seedMutation(dsl, GRAPH, "Mutation", "deleteDocument", "DELETE", "actor");

            assertThat(rows(dsl)).isEmpty();
            assertThat(scopeRows(dsl)).containsExactly("Mutation.deleteDocument MUTATION_TABLE actor");
        });
    }

    /**
     * A member that binds nothing contributes no row and no rejection: a container whose every
     * member must bind is the type classifier's invariant, so a partially bound container is
     * incomplete here and rejected there. Stated as a case because the alternative reading, that this
     * relation should answer for the container as a whole or not at all, would put a rejection's
     * verdict inside a resolution rule.
     */
    @Test
    void anUnboundMemberContributesNoBranchAndDoesNotSilenceTheOthers() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedType(dsl, GRAPH, "DatePeriod", "OBJECT");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "DatePeriod", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(rows(dsl).map(FieldParticipantScopeTableTest::render))
                .containsExactly("Query.documents Film film");
        });
    }

    /**
     * An ambiguously bound member is no branch rather than a picked one, for the reason both rungs
     * demand certainty: a branch is a statement, and two candidate tables are two different
     * statements the classifier never had in hand.
     */
    @Test
    void anAmbiguouslyBoundMemberIsNoBranch() {
        withCatalog(dsl -> {
            seedTwoSchemasNamed(dsl, "venue");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "venue");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(rows(dsl).map(FieldParticipantScopeTableTest::render))
                .containsExactly("Query.documents Film film");
        });
    }

    // ===== The projection into the scope relation =====

    /**
     * The scope relation carries the arm under its own basis, and the arm sits outside the window its
     * two rungs are ranked by, so a coordinate with branches has one row per branch rather than one
     * winner.
     */
    @Test
    void theScopeRelationCarriesEachBranchUnderTheParticipantBasis() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(scopeRows(dsl)).containsExactly(
                "Query.documents PARTICIPANT_TABLE actor",
                "Query.documents PARTICIPANT_TABLE film");
        });
    }

    /**
     * Two participants backed by one table are two rows here and one row there. The grains differ on
     * purpose: a statement's root is a table, so the scope relation is distinct on it, while the unit
     * a consumer mints is named after the participant, so this relation keys on that. A reader that
     * needs to know which participant reads this relation, and one that needs to know which tables to
     * root statements in reads the other.
     */
    @Test
    void twoParticipantsOnOneTableAreTwoBranchesAndOneScope() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Reel", "film");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Reel", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);

            assertThat(rows(dsl).map(FieldParticipantScopeTableTest::render))
                .containsExactly("Query.documents Film film", "Query.documents Reel film");
            assertThat(scopeRows(dsl))
                .containsExactly("Query.documents PARTICIPANT_TABLE film");
        });
    }

    /**
     * The argument-grain fan-out inherits the branches, which is what the argument-site column
     * resolution needs from it: one predicate per argument per branch, each compared against a column
     * of that branch's own table. Pinned here because the fan-out is the argument relation's whole
     * content and a drift between the two would be this relation's to answer for.
     */
    @Test
    void theArgumentGrainRelationFansEachBranchOverTheArguments() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedUnionMember(dsl, GRAPH, "Document", "Film", 1);
            seedUnionMember(dsl, GRAPH, "Document", "Actor", 2);
            seedField(dsl, GRAPH, "Query", "documents", "Document", true);
            seedArgument(dsl, GRAPH, "Query", "documents", "name", "String");

            assertThat(argumentRows(dsl)).containsExactly(
                "Query.documents(name) PARTICIPANT_TABLE actor",
                "Query.documents(name) PARTICIPANT_TABLE film");
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor", "language")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "Boolean", "SCALAR");
            body.accept(dsl);
        });
    }

    /**
     * One table name declared in two schemas, which is how a spelling resolves to two tables and a
     * binding over it becomes ambiguous.
     */
    private static void seedTwoSchemasNamed(DSLContext dsl, String tableName) {
        for (String schema : List.of(PUBLIC, "archive")) {
            seedTable(dsl, PKG, schema, tableName);
            seedConstraint(dsl, PKG, schema, tableName, tableName + "_pkey", "PRIMARY KEY", null);
        }
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.fields())
            .from(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE)
            .where(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.TYPE_NAME,
                INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.FIELD_NAME,
                INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.MEMBER_TYPE_NAME)
            .fetch();
    }

    /** The scope relation this arm is unioned into, rendered the way its own test renders it. */
    private static List<String> scopeRows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_FIELD_SCOPE_TABLE.fields())
            .from(INTENT_FIELD_SCOPE_TABLE)
            .where(INTENT_FIELD_SCOPE_TABLE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_FIELD_SCOPE_TABLE.TYPE_NAME,
                INTENT_FIELD_SCOPE_TABLE.FIELD_NAME,
                INTENT_FIELD_SCOPE_TABLE.TABLE_NAME)
            .fetch()
            .map(row -> row.get(INTENT_FIELD_SCOPE_TABLE.TYPE_NAME) + "."
                + row.get(INTENT_FIELD_SCOPE_TABLE.FIELD_NAME) + " "
                + row.get(INTENT_FIELD_SCOPE_TABLE.BASIS) + " "
                + row.get(INTENT_FIELD_SCOPE_TABLE.TABLE_NAME));
    }

    /** The argument-grain fan-out, rendered the way its own test renders it. */
    private static List<String> argumentRows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_ARGUMENT_SCOPE_TABLE.fields())
            .from(INTENT_ARGUMENT_SCOPE_TABLE)
            .where(INTENT_ARGUMENT_SCOPE_TABLE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_ARGUMENT_SCOPE_TABLE.TYPE_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.FIELD_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.ARGUMENT_NAME,
                INTENT_ARGUMENT_SCOPE_TABLE.TABLE_NAME)
            .fetch()
            .map(row -> row.get(INTENT_ARGUMENT_SCOPE_TABLE.TYPE_NAME) + "."
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.FIELD_NAME) + "("
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.ARGUMENT_NAME) + ") "
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.BASIS) + " "
                + row.get(INTENT_ARGUMENT_SCOPE_TABLE.TABLE_NAME));
    }

    /** The coordinate, the participant, and the table its branch is rooted in. */
    private static String render(Record row) {
        return row.get(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.TYPE_NAME) + "."
            + row.get(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.FIELD_NAME) + " "
            + row.get(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.MEMBER_TYPE_NAME) + " "
            + row.get(INTENT_FIELD_PARTICIPANT_SCOPE_TABLE.TABLE_NAME);
    }
}
