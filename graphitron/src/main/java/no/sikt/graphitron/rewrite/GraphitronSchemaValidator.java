package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.generators.FetcherEmitter;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ResultKeyAliasedField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.ValueLocator;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import no.sikt.graphitron.rewrite.model.MethodRef;

/**
 * Validates a {@link GraphitronSchema}, collecting all errors rather than failing on the first.
 *
 * <p>Each validation method receives the classified field or type and appends to the shared
 * error list. The Maven plugin calls this after {@code GraphitronSchemaBuilder.build()} and formats
 * the resulting {@link ValidationError} list as compiler-style messages with file and line
 * references.
 */
public class GraphitronSchemaValidator {

    public List<ValidationError> validate(GraphitronSchema schema) {
        var types = schema.types();
        var errors = new ArrayList<ValidationError>();
        types.values().forEach(type -> validateType(type, types, errors));
        schema.fields().values().forEach(field -> validateField(field, schema, types, errors));
        validateNestingParentCompat(schema, errors);
        validateReachableSourceShapes(schema, errors);
        validateLocalContextErrorsFieldGuards(schema, errors);
        validateOutcomeTypeShape(schema, errors);
        validateOutcomeChildArmSwitch(schema, errors);
        validateContextArgumentTypeAgreement(schema, errors);
        validateSessionHandleBindings(schema, errors);
        validateTenantBindings(schema, errors);
        validateConditionEmitImplemented(schema, errors);
        validateProjectionUnitAddresses(schema, errors);
        validateSiblingProjectionAgreement(schema, errors);
        validateLauncherMethodNames(schema, errors);
        validateJoinedTableReprojection(schema, errors);
        drainBuildDiagnostics(schema, errors);
        return List.copyOf(errors);
    }

    /**
     * Drains the joined-table reprojection fold's deferrals ({@link JoinedTableReprojection}):
     * participant field shapes that classify but have no discriminated-re-projection emission (a
     * non-directly-projected column carrier, which the retired inline assembly silently
     * truncated to its first column). One formula, read here and by every reprojection consumer
     * through {@link GraphitronSchema#joinedTableReprojectionOf}, so the emission and the
     * rejection cannot drift.
     */
    private void validateJoinedTableReprojection(GraphitronSchema schema, List<ValidationError> errors) {
        for (var type : schema.types().values()) {
            if (!(type instanceof no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType)) {
                continue;
            }
            for (var deferral : schema.joinedTableReprojectionOf(type.name()).deferrals()) {
                var field = schema.field(deferral.typeName(), deferral.fieldName());
                errors.add(new ValidationError(
                    deferral.typeName() + "." + deferral.fieldName(),
                    Rejection.deferred(deferral.message()),
                    field == null ? SourceLocation.EMPTY : field.location()));
            }
        }
    }

