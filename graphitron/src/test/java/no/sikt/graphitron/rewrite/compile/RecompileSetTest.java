package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 3 — unit coverage of the recompile-set algorithm over hand-built graphs, plus the
 * end-to-end tie between {@link AbiSignature} and {@link RecompileSet}. Covers the spec's three
 * required cases: a body-only edit does not propagate, an ABI edit propagates one hop and
 * transitively, and a {@code static final} constant-value edit propagates (its hash moves, so it is
 * treated as an ABI edit).
 *
 * <p>Edge direction convention (matching {@link MapCompileDependencyGraph.Accumulator#addEdge}):
 * {@code addEdge(from, to)} means "from references to", so {@code directDependents(to)} contains
 * {@code from}. A recompile propagates from an edited unit to the units that reference it.
 */
@UnitTier
class RecompileSetTest {

    private static CompileDependencyGraph graph(String... edges) {
        var acc = new MapCompileDependencyGraph.Accumulator();
        for (String edge : edges) {
            String[] parts = edge.split("->");
            acc.addEdge(parts[0], parts[1]);
        }
        return acc.build();
    }

    @Test
    void bodyOnlyEditPrunesDependents() {
        var g = graph("B->A"); // B references A
        // A changed content but its ABI did not move: no propagation to B.
        var recompile = RecompileSet.compute(g, Set.of("A"), Set.of());

        assertThat(recompile).containsExactly("A");
    }

    @Test
    void abiEditIncludesOneHopDependent() {
        var g = graph("B->A");
        var recompile = RecompileSet.compute(g, Set.of("A"), Set.of("A"));

        assertThat(recompile).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void abiEditIncludesTransitiveDependents() {
        var g = graph("C->B", "B->A"); // C references B references A
        var recompile = RecompileSet.compute(g, Set.of("A"), Set.of("A"));

        assertThat(recompile).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void nonAbiDeltaMembersDoNotSeedPropagation() {
        var g = graph("B->A", "Y->X");
        // Both A and X changed content; only A's ABI moved. X's dependent Y stays out.
        var recompile = RecompileSet.compute(g, Set.of("A", "X"), Set.of("A"));

        assertThat(recompile).containsExactlyInAnyOrder("A", "X", "B");
    }

    @Test
    void abiChangedIsTheHashDiffRestrictedToTheDelta() {
        var previous = Map.of("A", "h1", "B", "h2");
        var current = Map.of("A", "h1", "B", "h3"); // only B moved
        // The delta is what the writer reported changed; A is not in it even though we hold its hash.
        var changed = RecompileSet.abiChanged(Set.of("B"), previous, current);

        assertThat(changed).containsExactly("B");
    }

    @Test
    void unchangedHashYieldsNoAbiChange() {
        var previous = Map.of("A", "h1");
        var current = Map.of("A", "h1");
        var changed = RecompileSet.abiChanged(Set.of("A"), previous, current);

        assertThat(changed).isEmpty();
    }

    @Test
    void newlyEmittedUnitCountsAsAbiChanged() {
        var changed = RecompileSet.abiChanged(Set.of("N"), Map.of(), Map.of("N", "h1"));

        assertThat(changed).containsExactly("N");
    }

    @Test
    void constantValueEditPropagatesThroughGraphButBodyEditDoesNot() {
        String widget = "com.x.Widget";
        String caller = "com.x.Caller";
        var g = graph(caller + "->" + widget); // Caller references Widget

        String v1 = AbiSignature.hash(widget(1, "body-a"));
        var previous = Map.of(widget, v1);

        // Constant value 1 -> 2 moves the ABI hash: Caller must recompile (javac inlined the constant).
        var afterConstantEdit = Map.of(widget, AbiSignature.hash(widget(2, "body-a")));
        var abiChanged = RecompileSet.abiChanged(Set.of(widget), previous, afterConstantEdit);
        assertThat(abiChanged).containsExactly(widget);
        assertThat(RecompileSet.compute(g, Set.of(widget), abiChanged))
            .containsExactlyInAnyOrder(widget, caller);

        // Body-only edit leaves the hash still: Caller is pruned.
        var afterBodyEdit = Map.of(widget, AbiSignature.hash(widget(1, "body-b")));
        var noAbiChange = RecompileSet.abiChanged(Set.of(widget), previous, afterBodyEdit);
        assertThat(noAbiChange).isEmpty();
        assertThat(RecompileSet.compute(g, Set.of(widget), noAbiChange))
            .containsExactly(widget);
    }

    /** {@code Widget} with {@code public static final int LIMIT = <limit>} and a method whose body is {@code body}. */
    private static TypeSpec widget(int limit, String body) {
        return TypeSpec.classBuilder("Widget")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldSpec.builder(TypeName.INT, "LIMIT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", limit).build())
            .addMethod(MethodSpec.methodBuilder("describe")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get("java.lang", "String"))
                .addStatement("return $S", body).build())
            .build();
    }
}
