package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconstruction key {@code leaf = f(source, delivery, target)}, emitted as a derived table
 * beside {@link LeafRatchetTest}: one grain triple per surviving output leaf, declared where a
 * reviewer can diff it, with a collision check so the acceptance "no leaf pair differs on the
 * operation axis alone" is enforced rather than asserted in prose. Two leaves inside one
 * hierarchy sharing a triple is a reconstruction-key violation unless the pair is listed in
 * {@link #TOLERATED_COLLISIONS} with its grounds and owner; an unnamed collision fails.
 *
 * <p>The target term reads at classified-target-<em>type</em> granularity where the shape alone
 * would blur real distinctions: the pivot projection (a context-free nesting type filled by a
 * correlated aggregate) and a {@code @service} result record are both record-shaped targets,
 * but their target types classify differently, so the triples stay distinct without smuggling
 * an operation term back in.
 *
 * <p>Totality is pinned against {@link GeneratorCoverageTest#sealedLeaves}, the same counting
 * rule the leaf ratchet uses, so this table cannot silently lag the sealed hierarchies: a new
 * leaf fails here until it declares its triple, which is the moment to ask whether the
 * distinction it encodes is source, delivery or target grain, or an operation term that
 * belongs on a member row.
 */
@UnitTier
class LeafReconstructionKeyTest {

    private static String triple(String source, String delivery, String target) {
        return source + " | " + delivery + " | " + target;
    }

    /** Query roots: placement is the query root; the source term is what the FROM starts at. */
    private static final Map<Class<?>, String> QUERY_TRIPLES = Map.ofEntries(
        Map.entry(QueryField.QueryTableField.class,
            triple("tableExpr component (catalog table | routine chain)", "root", "table")),
        Map.entry(QueryField.QueryNodeField.class,
            triple("node-id argument", "root", "node interface, single")),
        Map.entry(QueryField.QueryNodesField.class,
            triple("node-id argument", "root", "node interface, list")),
        Map.entry(QueryField.QueryTableInterfaceField.class,
            triple("catalog table", "root", "single-table discriminated interface")),
        Map.entry(QueryField.QueryInterfaceField.class,
            triple("participant tables", "root", "multi-table interface")),
        Map.entry(QueryField.QueryUnionField.class,
            triple("participant tables", "root", "union")),
        Map.entry(QueryField.QueryServiceTableField.class,
            triple("service call", "root", "table")),
        Map.entry(QueryField.QueryServiceRecordField.class,
            triple("service call", "root", "result record")),
        Map.entry(QueryField.QueryServicePolymorphicField.class,
            triple("service call", "root", "multi-table interface")),
        Map.entry(QueryField.QueryServiceTableInterfaceField.class,
            triple("service call", "root", "single-table discriminated interface")));

    /** Mutation roots: placement is the mutation root; write-ness derives from placement. */
    private static final Map<Class<?>, String> MUTATION_TRIPLES = Map.ofEntries(
        Map.entry(MutationField.DmlTableField.class,
            triple("table input args (the carried write arm)", "root", "DML return expression")),
        Map.entry(MutationField.MutationRoutineWriteField.class,
            triple("routine chain", "root", "table (post-commit terminus)")),
        Map.entry(MutationField.MutationRoutineWriteRecordField.class,
            triple("routine call", "root", "payload record")),
        Map.entry(MutationField.MutationServiceTableField.class,
            triple("service call", "root", "table")),
        Map.entry(MutationField.MutationServiceRecordField.class,
            triple("service call", "root", "result record")),
        Map.entry(MutationField.MutationServicePolymorphicField.class,
            triple("service call", "root", "multi-table interface")),
        Map.entry(MutationField.MutationServiceTableInterfaceField.class,
            triple("service call", "root", "single-table discriminated interface")),
        Map.entry(MutationField.MutationDmlRecordField.class,
            triple("table input arg (the carried write arm)", "root", "payload record")),
        Map.entry(MutationField.MutationBulkDmlRecordField.class,
            triple("table input arg (the carried write arm)", "root", "payload record")));

    /** Child fields: the source term is what arrives at the fetcher plus what it navigates to. */
    private static final Map<Class<?>, String> CHILD_TRIPLES = Map.ofEntries(
        Map.entry(ChildField.ColumnBackedField.class,
            triple("parent table row", "inline", "column")),
        Map.entry(ChildField.ColumnBackedReferenceField.class,
            triple("referenced table row (join path)", "inline", "column")),
        Map.entry(ChildField.ParticipantColumnReferenceField.class,
            triple("participant table row (join path)", "inline", "column")),
        Map.entry(ChildField.TableField.class,
            triple("target table (join path)", "inline", "table")),
        Map.entry(ChildField.BatchedTableField.class,
            triple("target table (join path)", "batched", "table")),
        Map.entry(ChildField.TableInterfaceField.class,
            triple("target table (join path)", "inline", "single-table discriminated interface")),
        Map.entry(ChildField.BatchedTableInterfaceField.class,
            triple("target table (join path)", "batched", "single-table discriminated interface")),
        Map.entry(ChildField.InterfaceField.class,
            triple("participant tables", "inline", "multi-table interface")),
        Map.entry(ChildField.UnionField.class,
            triple("participant tables", "inline", "union")),
        Map.entry(ChildField.BatchedInterfaceField.class,
            triple("participant tables", "batched", "multi-table interface")),
        Map.entry(ChildField.BatchedUnionField.class,
            triple("participant tables", "batched", "union")),
        Map.entry(ChildField.NestingField.class,
            triple("parent record, handed through", "inline", "nesting fragment of the parent projection")),
        Map.entry(ChildField.PivotField.class,
            triple("attribute table (single FK hop)", "inline", "pivot projection (context-free nesting type)")),
        Map.entry(ChildField.BatchedPivotField.class,
            triple("attribute table (single FK hop)", "batched", "pivot projection (context-free nesting type)")),
        Map.entry(ChildField.PivotSlotField.class,
            triple("pivot record, read by name", "inline", "projection slot")),
        Map.entry(ChildField.ServiceTableField.class,
            triple("service call", "batched", "table")),
        Map.entry(ChildField.ServiceRecordField.class,
            triple("service call", "batched", "result record")),
        Map.entry(ChildField.RecordReadField.class,
            triple("parent record property", "inline", "value")),
        Map.entry(ChildField.RecordCompositeField.class,
            triple("parent record composite accessor", "inline", "composite record")),
        Map.entry(ChildField.ComputedField.class,
            triple("parent table row through a helper method", "inline", "computed column")),
        Map.entry(ChildField.SingleRecordIdField.class,
            triple("produced record's key columns", "inline", "encoded node id")),
        Map.entry(ChildField.SingleRecordIdFieldFromReturning.class,
            triple("DML RETURNING keys", "inline", "encoded node id")),
        Map.entry(ChildField.ErrorsField.class,
            triple("error transport (payload accessor | local context)", "inline", "errors list")));

    /**
     * Collisions the key tolerates, each with its owner. The single and bulk DML record
     * carriers share a triple deliberately: the pair differs on input cardinality, an
     * input-side axis outside {@code (source, delivery, target)}, owned by the input model's
     * own re-grain rather than this programme (the slice record for the DML verb fold binds
     * that decision).
     */
    private static final Set<Set<Class<?>>> TOLERATED_COLLISIONS = Set.of(
        Set.of(MutationField.MutationDmlRecordField.class, MutationField.MutationBulkDmlRecordField.class));

    @Test
    void everySurvivingLeafDeclaresItsGrainTriple() {
        assertThat(QUERY_TRIPLES.keySet())
            .as("query triples cover exactly the sealed QueryField leaves")
            .containsExactlyInAnyOrderElementsOf(GeneratorCoverageTest.sealedLeaves(QueryField.class));
        assertThat(MUTATION_TRIPLES.keySet())
            .as("mutation triples cover exactly the sealed MutationField leaves")
            .containsExactlyInAnyOrderElementsOf(GeneratorCoverageTest.sealedLeaves(MutationField.class));
        assertThat(CHILD_TRIPLES.keySet())
            .as("child triples cover exactly the sealed ChildField leaves")
            .containsExactlyInAnyOrderElementsOf(GeneratorCoverageTest.sealedLeaves(ChildField.class));
    }

    @Test
    void noUnnamedTripleCollisionWithinAHierarchy() {
        for (var hierarchy : List.of(QUERY_TRIPLES, MUTATION_TRIPLES, CHILD_TRIPLES)) {
            var byTriple = new LinkedHashMap<String, List<Class<?>>>();
            hierarchy.forEach((leaf, t) ->
                byTriple.computeIfAbsent(t, k -> new java.util.ArrayList<>()).add(leaf));
            byTriple.forEach((t, leaves) -> {
                if (leaves.size() > 1) {
                    assertThat(TOLERATED_COLLISIONS)
                        .as("leaves sharing the triple '" + t + "': " + leaves
                            + "; a shared triple means the pair differs on no source, delivery or "
                            + "target grain, so either a grain distinction is missing from the "
                            + "table or the pair encodes an operation term the member relation "
                            + "should carry. Name it in TOLERATED_COLLISIONS only with grounds "
                            + "and an owner.")
                        .contains(Set.copyOf(leaves));
                }
            });
        }
    }

    /** The tolerated list cannot outlive its collisions: every entry must still collide. */
    @Test
    void toleratedCollisionsStillCollide() {
        for (var pair : TOLERATED_COLLISIONS) {
            var triples = pair.stream()
                .map(c -> QUERY_TRIPLES.containsKey(c) ? QUERY_TRIPLES.get(c)
                    : MUTATION_TRIPLES.containsKey(c) ? MUTATION_TRIPLES.get(c)
                    : CHILD_TRIPLES.get(c))
                .distinct()
                .toList();
            assertThat(triples)
                .as("a tolerated collision whose members no longer share a triple is stale: " + pair)
                .hasSize(1);
        }
    }

    /** The table's classes are all output leaves; a typo cannot smuggle in a non-leaf. */
    @Test
    void everyTableEntryIsAnOutputLeaf() {
        for (var hierarchy : List.of(QUERY_TRIPLES, MUTATION_TRIPLES, CHILD_TRIPLES)) {
            for (var leaf : hierarchy.keySet()) {
                assertThat(OutputField.class).isAssignableFrom(leaf);
            }
        }
    }
}
