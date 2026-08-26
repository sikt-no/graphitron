package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_PAYLOAD_REFUSAL;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldDirective;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUniqueKey;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_mutation_payload_refusal} states: which occurrence inside a write payload the
 * build refuses, and under which of the two gates. The refusal half of
 * {@code intent_mutation_write_payload}, and the cases divide the way the relation's own vocabulary
 * does: one for the gate that runs before a walker exists, five for the walkers' own, and then the
 * three properties that are not a cause at all.
 *
 * <p>Every case that asserts a refusal asserts an admitted field beside it. An empty result is the
 * working write surface here, so a case reading {@code containsExactly} on one row is also a claim
 * that the other field produced none, which is what keeps a rule that refuses everything from
 * passing.
 *
 * <p>The ranking cases are the ones worth reading twice. A field can be two refusals at once, and
 * which one is reported is the walkers' own test order rather than anything this relation chose; a
 * union would surface both and hand a consumer a diagnostic the build never mints.
 */
class MutationPayloadRefusalTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * One write surface, {@code Mutation.updateFilm}, returning the {@code film}-bound {@code Film}
     * and taking its payload through {@code in: FilmUpdateInput}. The catalog carries a second table
     * and two foreign keys so the remote carrier has a shape to be remote through.
     */
    private static void withWriteSurface(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);

            seedTable(dsl, PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "rating", 2, "RATING");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_alt", 3, "PUB_ALT");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");

            seedTable(dsl, PKG, PUBLIC, "publisher");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_id", 0, "PUB_ID");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "alt_key", 1, "ALT_KEY");
            seedPrimaryKey(dsl, PKG, PUBLIC, "publisher", "publisher_pkey", "pub_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "publisher", "publisher_alt_uk", "alt_key");
            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_publisher_alt_fkey",
                "publisher", "publisher_alt_uk", "pub_alt");

            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedType(dsl, GRAPH, "FilmUpdateInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput");
            body.accept(dsl);
        });
    }

    /** One input field on the payload type, reached from the write surface's own argument. */
    private static void payloadField(DSLContext dsl, String fieldName, String namedType) {
        payloadField(dsl, fieldName, namedType, false);
    }

    /** The same, list-typed where the case says so; the ordinal is the field count so far. */
    private static void payloadField(DSLContext dsl, String fieldName, String namedType, boolean list) {
        seedInputField(dsl, GRAPH, "FilmUpdateInput", fieldName, namedType, 0, false, list, null);
        seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
            new OccurrenceStep("FilmUpdateInput", fieldName, namedType));
    }

    /** A nested grouping input under the payload, with one plain leaf inside it. */
    private static void nestedGrouping(DSLContext dsl, String fieldName, String nestedType,
                                       String leafName, boolean list) {
        seedType(dsl, GRAPH, nestedType, "INPUT_OBJECT");
        seedInputField(dsl, GRAPH, "FilmUpdateInput", fieldName, nestedType, 0, false, list, null);
        seedInputField(dsl, GRAPH, nestedType, leafName, "String", 0, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
            new OccurrenceStep("FilmUpdateInput", fieldName, nestedType),
            new OccurrenceStep(nestedType, leafName, "String"));
    }

    /** {@code Publisher} as a node type whose key the payload's table reaches only translated. */
    private static void publisherIsANode(DSLContext dsl) {
        seedType(dsl, GRAPH, "Publisher", "OBJECT");
        seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
        seedNode(dsl, GRAPH, "Publisher");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_id");
    }

    private static List<String> refusals(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_PAYLOAD_REFUSAL.fields())
            .from(INTENT_MUTATION_PAYLOAD_REFUSAL)
            .where(INTENT_MUTATION_PAYLOAD_REFUSAL.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_PAYLOAD_REFUSAL.PATH)
            .fetch()
            .map(MutationPayloadRefusalTest::render);
    }

    /**
     * The occurrence, the write target, the cause and the role: the located half and the classified
     * half of the row, which is the whole of what a consumer rendering a diagnostic needs.
     */
    private static String render(Record row) {
        return row.get(INTENT_MUTATION_PAYLOAD_REFUSAL.PATH) + " -> "
            + row.get(INTENT_MUTATION_PAYLOAD_REFUSAL.WRITE_TABLE) + " "
            + row.get(INTENT_MUTATION_PAYLOAD_REFUSAL.OPERATION) + " "
            + row.get(INTENT_MUTATION_PAYLOAD_REFUSAL.CAUSE) + " "
            + String.valueOf(row.get(INTENT_MUTATION_PAYLOAD_REFUSAL.ROLE));
    }

    // ===== The gate that runs before a walker exists =====

    /**
     * A field the classifier declined is refused before a walker sees it, and the cause says so
     * rather than naming a walker rule that never ran. A {@code @notGenerated} application is the
     * decline reached with the least machinery: retired at this site and rejected outright rather
     * than ignored, so the site has no role at all and the role column is null.
     */
    @Test
    void aFieldTheClassifierDeclinedIsRefusedBeforeTheWalker() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "rating", "String");
            seedFieldDirective(dsl, GRAPH, "FilmUpdateInput", "rating", "notGenerated");

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/rating -> film UPDATE UNCLASSIFIED null");
        });
    }

    // ===== The walkers' own five =====

    /**
     * A carrier whose decoded value reaches its row only through a join has nothing on the write
     * target to assign or to key on, so both walkers refuse it at the binding switch. The reference
     * here names a foreign key pointing at an alternate unique key rather than the node's own, which
     * is the translation that makes the binding remote.
     */
    @Test
    void aRemoteCarrierIsRefused() {
        withWriteSurface(dsl -> {
            publisherIsANode(dsl);
            payloadField(dsl, "title", "String");
            payloadField(dsl, "publisherId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "publisherId", "Publisher");

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/publisherId -> film UPDATE REMOTE_CARRIER NODE_ID");
        });
    }

    /**
     * A {@code @condition(override: true)} field is a carrier the read surface admits and a write
     * surface does not: the method owns the whole contribution and there is no value to write.
     */
    @Test
    void aConditionOwnedFieldIsRefused() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "rating", "String");
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "rating", true);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/rating -> film UPDATE CONDITION_OWNED CONDITION_OWNED");
        });
    }

    /**
     * A name reaching no column of the write target is a role rather than a silence upstream, and a
     * refusal here: a write surface has nowhere to put the value. The one cause whose upstream row
     * exists and reads as an ordinary classification.
     */
    @Test
    void anUnboundFieldIsRefused() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "nowhere", "String");

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/nowhere -> film UPDATE UNBOUND UNBOUND");
        });
    }

    /** A list-typed leaf carrier: the cardinality belongs on the payload argument, not on a field. */
    @Test
    void aListTypedLeafCarrierIsRefused() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "rating", "String", true);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/rating -> film UPDATE LIST_CARRIER NAME_MATCHED");
        });
    }

    /**
     * A composing {@code @condition} on a leaf carrier. The walkers emit no input-field condition on
     * either verb, so admitting the field would drop the directive silently.
     */
    @Test
    void aComposingConditionOnALeafCarrierIsRefused() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "rating", "String");
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "rating", false);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/rating -> film UPDATE AUTHORED_CONDITION NAME_MATCHED");
        });
    }

    /**
     * The same cause where the condition is the {@code override: true} spelling and the field is not
     * the condition-owned carrier, the classifier having given it the node-id arm instead and left
     * the condition attached beside it. The case that is why this cause is not read off the role
     * relation's {@code authored_condition} column, which by construction says nothing here.
     */
    @Test
    void anOverrideConditionBesideAnotherRoleIsRefusedAsAuthoredCondition() {
        withWriteSurface(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
            payloadField(dsl, "title", "String");
            payloadField(dsl, "filmId", "ID");
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "filmId", "Film");
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "filmId", true);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/filmId -> film UPDATE AUTHORED_CONDITION NODE_ID");
        });
    }

    // ===== The two causes that cover a grouping as well as a leaf =====

    /**
     * A list-typed nesting is refused for the same cause as a list-typed leaf, and the role beside
     * it is what tells the two apart. A list grouping has no meaning when the walker flattens the
     * whole tree onto one outer row.
     */
    @Test
    void aListTypedNestingIsRefusedWithTheGroupingRole() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            nestedGrouping(dsl, "extras", "FilmExtrasInput", "rating", true);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/extras -> film UPDATE LIST_CARRIER NESTING");
        });
    }

    /**
     * A {@code @condition} on a nesting is refused too, and the leaf below it is not reported at all:
     * a refused grouping is never descended into, so nothing under it is classified and a refusal
     * there would be a diagnostic the build never mints. The leaf is a real column of the write
     * target on purpose, so the cut is visible rather than coincidental.
     */
    @Test
    void aRefusedNestingCutsTheOccurrencesBelowIt() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            nestedGrouping(dsl, "extras", "FilmExtrasInput", "rating", false);
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "extras", false);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/extras -> film UPDATE AUTHORED_CONDITION NESTING");
        });
    }

    /**
     * An admitted nesting is not a refusal and does not cut: the leaf below it is classified against
     * the same write target the grouping was handed, and its own refusal is reported at its own
     * occurrence.
     */
    @Test
    void anAdmittedNestingReportsTheLeafBelowIt() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            nestedGrouping(dsl, "extras", "FilmExtrasInput", "nowhere", false);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/extras/nowhere -> film UPDATE UNBOUND UNBOUND");
        });
    }

    // ===== The ranking, which is the walkers' test order and not this relation's =====

    /**
     * A remote carrier that is also list-typed is reported as remote. The binding switch runs ahead
     * of the shape gate, so the list test is never reached, and a union here would surface a second
     * row for a rule the build never ran.
     */
    @Test
    void aRemoteCarrierOutranksTheListShape() {
        withWriteSurface(dsl -> {
            publisherIsANode(dsl);
            payloadField(dsl, "title", "String");
            payloadField(dsl, "publisherIds", "ID", true);
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "publisherIds", "Publisher");

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/publisherIds -> film UPDATE REMOTE_CARRIER NODE_ID");
        });
    }

    /**
     * A list-typed field carrying a {@code @condition} is reported as the list shape, which is the
     * first of the shape gate's two tests.
     */
    @Test
    void theListShapeOutranksTheCondition() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "rating", "String", true);
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "rating", false);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/rating -> film UPDATE LIST_CARRIER NAME_MATCHED");
        });
    }

    // ===== What is not a refusal =====

    /**
     * A payload every field of which the walkers admit has no row here, which is the ordinary
     * working write surface. Stated so the relation cannot be read as listing input fields.
     */
    @Test
    void anAdmittedPayloadIsSilent() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "rating", "String");

            assertThat(refusals(dsl)).isEmpty();
        });
    }

    /**
     * A plain {@code @reference} with a non-empty path is remote for a reason that has nothing to do
     * with a node id, and it is refused all the same. Stated beside the node-id case so the remote
     * cause is not read as a decode-only answer.
     */
    @Test
    void aPlainReferencePathIsRefusedAsRemote() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "title", "String");
            payloadField(dsl, "alt_key", "String");
            seedFieldReference(dsl, GRAPH, "FilmUpdateInput", "alt_key", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmUpdateInput", "alt_key", 0, 0, "publisher", null);

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/alt_key -> film UPDATE REMOTE_CARRIER NAME_MATCHED");
        });
    }

    /**
     * An INSERT's payload produces nothing here however its fields read: the population is the write
     * payload's, and INSERT admits its input through a gate whose refusals are not these.
     */
    @Test
    void anInsertPayloadIsOutsideThePopulation() {
        withWriteSurface(dsl -> {
            seedField(dsl, GRAPH, "Mutation", "createFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "createFilm", "INSERT");
            seedArgument(dsl, GRAPH, "Mutation", "createFilm", "in", "FilmUpdateInput");
            seedInputField(dsl, GRAPH, "FilmUpdateInput", "nowhere", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "createFilm", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "nowhere", "String"));

            assertThat(refusals(dsl)).isEmpty();
        });
    }

    /**
     * The payload argument itself is not an occurrence this relation reports on. Its own three
     * refusals are folded into {@code intent_mutation_write_payload}'s absence, so a payload whose
     * argument shape is refused has no write surface and therefore no refused occurrence either.
     */
    @Test
    void theArgumentOccurrenceItselfIsNotReported() {
        withWriteSurface(dsl -> {
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "dryRun", "String");
            payloadField(dsl, "nowhere", "String");

            assertThat(refusals(dsl)).isEmpty();
        });
    }

    // ===== The grain =====

    /**
     * One input type shared by two write surfaces is refused once under each, and each row names the
     * mutation it broke. The whole reason the grain is the occurrence rather than the input field:
     * a consumer pointing an author at the refusal needs the write surface the payload belongs to,
     * and the field alone does not carry it.
     */
    @Test
    void aSharedPayloadTypeIsRefusedOncePerWriteSurface() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "nowhere", "String");
            seedField(dsl, GRAPH, "Mutation", "updateFilmToo", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilmToo", "UPDATE");
            seedArgument(dsl, GRAPH, "Mutation", "updateFilmToo", "in", "FilmUpdateInput");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilmToo", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "nowhere", "String"));

            assertThat(refusals(dsl)).containsExactly(
                "Mutation.updateFilm(in)/nowhere -> film UPDATE UNBOUND UNBOUND",
                "Mutation.updateFilmToo(in)/nowhere -> film UPDATE UNBOUND UNBOUND");
        });
    }

    /** The graph partition holds: a sibling graph reads none of this one's refusals. */
    @Test
    void aSiblingGraphReadsNoRefusals() {
        withWriteSurface(dsl -> {
            payloadField(dsl, "nowhere", "String");

            derive(dsl);
            assertThat(dsl.selectFrom(INTENT_MUTATION_PAYLOAD_REFUSAL)
                .where(INTENT_MUTATION_PAYLOAD_REFUSAL.GRAPH_NAME.eq("other"))
                .fetch()).isEmpty();
        });
    }
}
