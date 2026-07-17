package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ParticipantCorrelation;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema pipeline tests for the build-time gate on multi-table
 * interface/union child fields. The only supported per-participant join shape is the
 * auto-discovered single-hop foreign key from each participant's table back to the parent/hub
 * table; every richer shape is rejected at classification time in
 * {@code FieldBuilder.resolveChildPolymorphicJoinPaths} rather than lowered by
 * {@code MultiTablePolymorphicEmitter} to an arbitrary-participant-row result on a green build.
 *
 * <p>The three rules under test:
 *
 * <ul>
 *   <li><b>1a</b> — any field-level {@code @reference} is a structural rejection (directive
 *       presence, not path shape: condition, multi-hop, and explicit single-hop {@code {key:}}
 *       all reject identically). A single stated path cannot express a distinct join per
 *       participant.</li>
 *   <li><b>1b</b> — a same-table participant (participant table equals the parent/hub table)
 *       produces an empty auto-path and is rejected as a deferred capability keyed to
 *       {@code per-participant-multitable-child-join-paths}.</li>
 *   <li><b>1c</b> — a zero-FK / multi-FK auto-discovery failure carries the multi-table-child
 *       context wrapper (pointing at the deferred capability) rather than the bare
 *       "add a @reference directive" steer.</li>
 * </ul>
 *
 * <p>Asserted on the classified field record / rejection, not on generated method bodies (per the
 * development principles).
 */
@PipelineTier
class MultiTableChildReferencePathRejectionPipelineTest {

    /** Two participants that both FK to {@code film} — the supported auto-discovered shape. */
    private static final String INTERFACE_PARTICIPANTS = """
        interface FilmReferrer { rowId: Int }
        type Inventory implements FilmReferrer @table(name: "inventory") {
          rowId: Int @field(name: "inventory_id")
        }
        type Content implements FilmReferrer @table(name: "content") {
          rowId: Int @field(name: "content_id")
        }
        """;

    private static final String UNION_PARTICIPANTS = """
        type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
        type Content @table(name: "content") { contentId: Int! @field(name: "content_id") }
        union FilmReferrer = Inventory | Content
        """;

    // ===== Rule 1a: any field-level @reference is a structural rejection =====

