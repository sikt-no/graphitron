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
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.TableRef;
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
        schema.fields().values().forEach(field -> validateField(field, types, errors));
        validateNestingParentCompat(schema, errors);
        validateLocalContextErrorsFieldGuards(schema, errors);
        validateOutcomeTypeShape(schema, errors);
        validateOutcomeChildArmSwitch(schema, errors);
        validateContextArgumentTypeAgreement(schema, errors);
        drainBuildDiagnostics(schema, errors);
        return List.copyOf(errors);
    }

    /**
     * R317 slice 5: drains the build-time validation diagnostics the immutable validate phase
     * accumulated ({@link GraphitronSchema#diagnostics()}) into the {@link ValidationError} stream.
     * The global soundness reductions (node-typeId uniqueness, case-fold collisions, the
     * dangling-reference backstop, the federation {@code @key} checks, and the multi-producer
     * {@code DomainReturnType} agreement, R204 / R279 slice 4) register a fully-formed
     * {@link ValidationError} on the schema rather than demoting a classified verdict to
     * {@code UnclassifiedType} / {@code UnclassifiedField}, so a verdict read after the walk equals
     * the verdict classification produced. This drain re-surfaces those findings unchanged: the
     * coordinate, typed {@link Rejection}, and source location are already those the validator's own
     * {@code validateUnclassifiedType} / {@code validateUnclassifiedField} passes would have
     * produced from the demotion, so the error stream is byte-identical to the demoting world.
     */
    private void drainBuildDiagnostics(GraphitronSchema schema, List<ValidationError> errors) {
        errors.addAll(schema.diagnostics());
    }

    /**
     * Cross-cutting check: drains the cached {@link ContextArgumentClassifier} output's typed
     * {@link Rejection.AuthorError.TypeConflict} list into {@link ValidationError}s, mirroring
     * the validator-mirrors-classifier shape of
     * {@link #validateLocalContextErrorsFieldGuards}. The classifier walks every
     * {@link no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed} whose source is
     * {@link no.sikt.graphitron.rewrite.model.ParamSource.Context} and rejects when two or more
     * directive sites reference the same name with disagreeing Java types, closing the loop
     * before the factory emitter is ever asked to paste a non-existent {@code TypeName} into
     * {@code Graphitron.newExecutionInput(...)}.
     *
     * <p>Reads {@link GraphitronSchema#contextArguments()}, which the schema's constructor
     * populated once at parse boundary, so the validator and {@code GraphitronFacadeGenerator}
     * see the identical classification rather than each re-running the walk.
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
            case no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType t     -> validateTableInputType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType t     -> validateConnectionType(t, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType t           -> {} // structural validation is a downstream concern
            case no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType t       -> {} // structural validation is a downstream concern
            case no.sikt.graphitron.rewrite.model.GraphitronType.NestingType t    -> {} // no domain directives, nothing to validate structurally
            case no.sikt.graphitron.rewrite.model.GraphitronType.EnumType t           -> {} // enums validate at the schema level; no domain concerns
            case no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType t         -> {} // resolver-validated at classification time; nothing extra here
            case no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType t   -> validateUnclassifiedType(t, errors);
        }
    }

    /**
     * Whether the generator's dispatch emits a {@code @table} re-projecting SELECT (re-fetch) for this
     * field, read from the leaf and its return-shape slot. This <em>mirrors</em> what
     * {@code TypeFetcherGenerator} actually emits; {@link #validateField} asserts it agrees with the
     * model's {@code Table mapping x holds-records} derivation
     * {@link no.sikt.graphitron.rewrite.model.OutputField#requiresReFetch()}, so the single-homed
     * predicate and the emitter cannot drift.
     *
     * <p>The {@code @service}-table and {@code DML}-projected-{@code @table} arms re-query from a
     * produced record; the Record-source family ({@code RecordTableField} — into which the former
     * {@code SingleRecordTableField} carriers collapsed — {@code RecordLookupTableField} /
     * {@code RecordTableMethodField})
     * re-projects the {@code @table} from keys read off a received record (R305). The
     * {@code @service}-record, DML-encoded (PK-only RETURNING), and catalog {@code Fetch} arms do not
     * re-fetch. The strict {@link no.sikt.graphitron.rewrite.model.Mapping#Table} guard matches
     * {@code requiresReFetch}, which fires only on a non-connection {@code Table} mapping; a
     * connection-shaped table field paginates rather than re-projecting in this derivation's sense.
     */
    private static boolean dispatchPerformsReFetch(no.sikt.graphitron.rewrite.model.OutputField field) {
        if (field.mapping() != no.sikt.graphitron.rewrite.model.Mapping.Table) {
            return false;
        }
        return switch (field) {
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField ignored -> true;
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField ignored -> true;
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField ignored -> true;
            // R305 — the Record-source family re-projects the @table from keys held at the source
            // (the former SingleRecordTableField carriers collapsed into RecordTableField).
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableField ignored -> true;
            case no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField ignored -> true;
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableMethodField ignored -> true;
            case no.sikt.graphitron.rewrite.model.MutationField.DmlTableField dml ->
                dml.returnExpression() instanceof no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle
                || dml.returnExpression() instanceof no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList;
            default -> false;
        };
    }

    private void validateField(GraphitronField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // R290 / R305 — re-fetch derivation mirror. The @table re-projecting SELECT is derived once
        // from Table mapping x holds-records on the field (OutputField.requiresReFetch); the
        // generator's per-leaf fetcher arms emit it. This mirror pins the single-homed derivation
        // against what the generator actually dispatches, so a future leaf or reclassification that
        // yields a holds-records x Table shape without a re-fetch arm (or vice versa) fails at build
        // time rather than silently emitting the wrong fetcher (validator-mirrors-classifier).
        if (field instanceof no.sikt.graphitron.rewrite.model.OutputField out
                && out.requiresReFetch() != dispatchPerformsReFetch(out)) {
            errors.add(new ValidationError(
                out.qualifiedName(),
                Rejection.invalidSchema("Field '" + out.qualifiedName() + "': re-fetch derivation (operation "
                    + out.operation() + " x target " + out.target() + " x source " + out.source()
                    + " -> requiresReFetch=" + out.requiresReFetch() + ") disagrees with the generator's "
                    + "re-fetch dispatch for " + out.getClass().getSimpleName() + "; the Table-mapping x "
                    + "holds-records derivation and the re-projecting SELECT have drifted"),
                out.location()));
        }
        switch (field) {
            case no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField f        -> validateQueryLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableField f         -> validateQueryTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableMethodTableField f   -> validateQueryTableMethodTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryNodeField f          -> validateQueryNodeField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryNodesField f         -> {} // no extra validation
            case no.sikt.graphitron.rewrite.model.QueryField.QueryTableInterfaceField f -> validateQueryTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField f     -> validateQueryInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField f         -> validateQueryUnionField(f, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableField f       -> validateQueryServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.QueryField.QueryServiceRecordField f      -> validateQueryServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField f     -> validateMutationInsertTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationUpdateTableField f     -> validateMutationUpdateTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField f     -> validateMutationDeleteTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationUpsertTableField f     -> validateMutationUpsertTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField f    -> validateMutationServiceTableField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField f   -> validateMutationServiceRecordField(f, errors);
            case no.sikt.graphitron.rewrite.model.MutationField.MutationDmlRecordField f       -> {} // R75 Phase 1 — narrow ResultReturnType + DELETE-rejecting compact ctor pin the structural shape; admission-time checks (table-equality, etc.) live in the mutation-field classifier and the trigger function
            case no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDmlRecordField f   -> {} // R141 — same structural pinning as MutationDmlRecordField plus list-input + list-data-field invariants on the compact ctor; admission-time checks (table-equality, Invariant #16) live in the classifier and the trigger function
            case no.sikt.graphitron.rewrite.model.MutationField.MutationUpdatePayloadField f   -> {} // R258 — narrow ResultReturnType + non-Optional InputArgRef / UpdateRows slots pin the structural shape; admission-time checks (PK-or-UK partition, table-equality) live in the @mutation classifier (classifyUpdatePayloadField) and the UpdateRowsWalker
            case no.sikt.graphitron.rewrite.model.MutationField.MutationBulkUpdatePayloadField f -> {} // R258 — bulk sibling of MutationUpdatePayloadField; same structural pinning, same classifier + walker admission-time checks
            case no.sikt.graphitron.rewrite.model.MutationField.MutationDeletePayloadField f   -> {} // R266 — narrow ResultReturnType + non-Optional InputArgRef / DeleteRows slots pin the structural shape; admission-time checks (PK-or-UK coverage, table-equality, DELETE-specific reclassify) live in the @mutation classifier (classifyDeletePayloadField) and the DeleteRowsWalker
            case no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDeletePayloadField f -> {} // R266 — bulk sibling of MutationDeletePayloadField; same structural pinning, same classifier + walker admission-time checks
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnField f             -> validateColumnField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField f    -> validateColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField f -> {} // structural; the interface fetcher's LEFT JOIN materialises and aliases the value
            case no.sikt.graphitron.rewrite.model.ChildField.CompositeColumnField f    -> validateCompositeColumnField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.CompositeColumnReferenceField f -> validateCompositeColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableField f              -> validateTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SplitTableField f        -> validateSplitTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.LookupTableField f       -> validateLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField f  -> validateSplitLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableMethodField f        -> validateTableMethodField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableMethodField f  -> validateRecordTableMethodField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField f     -> validateTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.InterfaceField f          -> validateInterfaceField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.UnionField f              -> validateUnionField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NestingField f            -> validateNestingField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField f       -> validateServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField f      -> validateServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableField f        -> validateRecordTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField f  -> validateRecordLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdField f -> {} // R275 — narrow ScalarReturnType + SourceKey compact-constructor invariants (ResultRowWalk, Wrap.TableRecord) pin the structural shape; admission-time checks (encoder-pins-to-producer-table, @node resolution) live in the serviceEmitted classifier branch
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning f -> {} // R156 — narrow ScalarReturnType component + NodeIdEncodeKeys compaction; admission-time checks (wrapper shape, encoder-pins-to-input-@table, DELETE-only) live in the @mutation classifier
            case no.sikt.graphitron.rewrite.model.ChildField.RecordField f             -> validateRecordField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ComputedField f           -> validateComputedField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.PropertyField f           -> validatePropertyField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ErrorsField f             -> {} // structural; @error type checks already ran at classify time
            case no.sikt.graphitron.rewrite.model.InputField.ColumnField f            -> validateInputColumnField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField f  -> validateInputColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.CompositeColumnField f  -> {} // type-narrowed extraction; arity invariant enforced by record ctor
            case no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField f -> {} // same as above
            case no.sikt.graphitron.rewrite.model.InputField.NestingField f          -> validateInputNestingField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.UnboundField f          -> validateInputUnboundField(f, errors);
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
     * connections) so the message stays disjoint from {@link #validatePaginationRequiresOrdering}
     * — connections always carry pagination and are caught there. Exempts
     * {@link no.sikt.graphitron.rewrite.model.OutputField#requiresReFetch()} fields (R305): a
     * re-fetch field's visible order is locked to the source/target key correspondence (the
     * {@code ORDER BY idx} scatter re-keys the re-projected rows to the upstream source order), so
     * the "list-shaped + {@code None}" signal does not imply non-determinism for them, regardless of
     * intent. This also covers the former {@code SingleRecordTableField} and {@code ServiceTableField}
     * carriers that the retired {@code OrderingOwnedByProducer} marker exempted, and correctly admits
     * a PK-less idx-ordered re-fetch that the marker would have rejected.
     */
    private void validateListRequiresOrdering(GraphitronField field, List<ValidationError> errors) {
        if (field instanceof SqlGeneratingField sgf
                && !(field instanceof no.sikt.graphitron.rewrite.model.OutputField out && out.requiresReFetch())
                && sgf.returnType().wrapper() instanceof FieldWrapper.List
                && sgf.orderBy() instanceof OrderBySpec.None) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Field '" + field.qualifiedName() + "': list fields must have a "
                        + "deterministic order. Add a primary key to the target table, or use "
                        + "@defaultOrder or @orderBy."),
                field.location()
            ));
        }
    }

    /**
     * Cross-cutting check: reject schemas whose classification lands on a variant that the
     * {@link TypeFetcherGenerator} hasn't implemented yet. Without this check the build
     * succeeds and the generated stub throws {@link UnsupportedOperationException} at the
     * first request hitting the variant; the principle in {@code graphitron-principles.md}
     * is that problems caught at build time are cheaper, so we surface it here.
     *
     * <p>The {@link TypeFetcherGenerator#STUBBED_VARIANTS} map is the single source of
     * truth for "stubbed" status. Variants in {@code IMPLEMENTED_LEAVES} or
     * {@code NOT_DISPATCHED_LEAVES} return {@code null} from this lookup and are correctly
     * ignored — an invariant enforced by
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

    // --- Type validators (stubs — filled in as test classes are added) ---

    private void validateTableType(no.sikt.graphitron.rewrite.model.GraphitronType.TableType type, List<ValidationError> errors) {
        // Unresolved tables are caught by the builder (UnclassifiedType). Nothing more to validate here.
    }
    private void validateNodeType(no.sikt.graphitron.rewrite.model.GraphitronType.NodeType type, List<ValidationError> errors) {
        // Unresolved tables and unresolved @node key columns are caught by the builder (UnclassifiedType).
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

    private void validateTableInputType(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType type, List<ValidationError> errors) {
        // R215: input fields are stored embedded in their parent type rather than in the schema's
        // flat field map, so the validateField walk doesn't reach them; iterate the type's input
        // fields here to surface UnboundField + @condition(override:false) shapes the classifier
        // admits structurally but the validator rejects.
        for (var field : type.inputFields()) {
            validateInputFieldRecursive(field, errors);
        }
    }

    /**
     * Walks the input-field tree rooted at {@code field}, surfacing R215 validator-side rejections
     * (currently only {@link no.sikt.graphitron.rewrite.model.InputField.UnboundField} with
     * {@code @condition(override:false)}). Recurses through
     * {@link no.sikt.graphitron.rewrite.model.InputField.NestingField} so nested plain inputs
     * inside an {@code @table} input are walked too.
     */
    private void validateInputFieldRecursive(no.sikt.graphitron.rewrite.model.InputField field, List<ValidationError> errors) {
        switch (field) {
            case no.sikt.graphitron.rewrite.model.InputField.UnboundField uf -> validateInputUnboundField(uf, errors);
            case no.sikt.graphitron.rewrite.model.InputField.NestingField nf -> {
                for (var nested : nf.fields()) {
                    validateInputFieldRecursive(nested, errors);
                }
            }
            case no.sikt.graphitron.rewrite.model.InputField.ColumnField ignored -> {}
            case no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField ignored -> {}
            case no.sikt.graphitron.rewrite.model.InputField.CompositeColumnField ignored -> {}
            case no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField ignored -> {}
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
     *       across UNION ALL branches. v1 doesn't NULL-pad; mixed arity rejects upstream.</li>
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

        // Deferred follow-up (R102 spec body, "carried forward"): spec called for lifting the
        // emit-time single-column participant-PK check at MultiTablePolymorphicEmitter.java:824
        // (the connection-rows method picks the first PK column to type sortField, silently
        // truncating composite participant PKs). Existing graphitron-sakila-example
        // Query.pagedItems → PagedA/PagedB has composite-PK participants on a connection that
        // quietly works because the test data is structured around k1, so promoting the
        // truncation to a hard validator error would block today. The wrapper parameter for
        // gating this check on Connection re-lands when the lift does — keeping it in the
        // signature today would be infrastructure ahead of need.
    }

    /**
     * Multi-table polymorphic child guard (both list and connection arms): rejects parent-key
     * arity above jOOQ's typed Row22 cap. The cap is uniform across both arms because the
     * shared {@code parentInput VALUES} emitter widens the parent key by an {@code idx} column
     * on every batched-rows method (see {@code MultiTablePolymorphicEmitter.buildParentInputValuesEmitter}),
     * so the resulting {@code Row<N+1>} tops out at jOOQ's {@code Row22} on either arm.
     * Field-level cap therefore is parent-key arity 21.
     *
     * <p>Surfaces the constraint as a clean validator rejection (build-time AUTHOR_ERROR with
     * file:line) instead of a codegen-time {@code IllegalStateException} or a
     * {@code Row23}-doesn't-exist compile failure.
     *
     * <p>Reads {@code field.parentSourceKey().columns()} uniformly across all five
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Reader} permits the polymorphic parent
     * can land on — the same column tuple the rows-method prelude uses, so the validator's
     * arity surface tracks the actual key tuple regardless of producer.
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

    // --- Field validators (stubs — filled in as test classes are added) ---

    private void validateQueryLookupTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': lookup fields must not return a connection"),
                field.location()
            ));
        } else {
            // Lookup cardinality is determined by whether any @lookupKey arg is a list.
            // Lookup-key args live on LookupMapping; any legacy filter-carried list arg is
            // also considered (validator is input-shape-agnostic and covers both paths).
            boolean anyKeyIsList = switch (field.lookupMapping()) {
                    case no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping cm ->
                        cm.hasListArg();
                }
                || field.filters().stream().anyMatch(f -> switch (f) {
                    case no.sikt.graphitron.rewrite.model.GeneratedConditionFilter gcf ->
                        gcf.bodyParams().stream().anyMatch(bp -> bp.list());
                    case no.sikt.graphitron.rewrite.model.ConditionFilter ignored -> false;
                });
            boolean returnIsList = field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List;
            if (anyKeyIsList != returnIsList) {
                errors.add(new ValidationError(
                    field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': result type does not match input cardinality"),
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
    private void validateQueryTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryTableMethodTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableMethodTableField field, List<ValidationError> errors) {
        // Connection rejection happens at classifier time (FieldBuilder Invariants §1);
        // no per-variant validation needed here.
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
    }
    private void validateQueryServiceRecordField(no.sikt.graphitron.rewrite.model.QueryField.QueryServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
    }
    private void validateMutationInsertTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField field, List<ValidationError> errors) {}
    private void validateMutationUpdateTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationUpdateTableField field, List<ValidationError> errors) {}
    private void validateMutationDeleteTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField field, List<ValidationError> errors) {}
    private void validateMutationUpsertTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationUpsertTableField field, List<ValidationError> errors) {}
    private void validateMutationServiceTableField(no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableField field, List<ValidationError> errors) {
        // Unresolved service method is caught by the builder (UnclassifiedField).
    }
    private void validateMutationServiceRecordField(no.sikt.graphitron.rewrite.model.MutationField.MutationServiceRecordField field, List<ValidationError> errors) {}
    private void validateColumnField(no.sikt.graphitron.rewrite.model.ChildField.ColumnField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        if (!(types.get(field.parentTypeName()) instanceof GraphitronType.TableBackedType)) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': @column is not valid on a non-table-backed type"),
                field.location()
            ));
        }
    }
    private void validateColumnReferenceField(no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField field, List<ValidationError> errors) {
        if (field.joinPath().isEmpty()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': @reference path is required"),
                field.location()
            ));
            return;
        }
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        // Two shapes are recognised structurally but not yet emitted; surface as build-time
        // deferred rejections rather than runtime stubs (Validator mirrors classifier invariants).
        if (field.compaction() instanceof no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys) {
            emitDeferredError(field,
                (Rejection.Deferred) Rejection.deferred(
                    "ColumnReferenceField NodeIdEncodeKeys (rooted-at-parent NodeId reference) not yet implemented"
                    + " — requires JOIN-with-projection emission",
                    "nodeidreferencefield-join-projection-form",
                    no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField.class),
                errors);
            return;
        }
    }
    private void validateCompositeColumnField(no.sikt.graphitron.rewrite.model.ChildField.CompositeColumnField field, List<ValidationError> errors) {
        // Arity invariant — composite carriers carry size >= 2; arity-1 routes to ColumnField.
        // The record's compact constructor enforces the lower bound; the upper bound matches the
        // RecordN / RowN ceiling (jOOQ's 22-slot cap). Any breach indicates a classifier bug.
        if (field.columns().size() > 22) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': composite NodeId carrier has "
                    + field.columns().size() + " columns, exceeding the 22-slot RecordN cap"),
                field.location()
            ));
        }
    }
    private void validateCompositeColumnReferenceField(no.sikt.graphitron.rewrite.model.ChildField.CompositeColumnReferenceField field, List<ValidationError> errors) {
        // Arity invariant matches CompositeColumnField — record's compact constructor handles
        // the lower bound; we cap at the RecordN 22-slot ceiling.
        if (field.columns().size() > 22) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': composite NodeId reference carrier has "
                    + field.columns().size() + " columns, exceeding the 22-slot RecordN cap"),
                field.location()
            ));
        }
        // joinPath shape is validated downstream by validateReferencePath; the FK-mirror collapse
        // happens at classification time so a CompositeColumnReferenceField that survives here is
        // a non-mirror reference (rooted-at-parent, multi-hop, or condition-join).
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
    }

    private void validateReferenceLeadsToType(String fieldName, SourceLocation location, List<JoinStep> path, String typeName, no.sikt.graphitron.rewrite.model.TableRef targetTable, List<ValidationError> errors) {
        if (path.isEmpty()) return; // classifier guarantees non-empty for this variant; skip in isolated validator unit tests
        // Every JoinStep permit implements HasTargetTable post-R232 (FkJoin and LiftedHop via
        // WithTarget; ConditionJoin directly). The comparison is uniform across permits.
        var lastStep = (JoinStep.HasTargetTable) path.getLast();
        if (!lastStep.targetTable().tableName().equalsIgnoreCase(targetTable.tableName())) {
            errors.add(new ValidationError(
                fieldName,
                Rejection.structural("Field '" + fieldName + "': @reference path does not lead to the table of type '" + typeName + "'"),
                location
            ));
        }
    }
    private void validateTableField(no.sikt.graphitron.rewrite.model.ChildField.TableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateSplitTableField(no.sikt.graphitron.rewrite.model.ChildField.SplitTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        // Split+Connection partitions rows by parent key; without a total order, ROW_NUMBER() produces
        // silently non-deterministic slicing. Require an explicit ordering (@defaultOrder, @orderBy,
        // or a fixed list) at build time rather than letting the cursor encoder hash an empty tuple.
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
    private void validateLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.LookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': lookup fields must not return a connection"),
                field.location()
            ));
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateSplitLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': lookup fields must not return a connection"),
                field.location()
            ));
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableMethodField(no.sikt.graphitron.rewrite.model.ChildField.TableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateRecordTableMethodField(no.sikt.graphitron.rewrite.model.ChildField.RecordTableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField field, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.InterfaceField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
        validateChildMultiTableParentPk(field.qualifiedName(), field.location(),
            field.parentTypeName(), field.parentSourceKey(), errors);
    }
    private void validateUnionField(no.sikt.graphitron.rewrite.model.ChildField.UnionField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
        validateMultiTableParticipants(field.qualifiedName(), field.location(), field.participants(), errors);
        validateChildMultiTableParentPk(field.qualifiedName(), field.location(),
            field.parentTypeName(), field.parentSourceKey(), errors);
    }
    private void validateNestingField(no.sikt.graphitron.rewrite.model.ChildField.NestingField field, List<ValidationError> errors) {
        // List cardinality has no source-passthrough semantic: one parent Record in, one list value out.
        if (field.returnType().wrapper() instanceof FieldWrapper.List) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': list cardinality on a plain-object nesting field is not supported"),
                field.location()
            ));
        }
        // Stubbed leaves at nested depth escape the top-level validateVariantIsImplemented pass
        // (they're inside NestingField.nestedFields(), not in schema.fields()). Walk them here —
        // this is the integration point the emitter's projection helper relies on for unreachability
        // of its fallthrough arm.
        walkNestedVariantsForImplementation(field.nestedFields(), errors);
    }

    private void walkNestedVariantsForImplementation(List<ChildField> fields, List<ValidationError> errors) {
        for (var f : fields) {
            if (f instanceof ChildField.NestingField nf) {
                walkNestedVariantsForImplementation(nf.nestedFields(), errors);
            } else {
                validateVariantIsImplemented(f, errors);
                validateVariantIsSupportedAtNestedDepth(f, errors);
            }
        }
    }

    /**
     * Variants wireable at nested depth. Post-R303 every leaf here is wired through the nested
     * type's own {@code <NestedTypeName>Fetchers} class: the column/table reads ({@code ColumnField},
     * {@code CompositeColumnField}, {@code TableField}, {@code LookupTableField},
     * {@code NestingField}) are reified onto it by {@code FetcherEmitter.bind}, and the class-backed
     * leaves ({@code SplitTableField}, {@code SplitLookupTableField}) carry their heavy methods
     * there. {@code TypeFetcherGenerator} emits that class for any nested type owning a fetcher (the
     * {@code FetcherEmitter.nestedTypeOwnsFetchers} gate shared with
     * {@code FetcherRegistrationsEmitter.nestedBody}, via a separate walk over
     * {@code NestingField.nestedFields()}). Expanding this set requires the corresponding
     * generator-side change.
     */
    private static final java.util.Set<Class<? extends GraphitronField>> NESTED_WIREABLE_LEAVES = java.util.Set.of(
        ChildField.ColumnField.class,
        ChildField.CompositeColumnField.class,
        ChildField.TableField.class,
        ChildField.LookupTableField.class,
        ChildField.NestingField.class,
        ChildField.SplitTableField.class,
        ChildField.SplitLookupTableField.class);

    private void validateVariantIsSupportedAtNestedDepth(GraphitronField field, List<ValidationError> errors) {
        // Stubbed variants already surfaced by validateVariantIsImplemented, don't double-report.
        if (TypeFetcherGenerator.STUBBED_VARIANTS.containsKey(field.getClass())) {
            return;
        }
        if (!NESTED_WIREABLE_LEAVES.contains(field.getClass())) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.deferred("Field '" + field.qualifiedName() + "': " + field.getClass().getSimpleName()
                    + " is not yet supported under NestingField", ""),
                field.location()
            ));
        }
    }

    /**
     * Schema-level parent-compatibility check for shared nesting types. When two or more {@code @table}
     * parents declare a field of the same plain-object nesting type, each parent independently classifies
     * its own {@code nestedFields} against its own table. The representative is the first parent in SDL
     * order; every subsequent parent's {@code nestedFields} must match the representative's shape field
     * by field — same name, same kind, and for {@link ChildField.ColumnField} the same SQL column name
     * and Java column class. jOOQ's name-based fallback in {@code Record.get(Field)} relies on this
     * invariant: a nested-type wiring emitted with the representative parent's typed {@code Field<T>}
     * must resolve against any parent's {@code Record} without silent mismatch.
     *
     * <p>Non-{@link ChildField.ColumnField} leaves reject at nested depth when the nesting type is
     * shared: their resolution depends on per-parent metadata (join paths, FK counts), which is
     * per-field. Multi-parent support for those leaves lands with the corresponding roadmap #8 arm.
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
                errors.add(new ValidationError(
                    coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' classifies as " + rf.getClass().getSimpleName() + " on the first but "
                        + of.getClass().getSimpleName() + " on the second"),
                    other.location()
                ));
                continue;
            }
            if (rf instanceof ChildField.ColumnField rcf && of instanceof ChildField.ColumnField ocf) {
                if (!rcf.column().sqlName().equals(ocf.column().sqlName())) {
                    errors.add(new ValidationError(
                        coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' resolves to column '" + rcf.column().sqlName() + "' on the first but '"
                            + ocf.column().sqlName() + "' on the second"),
                        other.location()
                    ));
                } else if (!rcf.column().columnClass().equals(ocf.column().columnClass())) {
                    errors.add(new ValidationError(
                        coord,
            Rejection.structural("Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' has Java type '" + rcf.column().columnClass() + "' on the first but '"
                            + ocf.column().columnClass() + "' on the second"),
                        other.location()
                    ));
                }
            } else if (rf instanceof ChildField.TableField) {
                // TableField is safe to share across parents: each parent's $fields emits its own
                // DSL.multiset arm (per-parent joinPath / filters / orderBy / pagination are
                // intentionally not compared), and the reified projected read (PROJECTED_LEAVES) reads
                // by field name from the source Record without consulting the outer parent table. No
                // further shape check is needed: returnType() derives from the single SDL declaration
                // on the shared nested type and is identical by construction. The class-equality gate
                // above already guarantees `of` is a TableField too.
            } else if (rf instanceof ChildField.NestingField rnf && of instanceof ChildField.NestingField onf) {
                // Two-level nesting: recurse so divergent inner columns don't slip past the
                // outer class-equality check. Thread the outer @table parent names so inner
                // errors still name the original tables, not the intermediate nested type.
                compareNestedFieldsShape(rnf, onf, repParent, otherParent, errors);
            } else if (!(rf instanceof ChildField.NestingField)) {
                // Remaining non-column, non-nesting, non-TableField leaves (the BatchKey carriers and
                // composite NodeId references) are not yet supported as multi-parent shared nested
                // fields: their resolution depends on per-parent metadata this shape check doesn't
                // inspect. See roadmap nestingfield-multiparent-batchkey-leaves for the follow-up.
                errors.add(new ValidationError(
                    coord,
            Rejection.deferred("Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' classifies as " + rf.getClass().getSimpleName()
                        + " which is not yet supported across multiple parents", ""),
                    other.location()
                ));
            }
        }
        // Report fields present on `other` but not on `rep`.
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
    private void validateServiceTableField(no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);

        var smr = field.method();

        // A table-bound service field requires at least one Sources parameter for DataLoader batching.
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
        // Mirrors the parent-PK invariant below: a classifier guarantee the lifted emitter relies on,
        // not a transient stopgap. See R285.
        TableRef returnTable = field.returnType().table();
        if (!returnTable.hasPrimaryKey()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': @service on a table-bound return type requires the returned table '" + returnTable.tableName() + "' to have a primary key for identity re-projection"),
                field.location()
            ));
        }

        var parentType = types.get(field.parentTypeName());
        if (!(parentType instanceof TableBackedType tbt)) {
            return; // non-table parent; no DataLoader key needed
        }
        TableRef parentTable = tbt.table();
        if (!parentTable.hasPrimaryKey()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.structural("Field '" + field.qualifiedName() + "': @service on a table-bound return type requires the parent table '" + parentTable.tableName() + "' to have a primary key"),
                field.location()
            ));
        }
    }
    private void validateServiceRecordField(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        if (!field.joinPath().isEmpty()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.deferred("Field '" + field.qualifiedName() + "': @service with a @reference path "
                    + "(condition-join lift form) is not yet supported — see "
                    + "graphitron-rewrite/roadmap/service-record-field.md", ""),
                field.location()));
            return;
        }
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
    }
    private void validateRecordTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateRecordLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.invalidSchema("Field '" + field.qualifiedName() + "': lookup fields must not return a connection"),
                field.location()
            ));
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateRecordField(no.sikt.graphitron.rewrite.model.ChildField.RecordField field, List<ValidationError> errors) {
        // Accessor-resolution rejection routes through UnclassifiedField at classify time
        // (FieldBuilder), so the slot here is statically AccessorResolution.Resolved or null.
        // Nothing to validate at this site.
    }

    private void validateComputedField(no.sikt.graphitron.rewrite.model.ChildField.ComputedField field, List<ValidationError> errors) {
        if (!field.joinPath().isEmpty()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
            Rejection.deferred("Field '" + field.qualifiedName() + "': @externalField with a @reference path "
                    + "(condition-join lift form) is not yet supported — see "
                    + "graphitron-rewrite/roadmap/computed-field-with-reference.md", ""),
                field.location()));
            return;
        }
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
    }
    private void validatePropertyField(no.sikt.graphitron.rewrite.model.ChildField.PropertyField field, List<ValidationError> errors) {
        // See validateRecordField: classifier-side routing leaves nothing for this site to do.
    }
    private void validateInputColumnField(no.sikt.graphitron.rewrite.model.InputField.ColumnField field, List<ValidationError> errors) {
        // Column resolution is guaranteed by the builder (unresolved → UnclassifiedType). Nothing to validate here.
    }
    private void validateInputColumnReferenceField(no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField field, List<ValidationError> errors) {
        // Column and join path resolution is guaranteed by the builder (unresolved → UnclassifiedType). Nothing to validate here.
    }
    private void validateInputNestingField(no.sikt.graphitron.rewrite.model.InputField.NestingField field, List<ValidationError> errors) {
        // Nested field columns are resolved at classification time; no additional structural checks needed.
    }
    /**
     * R215: an {@link no.sikt.graphitron.rewrite.model.InputField.UnboundField} with
     * {@code @condition(override: false)} present is a structural bug — the field has no column
     * to bind and the explicit condition method does not own the predicate (override: false
     * means the implicit column predicate is required to compose with the condition). The
     * classifier admits this shape so consumer-side rejection paths stay uniform; the validator
     * rejects it at the field's source location with a directed diagnostic.
     */
    private void validateInputUnboundField(no.sikt.graphitron.rewrite.model.InputField.UnboundField field, List<ValidationError> errors) {
        if (field.condition().isPresent() && !field.condition().get().override()) {
            errors.add(new ValidationError(
                field.qualifiedName(),
                Rejection.structural("Input field '" + field.qualifiedName()
                    + "': @condition(override: false) on a field with no resolving column is not "
                    + "supported; either add a matching column to the resolving table, or set "
                    + "override: true so the condition method owns the WHERE predicate entirely"),
                field.location()
            ));
        }
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
     */
    private void validateReferencePath(String fieldName, SourceLocation location, List<JoinStep> path, List<ValidationError> errors) {
        // All elements are resolved — builder rejects unresolved paths at classification time.
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
     * variants within {@link #LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS} by construction. R161
     * widens that admission to mutation DML record fields, and any future widening that admits a
     * non-guarded variant must extend the allow-list along with the matching fetcher's null-source
     * guard. This validator pass is the cross-check that turns a silently-broken admission into a
     * build-time {@link Rejection.AuthorError.Structural}.
     */
    private void validateLocalContextErrorsFieldGuards(GraphitronSchema schema, List<ValidationError> errors) {
        for (var f : schema.fields().values()) {
            if (!(f instanceof ChildField.ErrorsField ef)) continue;
            if (!(ef.transport() instanceof ChildField.Transport.LocalContext)) continue;
            for (var sib : schema.fieldsOf(ef.parentTypeName())) {
                if (sib == ef) continue;
                if (sib instanceof ChildField.ErrorsField) continue;
                if (!LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS.contains(sib.getClass())) {
                    errors.add(new ValidationError(
                        ef.qualifiedName(),
                        Rejection.structural("Field '" + ef.qualifiedName()
                            + "': LocalContext errors transport requires the carrier's data-channel "
                            + "fetcher to short-circuit on a null source, but sibling field '"
                            + sib.qualifiedName() + "' classifies as " + sib.getClass().getSimpleName()
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
     *   <li>{@code RecordTableField} → {@code buildRecordBasedDataFetcher} (explicit
     *       {@code if (env.getSource() == null) return completedFuture(null);} prelude before the
     *       key read; the {@code OUTCOME_SUCCESS} arm's {@code instanceof Success} narrowing also
     *       rejects null). This is the R305 successor to the former {@code SingleRecordTableField}
     *       carrier, which collapsed into {@code RecordTableField}.</li>
     *   <li>{@code SingleRecordIdFieldFromReturning} → {@code buildSingleRecordIdFromReturningFetcherValue}
     *       (explicit guard before encoder dispatch).</li>
     * </ul>
     *
     * <p>Adding a variant to this set requires the matching emitter site to honor the guard;
     * removing the guard from an existing emitter arm must remove the variant from this set.
     */
    private static final java.util.Set<Class<? extends GraphitronField>> LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS = java.util.Set.of(
        ChildField.RecordTableField.class,
        ChildField.SingleRecordIdFieldFromReturning.class
    );

    /**
     * R244 ; mirror-the-classifier check for the single-errors-field invariant on outcome types.
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
     * R244/R268 ; the {@code Outcome}-wrapper analogue of {@link #validateLocalContextErrorsFieldGuards}.
     * Under the wrapper transport ({@link ChildField.Transport.WrapperArm}), every immediate child
     * of an in-scope outcome type receives a non-null {@code Outcome} as {@code env.getSource()}, so
     * each data-channel fetcher must unwrap {@code Success} before its existing read (returning null
     * on {@code ErrorList}). An un-switched child is a silent runtime hole: graphql-java's default
     * {@code PropertyDataFetcher} would read a property off the {@code Outcome} object itself.
     *
     * <p>R268 retired the former allow-list of "arm-switchable" variants (a parallel taxonomy that
     * drifted from the emitter: a child that can't arm-switch is a generator limitation, not an
     * author error, and {@code @table}-bound DataLoader data fields were wrongly rejected by it).
     * The arm-switch now lives where it belongs: the inline-resolved reads narrow {@code Success}
     * in the source-read {@code FetcherEmitter.bind} reifies onto {@code <Type>Fetchers} (the
     * registration site carries the resulting reference), DataLoader fields narrow inside their
     * generated fetcher method. This pass
     * keeps a contextual structural guarantee instead of allow-list membership: every immediate
     * child of a wrapper outcome type must resolve through a graphitron-emitted fetcher, never
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
     * objects); stubbed variants are already rejected by {@code validateVariantIsImplemented}.
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
