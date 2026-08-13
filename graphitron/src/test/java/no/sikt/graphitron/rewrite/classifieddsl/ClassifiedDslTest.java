package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.ExemptionRegistry;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus.Example;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-by-example corpus. Each fixture is an annotated schema; the harness classifies
 * it with today's classifier and checks every {@code @classified} / {@code @classifiedType} coordinate
 * against its declared dimensional verdict (read through the schema's {@code sourceOf} /
 * {@code operationMembersOf} seams and the leaf's {@code target()}).
 *
 * <p>The meta-tests pin the coverage obligations over the {@code (source, operations, target)} axes:
 * <ul>
 *   <li><b>Verdict totality</b> is compiler-enforced for the leaf-derived axes: the {@code source()} /
 *       {@code target()} producers on each root ({@code QueryField} / {@code MutationField} /
 *       {@code ChildField}) switch exhaustively over that root's sealed leaves, so a new leaf without a
 *       verdict fails the build. The member rows are fenced per leaf by
 *       {@code OperationMembers.DECLARED_SHAPES} (totality against the sealed leaves is
 *       {@code OperationMemberProjectionTest}'s). No runtime test can strengthen that, so none is
 *       written.</li>
 *   <li><b>Value exercise</b>, {@link #everyDimensionValueIsExercised()}: every {@link Source} wrapper
 *       arm, {@link OperationMember} leaf arm, {@link no.sikt.graphitron.rewrite.model.Target} wrapper
 *       arm, {@link no.sikt.graphitron.rewrite.model.TargetShape} arm, and {@link SourceShape} value is
 *       either produced by some fixture (member arms on declared-and-agreeing rows) or, for the
 *       modeled-but-unpopulated arms, on an explicit known-gap list with a stated reason.</li>
 *   <li><b>Declaration fence</b>, {@link #declaredMemberListsSitInsideTheLeafsDeclaredShape()}: every
 *       declared {@code operations:} list is judged against the classified leaf's declared
 *       co-occurrence shape, production-independently, so an authored list the grammar rejects fails
 *       even when the production agrees with it.</li>
 *   <li><b>SDL-vs-Java mirrors</b>: the SDL {@code SourceWrapper} / {@code Member} /
 *       {@code TargetWrapper} / {@code TargetShape} / {@code SourceShape} enums equal the sealed-arm sets
 *       the field model produces.</li>
 *   <li><b>TypeVerdict mirror</b>: the SDL {@code TypeVerdict} enum equals the non-failure
 *       {@code GraphitronType} leaf set. Its soundness, like every name-based mirror here, rests on the
 *       arm simple names being unique ({@link #sealedAxisLeafSimpleNamesAreUnique()}).</li>
 * </ul>
 */
@PipelineTier
class ClassifiedDslTest {

    static Stream<Example> corpus() {
        return ClassifiedCorpus.examples().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void corpusClassifiesToDeclaredDimensions(Example example) {
        var result = ClassifiedHarness.classify(example.sdl());

        assertThat(result.fields().isEmpty() && result.types().isEmpty())
            .as("fixture %s must annotate at least one coordinate (@classified field or "
                + "@classifiedType type); some coverage fixtures assert only a type verdict", example)
            .isFalse();

        for (var fc : result.fields()) {
            assertThat(fc.actual())
                .as("%s.%s classifies to its declared (source, operations, target)", fc.parentType(), fc.fieldName())
                .isEqualTo(fc.expected());
        }
        for (var tc : result.types()) {
            assertThat(tc.actualVerdict())
                .as("type %s classifies to its declared TypeVerdict", tc.typeName())
                .isEqualTo(tc.expectedVerdict());
        }
        for (var sc : result.synthesises()) {
            assertThat(sc.produced())
                .as("%s.%s mints exactly its declared synthesis set (declared vs the "
                    + "connection-synthesis relation's produced row)", sc.parentType(), sc.fieldName())
                .isEqualTo(sc.declared());
        }
        // @commits agreement: declared arm tokens equal the produced launcher row's arm simple
        // names per coordinate. A declaration on a coordinate with no produced row fails (the
        // null produced side mismatches); a produced row with no declaration is fine, since the
        // directive makes no membership claim.
        var production = ClassifiedHarness.launcherProductions().get(example.id());
        for (var cc : ClassifiedHarness.commitCases(result, production)) {
            assertThat(cc.producedSource())
                .as("%s.%s declares @commits but the canonical run produced no launcher row at "
                    + "the coordinate", cc.parentType(), cc.fieldName())
                .isNotNull();
            assertThat(cc.producedSource())
                .as("%s.%s: declared launcher source arm vs the produced row's",
                    cc.parentType(), cc.fieldName())
                .isEqualTo(cc.declaredSource());
            assertThat(cc.producedResult())
                .as("%s.%s: declared launcher result arm vs the produced row's",
                    cc.parentType(), cc.fieldName())
                .isEqualTo(cc.declaredResult());
        }
    }

    /**
     * The launcher production sweep's failure roster, bound by equality in both directions: a
     * landed validator rejection (or emission) for a recorded gap shows up as a roster
     * mismatch, and a second example acquiring a gap is loud. The entry's reason is grounded on
     * the recorded validator-mirror gap it rides.
     *
     * <p>The roster shrank by one when the classifier started rejecting a child {@code @service}
     * declaring no {@code Sources} parameter: the {@code service} example's no-key coordinates now
     * carry real batch parameters and produce their rows, so the guard they rode is unreachable by
     * construction rather than merely unexercised.
     */
    @Test
    void launcherProductionFailureRosterIsExact() {
        var productions = ClassifiedHarness.launcherProductions();
        var failed = productions.entrySet().stream()
            .filter(e -> e.getValue() instanceof ClassifiedHarness.LauncherProduction.Failed)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        assertThat(failed)
            .as("exactly the one recorded validator-mirror-gap example fails launcher production;"
                + " an entry leaving means its gap closed (celebrate and shrink the roster), an"
                + " entry joining means a new example rides an unrecorded gap")
            .containsExactly("record-method");
        assertThat(((ClassifiedHarness.LauncherProduction.Failed) productions.get("record-method")).reason())
            .as("record-method rides the batched-lookup single-record-per-key guard: the"
                + " validator accepts the shape, no batched-lookup emission exists for the cell")
            .contains("batched lookup child");
    }

    /**
     * The invocation-determination pin over the corpus production sweep: every produced row's
     * delivery arm equals the arm the producer's declared determination map carries for its
     * source arm (the third relation the shared pin covers, beside the membership fixture and
     * the closure test's generator run).
     */
    @Test
    void producedInvocationMatchesTheDeclaredDeterminationAcrossTheCorpus() {
        for (var production : ClassifiedHarness.launcherProductions().values()) {
            if (production instanceof ClassifiedHarness.LauncherProduction.Produced p) {
                no.sikt.graphitron.plan.LauncherAxisPins
                    .assertInvocationMatchesDeclaredDetermination(p.relation());
            }
        }
    }

    /**
     * The launcher-arm occupancy census, the classification axis-pair census's launcher
     * sibling: sourced off the canonical per-example production sweep, it prints per-axis
     * occupancy and pairwise co-variation over the four launcher axes
     * ({@code source} / {@code invocation} / {@code tenancy} / {@code result}) so the next
     * re-measurement is a test run rather than a scratch file. The assertion is non-vacuity
     * (rows exist, every pair observable); the functional-determination claim lives in
     * {@link #producedInvocationMatchesTheDeclaredDeterminationAcrossTheCorpus()}, not here.
     */
    @Test
    void launcherAxisCensusIsDerivable() {
        record RowAxes(String source, String invocation, String tenancy, String result) {}
        var rows = new ArrayList<RowAxes>();
        for (var production : ClassifiedHarness.launcherProductions().values()) {
            if (production instanceof ClassifiedHarness.LauncherProduction.Produced p) {
                for (var row : p.relation().rows()) {
                    rows.add(new RowAxes(
                        row.source().getClass().getSimpleName(),
                        row.invocation().getClass().getSimpleName(),
                        row.tenancy().getClass().getSimpleName(),
                        row.result().getClass().getSimpleName()));
                }
            }
        }
        assertThat(rows).as("launcher rows measured (census must not be vacuous)").isNotEmpty();

        Map<String, Function<RowAxes, String>> axes = new LinkedHashMap<>();
        axes.put("source", RowAxes::source);
        axes.put("invocation", RowAxes::invocation);
        axes.put("tenancy", RowAxes::tenancy);
        axes.put("result", RowAxes::result);

        for (var axis : axes.entrySet()) {
            var counts = new TreeMap<String, Integer>();
            for (var row : rows) {
                counts.merge(axis.getValue().apply(row), 1, Integer::sum);
            }
            System.out.printf("LAUNCHER-AXIS %s: %s%n", axis.getKey(), counts);
        }

        List<String> names = List.copyOf(axes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                var first = axes.get(names.get(i));
                var second = axes.get(names.get(j));
                var firstValues = new TreeSet<String>();
                var secondValues = new TreeSet<String>();
                var observedPairs = new TreeSet<String>();
                for (var row : rows) {
                    firstValues.add(first.apply(row));
                    secondValues.add(second.apply(row));
                    observedPairs.add(first.apply(row) + "*" + second.apply(row));
                }
                assertThat(observedPairs)
                    .as("launcher axis pair %s x %s must be observable over the corpus",
                        names.get(i), names.get(j))
                    .isNotEmpty();
                var missing = new TreeSet<String>();
                for (String a : firstValues) {
                    for (String b : secondValues) {
                        if (!observedPairs.contains(a + "*" + b)) missing.add(a + "*" + b);
                    }
                }
                System.out.printf("LAUNCHER-PAIR %s x %s: observed %d of %d (%d x %d), missing %s%n",
                    names.get(i), names.get(j), observedPairs.size(),
                    firstValues.size() * secondValues.size(),
                    firstValues.size(), secondValues.size(), missing);
            }
        }
    }

    /**
     * Source wrapper arms the model declares but no fixture reaches, each with the reason. Empty:
     * every source wrapper arm ({@code Query} / {@code Mutation} / {@code OnlyChild} /
     * {@code Child}) is exercised by a corpus fixture. An arm that becomes unreachable rejoins
     * this list with its reason; an entry a fixture exercises must be removed (asserted below).
     */
    private static final Map<String, String> SOURCE_KNOWN_GAPS = Map.of();

    /**
     * One corpus coordinate's classified position on the five axes; {@code sourceShape} is null
     * on Root rows (the shape exists only on the nested source arms). Extracted once and shared
     * by {@link #everyDimensionValueIsExercised()} and {@link #axisPairCensusIsDerivable()} so
     * the two instruments cannot drift on what an axis value is.
     */
    private record CoordinateAxes(
        String source, SourceShape sourceShape, String targetWrapper, String targetShape) {}

    private static List<CoordinateAxes> corpusAxes;

    private static List<CoordinateAxes> corpusAxes() {
        if (corpusAxes == null) {
            var rows = new ArrayList<CoordinateAxes>();
            for (var example : ClassifiedCorpus.examples()) {
                for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                    Source source = fc.actual().source();
                    SourceShape shape = switch (source) {
                        case Source.OnlyChild(var s) -> s;
                        case Source.Child(var s) -> s;
                        case Source.Root ignored -> null;
                    };
                    rows.add(new CoordinateAxes(
                        source.getClass().getSimpleName(),
                        shape,
                        fc.actual().target().wrapper().getSimpleName(),
                        fc.actual().target().shape().getSimpleName()));
                }
            }
            corpusAxes = List.copyOf(rows);
        }
        return corpusAxes;
    }

    @Test
    void everyDimensionValueIsExercised() {
        var sourceArms = new HashSet<String>();
        var sourceShapes = EnumSet.noneOf(SourceShape.class);
        var targetWrappers = new HashSet<String>();
        var targetShapes = new HashSet<String>();

        for (var row : corpusAxes()) {
            sourceArms.add(row.source());
            if (row.sourceShape() != null) {
                sourceShapes.add(row.sourceShape());
            }
            targetWrappers.add(row.targetWrapper());
            targetShapes.add(row.targetShape());
        }

        // Target wrapper and shape arms are fully exercised (no declared gaps).
        assertThat(targetWrappers)
            .as("every Target wrapper arm must be exercised by the corpus")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetWrapperArmSimpleNames());
        assertThat(targetShapes)
            .as("every TargetShape arm must be exercised by the corpus")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetShapeArmSimpleNames());

        // Both source shapes are exercised on the nested (Child / OnlyChild) arms.
        assertThat(sourceShapes)
            .as("both source-shape values must be exercised by nested-source rows")
            .containsExactlyInAnyOrder(SourceShape.values());

        // Source wrapper arms: every arm exercised or on the known-gap list.
        var unexercisedSource = new HashSet<>(ClassifiedHarness.sourceWrapperArmSimpleNames());
        unexercisedSource.removeAll(sourceArms);
        unexercisedSource.removeAll(SOURCE_KNOWN_GAPS.keySet());
        assertThat(unexercisedSource)
            .as("every source wrapper arm must be exercised by a fixture or listed in SOURCE_KNOWN_GAPS "
                + "with a stated reason; these are neither")
            .isEmpty();
        assertThat(SOURCE_KNOWN_GAPS.keySet())
            .as("a known-gap source arm that a fixture now exercises must be removed from SOURCE_KNOWN_GAPS")
            .doesNotContainAnyElementsOf(sourceArms);

        // The operation axis, at member grain: every OperationMember leaf arm reached by a
        // declared-and-agreeing corpus row or exempt with a typed reason. The known-gap map
        // (ExemptionRegistry.MEMBER_KNOWN_GAPS) is one registry obligation; the shared
        // assertion carries the exercised-must-be-removed ratchet.
        ExemptionRegistry.assertHonoured(ExemptionRegistry.MEMBER_ARMS);
    }

    /**
     * The declaration fence: every declared member list sits inside the coordinate's leaf-declared
     * co-occurrence shape ({@code OperationMembers.DECLARED_SHAPES}), judged from the declaration
     * and the classified leaf alone. This is the production-independent check on the authored
     * {@code operations:} lists: the main corpus test asserts declared-equals-produced, which a
     * declaration transcribed from the production satisfies by construction; this fence is the
     * second judge (the grammar, not the producer), so a declared list the grammar rejects fails
     * even when the production agrees with it. The two member vocabularies are bound here by the
     * total {@code OperationMember.kind()} column ({@code ClassifiedHarness.kindOf} is its
     * class-token restatement, derived from the seal's own structure).
     */
    @Test
    void declaredMemberListsSitInsideTheLeafsDeclaredShape() {
        for (var example : ClassifiedCorpus.examples()) {
            for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                @SuppressWarnings("unchecked")
                var shape = OperationMembers.declaredShapeOf(
                    (Class<? extends no.sikt.graphitron.rewrite.model.OutputField>) fc.leaf());
                var declaredKinds = fc.expected().operations().stream()
                    .map(ClassifiedHarness::kindOf)
                    .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(OperationMember.Kind.class)));
                var admitted = EnumSet.noneOf(OperationMember.Kind.class);
                admitted.addAll(shape.required());
                admitted.addAll(shape.optional());
                assertThat(admitted)
                    .as("declared operations: kinds at %s: %s.%s must sit inside the %s leaf's "
                            + "declared shape (required %s, optional %s)",
                        example.id(), fc.parentType(), fc.fieldName(), fc.leaf().getSimpleName(),
                        shape.required(), shape.optional())
                    .containsAll(declaredKinds);
                assertThat(declaredKinds)
                    .as("declared operations: kinds at %s: %s.%s must include the %s leaf's "
                            + "required kinds %s",
                        example.id(), fc.parentType(), fc.fieldName(), fc.leaf().getSimpleName(),
                        shape.required())
                    .containsAll(shape.required());
            }
        }
    }

    /**
     * The axis-pair census: for each pair of classification axes, is the cross product of the
     * corpus-observed values populated, or only a diagonal? A populated product is measured
     * independence, so the families must separate; a diagonal is measured co-variation, so they
     * stay fused and the machinery is saved. The census turns "which families are real" from a
     * judgment call into a measurement, and its consumers are the grain worklist and the
     * split-on-measured-independence rule.
     *
     * <p>Stated so the instrument is not mistaken for a different one: this measures co-variation
     * between classification axes at the coordinate grain. It cannot falsify the projection
     * command's contribution-arm split, which is decided by counted downstream consumers of the
     * distinction, not by provenance and not by this census.
     *
     * <p>Mechanics: denominators use corpus-observed values only, so a known-gap arm (never
     * observed) cannot inflate a product. Pairs involving the source shape exclude Root rows,
     * where no shape exists; source-wrapper-by-source-shape is skipped outright because the
     * shape is a component of the nested wrapper arms, a containment rather than two independent
     * axes. The assertion is non-vacuity; the measured matrix prints to the test output as the
     * re-derivable figure.
     */
    @Test
    void axisPairCensusIsDerivable() {
        Map<String, Function<CoordinateAxes, String>> axes = new LinkedHashMap<>();
        axes.put("source", CoordinateAxes::source);
        axes.put("sourceShape", row -> row.sourceShape() == null ? null : row.sourceShape().name());
        axes.put("targetWrapper", CoordinateAxes::targetWrapper);
        axes.put("targetShape", CoordinateAxes::targetShape);

        var rows = corpusAxes();
        assertThat(rows.size()).as("corpus coordinates measured (census must not be vacuous)")
            .isGreaterThan(50);

        List<String> names = List.copyOf(axes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                if (names.get(i).equals("source") && names.get(j).equals("sourceShape")) {
                    continue; // containment, not a pair of independent axes
                }
                var first = axes.get(names.get(i));
                var second = axes.get(names.get(j));
                var firstValues = new TreeSet<String>();
                var secondValues = new TreeSet<String>();
                var observedPairs = new TreeSet<String>();
                for (var row : rows) {
                    String a = first.apply(row);
                    String b = second.apply(row);
                    if (a == null || b == null) continue;
                    firstValues.add(a);
                    secondValues.add(b);
                    observedPairs.add(a + "*" + b);
                }
                assertThat(observedPairs)
                    .as("axis pair %s x %s must be observable over the corpus", names.get(i), names.get(j))
                    .isNotEmpty();
                var missing = new ArrayList<String>();
                for (String a : firstValues) {
                    for (String b : secondValues) {
                        if (!observedPairs.contains(a + "*" + b)) missing.add(a + "*" + b);
                    }
                }
                System.out.printf("PAIR %s x %s: observed %d of %d (%d x %d), missing %s%n",
                    names.get(i), names.get(j), observedPairs.size(),
                    firstValues.size() * secondValues.size(),
                    firstValues.size(), secondValues.size(), missing);
            }
        }
    }

    /**
     * The member-grain census, the operation axis's instrument since the coordinate-grain
     * axis-pair census above dropped its single-token operation column (the census re-key of the
     * corpus re-grain): one observation per {@code (coordinate, member)} row of
     * {@link no.sikt.graphitron.rewrite.GraphitronSchema#operationMembersOf}, printing the
     * member-kind occupancy histogram, the per-leaf admissible-versus-observed combination gap
     * (admissible is the image of the leaf's declared shape, the required kinds plus the
     * powerset of the payload-gated optionals; observed is the distinct member-kind sets corpus
     * coordinates actually produce), and the member-kind-by-source / by-target-wrapper /
     * by-target-shape pairs in the axis-pair census's print form, so every retired
     * operation-column pair has a member-grain successor here. The assertion is non-vacuity; the
     * measured figures print as the re-derivable data the dissolution slices consume.
     */
    @Test
    void memberGrainCensusIsDerivable() {
        record MemberRow(Class<?> leaf, Set<OperationMember.Kind> kinds, String source,
                         String targetWrapper, String targetShape) {}
        var rows = new ArrayList<MemberRow>();
        for (var example : ClassifiedCorpus.examples()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            for (var entry : schema.fields().entrySet()) {
                if (!(entry.getValue() instanceof OutputField leaf)) {
                    continue;
                }
                var kinds = schema.operationMembersOf(entry.getKey()).stream()
                    .map(OperationMember::kind)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(OperationMember.Kind.class)));
                var source = schema.sourceOf(entry.getKey());
                rows.add(new MemberRow(leaf.getClass(), kinds,
                    source == null ? "?" : source.getClass().getSimpleName(),
                    leaf.target().getClass().getSimpleName(),
                    leaf.target().shape().getClass().getSimpleName()));
            }
        }
        assertThat(rows.size()).as("corpus coordinates measured (census must not be vacuous)")
            .isGreaterThan(50);

        var histogram = new TreeMap<OperationMember.Kind, Integer>();
        for (var row : rows) {
            row.kinds().forEach(k -> histogram.merge(k, 1, Integer::sum));
        }
        long emptySets = rows.stream().filter(r -> r.kinds().isEmpty()).count();
        System.out.printf("MEMBERS histogram over %d coordinates (%d empty sets): %s%n",
            rows.size(), emptySets, histogram);
        assertThat(histogram).as("the member census must observe populated kinds").isNotEmpty();

        // Per-leaf admissible-versus-observed combination gap.
        var observedByLeaf = new TreeMap<String, Set<Set<OperationMember.Kind>>>();
        for (var row : rows) {
            observedByLeaf.computeIfAbsent(row.leaf().getSimpleName(), k -> new HashSet<>())
                .add(row.kinds());
        }
        for (var e : OperationMembers.DECLARED_SHAPES.entrySet()) {
            long admissible = 1L << e.getValue().optional().size();
            var observed = observedByLeaf.getOrDefault(e.getKey().getSimpleName(), Set.of());
            System.out.printf("SHAPE %s: observed %d of %d admissible combinations "
                    + "(required %s, optional %s)%n",
                e.getKey().getSimpleName(), observed.size(), admissible,
                new TreeSet<>(e.getValue().required()), new TreeSet<>(e.getValue().optional()));
        }

        // Member-kind pairs against the coordinate axes, one observation per member row. These
        // three pairs are the operation axis's share of the retired coordinate-grain pair census
        // (whose operation column dropped with the single-token fold); the coordinate-grain
        // census keeps measuring the source and target axes against each other.
        Map<String, Function<MemberRow, String>> pairAxes = new LinkedHashMap<>();
        pairAxes.put("source", MemberRow::source);
        pairAxes.put("targetWrapper", MemberRow::targetWrapper);
        pairAxes.put("targetShape", MemberRow::targetShape);
        for (var axis : pairAxes.entrySet()) {
            var observedPairs = new TreeSet<String>();
            for (var row : rows) {
                String b = axis.getValue().apply(row);
                for (var kind : row.kinds()) {
                    observedPairs.add(kind + "*" + b);
                }
            }
            System.out.printf("PAIR memberKind x %s: observed %d pairs: %s%n",
                axis.getKey(), observedPairs.size(), observedPairs);
            assertThat(observedPairs)
                .as("member-kind pair census over %s must be observable", axis.getKey())
                .isNotEmpty();
        }
    }

    @Test
    void sourceWrapperMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.sourceWrapperEnumConstants())
            .as("the SDL SourceWrapper enum must mirror the sealed Source leaf arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.sourceWrapperArmSimpleNames());
    }


    @Test
    void memberMirrorsTheOperationMemberArms() {
        assertThat(ClassifiedHarness.memberEnumConstants())
            .as("the SDL Member enum must mirror the sealed OperationMember leaves; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.memberArmSimpleNames());
    }

    @Test
    void targetWrapperMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.targetWrapperEnumConstants())
            .as("the SDL TargetWrapper enum must mirror the sealed Target wrapper arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetWrapperArmSimpleNames());
    }

    @Test
    void targetShapeMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.targetShapeEnumConstants())
            .as("the SDL TargetShape enum must mirror the sealed TargetShape arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.targetShapeArmSimpleNames());
    }

    @Test
    void sourceShapeMirrorsAdapterValues() {
        assertThat(ClassifiedHarness.sourceShapeEnumConstants())
            .as("the SDL SourceShape enum must mirror the Java SourceShape value set; "
                + "adding a value to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(enumNames(SourceShape.values()));
    }

    private static <E extends Enum<E>> Set<String> enumNames(E[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    void synthesisedTypeMirrorsTheRelationsMintedArmVocabulary() {
        assertThat(ClassifiedHarness.synthesisedTypeEnumConstants())
            .as("the SDL SynthesisedType enum must mirror the connection-synthesis relation's "
                + "declared minted-arm vocabulary; widening one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(
                no.sikt.graphitron.rewrite.model.ConnectionSynthesis.MINTED_ARM_VOCABULARY.stream()
                    .map(Class::getSimpleName)
                    .collect(Collectors.toSet()));
    }

    @Test
    void launcherSourceMirrorsTheLaunchSourceArms() {
        assertThat(ClassifiedHarness.launcherSourceEnumConstants())
            .as("the SDL LauncherSource enum must mirror the concrete sealed LaunchSource arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.launchSourceArmSimpleNames());
    }

    @Test
    void launcherResultMirrorsTheResultShapeArms() {
        assertThat(ClassifiedHarness.launcherResultEnumConstants())
            .as("the SDL LauncherResult enum must mirror the sealed ResultShape arms; "
                + "adding an arm to one side without the other fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.resultShapeArmSimpleNames());
    }

    @Test
    void typeVerdictMirrorsGraphitronTypeLeaves() {
        assertThat(ClassifiedHarness.typeVerdictEnumConstants())
            .as("the SDL TypeVerdict enum must mirror GraphitronType's non-failure sealed leaves; "
                + "adding a type leaf without a matching TypeVerdict constant (or vice versa) fails here")
            .containsExactlyInAnyOrderElementsOf(ClassifiedHarness.graphitronTypeNonFailureLeafNames());
    }

    @Test
    void sealedAxisLeafSimpleNamesAreUnique() {
        // Every name-based mirror above compares the SDL enum against sealed-leaf simple names, so a future
        // nested leaf reusing a name within one seal would silently conflate two leaves and let the mirror
        // pass while a real leaf goes unmirrored. (Table / Record / Interface reused *across* SourceShape
        // and TargetShape is safe: the two seals are never folded into one name set.)
        assertThat(ClassifiedHarness.sourceWrapperArmSimpleNames()).as("Source arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.memberArmSimpleNames()).as("OperationMember arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.targetWrapperArmSimpleNames()).as("Target wrapper arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.targetShapeArmSimpleNames()).as("TargetShape arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.launchSourceArmSimpleNames()).as("LaunchSource arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.resultShapeArmSimpleNames()).as("ResultShape arm names").doesNotHaveDuplicates();
        assertThat(ClassifiedHarness.graphitronTypeNonFailureLeafSimpleNames())
            .as("GraphitronType's sealed leaves must have unique simple names: the TypeVerdict mirror "
                + "compares by simple name, so a future nested leaf reusing a name would silently conflate two")
            .doesNotHaveDuplicates();
    }
}