    @Test
    void interfaceChild_conditionReference_rejectedStructurally() {
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film @table(name: "film") {
              referrer: FilmReferrer @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "referrer"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("Film.referrer")
            .contains("an explicit @reference is not supported on a multi-table interface/union child field")
            .contains("auto-discovered")
            .contains("Remove the @reference directive");
    }

    @Test
    void interfaceChild_multiHopReference_rejectedStructurally() {
        // Same rejection as the condition path — the rule is directive presence, not path shape.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film @table(name: "film") {
              referrer: FilmReferrer @reference(path: [{key: "inventory_film_id_fkey"}, {key: "content_film_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "referrer"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("an explicit @reference is not supported on a multi-table interface/union child field");
    }

    @Test
    void interfaceChild_singleHopKeyReference_rejectedStructurally() {
        // Explicit single-hop {key:} — structurally identical to what auto-discovery would produce
        // for one participant, yet still rejected: the rule is directive presence, not path shape.
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film @table(name: "film") {
              referrer: FilmReferrer @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "referrer"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("an explicit @reference is not supported on a multi-table interface/union child field");
    }

    @Test
    void unionChild_reference_rejectedStructurally() {
        // Union-typed variant of rule 1a (the other polymorphic element arm).
        var schema = TestSchemaHelper.buildSchema(UNION_PARTICIPANTS + """
            type Film @table(name: "film") {
              referrer: FilmReferrer @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "referrer"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("an explicit @reference is not supported on a multi-table interface/union child field");
    }

    @Test
    void recordParentInterfaceChild_reference_rejectedStructurally() {
        // Record-backed-parent producer arm (classifyRecordParentPolymorphicChild) reaches the same
        // gate. Film is produced by a @service returning FilmRecord (JooqTableRecordType parent).
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film {
              referrer: FilmReferrer @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Query {
              film: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var rejection = rejectionOf(schema.field("Film", "referrer"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("an explicit @reference is not supported on a multi-table interface/union child field");
    }

    // ===== Rule 1b: same-table participant is a deferred capability =====

    @Test
    void interfaceChild_sameTableParticipant_withoutReferenceFor_steersToReferenceFor() {
        // FilmSelf is backed by the same table (film) as the parent/hub. parsePath skips FK
        // auto-discovery when source and target tables match, so no correlation is auto-derivable.
        // With @referenceFor shipping the self-FK route in slice 1, this is now an author-correctable
        // structural steer (state the self-referencing key), not a deferred capability.
        var schema = TestSchemaHelper.buildSchema("""
            interface FilmThing { rowId: Int }
            type FilmSelf implements FilmThing @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Inventory implements FilmThing @table(name: "inventory") { rowId: Int @field(name: "inventory_id") }
            type Film @table(name: "film") { thing: FilmThing }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "thing"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("FilmSelf")
            .contains("same table as the parent/hub ('film')")
            .contains("@referenceFor(type: \"FilmSelf\"");
    }

    // ===== Rule 1c: auto-discovery FK-count failures carry the multi-table-child context wrapper =====

    @Test
    void interfaceChild_zeroFkParticipant_wrapsAutoDiscoveryFailureWithContext() {
        // Neither inventory nor store has an FK to actor, so auto-discovery finds zero FKs. The
        // generic fkCountMessage steers toward "add a @reference directive", which on these fields
        // leads straight into rule 1a; the wrapper redirects authors to the deferred capability.
        var schema = TestSchemaHelper.buildSchema("""
            interface ActorThing { rowId: Int }
            type Inventory implements ActorThing @table(name: "inventory") { rowId: Int @field(name: "inventory_id") }
            type Store implements ActorThing @table(name: "store") { rowId: Int @field(name: "store_id") }
            type Actor @table(name: "actor") { thing: ActorThing }
            type Query { actor: Actor }
            """);
        var rejection = rejectionOf(schema.field("Actor", "thing"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("no foreign key found between tables 'actor'")
            .contains("an explicit @reference is not supported on multi-table interface/union child fields");
    }

    @Test
    void interfaceChild_multiFkParticipant_wrapsAutoDiscoveryFailureWithContext() {
        // film has two FKs to language (language_id, original_language_id), so auto-discovery from
        // language to film finds multiple FKs and cannot disambiguate. Same context wrapper.
        var schema = TestSchemaHelper.buildSchema("""
            interface LangThing { rowId: Int }
            type Film implements LangThing @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Language @table(name: "language") { thing: LangThing }
            type Query { language: Language }
            """);
        var rejection = rejectionOf(schema.field("Language", "thing"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("multiple foreign keys found between tables 'language' and 'film'")
            .contains("an explicit @reference is not supported on multi-table interface/union child fields");
    }

    // ===== Control: the auto-discovered fixture still classifies, carrying KeyTupleWhere =====

    @Test
    void interfaceChild_autoDiscovered_classifiesWithNonEmptyFkPathCarrier() {
        var schema = TestSchemaHelper.buildSchema(INTERFACE_PARTICIPANTS + """
            type Film @table(name: "film") { referrers: [FilmReferrer!]! }
            type Query { film: Film }
            """);
        var field = (ChildField.InterfaceField) schema.field("Film", "referrers");
        assertThat(field.participantJoinPaths().keySet())
            .containsExactlyInAnyOrder("Inventory", "Content");
        for (ParticipantCorrelation correlation : field.participantJoinPaths().values()) {
            assertThat(correlation).isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
            assertThat(((ParticipantCorrelation.KeyTupleWhere) correlation).slots()).isNotEmpty();
        }
    }

    private static Rejection rejectionOf(no.sikt.graphitron.rewrite.model.GraphitronField field) {
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) field).rejection();
    }
}
