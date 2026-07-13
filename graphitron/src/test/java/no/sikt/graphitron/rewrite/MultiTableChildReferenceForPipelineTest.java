package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ParticipantCorrelation;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema pipeline tests for {@code @referenceFor} (R458 slice 1): the explicit
 * per-participant join path on multi-table interface/union child fields. Slice 1 ships multi-FK
 * disambiguation and same-table self-FK participants, both lowering to
 * {@link ParticipantCorrelation.KeyTupleWhere}; multi-hop chains (slice 2) and condition correlation
 * (slice 3) are DEFERRED. Structural rejections cover placement, membership, duplicate {@code type:},
 * and per-route terminal-target mismatch.
 *
 * <p>Asserted on the classified field record / rejection, not on generated method bodies (per the
 * development principles).
 */
@PipelineTier
class MultiTableChildReferenceForPipelineTest {

    private static final String SLUG_POINTER = "roadmap/per-participant-multitable-child-join-paths.md";

    // ===== Multi-FK disambiguation (slice 1 capability) =====

    @Test
    void multiFkDisambiguation_classifiesToChosenFk() {
        // film has two FKs to language (language_id, original_language_id); auto-discovery from
        // language to film cannot disambiguate (rule 1c). @referenceFor picks one.
        var schema = TestSchemaHelper.buildSchema("""
            interface LangThing { rowId: Int }
            type Film implements LangThing @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Language @table(name: "language") {
              thing: LangThing @referenceFor(type: "Film", path: [{key: "film_original_language_id_fkey"}])
            }
            type Query { language: Language }
            """);
        var field = (ChildField.InterfaceField) schema.field("Language", "thing");
        var correlation = field.participantJoinPaths().get("Film");
        assertThat(correlation).isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
        var slots = ((ParticipantCorrelation.KeyTupleWhere) correlation).slots();
        // The chosen FK, not the arbitrary auto-discovery pick: participant side is
        // film.original_language_id (parent side is language.language_id).
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).targetSide().sqlName()).isEqualTo("original_language_id");
        assertThat(slots.get(0).sourceSide().sqlName()).isEqualTo("language_id");
    }

    @Test
    void multiFkDisambiguation_withoutReferenceFor_steersToReferenceFor() {
        // The control: without @referenceFor the multi-FK failure now names @referenceFor.
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
            .contains("@referenceFor")
            .contains(SLUG_POINTER);
    }

    // ===== Same-table self-FK route + override-merge (slice 1 capability) =====

    @Test
    void sameTableSelfFkRoute_classifies_andAutoDiscoveredSiblingMerges() {
        // category has a self-referencing FK (category_parent_category_id_fkey). CategorySelf is
        // backed by the same table as the parent (category); FilmCategory auto-discovers to category.
        // Override-merge: the explicit route overrides only its named participant.
        var schema = TestSchemaHelper.buildSchema("""
            interface CatThing { rowId: Int }
            type CategorySelf implements CatThing @table(name: "category") { rowId: Int @field(name: "category_id") }
            type FilmCategory implements CatThing @table(name: "film_category") { rowId: Int @field(name: "category_id") }
            type Category @table(name: "category") {
              thing: CatThing @referenceFor(type: "CategorySelf", path: [{key: "category_parent_category_id_fkey"}])
            }
            type Query { category: Category }
            """);
        var field = (ChildField.InterfaceField) schema.field("Category", "thing");
        assertThat(field.participantJoinPaths().keySet())
            .containsExactlyInAnyOrder("CategorySelf", "FilmCategory");

        var self = field.participantJoinPaths().get("CategorySelf");
        assertThat(self).isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
        var selfSlots = ((ParticipantCorrelation.KeyTupleWhere) self).slots();
        // Slot orientation pinned: parent side is category.parent_category_id, participant side is
        // category.category_id (navigate to the parent category). A flipped orientation is silently
        // wrong data; the execution tier asserts the resulting rows.
        assertThat(selfSlots).hasSize(1);
        assertThat(selfSlots.get(0).sourceSide().sqlName()).isEqualTo("parent_category_id");
        assertThat(selfSlots.get(0).targetSide().sqlName()).isEqualTo("category_id");

        // The auto-discovered sibling is still present in the carrier.
        assertThat(field.participantJoinPaths().get("FilmCategory"))
            .isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
    }

    // ===== Structural rejections: membership, duplicate, placement, terminal mismatch =====

    @Test
    void unknownType_rejectedListingValidParticipants() {
        var schema = TestSchemaHelper.buildSchema("""
            interface LangThing { rowId: Int }
            type Film implements LangThing @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Language @table(name: "language") {
              thing: LangThing @referenceFor(type: "Nonexistent", path: [{key: "film_language_id_fkey"}])
            }
            type Query { language: Language }
            """);
        var rejection = rejectionOf(schema.field("Language", "thing"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("Nonexistent")
            .contains("not a table-bound participant")
            .contains("Film");
    }

    @Test
    void duplicateType_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            interface LangThing { rowId: Int }
            type Film implements LangThing @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Language @table(name: "language") {
              thing: LangThing
                @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
                @referenceFor(type: "Film", path: [{key: "film_original_language_id_fkey"}])
            }
            type Query { language: Language }
            """);
        var rejection = rejectionOf(schema.field("Language", "thing"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("Film")
            .contains("more than once");
    }

    @Test
    void terminalMismatch_rejectedNamingParticipant() {
        // Foo is backed by city, but its @referenceFor path (film_language_id_fkey, from language)
        // lands on film — a terminal-target mismatch naming Foo.
        var schema = TestSchemaHelper.buildSchema("""
            interface Thing { rowId: Int }
            type Foo implements Thing @table(name: "city") { rowId: Int @field(name: "city_id") }
            type FilmX implements Thing @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Language @table(name: "language") {
              thing: Thing
                @referenceFor(type: "Foo", path: [{key: "film_language_id_fkey"}])
                @referenceFor(type: "FilmX", path: [{key: "film_language_id_fkey"}])
            }
            type Query { language: Language }
            """);
        var rejection = rejectionOf(schema.field("Language", "thing"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("Foo")
            .contains("does not land on that participant's table");
    }

    @Test
    void misplaced_onPlainField_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") {
              title: String @referenceFor(type: "Whatever", path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "title"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("@referenceFor is only valid on a multi-table interface/union child field")
            .contains("Remove the @referenceFor directive");
    }

    @Test
    void misplaced_onRootField_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Query {
              film: Film @referenceFor(type: "Whatever", path: [{key: "film_language_id_fkey"}])
            }
            """);
        var rejection = rejectionOf(schema.field("Query", "film"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("@referenceFor is only valid on a multi-table interface/union child field");
    }

    @Test
    void misplaced_onSingleTablePolymorphicField_rejected() {
        // Film.content returns a single-table (discriminated) interface — the @discriminate mechanism,
        // not multi-table dispatch. @referenceFor has no participant set to bind a path to here.
        var schema = TestSchemaHelper.buildSchema("""
            interface Content @table(name: "content") @discriminate(on: "content_type") {
              rowId: Int @field(name: "content_id")
            }
            type Movie implements Content @table(name: "content") @discriminator(value: "MOVIE") {
              rowId: Int @field(name: "content_id")
            }
            type Film @table(name: "film") {
              content: Content @referenceFor(type: "Movie", path: [{key: "content_film_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "content"));
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(rejection.message())
            .contains("@referenceFor is only valid on a multi-table interface/union child field");
    }

    // ===== Slice-1 deferrals: condition (slice 3) and multi-hop (slice 2) =====

    @Test
    void conditionRoute_deferredToSlice3() {
        var schema = TestSchemaHelper.buildSchema("""
            interface FilmReferrer { rowId: Int }
            type Inventory implements FilmReferrer @table(name: "inventory") { rowId: Int @field(name: "inventory_id") }
            type Content implements FilmReferrer @table(name: "content") { rowId: Int @field(name: "content_id") }
            type Film @table(name: "film") {
              referrer: FilmReferrer @referenceFor(type: "Inventory", path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "referrer"));
        assertThat(rejection).isInstanceOf(Rejection.Deferred.class);
        assertThat(rejection.message())
            .contains("Inventory")
            .contains("condition")
            .contains(SLUG_POINTER);
    }

    @Test
    void multiHopRoute_deferredToSlice2() {
        var schema = TestSchemaHelper.buildSchema("""
            interface ActorThing { rowId: Int }
            type ActorP implements ActorThing @table(name: "actor") { rowId: Int @field(name: "actor_id") }
            type Inventory implements ActorThing @table(name: "inventory") { rowId: Int @field(name: "inventory_id") }
            type Film @table(name: "film") {
              thing: ActorThing @referenceFor(type: "ActorP", path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var rejection = rejectionOf(schema.field("Film", "thing"));
        assertThat(rejection).isInstanceOf(Rejection.Deferred.class);
        assertThat(rejection.message())
            .contains("ActorP")
            .contains("multi-hop")
            .contains(SLUG_POINTER);
    }

    // ===== Producer-arm coverage: union element type, record-backed parent =====

    @Test
    void unionProducerArm_explicitRouteOverridesOneParticipant() {
        // Union element-type arm. Inventory's single-hop FK is stated explicitly with @referenceFor
        // (override), Content stays auto-discovered — both land KeyTupleWhere.
        var schema = TestSchemaHelper.buildSchema("""
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type Content @table(name: "content") { contentId: Int! @field(name: "content_id") }
            union FilmReferrer = Inventory | Content
            type Film @table(name: "film") {
              referrer: FilmReferrer @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var field = (ChildField.UnionField) schema.field("Film", "referrer");
        assertThat(field.participantJoinPaths().keySet())
            .containsExactlyInAnyOrder("Inventory", "Content");
        assertThat(field.participantJoinPaths().get("Inventory"))
            .isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
        assertThat(field.participantJoinPaths().get("Content"))
            .isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
    }

    @Test
    void recordParentArm_multiFkDisambiguation_classifies() {
        // Record-backed-parent producer arm: Language is produced by a @service returning a record.
        var schema = TestSchemaHelper.buildSchema("""
            interface LangThing { rowId: Int }
            type Film implements LangThing @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Language {
              thing: LangThing @referenceFor(type: "Film", path: [{key: "film_original_language_id_fkey"}])
            }
            type Query {
              language: Language @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            """);
        var field = (ChildField.InterfaceField) schema.field("Language", "thing");
        var correlation = field.participantJoinPaths().get("Film");
        assertThat(correlation).isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
    }

    private static Rejection rejectionOf(no.sikt.graphitron.rewrite.model.GraphitronField field) {
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) field).rejection();
    }
}
