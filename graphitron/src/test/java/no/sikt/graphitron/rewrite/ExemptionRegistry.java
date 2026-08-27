package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusExpectations;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OutputField;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exemption-obligation registry: every coverage obligation that carries a typed
 * {@link Exemption} map is declared here as one {@link Obligation} row pairing the obligation's
 * domain, its covered-set derivation, and its exemption map. The row is the unit the shared
 * {@link #assertHonoured} helper checks: every exemption key is in-domain, every exemption key is
 * still uncovered (the covered-entry-must-be-removed ratchet that forces a closed row off the
 * list), and every domain member is covered or exempt.
 *
 * <p>The rows are asserted by the parameterized meta-test in
 * {@code ExemptionRegistryTest} (pipeline tier). It also carries the
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
        // Restrict the covered set to the domain's vocabulary once, here, so an instrument
        // that observes more than the row's domain (a reflective scan spanning hierarchies)
        // cannot inflate the counts in the failure message below, and no per-row derivation
        // needs its own in-domain filter kept in sync with the domain supplier.
        Set<Class<?>> covered = o.covered().get().stream()
            .filter(domain::contains)
            .collect(Collectors.toSet());

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

    /** The UPSERT retirement; the blocker names the live rejection that would have to lift. */
    private static final Exemption UPSERT_RETIRED = new Exemption.Unimplemented(
        "lifting the MutationInputResolver UPSERT rejection (UPSERT generation is retired)",
        "The classifier rejects every UPSERT mutation upstream, so no schema-reachable case can "
        + "mint the Write.Upsert member arm.");

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
     * {@link OperationMember} leaf arms no declared-and-agreeing {@code operations:} corpus row
     * reaches: the five modeled-but-unpopulated arms plus the retired UPSERT, each with its
     * blocker story. An arm leaves this map the moment a fixture declares-and-agrees on it (the
     * ratchet); an unreached arm not listed here fails the completeness check.
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
     * Launcher command arms ({@code LaunchSource} / {@code ResultShape}) no corpus
     * {@code plan_launcher_command} block spells. Empty: the corpus's declared rows currently
     * reach every arm; the ratchet in {@link #assertHonoured} forces a newly reached arm off
     * this map the moment its declaration lands.
     */
    public static final Map<Class<?>, Exemption> LAUNCHER_COMMITMENT_GAPS = Map.of();

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

    /**
     * The {@link OperationMember} leaf arms the corpus demonstrates at member grain: every arm
     * in a declared {@code operations:} list that agrees with the produced member rows at its
     * coordinate (the {@code corpusCommittedLauncherArms()} agreement-gate shape: declaration
     * alone claims nothing, production alone claims nothing).
     */
    private static Set<Class<?>> corpusDeclaredMemberArms() {
        var covered = new HashSet<Class<?>>();
        for (var example : CorpusDocuments.documents()) {
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
        for (var example : CorpusDocuments.documents()) {
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
     * {@code ResultShape} arm a document's {@code plan_launcher_command} block spells.
     *
     * <p>The agreement half is not restated here, it is enforced elsewhere and this reads the
     * declaration alone on purpose. A block asserts set equality with the produced relation in
     * both directions, per document, and {@code CorpusExpectationTest} is what checks it against
     * a real production; a token that names no arm cannot survive that check, because no row
     * carries it. So the witness a declared arm gives is worth what it was worth when a
     * coordinate directive carried both sides at once, with the two halves now sitting in the
     * gates that own them rather than in one loop.
     *
     * <p>Tokens no arm answers to are dropped rather than counted as a null member: the coverage
     * comparison is over arm classes, and a typo is the corpus expectation's failure to report,
     * not this map's.
     */
    private static Set<Class<?>> corpusCommittedLauncherArms() {
        var arms = new java.util.HashMap<String, Class<?>>();
        GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.LaunchSource.class)
            .forEach(c -> arms.put(c.getSimpleName(), c));
        GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.ResultShape.class)
            .forEach(c -> arms.put(c.getSimpleName(), c));
        var csvReader = org.jooq.impl.DSL.using(org.jooq.SQLDialect.H2);
        var covered = new HashSet<Class<?>>();
        for (var example : CorpusDocuments.documents()) {
            for (var block : CorpusExpectations.declaredBlocks(csvReader, example.id(), example.sdl())) {
                if (!block.relation().equalsIgnoreCase(CorpusExpectations.LAUNCHER_COMMAND_RELATION)) {
                    continue;
                }
                for (var column : List.of("source", "result")) {
                    int index = block.columns().indexOf(column);
                    if (index < 0) {
                        continue;
                    }
                    block.rows().stream()
                        .map(row -> arms.get(row.get(index)))
                        .filter(java.util.Objects::nonNull)
                        .forEach(covered::add);
                }
            }
        }
        return covered;
    }

    // ===== The obligations =====

    public static final Obligation VARIANT_COVERAGE_OUTPUT = new Obligation(
        "variant-coverage: output-field and type leaves vs the corpus walk",
        memo(ExemptionRegistry::corpusOwnedLeaves),
        memo(CorpusDocuments::coveredLeaves),
        CORPUS_NO_CASE_REQUIRED);

    public static final Obligation VARIANT_COVERAGE_INPUT = new Obligation(
        "variant-coverage: input-field leaves vs the enum truth table",
        memo(() -> GeneratorCoverageTest.sealedLeaves(InputField.class)),
        memo(ExemptionRegistry::enumCaseCoveredLeaves),
        ENUM_NO_CASE_REQUIRED);

    /**
     * The member-grain operation obligation: every {@link OperationMember} leaf arm must be
     * reached by a declared-and-agreeing {@code operations:} corpus row or carry a typed
     * exemption.
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
     * arm must be spelled by a corpus {@code plan_launcher_command} block or carry a typed
     * exemption. This is also what the retired SDL enums mirroring the two seals used to buy,
     * now stated once: an arm added to either seal and exercised by no document fails here,
     * where before it failed a mirror floor against an enum a document had to spell.
     * {@code ResultShape.LoaderDelegated}'s coverage is entailed by the
     * service source arms through the {@code LauncherCommand} compact constructor's
     * biconditional (service source iff {@code LoaderDelegated}), so that cell is not an
     * independent witness: it arrives with {@code ServiceCall} / {@code ServiceTableLift} and
     * cannot be reached without them.
     */
    public static final Obligation LAUNCHER_COMMITMENT = new Obligation(
        "launcher-commitment: LaunchSource and ResultShape arms vs the corpus's declared launcher rows",
        memo(() -> {
            var domain = new HashSet<Class<?>>(
                GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.LaunchSource.class));
            domain.addAll(GeneratorCoverageTest.sealedLeaves(no.sikt.graphitron.command.ResultShape.class));
            return domain;
        }),
        memo(ExemptionRegistry::corpusCommittedLauncherArms),
        LAUNCHER_COMMITMENT_GAPS);

    /**
     * All rows: the discovery guard's registration authority, and the subjects of
     * {@code ExemptionRegistryTest}'s parameterized meta-test at pipeline tier. Every row is
     * corpus-backed, so one list serves both and a new row cannot reach the registry without
     * landing in the sweep.
     */
    public static List<Obligation> obligations() {
        return List.of(VARIANT_COVERAGE_OUTPUT, VARIANT_COVERAGE_INPUT, MEMBER_ARMS,
            SOURCE_SHAPE_CORPUS, LAUNCHER_COMMITMENT);
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
