package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedDsl;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.INTENT_EXPANDED_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_EXPANDED_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_TYPE_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow reader of the demand stratum, and the registered agreement anchor for the demand and
 * exemption rule views and the resolved reductions over them. The other side of the diff is
 * {@link ClaimDomain}, the unreified demand relation those relations exist to replace, with
 * {@link DemandResidue} naming the populations the store cannot express yet.
 *
 * <p>{@code intent_type_domain} is no longer pinned here. Its seed rule was decided from what the
 * requirement says rather than from what the walk reached, so an equality against the walk's
 * reachable set would make the walk that relation's specification again; the relation's own anchor
 * is {@code no.sikt.graphitron.rewrite.capture.ClassificationDomainTest}, which states its
 * membership rather than diffing it. The domain still bounds what this sweep reads, the reductions
 * joining it, and the coverage gate below is where that shows.
 *
 * <p>The rules state the intended model, not the walk's incidental holes, so the sweep is not a
 * plain equality: it asserts equality outside the named residues and pins each disagreement
 * direction against a store-derived population (never a Java-side coordinate list, per the
 * residue discipline the column-match sweep set). Demanded-but-unregistered rows may sit only
 * under DML mutation payloads (the DELETE carrier's verdict loss) and non-conventional root
 * bindings (the renamed-root hole); registered-but-undemanded rows only under the reflection
 * residue. The targeted fixtures then assert each pinned population non-empty on the schema
 * shapes that create it, so the pins cannot go vacuous.
 *
 * <p>What the demand relations return given rows is not asked here. That is their own algebra,
 * their arms, their position masks, their structural recognitions and the precedence their
 * reductions apply, and it lives in the module whose DDL declares them, in
 * {@code no.sikt.graphitron.model.intent.DemandRuleTest}, against a store seeded row by row. Every
 * fixture below is a real capture of real SDL for the reason the split was worth making: what
 * stands here is that an author's schema reaches those relations in the shape the rules read, and
 * that where the answer differs from the walk's, the difference is one of the named populations
 * rather than a drift.
 */
@PipelineTier
class DemandShadowTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    private static final Set<String> CONVENTIONAL_ROOTS = Set.of("Query", "Mutation", "Subscription");
    private static final Set<String> SPEC_BUILT_IN_SCALARS = Set.of("String", "Int", "Float", "Boolean", "ID");
    private static final Set<String> FIELD_BEARING_KINDS = Set.of("OBJECT", "INTERFACE", "INPUT_OBJECT");
    private static final Set<String> DEMAND_RULES =
        Set.of("ROOT_OPERATION", "TABLE_TYPE", "ERROR_TYPE", "PRODUCER_PAYLOAD");
    private static final Set<String> FIELD_EXEMPTION_REASONS = Set.of(
        "INTERFACE_TYPE", "INPUT_TYPE", "UNDERSCORE_TYPE", "CONNECTION_MACHINERY", "NESTING_TARGET");
    private static final Set<String> TYPE_DEMAND_RULES = Set.of(
        "ROOT_OPERATION", "TABLE_TYPE", "ERROR_TYPE", "INTERFACE_TYPE", "UNION_TYPE",
        "CONNECTION_MACHINERY", "PRODUCER_PAYLOAD");
    private static final Set<String> TYPE_EXEMPTION_REASONS = Set.of("UNDERSCORE_TYPE", "LEAF_KIND_DEFERRED");

    @TempDir
    Path tmp;

    // ===== The corpus sweep =====

    /**
     * Per corpus example, captured as its own graph in one store: the resolved field reduction
     * covers every domain coordinate exactly once (the coverage gate, so a rule-arm drift that
     * opens a gap fails as a count rather than a silent drop); the demanded set agrees with the
     * walked registry outside the named residues, both directions pinned; and the type grain agrees
     * under the same discipline, with the machinery and leaf populations carried as rows. The closed
     * vocabularies are asserted over everything the sweep produced.
     */
    @Test
    void demandShadowAgreesWithTheWalkedRegistriesOverTheCorpus() {
        int comparedFields = 0;
        var seenVerdicts = new LinkedHashSet<String>();
        var seenRules = new LinkedHashSet<String>();
        try (var store = captureCorpus()) {
            for (CorpusDocuments.Document example : CorpusDocuments.documents()) {
                var bundle = TestSchemaHelper.buildBundle(preluded(example));
                var legacy = ClaimDomain.of(bundle.model());
                var residue = DemandResidue.of(bundle.model());

                // 1. The field grain: one resolved row per coordinate, covering the domain.
                var resolved = new LinkedHashMap<String, String>();
                store.dsl().selectFrom(INTENT_RESOLVED_FIELD_DEMAND)
                    .where(INTENT_RESOLVED_FIELD_DEMAND.GRAPH_NAME.eq(example.id()))
                    .forEach(row -> {
                        var previous = resolved.put(key(row.getTypeName(), row.getFieldName()),
                            row.getVerdict() + ":" + row.getRule());
                        assertThat(previous)
                            .as("one verdict per coordinate (%s.%s in %s)",
                                row.getTypeName(), row.getFieldName(), example.id())
                            .isNull();
                        seenVerdicts.add(row.getVerdict());
                        seenRules.add(row.getRule());
                    });
                assertThat(resolved)
                    .as("the coverage gate: every domain coordinate resolves (%s)", example.id())
                    .hasSize(domainFieldCoordinateCount(store.dsl(), example.id()));

                // 2. The two disagreement directions, each pinned to its store-derived population.
                var demanded = new LinkedHashSet<String>();
                resolved.forEach((coordinate, verdict) -> {
                    if (verdict.startsWith("DEMANDED:")) demanded.add(coordinate);
                });
                var registered = new LinkedHashSet<String>();
                legacy.fieldCoordinates().forEach(c ->
                    registered.add(key(c.getTypeName(), c.getFieldName())));

                var excessParents = pinnedExcessParents(store.dsl(), example.id());
                for (String coordinate : demanded) {
                    if (!registered.contains(coordinate)) {
                        assertThat(excessParents)
                            .as("demanded-but-unregistered only under the pinned holes (%s in %s)",
                                coordinate, example.id())
                            .contains(parentOf(coordinate));
                    }
                }
                for (String coordinate : registered) {
                    if (!demanded.contains(coordinate)) {
                        assertThat(residue.reflectionBound())
                            .as("registered-but-undemanded only under the reflection residue (%s in %s)",
                                coordinate, example.id())
                            .contains(parentOf(coordinate));
                    }
                }
                comparedFields += registered.size();

                // 3. The type grain, same discipline; machinery and leaf populations are data.
                var demandedTypes = new LinkedHashSet<String>();
                var exemptTypes = new LinkedHashSet<String>();
                store.dsl().selectFrom(INTENT_RESOLVED_TYPE_DEMAND)
                    .where(INTENT_RESOLVED_TYPE_DEMAND.GRAPH_NAME.eq(example.id()))
                    .forEach(row -> {
                        ("DEMANDED".equals(row.getVerdict()) ? demandedTypes : exemptTypes)
                            .add(row.getTypeName());
                        seenVerdicts.add(row.getVerdict());
                        seenRules.add(row.getRule());
                    });
                var machinery = machineryVerdicts(bundle.model());
                for (String typeName : demandedTypes) {
                    if (!legacy.typeNames().contains(typeName)) {
                        assertThat(excessParents)
                            .as("type demanded but unregistered only under the pinned holes (%s in %s)",
                                typeName, example.id())
                            .contains(typeName);
                    }
                }
                for (String typeName : legacy.typeNames()) {
                    if (demandedTypes.contains(typeName)) continue;
                    boolean accounted = exemptTypes.contains(typeName)
                        || residue.reflectionBound().contains(typeName)
                        || residue.embeddingDecided().contains(typeName)
                        || machinery.contains(typeName)
                        // Spec built-ins are engine-provided and their verdicts are engine-owned.
                        // The gatherer's traversal reads the assembled schema, so the edges that
                        // reach some of them (a built-in directive's argument, @include(if:
                        // Boolean!)) are followed; which of the five a given corpus example
                        // reaches is still the example's business. A closed five-name population,
                        // acceptable as registered without a demand reading.
                        || SPEC_BUILT_IN_SCALARS.contains(typeName);
                    assertThat(accounted)
                        .as("registered type %s in %s is demanded, exempt-as-data, residue, "
                            + "machinery or a spec built-in", typeName, example.id())
                        .isTrue();
                }
            }
        }
        assertThat(comparedFields)
            .as("the corpus registers coordinates, so the sweep pinned something")
            .isGreaterThan(100);
        assertThat(seenVerdicts).isSubsetOf(Set.of("DEMANDED", "EXEMPT"));
        var allRules = new LinkedHashSet<String>();
        allRules.addAll(DEMAND_RULES);
        allRules.addAll(FIELD_EXEMPTION_REASONS);
        allRules.addAll(TYPE_DEMAND_RULES);
        allRules.addAll(TYPE_EXEMPTION_REASONS);
        assertThat(seenRules)
            .as("the resolved rules stay inside the declared vocabularies")
            .isSubsetOf(allRules);
    }

    // ===== Targeted pins =====

    /**
     * The renamed-root hole made visible, and its pin non-empty: the demand relation keys roots
     * off the operation binding, today's walk off the literal names, so a renamed subscription
     * root's fields are demanded here and registered nowhere.
     */
    @Test
    void renamedSubscriptionRootFieldsAreDemandedWhereTheWalkSkipsThem() {
        var sdl = """
            schema { query: Query, subscription: Feed }
            type Query { ping: String }
            type Feed { tick: String }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, sdl, jooq())) {
            assertThat(fetchResolvedField(store.dsl(), "Feed", "tick"))
                .isEqualTo("DEMANDED:ROOT_OPERATION");
            var walked = TestSchemaHelper.buildSchema(sdl);
            assertThat(walked.fields().containsKey(FieldCoordinates.coordinates("Feed", "tick")))
                .as("the legacy walk never classifies the renamed root's fields")
                .isFalse();
            assertThat(pinnedExcessParents(store.dsl(), GRAPH))
                .as("the non-conventional-root pin is derivable from the store and non-empty")
                .contains("Feed");
        }
    }

    /**
     * The DELETE carrier's data field is demanded through the producer-payload arm in both
     * outcomes; the walk registers it only where the IdElement repayment fires. The failing
     * shape (a {@code @table}-element data field, rejected with no repayment) is the pinned
     * demanded-but-unregistered population; the succeeding shape agrees.
     */
    @Test
    void deleteCarrierDataFieldIsDemandedInBothRepaymentOutcomes() {
        var failing = """
            type Film @table(name: "film") { title: String }
            input FilmRef @table(name: "film") { filmId: ID! @field(name: "film_id") }
            type DeleteFilmPayload { film: Film }
            type Query { films: [Film] }
            type Mutation {
              deleteFilm(in: FilmRef!): DeleteFilmPayload @mutation(typeName: DELETE)
            }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, failing, jooq())) {
            assertThat(fetchResolvedField(store.dsl(), "DeleteFilmPayload", "film"))
                .isEqualTo("DEMANDED:PRODUCER_PAYLOAD");
            var walked = TestSchemaHelper.buildSchema(failing);
            assertThat(walked.fields()
                    .containsKey(FieldCoordinates.coordinates("DeleteFilmPayload", "film")))
                .as("the walk loses the data field's verdict on the non-repaid path")
                .isFalse();
            assertThat(pinnedExcessParents(store.dsl(), GRAPH))
                .as("the DML-payload pin is derivable from the store and non-empty")
                .contains("DeleteFilmPayload");
        }
        var succeeding = """
            interface Node { id: ID! }
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId }
            input FilmActorRef { id: ID! @nodeId }
            type DeletedPayload { deletedId: ID }
            type Query { x: String }
            type Mutation {
              deleteFilmActor(in: FilmActorRef!): DeletedPayload
                @mutation(typeName: DELETE, table: "film_actor")
            }
            """;
        try (var store = CapturedStore.ofCatalog(tmp, succeeding, jooq())) {
            assertThat(fetchResolvedField(store.dsl(), "DeletedPayload", "deletedId"))
                .isEqualTo("DEMANDED:PRODUCER_PAYLOAD");
            var walked = TestSchemaHelper.buildSchema(succeeding);
            assertThat(walked.fields()
                    .containsKey(FieldCoordinates.coordinates("DeletedPayload", "deletedId")))
                .as("the IdElement repayment registers the data field, so the sides agree")
                .isTrue();
        }
    }

    // ===== Helpers =====

    /** The count the coverage gate compares against: the domain's field-bearing coordinates. */
    private static int domainFieldCoordinateCount(DSLContext dsl, String graphName) {
        return dsl.fetchCount(dsl
            .select(INTENT_EXPANDED_FIELD.TYPE_NAME, INTENT_EXPANDED_FIELD.FIELD_NAME)
            .from(INTENT_EXPANDED_FIELD)
            .join(INTENT_TYPE_DOMAIN)
            .on(INTENT_TYPE_DOMAIN.GRAPH_NAME.eq(INTENT_EXPANDED_FIELD.GRAPH_NAME)
                .and(INTENT_TYPE_DOMAIN.TYPE_NAME.eq(INTENT_EXPANDED_FIELD.TYPE_NAME)))
            .join(INTENT_EXPANDED_TYPE)
            .on(INTENT_EXPANDED_TYPE.GRAPH_NAME.eq(INTENT_EXPANDED_FIELD.GRAPH_NAME)
                .and(INTENT_EXPANDED_TYPE.TYPE_NAME.eq(INTENT_EXPANDED_FIELD.TYPE_NAME)))
            .where(INTENT_EXPANDED_FIELD.GRAPH_NAME.eq(graphName))
            .and(INTENT_EXPANDED_TYPE.KIND.in(FIELD_BEARING_KINDS)));
    }

    /**
     * The store-derived pin for the demanded-but-unregistered direction: DML mutation payload
     * types (the DELETE carrier's verdict loss) and non-conventional root bindings (the
     * renamed-root hole). Derived from the base relations, never a Java coordinate list, so a
     * third instance of the same hole class fails the sweep instead of hiding.
     */
    private static Set<String> pinnedExcessParents(DSLContext dsl, String graphName) {
        var parents = new LinkedHashSet<String>(dsl.select(GRAPHQL_FIELD.NAMED_TYPE)
            .from(GRAPHITRON_MUTATION)
            .join(GRAPHQL_FIELD)
            .on(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPHITRON_MUTATION.GRAPH_NAME)
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(GRAPHITRON_MUTATION.TYPE_NAME))
                .and(GRAPHQL_FIELD.FIELD_NAME.eq(GRAPHITRON_MUTATION.FIELD_NAME)))
            .where(GRAPHITRON_MUTATION.GRAPH_NAME.eq(graphName))
            .fetch(org.jooq.Record1::value1));
        parents.addAll(dsl.select(GRAPHQL_ROOT_OPERATION.TYPE_NAME)
            .from(GRAPHQL_ROOT_OPERATION)
            .where(GRAPHQL_ROOT_OPERATION.GRAPH_NAME.eq(graphName))
            .and(GRAPHQL_ROOT_OPERATION.TYPE_NAME.notIn(CONVENTIONAL_ROOTS))
            .fetch(org.jooq.Record1::value1));
        return parents;
    }

    /**
     * The connection machinery's walk-side verdicts: types the promotion registers, which the
     * type-grain agreement accepts as demanded (the SDL-declared and captured-synthesized
     * shapes) or as machinery the store's recognition does not name yet (the facet shapes).
     */
    private static Set<String> machineryVerdicts(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        var machinery = new LinkedHashSet<String>();
        schema.types().forEach((name, verdict) -> {
            if (verdict instanceof GraphitronType.ConnectionType
                || verdict instanceof GraphitronType.EdgeType
                || verdict instanceof GraphitronType.PageInfoType
                || verdict instanceof GraphitronType.FacetsType
                || verdict instanceof GraphitronType.FacetValueType) {
                machinery.add(name);
            }
        });
        return machinery;
    }

    private static String fetchResolvedField(DSLContext dsl, String typeName, String fieldName) {
        return dsl.select(INTENT_RESOLVED_FIELD_DEMAND.VERDICT, INTENT_RESOLVED_FIELD_DEMAND.RULE)
            .from(INTENT_RESOLVED_FIELD_DEMAND)
            .where(INTENT_RESOLVED_FIELD_DEMAND.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_RESOLVED_FIELD_DEMAND.TYPE_NAME.eq(typeName))
            .and(INTENT_RESOLVED_FIELD_DEMAND.FIELD_NAME.eq(fieldName))
            .fetchOne(r -> r.value1() + ":" + r.value2());
    }

    private static String key(String typeName, String fieldName) {
        return typeName + "." + fieldName;
    }

    private static String parentOf(String coordinate) {
        return coordinate.substring(0, coordinate.indexOf('.'));
    }

    /**
     * Every corpus example captured as its own graph into one store, which is what lets the sweep
     * read the partition dimension as part of what it asserts. The catalog carries node inference
     * with it, production's arrangement and the one whose over-approximation the domain equality
     * above is the enforcer for.
     */
    private CapturedStore captureCorpus() {
        var jooq = jooq();
        CapturedStore store = null;
        for (CorpusDocuments.Document example : CorpusDocuments.documents()) {
            store = store == null
                ? CapturedStore.ofCatalog(tmp, example.id(), preluded(example), jooq)
                : store.andCatalogGraph(example.id(), preluded(example), jooq);
        }
        return store;
    }

    /**
     * A corpus example's SDL as the walk sees it. The Relay Node interface is appended when absent
     * so the captured document matches the one the walk parses, {@link TestSchemaHelper} injecting
     * it there.
     */
    private static String preluded(CorpusDocuments.Document example) {
        String full = CorpusDocuments.prelude() + "\n" + example.sdl();
        return full.contains("interface Node") ? full : full + "\ninterface Node { id: ID! }\n";
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
