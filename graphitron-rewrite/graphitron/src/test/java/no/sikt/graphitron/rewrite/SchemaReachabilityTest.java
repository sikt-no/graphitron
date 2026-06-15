package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R279 slice 1 — the reachability observatory. Asserts the durable safety invariant
 * <strong>reachable ⊆ classified</strong> (every type the walk reaches is classified, the property
 * every later slice must preserve) and separately <em>measures</em> the classified-but-unreachable
 * orphan set as the inventory slice 6 will prune. The orphan measurement is an observation, not a
 * correctness invariant: slice 6 flips it into "the orphan set is empty / rejected".
 *
 * <p>The fixture is built so each descent edge and each seed is exercised in isolation:
 * <ul>
 *   <li>{@code Actor} is reachable only as a union member,</li>
 *   <li>{@code FilmMedia} is reachable only through the interface → implementor fan-out,</li>
 *   <li>{@code City} is reachable only through the {@code @node} seed scan (no field returns it),</li>
 *   <li>the synthesised {@code @asConnection} types are reachable through the rebuilt carrier field,</li>
 *   <li>{@code OrphanCat} is classified but reached by nothing, so it lands in the orphan set.</li>
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
    void unreachableClassifiedTypeIsMeasuredAsAnOrphan() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var reachable = SchemaReachability.reachableTypeNames(bundle.assembled());

        var orphans = new LinkedHashSet<>(bundle.model().types().keySet());
        orphans.removeAll(reachable);

        // Observation, not an invariant: OrphanCat is classified (@table) but reached by no field,
        // union, interface, or seed. Slice 6 will turn this into "the orphan set is empty".
        assertThat(orphans)
            .as("a classified-but-unreachable type is detected as an orphan")
            .contains("OrphanCat");
        assertThat(orphans)
            .as("types reached through a real edge or seed are not mistaken for orphans")
            .doesNotContain("Film", "Actor", "FilmMedia", "City", "PageInfo");
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
