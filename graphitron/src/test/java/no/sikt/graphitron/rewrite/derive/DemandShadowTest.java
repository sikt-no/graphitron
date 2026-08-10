package no.sikt.graphitron.rewrite.derive;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.SchemaReachability;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedDsl;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_DEMAND_RULE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_EXEMPTION_RULE;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_TYPE_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow reader of the demand stratum, and the registered agreement anchor for
 * {@code intent_type_domain}, the demand and exemption rule views, and the resolved reductions
 * over them. The other side of the diff is {@link ClaimDomain}, the unreified demand relation
 * those relations exist to replace, with {@link DemandResidue} naming the populations the store
 * cannot express yet.
 *
 * <p>The rules state the intended model, not the walk's incidental holes, so the sweep is not a
 * plain equality: it asserts equality outside the named residues and pins each disagreement
 * direction against a store-derived population (never a Java-side coordinate list, per the
 * residue discipline the column-match sweep set). Demanded-but-unregistered rows may sit only
 * under DML mutation payloads (the DELETE carrier's verdict loss) and non-conventional root
 * bindings (the renamed-root hole); registered-but-undemanded rows only under the reflection
 * residue. The targeted fixtures then assert each pinned population non-empty on the schema
 * shapes that create it, so the pins cannot go vacuous.
 */
@PipelineTier
class DemandShadowTest {

    private static final String GRAPH = "DemandShadowTest";

    private static final Set<String> CONVENTIONAL_ROOTS = Set.of("Query", "Mutation", "Subscription");
    private static final Set<String> SPEC_BUILT_IN_SCALARS = Set.of("String", "Int", "Float", "Boolean", "ID");
    private static final Set<String> COMPOSITE_KINDS = Set.of("OBJECT", "INTERFACE", "UNION");
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
     * Per corpus example, captured as its own graph in one store: the domain equals the legacy
     * reachable set on the composite kinds (both sides post macro expansion, since capture
     * expands and the bundle's assembled schema is rebuilt; this equality is also the enforcer
     * for the node-inference seed's over-approximation, which must add nothing on the corpus);
     * the resolved field reduction covers every domain coordinate exactly once (the coverage
     * gate, so a rule-arm drift that opens a gap fails as a count rather than a silent drop);
     * the demanded set agrees with the walked registry outside the named residues, both
     * directions pinned; and the type grain agrees under the same discipline, with the
     * machinery and leaf populations carried as rows. The closed vocabularies are asserted over
     * everything the sweep produced.
     */
    @Test
    void demandShadowAgreesWithTheWalkedRegistriesOverTheCorpus() throws IOException {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var nodes = TestSchemaHelper.nodeDeclaration();
        int comparedFields = 0;
        var seenVerdicts = new LinkedHashSet<String>();
        var seenRules = new LinkedHashSet<String>();
        try (var store = GraphitronModelStore.open()) {
            for (ClassifiedCorpus.Example example : ClassifiedCorpus.examples()) {
                // The Relay Node interface is appended when absent so the captured document
                // matches the one the walk parses (TestSchemaHelper injects it there).
                String full = ClassifiedDsl.PRELUDE + "\n" + example.sdl();
                if (!full.contains("interface Node")) {
                    full += "\ninterface Node { id: ID! }\n";
                }
                Path dir = Files.createDirectories(tmp.resolve(example.id()));
                var registry = RewriteSchemaLoader.load(List.of(write(dir, full).toString()));
                FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(example.id(), dir),
                    registry, jooq, List.of(), nodes);

                var bundle = TestSchemaHelper.buildBundle(full);
                var legacy = ClaimDomain.of(bundle.model());
                var residue = DemandResidue.of(bundle.model());

                // 1. The domain, on the kinds the legacy reachable set records. The facet
                // shapes are subtracted as a named population: capture's connection expansion
                // records the @asFacet marker but does not synthesize the facet types yet, so
                // the walk's rebuilt schema reaches names the store cannot hold; the population
                // is the walk's own facet verdicts and closes when capture expands facets.
                var expectedReach = new LinkedHashSet<>(
                    SchemaReachability.reachableTypeNames(bundle.assembled(), nodes));
                expectedReach.removeAll(facetShapes(bundle.model()));
                assertThat(domainComposites(store.dsl(), example.id()))
                    .as("intent_type_domain vs SchemaReachability on composites (%s)", example.id())
                    .containsExactlyInAnyOrderElementsOf(expectedReach);

                // 2. The field grain: one resolved row per coordinate, covering the domain.
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

                // 3. The two disagreement directions, each pinned to its store-derived population.
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

                // 4. The type grain, same discipline; machinery and leaf populations are data.
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
                        // Spec built-ins are engine-provided: their verdicts are engine-owned,
                        // and one of their reaching edges (a built-in directive's argument,
                        // @include(if: Boolean!)) exists only in the assembled schema, never in
                        // the registry capture transcribes, so the domain cannot always hold
                        // them. A closed five-name population, acceptable as registered without
                        // a demand reading.
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
        withCapturedStore(sdl, dsl -> {
            assertThat(fetchResolvedField(dsl, "Feed", "tick"))
                .isEqualTo("DEMANDED:ROOT_OPERATION");
            var walked = TestSchemaHelper.buildSchema(sdl);
            assertThat(walked.fields().containsKey(FieldCoordinates.coordinates("Feed", "tick")))
                .as("the legacy walk never classifies the renamed root's fields")
                .isFalse();
            assertThat(pinnedExcessParents(dsl, GRAPH))
                .as("the non-conventional-root pin is derivable from the store and non-empty")
                .contains("Feed");
        });
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
        withCapturedStore(failing, dsl -> {
            assertThat(fetchResolvedField(dsl, "DeleteFilmPayload", "film"))
                .isEqualTo("DEMANDED:PRODUCER_PAYLOAD");
            var walked = TestSchemaHelper.buildSchema(failing);
            assertThat(walked.fields()
                    .containsKey(FieldCoordinates.coordinates("DeleteFilmPayload", "film")))
                .as("the walk loses the data field's verdict on the non-repaid path")
                .isFalse();
            assertThat(pinnedExcessParents(dsl, GRAPH))
                .as("the DML-payload pin is derivable from the store and non-empty")
                .contains("DeleteFilmPayload");
        });
        var succeeding = """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId }
            input FilmActorRef { id: ID! @nodeId }
            type DeletedPayload { deletedId: ID }
            type Query { x: String }
            type Mutation {
              deleteFilmActor(in: FilmActorRef!): DeletedPayload
                @mutation(typeName: DELETE, table: "film_actor")
            }
            """;
        withCapturedStore(succeeding, dsl -> {
            assertThat(fetchResolvedField(dsl, "DeletedPayload", "deletedId"))
                .isEqualTo("DEMANDED:PRODUCER_PAYLOAD");
            var walked = TestSchemaHelper.buildSchema(succeeding);
            assertThat(walked.fields()
                    .containsKey(FieldCoordinates.coordinates("DeletedPayload", "deletedId")))
                .as("the IdElement repayment registers the data field, so the sides agree")
                .isTrue();
        });
    }

    /**
     * The exemption arms stay unmasked: a structural connection type is also a directiveless
     * object, both rule rows survive, and the reduction picks the more specific reading. The
     * interface arm resolves the census's largest population as exempt rows.
     */
    @Test
    void overlappingExemptionReadingsSurviveInTheRulesAndResolveByPrecedence() {
        var sdl = """
            type Query { films: FilmConnection, media: MediaItem }
            type FilmConnection { edges: [FilmEdge], pageInfo: PageInfo }
            type FilmEdge { node: Film, cursor: String }
            type PageInfo { hasNextPage: Boolean! }
            interface MediaItem { title: String }
            type Film implements MediaItem @table(name: "film") { title: String }
            """;
        withCapturedStore(sdl, dsl -> {
            var reasons = dsl.select(INTENT_FIELD_EXEMPTION_RULE.REASON)
                .from(INTENT_FIELD_EXEMPTION_RULE)
                .where(INTENT_FIELD_EXEMPTION_RULE.GRAPH_NAME.eq(GRAPH))
                .and(INTENT_FIELD_EXEMPTION_RULE.TYPE_NAME.eq("FilmConnection"))
                .fetch(org.jooq.Record1::value1);
            assertThat(reasons)
                .as("both readings of the connection type survive unmasked")
                .containsExactlyInAnyOrder("CONNECTION_MACHINERY", "NESTING_TARGET");
            assertThat(fetchResolvedField(dsl, "FilmConnection", "edges"))
                .isEqualTo("EXEMPT:CONNECTION_MACHINERY");
            assertThat(fetchResolvedField(dsl, "FilmEdge", "node"))
                .isEqualTo("EXEMPT:CONNECTION_MACHINERY");
            assertThat(fetchResolvedField(dsl, "PageInfo", "hasNextPage"))
                .isEqualTo("EXEMPT:CONNECTION_MACHINERY");
            assertThat(fetchResolvedField(dsl, "MediaItem", "title"))
                .isEqualTo("EXEMPT:INTERFACE_TYPE");
            assertThat(fetchResolvedField(dsl, "Film", "title"))
                .isEqualTo("DEMANDED:TABLE_TYPE");
        });
    }

    /** The demand rules are total over the domain's plain objects: the catch-all complements them. */
    @Test
    void directivelessObjectResolvesThroughTheCatchAll() {
        var sdl = """
            type Query { film: Film }
            type Film @table(name: "film") { details: FilmDetails }
            type FilmDetails { note: String }
            """;
        withCapturedStore(sdl, dsl -> {
            assertThat(fetchResolvedField(dsl, "FilmDetails", "note"))
                .isEqualTo("EXEMPT:NESTING_TARGET");
            assertThat(dsl.fetchCount(INTENT_FIELD_DEMAND_RULE,
                INTENT_FIELD_DEMAND_RULE.GRAPH_NAME.eq(GRAPH)
                    .and(INTENT_FIELD_DEMAND_RULE.TYPE_NAME.eq("FilmDetails"))))
                .isZero();
        });
    }

    // ===== Helpers =====

    /** The domain's members restricted to the kinds the legacy reachable set records. */
    private static Set<String> domainComposites(DSLContext dsl, String graphName) {
        return new LinkedHashSet<>(dsl.select(INTENT_TYPE_DOMAIN.TYPE_NAME)
            .from(INTENT_TYPE_DOMAIN)
            .join(GRAPHQL_TYPE)
            .on(GRAPHQL_TYPE.GRAPH_NAME.eq(INTENT_TYPE_DOMAIN.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(INTENT_TYPE_DOMAIN.TYPE_NAME)))
            .where(INTENT_TYPE_DOMAIN.GRAPH_NAME.eq(graphName))
            .and(GRAPHQL_TYPE.KIND.in(COMPOSITE_KINDS))
            .fetch(org.jooq.Record1::value1));
    }

    /** The count the coverage gate compares against: the domain's field-bearing coordinates. */
    private static int domainFieldCoordinateCount(DSLContext dsl, String graphName) {
        return dsl.fetchCount(dsl.select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME)
            .from(GRAPHQL_FIELD)
            .join(INTENT_TYPE_DOMAIN)
            .on(INTENT_TYPE_DOMAIN.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
                .and(INTENT_TYPE_DOMAIN.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME)))
            .join(GRAPHQL_TYPE)
            .on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME)))
            .where(GRAPHQL_FIELD.GRAPH_NAME.eq(graphName))
            .and(GRAPHQL_TYPE.KIND.in(FIELD_BEARING_KINDS)));
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

    /** The facet half of the machinery: the types capture's expansion does not synthesize yet. */
    private static Set<String> facetShapes(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        var facets = new LinkedHashSet<String>();
        schema.types().forEach((name, verdict) -> {
            if (verdict instanceof GraphitronType.FacetsType
                || verdict instanceof GraphitronType.FacetValueType) {
                facets.add(name);
            }
        });
        return facets;
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

    private void withCapturedStore(String sdl, java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var registry = RewriteSchemaLoader.load(List.of(write(tmp, sdl).toString()));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp), registry,
                jooq, List.of(), TestSchemaHelper.nodeDeclaration());
            body.accept(store.dsl());
        }
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
