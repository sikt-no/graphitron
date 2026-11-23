package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.FacetFragment;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pipeline-tier assertions on the condition command relation over classified fixture schemas:
 * the produced rows, their keys, and the producer-decided data (glue refs, facet fragments, the
 * signature facts). The emitted conditions are the store's and are covered by the execution tier;
 * this class pins what the producer mints, so it asserts on rows, never on emitted code. The
 * relation's membership enforcer (relation key-set equals the schema-derived covered set) lives in
 * {@code ConditionMembershipTest}.
 */
@PipelineTier
class ConditionCommandsPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    @Test
    void filteredRootCoordinate_producesOneRow() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argCondition"})): [Language!]!
                unfiltered: [Language!]!
            }
            """.formatted(STUB));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // Non-vacuity and bounding: exactly one covered coordinate; the filterless one has no row.
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.coordinate()).isEqualTo(FieldCoordinates.coordinates("Query", "languages"));
        assertThat(row.table().tableName()).isEqualTo("language");

        // Glue is total and minted from the naming vocabulary; the relation's landing addresses
        // are the distinct glue owners.
        assertThat(row.glue().owner().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".conditions.QueryConditions");
        assertThat(row.glue().methodName()).isEqualTo("languagesCondition");
        assertThat(row.readsRequestContext()).isFalse();
        assertThat(row.readsSession()).isFalse();
        assertThat(row.decodesNodeId()).isFalse();
        assertThat(relation.units()).containsExactly(row.glue().owner());
    }

    @Test
    void facetedCoordinate_mintsABaseFragmentAndOnePerFacet() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                title: [String!] @field(name: "title") @asFacet
                length: [Int] @field(name: "length") @asFacet
                releaseYear: [Int!] @field(name: "release_year")
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);

        // The fragment set is data: one base plus one per facet, methods minted from the naming
        // vocabulary.
        assertThat(row.facets()).extracting(f -> f.method().methodName()).containsExactly(
            "filmsFacetBaseCondition", "filmsFacet_titleCondition", "filmsFacet_lengthCondition");
    }

    @Test
    void facetedCoordinate_ownFragmentsKeyedByInputFieldName_referenceTermCarriesTheReach() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                title: [String!] @field(name: "title") @asFacet
                actor: [String!] @field(name: "last_name") @asFacet
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        var row = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE).rows().get(0);

        // One base, then one own fragment per facet keyed by its input field name; the own
        // fragment carries the facet's term, the reference facet's with the narrowed reach.
        assertThat(row.facets()).hasSize(3);
        assertThat(row.facets().get(0)).isInstanceOf(FacetFragment.Base.class);
        assertThat(row.facets().subList(1, 3))
            .allSatisfy(f -> assertThat(f).isInstanceOf(FacetFragment.Own.class))
            .extracting(f -> ((FacetFragment.Own) f).inputFieldName())
            .containsExactly("title", "actor");
        var title = ((FacetFragment.Own) row.facets().get(1)).term().orElseThrow();
        assertThat(title.reach().isEmpty()).isTrue();
        assertThat(title.columns()).extracting(c -> c.sqlName()).containsExactly("title");
        var actor = ((FacetFragment.Own) row.facets().get(2)).term().orElseThrow();
        assertThat(actor.reach().hops()).extracting(h -> h.targetTable().tableName())
            .containsExactly("film_actor", "actor");
        assertThat(actor.columns()).extracting(c -> c.sqlName()).containsExactly("last_name");
    }

    @Test
    void lookupCoordinate_authoredFilterProducesARow() {
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customersByKey(
                    customer_id: [Int!]! @lookupKey,
                    email: String @field(name: "email")
                        @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: email"}, override: true)
                ): [Customer!]!
            }
            """.formatted(STUB));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // Lookup keys ride the VALUES join and are not predicates: the row carries only the
        // authored non-key filter, an ordinary row the lookup rows method calls beside its
        // VALUES join.
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.coordinate()).isEqualTo(FieldCoordinates.coordinates("Query", "customersByKey"));
        assertThat(row.glue().methodName()).isEqualTo("customersByKeyCondition");
    }

    @Test
    void polymorphicRoot_expandsToOneRowPerParticipantTable() {
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type Query {
                occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
            }
            """);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // The expansion is the fact, not a special case: the coordinate's filters live per
        // participant, so the row count equals the participant count and the rows share the
        // coordinate while differing in resolved table, which is why the key needs no second
        // column. Participant rows disambiguate by minted method name, the same refs the
        // polymorphic branch folds derive at their call sites.
        assertThat(relation.rows()).hasSize(2);
        assertThat(relation.rows())
            .allMatch(r -> r.coordinate().equals(FieldCoordinates.coordinates("Query", "occupants")));
        assertThat(relation.rows()).extracting(r -> r.table().tableName())
            .containsExactlyInAnyOrder("customer", "staff");
        assertThat(relation.rows()).extracting(r -> r.glue().methodName())
            .containsExactlyInAnyOrder(
                "occupantsParticipant_CustomerCondition", "occupantsParticipant_StaffCondition");
    }

    /**
     * The two per-coordinate reads on the participant-expanded shape, which is the one that tells
     * them apart. {@code rowsFor} is the whole group, in producer order, and is what a consumer
     * folding every participant's glue target wants. {@code soleRowFor} refuses the group rather
     * than returning its first row: a consumer emitting one glue call has no way to serve two
     * participants, and returning either one would emit a call against whichever table the
     * producer happened to mint first.
     */
    @Test
    void participantExpandedCoordinate_readsAsAGroupAndRefusesTheSingleRowRead() {
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type Query {
                occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
            }
            """);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        assertThat(relation.rowsFor("Query", "occupants"))
            .containsExactlyElementsOf(relation.rows());
        assertThatThrownBy(() -> relation.soleRowFor("Query", "occupants"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.occupants")
            .hasMessageContaining("2 condition rows");
    }

    /**
     * The same two reads where the coordinate has one row, and at a coordinate with none. A
     * coordinate the relation does not cover reads as empty on both, which is the absence every
     * WHERE consumer composes the neutral condition from; it is not a rejection.
     */
    @Test
    void singleRowCoordinateReadsAsOneOnBothSurfacesAndAnUncoveredOneReadsAsEmpty() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argCondition"})): [Language!]!
                unfiltered: [Language!]!
            }
            """.formatted(STUB));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        assertThat(relation.rowsFor("Query", "languages")).hasSize(1);
        assertThat(relation.soleRowFor("Query", "languages"))
            .contains(relation.rows().getFirst());
        assertThat(relation.rowsFor("Query", "unfiltered")).isEmpty();
        assertThat(relation.soleRowFor("Query", "unfiltered")).isEmpty();
    }

    @Test
    void contextBoundCondition_producesAnEnvAppendingRow() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argConditionWithContext"}, contextArguments: ["tenantId"])): [Language!]!
            }
            """.formatted(STUB));

        // A context-reading binding flips the row onto the env-appending glue signature (the
        // producer-decided, row-grained fact both the renderer and every call site read); the
        // shape is accepted, not rejected. (The retired shim emitted a graphitronContext(env)
        // call its class never carried; the glue class owns its own helper.)
        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.readsRequestContext()).isTrue();
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("contextArguments"));
    }

    @Test
    void nestedAuthoredCondition_producesARowOnTheNestingCoordinate() {
        // A field nested inside a plain nesting type has no fieldsOf entry; the producer reaches
        // it through the NestingField's carried children, and the glue lands on the nesting
        // type's own conditions class. Only the authored arm is representable here (the
        // generated form stays a deferred rejection until nesting types become walkable).
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type FilmMeta {
                languages(name: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: name"}, override: true)):
                    [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") {
                meta: FilmMeta
            }
            type Query { films: [Film!]! }
            """.formatted(STUB));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.coordinate()).isEqualTo(FieldCoordinates.coordinates("FilmMeta", "languages"));
        assertThat(row.glue().owner().fqcn())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".conditions.FilmMetaConditions");
    }

    @Test
    void nestedGeneratedFilter_validatesCleanAndProducesTheNestedRow() {
        // A filterable arg on a field nested inside a plain-object nesting type classifies to a
        // GeneratedConditionFilter. This fixture pinned the deferred rejection while nested
        // coordinates had no walkable home; nesting types are projection units now, the nested
        // arm calls the glue this row renders, and the same fixture pins the emitted state.
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type FilmMeta {
                languages(name: String @field(name: "name")): [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") {
                meta: FilmMeta
            }
            type Query { films: [Film!]! }
            """);

        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("nested inside a plain-object nesting type"));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.coordinate()).isEqualTo(FieldCoordinates.coordinates("FilmMeta", "languages"));
        assertThat(row.glue().owner().fqcn())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".conditions.FilmMetaConditions");
    }

    @Test
    void lookupCoordinate_generatedFilterProducesARow() {
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customersByKey(
                    customer_id: [Int!]! @lookupKey,
                    email: String @field(name: "email")
                ): [Customer!]!
            }
            """);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // The generated twin of the authored case above: a lookup coordinate's non-key argument
        // mints an ordinary generated row, composed beside the VALUES join by the same glue call.
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.coordinate()).isEqualTo(FieldCoordinates.coordinates("Query", "customersByKey"));
        assertThat(row.glue().methodName()).isEqualTo("customersByKeyCondition");
    }

    @Test
    void nodeIdFilterCoordinate_producesARowThatDecodesNodeIds() {
        // An optional list filter field reaching a node type through a junction table: the row's
        // glue decodes the ids, which is the row-grained fact the recompile graph reads.
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            input ActorByFilmFilter {
                filmIds: [ID!] @nodeId(typeName: "Film") @reference(path: [
                    {key: "film_actor_actor_id_fkey"},
                    {key: "film_actor_film_id_fkey"}
                ])
            }
            type Query { actors(filter: ActorByFilmFilter): [Actor!]! }
            """);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(relation.rows()).hasSize(1);
        assertThat(relation.rows().get(0).decodesNodeId()).isTrue();
    }
}