    /**
     * Validator mirror for the condition emit's unimplemented shapes, each a deferred rejection
     * so an accepted classification whose emission cannot run fails the build instead of
     * shipping wrong output (a call to a method that is never generated, or a filter the fetcher
     * silently ignores at a live request).
     *
     * <ul>
     *   <li><b>Any filter on a single-table interface child coordinate.</b>
     *       {@code ChildField.TableInterfaceField} carries the shared filter components, but its
     *       fetcher composes only the FK correlation and the discriminator restriction and folds
     *       no filters at all, so an accepted filter would be silently ignored at runtime
     *       (unfiltered rows, wrong data). Rejected, authored and generated alike, until that
     *       fetcher folds its filter list.</li>
     * </ul>
     */
    private void validateConditionEmitImplemented(GraphitronSchema schema, List<ValidationError> errors) {
        for (var entry : schema.fields().entrySet()) {
            var field = entry.getValue();
            if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField tif
                    && !tif.filters().isEmpty()) {
                emitDeferredError(field, (Rejection.Deferred) Rejection.deferred(
                    "filters on a single-table interface child coordinate are not emitted: the "
                    + "interface fetcher composes only the parent correlation and the discriminator "
                    + "restriction, so the filter would be silently ignored at runtime; hoist the "
                    + "filterable argument to a concrete coordinate, or drop it"),
                    errors);
            }
        }
    }

    /**
     * Validator mirror of the projection producer's case-folded address census
     * ({@code ProjectionCommands.addressCollisions}): the anchor-prefixed nesting units and
     * per-coordinate pivot units mint concatenated names that can collide with an authored
     * type's unit or with each other, and two units cannot land at one address. The producer's
     * hard failure is the backstop; this rejection is what an author sees, located at the
     * colliding declarations.
     */
    private void validateProjectionUnitAddresses(GraphitronSchema schema, List<ValidationError> errors) {
        for (var collision : no.sikt.graphitron.plan.ProjectionCommands.addressCollisions(schema)) {
            var origins = collision.origins();
            var others = origins.stream().skip(1)
                .map(no.sikt.graphitron.plan.ProjectionCommands.AddressOrigin::description)
                .collect(java.util.stream.Collectors.joining(", "));
            errors.add(new ValidationError(
                origins.get(0).description(),
                Rejection.invalidSchema(
                    origins.get(0).description() + " mints the generated projection unit name '"
                        + collision.foldedSimpleName() + "' (case-folded), which " + others
                        + " also mints; rename the nesting type, the pivot field, or the colliding "
                        + "type so every projection unit has a distinct class name"),
                origins.get(0).location() == null
                    ? SourceLocation.EMPTY
                    : origins.get(0).location()));
        }
    }

    /**
     * Validator mirror of the projection producer's shared-alias agreement census
     * ({@code ProjectionCommands.siblingProjectionConflicts}). A single-table discriminated
     * interface's query folds every participant's select terms into one set that dedupes aliased
     * terms by their alias alone, so two participants contributing different SQL under the same
     * alias silently lose one of the two projections. The alias qualifier this generator stamps
     * closes that for a name the participant type declares itself; what it cannot close is a name
     * the interface declares (every arm mints the interface-qualified alias, which is exactly what
     * makes the agreeing case collapse to one term) or a name a spliced nesting unit contributes
     * (no anchor-dependent alias exists that the nesting type's one registered fetcher could
     * read). Those two are what this census rejects, so no shape covered by the
     * correct-or-build-error promise resolves to a silent drop.
     *
     * <p>Deferred, not an author error: the divergent schema is legal and meaningful, and
     * qualifying every arm per participant would emit it. The rejection says the generator does
     * not do that yet.
     */
    private void validateSiblingProjectionAgreement(GraphitronSchema schema, List<ValidationError> errors) {
        for (var conflict : no.sikt.graphitron.plan.ProjectionCommands.siblingProjectionConflicts(schema)) {
            errors.add(new ValidationError(
                conflict.secondTypeName() + "." + conflict.fieldName(),
                Rejection.deferred(
                    "'" + conflict.secondTypeName() + "." + conflict.fieldName() + "' and '"
                    + conflict.firstTypeName() + "." + conflict.fieldName() + "' are participants of"
                    + " the single-table discriminated interface '" + conflict.interfaceName()
                    + "' whose projections of '" + conflict.fieldName() + "' (" + conflict.origin()
                    + ") resolve differently. Both project under the same SELECT alias, so the"
                    + " interface's one query can carry only one of them and the other type's rows"
                    + " would read this one's value. Make the two declarations agree, or move the"
                    + " diverging field off the interface onto each participant under its own name"),
                conflict.secondLocation() == null ? SourceLocation.EMPTY : conflict.secondLocation()));
        }
    }

    /**
     * Validator mirror of the launcher relation's case-folded method-name census
     * ({@code LauncherCommands.methodCollisions}): the {@code rows} / {@code load} /
     * {@code lookup} formulas upper-camel a field name, which is not injective, so two covered
     * coordinates on one type can mint one emitted method. The relation constructor's hard
     * failure is the backstop; this rejection is what an author sees, located at the colliding
     * declarations.
     */
    private void validateLauncherMethodNames(GraphitronSchema schema, List<ValidationError> errors) {
        for (var collision : no.sikt.graphitron.plan.LauncherCommands.methodCollisions(schema)) {
            var origins = collision.origins();
            var others = origins.stream().skip(1)
                .map(no.sikt.graphitron.plan.LauncherCommands.MethodOrigin::description)
                .collect(java.util.stream.Collectors.joining(", "));
            errors.add(new ValidationError(
                origins.get(0).description(),
                Rejection.invalidSchema(
                    origins.get(0).description() + " mints the generated launcher method '"
                        + collision.foldedKey() + "' (case-folded), which " + others
                        + " also mints; rename one of the colliding fields so every launcher"
                        + " method has a distinct name"),
                origins.get(0).location() == null
                    ? SourceLocation.EMPTY
                    : origins.get(0).location()));
        }
    }

    /**
     * Drains the cached tenant-scope classification's typed rejections into
     * {@link ValidationError}s (same validator-mirrors-classifier shape as
     * {@link #validateContextArgumentTypeAgreement}). {@link TenantScopeClassifier} runs once at
     * catalog load; the validator and the tenant-routing emitters read the identical
     * {@link GraphitronSchema#tenantScopes()}. A tenant-column defect has no SDL coordinate, so
     * the errors surface at {@code <schema>}.
     */
    private void validateTenantBindings(GraphitronSchema schema, List<ValidationError> errors) {
        if (schema.tenantScopes() instanceof no.sikt.graphitron.rewrite.model.TenantScopes.Configured configured) {
            for (Rejection conflict : configured.conflicts()) {
                errors.add(new ValidationError(
                    "<schema>",
                    conflict,
                    SourceLocation.EMPTY
                ));
            }
        }
        // The per-field half: the tenant-binding fold's noTenantBinding findings, already
        // fully-formed ValidationErrors carrying the offending coordinate.
        errors.addAll(schema.tenantBindings().rejections());
    }

    /**
     * Drains the build-time validation diagnostics ({@link GraphitronSchema#diagnostics()}) into
     * the {@link ValidationError} stream. The global soundness reductions (node-typeId
     * uniqueness, case-fold collisions, the dangling-reference backstop, the federation
     * {@code @key} checks, the multi-producer {@code DomainReturnType} agreement) register a
     * fully-formed {@link ValidationError} on the schema instead of demoting a classified verdict
     * to {@code UnclassifiedType} / {@code UnclassifiedField}, so a verdict read after the walk
     * equals the verdict classification produced; this drain re-surfaces those findings unchanged
     * (coordinate, typed {@link Rejection}, source location).
     */
    private void drainBuildDiagnostics(GraphitronSchema schema, List<ValidationError> errors) {
        errors.addAll(schema.diagnostics());
    }

    /**
     * Drains the cached {@link ContextArgumentClassifier} output's typed
     * {@link Rejection.AuthorError.TypeConflict} list into {@link ValidationError}s: two or more
     * directive sites referencing the same context-argument name with disagreeing Java types
     * reject here, before the factory emitter is asked to paste a non-existent {@code TypeName}
     * into {@code Graphitron.newExecutionInput(...)}.
     *
     * <p>Reads {@link GraphitronSchema#contextArguments()}, populated once at parse boundary, so
     * the validator and {@code GraphitronFacadeGenerator} see the identical classification.
     */
    private void validateContextArgumentTypeAgreement(GraphitronSchema schema, List<ValidationError> errors) {
        for (Rejection conflict : schema.contextArguments().conflicts()) {
            errors.add(new ValidationError(
                "<schema>",
                conflict,
                graphql.language.SourceLocation.EMPTY
            ));
        }
    }

    /**
     * The {@code $session} sigil's located rejections, checked against the resolved session-hook
     * carrier ({@link GraphitronSchema#sessionHooks()}): a binding with no method-hook
     * {@code <sessionState>} configured, a binding against a handle-less mount (the mount
     * returns {@code void}), and a bound parameter whose declared type is not the mount's
     * reflected handle type, each naming the field coordinate, the sigil, and the config side.
     * The classifier lowers the sigil structurally without judging the config, so this pass is
     * the one home of the cross-artifact check.
     */
    private void validateSessionHandleBindings(GraphitronSchema schema, List<ValidationError> errors) {
        var hooks = schema.sessionHooks();
        for (var field : schema.fields().values()) {
            String qualifiedName = field.parentTypeName() + "." + field.name();
            var bindings = new java.util.LinkedHashMap<String, no.sikt.graphitron.javapoet.TypeName>();
            if (field instanceof no.sikt.graphitron.rewrite.model.MethodBackedField mbf) {
                for (var p : mbf.method().params()) {
                    if (p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed typed
                            && typed.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.SessionHandle) {
                        bindings.put(typed.name(), typed.javaType());
                    }
                }
            }
            if (field instanceof no.sikt.graphitron.rewrite.model.ServiceField sf) {
                var carrier = sf.serviceMethodCall();
                var entries = new java.util.ArrayList<no.sikt.graphitron.rewrite.model.MappingEntry>(carrier.methodArgs());
                if (carrier instanceof no.sikt.graphitron.rewrite.model.ServiceMethodCall.Instance inst) {
                    entries.addAll(inst.ctorArgs());
                }
                for (var entry : entries) {
                    if (entry instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromSessionHandle handle) {
                        bindings.put(handle.javaName(), handle.javaType());
                    }
                }
            }
            for (var binding : bindings.entrySet()) {
                String paramName = binding.getKey();
                var declared = binding.getValue();
                switch (hooks) {
                    case no.sikt.graphitron.rewrite.session.SessionHooks.NotConfigured ignored ->
                        errors.add(ValidationError.forField(qualifiedName, Rejection.structural(
                            "argMapping binds parameter '" + paramName + "' to "
                                + ArgMappingSigil.SESSION_LITERAL + ", but no method-hook <sessionState> is"
                                + " configured; configure <sessionState><mount>fqcn#method</mount> whose"
                                + " return value is the handle, or remove the binding"),
                            graphql.language.SourceLocation.EMPTY));
                    case no.sikt.graphitron.rewrite.session.SessionHooks.HandleLess handleLess ->
                        errors.add(ValidationError.forField(qualifiedName, Rejection.structural(
                            "argMapping binds parameter '" + paramName + "' to "
                                + ArgMappingSigil.SESSION_LITERAL + ", but the configured <mount> ("
                                + handleLess.mount().className() + "#" + handleLess.mount().methodName()
                                + ") returns void — there is no session handle to bind"),
                            graphql.language.SourceLocation.EMPTY));
                    case no.sikt.graphitron.rewrite.session.SessionHooks.Handled handled -> {
                        if (!handled.handleType().equals(declared)) {
                            errors.add(ValidationError.forField(qualifiedName, Rejection.structural(
                                "argMapping binds parameter '" + paramName + "' to "
                                    + ArgMappingSigil.SESSION_LITERAL + " with declared type '" + declared
                                    + "', but the configured <mount> (" + handled.mount().className() + "#"
                                    + handled.mount().methodName() + ") returns '" + handled.handleType()
                                    + "' — both are your declarations; align the parameter type with the"
                                    + " mount's return type"),
                                graphql.language.SourceLocation.EMPTY));
                        }
                    }
                }
            }
        }
    }

    private void validateType(GraphitronType type, Map<String, GraphitronType> types, List<ValidationError> errors) {
        switch (type) {
            case no.sikt.graphitron.rewrite.model.GraphitronType.TableType t          -> validateTableType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.NodeType t           -> validateNodeType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.ResultType t         -> validateResultType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.RootType t           -> validateRootType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType t -> validateTableInterfaceType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType t      -> validateInterfaceType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.UnionType t          -> validateUnionType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType t          -> {} // no structural validation needed
            case no.sikt.graphitron.rewrite.model.GraphitronType.InputType t          -> validateInputType(t, types, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType t     -> validateConnectionType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType t           -> {} // schema form always present: structural edges reference the declared edges-element type, synthesised edges are built

            case no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType t       -> {} // structural validation is a downstream concern
            case no.sikt.graphitron.rewrite.model.GraphitronType.NestingType t    -> {} // no domain directives, nothing to validate structurally
            case no.sikt.graphitron.rewrite.model.GraphitronType.FacetsType t     -> {} // synthesised; shape is promoter-owned, nothing to validate structurally
            case no.sikt.graphitron.rewrite.model.GraphitronType.FacetValueType t -> {} // synthesised; shape is promoter-owned, nothing to validate structurally
            case no.sikt.graphitron.rewrite.model.GraphitronType.EnumType t           -> {} // enums validate at the schema level; no domain concerns
            case no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType t         -> {} // resolver-validated at classification time; nothing extra here
            case no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType t   -> validateUnclassifiedType(t, errors);
        }
    }

    private void validateField(GraphitronField field, GraphitronSchema schema, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateVariantSpecific(field, schema, types, null, errors);
    }

    /**
     * The per-variant validation pass: the two cross-variant guards, the variant dispatch switch,
     * and the cross-cutting checks that close it. Every site that reaches a classified field runs
     * this, so no site can validate a leaf less than another does.
     *
     * <p>Three sites call it: the top-level field walk ({@link #validateField}), the nested walk
     * under a {@link ChildField.NestingField} ({@link #walkNestedVariants}), and the
     * {@code @pivot} slot walk ({@link #validatePivotSpec}). The nested walk used to run only
     * {@link #validateVariantIsImplemented} and {@link #validateVariantIsSupportedAtNestedDepth},
     * so every per-variant check was shadowed at nested depth by the blanket nested-depth
     * deferral; a leaf admitted there would have reached the emitter unchecked.
     *
     * <p>{@code nestedAnchor} is the enclosing {@link ChildField.NestingField}'s table-bound
     * return type when the field sits at nested depth, and {@code null} at ordinary depth. A
     * plain-object nesting type carries no {@code @table} of its own and inherits the anchor's
     * table context, so an arm that asks "is there a table behind this field" cannot read the
     * immediate parent type and must read the anchor; see {@link #validateColumnBackedField}.
     *
     * <p>The two guards ahead of the switch and the two cross-cutting checks after it belong at
     * every depth, so they moved in here whole rather than staying behind at the top level:
     *
     * <ul>
     *   <li>The array-typed DataLoader-key guard is live at nested depth. {@code BatchedTableField}
     *       implements {@link no.sikt.graphitron.rewrite.model.BatchKeyField} and its Table-sourced
     *       arm is already admitted under a nesting field, so a nested batched leaf keyed on an
     *       array column mis-batched silently while the guard ran only at the top level.</li>
     *   <li>The reentry implementedness guard fires on no current leaf at any depth (the sealed
     *       hierarchy admits no such combination); running it uniformly is what keeps that true
     *       as new arms land, which is the guard's whole job.</li>
     *   <li>{@link #validatePaginationRequiresOrdering} and {@link #validateListRequiresOrdering}
     *       read {@link SqlGeneratingField#pagination()} / {@link SqlGeneratingField#orderBy()},
     *       which nested {@code TableField} and {@code BatchedTableField} leaves genuinely carry:
     *       a paginated nested leaf with no ordering encodes a cursor over nothing exactly as at
     *       ordinary depth. The authoring surface exists at nested depth, so the check does.</li>
     * </ul>
     */
    private void validateVariantSpecific(GraphitronField field, GraphitronSchema schema,
            Map<String, GraphitronType> types,
            ReturnTypeRef.TableBoundReturnType nestedAnchor,
            List<ValidationError> errors) {
        // Reentry implementedness guard: a leaf that derives site-level reentry
        // (emitsKeyedReQuery) without being one of the shapes the reentry emit handles
        // (the DataLoader-backed BatchKeyField leaves, the projected/discriminated
        // DmlTableField arms, and the two table-bound root @service leaves, whose fetcher lifts
        // the returned records' keys and calls the same companion) must fail at validate time,
        // not reach the generator. No current leaf can fire this (the sealed hierarchy admits no
        // such combination); preserving that state is the guard's job.
        if (field instanceof no.sikt.graphitron.rewrite.model.OutputField out
                && out.emitsKeyedReQuery()
                && !(field instanceof no.sikt.graphitron.rewrite.model.BatchKeyField)
                && !(field instanceof no.sikt.graphitron.rewrite.model.MutationField.DmlTableField)
                && !(field instanceof no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField)
                && !(field instanceof no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField)) {
            var memberKinds = schema
                .operationMembersOf(graphql.schema.FieldCoordinates.coordinates(
                    out.parentTypeName(), out.name()))
                .stream()
                .map(m -> m.kind().name())
                .toList();
            errors.add(new ValidationError(
                out.qualifiedName(),
                Rejection.invalidSchema("Field '" + out.qualifiedName() + "': site-level reentry "
                    + "(a reentry member among " + memberKinds + " x target " + out.target()
                    + ") on " + out.getClass().getSimpleName() + ", which carries no reentry emit — "
                    + "the keyed re-query is emitted only for DataLoader-backed leaves, "
                    + "projected/discriminated DML arms, and table-bound root @service returns"),
                out.location()));
        }
        // An array-typed column used as a DataLoader batch key (@splitQuery / SourceKey key
        // element) would key the RowN / Set<K> tuple by array reference identity, so equal-content
        // keys dedupe and match by reference and the batch mis-groups on a live request. The emitted
        // code compiles but is silently wrong, so reject at validate time instead.
        if (field instanceof no.sikt.graphitron.rewrite.model.BatchKeyField bk && bk.sourceKey() != null) {
            for (var col : bk.sourceKey().columns()) {
                if (col.columnType() instanceof no.sikt.graphitron.javapoet.ArrayTypeName) {
                    errors.add(new ValidationError(
                        field.qualifiedName(),
                        Rejection.structural("Field '" + field.qualifiedName() + "': DataLoader key column '"
                            + col.sqlName() + "' is array-typed (" + col.columnClass() + "); array columns "
                            + "cannot be used as batch key elements because Java arrays compare by reference "
                            + "identity, so equal-content keys would mis-batch. Use a scalar key column."),
                        field.location()));
                }
            }
        }
        switch (field) {
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableField f         -> validateQueryTableField(f, types, errors);

            case no.sikt.graphitron.rewrite.model.QueryField.QueryNodeField f          -> validateQueryNodeField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryNodesField f         -> {} // no extra validation
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableInterfaceField f -> validateQueryTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField f     -> validateQueryInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField f         -> validateQueryUnionField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField f       -> validateQueryServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceRecordField f      -> validateQueryServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServicePolymorphicField f -> validateQueryServicePolymorphicField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableInterfaceField f -> validateQueryServiceTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.DmlTableField f            -> validateDmlTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationRoutineWriteField f    -> {} // RoutineDirectiveResolver pins routine resolution + arg binding at classify time; RoutineChain's compact constructor and the leaf's own pins (hops non-empty, terminus rule, ColumnPairs hop 0 via the classifier's re-read-anchor verdict) carry the structural shape
            case no.sikt.graphitron.rewrite.model.MutationField.MutationRoutineWriteRecordField f -> {} // Narrow ResultReturnType + LocalContext channel + the compact ctor's two-statements pins (pairs non-empty, name-matched, target side == target PK) carry the structural shape; the classify-time rejections (scan rejects, the D7 non-null data field, the unmatched-PK keying, the fourth-cell directive conflict) surface as UnclassifiedField through validateUnclassifiedField, per the validator-mirrors-classifier rule
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField f    -> validateMutationServiceTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField f   -> validateMutationServiceRecordField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServicePolymorphicField f -> validateMutationServicePolymorphicField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableInterfaceField f -> validateMutationServiceTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationDmlRecordField f       -> {} // Narrow ResultReturnType + the write arm's typed payloads pin the structural shape; admission-time checks (table-equality, PK-or-UK partition) live in the @mutation classifier and the walkers
            case no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDmlRecordField f   -> {} // Same structural pinning as MutationDmlRecordField plus the list-input + Upsert-rejecting invariants on the compact ctor; admission-time checks (table-equality, Invariant #16) live in the classifier and the walkers
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField f       -> validateColumnBackedField(f, types, nestedAnchor, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedReferenceField f -> validateColumnBackedReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField f -> {} // structural; the interface fetcher's subselect materialises and aliases the value
            case no.sikt.graphitron.rewrite.model.ChildField.TableField f              -> validateTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField f      -> validateBatchedTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField f     -> validateTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedTableInterfaceField f -> validateBatchedTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.InterfaceField f          -> validateInterfaceField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.UnionField f              -> validateUnionField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedInterfaceField f   -> validateBatchedInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.BatchedUnionField f       -> validateBatchedUnionField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NestingField f            -> validateNestingField(f, schema, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.PivotSpecField f          -> validatePivotSpec(f, schema, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.PivotSlotField f          -> {} // readName-only leaf; every pivot admission check fires at classify time (PivotError via UnclassifiedField), and the consuming leaf's validatePivotSpec walks the slots
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField f       -> validateServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField f      -> validateServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdField f -> {} // Narrow ScalarReturnType + SourceKey compact-constructor invariants (ResultRowWalk, Wrap.TableRecord) pin the structural shape; admission-time checks (encoder-pins-to-producer-table, @node resolution) live in the serviceEmitted classifier branch
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning f -> {} // Narrow ScalarReturnType component + NodeIdEncodeKeys compaction; admission-time checks (wrapper shape, encoder-pins-to-input-@table, DELETE-only) live in the @mutation classifier
            case no.sikt.graphitron.rewrite.model.ChildField.RecordCompositeField f    -> {} // Narrow ResultReturnType + non-null fqClassName / envelope compact-constructor invariants pin the structural shape; the near-miss rejections (mismatched producer, a @field child neither @table-backed nor a resolvable composite accessor, the re-leveled cardinality mismatch) fire at classify time as UnclassifiedField (RecordBindingMultiProducer / accessor-mismatch / the composite-carrier cardinality reject), surfaced via validateUnclassifiedField
            case no.sikt.graphitron.rewrite.model.ChildField.RecordReadField f         -> validateRecordReadField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ComputedField f           -> validateComputedField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ErrorsField f             -> {} // structural; @error type checks already ran at classify time
            case no.sikt.graphitron.rewrite.model.InputField.ColumnBackedField f     -> {} // column resolution guaranteed by the builder; arity/extraction invariants enforced by the record ctor
            case no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField f -> validateInputColumnBackedReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.NestingField f          -> validateInputNestingField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.UnboundField f          -> {} // the malformed @condition(override:false) fact is minted at classification (BuildContext.classifyInputField, definition-and-table keyed); the cascade verdict is use-keyed and minted by the consumer walk
            case no.sikt.graphitron.rewrite.model.InputField.ConditionOwnedField f   -> {} // the explicit @condition(override:true) method owns the predicate; the carrier's compact constructor pins the shape and the DML walkers own the write-side rejections
            case no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField f -> validateUnclassifiedField(f, errors);
        }
        validatePaginationRequiresOrdering(field, errors);
        validateListRequiresOrdering(field, errors);
        validateVariantIsImplemented(field, errors);
    }

    /**
     * Cross-cutting check: any SQL-generating field that has pagination arguments must also have
     * ordering. Keyset pagination without ordering is broken by definition — the cursor encodes
     * ORDER BY column values, so there must be columns to encode.
     */
    private void validatePaginationRequiresOrdering(GraphitronField field, List<ValidationError> errors) {
        if (field instanceof SqlGeneratingField sgf
                && sgf.pagination() != null
                && sgf.orderBy() instanceof OrderBySpec.None) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': paginated fields must have ordering "
                    + "(add @defaultOrder or @orderBy)"),
                field.location()
            ));
        }
    }

    /**
     * Cross-cutting check: any SQL-generating list field must have a deterministic order. The
     * resolver lands on {@link OrderBySpec.None} only when no {@code @defaultOrder}/{@code @orderBy}
     * is present and the target table has no primary key; in that case the generated SQL emits
     * no {@code ORDER BY} clause and rows return in catalog order, producing visibly different
     * results every run. Reject at build time rather than ship a latent non-determinism bug.
     *
     * <p>Gated on {@link FieldWrapper.List} (not {@link FieldWrapper#isList()}, which also covers
     * connections) so the message stays disjoint from {@link #validatePaginationRequiresOrdering};
     * connections always carry pagination and are caught there. Exempts
     * {@link no.sikt.graphitron.rewrite.model.OutputField#requiresReFetch()} fields: a
     * re-fetch field's visible order is locked to the source/target key correspondence (the
     * {@code ORDER BY idx} scatter re-keys the re-projected rows to the upstream source order), so
     * the "list-shaped + {@code None}" signal does not imply non-determinism for them.
     *
     * <p>A routine-backed read is not exempt. Its rows arrive in the function's own result order,
     * which is exactly the non-determinism this check exists to reject, and the surface to fix it
     * with does ship: {@code @defaultOrder(fields:)} over the terminus. What a routine terminus
     * lacks is a primary key for the fallback to find, so it gets its own message
     * ({@link #listOrderingDiagnostic}) rather than the generic one, whose "add a primary key to
     * the target table" advice is impossible on a function result.
     */
    private void validateListRequiresOrdering(GraphitronField field, List<ValidationError> errors) {
        if (field instanceof SqlGeneratingField sgf
                && !(field instanceof no.sikt.graphitron.rewrite.model.OutputField out && out.requiresReFetch())
                && sgf.returnType().wrapper() instanceof FieldWrapper.List
                && sgf.orderBy() instanceof OrderBySpec.None) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural(listOrderingDiagnostic(field)),
                field.location()
            ));
        }
    }

    /**
     * The deterministic-order message, forked on whether the read lands on a table-valued
     * function result. Both arms state the same rule; they differ only in which remedies are
     * reachable, since a function result has no primary key to add and no fallback to inherit.
     */
    private static String listOrderingDiagnostic(GraphitronField field) {
        String routine = routineResultTerminusOf(field);
        if (routine != null) {
            return "Field '" + field.qualifiedName() + "': list fields must have a deterministic "
                + "order, and these rows come from the table-valued function '" + routine
                + "', whose result carries no primary key for the default order to fall back on. "
                + "Add @defaultOrder(fields: [...]) naming the function's own result columns, or "
                + "an @orderBy argument over them.";
        }
        return "Field '" + field.qualifiedName() + "': list fields must have a "
            + "deterministic order. Add a primary key to the target table, or use "
            + "@defaultOrder or @orderBy.";
    }

    /**
     * The SQL name of the table-valued function a field's read terminates on, or {@code null}
     * when the terminus is an ordinary catalog table. A root chain terminates on its routine
     * exactly when no {@code @reference} hop follows it; a child chain carries the routine as a
     * lateral hop, so its terminus is the routine when the last step targets the routine call.
     */
    private static String routineResultTerminusOf(GraphitronField field) {
        if (field instanceof no.sikt.graphitron.rewrite.model.QueryField.QueryTableField qtf) {
            // A root chain's hops all target the catalog (the chain constructor's own
            // invariant), so the routine is the terminus exactly when no hop follows it.
            return qtf.routine() instanceof no.sikt.graphitron.rewrite.model.RoutineResolution.Chain c
                    && c.chain().hops().isEmpty()
                ? c.chain().start().resultTable().tableName()
                : null;
        }
        List<JoinStep> steps = switch (field) {
            case ChildField.TableField tf -> tf.joinPath();
            case ChildField.BatchedTableField btf -> btf.joinPath();
            default -> null;
        };
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        return steps.getLast() instanceof JoinStep.Hop hop
            && hop.target() instanceof no.sikt.graphitron.rewrite.model.TableExpr.RoutineCall call
            ? call.resultTable().tableName()
            : null;
    }

    /**
     * Cross-cutting check: reject schemas whose classification lands on a variant the
     * {@link TypeFetcherGenerator} does not implement. Without this check the build succeeds
     * and the generated stub throws {@link UnsupportedOperationException} at the first request
     * hitting the variant; problems caught at build time are cheaper.
     *
     * <p>The {@link TypeFetcherGenerator#STUBBED_VARIANTS} map is the single source of
     * truth for "stubbed" status. Variants in {@code IMPLEMENTED_LEAVES} or
     * {@code NOT_DISPATCHED_LEAVES} return {@code null} from this lookup and are correctly
     * ignored, an invariant enforced by
     * {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
     */
    private void validateVariantIsImplemented(GraphitronField field, List<ValidationError> errors) {
        var stubbed = TypeFetcherGenerator.STUBBED_VARIANTS.get(field.getClass());
        if (stubbed != null) {
            emitDeferredError(field, stubbed, errors);
        }
    }

    private static void emitDeferredError(GraphitronField field,
            no.sikt.graphitron.rewrite.model.Rejection.Deferred deferred, List<ValidationError> errors) {
        errors.add(new ValidationError(
            field.qualifiedName(),
            deferred.prefixedWith("Field '" + field.qualifiedName() + "': "),
            field.location()
        ));
    }

    // --- Type validators ---

    private void validateTableType(no.sikt.graphitron.rewrite.model.GraphitronType.TableType type, List<ValidationError> errors) {
        // Unresolved tables are caught by the builder (UnclassifiedType). Nothing more to validate here.
    }
    private void validateNodeType(no.sikt.graphitron.rewrite.model.GraphitronType.NodeType type, List<ValidationError> errors) {
        // Unresolved tables and unresolved @node key columns are caught by the builder (UnclassifiedType).
        // An array-typed column used as a NodeId key column would be encoded/decoded and
        // compared by array reference identity, so distinct rows with equal element content mis-match.
        // Reject at validate time rather than emitting a NodeId encoder that mis-identifies at runtime.
        for (var col : type.nodeKeyColumns()) {
            if (col.columnType() instanceof no.sikt.graphitron.javapoet.ArrayTypeName) {
                errors.add(ValidationError.forType(
                    type.name(),
                    Rejection.structural("@node key column '" + col.sqlName() + "' is array-typed ("
                        + col.columnClass() + "); array columns cannot be used as NodeId key columns "
                        + "because Java arrays compare by reference identity, so equal-content rows would "
                        + "fail to match. Use a scalar key column."),
                    type.location()));
            }
        }
    }
    private void validateResultType(no.sikt.graphitron.rewrite.model.GraphitronType.ResultType type, List<ValidationError> errors) {}
    private void validateRootType(no.sikt.graphitron.rewrite.model.GraphitronType.RootType type, List<ValidationError> errors) {}
    private void validateTableInterfaceType(no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
    }
    private void validateInterfaceType(no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
        // PK-presence and PK-arity constraints are scoped to fields that actually emit the
        // multi-table polymorphic fetcher (validateQueryInterfaceField / validateQueryUnionField).
        // Interfaces dispatched via other paths, notably the Node interface's QueryNodeFetcher,
        // bypass those constraints.
    }
    private void validateUnionType(no.sikt.graphitron.rewrite.model.GraphitronType.UnionType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
        // Same scoping as validateInterfaceType: see validateQueryUnionField for the PK-presence
        // and PK-arity checks that the multi-table polymorphic emitter requires.
    }

    /**
     * Connections carry an optional {@code totalCount: Int} field. When the SDL author declares
     * the field with any non-{@code Int} scalar (or a list / object type), the build fails with a
     * compiler-style error rather than silently mis-wiring the resolver. The synthesised path
     * always uses {@code Int}, so it never trips this check.
     *
     * <p>Coordinate and location point at the field, not the containing type, so editors and the
     * watch-mode formatter highlight the exact line the author needs to fix. The field's AST
     * {@code FieldDefinition} carries the structural source location; on the rare programmatic
     * path where it is absent, fall back to the type-level location.
     */
    private void validateConnectionType(GraphitronType.ConnectionType type, List<ValidationError> errors) {
        var fd = type.schemaType().getFieldDefinition("totalCount");
        if (fd == null) return;
        var unwrapped = graphql.schema.GraphQLTypeUtil.unwrapNonNull(fd.getType());
        if (unwrapped != graphql.Scalars.GraphQLInt) {
            var def = fd.getDefinition();
            var location = def != null && def.getSourceLocation() != null
                ? def.getSourceLocation()
                : type.location();
            errors.add(new ValidationError(
                type.name() + ".totalCount",
            Rejection.invalidSchema("Field '" + type.name() + ".totalCount' must be of type 'Int' (got '"
                    + graphql.schema.GraphQLTypeUtil.simplePrint(fd.getType()) + "')"),
                location
            ));
        }
    }

    private void validateInputType(no.sikt.graphitron.rewrite.model.GraphitronType.InputType type, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Type-existence of field types is already guaranteed by graphql-java schema validation.
    }

    /**
     * The input-field validator-side rejections ({@link #validateInputFieldRecursive}) as a
     * standalone list. Input fields are resolved per consuming field (the field-derived
     * write-target paths in {@code FieldBuilder}), never in a registry type walk, so every
     * call site that resolves input fields against a table drains this one walk to enforce
     * the identical rule (the validator-mirror obligation).
     */
    static List<ValidationError> collectInputFieldRejections(List<no.sikt.graphitron.rewrite.model.InputField> fields) {
        var errors = new java.util.ArrayList<ValidationError>();
        for (var field : fields) {
            validateInputFieldRecursive(field, errors);
        }
        return errors;
    }

    /**
     * Walks the input-field tree rooted at {@code field}, surfacing the validator-side
     * rejections; recurses through nesting fields so nested plain inputs inside a
     * DML input are walked too.
     */
    private static void validateInputFieldRecursive(no.sikt.graphitron.rewrite.model.InputField field, List<ValidationError> errors) {
        switch (field) {
            case no.sikt.graphitron.rewrite.model.InputField.UnboundField uf -> {} // the malformed @condition(override:false) fact is minted at classification; the DML walkers own the write-side consequence
            case no.sikt.graphitron.rewrite.model.InputField.ConditionOwnedField cof -> {} // shape pinned by the carrier's compact constructor; write-side admission is the DML walkers' arm
            case no.sikt.graphitron.rewrite.model.InputField.NestingField nf -> {
                for (var nested : nf.fields()) {
                    validateInputFieldRecursive(nested, errors);
                }
            }
            case no.sikt.graphitron.rewrite.model.InputField.ColumnBackedField ignored -> {}
            case no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField crf -> validateInputColumnBackedReferenceField(crf, errors);
        }
    }

    private void validateParticipants(String typeName, java.util.List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants, List<ValidationError> errors) {
        // Unbound participants are caught by the builder (UnclassifiedType). Nothing to validate here.
    }

    /**
     * Validates a {@link no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField}
     * or {@link no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField}'s participant set
     * against the constraints of the two-stage native fetcher emission:
     *
     * <ul>
     *   <li>Every {@link no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound} participant
     *       must declare a primary key. Stage 1 needs row identity per branch.</li>
     *   <li>All TableBound participants must share the same PK arity. Stage 1 projects
     *       {@code (typename, pk0..pkN-1, sort)} per branch, and the column count must align
     *       across UNION ALL branches; the emitter does not NULL-pad.</li>
     * </ul>
     *
     * <p>{@link no.sikt.graphitron.rewrite.model.ParticipantRef.Unbound} participants are
     * skipped: they are handled by the {@code @error} carrier path or never reach the SQL
     * emitter. Only {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType}
     * has its own dedicated single-table emitter and bypasses this check.
     *
     * <p>Scoped to fields, not types, so an interface like {@code Node} (dispatched via
     * {@code QueryNodeFetcher}, not the multi-table polymorphic emitter) does not trip the
     * arity rule when its implementers have heterogeneous PK shapes.
     *
     * @param qualifiedName field qualified name used in the error message header (e.g.
     *                      {@code "Query.search"}). Pass {@code field.qualifiedName()}.
     */
    private void validateMultiTableParticipants(String qualifiedName, SourceLocation location,
            java.util.List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants,
            List<ValidationError> errors) {
        var tableBound = participants.stream()
            .filter(p -> p instanceof no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound)
            .map(p -> (no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound) p)
            .toList();
        if (tableBound.isEmpty()) return;

        for (var tb : tableBound) {
            if (!tb.table().hasPrimaryKey()) {
                errors.add(new ValidationError(
                    qualifiedName,
            Rejection.structural("Field '" + qualifiedName + "': participant '" + tb.typeName()
                        + "' has no primary key on table '" + tb.table().tableName()
                        + "'; multi-table interface/union fetchers require a primary key on every participant"),
                    location
                ));
            }
        }

        // Arity check: only meaningful when every participant has at least one PK column.
        // PK-less participants surface their own dedicated error above; reporting an arity
        // mismatch on top would noise the message stream.
        var pkBearing = tableBound.stream()
            .filter(tb -> tb.table().hasPrimaryKey())
            .toList();
        if (pkBearing.size() >= 2) {
            int expected = pkBearing.get(0).table().primaryKeyColumns().size();
            for (var tb : pkBearing.subList(1, pkBearing.size())) {
                int actual = tb.table().primaryKeyColumns().size();
                if (actual != expected) {
                    errors.add(new ValidationError(
                        qualifiedName,
                Rejection.structural("Field '" + qualifiedName + "': primary-key arity mismatch — '" + pkBearing.get(0).typeName()
                            + "' has " + expected + " PK column" + (expected == 1 ? "" : "s")
                            + " but '" + tb.typeName() + "' has " + actual
                            + "; v1 multi-table interface/union fetchers require uniform PK arity across participants"),
                        location
                    ));
                    return; // one mismatch is enough; subsequent ones are noise
                }
            }
        }

        // Same-table discriminability floor: same-table polymorphism must be modeled as a
        // single-table discriminated interface (TableInterfaceType: @table @discriminate). Two
        // participants of a *plain* multitable interface/union backed by the same table share a
        // recordClass, so record-class dispatch (route (a)) and the stage-1 __typename UNION-ALL
        // (query path) cannot tell them apart — with or without a @discriminator. Reject at
        // build time rather than misdispatch (validator mirrors classifier invariants); one shared
        // site guards both the @service-return fetcher and the query path.
        var byRecordClass = new java.util.LinkedHashMap<no.sikt.graphitron.javapoet.ClassName,
            List<no.sikt.graphitron.rewrite.model.ParticipantRef.TableBound>>();
        for (var tb : tableBound) {
            byRecordClass.computeIfAbsent(tb.table().recordClass(), k -> new java.util.ArrayList<>()).add(tb);
        }
        for (var group : byRecordClass.values()) {
            if (group.size() < 2) continue;
            errors.add(new ValidationError(
                qualifiedName,
        Rejection.structural("Field '" + qualifiedName + "': interface/union maps types '"
                    + group.get(0).typeName() + "' and '" + group.get(1).typeName()
                    + "' to the same table '" + group.get(0).table().tableName()
                    + "'; model same-table polymorphism as a single-table discriminated interface"
                    + " (@table @discriminate), or split the types"),
                location
            ));
            return; // one collision is enough; subsequent ones are noise
        }

        // Not enforced here: MultiTablePolymorphicEmitter's connection-rows method types its
        // sortField by the first PK column only, silently truncating composite participant PKs.
        // Promoting that truncation to a validator rejection would break composite-PK connection
        // participants that work today (graphitron-sakila-example's Query.pagedItems), so the
        // validator stays silent on it.
    }

    /**
     * Multi-table polymorphic child guard (both list and connection arms): rejects parent-key
     * arity above jOOQ's typed Row22 cap. The cap is uniform across both arms because the
     * shared {@code parentInput VALUES} emitter widens the parent key by an {@code idx} column
     * on every batched-rows method (see {@code MultiTablePolymorphicEmitter.buildParentInputValuesEmitter}),
     * so the resulting {@code Row<N+1>} tops out at jOOQ's {@code Row22} on either arm.
     * Field-level cap therefore is parent-key arity 21.
     *
     * <p>Surfaces the constraint as a build-time validator rejection with file:line instead of a
     * codegen-time {@code IllegalStateException} or a {@code Row23}-doesn't-exist compile failure.
     *
     * <p>Reads {@code field.parentSourceKey().columns()} uniformly across every
     * {@link no.sikt.graphitron.rewrite.model.KeyLift} arm the polymorphic parent can land on,
     * the same column tuple the rows-method prelude uses, so the validator's arity surface
     * tracks the actual key tuple regardless of producer.
     *
     * <p>The non-empty invariant is enforced upstream at construction time: the producer
     * routes empty-PK / unresolved-hub parents through {@code UnclassifiedField} before
     * constructing a {@link no.sikt.graphitron.rewrite.model.SourceKey}, so an empty
     * {@code columns()} is unreachable here. The validator's job is purely the upper-bound check.
     */
    private void validateChildMultiTableParentPk(String qualifiedName, SourceLocation location,
            String parentTypeName,
            no.sikt.graphitron.rewrite.model.SourceKey parentSourceKey,
            List<ValidationError> errors) {
        var keyCols = parentSourceKey.columns();
        if (keyCols.size() > 21) {
            errors.add(new ValidationError(
                qualifiedName,
            Rejection.structural("Field '" + qualifiedName + "': multi-table interface/union child "
                    + "field whose parent type '" + parentTypeName + "' has a parent key with "
                    + keyCols.size() + " columns exceeds jOOQ's typed Row22 cap "
                    + "(parent key + idx must fit in Row<N+1>). Use a narrower parent key or "
                    + "split the parent type"),
                location
            ));
        }
    }

    // --- Field validators ---

    private void validateQueryTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        if (field.lookup() instanceof no.sikt.graphitron.rewrite.model.LookupResolution.Keyed keyed) {
            validateRootLookup(field, keyed, errors);
            return;
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }

    /** The lookup-gated rules of the root table read; reached only on a keyed resolution. */
    private void validateRootLookup(no.sikt.graphitron.rewrite.model.QueryField.QueryTableField field,
            no.sikt.graphitron.rewrite.model.LookupResolution.Keyed keyed, List<ValidationError> errors) {
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': lookup fields must not return a connection"),
                field.location()
            ));
        } else {
            // Lookup cardinality is the key axis alone: one output row per key, so a list of
            // keys returns a list. The mapping is the single source of that fact — non-key
            // filterable arguments compose in the WHERE beside the VALUES join and narrow the
            // rows a key matches, never the number of keys.
            boolean anyKeyIsList = switch (keyed.mapping()) {
                case no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping cm ->
                    cm.hasListArg();
            };
            boolean returnIsList = field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List;
            if (anyKeyIsList != returnIsList) {
                errors.add(new ValidationError(
                    field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': result type does not match input cardinality"),
                    field.location()
                ));
            }
            // A list lookup answers one slot per key, and a key matching no row occupies its slot
            // with null. Non-nullable elements make that unrepresentable: GraphQL propagates the
            // null out of the list and the whole field returns null with an error, so a single
            // unmatched key would discard every matched one. Rejected at build time, because the
            // alternative is a schema that works until the first miss.
            if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List list
                    && !list.itemNullable()) {
                errors.add(new ValidationError(
                    field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': a root lookup field's "
                + "list elements must be nullable, since an unmatched key yields null at its output "
                + "position; declare the element type without '!'"),
                    field.location()
                ));
            }
        }
        if (field.orderBy() instanceof OrderBySpec.Argument) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': @orderBy is not valid on a lookup field"),
                field.location()
            ));
        }
    }
    private void validateQueryNodeField(no.sikt.graphitron.rewrite.model.QueryField.QueryNodeField field, List<ValidationError> errors) {}
    private void validateQueryTableInterfaceField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryInterfaceField(no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
    }
    private void validateQueryUnionField(no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
    }
    private void validateQueryServiceTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Unresolved service method is caught by the builder (UnclassifiedField).
        validateRootServiceTableReturn(field, field.returnType(),
            field.errorChannel().isPresent(), errors);
    }

    /**
     * The three guards a table-bound root {@code @service} return owes, stated once for the
     * query leaf and its mutation twin. All three mirror an invariant the emit relies on rather
     * than being the primary diagnostic (the house pattern), and the first is
     * classifier-guaranteed outright, so the field normally arrives here as
     * {@code UnclassifiedField} instead:
     *
     * <ul>
     *   <li><b>A keyed return table.</b> The coordinate re-selects the requested fields from the
     *       returned table keyed on each returned record's primary key, so a key-less table
     *       leaves the emitter nothing to key on. The root twin of the clause in
     *       {@link #validateServiceTableField}; primary diagnostic in
     *       {@link ServiceDirectiveResolver}'s STRICT_ROOT classify arm.</li>
     *   <li><b>Key arity.</b> The list arm's companion joins a {@code VALUES (idx, key...)}
     *       derived table whose typed row tops out at jOOQ's {@code Row22}, so the key is capped
     *       at 21 columns. The root sibling of {@code validateDmlReentryKeyArity}, and what
     *       keeps {@code ReentryRowsFragments}' claim true that the row builder's own throw is
     *       only a backstop for objects built outside the pipeline.</li>
     *   <li><b>No error channel.</b> Structurally empty today, and the guard exists to keep it
     *       that way: {@code FieldBuilder.resolveErrorChannel} answers no-channel for anything
     *       but a class-backed {@code ResultReturnType}, and a {@code @table}-bound return is not
     *       one, so the lift emit carries no channel arms. The child arm's mirror in
     *       {@link #validateServiceTableField} pins the same premise at its coordinate; if
     *       channel resolution ever widens to table-bound payloads, the first schema exercising
     *       it fails here rather than inheriting an arm shape never designed for a channel.</li>
     * </ul>
     */
    private void validateRootServiceTableReturn(
            no.sikt.graphitron.rewrite.model.OutputField field,
            ReturnTypeRef.TableBoundReturnType returnType,
            boolean hasErrorChannel, List<ValidationError> errors) {
        TableRef returnTable = returnType.table();
        if (!returnTable.hasPrimaryKey()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': @service on a "
                    + "table-bound return type requires the returned table '"
                    + returnTable.tableName() + "' to have a primary key for the keyed "
                    + "re-projection of the returned records"),
                field.location()
            ));
            return;
        }
        int keyArity = returnTable.primaryKeyColumns().size();
        if (returnType.wrapper().isList() && keyArity > 21) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': a list-returning "
                    + "@service on a @table type re-fetches the returned rows through a keyed "
                    + "re-query whose key is table '" + returnTable.tableName() + "'s primary "
                    + "key; " + keyArity + " key columns exceeds jOOQ's typed Row22 cap (key + "
                    + "idx must fit in Row<N+1>). Use a narrower primary key, or drop @table "
                    + "from the return type to keep reading the columns off the returned record"),
                field.location()
            ));
        }
        if (hasErrorChannel) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': a root @service "
                    + "field with a @table-bound return carrying an error channel is not "
                    + "supported — the keyed re-projection emit inlines its channel arms on the "
                    + "single-channel premise; widening channel resolution to table-bound "
                    + "payloads requires designing that arm first"),
                field.location()
            ));
        }
    }
    private void validateQueryServiceRecordField(no.sikt.graphitron.rewrite.model.QueryField.QueryServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
    }
    private void validateQueryServicePolymorphicField(no.sikt.graphitron.rewrite.model.QueryField.QueryServicePolymorphicField field, List<ValidationError> errors) {
        // Route (a): the @service-return arm shares the multitable participant invariants with
        // the query interface/union path (PK presence, uniform PK arity, and the same-table
        // discriminability floor), so the build error fires from the @service-return arm too.
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
    }
    private void validateQueryServiceTableInterfaceField(no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableInterfaceField field, List<ValidationError> errors) {
        // Mirror the read-side single-table floor (validateQueryTableInterfaceField), NOT the
        // multi-table one: validateMultiTableParticipants enforces the same-table *rejection*
        // floor, and single-table is precisely the shape that floor steers authors toward, so
        // applying it here would reject the valid case. The single-table invariants (single-hop
        // FK per cross-table participant field, PK-bearing shared table, resolvable discriminator
        // column, and its literals when the column's value domain is closed) are enforced upstream
        // in TypeBuilder, which demotes the interface to UnclassifiedType rather than producing a
        // TableInterfaceType at all; the variant reuses tit.participants() verbatim, so they are
        // inherited rather than re-mirrored here.
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateMutationServiceTableInterfaceField(no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableInterfaceField field, List<ValidationError> errors) {
        // Mutation twin of validateQueryServiceTableInterfaceField; same single-table floor.
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateDmlTableField(no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field, List<ValidationError> errors) {
        validateDmlReentryKeyArity(field, errors);
    }

    /**
     * DML reentry key guard, the sibling of {@link #validateChildMultiTableParentPk} on the
     * mutation side: a bulk projected / discriminated DML return re-fetches through the
     * {@code rows<Name>} companion's {@code VALUES (idx, key...)} join, whose typed row tops
     * out at jOOQ's {@code Row22} ({@code ValuesJoinRowBuilder}'s cap), so the carried reentry
     * key (the bound table's primary key) is capped at 21 columns on the list-cardinality arms.
     * Surfaces the constraint as a validate-time rejection instead of the row builder's
     * codegen-time {@code IllegalStateException}. Single-cardinality arms render plain key
     * equality with no {@code idx} slot and are exempt; {@code Encoded*} arms carry no reentry
     * and are skipped, which makes this check provably vacuous for a Delete write arm (the
     * leaf constructor pairs Delete with {@code Encoded*} returns only).
     */
    private void validateDmlReentryKeyArity(no.sikt.graphitron.rewrite.model.MutationField.DmlTableField field,
            List<ValidationError> errors) {
        no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots correlation =
            switch (field.returnExpression()) {
                case no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList p -> p.reentryCorrelation();
                case no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedList d -> d.reentryCorrelation();
                default -> null;
            };
        if (correlation == null) return;
        var keyCols = correlation.columns();
        if (keyCols.size() > 21) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': bulk @mutation with a @table "
                    + "return re-fetches the written rows through a keyed re-query whose key is table '"
                    + correlation.targetTable().tableName() + "'s primary key; " + keyCols.size()
                    + " key columns exceeds jOOQ's typed Row22 cap (key + idx must fit in Row<N+1>). "
                    + "Use a narrower primary key or return ID"),
                field.location()
            ));
        }
    }
    private void validateMutationServiceTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField field, List<ValidationError> errors) {
        // Unresolved service method is caught by the builder (UnclassifiedField).
        validateRootServiceTableReturn(field, field.returnType(),
            field.errorChannel().isPresent(), errors);
    }
    private void validateMutationServiceRecordField(no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField field, List<ValidationError> errors) {}
    private void validateMutationServicePolymorphicField(no.sikt.graphitron.rewrite.model.MutationField.MutationServicePolymorphicField field, List<ValidationError> errors) {
        // Route (a): mutation analogue of validateQueryServicePolymorphicField; same shared
        // multitable participant invariants, including the same-table discriminability floor.
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
    }
    /**
     * {@code nestedAnchor} non-null means the leaf sits under a {@link ChildField.NestingField},
     * where the immediate parent type is the plain-object nesting type. That type classifies as
     * {@link GraphitronType.NestingType} and carries no {@code @table} of its own: it inherits
     * the anchor's table context, and the leaf's columns resolved against that table. So the
     * parent-type read below answers the wrong question at nested depth, and reading the anchor
     * instead answers the right one, {@link ReturnTypeRef.TableBoundReturnType} being table-bound
     * by construction (its {@code table} is always fully resolved, or the containing field would
     * have classified as {@code UnclassifiedField}).
     */
    private void validateColumnBackedField(no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField field,
            Map<String, GraphitronType> types,
            ReturnTypeRef.TableBoundReturnType nestedAnchor,
            List<ValidationError> errors) {
        if (nestedAnchor == null && !(types.get(field.parentTypeName()) instanceof GraphitronType.TableBackedType)) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': @column is not valid on a non-table-backed type"),
                field.location()
            ));
        }
        // The record's compact constructor enforces the arity floor and the composite-implies-
        // NodeIdEncodeKeys narrowing; the upper bound matches the RecordN / RowN ceiling
        // (jOOQ's 22-slot cap). Any breach indicates a classifier bug.
        if (field.columns().size() > 22) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': composite NodeId carrier has "
                    + field.columns().size() + " columns, exceeding the 22-slot RecordN cap"),
                field.location()
            ));
        }
    }
    private void validateColumnBackedReferenceField(no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedReferenceField field, List<ValidationError> errors) {
        if (field.joinPath().isEmpty()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': @reference path is required"),
                field.location()
            ));
            return;
        }
        // Same RecordN / RowN ceiling as the non-reference carrier.
        if (field.columns().size() > 22) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': composite NodeId reference carrier has "
                    + field.columns().size() + " columns, exceeding the 22-slot RecordN cap"),
                field.location()
            ));
        }
        // The FK-mirror collapse happens at classification time, so a NodeIdEncodeKeys carrier
        // that survives here is a non-mirror reference (rooted-at-parent, multi-hop, or
        // condition-join).
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        // Recognised structurally but not yet emitted, at every arity; surface as a build-time
        // deferred rejection rather than a runtime stub (Validator mirrors classifier invariants).
        if (field.compaction() instanceof no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys) {
            emitDeferredError(field,
                (Rejection.Deferred) Rejection.deferred(
                    "ColumnBackedReferenceField NodeIdEncodeKeys (rooted-at-parent NodeId reference) not yet implemented"
                    + " — requires JOIN-with-projection emission",
                    no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedReferenceField.class),
                errors);
        }
    }

    private void validateReferenceLeadsToType(String fieldName, SourceLocation location, List<JoinStep> path, String typeName, no.sikt.graphitron.rewrite.model.TableRef targetTable, List<ValidationError> errors) {
        if (path.isEmpty()) return; // classifier guarantees non-empty for this variant; skip in isolated validator unit tests
        // Every JoinStep permit implements HasTargetTable. The comparison is
        // uniform across permits.
        var lastStep = (JoinStep.HasTargetTable) path.getLast();
        if (!lastStep.targetTable().denotesSameTableAs(targetTable)) {
            errors.add(new ValidationError(
                fieldName,
                Rejection.structural("Field '" + fieldName + "': @reference path does not lead to the table of type '" + typeName + "'"),
                location
            ));
        }
    }
    private void validateTableField(no.sikt.graphitron.rewrite.model.ChildField.TableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        if (field.lookup().isKeyed()) {
            rejectLookupConnection(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateBatchedTableField(no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        if (field.lookup().isKeyed()) {
            // The lookup-gated branch owns the Connection verdict outright: one located
            // rejection, not the ORDER-BY guard stacked on top of it.
            rejectLookupConnection(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
            return;
        }
        // Split+Connection partitions rows by parent key; without a total order, ROW_NUMBER() produces
        // silently non-deterministic slicing. Require an explicit ordering (@defaultOrder, @orderBy,
        // or a fixed list) at build time rather than letting the cursor encoder hash an empty tuple.
        // Reachable only from the Table-sourced no-lookup arm: the leaf's ctor rejects the plain
        // Record + Connection mint and the lookup branch above returns, so this guard needs no
        // sourceShape gate.
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            var orderBy = field.orderBy();
            boolean empty = orderBy instanceof no.sikt.graphitron.rewrite.model.OrderBySpec.None
                || (orderBy instanceof no.sikt.graphitron.rewrite.model.OrderBySpec.Fixed f && f.columns().isEmpty());
            if (empty) {
                errors.add(new ValidationError(
                    field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': @splitQuery connections require a non-empty ORDER BY "
                        + "(add @defaultOrder, @orderBy, or a primary key on the target table)"),
                    field.location()
                ));
            }
        }
    }

    /** The shared "lookup fields must not return a connection" verdict, on all lookup-keyed reads. */
    private static void rejectLookupConnection(String qualifiedName, graphql.language.SourceLocation location,
            FieldWrapper wrapper, List<ValidationError> errors) {
        if (wrapper instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                qualifiedName,
            Rejection.invalidSchema("Field '" + qualifiedName + "': lookup fields must not return a connection"),
                location
            ));
        }
    }
    private void validateTableInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField field, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    /**
     * The batched twin's checks are the unbatched one's: the same authored {@code @reference}
     * path and the same cardinality surface. The batch shape itself (list-only mint, the key
     * lift, the loader contract) is pinned on the leaf's compact constructor, so nothing an
     * author writes can reach a violation of it.
     */
    private void validateBatchedTableInterfaceField(
            no.sikt.graphitron.rewrite.model.ChildField.BatchedTableInterfaceField field,
            List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.InterfaceField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
        validateChildMultiTableParentPk(field.qualifiedName(), field.location(),
            field.parentTypeName(), field.sourceKey(), errors);
    }
    private void validateUnionField(no.sikt.graphitron.rewrite.model.ChildField.UnionField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
        validateChildMultiTableParentPk(field.qualifiedName(), field.location(),
            field.parentTypeName(), field.sourceKey(), errors);
    }
    private void validateBatchedInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.BatchedInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
        validateChildMultiTableParentPk(field.qualifiedName(), field.location(),
            field.parentTypeName(), field.sourceKey(), errors);
    }
    private void validateBatchedUnionField(no.sikt.graphitron.rewrite.model.ChildField.BatchedUnionField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
        validateChildMultiTableParentPk(field.qualifiedName(), field.location(),
            field.parentTypeName(), field.sourceKey(), errors);
    }
    private void validateNestingField(no.sikt.graphitron.rewrite.model.ChildField.NestingField field,
            GraphitronSchema schema, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateNestingFieldShape(field, errors);
        // Leaves at nested depth escape the top-level field walk entirely (they're inside
        // NestingField.nestedFields(), not in schema.fields()). Walk them here — this is the
        // integration point the emitter's projection helper relies on for unreachability of its
        // fallthrough arm.
        walkNestedVariants(field.nestedFields(), schema, types, field.returnType(), errors);
    }

    /**
     * The nesting field's own checks, everything {@link #validateNestingField} does apart from
     * descending into {@link ChildField.NestingField#nestedFields()}. Split out so the nested walk
     * can apply it at every level: a nesting type nested inside another nesting type never reaches
     * the dispatch switch, so before the split the list-cardinality rejection fired only at the
     * top level and a list-shaped inner nesting type went unchecked.
     */
    private void validateNestingFieldShape(no.sikt.graphitron.rewrite.model.ChildField.NestingField field,
            List<ValidationError> errors) {
        // List cardinality has no source-passthrough semantic: one parent Record in, one list value out.
        if (field.returnType().wrapper() instanceof FieldWrapper.List) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': list cardinality on a plain-object nesting field is not supported"),
                field.location()
            ));
        }
    }

    /**
     * Shared by both {@code @pivot} delivery leaves: mirrors at validate time the classifier
     * invariants that survive into the model. The schema-shape rejections (non-null / non-scalar
     * slots, vocabulary misses, unresolved columns, path shape, record parent, list return) fire
     * at classify time as typed {@link no.sikt.graphitron.rewrite.model.PivotError} arms via
     * {@code UnclassifiedField}; what remains checkable on the classified leaf is the
     * distinct-token invariant (the emitter would otherwise project two identical aggregates
     * under different aliases) plus the slot leaves' own validation, routed through the same
     * {@link #validateVariantSpecific} the top-level walk and the nested walk use. A slot sits at
     * ordinary depth (its parent is the {@code @pivot} coordinate's own type, table-backed), so it
     * passes no nesting anchor.
     */
    private void validatePivotSpec(no.sikt.graphitron.rewrite.model.ChildField.PivotSpecField field,
            GraphitronSchema schema, Map<String, GraphitronType> types, List<ValidationError> errors) {
        var slotsByToken = new java.util.LinkedHashMap<String, List<String>>();
        field.pivot().tokenBySlot().forEach((slot, token) ->
            slotsByToken.computeIfAbsent(token, k -> new java.util.ArrayList<>()).add(slot));
        slotsByToken.forEach((token, slots) -> {
            if (slots.size() > 1) {
                slots.sort(String::compareTo);
                errors.add(new ValidationError(
                    field.qualifiedName(),
                    new no.sikt.graphitron.rewrite.model.PivotError.DuplicateSlotToken(token, slots),
                    field.location()));
            }
        });
        for (var slot : field.spec().slots()) {
            validateVariantSpecific(slot, schema, types, null, errors);
        }
    }

    /**
     * Validates the leaves of a {@link ChildField.NestingField}, recursively. Each non-nesting leaf
     * runs the full {@link #validateVariantSpecific} pass plus the nested-depth wireability gate;
     * each nested nesting field runs its own {@link #validateNestingFieldShape} and hands its
     * return type down as the anchor for its own leaves.
     *
     * <p>Nesting fields are not routed through {@link #validateVariantSpecific}: its
     * {@code NestingField} arm walks the subtree, which would re-enter this walk and double-report
     * everything below.
     */
    private void walkNestedVariants(List<ChildField> fields, GraphitronSchema schema,
            Map<String, GraphitronType> types,
            ReturnTypeRef.TableBoundReturnType anchor,
            List<ValidationError> errors) {
        for (var f : fields) {
            if (f instanceof ChildField.NestingField nf) {
                validateNestingFieldShape(nf, errors);
                walkNestedVariants(nf.nestedFields(), schema, types, nf.returnType(), errors);
            } else {
                validateVariantSpecific(f, schema, types, anchor, errors);
                validateVariantIsSupportedAtNestedDepth(f, errors);
            }
        }
    }

    /**
     * Variants wireable at nested depth. Every leaf here is wired through the nested
     * type's own {@code <NestedTypeName>Fetchers} class: the column/table reads
     * ({@code ColumnBackedField}, {@code TableField} with or without a keyed lookup,
     * {@code NestingField}) are reified onto it by {@code FetcherEmitter.bind}, and the
     * class-backed leaves (the Table-sourced {@code BatchedTableField} arms, lookup-keyed or
     * not) carry their heavy methods there. {@code TypeFetcherGenerator} emits that class for any nested type owning
     * a fetcher (the {@code FetcherEmitter.nestedTypeOwnsFetchers} gate shared with
     * {@code FetcherRegistrationsEmitter.nestedBody}, via a separate walk over
     * {@code NestingField.nestedFields()}).
     *
     * <p>Admitting a projected leaf here is a validator question, not an emitter one. The projected
     * leaves ({@code ColumnBackedField}, {@code ColumnBackedReferenceField}, {@code ComputedField})
     * reach nested depth through a projection unit minted per anchor
     * ({@code ProjectionCommands.mintNestedUnit}), whose {@code $project} takes the anchor's own
     * {@code table} local, so their contributions correlate on the parent table by construction;
     * and {@code FetcherEmitter.bind} reads their values back off the source record by
     * {@code __rk_<resultKey>} alias without consulting the parent table, so one shared
     * {@code <Type>Fetchers} class serves every parent. What such a leaf does need is its own
     * per-variant validation at nested depth, which {@link #walkNestedVariants} runs. The leaves
     * that would need real emit work are the class-backed and record-sourced ones, whose key lift
     * and result mapping do read the parent's identity.
     *
     * <p>A predicate rather than a class set: {@code BatchedTableField} is wireable at nested
     * depth only on its Table-sourced arm; a nested plain-object type shares the parent's table
     * context, which the record-sourced arm's key lift does not read. Admitting record-sourced
     * instances here would silently wire them into nested fetchers the emit arms never supported.
     */
    private static boolean isNestedWireableLeaf(GraphitronField field) {
        return switch (field) {
            case ChildField.ColumnBackedField ignored -> true;
            // Not gated on CallSiteCompaction.Direct: validateColumnBackedReferenceField now runs
            // at nested depth and rejects the NodeIdEncodeKeys carrier on its own account, naming
            // the missing capability (JOIN-with-projection emission). A compaction gate here would
            // re-shadow that with the vaguer nested-depth deferral below.
            case ChildField.ColumnBackedReferenceField ignored -> true;
            case ChildField.ComputedField ignored -> true;
            case ChildField.TableField ignored -> true;
            case ChildField.NestingField ignored -> true;
            case ChildField.BatchedTableField f -> f.sourceShape() == no.sikt.graphitron.rewrite.model.SourceShape.Table;
            default -> false;
        };
    }

    private void validateVariantIsSupportedAtNestedDepth(GraphitronField field, List<ValidationError> errors) {
        // Stubbed variants already surfaced by validateVariantIsImplemented, don't double-report.
        if (TypeFetcherGenerator.STUBBED_VARIANTS.containsKey(field.getClass())) {
            return;
        }
        if (!isNestedWireableLeaf(field)) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.deferred("Field '" + field.qualifiedName() + "': " + field.getClass().getSimpleName()
                    + " is not yet supported under NestingField"),
                field.location()
            ));
        }
    }

    /**
     * Schema-level parent-compatibility check for shared nesting types. When two or more {@code @table}
     * parents declare a field of the same plain-object nesting type, each parent independently classifies
     * its own {@code nestedFields} against its own table (the <em>anchor</em>). The representative is the
     * first parent in SDL order; every subsequent parent's {@code nestedFields} must match the
     * representative's shape field by field, starting with name and leaf class.
     *
     * <h2>The rule the arms are instances of</h2>
     *
     * <p>A leaf is multi-parent-safe when every generated unit and method address it mints under a
     * nested type carries the anchor; the arm then compares exactly the inputs of the addresses that
     * do <em>not</em>. Per-anchor facts are deliberately left uncompared, because each anchor renders
     * its own artifact from them.
     *
     * <ul>
     *   <li>{@link ChildField.ColumnBackedField}: compares terminal SQL name and Java column class,
     *       because the one shared nested-type fetchers class reads through jOOQ's name-based typed
     *       {@code Record.get(Field)} and a wiring emitted with the representative's {@code Field<T>}
     *       must resolve against any anchor's {@code Record}.</li>
     *   <li>{@link ChildField.TableField}: compares {@code filters()}, because one generated condition
     *       method serves every reuse site; skips {@code joinPath} / {@code orderBy} /
     *       {@code pagination}, whose projection unit is minted per anchor.</li>
     *   <li>{@link ResultKeyAliasedField} projected leaves ({@link ChildField.ComputedField},
     *       {@link ChildField.ColumnBackedReferenceField}): compares {@code domainReturnType()} and,
     *       for the reference half, the {@code joinPath()} terminus, because the shared fetchers class
     *       carries one typed read per coordinate.</li>
     *   <li>{@link ChildField.NestingField}: recursion, so a divergent inner leaf cannot hide behind
     *       the outer class-equality check.</li>
     * </ul>
     *
     * <p>A leaf whose address does not carry the anchor cannot be admitted by comparison alone; the
     * catch-all defers it, and names today's resident at the rejection site.
     */
    private void validateNestingParentCompat(GraphitronSchema schema, List<ValidationError> errors) {
        var grouped = new java.util.LinkedHashMap<String, List<ChildField.NestingField>>();
        schema.fields().values().forEach(f -> {
            if (f instanceof ChildField.NestingField nf) {
                grouped.computeIfAbsent(nf.returnType().returnTypeName(), k -> new ArrayList<>()).add(nf);
            }
        });
        for (var group : grouped.values()) {
            if (group.size() < 2) {
                continue;
            }
            var rep = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                compareNestedFieldsShape(rep, group.get(i), errors);
            }
        }
    }

    /**
     * Rejects any mixed-source coordinate whose reified shape-set union
     * ({@link GraphitronSchema#reachableSourceShapes}) is a combination no emitter arm serves. Reads the
     * same reified fact the dispatch emitter dispatches on, so emitter and validator cannot drift on which
     * combinations are supported. The {@code JooqRecordCarrier} + nesting mix
     * ({@link no.sikt.graphitron.rewrite.model.ReachableSourceShape#REJECTED}) rejects a
     * {@code @table} parent embedding a jOOQ-record-carrier result.
     */
    private void validateReachableSourceShapes(GraphitronSchema schema, List<ValidationError> errors) {
        schema.mixedSourceCoordinates().forEach((coord, shapes) -> {
            if (no.sikt.graphitron.rewrite.model.ReachableSourceShape.isEmittable(shapes)) {
                return;
            }
            var field = schema.fields().get(coord);
            SourceLocation location = field == null ? null : field.location();
            errors.add(new ValidationError(
                coord.getTypeName() + "." + coord.getFieldName(),
                Rejection.structural("Field '" + coord.getTypeName() + "." + coord.getFieldName()
                    + "' is reached through source shapes " + shapes + ", a combination no fetcher serves. "
                    + "A directiveless type reached as both a nesting projection of a @table parent and a "
                    + "jOOQ-record-carrier result cannot be served by one datafetcher: both arms would read "
                    + "a jOOQ Record with independently derived read names. Split '" + coord.getTypeName()
                    + "' into two type names, or back it with @table so the nesting projection and the "
                    + "carrier agree on the row shape."),
                location));
        });
    }

    private void compareNestedFieldsShape(ChildField.NestingField rep, ChildField.NestingField other,
                                          List<ValidationError> errors) {
        compareNestedFieldsShape(rep, other, rep.parentTypeName(), other.parentTypeName(), errors);
    }

    private void compareNestedFieldsShape(ChildField.NestingField rep, ChildField.NestingField other,
                                          String repParent, String otherParent,
                                          List<ValidationError> errors) {
        var nestedTypeName = rep.returnType().returnTypeName();
        var repByName = new java.util.LinkedHashMap<String, ChildField>();
        rep.nestedFields().forEach(f -> repByName.put(f.name(), f));
        var otherByName = new java.util.LinkedHashMap<String, ChildField>();
        other.nestedFields().forEach(f -> otherByName.put(f.name(), f));

        for (var entry : repByName.entrySet()) {
            var name = entry.getKey();
            var rf = entry.getValue();
            var of = otherByName.get(name);
            String coord = otherParent + "." + other.name();
            if (of == null) {
                errors.add(new ValidationError(
                    coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' exists on the first but not the second"),
                    other.location()
                ));
                continue;
            }
            if (!rf.getClass().equals(of.getClass())) {
                // A membership split on ResultKeyAliasedField is not an authored mistake, so it is
                // deferred rather than structural. Exactly one route reaches it: an @nodeId
                // reference whose FK-mirror collapse resolves to the anchor's own key columns on
                // one anchor (a plain ColumnBackedField) and needs a join on the other (a
                // ColumnBackedReferenceField), because the two anchors enter the same node target
                // from opposite ends of their foreign keys. Directives are otherwise per
                // declaration, so no other shape can diverge. Reachable schemas always co-reject
                // on the reference side's own NodeIdEncodeKeys deferral, which is why the wording
                // stays on the FK-orientation fact rather than on a projected-alias read that side
                // never performs.
                boolean membershipDiffers =
                    rf instanceof ResultKeyAliasedField != of instanceof ResultKeyAliasedField;
                var summary = "Nested type '" + nestedTypeName + "' shared across '" + repParent
                    + "' and '" + otherParent + "': field '" + name
                    + "' classifies as " + rf.getClass().getSimpleName() + " on the first but "
                    + of.getClass().getSimpleName() + " on the second";
                errors.add(new ValidationError(
                    coord,
                    membershipDiffers
                        ? Rejection.deferred(summary + ", because the two parents enter the same "
                            + "node target from opposite ends of their foreign keys: one resolves "
                            + "to the parent's own key columns and the other needs a join, and one "
                            + "generated fetchers class carries one read", rf.getClass())
                        : Rejection.structural(summary),
                    other.location()
                ));
                continue;
            }
            if (rf instanceof ChildField.ColumnBackedField rcf && of instanceof ChildField.ColumnBackedField ocf) {
                var repSql = rcf.columns().stream().map(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName).toList();
                var otherSql = ocf.columns().stream().map(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName).toList();
                var repClasses = rcf.columns().stream().map(no.sikt.graphitron.rewrite.model.ColumnRef::columnClass).toList();
                var otherClasses = ocf.columns().stream().map(no.sikt.graphitron.rewrite.model.ColumnRef::columnClass).toList();
                if (!repSql.equals(otherSql)) {
                    errors.add(new ValidationError(
                        coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' resolves to column '" + String.join(", ", repSql) + "' on the first but '"
                            + String.join(", ", otherSql) + "' on the second"),
                        other.location()
                    ));
                } else if (!repClasses.equals(otherClasses)) {
                    errors.add(new ValidationError(
                        coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' has Java type '" + String.join(", ", repClasses) + "' on the first but '"
                            + String.join(", ", otherClasses) + "' on the second"),
                        other.location()
                    ));
                }
            } else if (rf instanceof ChildField.TableField rtf && of instanceof ChildField.TableField otf) {
                // TableField's join topology is safe to share across parents: each parent's $project
                // emits its own DSL.multiset arm (per-parent joinPath / orderBy / pagination are
                // intentionally not compared), and the reified projected read (the plan-derived projected dispatch) reads
                // by field name from the source Record without consulting the outer parent table;
                // returnType() derives from the single SDL declaration on the shared nested type and
                // is identical by construction. The FILTERS are not per-parent any more: one condition
                // glue method per nested coordinate serves every reuse site, so the sites must agree
                // on the filter list (the condition producer's dedup fails hard on the same fact; this
                // is the build-boundary mirror that names the diverging parents).
                if (!rtf.filters().equals(otf.filters())) {
                    errors.add(new ValidationError(
                        coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' classifies different condition filters at the two reuse sites, and "
                            + "one generated condition method serves every site"),
                        other.location()
                    ));
                }
            } else if (rf instanceof ChildField.NestingField rnf && of instanceof ChildField.NestingField onf) {
                // Two-level nesting: recurse so divergent inner columns don't slip past the
                // outer class-equality check. Thread the outer @table parent names so inner
                // errors still name the original tables, not the intermediate nested type.
                compareNestedFieldsShape(rnf, onf, repParent, otherParent, errors);
            } else if (rf instanceof ResultKeyAliasedField && !(rf instanceof ChildField.PivotField)) {
                // MUST STAY BELOW THE TableField ARM. TableField is a ResultKeyAliasedField too, so
                // an arm placed above it swallows it and its filters() comparison silently stops
                // running; the negative test on that message is what holds this ordering.
                //
                // The projected leaves ColumnBackedReferenceField and ComputedField need no emitter
                // work to be shared: every address they mint under a nested type carries the anchor
                // (ProjectionCommands mints the projection unit per anchor, and its $project
                // receives the anchor's own table local), and the value is read back off the source
                // record by result-key alias without consulting the parent, so which parent
                // registers the shared nested type is output-irrelevant.
                //
                // The capability alone is NOT the admission predicate, which is why PivotField is
                // excluded here rather than merely absent: it projects under a result-key alias and
                // is a legitimate member, but its unit is addressed on (parentTypeName, fieldName),
                // and a nested leaf's parentTypeName is the nested type, so its address does not
                // carry the anchor. isNestedWireableLeaf keeps it off this gate today, so the
                // exclusion is unreachable; it is here so the address premise is a condition rather
                // than a remembered one. A future widening of isNestedWireableLeaf to another
                // member revisits this arm as part of its own work.
                //
                // domainReturnType() is the base comparison because it is the read-side analogue of
                // the ColumnBackedField arm's columnClass check, and it is the reference half that
                // gives it work: that leaf's claim carries the terminal column's own type, which is
                // anchor-derived on a {key: "..."} path. On the computed half every fact the leaf
                // stores bar its (still per-variant-deferred) joinPath derives from the single SDL
                // declaration on the shared nested type, so the two sides agree by construction and
                // the check is total rather than discriminating.
                if (!rf.domainReturnType().equals(of.domainReturnType())) {
                    errors.add(new ValidationError(
                        coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' projects Java type '" + rf.domainReturnType() + "' on the first but '"
                            + of.domainReturnType() + "' on the second, and one generated fetchers "
                            + "class carries one typed read per coordinate"),
                        other.location()
                    ));
                } else if (rf instanceof ChildField.ColumnBackedReferenceField rref
                        && of instanceof ChildField.ColumnBackedReferenceField oref) {
                    // One fact more than domainReturnType(): where the @reference path ends. A
                    // {key: "..."} first step resolves from EITHER endpoint of the named FK, so two
                    // anchors sitting on opposite ends traverse it in opposite directions and read
                    // two different tables' same-named, same-typed column. domainReturnType() sees
                    // no difference there. Terminal table, not terminal column name, is the grain
                    // that separates them. Read off the model (every JoinStep permit mixes in
                    // HasTargetTable with a pre-resolved targetTable()) rather than through
                    // ServiceCatalog: this validator holds no catalog, and the catalog method
                    // answers empty for a non-FK-derived terminus where the comparison still wants
                    // both sides. The path is non-empty on any schema that gets here, because
                    // validateColumnBackedReferenceField rejects an empty one per parent instance.
                    var repTerminal = terminalTableOf(rref.joinPath());
                    var otherTerminal = terminalTableOf(oref.joinPath());
                    if (repTerminal != null && otherTerminal != null
                            && !repTerminal.denotesSameTableAs(otherTerminal)) {
                        errors.add(new ValidationError(
                            coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                                + "' and '" + otherParent + "': field '" + name
                                + "' ends its @reference path on table '" + repTerminal.tableName()
                                + "' on the first but '" + otherTerminal.tableName()
                                + "' on the second, and one generated fetchers class carries one "
                                + "read per coordinate"),
                            other.location()
                        ));
                    }
                }
            } else if (!(rf instanceof ChildField.NestingField)) {
                // The catch-all. A leaf lands here when an address it mints under a nested type is
                // keyed on the nested type while the fact behind it is per-anchor, so no comparison
                // of the two sides can make them agree.
                //
                // Today's sole resident is the Table-sourced arm of BatchedTableField, on both its
                // authoring shapes (a plain @splitQuery and a lookup-keyed instance, since
                // isNestedWireableLeaf gates on sourceShape() and reads nothing about lookup()):
                // GeneratedUnits.rowsMethod addresses the rows method on the nested type, so every
                // sharing parent mints the identical <NestedType>Fetchers#rows<Field> reference and
                // the coordinate gets one DataFetcher, while deriveSplitQuerySource reads the batch
                // grain off each anchor's own correlation columns, so each parent legitimately
                // wants a different rows-method body and key lift. Admitting it needs per-anchor
                // minting plus a runtime dispatch, inside the single registered fetcher, on which
                // anchor the arriving source row came from; where that discriminator lives on a
                // projected source row is an open design problem, and no measured downstream demand
                // asks for it. Other BatchKey carriers never reach here: they are rejected at
                // nested depth per variant by validateVariantIsSupportedAtNestedDepth first.
                errors.add(new ValidationError(
                    coord,
            Rejection.deferred("Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' classifies as " + rf.getClass().getSimpleName()
                        + " which is not yet supported across multiple parents", rf.getClass()),
                    other.location()
                ));
            }
        }
        String coord = otherParent + "." + other.name();
        for (var name : otherByName.keySet()) {
            if (!repByName.containsKey(name)) {
                errors.add(new ValidationError(
                    coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' exists on the second but not the first"),
                    other.location()
                ));
            }
        }
    }

    /**
     * The table a {@code @reference} path ends on, read straight off the model: every
     * {@link JoinStep} permit mixes in {@link JoinStep.HasTargetTable}, so the last step always
     * carries a pre-resolved {@link TableRef} with no lookup and no {@code Optional}.
     * {@code null} only for an empty path, which {@code validateColumnBackedReferenceField}
     * rejects on its own account.
     */
    private static TableRef terminalTableOf(List<JoinStep> joinPath) {
        if (joinPath.isEmpty()) {
            return null;
        }
        return ((JoinStep.HasTargetTable) joinPath.get(joinPath.size() - 1)).targetTable();
    }

    private void validateServiceTableField(no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);

        // Enforces the channel-less premise on the service reentry path (DML reentry has its
        // own channel transport and fetcher family): the service reentry fetcher inlines the
        // channel catch / early-return arms into the Fetcher with no independent seam, on the
        // premise that reentry @service fields are channel-less. The slot is provably empty
        // today (resolveErrorChannel guards on ResultReturnType; this return is table-bound),
        // so the inlined arm shape cannot vary within the family. If channel resolution ever
        // widens to table-bound payloads, the first schema exercising it fails here, at the
        // build boundary, instead of inheriting an inlined arm shape never designed for a
        // channel.
        if (field.emitsKeyedReQuery() && field.errorChannel().isPresent()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': a reentry @service field "
                    + "carrying an error channel is not supported — the reentry fetcher inlines its "
                    + "channel arms on the single-channel premise; widening "
                    + "channel resolution to table-bound payloads requires designing that arm first"),
                field.location()
            ));
        }

        var smr = field.method();

        // A table-bound service field requires at least one Sources parameter for DataLoader
        // batching. Classifier-guaranteed since the child @service coordinate rejects a Sources-less
        // signature outright; this is the mirror, not the primary diagnostic.
        boolean hasSources = smr.params().stream().anyMatch(p -> p instanceof MethodRef.Param.Sourced);
        if (!hasSources) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': @service on a table-bound return type requires a Sources parameter for DataLoader batching"),
                field.location()
            ));
            return;
        }

        // The lift re-projects the service result by joining the returned table on its own primary
        // key (identity re-projection); a PK-less returned table gives the emitter no key to extract.
        // Mirrors the key-owner-PK invariant below: a classifier guarantee the lifted emitter relies
        // on, not a transient stopgap.
        TableRef returnTable = field.returnType().table();
        if (!returnTable.hasPrimaryKey()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': @service on a table-bound return type requires the returned table '" + returnTable.tableName() + "' to have a primary key for identity re-projection"),
                field.location()
            ));
        }

        validateServiceBatchKey(field, field.keySource(), field.sourceKey(), errors);
    }

    private void validateServiceRecordField(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        if (!field.joinPath().isEmpty()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.deferred("Field '" + field.qualifiedName() + "': @service with a @reference path "
                    + "(condition-join lift form) is not yet supported"),
                field.location()));
            return;
        }
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);

        // The sibling's Sources-required check, which this leaf never carried. It is the leaf the
        // Result and Scalar classify arms mint on both parent kinds, so it is the one that most needs
        // the mirror; classifier-guaranteed, like the table-bound copy.
        if (field.method().params().stream().noneMatch(p -> p instanceof MethodRef.Param.Sourced)) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': a child @service field "
                    + "requires a Sources parameter for DataLoader batching"),
                field.location()));
            return;
        }
        validateServiceBatchKey(field, field.keySource(), field.sourceKey(), errors);
    }

    /**
     * The batched child {@code @service} invariant, stated once for both service leaves: a
     * {@code Sources} parameter exists, and its columns are the key owner's primary key.
     *
     * <p>Every clause mirrors a {@link ServiceDirectiveResolver} classify-time rejection rather than
     * being the primary diagnostic (the house pattern: the validator mirrors classifier invariants).
     * The key owner is the parent's own table only when the parent carries {@code @table}; on a
     * class-backed parent it is the table the {@code Sources} element type names, which is why this
     * reads the leaf's stored key source rather than looking the parent type up.
     */
    private void validateServiceBatchKey(GraphitronField field,
            no.sikt.graphitron.rewrite.model.ServiceKeySource keySource,
            no.sikt.graphitron.rewrite.model.SourceKey sourceKey, List<ValidationError> errors) {
        TableRef keyOwner = keySource.keyOwner();
        if (!keyOwner.hasPrimaryKey()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': @service batches on table '"
                    + keyOwner.tableName() + "', which has no primary key, so there is nothing to key "
                    + "the batch on"),
                field.location()));
            return;
        }
        if (!sourceKey.columns().equals(keyOwner.primaryKeyColumns())) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': @service batch key columns "
                    + "do not match the primary key of the table they are read through ('"
                    + keyOwner.tableName() + "')"),
                field.location()));
        }
    }
    /**
     * The record-read leaf's cross-axis gating rule: each {@link ValueLocator} arm is only
     * admissible under the parent source-object shape whose cast the emitter's corresponding
     * read arm performs ({@code FetcherEmitter.recordReadBinding}). The leaf cannot carry the
     * parent classification, so a compact constructor cannot see this; checking it here is what
     * makes the emitter's parent-shape casts guaranteed by a checked fact instead of
     * construction-site coincidence. {@link ValueLocator.DefaultRead} is unconstrained:
     * graphitron locates nothing, so no cast depends on the parent shape.
     *
     * <p>Accessor-resolution rejection routes through {@code UnclassifiedField} at classify time
     * (FieldBuilder), so the {@link ValueLocator.JavaAccessor} arm is statically
     * {@code AccessorResolution.Resolved}.
     */
    private void validateRecordReadField(no.sikt.graphitron.rewrite.model.ChildField.RecordReadField field,
            Map<String, GraphitronType> types, List<ValidationError> errors) {
        var parent = types.get(field.parentTypeName());
        if (parent == null) return;
        String incompatible = switch (field.locator()) {
            case ValueLocator.TypedColumn ignored ->
                parent instanceof GraphitronType.JooqTableRecordType jtrt && jtrt.table() != null
                    ? null
                    : "a TypedColumn locator requires a jOOQ table-record-backed parent with a resolved table";
            case ValueLocator.JavaAccessor ignored ->
                parent instanceof GraphitronType.JavaRecordType
                        || parent instanceof GraphitronType.PojoResultType.Backed
                    ? null
                    : "a JavaAccessor locator requires a class-backed parent (Java record or POJO)";
            case ValueLocator.ByName ignored ->
                parent instanceof GraphitronType.JooqRecordCarrier
                    ? null
                    : "a ByName locator requires a jOOQ-record-carrier parent";
            case ValueLocator.DefaultRead ignored -> null;
        };
        if (incompatible != null) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': " + incompatible
                    + "; parent type '" + field.parentTypeName() + "' classifies as "
                    + parent.getClass().getSimpleName()),
                field.location()
            ));
        }
    }

    private void validateComputedField(no.sikt.graphitron.rewrite.model.ChildField.ComputedField field, List<ValidationError> errors) {
        if (!field.joinPath().isEmpty()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.deferred("Field '" + field.qualifiedName() + "': @externalField with a @reference path "
                    + "(condition-join lift form) is not yet supported"),
                field.location()));
            return;
        }
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
    }
    private static void validateInputColumnBackedReferenceField(no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField field, List<ValidationError> errors) {
        // Column and join path resolution is guaranteed by the builder (unresolved → UnclassifiedType).
        //
        // A plain @reference filter path may carry any hop kind the path parser mints: the reach
        // emission dispatches per hop on the On seal, so a developer-supplied predicate hop
        // correlates through its two-argument call. There is no check for that shape here, and its
        // absence is the contract; the pipeline cases on both filter surfaces are its enforcer.
        //
        // What stays closed is the FK-target @nodeId + @condition shape below. This is a policy
        // deferral, not an emitter precondition: that carrier binds *decoded id columns* to the
        // path's FK slots, a different mechanism from a wire value compared against a terminal
        // column, and nobody has designed what a predicate hop means for it. So it is stated as a
        // deferral and the pinned validator case stands on its own rather than riding an
        // FK-only-reach invariant the emitter no longer has.
        if (field.condition().isPresent() && !field.joinPath().isEmpty()
                && field.joinPath().stream().anyMatch(h -> !(h instanceof no.sikt.graphitron.rewrite.model.JoinStep.Hop hh && hh.on() instanceof no.sikt.graphitron.rewrite.model.On.ColumnPairs))) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.deferred("Input field '" + field.qualifiedName()
                    + "': @condition on " + (field.isComposite()
                        ? "a composite-key FK-target @nodeId field"
                        : "an FK-target @nodeId field")
                    + " requires a foreign-key join path; the resolved path contains a "
                    + "non-foreign-key hop, which is not yet supported for this carrier. The "
                    + "decoded id columns bind to the path's foreign-key slots, so a "
                    + "developer-supplied predicate hop has nothing to bind them to; a plain "
                    + "`@reference` filter on the same path is supported."),
                field.location()
            ));
        }
    }
    private void validateInputNestingField(no.sikt.graphitron.rewrite.model.InputField.NestingField field, List<ValidationError> errors) {
        // Nested field columns are resolved at classification time; no additional structural checks needed.
    }
    private void validateUnclassifiedType(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType type, List<ValidationError> errors) {
        errors.add(ValidationError.forType(type.name(), type.rejection(), type.location()));
    }

    private void validateUnclassifiedField(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField field, List<ValidationError> errors) {
        errors.add(ValidationError.forField(field.qualifiedName(), field.rejection(), field.location()));
    }

    private void validateCardinality(String fieldName, SourceLocation location, no.sikt.graphitron.rewrite.model.FieldWrapper cardinality, List<ValidationError> errors) {
        // Order specs are fully resolved to ColumnOrder at build time; no per-variant validation
        // is needed here. The switch exhausts all cases to keep the compiler warning-free.
        switch (cardinality) {
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Single ignored -> {}
            case no.sikt.graphitron.rewrite.model.FieldWrapper.List ignored -> {}
            case no.sikt.graphitron.rewrite.model.FieldWrapper.Connection ignored -> {}
        }
    }

    /**
     * No-op: all path elements are guaranteed resolved by the builder (unresolved paths produce
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} instead).
     *
     * <p>Path <em>shape</em> is likewise gated at classification time, not here: the single-table
     * {@code TableInterfaceField} arm through {@code FieldBuilder.validateSingleHopFkJoin}, and the
     * multi-table interface/union child arm through {@code FieldBuilder.resolveChildPolymorphicJoinPaths},
     * which carries its resolved correlation as a
     * {@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation} so an unsupported join shape is
     * unrepresentable downstream. No reference-path shape rule is left for the validator to enforce.
     */
    private void validateReferencePath(String fieldName, SourceLocation location, List<JoinStep> path, List<ValidationError> errors) {
        // All elements are resolved and the join-path shape is gated in the builder; nothing to check.
    }

    /**
     * {@link ChildField.ErrorsField} variants carry their data through either
     * {@link ChildField.Transport.PayloadAccessor} (the parent payload's errors-named property) or
     * {@link ChildField.Transport.LocalContext} (graphql-java's {@code DataFetcherResult.localContext}
     * slot). The latter pairs with an {@link no.sikt.graphitron.rewrite.model.ErrorChannel.LocalContext}
     * catch arm in {@code TypeFetcherGenerator} that ships
     * {@code data(null).localContext(errors).build()}: the parent payload is bypassed entirely and
     * the sibling data-channel field's fetcher fires against a {@code null} source.
     *
     * <p>The fetcher emitted for that sibling must short-circuit on {@code null} source and return
     * {@code null}; otherwise the catch path renders {@code data} as a corrupt half-payload instead
     * of the SDL-level {@code data: null, errors: [...]} shape. The {@code @service}-payload
     * lifting in {@code FieldBuilder.findPayloadErrorsBinding} keeps the data-channel role's
     * variants within {@link #isLocalContextGuardedDataChannel} by construction; widening the
     * admission to a new variant requires extending the allow-list along with the matching
     * fetcher's null-source guard. This validator pass is the cross-check that turns a
     * silently-broken admission into a build-time {@link Rejection.AuthorError.Structural}.
     */
    private void validateLocalContextErrorsFieldGuards(GraphitronSchema schema, List<ValidationError> errors) {
        for (var f : schema.fields().values()) {
            if (!(f instanceof ChildField.ErrorsField ef)) continue;
            if (!(ef.transport() instanceof ChildField.Transport.LocalContext)) continue;
            for (var sib : schema.fieldsOf(ef.parentTypeName())) {
                if (sib == ef) continue;
                if (sib instanceof ChildField.ErrorsField) continue;
                if (!isLocalContextGuardedDataChannel(sib)) {
                    // Name the disqualifying fact, not just the class: a lookup-keyed batched
                    // read shares its class with an admitted plain read and differs only on
                    // the resolution axis.
                    String siblingShape = sib instanceof ChildField.TableTargetField ttf
                            && ttf.lookup().isKeyed()
                        ? sib.getClass().getSimpleName() + " with a keyed lookup"
                        : sib.getClass().getSimpleName();
                    errors.add(new ValidationError(
                        ef.qualifiedName(),
                        Rejection.structural("Field '" + ef.qualifiedName()
                            + "': LocalContext errors transport requires the carrier's data-channel "
                            + "fetcher to short-circuit on a null source, but sibling field '"
                            + sib.qualifiedName() + "' classifies as " + siblingShape
                            + " which is not on the LocalContext-safe allow-list"),
                        ef.location()
                    ));
                }
            }
        }
    }

    /**
     * Field variants whose generated fetcher honors the null-source short-circuit guard at emit
     * time, making them safe siblings for an {@link ChildField.ErrorsField} with
     * {@link ChildField.Transport.LocalContext}:
     *
     * <ul>
     *   <li>Record-sourced {@code BatchedTableField} → the Record-shape arm of
     *       {@code TypeFetcherGenerator.buildBatchedDataFetcher} (explicit
     *       {@code if (env.getSource() == null) return completedFuture(null);} prelude before
     *       the key read; the Outcome arm's {@code instanceof Success} narrowing also rejects
     *       null). The Table-sourced arm is deliberately excluded: it emits an empty prelude
     *       with no null-source guard.</li>
     *   <li>{@code SingleRecordIdFieldFromReturning} →
     *       {@code FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue} (explicit
     *       {@code if (source == null) return null;} guard before encoder dispatch).</li>
     * </ul>
     *
     * <p>The lookup-keyed shape is not admitted, although it routes through the same
     * source-shape-gated builder arms as the plain read; widening the allow-list is a
     * validator behavior change that needs its own validation-coverage decision, so the
     * lookup fold preserved the exclusion as the resolution gate below rather than widening
     * it by rename.
     *
     * <p>Admitting a variant here requires the matching emitter site to honor the guard;
     * removing the guard from an existing emitter arm must remove the variant here.
     */
    private static boolean isLocalContextGuardedDataChannel(GraphitronField field) {
        return switch (field) {
            case ChildField.BatchedTableField f ->
                f.sourceShape() == no.sikt.graphitron.rewrite.model.SourceShape.Record
                    && f.lookup() instanceof no.sikt.graphitron.rewrite.model.LookupResolution.None;
            case ChildField.SingleRecordIdFieldFromReturning ignored -> true;
            default -> false;
        };
    }

    /**
     * Mirror-the-classifier check for the single-errors-field invariant on outcome types.
     * The binary {@code Outcome} witness ({@code Success | ErrorList}) has one error slot, so a type
     * carrying two {@link ChildField.ErrorsField} children has no well-defined success/error fork.
     * This is the validator face of the {@code OutcomeType} classification's
     * {@link no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.MultipleErrorsFields} rejection;
     * it runs over the classified model so a mis-shaped type fails the build rather than reaching
     * the walker.
     *
     * <p>Pure model check, independent of the errors-field transport: any object type with two or
     * more errors fields is rejected, regardless of whether it uses the wrapper, localContext, or
     * payload-accessor transport.
     */
    private void validateOutcomeTypeShape(GraphitronSchema schema, List<ValidationError> errors) {
        java.util.Map<String, List<ChildField.ErrorsField>> byParent = new java.util.LinkedHashMap<>();
        for (var f : schema.fields().values()) {
            if (f instanceof ChildField.ErrorsField ef) {
                byParent.computeIfAbsent(ef.parentTypeName(), k -> new ArrayList<>()).add(ef);
            }
        }
        for (var entry : byParent.entrySet()) {
            var errorsFields = entry.getValue();
            if (errorsFields.size() < 2) continue;
            var names = errorsFields.stream().map(ChildField.ErrorsField::name).toList();
            var first = errorsFields.get(0);
            errors.add(new ValidationError(
                first.qualifiedName(),
                new no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.MultipleErrorsFields(
                    entry.getKey(), names),
                first.location()
            ));
        }
    }

    /**
     * The {@code Outcome}-wrapper analogue of {@link #validateLocalContextErrorsFieldGuards}.
     * Under the wrapper transport ({@link ChildField.Transport.WrapperArm}), every immediate child
     * of an in-scope outcome type receives a non-null {@code Outcome} as {@code env.getSource()}, so
     * each data-channel fetcher must unwrap {@code Success} before its existing read (returning null
     * on {@code ErrorList}). An un-switched child is a silent runtime hole: graphql-java's default
     * {@code PropertyDataFetcher} would read a property off the {@code Outcome} object itself.
     *
     * <p>The arm-switch lives with the emit: inline-resolved reads narrow {@code Success} in the
     * source-read {@code FetcherEmitter.bind} reifies onto {@code <Type>Fetchers}, and DataLoader
     * fields narrow inside their generated fetcher method. This pass therefore checks a
     * contextual structural guarantee rather than allow-list membership: every immediate child of
     * a wrapper outcome type must resolve through a graphitron-emitted fetcher, never
     * graphql-java's default {@code PropertyDataFetcher}.
     *
     * <p>The dispatch-partition coverage test does not catch this: it pins a global property (every
     * leaf lands in one dispatch-status set), whereas this guarantee is contextual (this leaf,
     * <em>when it is an immediate child of a wrapper outcome type</em>, resolves through a real
     * fetcher). A child resolves through {@code PropertyDataFetcher} in two ways, both rejected here:
     * it is an {@link GraphitronField.UnclassifiedField} (no registration, so graphql-java installs
     * its default), or its emitted value is {@code PropertyDataFetcher.fetching} per
     * {@link FetcherEmitter#resolvesViaPropertyDataFetcher} (a {@code PayloadAccessor} errors field
     * or a property/record read on a no-backing parent). {@code UnclassifiedField} is the only
     * unregistered leaf that can sibling an errors field on an object type (the other
     * {@code NOT_DISPATCHED_LEAVES} are {@code InputField} leaves, which only attach to input
     * objects); stubbed variants are already rejected by {@link #validateVariantIsImplemented}.
     */
    private void validateOutcomeChildArmSwitch(GraphitronSchema schema, List<ValidationError> errors) {
        for (var f : schema.fields().values()) {
            if (!(f instanceof ChildField.ErrorsField ef)) continue;
            if (!(ef.transport() instanceof ChildField.Transport.WrapperArm)) continue;
            var parentType = schema.type(ef.parentTypeName());
            GraphitronType.ResultType resultType =
                parentType instanceof GraphitronType.ResultType rt ? rt : null;
            for (var sib : schema.fieldsOf(ef.parentTypeName())) {
                if (sib == ef) continue;
                if (sib instanceof ChildField.ErrorsField) continue;
                boolean unregistered = sib instanceof GraphitronField.UnclassifiedField;
                if (unregistered || FetcherEmitter.resolvesViaPropertyDataFetcher(sib, resultType)) {
                    errors.add(new ValidationError(
                        ef.qualifiedName(),
                        Rejection.structural("Field '" + ef.qualifiedName()
                            + "': WrapperArm errors transport requires every sibling data field to "
                            + "resolve through an arm-switching graphitron fetcher, but '"
                            + sib.qualifiedName() + "' (" + sib.getClass().getSimpleName()
                            + ") would resolve through graphql-java's default PropertyDataFetcher, "
                            + "reading a property off the Outcome source object instead of unwrapping "
                            + "Success"),
                        ef.location()
                    ));
                }
            }
        }
    }
}
