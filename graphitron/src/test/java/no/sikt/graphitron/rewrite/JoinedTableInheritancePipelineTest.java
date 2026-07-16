package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification coverage for first-class discriminated joined-table (class-table) inheritance.
 * Behaviour (join orientation, NULL-through, standalone resolution) is the runtime property the
 * {@code @ExecutionTier} {@code GraphQLQueryTest.allParties_*} / {@code allIndividuals_*} tests prove
 * against real PostgreSQL; this pipeline tier pins the classification shape the emitter relies on:
 * a participant whose own {@code @table} differs from the discriminated base classifies as a
 * {@link ParticipantRef.JoinedTableBound} carrying the resolved child&rarr;parent hop, its inherited
 * (parent-{@code @reference}) field as a {@link ChildField.ColumnReferenceField} and its own column
 * as a plain {@link ChildField.ColumnField}.
 *
 * <p>Driven by the {@code party} + {@code party_individual} + {@code party_company} single-column
 * shared-PK fixture in {@code graphitron-sakila-db/src/main/resources/init.sql}.
 */
@PipelineTier
class JoinedTableInheritancePipelineTest {

    private static final String PARTY_SDL = """
        interface Party @table(name: "party") @discriminate(on: "party_kind") {
            partyId:     Int!    @field(name: "party_id")
            displayName: String! @field(name: "display_name")
        }
        type Individual implements Party @table(name: "party_individual") @discriminator(value: "INDIVIDUAL") {
            partyId:     Int!    @field(name: "party_id")
            displayName: String! @reference(path: [{key: "party_individual_party_id_fkey"}]) @field(name: "display_name")
            birthDate:   String  @field(name: "birth_date")
        }
        type Company implements Party @table(name: "party_company") @discriminator(value: "COMPANY") {
            partyId:     Int!    @field(name: "party_id")
            displayName: String! @reference(path: [{key: "party_company_party_id_fkey"}]) @field(name: "display_name")
            orgNumber:   String  @field(name: "org_number")
        }
        type Query { allParties: [Party!]! }
        """;

    @Test
    void joinedTableParticipant_classifiesAsJoinedTableBound_withResolvedHopAndFieldResidence() {
        var schema = TestSchemaHelper.buildSchema(PARTY_SDL);
        assertThat(schema.diagnostics())
            .as("the textbook PK=FK joined-table fixture must classify cleanly")
            .noneMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA);

        var party = (TableInterfaceType) schema.type("Party");
        var individual = party.participants().stream()
            .filter(p -> p.typeName().equals("Individual"))
            .findFirst().orElseThrow();

        assertThat(individual)
            .as("a participant on its own detail table is a JoinedTableBound, not a single-table TableBound")
            .isInstanceOf(ParticipantRef.JoinedTableBound.class);
        var jtb = (ParticipantRef.JoinedTableBound) individual;
        assertThat(jtb.detailTable().sameTable("party_individual")).isTrue();
        assertThat(jtb.discriminatorValue()).isEqualTo("INDIVIDUAL");
        // The child->parent hop resolves from the inherited displayName @reference: detail -> base.
        assertThat(jtb.childToParent().originTable().sameTable("party_individual")).isTrue();
        assertThat(jtb.childToParent().targetTable().sameTable("party")).isTrue();

