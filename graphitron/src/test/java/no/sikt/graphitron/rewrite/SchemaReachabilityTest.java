package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R279 slice 1 / slice 6 — the reachability observatory. Asserts the durable safety invariant
 * <strong>reachable ⊆ classified</strong> (every type the walk reaches is classified, the property
 * every later slice preserves) and, since slice 6, the converse for output composites:
 * <strong>every classified output composite is reachable</strong> (the orphan prune, now an
 * invariant rather than the slice-1 observation).
 *
 * <p>The fixture is built so each descent edge and each seed is exercised in isolation:
 * <ul>
 *   <li>{@code Actor} is reachable only as a union member,</li>
 *   <li>{@code FilmMedia} is reachable only through the interface → implementor fan-out,</li>
 *   <li>{@code City} is reachable only through the {@code @node} seed scan (no field returns it),</li>
 *   <li>the synthesised {@code @asConnection} types are reachable through the rebuilt carrier field,</li>
 *   <li>{@code OrphanCat} is reached by nothing, so slice 6 prunes it: it is not classified.</li>
 * </ul>
 */
@PipelineTier
class SchemaReachabilityTest {

    private static final String SDL = """
        type Query {
          film: Film
          search: FilmOrActor
          media: MediaItem
          films: [Film!]! @asConnection @defaultOrder(primaryKey: true)
        }
        type Film @table(name: "film") { title: String }
        type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
        union FilmOrActor = Film | Actor
        interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
        type FilmMedia implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
        type City implements Node @table(name: "city") @node(keyColumns: ["city_id"]) { id: ID! @nodeId }
        type OrphanCat @table(name: "category") { name: String @field(name: "NAME") }
        """;

    @Test
    void everyReachableTypeIsClassified() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var classified = bundle.model().types().keySet();

        var reachableTargets = reachableExcludingOperationRoots(bundle);

        assertThat(classified)
            .as("reachable ⊆ classified: every reachable target type must be classified")
            .containsAll(reachableTargets);
    }

    @Test
    void eachDescentEdgeAndSeedReachesItsType() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var reachable = SchemaReachability.reachableTypeNames(bundle.assembled());

        assertThat(reachable)
            .as("field edge, union members, interface fan-out, @node seed, and synthesised "
                + "connection types are all reached")
            .contains(
                "Film",                  // direct field + union + connection node
                "Actor",                 // union member only
                "FilmOrActor",           // union returned by a field
                "MediaItem",             // interface returned by a field
                "FilmMedia",             // interface → implementor fan-out only
                "City",                  // @node directive-scan seed only
                "QueryFilmsConnection",  // synthesised @asConnection carrier
                "QueryFilmsEdge",
                "PageInfo");
    }

    @Test
    void noClassifiedOutputCompositeIsUnreachable() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var reachable = SchemaReachability.reachableTypeNames(bundle.assembled());

        // R279 slice 6 — the orphan prune is now an invariant, not an observation: the field-first
        // walk is the sole classifier, so an output composite (object / interface / union) reached by
        // no field, union, interface, or seed is no longer classified. Restricted to output
        // composites because reachableTypeNames only reports those; input types / scalars / enums stay
        // classified through their own sweep and are out of this check's scope.
        var classifiedOutputComposites = bundle.model().types().keySet().stream()
            .filter(name -> bundle.assembled().getType(name) instanceof GraphQLObjectType
                || bundle.assembled().getType(name) instanceof GraphQLInterfaceType
                || bundle.assembled().getType(name) instanceof GraphQLUnionType)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertThat(reachable)
            .as("slice 6: every classified output composite is reachable (the prune is observable)")
            .containsAll(classifiedOutputComposites);
        assertThat(bundle.model().types())
            .as("the unreachable @table object is pruned, not classified")
            .doesNotContainKey("OrphanCat");
    }

    @Test
    void comparatorReportsNoDiffForEqualSnapshotsAndADiffForChangedOnes() {
        var registry = TestSchemaHelper.parseRegistryWithPrelude(SDL);
        var snapshot = CatalogBuilder.buildSnapshot(registry);

        assertThat(ProjectionSnapshotComparator.diff(snapshot, snapshot))
            .as("identical snapshots produce no differences")
            .isEmpty();

        var withExtraDirective = TestSchemaHelper.parseRegistryWithPrelude(
            SDL + "\ndirective @bisectAidProbe on FIELD_DEFINITION\n");
        var changed = CatalogBuilder.buildSnapshot(withExtraDirective);

        assertThat(ProjectionSnapshotComparator.diff(snapshot, changed))
            .as("a changed snapshot is localised by the bisect aid")
            .anyMatch(line -> line.contains("bisectAidProbe"));
    }

    private static Set<String> reachableExcludingOperationRoots(GraphitronSchemaBuilder.Bundle bundle) {
        var reachable = new LinkedHashSet<>(SchemaReachability.reachableTypeNames(bundle.assembled()));
        var schema = bundle.assembled();
        if (schema.getQueryType() != null) reachable.remove(schema.getQueryType().getName());
        if (schema.getMutationType() != null) reachable.remove(schema.getMutationType().getName());
        return reachable;
    }
}
