package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.ProjectionCommand;
import no.sikt.graphitron.command.SelectTerm;
import no.sikt.graphitron.command.TermAlias;
import no.sikt.graphitron.command.UnitMethodRef;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DeliveryFact;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParentRowDemand;
import no.sikt.graphitron.rewrite.model.ResultKeyAliasedField;
import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produces the projection command relation: one {@link ProjectionCommand} row per projection
 * unit. Anchor units are minted for every {@code TableType} / {@code NodeType}; nested units for
 * every {@code (anchor, nesting type)} reach; pivot units for every {@code @pivot}-bearing
 * coordinate (inline and batched delivery alike, since both hosts consume the same unit).
 *
 * <p>The table-target family's contribution fork reads the operation members and the delivery
 * fact (the serviceCall member and batched delivery route to correlation-key arms, the lookup
 * member picks the multiset wrap); the payload extraction inside still reads the leaf-carried
 * resolutions, the additive window's sanctioned half, and the renderer never notices.
 *
 * <p><b>Membership census.</b> {@link #CONTRIBUTION_MINTING_LEAVES} declares the leaf kinds this
 * producer mints projection output for, adjacent to the dispatch that implements it; the
 * projection census test validates the declaration against observed minting in both directions,
 * so the set cannot drift from the switch the way a hand-maintained restatement could.
 *
 * <p><b>Address census.</b> Prefixed unit names ({@code <Anchor><Nested>},
 * {@code <Parent><Field>}) can collide with an authored type's unit or with each other; every
 * mint passes through a case-folded address census that fails production on a duplicate address
 * with diverging rows. {@link #addressCollisions} is the validator's mirror of that census, so
 * an authored collision fails validation with a located error before production runs.
 */
public final class ProjectionCommands {

    private ProjectionCommands() {}

    /**
     * The leaf kinds the producer mints projection output for: a contribution on the walked
     * unit's own row, a unit row of its own ({@code PivotField} / {@code BatchedPivotField}
     * mint their coordinate's pivot unit), or slot contributions ridden into a pivot unit's row
     * ({@code PivotSlotField}). The leaves whose fetchers read parent-row columns off
     * {@code env.getSource()} (every {@link no.sikt.graphitron.rewrite.model.BatchKeyField} /
     * {@link no.sikt.graphitron.rewrite.model.ParentRowDemand} implementer in the
     * {@link ChildField} seal) belong here because their correlation keys are gated
     * {@link Contribution.Project} arms: project the key columns when the child is selected,
     * nothing when it is not (the fetcher only runs for a selected field, so supply meets
     * demand). The projection membership census enforces that capability-to-membership edge
     * mechanically. Everything else lands no projection output; a leaf in both this set and the
     * fetcher dispatch's implemented set projects <em>and</em> gets a fetcher method (the
     * dual-arm kinds the census test pins explicitly).
     */
    public static final Set<Class<? extends GraphitronField>> CONTRIBUTION_MINTING_LEAVES = Set.of(
        ChildField.ColumnBackedField.class,
        ChildField.ColumnBackedReferenceField.class,
        ChildField.TableField.class,
        ChildField.NestingField.class,
        ChildField.ComputedField.class,
        ChildField.PivotField.class,
        ChildField.BatchedPivotField.class,
        ChildField.PivotSlotField.class,
        ChildField.BatchedTableField.class,
        ChildField.ServiceTableField.class,
        ChildField.ServiceRecordField.class,
        ChildField.TableInterfaceField.class,
        ChildField.InterfaceField.class,
        ChildField.UnionField.class,
        ChildField.BatchedInterfaceField.class,
        ChildField.BatchedUnionField.class
    );

    public static ProjectionRelation produce(GraphitronSchema schema, ConditionRelation conditions,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var glueEnvByMethod = conditions.rows().stream()
            .collect(Collectors.toMap(row -> row.glue(), row -> row.readsRequestContext()));
        var census = new AddressCensus();
        for (var typeName : schema.types().keySet().stream().sorted().toList()) {
            if (!(schema.type(typeName) instanceof GraphitronType.TableBackedType type)
                    || !(type instanceof GraphitronType.TableType || type instanceof GraphitronType.NodeType)) {
                continue;
            }
            var anchor = units.typeClass(typeName);
            var contributions = collectContributions(
                schema, schema.fieldsOf(typeName), anchor, typeName, units, glueEnvByMethod,
                census, false);
            census.add(new ProjectionCommand.AnchorUnit(anchor, type.table(), contributions),
                "table-backed type '" + typeName + "'");
        }
        return new ProjectionRelation(census.rows());
    }

    // ------------------------------------------------------------------------------------------
    // Contribution dispatch
    // ------------------------------------------------------------------------------------------

    /**
     * Maps each walked field to its contribution (empty for the leaf kinds that project
     * nothing), side-minting the nested and pivot unit rows the walk discovers. The switch is
     * exhaustive over {@link ChildField} with no default arm, so a new leaf fails compilation
     * here until it declares its projection verdict; a leaf whose projection the producer cannot
     * yet mint surfaces as a validate-time rejection, never a silent skip.
     */
    private static List<Contribution> collectContributions(GraphitronSchema schema,
            List<? extends GraphitronField> fields,
            UnitRef owner, String anchorTypeName, GeneratedUnits units,
            Map<UnitMethodRef, Boolean> glueEnvByMethod, AddressCensus census, boolean nested) {
        var contributions = new ArrayList<Contribution>();
        for (var field : fields) {
            if (field instanceof GraphitronField.UnclassifiedField) {
                continue; // carries a rejection; the validator fails the build before production
            }
            if (!(field instanceof ChildField cf)) {
                throw new IllegalStateException(
                    "non-child field '" + field.qualifiedName() + "' ("
                    + field.getClass().getSimpleName() + ") reached a projection-unit walk; "
                    + "root operation types are never projection units");
            }
            var contribution = contributionFor(schema, cf, owner, anchorTypeName, units,
                glueEnvByMethod, census, nested);
            contribution.ifPresent(c -> {
                requireAliasedWriteArm(cf, c);
                contributions.add(c);
            });
        }
        return contributions;
    }

    private static Optional<Contribution> contributionFor(GraphitronSchema schema, ChildField field,
            UnitRef owner, String anchorTypeName, GeneratedUnits units,
            Map<UnitMethodRef, Boolean> glueEnvByMethod, AddressCensus census, boolean nested) {
        return switch (field) {
            case ChildField.ColumnBackedField cf -> Optional.of(new Contribution.Project(cf.name(),
                cf.columns().stream()
                    .map(col -> (SelectTerm) new SelectTerm.Column(col, TermAlias.BY_COLUMN_IDENTITY))
                    .toList()));
            case ChildField.ColumnBackedReferenceField crf -> {
                if (crf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                    throw new IllegalStateException(
                        "inline ColumnBackedReferenceField '" + crf.qualifiedName() + "' with "
                        + "NodeIdEncodeKeys compaction must be rejected by the validator before "
                        + "production");
                }
                // Empty path: start table == target table, so the referenced column lives on
                // this unit's own table; the alias-by-result-key read is the one inherited
                // reader-uniformity alias the term slot keeps representable.
                yield Optional.of(new Contribution.Project(crf.name(), List.of(
                    crf.joinPath().isEmpty()
                        ? new SelectTerm.Column(crf.columns().get(0), TermAlias.BY_RESULT_KEY)
                        : new SelectTerm.ScalarSubselect(
                            crf.joinPath(), crf.parentCorrelation(), crf.columns().get(0)))));
            }
            // The table-target family's one arm: the delivery fork and the
            // Multiset-vs-LookupMultiset fork are member and delivery reads, never leaf
            // identity; the payload extraction inside keeps the sanctioned leaf reads.
            case ChildField.TableTargetField ttf ->
                tableTargetContribution(schema, ttf, owner, units, glueEnvByMethod, nested);
            case ChildField.NestingField nf -> {
                var nested2 = mintNestedUnit(schema, nf, anchorTypeName, units, glueEnvByMethod, census);
                yield Optional.of(new Contribution.Call(nf.name(), nested2, new CallWrap.Splice()));
            }
            case ChildField.ComputedField cmp -> Optional.of(new Contribution.Project(cmp.name(),
                List.of(new SelectTerm.HelperCall(cmp.method()))));
            case ChildField.PivotField pf -> {
                var pivotUnit = mintPivotUnit(pf, units, census);
                yield Optional.of(new Contribution.Call(pf.name(), pivotUnit,
                    new CallWrap.PivotMultiset(pf.pivot().table(), pf.spec().pairs())));
            }
            // The batched pivot delivers through the DataLoader seam (the projection itself is
            // still this coordinate's pivot unit, which the batched rows method consumes), so
            // the anchor's contribution is the correlation-key arm alone.
            case ChildField.BatchedPivotField bpf -> {
                mintPivotUnit(bpf, units, census);
                yield correlationKeyArm(bpf, bpf.sourceKey() == null
                    ? List.of() : bpf.sourceKey().columns());
            }
            // Slots mint their contributions inside their pivot unit's row, from
            // PivotSpec.slots(); a slot on a table-context walk is a classifier bug.
            case ChildField.PivotSlotField slot -> throw new IllegalStateException(
                "PivotSlotField '" + slot.qualifiedName() + "' reached a table-context projection "
                + "walk; slots live on the pivot projection type and mint inside their pivot unit");
            // Correlation-key arms: these leaves deliver their data through their own fetcher
            // methods (service delegations, polymorphic dispatch), but the fetchers extract
            // their key / correlation columns off the parent row, so the parent SELECT must
            // carry those columns exactly when the field is selected. The column list is read
            // from the same accessors the extraction emitters consume
            // (BatchKeyField.sourceKey(), ParentRowDemand.parentRowColumns()), so supply and
            // demand are one read, not two derivations to cross-check. A null sourceKey is a
            // no-Sources service method: plain per-parent delegation, nothing to project. The
            // table-target family's correlation-key arms live in the merged arm above.
            case ChildField.ServiceRecordField sr -> correlationKeyArm(sr, sr.sourceKey() == null
                ? List.of() : sr.sourceKey().columns());
            case ChildField.InterfaceField pif -> correlationKeyArm(pif, pif.parentRowColumns());
            case ChildField.UnionField uf -> correlationKeyArm(uf, uf.parentRowColumns());
            case ChildField.BatchedInterfaceField bif -> correlationKeyArm(bif, bif.parentRowColumns());
            case ChildField.BatchedUnionField buf -> correlationKeyArm(buf, buf.parentRowColumns());
            // No projection output: leaves with no parent-row read of any kind.
            case ChildField.ParticipantColumnReferenceField ignored -> Optional.empty();
            case ChildField.RecordReadField ignored -> Optional.empty();
            case ChildField.RecordCompositeField ignored -> Optional.empty();
            case ChildField.SingleRecordIdField ignored -> Optional.empty();
            case ChildField.SingleRecordIdFieldFromReturning ignored -> Optional.empty();
            case ChildField.ErrorsField ignored -> Optional.empty();
        };
    }

    /**
     * The table-target family's contribution, dispatched on the operation members, the
     * delivery fact and the parent-row-demand capability: a serviceCall member delegates
     * through its loader and projects the key columns; a batched delivery re-queries and
     * projects the key columns; the parent-row-demanding twin (the single-table interface
     * child) projects its demand columns; the inline remainder composes into the parent
     * statement, with the Multiset-vs-LookupMultiset fork read off the lookup member and the
     * mapping taken from that member's payload. Members come from the minted view for flat
     * coordinates and the leaf projection for nested instances (the member relation's domain
     * boundary); delivery reads the same split.
     */
    private static Optional<Contribution> tableTargetContribution(GraphitronSchema schema,
            ChildField.TableTargetField ttf, UnitRef owner, GeneratedUnits units,
            Map<UnitMethodRef, Boolean> glueEnvByMethod, boolean nested) {
        var members = nested
            ? OperationMembers.membersOf(ttf)
            : schema.operationMembersOf(ttf.parentTypeName(), ttf.name());
        var delivery = nested
            ? DeliveryFact.leafDerivedOf(ttf)
            : schema.deliveryOf(FieldCoordinates.coordinates(ttf.parentTypeName(), ttf.name()));
        boolean serviceCall = hasKind(members, OperationMember.Kind.SERVICE_CALL);
        if (serviceCall || delivery instanceof DeliveryFact.Batched) {
            var keyed = (BatchKeyField) ttf;
            return correlationKeyArm(ttf, keyed.sourceKey() == null
                ? List.of() : keyed.sourceKey().columns());
        }
        // The single-table interface child twin: inline delivery, but the discriminated
        // fetcher reads its correlation off the parent row, and the demand capability is the
        // fact (within the table-target seal, exactly the twin declares one).
        if (ttf instanceof ParentRowDemand demand) {
            return correlationKeyArm(ttf, demand.parentRowColumns());
        }
        var lookupMember = members.stream()
            .filter(m -> m instanceof OperationMember.Lookup)
            .map(m -> (OperationMember.Lookup) m)
            .findFirst();
        if (lookupMember.isPresent()) {
            var mapping = switch (lookupMember.get().lookupMapping()) {
                case LookupMapping.ColumnMapping cm -> cm;
            };
            return Optional.of(new Contribution.Call(ttf.name(),
                units.typeClass(ttf.returnType().returnTypeName()),
                new CallWrap.LookupMultiset(
                    ttf.joinPath(),
                    inlineParentCorrelationOf(ttf),
                    ttf.returnType().table(),
                    mapping,
                    units.inputRowsMethod(owner, ttf.name()),
                    glueFor(ttf.parentTypeName(), ttf.name(), ttf.filters(), units, glueEnvByMethod))));
        }
        return Optional.of(new Contribution.Call(ttf.name(),
            units.typeClass(ttf.returnType().returnTypeName()),
            new CallWrap.Multiset(
                ttf.joinPath(),
                inlineParentCorrelationOf(ttf),
                ttf.returnType().table(),
                ttf.returnType().wrapper() instanceof FieldWrapper.Single ? Arity.SINGLE : Arity.LIST,
                ttf.orderBy(),
                !(ttf.returnType().wrapper() instanceof FieldWrapper.Single)
                    && ttf.pagination() != null && ttf.pagination().first() != null,
                glueFor(ttf.parentTypeName(), ttf.name(), ttf.filters(), units, glueEnvByMethod),
                readsSelectedFieldArguments(ttf))));
    }

    /**
     * The inline table child's step-0 correlation: the one leaf that composes into the parent
     * statement carries it; a non-inline leaf reaching here is a delivery-fork bug surfaced
     * loudly.
     */
    private static no.sikt.graphitron.rewrite.model.ParentCorrelation inlineParentCorrelationOf(
            ChildField.TableTargetField ttf) {
        if (ttf instanceof ChildField.TableField tf) {
            return tf.parentCorrelation();
        }
        throw new IllegalStateException(
            "Graphitron generator bug (projection contribution): coordinate '"
            + ttf.qualifiedName() + "' (" + ttf.getClass().getSimpleName()
            + ") reached the inline multiset arm; the delivery fork above must route batched,"
            + " service and interface deliveries to their correlation-key arms first");
    }

    private static boolean hasKind(List<OperationMember> members, OperationMember.Kind kind) {
        return members.stream().anyMatch(m -> m.kind() == kind);
    }

    private static UnitRef mintNestedUnit(GraphitronSchema schema, ChildField.NestingField nf,
            String anchorTypeName,
            GeneratedUnits units, Map<UnitMethodRef, Boolean> glueEnvByMethod, AddressCensus census) {
        var unit = units.nestingUnit(anchorTypeName, nf.returnType().returnTypeName());
        var contributions = collectContributions(
            schema, nf.nestedFields(), unit, anchorTypeName, units, glueEnvByMethod, census, true);
        census.add(new ProjectionCommand.NestedUnit(unit, nf.returnType().table(), contributions),
            "nesting type '" + nf.returnType().returnTypeName() + "' under anchor '"
                + anchorTypeName + "'");
        return unit;
    }

    private static UnitRef mintPivotUnit(ChildField.PivotSpecField field,
            GeneratedUnits units, AddressCensus census) {
        var unit = units.pivotUnit(field.parentTypeName(), field.name());
        var pivot = field.pivot();
        var contributions = field.spec().slots().stream()
            .map(slot -> (Contribution) new Contribution.Project(slot.name(), List.of(
                new SelectTerm.Aggregate(pivot.value(), pivot.discriminator(),
                    pivot.tokenBySlot().get(slot.name()), slot.readName()))))
            .toList();
        census.add(new ProjectionCommand.PivotUnit(unit, pivot.table(), contributions),
            "@pivot coordinate '" + field.parentTypeName() + "." + field.name() + "'");
        return unit;
    }

    // ------------------------------------------------------------------------------------------
    // Row facts
    // ------------------------------------------------------------------------------------------

    /**
     * The coordinate's condition glue call, resolved off the condition relation: the glue ref by
     * the naming scheme, the env-appending fork by the row's own answer (single-sourced; never
     * recomputed from filters here). A filtered coordinate with no condition row is a relation
     * integrity failure: the condition producer's membership predicate covers every
     * SQL-generating field with filters.
     */
    private static GlueCall glueFor(String parentTypeName, String fieldName,
            List<WhereFilter> filters, GeneratedUnits units, Map<UnitMethodRef, Boolean> glueEnvByMethod) {
        if (filters.isEmpty()) {
            return null;
        }
        var method = units.conditionMethod(parentTypeName, fieldName);
        Boolean takesEnv = glueEnvByMethod.get(method);
        if (takesEnv == null) {
            throw new IllegalStateException(
                "filtered coordinate '" + parentTypeName + "." + fieldName + "' has no condition "
                + "row; the condition relation must cover every SQL-generating field with filters");
        }
        return new GlueCall(method, takesEnv);
    }

    /**
     * True when the emitted multiset arm serves runtime state off the canonical
     * {@code SelectedField}, clause for clause against the arm's emission: the runtime
     * {@code first} limit, filter bindings the glue extracts from the passed argument map (a
     * request-context binding is served by the appended env instead), and routine-hop
     * {@code Arg} bindings. An arm that merely carries arguments nothing reads stays unguarded,
     * so the occurrence guard cannot false-positive on divergence in arguments nothing consumes.
     */
    private static boolean readsSelectedFieldArguments(ChildField.TableTargetField tf) {
        boolean singleCardinality = tf.returnType().wrapper() instanceof FieldWrapper.Single;
        if (!singleCardinality && tf.pagination() != null && tf.pagination().first() != null) {
            return true;
        }
        if (tf.filters().stream()
                .flatMap(f -> f.callParams().stream())
                .anyMatch(p -> !p.readsRequestContext())) {
            return true;
        }
        return tf.joinPath().stream().anyMatch(step ->
            step instanceof JoinStep.Hop hop
                && hop.target() instanceof TableExpr.RoutineCall rc
                && rc.routine().argBindings().stream()
                    .anyMatch(b -> b.source() instanceof ParamSource.Arg));
    }

    /**
     * The write/read alias membership guard, relocated from the retired switch emitter's
     * default arm: a {@link ResultKeyAliasedField} whose contribution lands no
     * result-key-aliased output would drop its {@code __rk_} alias from the SELECT while the
     * read side still derives it, so production fails loudly instead.
     */
    private static void requireAliasedWriteArm(ChildField field, Contribution contribution) {
        if (!(field instanceof ResultKeyAliasedField)) {
            return;
        }
        boolean aliased = switch (contribution) {
            case Contribution.Call call -> !(call.wrap() instanceof CallWrap.Splice);
            case Contribution.Project p -> p.terms().stream().anyMatch(t -> switch (t) {
                case SelectTerm.Column c -> c.alias() == TermAlias.BY_RESULT_KEY;
                case SelectTerm.ScalarSubselect ignored -> true;
                case SelectTerm.HelperCall ignored -> true;
                case SelectTerm.Aggregate ignored -> false;
            });
        };
        if (!aliased) {
            throw new IllegalStateException(
                "ResultKeyAliasedField '" + field.qualifiedName() + "' ("
                + field.getClass().getSimpleName() + ") minted no result-key-aliased projection "
                + "output; its reader derives a __rk_ alias the SELECT would not carry");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Required projection (interim slot; retires with the gated correlation arm)
    // ------------------------------------------------------------------------------------------

    /**
     * The correlation-key arm for a child whose fetcher reads {@code columns} off the parent
     * row by <em>base</em> name: an ordinary {@link Contribution.Project} of unaliased column
     * terms, gated on the field like every other contribution. Empty demand (a no-Sources
     * service delegation, a single-cardinality polymorphic field with only unbound
     * participants) mints nothing.
     *
     * <p>Tripwire first, unconditionally: a Record-sourced parent-row reader keys off the held
     * object, not a parent SELECT, so reaching a projection-unit walk at all is a generator
     * bug. Fail at production rather than at runtime with a null DataLoader key.
     */
    private static Optional<Contribution> correlationKeyArm(ChildField field,
            List<no.sikt.graphitron.rewrite.model.ColumnRef> columns) {
        if (field.sourceShape() == no.sikt.graphitron.rewrite.model.SourceShape.Record) {
            throw new IllegalStateException(
                "Record-sourced field '" + field.name() + "' (" + field.getClass().getSimpleName()
                    + ") reached a table-context projection walk; its key / correlation"
                    + " columns are not parent-row columns and must not be projected");
        }
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Contribution.Project(field.name(),
            columns.stream().distinct()
                .map(col -> (SelectTerm) new SelectTerm.Column(col, TermAlias.BY_COLUMN_IDENTITY))
                .toList()));
    }

    // ------------------------------------------------------------------------------------------
    // Address census
    // ------------------------------------------------------------------------------------------

    /**
     * Registers rows under their case-folded unit address. A key hit with an identical row is
     * legitimate reuse (the same {@code (anchor, nesting type)} reached through two fields);
     * a key hit with a diverging row is an address collision, which the validator mirror
     * ({@link #addressCollisions}) rejects with a located error before production normally runs,
     * so this throw is the producer-side backstop.
     */
    private static final class AddressCensus {
        private final LinkedHashMap<String, ProjectionCommand> byFoldedAddress = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> originByFoldedAddress = new LinkedHashMap<>();

        void add(ProjectionCommand row, String origin) {
            var key = row.unit().fqcn().toLowerCase(Locale.ROOT);
            var existing = byFoldedAddress.putIfAbsent(key, row);
            if (existing == null) {
                originByFoldedAddress.put(key, origin);
                return;
            }
            if (!existing.equals(row)) {
                throw new IllegalStateException(
                    "projection unit address '" + row.unit().fqcn() + "' minted twice with "
                    + "diverging rows: " + originByFoldedAddress.get(key) + " and " + origin
                    + "; the validator's projection-unit address census must reject this before "
                    + "production");
            }
        }

        List<ProjectionCommand> rows() {
            return List.copyOf(byFoldedAddress.values());
        }
    }

    /**
     * The validator's mirror of the producer's address census: every projection-unit simple
     * name the schema mints (anchor type names, {@code <Anchor><Nested>} pairs,
     * {@code <Parent><Field>} pivot coordinates), grouped case-folded, with the groups that
     * collide across <em>distinct origins</em> returned for rejection. Reuse of one origin
     * (the same pair reached through two fields) is not a collision.
     */
    public static List<AddressCollision> addressCollisions(GraphitronSchema schema) {
        var units = new GeneratedUnits("");
        var origins = new LinkedHashMap<String, LinkedHashMap<String, SourceLocation>>();
        for (var typeName : schema.types().keySet().stream().sorted().toList()) {
            var type = schema.type(typeName);
            if (!(type instanceof GraphitronType.TableType || type instanceof GraphitronType.NodeType)) {
                continue;
            }
            record(origins, units.typeClass(typeName).simpleName(),
                "type '" + typeName + "'", type.location());
            walkAddresses(schema.fieldsOf(typeName), typeName, units, origins);
        }
        return origins.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> new AddressCollision(
                byFoldedName(origins, e.getKey()),
                e.getValue().entrySet().stream()
                    .map(o -> new AddressOrigin(o.getKey(), o.getValue()))
                    .toList()))
            .toList();
    }

    private static String byFoldedName(
            LinkedHashMap<String, LinkedHashMap<String, SourceLocation>> origins, String foldedKey) {
        // Representative simple name for the message: recover it from the first origin
        // description being keyed under the folded name; the fold itself is the identity that
        // matters (case-insensitive filesystems).
        return foldedKey;
    }

    private static void walkAddresses(List<? extends GraphitronField> fields, String anchorTypeName,
            GeneratedUnits units, LinkedHashMap<String, LinkedHashMap<String, SourceLocation>> origins) {
        for (var f : fields) {
            switch (f) {
                case ChildField.NestingField nf -> {
                    record(origins, units.nestingUnit(anchorTypeName, nf.returnType().returnTypeName()).simpleName(),
                        "nesting type '" + nf.returnType().returnTypeName() + "' under anchor '"
                            + anchorTypeName + "'", nf.location());
                    walkAddresses(nf.nestedFields(), anchorTypeName, units, origins);
                }
                case ChildField.PivotField pf ->
                    record(origins, units.pivotUnit(pf.parentTypeName(), pf.name()).simpleName(),
                        "@pivot coordinate '" + pf.parentTypeName() + "." + pf.name() + "'", pf.location());
                case ChildField.BatchedPivotField bpf ->
                    record(origins, units.pivotUnit(bpf.parentTypeName(), bpf.name()).simpleName(),
                        "@pivot coordinate '" + bpf.parentTypeName() + "." + bpf.name() + "'", bpf.location());
                default -> { }
            }
        }
    }

    private static void record(LinkedHashMap<String, LinkedHashMap<String, SourceLocation>> origins,
            String simpleName, String origin, SourceLocation location) {
        origins.computeIfAbsent(simpleName.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>())
            .putIfAbsent(origin, location);
    }

    /** One case-folded projection-unit address minted from more than one distinct origin. */
    public record AddressCollision(String foldedSimpleName, List<AddressOrigin> origins) {}

    /** One origin of a colliding address: what minted it, and where it was authored. */
    public record AddressOrigin(String description, SourceLocation location) {}
}
