package no.sikt.graphitron.rewrite.validation;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
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
 * Tests cover the well-formed path plus the validator rejections in
 * {@code GraphitronSchemaValidator.validateInterfaceType}: PK-less participants and PK-arity
 * mismatches across participants.
 */
@UnitTier
class QueryInterfaceFieldValidationTest {

    private static final TableRef FILM = TestFixtures.tableRef("film", "FILM", "Film",
        List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")));
    private static final TableRef ACTOR = TestFixtures.tableRef("actor", "ACTOR", "Actor",
        List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer")));
    private static final TableRef BAR = TestFixtures.tableRef("bar", "BAR", "Bar",
        List.of(
            new ColumnRef("id_1", "ID_1", "java.lang.Integer"),
            new ColumnRef("id_2", "ID_2", "java.lang.Integer")));
    private static final TableRef NO_PK = TestFixtures.tableRef("kpis", "KPIS", "Kpis", List.of());

    @Test
    void wellFormed_singleTableBoundParticipant_noErrors() {
        var participants = List.<ParticipantRef>of(new ParticipantRef.TableBound("Film", FILM, null));
        var iface = new GraphitronType.InterfaceType("Searchable", null, participants);
        var field = new QueryInterfaceField("Query", "search", null,
            new ReturnTypeRef.PolymorphicReturnType("Searchable", new FieldWrapper.List(false, false)),
            participants);
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
            participants);
        var sch = new GraphitronSchema(
            java.util.Map.of(
                "Searchable", iface,
                "Query", new GraphitronType.RootType("Query", null)),
            java.util.Map.of(graphql.schema.FieldCoordinates.coordinates("Query", "search"), field));
        assertThat(validate(sch)).isEmpty();
    }

    @Test
    void rejects_participantWithoutPrimaryKey() {
        // PK validation lives at the field level so the Node interface (heterogeneous PK
        // arities, dispatched via QueryNodeFetcher) does not trip a false rejection. The error
        // pins to the field qualified name.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", FILM, null),
            new ParticipantRef.TableBound("Kpis", NO_PK, null));
        var iface = new GraphitronType.InterfaceType("Searchable", null, participants);
        var field = new QueryInterfaceField("Query", "search", null,
            new ReturnTypeRef.PolymorphicReturnType("Searchable", new FieldWrapper.List(false, false)),
            participants);
        var sch = new GraphitronSchema(
            java.util.Map.of(
                "Searchable", iface,
                "Query", new GraphitronType.RootType("Query", null)),
            java.util.Map.of(graphql.schema.FieldCoordinates.coordinates("Query", "search"), field));
        assertHasKind(validate(sch), RejectionKind.AUTHOR_ERROR,
            "Field 'Query.search': participant 'Kpis' has no primary key");
    }

    @Test
    void rejects_mismatchedPkArityAcrossParticipants() {
        // Film has arity 1, Bar has arity 2 — stage-1 column count mismatch.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", FILM, null),
            new ParticipantRef.TableBound("Bar", BAR, null));
        var iface = new GraphitronType.InterfaceType("Searchable", null, participants);
        var field = new QueryInterfaceField("Query", "search", null,
            new ReturnTypeRef.PolymorphicReturnType("Searchable", new FieldWrapper.List(false, false)),
            participants);
        var sch = new GraphitronSchema(
            java.util.Map.of(
                "Searchable", iface,
                "Query", new GraphitronType.RootType("Query", null)),
            java.util.Map.of(graphql.schema.FieldCoordinates.coordinates("Query", "search"), field));
        assertHasKind(validate(sch), RejectionKind.AUTHOR_ERROR,
            "Field 'Query.search': primary-key arity mismatch");
    }
}
