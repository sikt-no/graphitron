package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OutputField;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exemption-obligation registry: every coverage obligation that carries a typed
 * {@link Exemption} map is declared here as one {@link Obligation} row pairing the obligation's
 * domain, its covered-set derivation, and its exemption map. The row is the unit the shared
 * {@link #assertHonoured} helper checks: every exemption key is in-domain, every exemption key is
 * still uncovered (the covered-entry-must-be-removed ratchet that forces a closed row off the
 * list), and every domain member is covered or exempt.
 *
 * <p>The corpus-backed rows are asserted by the parameterized meta-test in
 * {@code ExemptionRegistryTest} (pipeline tier); the annotation-derived
 * {@link #LSP_PROJECTION} row is asserted at unit tier by {@code ProjectionCoverageTest}, whose
 * obligation needs no corpus classification. {@code ExemptionRegistryTest} also carries the
 * reflective discovery guard: a static {@code Map<..., Exemption>} anywhere in the test tree that
 * is not a registry row fails the build, so a sixth exemption list cannot appear outside the
 * registry's checks.
 *
 * <p>Blocker facts are single-homed: an exemption shared by several obligations (the UPSERT
 * retirement, the errors-field lift) is one constant referenced from each map, never restated,
 * so a closure retires the fact everywhere at once and the copies cannot drift apart.
 */
public final class ExemptionRegistry {

    private ExemptionRegistry() {}

    /**
     * One obligation: {@code domain} is the set the obligation quantifies over, {@code covered}
     * derives what its instrument currently demonstrates, {@code exemptions} carries the typed
     * story for every domain member the instrument does not reach. Suppliers are memoized, so a
     * row asserted from several tests derives its sets once per JVM.
     */
    public record Obligation(String name,
                      Supplier<Set<Class<?>>> domain,
                      Supplier<Set<Class<?>>> covered,
                      Map<Class<?>, Exemption> exemptions) {
        @Override public String toString() {
            return name;
        }
    }

    /** Asserts one obligation row: keys in-domain, keys still uncovered, domain accounted for. */
    public static void assertHonoured(Obligation o) {
        Set<Class<?>> domain = o.domain().get();
        Set<Class<?>> covered = o.covered().get();

        var outOfDomain = o.exemptions().keySet().stream()
            .filter(k -> !domain.contains(k))
            .map(Class::getSimpleName).sorted().toList();
        assertThat(outOfDomain)
            .as("[%s] every exemption key must be in the obligation's domain; these are stale "
                + "(retired leaves) or homed on the wrong obligation", o.name())
            .isEmpty();

        var coveredExemptions = o.exemptions().keySet().stream()
            .filter(covered::contains)
            .map(Class::getSimpleName).sorted().toList();
        assertThat(coveredExemptions)
            .as("[%s] the obligation's instrument now demonstrates these exempted entries; a "
                + "covered entry must be removed from the exemption map (the ratchet that turns "
                + "a closure into a row deletion)", o.name())
            .isEmpty();

        var unaccounted = domain.stream()
            .filter(k -> !covered.contains(k))
            .filter(k -> !o.exemptions().containsKey(k))
            .map(Class::getSimpleName).sorted().toList();
        assertThat(unaccounted)
            .as("[%s] every domain member must be demonstrated by the obligation's instrument or "
                + "carry a typed Exemption; these are neither (out of %d domain members, %d are "
                + "covered and %d exempt)", o.name(), domain.size(), covered.size(),
                o.exemptions().size())
            .isEmpty();
    }

    // ===== Shared blocker facts (one declaration, referenced by every obligation it exempts) =====

    /** The UPSERT retirement; the blocker string names the lifting item. */
    private static final Exemption UPSERT_RETIRED = new Exemption.Unimplemented(
        "R145 lifting the MutationInputResolver UPSERT rejection (R144 retired generation)",
        "The classifier rejects every UPSERT mutation upstream, so no schema-reachable case can "
        + "land on the UPSERT mutation leaf or the Upsert operation arm.");

    private static final Exemption ERRORS_FIELD_PENDING = new Exemption.Unimplemented(
        "the error-handling parity item's C3 lifting the five PolymorphicReturnType rejection "
        + "sites in FieldBuilder",
        "The ErrorsField permit landed alongside the ErrorChannel slot (its C2), but the "
        + "classifier does not mint the leaf until the rejection sites lift.");

    /**
     * The live ground shared by the Count / Facet rows: a synthesised connection type's
     * {@code totalCount} and {@code facets} fields are not classified coordinates in the fact
     * base at all (the connection-synthesis relation mints the types, but their fields never
     * enter the classified field map), so no coordinate can carry either operation arm. The
     * launcher's result shape is not the blocker any more: {@code ResultShape.Connection}
     * carries the helper, carrier and facet plan today. The blocker string names the owner of
     * the synthesised-fields-as-coordinates model question; a model change landing those
     * coordinates retires both rows together.
     */
    private static final String SYNTHESISED_CONNECTION_FIELDS_NOT_COORDINATES =
        "synthesised connection fields (totalCount, facets) as classified coordinates (R562)";

    /** The instrument the LSP-projection obligation reads; shared by its walker-gap rows. */
    private static final String PROJECTION_WALKER =
        "the @ProjectionFor annotation scan over GraphitronSchemaBuilderTest test methods";

    // ===== The exemption maps, one per obligation =====

    /**
     * Output-field and type leaves the corpus does not demonstrate. Shared vocabulary of the
     * output side of the old {@code NO_CASE_REQUIRED} list; the input side is
     * {@link #ENUM_NO_CASE_REQUIRED}.
     */
    public static final Map<Class<?>, Exemption> CORPUS_NO_CASE_REQUIRED = Map.ofEntries(
        Map.entry(ChildField.ErrorsField.class, ERRORS_FIELD_PENDING));

    /**
     * Input-field leaves without a {@code GraphitronSchemaBuilderTest} enum classification case.
     * Empty: every input leaf currently has one; the ratchet in {@link #assertHonoured} forces a
     * newly demonstrated entry off this map the moment its case lands.
     */
    public static final Map<Class<?>, Exemption> ENUM_NO_CASE_REQUIRED = Map.of();

    /**
     * {@link Operation} arms the model declares but no corpus fixture lands on. An arm leaves
     * this map the moment a fixture exercises it (the ratchet); an unexercised arm not listed
     * here fails the completeness check.
     */
    public static final Map<Class<?>, Exemption> OPERATION_KNOWN_GAPS = Map.of(
        Operation.EntityResolve.class, new Exemption.Unimplemented(
            "a federation _entities classification item",
            "Federation _entities resolution is not a classified leaf yet, so no fixture can "
            + "produce the arm."),
        Operation.Count.class, new Exemption.Unimplemented(
            SYNTHESISED_CONNECTION_FIELDS_NOT_COORDINATES,
            "A synthesised connection type's totalCount field is not a classified coordinate in "
            + "the fact base, so no fixture can land a coordinate on the Count arm; R562 owns "
            + "the synthesised-fields-as-coordinates model question."),
        Operation.Facet.class, new Exemption.Unimplemented(
            SYNTHESISED_CONNECTION_FIELDS_NOT_COORDINATES,
            "A synthesised connection type's facets field is not a classified coordinate in "
            + "the fact base, so no fixture can land a coordinate on the Facet arm; R562 owns "
            + "the synthesised-fields-as-coordinates model question."),
        Operation.UpdateMatching.class, new Exemption.Unimplemented(
            "condition-matched UPDATE",
            "The condition-matched write verbs are declared ahead of implementation; the "
            + "validator-mirror-gaps item owns the per-arm verdicts."),
        Operation.DeleteMatching.class, new Exemption.Unimplemented(
            "condition-matched DELETE",
            "The condition-matched write verbs are declared ahead of implementation; the "
            + "validator-mirror-gaps item owns the per-arm verdicts."),
        Operation.Upsert.class, UPSERT_RETIRED);

    /**
     * {@link OperationMember} leaf arms no declared-and-agreeing {@code operations:} corpus row
     * reaches: the member-grain successor of {@link #OPERATION_KNOWN_GAPS}, carrying the same
     * blocker stories re-keyed onto the member vocabulary (the five modeled-but-unpopulated arms
     * plus the retired UPSERT). An arm leaves this map the moment a fixture declares-and-agrees
     * on it (the ratchet); an unreached arm not listed here fails the completeness check.
     */
    public static final Map<Class<?>, Exemption> MEMBER_KNOWN_GAPS = Map.of(
        OperationMember.EntityResolve.class, new Exemption.Unimplemented(
            "a federation _entities classification item",
            "Federation _entities resolution is not a classified leaf yet, so no fixture can "
            + "produce the member."),
        OperationMember.Count.class, new Exemption.Unimplemented(
            SYNTHESISED_CONNECTION_FIELDS_NOT_COORDINATES,
            "A synthesised connection type's totalCount field is not a classified coordinate in "
            + "the fact base, so no fixture can land a coordinate carrying the Count member; "
            + "R562 owns the synthesised-fields-as-coordinates model question."),
        OperationMember.Facet.class, new Exemption.Unimplemented(
            SYNTHESISED_CONNECTION_FIELDS_NOT_COORDINATES,
            "A synthesised connection type's facets field is not a classified coordinate in "
            + "the fact base, so no fixture can land a coordinate carrying the Facet member; "
            + "R562 owns the synthesised-fields-as-coordinates model question."),
        OperationMember.Write.UpdateMatching.class, new Exemption.Unimplemented(
            "condition-matched UPDATE",
            "The condition-matched write verbs are declared ahead of implementation; the "
            + "validator-mirror-gaps item owns the per-arm verdicts."),
        OperationMember.Write.DeleteMatching.class, new Exemption.Unimplemented(
            "condition-matched DELETE",
            "The condition-matched write verbs are declared ahead of implementation; the "
            + "validator-mirror-gaps item owns the per-arm verdicts."),
        OperationMember.Write.Upsert.class, UPSERT_RETIRED);

    /**
     * Concrete {@link ChildField} leaves the corpus source-shape walk (top-level coordinates
     * plus the ridden {@code NestingField.nestedFields()} / {@code PivotSpec.slots()} lists)
     * does not observe. Empty: the walk currently reaches every leaf.
     */
    public static final Map<Class<?>, Exemption> NOT_CORPUS_COVERED = Map.of();

    /**
     * Launcher command arms ({@code LaunchSource} / {@code ResultShape}) no declared-and-agreeing
     * {@code @commits} corpus row reaches. Empty: the corpus's covered coordinates currently
     * reach every arm; the ratchet in {@link #assertHonoured} forces a newly reached arm off
     * this map the moment its declaration lands.
     */
    public static final Map<Class<?>, Exemption> LAUNCHER_COMMITMENT_GAPS = Map.of();

    /** The plain-jOOQ-record backing pair shares one row story, declared once for both keys. */
    private static final Exemption PLAIN_JOOQ_RECORD_PROJECTION_UNASSERTED =
        new Exemption.FixtureAbsent(
            "a @ProjectionFor-annotated projection assertion for the plain-jOOQ-record backing "
            + "pair (JooqRecordType / JooqRecordInputType)",
            "The classification is demonstrated by the corpus's plain-jOOQ-record example, but "
            + "no test asserts the LSP projection arm's payload for either leaf.");

    /**
     * Sealed leaves without a payload-asserting {@code @ProjectionFor} test under
     * {@code GraphitronSchemaBuilderTest}.
     */
    public static final Map<Class<?>, Exemption> NO_PROJECTION_REQUIRED = Map.ofEntries(
        Map.entry(ChildField.ErrorsField.class, ERRORS_FIELD_PENDING),
        Map.entry(GraphitronType.JooqRecordType.class, PLAIN_JOOQ_RECORD_PROJECTION_UNASSERTED),
        Map.entry(GraphitronType.JooqRecordInputType.class, PLAIN_JOOQ_RECORD_PROJECTION_UNASSERTED),
        Map.entry(GraphitronType.JavaRecordInputType.class, new Exemption.FixtureAbsent(
            "an @input-only fixture exercising the Java-record input permit in isolation under "
            + "the default catalog",
            "Input-side Java record backing lands via the same TestRecordDto class as "
            + "JavaRecordType; the projector arm covers both sides, so no standalone snapshot "
            + "entry exists for the input permit to assert on.")),
        Map.entry(GraphitronType.JooqTableRecordInputType.class, new Exemption.FixtureAbsent(
            "a standalone projection-asserting fixture for the input-side jOOQ TableRecord "
            + "backing under the default catalog",
            "Covered structurally by the codegen tier, but no @ProjectionFor-annotated method "
            + "asserts the input permit's projection payload.")),
        Map.entry(ChildField.PivotSlotField.class, new Exemption.WalkerGap(
            PROJECTION_WALKER,
            GraphitronSchemaBuilderTest.class,
            "A @pivot projection slot rides the consuming leaf's PivotSpec.slots(); its "
            + "classification is demonstrated by the pivot cases and the corpus walk's descent, "
            + "but no @ProjectionFor-annotated method asserts a slot projection payload. The "
            + "emit-side pivot/nesting wiring-key defect is filed as its own item and is not "
            + "this row's blocker.")),
        Map.entry(InputField.ColumnBackedField.class, new Exemption.WalkerGap(
            PROJECTION_WALKER,
            GraphitronSchemaBuilderTest.class,
            "The composite same-table @nodeId filter cases demonstrate classification on the "
            + "enum truth table (enum constants carry no method annotation), and no "
            + "@ProjectionFor-annotated method asserts the leaf's projection payload.")),
        Map.entry(InputField.ColumnBackedReferenceField.class, new Exemption.WalkerGap(
            PROJECTION_WALKER,
            GraphitronSchemaBuilderTest.class,
            "The FK-target @nodeId and @reference input cases demonstrate classification on the "
            + "enum truth table (enum constants carry no method annotation), and no "
            + "@ProjectionFor-annotated method asserts the leaf's projection payload.")),
        Map.entry(ChildField.SingleRecordIdFieldFromReturning.class, new Exemption.WalkerGap(
            PROJECTION_WALKER,
            MutationDmlNodeIdClassificationTest.class,
            "The payload-returning DELETE data field is demonstrated by the admission matrix "
            + "and by the corpus's DELETE-payload example, but no @ProjectionFor-annotated "
            + "method asserts its projection payload.")));

    // ===== Domain and covered-set derivations =====

    /**
     * The leaves the corpus owns as single source of truth: every {@link OutputField} leaf and
     * every {@link GraphitronType} leaf except the failure leaf {@code UnclassifiedType}.
     * Input-field leaves and {@code UnclassifiedField} are deliberately excluded: the corpus
     * asserts successful classification only, and input-side truth stays on the enum table.
     */
    private static Set<Class<?>> corpusOwnedLeaves() {
        var leaves = new HashSet<>(GeneratorCoverageTest.sealedLeaves(OutputField.class));
        GeneratorCoverageTest.sealedLeaves(GraphitronType.class).stream()
            .filter(l -> l != GraphitronType.UnclassifiedType.class)
            .forEach(leaves::add);
        return leaves;
    }

    /**
     * All {@link ClassificationCase} constants across all enum types declared in
     * {@link GraphitronSchemaBuilderTest}, flattened to the leaf set they claim coverage for.
     */
    private static Set<Class<?>> enumCaseCoveredLeaves() {
        var covered = new HashSet<Class<?>>();
        Arrays.stream(GraphitronSchemaBuilderTest.class.getDeclaredClasses())
            .filter(Class::isEnum)
            .filter(ClassificationCase.class::isAssignableFrom)
            .flatMap(c -> Arrays.stream(c.getEnumConstants()))
            .map(ClassificationCase.class::cast)
            .flatMap(c -> c.variants().stream())
            .forEach(covered::add);
        return covered;
    }

    /** The {@link Operation} arm classes observed on the corpus's {@code @classified} coordinates. */
    private static Set<Class<?>> corpusObservedOperations() {
        var ops = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                ops.add(fc.actual().operation());
            }
        }
        return ops;
    }

    /**
     * The {@link OperationMember} leaf arms the corpus demonstrates at member grain: every arm
     * in a declared {@code operations:} list that agrees with the produced member rows at its
     * coordinate (the {@code corpusCommittedLauncherArms()} agreement-gate shape: declaration
     * alone claims nothing, production alone claims nothing).
     */
    private static Set<Class<?>> corpusDeclaredMemberArms() {
        var covered = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            for (var fc : ClassifiedHarness.classify(example.sdl()).fields()) {
                if (fc.expected().operations().equals(fc.actual().operations())) {
                    covered.addAll(fc.expected().operations());
                }
            }
        }
        return covered;
    }

    /**
     * The concrete {@link ChildField} leaf classes the corpus source-shape walk observes:
     * every classified child field in every fixture schema, descending the ridden lists
     * ({@code NestingField.nestedFields()}, {@code PivotSpec.slots()}).
     */
    private static Set<Class<?>> corpusObservedChildFieldLeaves() {
        var covered = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            var schema = ClassifiedHarness.classify(example.sdl()).schema();
            schema.fields().values().forEach(f -> {
                if (f instanceof ChildField c) {
                    ClassifiedHarness.forEachWithRiddenFields(c, r -> covered.add(r.getClass()));
                }
            });
        }
        return covered;
    }

    /**
     * The launcher command arms the corpus demonstrates: every {@code LaunchSource} and
     * {@code ResultShape} arm reached by a {@code @commits} declaration that agrees with the
     * produced relation row at its coordinate (the {@code coveredLeaves()} agreement-gate
     * shape: declaration alone claims nothing, production alone claims nothing).
     */
    private static Set<Class<?>> corpusCommittedLauncherArms() {
        var sourceArms = new java.util.HashMap<String, Class<?>>();
        GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.LaunchSource.class)
            .forEach(c -> sourceArms.put(c.getSimpleName(), c));
        var resultArms = new java.util.HashMap<String, Class<?>>();
        GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.ResultShape.class)
            .forEach(c -> resultArms.put(c.getSimpleName(), c));
        var covered = new HashSet<Class<?>>();
        for (var example : ClassifiedCorpus.examples()) {
            var result = ClassifiedHarness.classify(example.sdl());
            var production = ClassifiedHarness.launcherProductions().get(example.id());
            for (var cc : ClassifiedHarness.commitCases(result, production)) {
                if (cc.declaredSource().equals(cc.producedSource())
                        && cc.declaredResult().equals(cc.producedResult())) {
                    covered.add(sourceArms.get(cc.declaredSource()));
                    covered.add(resultArms.get(cc.declaredResult()));
                }
            }
        }
        return covered;
    }

    /** All sealed-leaf classes named by any {@code @ProjectionFor} annotation in the truth table. */
    private static Set<Class<?>> projectionForCoveredLeaves() {
        var covered = new HashSet<Class<?>>();
        for (var method : GraphitronSchemaBuilderTest.class.getDeclaredMethods()) {
            var pf = method.getAnnotation(no.sikt.graphitron.rewrite.catalog.ProjectionFor.class);
            if (pf != null) {
                covered.addAll(Arrays.asList(pf.value()));
            }
        }
        return covered;
    }

    private static Set<Class<?>> allModelLeaves() {
        var leaves = new HashSet<Class<?>>(GeneratorCoverageTest.sealedLeaves(GraphitronField.class));
        leaves.addAll(GeneratorCoverageTest.sealedLeaves(GraphitronType.class));
        return leaves;
    }

    // ===== The obligations =====

    public static final Obligation VARIANT_COVERAGE_OUTPUT = new Obligation(
        "variant-coverage: output-field and type leaves vs the corpus walk",
        memo(ExemptionRegistry::corpusOwnedLeaves),
        memo(ClassifiedCorpus::coveredLeaves),
        CORPUS_NO_CASE_REQUIRED);

    public static final Obligation VARIANT_COVERAGE_INPUT = new Obligation(
        "variant-coverage: input-field leaves vs the enum truth table",
        memo(() -> GeneratorCoverageTest.sealedLeaves(InputField.class)),
        memo(ExemptionRegistry::enumCaseCoveredLeaves),
        ENUM_NO_CASE_REQUIRED);

    public static final Obligation OPERATION_ARMS = new Obligation(
        "classified-dsl: operation arms vs corpus-observed operations",
        memo(() -> GeneratorCoverageTest.sealedLeaves(Operation.class)),
        memo(ExemptionRegistry::corpusObservedOperations),
        OPERATION_KNOWN_GAPS);

    /**
     * The member-grain operation obligation, {@link #OPERATION_ARMS}' successor: every
     * {@link OperationMember} leaf arm must be reached by a declared-and-agreeing
     * {@code operations:} corpus row or carry a typed exemption.
     */
    public static final Obligation MEMBER_ARMS = new Obligation(
        "classified-dsl: operation-member arms vs declared-and-agreeing member rows",
        memo(() -> GeneratorCoverageTest.sealedLeaves(OperationMember.class)),
        memo(ExemptionRegistry::corpusDeclaredMemberArms),
        MEMBER_KNOWN_GAPS);

    public static final Obligation SOURCE_SHAPE_CORPUS = new Obligation(
        "source-shape projection: ChildField leaves vs the corpus walk",
        memo(() -> GeneratorCoverageTest.sealedLeaves(ChildField.class)),
        memo(ExemptionRegistry::corpusObservedChildFieldLeaves),
        NOT_CORPUS_COVERED);

    /**
     * The launcher commitment obligation: every {@code LaunchSource} and {@code ResultShape}
     * arm must be reached by a declared-and-agreeing {@code @commits} corpus row or carry a
     * typed exemption. {@code ResultShape.LoaderDelegated}'s coverage is entailed by the
     * service source arms through the {@code LauncherCommand} compact constructor's
     * biconditional (service source iff {@code LoaderDelegated}), so that cell is not an
     * independent witness: it arrives with {@code ServiceCall} / {@code ServiceTableLift} and
     * cannot be reached without them.
     */
    public static final Obligation LAUNCHER_COMMITMENT = new Obligation(
        "launcher-commitment: LaunchSource and ResultShape arms vs declared-and-agreeing @commits rows",
        memo(() -> {
            var domain = new HashSet<Class<?>>(
                GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.LaunchSource.class));
            domain.addAll(GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.ResultShape.class));
            return domain;
        }),
        memo(ExemptionRegistry::corpusCommittedLauncherArms),
        LAUNCHER_COMMITMENT_GAPS);

    public static final Obligation LSP_PROJECTION = new Obligation(
        "lsp-projection: sealed leaves vs @ProjectionFor assertions",
        memo(ExemptionRegistry::allModelLeaves),
        memo(ExemptionRegistry::projectionForCoveredLeaves),
        NO_PROJECTION_REQUIRED);

    /**
     * The corpus-backed rows, asserted by {@code ExemptionRegistryTest}'s parameterized
     * meta-test at pipeline tier. {@link #LSP_PROJECTION} is deliberately absent: its covered
     * set is annotation-derived, needs no classification, and is asserted at unit tier by
     * {@code ProjectionCoverageTest}.
     */
    public static List<Obligation> corpusObligations() {
        return List.of(VARIANT_COVERAGE_OUTPUT, VARIANT_COVERAGE_INPUT, OPERATION_ARMS,
            MEMBER_ARMS, SOURCE_SHAPE_CORPUS, LAUNCHER_COMMITMENT);
    }

    /** All rows, the discovery guard's registration authority. */
    public static List<Obligation> obligations() {
        return List.of(VARIANT_COVERAGE_OUTPUT, VARIANT_COVERAGE_INPUT, OPERATION_ARMS,
            MEMBER_ARMS, SOURCE_SHAPE_CORPUS, LAUNCHER_COMMITMENT, LSP_PROJECTION);
    }

    private static <T> Supplier<T> memo(Supplier<T> s) {
        return new Supplier<>() {
            private T value;
            @Override public synchronized T get() {
                if (value == null) {
                    value = s.get();
                }
                return value;
            }
        };
    }
}
