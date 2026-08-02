package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.AccessorProbe;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis;
import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.rewrite.model.DeleteRowsError;
import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.DomainReturnType;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError;
import no.sikt.graphitron.rewrite.model.ErrorsSlot;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HasInputRecordShape;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.MutationTableArgError;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.ParticipantCorrelation;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.PayloadConstructionShape;
import no.sikt.graphitron.rewrite.model.PivotError;
import no.sikt.graphitron.rewrite.model.ProducerBinding;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReflectionError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RootField;
import no.sikt.graphitron.rewrite.model.ScalarResolution;
import no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.model.ServiceMethodCallError;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.UpdateRowsError;
import no.sikt.graphitron.rewrite.model.ValueLocator;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.model.WireCoercionError;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hierarchy-kind labelling: every sealed hierarchy in the fact base declares which of the
 * four kinds it is, sorted by how a row comes to exist rather than by subject matter. The
 * registry is the vocabulary the command migration is phrased in, and the coverage test below
 * keeps it total: a new hierarchy cannot land unlabelled, and a retired one cannot linger.
 *
 * <ul>
 *   <li>{@link HierarchyKind#WALKED_FACT}: read off the SDL, the catalog, or the consumer's
 *       reflected Java surface by a traversal.</li>
 *   <li>{@link HierarchyKind#RESOLVED_VIEW}: a coalesce or inference over facts, with no walk of
 *       its own.</li>
 *   <li>{@link HierarchyKind#COMMAND}: minted at emit grain from facts; describes what the emit
 *       does rather than what the schema means.</li>
 *   <li>{@link HierarchyKind#ERROR_CHANNEL}: minted by the gathering pass rather than read off a
 *       traversal; located violations (and advisories) asserted once and rendered into views.</li>
 * </ul>
 *
 * <p>The four kinds are not a closed partition: they are cells of a provenance-by-phase product,
 * and the cell "derived view at emit grain" (edge views over commands, the recompile graph as a
 * projection) is expected to join the enum when the first such hierarchy exists. Adding it then
 * is the taxonomy filling in, not drifting.
 *
 * <p><b>Scope.</b> The scan covers the fact base: top-level sealed types under
 * {@code rewrite/model} and {@code command} (which holds the pure-data command records; no
 * sealed type yet, so the walk is armed for the arms the projection command brings), plus
 * {@link BuildWarning} by name, the error channel's non-fatal half, which lives in the core
 * package among gathering scaffolding. Excluded by principle, not by accident: builder-internal
 * result channels ({@code FieldBuilder}'s nested seals, the resolver {@code Resolved} families)
 * are gathering scaffolding discarded before the model exists; {@code catalog/}'s projections
 * are views over the model, not hierarchies in it; {@code plan/} internals are producer
 * scaffolding by the same rule, and so is {@code facts/}'s visitor contract (the gathered
 * relations themselves are plain records, not sealed hierarchies; the sealed visitor set is the
 * engine's registration machinery). Nested seals inherit their top-level root's kind.
 *
 * <p>Each entry is one judgment call, stated so it can be corrected in one line; the enforced
 * property is coverage, not the infallibility of any single label.
 */
@UnitTier
class HierarchyKindRegistryTest {

    enum HierarchyKind { WALKED_FACT, RESOLVED_VIEW, COMMAND, ERROR_CHANNEL }

    /**
     * {@link GraphitronType}'s synthesised permits: command outputs stored in the fact map, the
     * one place the walked label is knowingly impure. The facet pair has no SDL declaration at
     * all; the connection triple is dual-provenance (synthesised by {@code @asConnection},
     * walked when authored structurally). Sourced live from the connection-synthesis relation's
     * declared minted-arm vocabulary: the relation's producer is the single producer of exactly
     * these permits, and {@link ConnectionSynthesis.MintedName} enforces the vocabulary at
     * construction, so a sixth synthesised type must widen the declaration before it can mint.
     */
    static final Set<Class<? extends GraphitronType>> SYNTHESISED_TYPE_PERMITS =
        ConnectionSynthesis.MINTED_ARM_VOCABULARY;

    private static final Map<Class<?>, HierarchyKind> REGISTRY = Map.ofEntries(
        // The classification hierarchies: walked off the SDL against the catalog.
        Map.entry(GraphitronField.class, HierarchyKind.WALKED_FACT),
        Map.entry(OutputField.class, HierarchyKind.WALKED_FACT),
        Map.entry(RootField.class, HierarchyKind.WALKED_FACT),
        Map.entry(ChildField.class, HierarchyKind.WALKED_FACT),
        Map.entry(QueryField.class, HierarchyKind.WALKED_FACT),
        Map.entry(MutationField.class, HierarchyKind.WALKED_FACT),
        Map.entry(InputField.class, HierarchyKind.WALKED_FACT),
        // Walked, with the synthesised-permit impurity carried in SYNTHESISED_TYPE_PERMITS.
        Map.entry(GraphitronType.class, HierarchyKind.WALKED_FACT),
        // The three classification axes and their shapes.
        Map.entry(Source.class, HierarchyKind.WALKED_FACT),
        Map.entry(Target.class, HierarchyKind.WALKED_FACT),
        Map.entry(TargetShape.class, HierarchyKind.WALKED_FACT),
        Map.entry(FieldWrapper.class, HierarchyKind.WALKED_FACT),
        // Read off directives, the catalog, or the reflected consumer surface.
        Map.entry(TenantBinding.class, HierarchyKind.WALKED_FACT),
        Map.entry(TenantScopes.class, HierarchyKind.WALKED_FACT),
        Map.entry(ScalarResolution.class, HierarchyKind.WALKED_FACT),
        Map.entry(ProducerBinding.class, HierarchyKind.WALKED_FACT),
        Map.entry(AccessorProbe.class, HierarchyKind.WALKED_FACT),
        Map.entry(ParamSource.class, HierarchyKind.WALKED_FACT),
        Map.entry(ValueShape.class, HierarchyKind.WALKED_FACT),
        // Walker carriers for the DML directive walks.
        Map.entry(DeleteRows.class, HierarchyKind.WALKED_FACT),
        Map.entry(UpdateRows.class, HierarchyKind.WALKED_FACT),
        Map.entry(MatchedKey.class, HierarchyKind.WALKED_FACT),
        // Capability seals over walked leaves.
        Map.entry(LookupField.class, HierarchyKind.WALKED_FACT),
        Map.entry(HasInputRecordShape.class, HierarchyKind.WALKED_FACT),

        // Coalesces and inferences over facts, with no walk of their own.
        Map.entry(JoinStep.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(JoinSlot.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(On.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(TableExpr.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(ParentCorrelation.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(ParticipantCorrelation.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(ParticipantRef.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(MethodRef.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(AccessorResolution.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(KeyAlternative.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(ReturnTypeRef.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(ValueLocator.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(ErrorsSlot.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(DialectRequirement.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(InputColumnBinding.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(InputColumnBindingGroup.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(DomainReturnType.class, HierarchyKind.RESOLVED_VIEW),
        // The connection-synthesis row: produced during the classify walk but not itself an
        // authored SDL fact; it coalesces the carrier's directive facts, the pagination
        // resolution and the assembled schema's name presence into one resolved per-coordinate
        // product, with no walk of its own (it rides the classify walk's visits).
        Map.entry(ConnectionSynthesis.class, HierarchyKind.RESOLVED_VIEW),
        // The operation member view: the per-coordinate join of the operation-trigger facts
        // (today a projection over the classified leaves, ConnectionSynthesisRelation's
        // precedent for a resolved per-coordinate product), with no walk of its own. The
        // nested Write seal inherits this kind: the verb payloads it carries are trigger-fact
        // references the view realizes, not emit-grain mints.
        Map.entry(OperationMember.class, HierarchyKind.RESOLVED_VIEW),

        // Minted at emit grain: these describe what the emit does. The commands-in-waiting.
        Map.entry(no.sikt.graphitron.command.Predicate.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.ProjectionCommand.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.Contribution.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.CallWrap.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.SelectTerm.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.Ordering.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.ResultShape.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.Invocation.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.TenantStrategy.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.LaunchSource.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.TypeUnitCommand.class, HierarchyKind.COMMAND),
        Map.entry(no.sikt.graphitron.command.GlobalCommand.class, HierarchyKind.COMMAND),
        // Re-labelled COMMAND -> RESOLVED_VIEW at the keystone: the summary column is a derived
        // view over the coordinate's member rows (the payload-mirroring pin holds them equal),
        // not an emit-grain mint; its arm payloads are the same trigger-fact references the
        // member arms carry. The COMMAND label predated the member relation and encoded the
        // one-arm-per-coordinate reading the operation relation dissolves.
        Map.entry(Operation.class, HierarchyKind.RESOLVED_VIEW),
        Map.entry(BodyParam.class, HierarchyKind.COMMAND),
        Map.entry(DmlReturnExpression.class, HierarchyKind.COMMAND),
        Map.entry(CallSiteExtraction.class, HierarchyKind.COMMAND),
        Map.entry(CallSiteCompaction.class, HierarchyKind.COMMAND),
        Map.entry(OrderBySpec.class, HierarchyKind.COMMAND),
        Map.entry(KeyLift.class, HierarchyKind.COMMAND),
        Map.entry(PayloadConstructionShape.class, HierarchyKind.COMMAND),
        Map.entry(LookupMapping.class, HierarchyKind.COMMAND),
        Map.entry(ServiceMethodCall.class, HierarchyKind.COMMAND),
        Map.entry(MappingEntry.class, HierarchyKind.COMMAND),
        Map.entry(HelperRef.class, HierarchyKind.COMMAND),
        Map.entry(WhereFilter.class, HierarchyKind.COMMAND),
        Map.entry(ErrorChannel.class, HierarchyKind.COMMAND),

        // The walk's error channel: minted by the gathering pass, keyed by location plus code.
        Map.entry(Rejection.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(DeleteRowsError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(ErrorChannelWalkerError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(MutationTableArgError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(PivotError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(ReflectionError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(ServiceCarrierShapeError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(ServiceMethodCallError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(UpdateRowsError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(WireCoercionError.class, HierarchyKind.ERROR_CHANNEL),
        Map.entry(WalkerResult.class, HierarchyKind.ERROR_CHANNEL),
        // The non-fatal half of the channel, enumerated by name (see the scope note above).
        Map.entry(BuildWarning.class, HierarchyKind.ERROR_CHANNEL)
    );

    /** Package roots whose top-level sealed types the registry must cover. */
    private static final Set<String> SCANNED_PACKAGE_DIRS = Set.of(
        "no/sikt/graphitron/rewrite/model",
        "no/sikt/graphitron/command");

    private static final Set<Class<?>> NAMED_EXTRAS = Set.of(BuildWarning.class);

    @Test
    void everyFactBaseHierarchyIsLabelledExactlyOnce() throws IOException {
        var discovered = new HashSet<Class<?>>(NAMED_EXTRAS);
        Path classesRoot = Path.of("target/classes");
        for (String pkg : SCANNED_PACKAGE_DIRS) {
            Path dir = classesRoot.resolve(pkg);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path classFile : files.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.getFileName().toString().contains("$")).toList()) {
                    String className = classesRoot.relativize(classFile).toString()
                        .replace(".class", "").replace(java.io.File.separatorChar, '.');
                    Class<?> clazz;
                    try {
                        clazz = Class.forName(className);
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        continue;
                    }
                    if (clazz.isSealed() && clazz.getEnclosingClass() == null) {
                        discovered.add(clazz);
                    }
                }
            }
        }

        var unlabelled = new TreeSet<String>();
        discovered.stream().filter(c -> !REGISTRY.containsKey(c))
            .map(Class::getSimpleName).forEach(unlabelled::add);
        var stale = new TreeSet<String>();
        REGISTRY.keySet().stream().filter(c -> !discovered.contains(c))
            .map(Class::getSimpleName).forEach(stale::add);

        assertThat(unlabelled)
            .as("fact-base hierarchies without a kind label; add each to the registry with the "
                + "kind its provenance earns (how does a row come to exist?)")
            .isEmpty();
        assertThat(stale)
            .as("registry entries no longer discovered as top-level sealed types in scope; "
                + "remove or re-home them")
            .isEmpty();
        assertThat(discovered.size())
            .as("hierarchies discovered (scan must not be vacuous)")
            .isGreaterThan(60);
    }

    @Test
    void synthesisedPermitsAreGraphitronTypeLeaves() {
        assertThat(SYNTHESISED_TYPE_PERMITS)
            .allSatisfy(c -> assertThat(GraphitronType.class)
                .as("%s must be a GraphitronType member", c.getSimpleName())
                .isAssignableFrom(c));
    }
}
