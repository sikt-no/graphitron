package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.AuthoredMethodRef;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.OuterLift;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.PresenceGuard;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pipeline-tier assertions on the condition command relation over classified fixture schemas:
 * the produced rows, their keys, and the producer-decided data (glue refs, locals, lifts, facet
 * fragments, the env-appending fact). Renderer behaviour is covered by
 * {@code ConditionGluePipelineTest} and the execution tier; this class pins what the producer
 * mints, so it asserts on rows, never on emitted code. The relation's membership enforcer
 * (relation key-set equals the schema-derived covered set) lives in
 * {@code ConditionMembershipTest}.
 */
@PipelineTier
class ConditionCommandsPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    /**
     * The {@code nodeidfixture} catalog, where {@code bar} carries a single-column FK to {@code baz}:
     * the FK-target shape an authored {@code @condition} reaches through a correlated EXISTS.
     */
    private static final RewriteContext NODE_ID_FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), "ConditionCommandsPipelineTest", Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, "no.sikt.graphitron.rewrite.nodeidfixture");

    @Test
    void filteredRootCoordinate_producesOneRowCarryingBothArms() {
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

        // Conjunct order is today's fold order: the generated predicate first, then authored.
        assertThat(row.predicates()).hasSize(2);
        var generated = (Predicate.Generated) row.predicates().get(0);
        assertThat(generated.terms()).hasSize(1);
        var term = generated.terms().get(0);
        assertThat(term.match()).isEqualTo(MatchKind.EQUALITY);
        assertThat(term.columns()).hasSize(1);
        assertThat(term.reach().hops()).isEmpty();
        assertThat(term.binding().localName()).isEqualTo("cityNames");
        var authored = (Predicate.Authored) row.predicates().get(1);
        assertThat(authored.method().methodName()).isEqualTo("argCondition");
        assertThat(authored.reach().hops()).isEmpty();
        assertThat(authored.bindings()).extracting(b -> b.localName()).containsExactly("cityNames");
        // Same-table: the author's return value reaches the WHERE clause with nothing of ours
        // between, so the call always fires and their null-mapping convention owns the semantics.
        assertThat(authored.presence()).isEqualTo(PresenceGuard.always());

        // Glue is total and minted from the naming vocabulary; the relation's landing addresses
        // are the distinct glue owners.
        assertThat(row.glue().owner().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".conditions.QueryConditions");
        assertThat(row.glue().methodName()).isEqualTo("languagesCondition");
        assertThat(row.readsRequestContext()).isFalse();
        assertThat(relation.units()).containsExactly(row.glue().owner());
    }

    @Test
    void facetedCoordinate_fragmentsAreMaskedPredicateListsAndTheOuterLifts() {
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

        // Three nested bindings share the `filter` outer, so the row lifts it once.
        assertThat(row.lifts()).containsExactly(new OuterLift("filter", "filterMap"));

        // The fragment set is data: one base plus one per facet, methods minted from the naming
        // vocabulary; the base keeps exactly the non-facet term, each per-facet fragment exactly
        // its own (the partition the row's constructor enforces).
        assertThat(row.facets()).extracting(f -> f.method().methodName()).containsExactly(
            "filmsFacetBaseCondition", "filmsFacet_titleCondition", "filmsFacet_lengthCondition");
        var base = row.facets().get(0);
        assertThat(generatedTermLocals(base.predicates())).containsExactly("releaseYear");
        // One retained binding: no lift in the base fragment.
        assertThat(base.lifts()).isEmpty();
        assertThat(generatedTermLocals(row.facets().get(1).predicates())).containsExactly("title");
        assertThat(generatedTermLocals(row.facets().get(2).predicates())).containsExactly("length");
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
        assertThat(row.predicates()).hasSize(1);
        assertThat(row.predicates().get(0)).isInstanceOf(Predicate.Authored.class);
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
        // The generated term and the authored call share the cityNames local (one local, one
        // value); the trailing context param rides the authored call's bindings.
        assertThat(row.bindings()).extracting(b -> b.localName())
            .containsExactly("cityNames", "cityNames", "tenantId");
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
        assertThat(row.predicates()).hasSize(1);
        assertThat(row.predicates().get(0)).isInstanceOf(Predicate.Authored.class);
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
        assertThat(row.predicates()).hasSize(1);
        assertThat(row.predicates().get(0)).isInstanceOf(Predicate.Generated.class);
    }

    @Test
    void lookupCoordinate_generatedFilterProducesARowThatNeverRestatesTheKey() {
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
        assertThat(row.predicates()).hasSize(1);
        assertThat(row.predicates().get(0)).isInstanceOf(Predicate.Generated.class);

        // The key-not-restated fact, asserted at the model rather than by counting placeholders in
        // a rendered statement: the lookup key rides the VALUES join and is excluded from the
        // generated filter upstream, so no term of this row addresses customer_id. Were the
        // exclusion to lapse, the key would be predicated twice — once in the join, once here.
        assertThat(row.predicates().stream()
                .filter(p -> p instanceof Predicate.Generated)
                .flatMap(p -> ((Predicate.Generated) p).terms().stream())
                .flatMap(t -> t.columns().stream())
                .map(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName))
            .containsExactly("email");
    }

    // ===== Presence gating: when a conjunct is contributed at all =====
    //
    // The rule both arms follow is that an absent value contributes no conjunct. On the generated
    // arm it is per-term data the renderer spells as a null/empty guard. On the authored arm it is
    // per-predicate data, because what needs gating there is not the developer's method but the
    // correlated EXISTS graphitron wraps around it: that wrapper is a semi-join, so firing it for a
    // value nobody supplied silently drops every row with no far-side relation, and the author
    // cannot neutralise it from inside their method. The two arms are pinned from both sides here,
    // as data, which is the boundary that decides where the guard lands.

    @Test
    void junctionFilterCoordinate_generatedTermCarriesThePresenceGatingFact() {
        // The reported shape: an optional list filter field reaching a node type through a junction
        // table. The row's own data says how the guard is spelled — the binding is list-shaped and
        // the term is not proven non-null — which is what makes the renderer's null-and-non-empty
        // guard a derivation rather than a decision.
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
        var generated = (Predicate.Generated) relation.rows().get(0).predicates().get(0);
        assertThat(generated.terms()).hasSize(1);
        var term = generated.terms().get(0);

        assertThat(term.reach().hops())
            .as("the reach is what makes the conjunct a semi-join rather than a column comparison")
            .hasSize(2);
        assertThat(term.nonNull())
            .as("an optional field is not proven non-null, so the guard is not elided")
            .isFalse();
        assertThat(term.binding().param().list())
            .as("list-shaped, so an empty list is guarded alongside an absent value")
            .isTrue();
    }

    @Test
    void fkTargetAuthoredPredicate_carriesTheOwningFieldsWireAddressAsItsGuard() {
        // The authored twin. The condition method is reached through a correlated EXISTS over the
        // FK, so the row carries the address of the field that owns that wrapper: the argument it
        // roots at, the path down to the leaf, and its shape. The grain is the field, never the
        // method's parameter list, so a method that ignores its value guards exactly like one that
        // binds it.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            input BarFilter {
                bazId: ID! @nodeId(typeName: "Baz")
                    @condition(condition: {className: "%s", method: "argConditionTypeUnique"}, override: true)
            }
            type Query { bars(filter: BarFilter): [Bar!]! }
            """.formatted(STUB), NODE_ID_FIXTURE_CTX);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(relation.rows()).hasSize(1);
        var authored = (Predicate.Authored) relation.rows().get(0).predicates().get(0);

        assertThat(authored.reach().hops()).hasSize(1);
        assertThat(authored.presence())
            .isEqualTo(new PresenceGuard.FieldPresent("filter", List.of("bazId"), false));
    }

    @Test
    void authoredReachWithoutAGuard_isUnconstructable() {
        // The invariant that makes the boundary structural rather than a convention a producer has
        // to remember: a wrapper of ours cannot exist with nothing gating it.
        var actorId = TestFixtures.col("actor_id", "ACTOR_ID", "java.lang.Integer");
        var filmActor = TestFixtures.tableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of(actorId));
        var actor = TestFixtures.tableRef("actor", "ACTOR", "Actor", List.of(actorId));
        var reach = new ReachPath(List.of(TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_actor_actor_id_fkey"),
            filmActor, List.of(actorId), actor, List.of(actorId), null, "actors_0")));

        assertThatThrownBy(() -> new Predicate.Authored(
                new AuthoredMethodRef(STUB, "argConditionTypeUnique"), List.of(),
                reach, PresenceGuard.always()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no field-presence guard");
    }

    private static List<String> generatedTermLocals(List<Predicate> predicates) {
        return predicates.stream()
            .filter(p -> p instanceof Predicate.Generated)
            .flatMap(p -> ((Predicate.Generated) p).terms().stream())
            .map(ColumnTerm::binding)
            .map(b -> b.localName())
            .toList();
    }
}