        // Residence is read off the field variant: inherited displayName is a ColumnReferenceField
        // (resolved on the base via the reference path), birthDate a plain ColumnField on the detail.
        assertThat(schema.field("Individual", "displayName"))
            .isInstanceOf(ChildField.ColumnReferenceField.class);
        assertThat(schema.field("Individual", "birthDate"))
            .isInstanceOf(ChildField.ColumnField.class);
        // The shared single-column PK partyId lives on the detail too, so it carries no @reference
        // and stays a plain ColumnField.
        assertThat(schema.field("Individual", "partyId"))
            .isInstanceOf(ChildField.ColumnField.class);
    }

    @Test
    void mixedInterface_discriminatorOnlyParticipantAlongsideJoinedTableParticipant() {
        // A TableInterfaceType may freely mix a discriminator-only participant (its data wholly on the
        // base) with a joined-table participant (its own detail table). The former stays TableBound,
        // the latter is JoinedTableBound; the emitter switches on the variant.
        var schema = TestSchemaHelper.buildSchema("""
            interface Party @table(name: "party") @discriminate(on: "party_kind") {
                partyId:     Int!    @field(name: "party_id")
                displayName: String! @field(name: "display_name")
            }
            type GenericParty implements Party @table(name: "party") @discriminator(value: "GENERIC") {
                partyId:     Int!    @field(name: "party_id")
                displayName: String! @field(name: "display_name")
            }
            type Individual implements Party @table(name: "party_individual") @discriminator(value: "INDIVIDUAL") {
                partyId:     Int!    @field(name: "party_id")
                displayName: String! @reference(path: [{key: "party_individual_party_id_fkey"}]) @field(name: "display_name")
                birthDate:   String  @field(name: "birth_date")
            }
            type Query { allParties: [Party!]! }
            """);
        assertThat(schema.diagnostics()).noneMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA);

        var party = (TableInterfaceType) schema.type("Party");
        assertThat(party.participants().stream().filter(p -> p.typeName().equals("GenericParty")).findFirst().orElseThrow())
            .as("a participant whose @table is the base stays a single-table TableBound")
            .isInstanceOf(ParticipantRef.TableBound.class);
        assertThat(party.participants().stream().filter(p -> p.typeName().equals("Individual")).findFirst().orElseThrow())
            .isInstanceOf(ParticipantRef.JoinedTableBound.class);
    }

    @Test
    void joinedTableParticipant_withNoInheritedReference_rejectedWithCandidateFkHint() {
        // A participant on its own detail table with no base-resident field carrying @reference cannot
        // pin the base->detail join in the unambiguous shape (disambiguation of the ambiguous shapes is deferred). The
        // classifier rejects it at build time with a candidate-FK hint rather than guessing the join.
        var schema = TestSchemaHelper.buildSchema("""
            interface Party @table(name: "party") @discriminate(on: "party_kind") {
                partyId: Int! @field(name: "party_id")
            }
            type Individual implements Party @table(name: "party_individual") @discriminator(value: "INDIVIDUAL") {
                partyId:   Int!   @field(name: "party_id")
                birthDate: String @field(name: "birth_date")
            }
            type Query { allParties: [Party!]! }
            """);

        assertThat(schema.diagnostics())
            .as("a joined-table participant with no inherited @reference to name the join must be rejected")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("Individual")
                && e.message().contains("party_individual")
                && e.message().contains("no base-resident field carrying @reference")
                && e.message().contains("party_individual_party_id_fkey"));
    }

    @Test
    void detailJoinNotPkEqFk_rejected() {
        // PK=FK invariant: the detail table's FK columns to the base must BE the detail's own primary
        // key, so the base->detail join is single-valued. Here CityPlace's detail table 'city' joins
        // the base 'country' on country_id (city's FK), but city's primary key is the surrogate
        // city_id — not the join column. The base->detail join would fan out, so it is rejected.
        var schema = TestSchemaHelper.buildSchema("""
            interface Place @table(name: "country") @discriminate(on: "country") {
                placeId: Int! @field(name: "country_id")
            }
            type CityPlace implements Place @table(name: "city") @discriminator(value: "CITY") {
                placeId:   Int!    @field(name: "country_id")
                placeName: String! @reference(path: [{key: "city_country_id_fkey"}]) @field(name: "country")
            }
            type Query { allPlaces: [Place!]! }
            """);

        assertThat(schema.diagnostics())
            .as("a detail table whose FK to the base is not its own primary key must be rejected as non-single-valued")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("CityPlace")
                && e.message().contains("not single-valued")
                && e.message().contains("city")
                && e.message().contains("must be the detail table's own primary key"));
    }

    @Test
    void parentReferenceToNonBaseTable_rejected() {
        // same-base invariant: a joined-table participant's inherited @reference must bridge back to the
        // discriminated base. AddressPlace's detail table 'address' has no FK to the base 'country'; its
        // only reference (address_city_id_fkey) resolves to 'city', which is not the base. A reference
        // to some other table is not a base bridge, so it is rejected.
        var schema = TestSchemaHelper.buildSchema("""
            interface Place @table(name: "country") @discriminate(on: "country") {
                placeId: Int! @field(name: "country_id")
            }
            type AddressPlace implements Place @table(name: "address") @discriminator(value: "ADDRESS") {
                placeId:  Int!    @field(name: "address_id")
                cityName: String! @reference(path: [{key: "address_city_id_fkey"}]) @field(name: "city")
            }
            type Query { allPlaces: [Place!]! }
            """);

        assertThat(schema.diagnostics())
            .as("a joined-table participant whose parent-@reference resolves to a non-base table must be rejected")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("AddressPlace")
                && e.message().contains("does not resolve to the discriminated base table")
                && e.message().contains("country"));
    }
}
