package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
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
        validateContextArgumentTypeAgreement(schema, errors);
        return List.copyOf(errors);
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
            case no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType t    -> {} // no domain directives, nothing to validate structurally
            case no.sikt.graphitron.rewrite.model.GraphitronType.EnumType t           -> {} // enums validate at the schema level; no domain concerns
            case no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType t         -> {} // resolver-validated at classification time; nothing extra here
            case no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType t   -> validateUnclassifiedType(t, errors);
        }
    }

    private void validateField(GraphitronField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
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
            case no.sikt.graphitron.rewrite.model.ChildField.ConstructorField f        -> validateConstructorField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField f       -> validateServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField f      -> validateServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableField f        -> validateRecordTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField f  -> validateRecordLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordTableField f  -> {} // R75 Phase 1 — narrow TableBoundReturnType component + SourceKey compact-constructor invariant pin the structural shape; admission-time checks (table-equality, DELETE rejection) live in the mutation-field classifier
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning f -> {} // R156 — narrow ScalarReturnType component + NodeIdEncodeKeys compaction; admission-time checks (wrapper shape, encoder-pins-to-input-@table, DELETE-only) live in the @mutation classifier
            case no.sikt.graphitron.rewrite.model.ChildField.SingleRecordTableFieldFromReturning f -> {} // R156 — narrow TableBoundReturnType + List<PkResolution> projection from BuildContext.classifyDeleteTableProjection; admission-time checks (no NonPkNonNullable/ServiceField/UnsupportedField arms, DELETE-only) live in the @mutation classifier
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
     * — connections always carry pagination and are caught there. Excludes permits bearing
     * {@link no.sikt.graphitron.rewrite.model.OrderingOwnedByProducer}: those (single-record
     * carrier data fields and {@code @service}-backed child fields) have an upstream producer
     * that determines visible result ordering, so the "list-shaped + {@code None}" signal does
     * not imply non-determinism for them.
     */
    private void validateListRequiresOrdering(GraphitronField field, List<ValidationError> errors) {
        if (field instanceof SqlGeneratingField sgf
                && !(field instanceof no.sikt.graphitron.rewrite.model.OrderingOwnedByProducer)
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
     *
     * <p>Both the stubbed-variant map lookup and the intra-variant
     * {@code SplitRowsMethodEmitter.unsupportedReason} predicates produce a
     * {@link no.sikt.graphitron.rewrite.model.Rejection.Deferred}; both project to a
     * {@link ValidationError} via the shared {@link #emitDeferredError} path so the validator's
     * deferred-gate output is uniform regardless of which channel the deferred arose from.
     */
    private void validateVariantIsImplemented(GraphitronField field, List<ValidationError> errors) {
        var stubbed = TypeFetcherGenerator.STUBBED_VARIANTS.get(field.getClass());
        if (stubbed != null) {
            emitDeferredError(field, stubbed, errors);
            return;
        }
        // Intra-variant stubs: the six ChildField variants that share the condition-join
        // predicate ({@link ConditionJoinReportable}) all dispatch through the emitter's
        // single capability-keyed unsupportedReason; STUBBED_VARIANTS is class-keyed so
        // cannot distinguish "fully stubbed" from "intra-variant emit-block", and the
        // capability dispatch keeps validator and emitter in lock-step.
        no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter.unsupportedReason(field)
            .ifPresent(d -> emitDeferredError(field, d, errors));
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
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "column-reference-field-no-nodeid-encode-keys",
        description = "Rejects ChildField.ColumnReferenceField with NodeIdEncodeKeys compaction "
            + "as Rejection.Deferred keyed to nodeidreferencefield-join-projection-form. Lets "
            + "InlineColumnReferenceFieldEmitter and TypeClassGenerator assume Direct compaction "
            + "post-validate without a runtime branch on compaction(), and lets FetcherEmitter "
            + "carry only the Direct-compaction wiring arm for this variant.")
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "column-reference-field-no-condition-join-step",
        description = "Rejects ChildField.ColumnReferenceField whose joinPath contains a "
            + "JoinStep.ConditionJoin as Rejection.Deferred keyed to "
            + "column-reference-on-scalar-field-condition-join. Lets "
            + "InlineColumnReferenceFieldEmitter cast each step in joinPath to JoinStep.FkJoin "
            + "without a defensive arm.")
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
        if (no.sikt.graphitron.rewrite.generators.JoinPathEmitter.hasConditionJoin(field.joinPath())) {
            emitDeferredError(field,
                (Rejection.Deferred) Rejection.deferred(
                    "ColumnReferenceField with @condition-method step in path not yet implemented"
                    + " — pending classification-vocabulary item 5",
                    "column-reference-on-scalar-field-condition-join",
                    no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField.class),
                errors);
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
        var lastStep = path.getLast();
        // FkJoin and LiftedHop both implement WithTarget — the targetTable accessor means the
        // same thing on each, so the comparison is uniform. ConditionJoin has no pre-resolved
        // target table and is skipped per the existing convention.
        if (lastStep instanceof JoinStep.WithTarget wt) {
            if (!wt.targetTable().tableName().equalsIgnoreCase(targetTable.tableName())) {
                errors.add(new ValidationError(
                    fieldName,
            Rejection.structural("Field '" + fieldName + "': @reference path does not lead to the table of type '" + typeName + "'"),
                    location
                ));
            }
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
     * Variants wireable at nested depth. Inline leaves ({@code ColumnField}, {@code TableField},
     * etc.) have className-independent arms in {@code FetcherRegistrationsEmitter}.
     * Class-backed leaves ({@code SplitTableField}, {@code SplitLookupTableField}) are wired via
     * a per-nested-type {@code <NestedTypeName>Fetchers} class; {@code TypeFetcherGenerator.generate}
     * emits that class via a separate walk over {@code NestingField.nestedFields()}. Expanding
     * either group requires the corresponding generator-side change.
     */
    private static final java.util.Set<Class<? extends GraphitronField>> NESTED_WIREABLE_LEAVES = java.util.Set.of(
        ChildField.ColumnField.class,
        ChildField.CompositeColumnField.class,
        ChildField.TableField.class,
        ChildField.LookupTableField.class,
        ChildField.ConstructorField.class,
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
            } else if (rf instanceof ChildField.NestingField rnf && of instanceof ChildField.NestingField onf) {
                // Two-level nesting: recurse so divergent inner columns don't slip past the
                // outer class-equality check. Thread the outer @table parent names so inner
                // errors still name the original tables, not the intermediate nested type.
                compareNestedFieldsShape(rnf, onf, repParent, otherParent, errors);
            } else if (!(rf instanceof ChildField.NestingField)) {
                // Non-column, non-nesting leaves are not yet supported as multi-parent shared nested
                // fields, their resolution depends on per-parent metadata that this shape check
                // doesn't inspect. Lands with the corresponding roadmap #8 arm.
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
    private void validateConstructorField(no.sikt.graphitron.rewrite.model.ChildField.ConstructorField field, List<ValidationError> errors) {}
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
        errors.add(new ValidationError(
            type.name(),
            type.rejection().prefixedWith("Type '" + type.name() + "': "),
            type.location()
        ));
    }

    private void validateUnclassifiedField(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField field, List<ValidationError> errors) {
        errors.add(new ValidationError(
            field.qualifiedName(),
            field.rejection().prefixedWith("Field '" + field.qualifiedName() + "': "),
            field.location()
        ));
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "error-channel.local-context-transport",
        reliesOn = "The validator mirrors the producer invariant that a carrier admitted with "
            + "ErrorChannel.LocalContext has a data field whose fetcher honors the null-source "
            + "short-circuit guard. Rejects schemas whose ErrorsField carries Transport.LocalContext "
            + "but whose sibling data-channel field is not on the "
            + "LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS allow-list as Rejection.structural so "
            + "emit-time consumers (TypeFetcherGenerator's catch arm and asyncWrapTail) can assume "
            + "the guard without a runtime branch.")
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
     * {@link ChildField.Transport.LocalContext}. Each entry's matching emitter method in
     * {@code FetcherEmitter} carries a {@code @DependsOnClassifierCheck(key =
     * "error-channel.local-context-transport")} pinning the guarantee:
     *
     * <ul>
     *   <li>{@code SingleRecordTableField} → {@code buildSingleRecordTableFetcherValueRecordWrap}
     *       + {@code buildSingleRecordTableFetcherValueTableRecordWrap} (explicit
     *       {@code if (source == null) return null;} before any
     *       {@code source.value1()} / {@code record.get(<PK>)} read).</li>
     *   <li>{@code SingleRecordIdFieldFromReturning} → {@code buildSingleRecordIdFromReturningFetcherValue}
     *       (explicit guard before encoder dispatch).</li>
     *   <li>{@code SingleRecordTableFieldFromReturning} → {@code buildSingleRecordTableFromReturningFetcherValue}
     *       (explicit guard before PK copy onto the synthesised Record).</li>
     * </ul>
     *
     * <p>The audit harness {@code LoadBearingGuaranteeAuditTest} surfaces a missing producer if
     * the {@code @LoadBearingClassifierCheck} on the LocalContext producer is relaxed below the
     * consumer count. Adding a variant to this set without anchoring the matching emitter site
     * annotation orphans the new consumer; removing the guard from an existing emitter arm must
     * remove the variant from this set so the consumer-side annotation stops referencing a
     * no-longer-honored invariant.
     */
    private static final java.util.Set<Class<? extends GraphitronField>> LOCAL_CONTEXT_GUARDED_DATA_CHANNEL_VARIANTS = java.util.Set.of(
        ChildField.SingleRecordTableField.class,
        ChildField.SingleRecordIdFieldFromReturning.class,
        ChildField.SingleRecordTableFieldFromReturning.class
    );
}
