package no.sikt.graphitron.rewrite.validation;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.assertHasKind;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.schema;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.TestFixtures;

/**
 * Validates {@link QueryInterfaceField} (the multi-table polymorphic root case).
 * Tests cover the well-formed path plus the validator's same-table rejection; a participant
 * without a primary key and keys of different arity are the store's
 * {@code PARTICIPANT_WITHOUT_PRIMARY_KEY} and {@code PARTICIPANT_KEYS_DIFFER_IN_ARITY}.
 */
@UnitTier
class QueryInterfaceFieldValidationTest {

    private static final TableRef FILM = TestFixtures.tableRef("film", "FILM", "Film",
        List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")));
    private static final TableRef ACTOR = TestFixtures.tableRef("actor", "ACTOR", "Actor",
        List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer")));

    @Test
    void wellFormed_singleTableBoundParticipant_noErrors() {
        var participants = List.<ParticipantRef>of(new ParticipantRef.TableBound("Film", FILM, null));
        var iface = new GraphitronType.InterfaceType("Searchable", null, participants);
        var field = new QueryInterfaceField("Query", "search", null,
            new ReturnTypeRef.PolymorphicReturnType("Searchable", new FieldWrapper.List(false, false)),
            participants, List.of(), List.of(), java.util.Optional.empty());
        var sch = new GraphitronSchema(
            java.util.Map.of(
                "Searchable", iface,
                "Query", new GraphitronType.RootType("Query", null)),
            java.util.Map.of(graphql.schema.FieldCoordinates.coordinates("Query", "search"), field));
        assertThat(validate(sch)).isEmpty();
    }

    @Test
    void wellFormed_twoSameArityParticipants_noErrors() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", FILM, null),
            new ParticipantRef.TableBound("Actor", ACTOR, null));
        var iface = new GraphitronType.InterfaceType("Searchable", null, participants);
        var field = new QueryInterfaceField("Query", "search", null,
            new ReturnTypeRef.PolymorphicReturnType("Searchable", new FieldWrapper.List(false, false)),
            participants, List.of(), List.of(), java.util.Optional.empty());
        var sch = new GraphitronSchema(
            java.util.Map.of(
                "Searchable", iface,
                "Query", new GraphitronType.RootType("Query", null)),
            java.util.Map.of(graphql.schema.FieldCoordinates.coordinates("Query", "search"), field));
        assertThat(validate(sch)).isEmpty();
    }

    @Test
    void rejects_sameTableParticipantsWithoutDiscriminator() {
        // Discriminability floor: two participant types backed by the same table share a
        // recordClass, so a returned record cannot be discriminated from its Java type alone.
        // Without a @discriminator this is a build error rather than a silent misdispatch.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", FILM, null),
            new ParticipantRef.TableBound("FilmVariant", FILM, null));
        var iface = new GraphitronType.InterfaceType("Searchable", null, participants);
        var field = new QueryInterfaceField("Query", "search", null,
            new ReturnTypeRef.PolymorphicReturnType("Searchable", new FieldWrapper.List(false, false)),
            participants, List.of(), List.of(), java.util.Optional.empty());
        var sch = new GraphitronSchema(
            java.util.Map.of(
                "Searchable", iface,
                "Query", new GraphitronType.RootType("Query", null)),
            java.util.Map.of(graphql.schema.FieldCoordinates.coordinates("Query", "search"), field));
        assertHasKind(validate(sch), RejectionKind.AUTHOR_ERROR,
            "maps types 'Film' and 'FilmVariant' to the same table");
    }

    @Test
    void rejects_sameTableParticipantsWithDiscriminator() {
        // Same-table polymorphism belongs in a single-table discriminated interface
        // (TableInterfaceType), not a plain multitable interface/union — even when every colliding
        // type carries a @discriminator, record-class dispatch can't tell them apart (shared
        // recordClass). Same AUTHOR_ERROR as the no-discriminator case (use TableInterfaceType or split).
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", FILM, "F"),
            new ParticipantRef.TableBound("FilmVariant", FILM, "V"));
        var iface = new GraphitronType.InterfaceType("Searchable", null, participants);
        var field = new QueryInterfaceField("Query", "search", null,
            new ReturnTypeRef.PolymorphicReturnType("Searchable", new FieldWrapper.List(false, false)),
            participants, List.of(), List.of(), java.util.Optional.empty());
        var sch = new GraphitronSchema(
            java.util.Map.of(
                "Searchable", iface,
                "Query", new GraphitronType.RootType("Query", null)),
            java.util.Map.of(graphql.schema.FieldCoordinates.coordinates("Query", "search"), field));
        assertHasKind(validate(sch), RejectionKind.AUTHOR_ERROR,
            "maps types 'Film' and 'FilmVariant' to the same table");
    }
}
