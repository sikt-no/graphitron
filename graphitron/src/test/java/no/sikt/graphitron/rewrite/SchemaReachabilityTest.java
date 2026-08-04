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
 * The reachability observatory. Asserts the durable safety invariant
 * <strong>reachable ⊆ classified</strong> (every type the walk reaches is classified, the invariant
 * the pipeline preserves throughout) and the converse for output composites:
 * <strong>every classified output composite is reachable</strong> (the orphan prune, now an
 * invariant rather than a mere observation).
 *
 * <p>The fixture is built so each descent edge and each seed is exercised in isolation:
 * <ul>
 *   <li>{@code Actor} is reachable only as a union member,</li>
 *   <li>{@code FilmMedia} is reachable only through the interface → implementor fan-out,</li>
 *   <li>{@code City} is reachable only through the {@code @node} seed scan (no field returns it),</li>
 *   <li>the synthesised {@code @asConnection} types are reachable through the rebuilt carrier field,</li>
 *   <li>{@code LeafProbeFilter} is reachable only through an argument edge, and
 *       {@code LeafProbeKind} only through that input's field edge,</li>
 *   <li>{@code OrphanCat}, {@code OrphanFilter}, {@code OrphanKind}, {@code OrphanStamp} and
 *       {@code OrphanSortHolder} are reached by nothing, so they are pruned: not classified.</li>
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
          leafProbe(filter: LeafProbeFilter): Film
        }
        type Film @table(name: "film") { title: String }
        type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
        union FilmOrActor = Film | Actor
        interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
        type FilmMedia implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
        type City implements Node @table(name: "city") @node(keyColumns: ["city_id"]) { id: ID! @nodeId }
        type OrphanCat @table(name: "category") { name: String @field(name: "NAME") }
        input LeafProbeFilter { title: String, kind: LeafProbeKind }
        enum LeafProbeKind { PROBE_A PROBE_B }
        input OrphanFilter { name: String }
        enum OrphanKind { ORPHAN_A }
        scalar OrphanStamp @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
        input OrphanSortHolder { dir: SortDirection }
        directive @survivorProbe(kind: SurvivorKind) on FIELD_DEFINITION
        enum SurvivorKind { PROBE }
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
        var reachable = SchemaReachability.reachableTypeNames(bundle.assembled(), TestSchemaHelper.nodeDeclaration());

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
        var reachable = SchemaReachability.reachableTypeNames(bundle.assembled(), TestSchemaHelper.nodeDeclaration());

        // The orphan prune is now an invariant, not an observation: the field-first
        // walk is the sole classifier, so an output composite (object / interface / union) reached by
        // no field, union, interface, or seed is no longer classified. Restricted to output
        // composites because reachableTypeNames only records those; leaves are classified by the
        // same walk but deliberately kept out of the observatory's recorded set (their prune is
        // pinned by walkClassifiesLeavesAndPrunesUnreachedOnes below).
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
    void walkClassifiesLeavesAndPrunesUnreachedOnes() {
        var bundle = TestSchemaHelper.buildBundle(SDL);
        var types = bundle.model().types();

        // The input surface is classified by the same walk: LeafProbeFilter is reached only
        // through Query.leafProbe's argument edge, LeafProbeKind only through that input's own
        // field edge. (The filter's `kind` field resolves to no film column, so the *field*
        // Query.leafProbe rejects; the leaf types' own verdicts are independent of that and are
        // what this test pins.)
        assertThat(types).containsKeys("LeafProbeFilter", "LeafProbeKind");

        // Declared but reached by no field or argument: pruned, exactly like OrphanCat above.
        // Before the walk owned the input surface these were classified by the pre-walk sweep
        // whether used or not.
        assertThat(types).doesNotContainKeys("OrphanFilter", "OrphanKind", "OrphanStamp");

        // The published-support-type sub-case: SortDirection is retained by the all-declared
        // retainedSupportTypes() scan (OrphanSortHolder.dir references it) yet reachable from no
        // coordinate, so it is pruned together with its unreachable holder. This is where a
        // prune-vs-retain mismatch would hide: retention feeds the verdict, reachability decides
        // membership in types().
        assertThat(types).doesNotContainKeys("OrphanSortHolder", "SortDirection");

        // A survivor directive definition (one the emitted schema re-declares, i.e. not a
        // graphitron build-time directive) seeds its argument types: the emitted schema's
        // directive declaration references them, so pruning would dangle a type reference
        // (federation__FieldSet on @key is the production case).
        assertThat(types).containsKey("SurvivorKind");
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
        var reachable = new LinkedHashSet<>(SchemaReachability.reachableTypeNames(bundle.assembled(), TestSchemaHelper.nodeDeclaration()));
        var schema = bundle.assembled();
        if (schema.getQueryType() != null) reachable.remove(schema.getQueryType().getName());
        if (schema.getMutationType() != null) reachable.remove(schema.getMutationType().getName());
        return reachable;
    }
}
