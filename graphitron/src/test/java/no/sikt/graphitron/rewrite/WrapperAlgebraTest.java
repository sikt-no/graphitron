package no.sikt.graphitron.rewrite;

import graphql.language.FieldDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.schema.FieldCoordinates;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper-algebra invariant, made executable rather than prose.
 *
 * <p>The pivot keeps cardinality as a <em>wrapper bound to an endpoint</em>, never a free
 * {@code One} / {@code Many} enum: the {@link Source} arm is the field's arrival cardinality and the
 * {@link Target} arm is its output cardinality. This test pins the two-position algebra the
 * {@code source} and {@code target} axes encode (the wrapper algebra), walking every field the
 * spec-by-example corpus demonstrates ({@link ClassifiedCorpus}), the same corpus-mirror shape
 * {@link SourceShapeProjectionTest} uses for the source-shape projection.
 *
 * <p>Two halves, each cross-checked against an <em>independent</em> source of truth rather than against
 * the producer that built the arm:
 *
 * <ul>
 *   <li><b>Target half</b> ({@link #targetWrapperEqualsTheFieldsOwnOutputWrapper()}): {@code target.wrapper}
 *       equals the field's own GraphQL output wrapper. The independent truth is the field's return type
 *       read straight off the parsed SDL ({@link TypeDefinitionRegistry}), <em>not</em> the
 *       {@link OutputField#target()} switch the model builds. Connections are the one principled
 *       asymmetry and are excluded from this mirror: a native Relay connection (SDL {@code FooConnection},
 *       non-list) and an {@code @asConnection}-promoted field (SDL {@code [Foo]}, list) both collapse to
 *       {@code Single(Connection(...))}, their many-ness riding the {@code edges} / {@code nodes} fields,
 *       so they are governed by the decomposition law ({@link #connectionTargetIsAlwaysSingleWrapped()})
 *       rather than the SDL-list mirror.</li>
 *   <li><b>Source half</b> ({@link #sourceWrapperIsTheFoldOfAncestorTargetWrappers()}): {@code source.wrapper}
 *       is the fold of the ancestors' target wrappers ({@link Source.Root} the empty product,
 *       {@link Source.OnlyChild} the {@code One} identity, {@link Source.Child} the {@code Many} absorber).
 *       The fold is real (the ancestor-product arrival index, read through
 *       {@code GraphitronSchema.sourceOf}), so this half no longer asserts a hard-coded conservative arm.
 *       Reimplementing the fold test-side would be a drifting second copy, and a simplified one would be
 *       vacuous, so instead it pins the laws that stay independently checkable: the <em>Root law</em> (a
 *       field on an SDL root operation type folds the empty product, {@code Root}), the <em>grain law</em>
 *       (arrival is a function of the parent typename alone, so every {@link OutputField} on one parent
 *       carries the same arrival arm), and a <em>coverage floor</em> requiring {@link Source.OnlyChild},
 *       {@link Source.Child}, and a {@link Source.Root} arm all observed across the corpus.</li>
 * </ul>
 */
@PipelineTier
class WrapperAlgebraTest {

    /**
     * The scalar-projection leaves whose {@link OutputField#target()} is built via
     * {@link OutputField#single} and therefore <em>does not model output cardinality</em>: the column
     * family, the record-property passthrough, and the errors-list field. graphql-java's
     * {@code PropertyDataFetcher} reads the value (list or not) straight off the source, so {@code Single}
     * is the faithful read regardless of the GraphQL wrapper, exactly as {@code OutputField.single}'s
     * javadoc anticipates this test surfacing (a list-shaped scalar such as {@code MyError.path:
     * [String!]!}). These are excluded from the SDL-list mirror and instead pinned as always-{@code Single}.
     */
    private static final Set<Class<?>> CARDINALITY_NOT_MODELED = Set.of(
        no.sikt.graphitron.rewrite.model.ChildField.ColumnField.class,
        no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField.class,
        no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField.class,
        no.sikt.graphitron.rewrite.model.ChildField.CompositeColumnField.class,
        no.sikt.graphitron.rewrite.model.ChildField.CompositeColumnReferenceField.class,
        no.sikt.graphitron.rewrite.model.ChildField.PropertyField.class,
        no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);

    @Test
    void targetWrapperEqualsTheFieldsOwnOutputWrapper() {
        var observedWrappers = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            var registry = TestSchemaHelper.parseRegistryWithPrelude(example.sdl());
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            schema.fields().forEach((coord, field) -> {
                if (!(field instanceof OutputField out)) return;
                // Connection targets are the one principled asymmetry: a native Relay connection
                // (SDL FooConnection, non-list) and an @asConnection-promoted field (SDL [Foo], list)
                // both collapse to Single(Connection(...)), so neither obeys the SDL-list mirror. They
                // are governed by the decomposition law in connectionTargetIsAlwaysSingleWrapped instead.
                if (out.target().shape() instanceof TargetShape.Connection) return;
                var declaredList = graphqlReturnsList(registry, coord);
                if (declaredList.isEmpty()) return; // synthesised coordinate with no SDL field (e.g. Relay edges)
                observedWrappers.add(out.target().getClass());
                if (CARDINALITY_NOT_MODELED.contains(field.getClass())) {
                    assertThat(out.target())
                        .as("%s: a scalar-projection leaf does not model output cardinality, so its "
                            + "target() is the faithful Single even when the GraphQL type is a list", coord)
                        .isInstanceOf(Target.Single.class);
                } else {
                    assertThat(out.target() instanceof Target.List)
                        .as("%s: a cardinality-modelling non-connection target() must be a List arm iff "
                            + "the field's GraphQL return type is a list", coord)
                        .isEqualTo(declaredList.get());
                }
            });
        }
        assertThat(observedWrappers)
            .as("the corpus must exercise both target wrapper arms for the mirror to be meaningful")
            .containsExactlyInAnyOrder(Target.Single.class, Target.List.class);
    }

    @Test
    void cardinalityNotModeledSetListsOnlyRealLeaves() {
        var leaves = GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.rewrite.model.GraphitronField.class);
        assertThat(CARDINALITY_NOT_MODELED)
            .as("the cardinality-not-modelled exemption must only name real GraphitronField leaves, so it "
                + "cannot rot into a stale escape hatch")
            .allMatch(leaves::contains);
    }

    @Test
    void connectionTargetIsAlwaysSingleWrapped() {
        var sawConnection = false;
        for (var example : ClassifiedCorpus.examples()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            for (var entry : schema.fields().entrySet()) {
                if (!(entry.getValue() instanceof OutputField out)) continue;
                if (out.target().shape() instanceof TargetShape.Connection) {
                    sawConnection = true;
                    assertThat(out.target())
                        .as("%s: a Connection target is the decomposition of the fused TableConnection "
                            + "mapping — Single(Connection(...)), its many-ness on edges/nodes, never a List arm",
                            entry.getKey())
                        .isInstanceOf(Target.Single.class);
                }
            }
        }
        assertThat(sawConnection)
            .as("the corpus must demonstrate a Connection target for the decomposition law to be meaningful")
            .isTrue();
    }

    @Test
    void sourceWrapperIsTheFoldOfAncestorTargetWrappers() {
        var observedSources = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            var registry = TestSchemaHelper.parseRegistryWithPrelude(example.sdl());
            var roots = rootOperationTypeNames(registry);
            var schema = ClassifiedHarness.classify(example.sdl()).schema();

            // The arrival arm observed per parent type: the grain law requires it to be uniform.
            var armByParent = new java.util.LinkedHashMap<String, Class<? extends Source>>();
            schema.fields().forEach((coord, field) -> {
                if (!(field instanceof OutputField)) return;
                Source source = schema.sourceOf(coord);
                observedSources.add(source.getClass());
                boolean onRoot = roots.contains(coord.getTypeName());
                if (onRoot) {
                    // Root law — empty product: a root field has no ancestor, so the fold yields Root.
                    assertThat(source)
                        .as("%s: a field on a root operation type folds the empty product -> Source.Root", coord)
                        .isInstanceOf(Source.Root.class);
                } else {
                    // A nested field's arrival is OnlyChild (One) or Child (Many); never Root.
                    assertThat(source)
                        .as("%s: a nested field's arrival is OnlyChild or Child, never Root", coord)
                        .isInstanceOfAny(Source.OnlyChild.class, Source.Child.class);
                }
                // Grain law: arrival is a function of the parent typename alone, so every field on one
                // parent carries the same arrival arm. A parent that produced two different arms means
                // the fold leaked below parent grain.
                var prior = armByParent.putIfAbsent(coord.getTypeName(), source.getClass());
                if (prior != null) {
                    assertThat(source.getClass())
                        .as("%s: arrival is parent-grain, so every field on '%s' must carry the same arrival arm",
                            coord, coord.getTypeName())
                        .isEqualTo(prior);
                }
            });
        }
        // Coverage floor: the corpus must exercise all three arms of the fold for the laws to be
        // meaningful. OnlyChild is reachable, so it is no longer a documented gap.
        assertThat(observedSources)
            .as("the corpus must exercise the Root (empty product), OnlyChild (One identity), and Child "
                + "(Many absorber) arms of the arrival fold")
            .contains(Source.OnlyChild.class, Source.Child.class)
            .containsAnyOf(Source.Root.Query.class, Source.Root.Mutation.class);
    }

    /**
     * Whether the field at {@code coord} returns a GraphQL list, read directly off the parsed SDL (the
     * independent truth for the target-wrapper mirror). {@link Optional#empty()} when the coordinate has
     * no SDL field definition (a synthesised coordinate), so the caller skips it rather than asserting.
     */
    private static Optional<Boolean> graphqlReturnsList(TypeDefinitionRegistry registry, FieldCoordinates coord) {
        return Optional.ofNullable(registry.types().get(coord.getTypeName()))
            .flatMap(t -> fieldDefinitions(t).stream()
                .filter(f -> f.getName().equals(coord.getFieldName()))
                .findFirst())
            .map(f -> unwrapsToList(f.getType()));
    }

    private static List<FieldDefinition> fieldDefinitions(TypeDefinition<?> type) {
        if (type instanceof ObjectTypeDefinition o) return o.getFieldDefinitions();
        if (type instanceof InterfaceTypeDefinition i) return i.getFieldDefinitions();
        return List.of();
    }

    /** Unwrap any leading {@code NonNull} and report whether the underlying type node is a list. */
    private static boolean unwrapsToList(Type<?> type) {
        if (type instanceof NonNullType nn) return unwrapsToList(nn.getType());
        return type instanceof ListType;
    }

    /**
     * The SDL's root operation type names: an explicit {@code schema { query: ... }} block if present,
     * else the graphql-java conventional defaults. The independent truth for "is this an arrival root".
     */
    private static Set<String> rootOperationTypeNames(TypeDefinitionRegistry registry) {
        var explicit = registry.schemaDefinition()
            .map(s -> s.getOperationTypeDefinitions().stream()
                .map(OperationTypeDefinition::getTypeName)
                .map(graphql.language.TypeName::getName)
                .collect(java.util.stream.Collectors.toSet()))
            .orElse(Set.of());
        if (!explicit.isEmpty()) return explicit;
        return Set.of("Query", "Mutation", "Subscription");
    }
}
