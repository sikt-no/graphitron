package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
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
        return List.copyOf(errors);
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
            case no.sikt.graphitron.rewrite.model.QueryField.QueryEntityField f        -> validateQueryEntityField(f, errors);
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
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnField f             -> validateColumnField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField f    -> validateColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NodeIdField f             -> validateNodeIdField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField f    -> validateNodeIdReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableField f              -> validateTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SplitTableField f        -> validateSplitTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.LookupTableField f       -> validateLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField f  -> validateSplitLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableMethodField f        -> validateTableMethodField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField f     -> validateTableInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.InterfaceField f          -> validateInterfaceField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.UnionField f              -> validateUnionField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.NestingField f            -> validateNestingField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ConstructorField f        -> validateConstructorField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField f       -> validateServiceTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField f      -> validateServiceRecordField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordTableField f        -> validateRecordTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField f  -> validateRecordLookupTableField(f, types, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.RecordField f             -> validateRecordField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.ComputedField f           -> validateComputedField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.PropertyField f           -> validatePropertyField(f, errors);
            case no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField f -> validateMultitableReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.ColumnField f            -> validateInputColumnField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField f  -> validateInputColumnReferenceField(f, errors);
            case no.sikt.graphitron.rewrite.model.InputField.NodeIdField f            -> {} // no extra validation yet
            case no.sikt.graphitron.rewrite.model.InputField.NodeIdReferenceField f  -> {} // no extra validation yet
            case no.sikt.graphitron.rewrite.model.InputField.NestingField f          -> validateInputNestingField(f, errors);
            case no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField f -> validateUnclassifiedField(f, errors);
        }
        validatePaginationRequiresOrdering(field, errors);
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
            errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': paginated fields must have ordering "
                    + "(add @defaultOrder or @orderBy)",
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
     * <p>The {@link TypeFetcherGenerator#NOT_IMPLEMENTED_REASONS} map is the single source of
     * truth for "stubbed" status. Variants in {@code IMPLEMENTED_LEAVES} or
     * {@code NOT_DISPATCHED_LEAVES} return {@code null} from this lookup and are correctly
     * ignored — an invariant enforced by
     * {@code GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus}.
     */
    private void validateVariantIsImplemented(GraphitronField field, List<ValidationError> errors) {
        String reason = TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.get(field.getClass());
        if (reason != null) {
            errors.add(new ValidationError(RejectionKind.DEFERRED,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': " + reason,
                field.location()
            ));
            return;
        }
        // Intra-variant stubs: some SplitTableField / SplitLookupTableField shapes (single
        // cardinality, condition-join in FK path, empty FK path) compile but the emitted
        // rows method throws at request time. NOT_IMPLEMENTED_REASONS is class-keyed so
        // cannot distinguish these from emittable shapes, delegate to the emitter's own
        // shape predicate so validator and emitter stay in lock-step.
        if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.SplitTableField stf) {
            no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter.unsupportedReason(stf)
                .ifPresent(msg -> errors.add(new ValidationError(RejectionKind.DEFERRED,
                    field.qualifiedName(),
                    "Field '" + field.qualifiedName() + "': " + msg, field.location())));
        } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField slf) {
            no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter.unsupportedReason(slf)
                .ifPresent(msg -> errors.add(new ValidationError(RejectionKind.DEFERRED,
                    field.qualifiedName(),
                    "Field '" + field.qualifiedName() + "': " + msg, field.location())));
        } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.RecordTableField rtf) {
            no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter.unsupportedReason(rtf)
                .ifPresent(msg -> errors.add(new ValidationError(RejectionKind.DEFERRED,
                    field.qualifiedName(),
                    "Field '" + field.qualifiedName() + "': " + msg, field.location())));
        } else if (field instanceof no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField rltf) {
            no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter.unsupportedReason(rltf)
                .ifPresent(msg -> errors.add(new ValidationError(RejectionKind.DEFERRED,
                    field.qualifiedName(),
                    "Field '" + field.qualifiedName() + "': " + msg, field.location())));
        }
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
    }
    private void validateUnionType(no.sikt.graphitron.rewrite.model.GraphitronType.UnionType type, List<ValidationError> errors) {
        validateParticipants(type.name(), type.participants(), errors);
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
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                type.name() + ".totalCount",
                "Field '" + type.name() + ".totalCount' must be of type 'Int' (got '"
                    + graphql.schema.GraphQLTypeUtil.simplePrint(fd.getType()) + "')",
                location
            ));
        }
    }

    private void validateInputType(no.sikt.graphitron.rewrite.model.GraphitronType.InputType type, Map<String, GraphitronType> types, List<ValidationError> errors) {
        // Type-existence of field types is already guaranteed by graphql-java schema validation.
        // Graphitron-specific constraints (e.g. javaName deprecation) will be added here.
    }

    private void validateTableInputType(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType type, List<ValidationError> errors) {
        // Unresolved tables and unresolved fields are caught by the builder (UnclassifiedType).
    }

    private void validateParticipants(String typeName, java.util.List<no.sikt.graphitron.rewrite.model.ParticipantRef> participants, List<ValidationError> errors) {
        // Unbound participants are caught by the builder (UnclassifiedType). Nothing to validate here.
    }

    // --- Field validators (stubs — filled in as test classes are added) ---

    private void validateQueryLookupTableField(no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': lookup fields must not return a connection",
                field.location()
            ));
        } else {
            // Lookup cardinality is determined by whether any @lookupKey arg is a list. Post argres
            // Phase 1, lookup-key args live on LookupMapping; any legacy filter-carried list arg is
            // also considered (validator is input-shape-agnostic and covers both paths).
            boolean anyKeyIsList = switch (field.lookupMapping()) {
                    case no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping cm ->
                        cm.columns().stream().anyMatch(no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupColumn::list);
                    case no.sikt.graphitron.rewrite.model.LookupMapping.NodeIdMapping nim ->
                        nim.list();
                }
                || field.filters().stream().anyMatch(f -> switch (f) {
                    case no.sikt.graphitron.rewrite.model.GeneratedConditionFilter gcf ->
                        gcf.bodyParams().stream().anyMatch(bp -> bp.list());
                    case no.sikt.graphitron.rewrite.model.ConditionFilter ignored -> false;
                });
            boolean returnIsList = field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List;
            if (anyKeyIsList != returnIsList) {
                errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                    field.qualifiedName(),
                    "Field '" + field.qualifiedName() + "': result type does not match input cardinality",
                    field.location()
                ));
            }
        }
        if (field.orderBy() instanceof OrderBySpec.Argument) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': @orderBy is not valid on a lookup field",
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
    private void validateQueryEntityField(no.sikt.graphitron.rewrite.model.QueryField.QueryEntityField field, List<ValidationError> errors) {}
    private void validateQueryTableInterfaceField(no.sikt.graphitron.rewrite.model.QueryField.QueryTableInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryInterfaceField(no.sikt.graphitron.rewrite.model.QueryField.QueryInterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateQueryUnionField(no.sikt.graphitron.rewrite.model.QueryField.QueryUnionField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
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
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': @column is not valid on a non-table-backed type",
                field.location()
            ));
        }
        if (field.javaNamePresent()) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': @field(javaName:) is not supported in record-based output",
                field.location()
            ));
        }
    }
    private void validateColumnReferenceField(no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField field, List<ValidationError> errors) {
        if (field.javaNamePresent()) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': @field(javaName:) is not supported in record-based output",
                field.location()
            ));
        }
        if (field.joinPath().isEmpty()) {
            errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': @reference path is required",
                field.location()
            ));
        } else {
            validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        }
    }
    private void validateNodeIdField(no.sikt.graphitron.rewrite.model.ChildField.NodeIdField field, List<ValidationError> errors) {
        // NodeIdField is only classified when the parent type is a NodeType.
        // The absence-of-@node case is classified as UnclassifiedField in the builder.
    }
    private void validateNodeIdReferenceField(no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField field, List<ValidationError> errors) {
        // @node is always resolved — builder returns UnclassifiedField if the type is missing or lacks @node.
        // FK-count validation (implicit inference failure) is handled at classification time in
        // BuildContext.parsePath; the path is either non-empty here or classification already emitted
        // an UnclassifiedField. Validate the path's steps and that it leads to the target type's table.
        if (!(field.targetType() instanceof ReturnTypeRef.TableBoundReturnType tb)) {
            validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
            return;
        }
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateReferenceLeadsToType(field.qualifiedName(), field.location(), field.joinPath(), field.typeName(), tb.table(), errors);
    }

    private void validateReferenceLeadsToType(String fieldName, SourceLocation location, List<JoinStep> path, String typeName, no.sikt.graphitron.rewrite.model.TableRef targetTable, List<ValidationError> errors) {
        if (path.isEmpty()) return; // classifier guarantees non-empty for this variant; skip in isolated validator unit tests
        var lastStep = path.getLast();
        switch (lastStep) {
            case JoinStep.FkJoin fk -> {
                if (!fk.targetTable().tableName().equalsIgnoreCase(targetTable.tableName())) {
                    errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                        fieldName,
                        "Field '" + fieldName + "': @reference path does not lead to the table of type '" + typeName + "'",
                        location
                    ));
                }
            }
            case JoinStep.ConditionJoin ignored -> { /* no FK tables to check */ }
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
                errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                    field.qualifiedName(),
                    "Field '" + field.qualifiedName() + "': @splitQuery connections require a non-empty ORDER BY "
                        + "(add @defaultOrder, @orderBy, or a primary key on the target table)",
                    field.location()
                ));
            }
        }
    }
    private void validateLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.LookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateSplitLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableMethodField(no.sikt.graphitron.rewrite.model.ChildField.TableMethodField field, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateTableInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField field, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateInterfaceField(no.sikt.graphitron.rewrite.model.ChildField.InterfaceField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateUnionField(no.sikt.graphitron.rewrite.model.ChildField.UnionField field, List<ValidationError> errors) {
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateNestingField(no.sikt.graphitron.rewrite.model.ChildField.NestingField field, List<ValidationError> errors) {
        // List cardinality has no source-passthrough semantic: one parent Record in, one list value out.
        if (field.returnType().wrapper() instanceof FieldWrapper.List) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': list cardinality on a plain-object nesting field is not supported",
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
        ChildField.NodeIdField.class,
        ChildField.TableField.class,
        ChildField.LookupTableField.class,
        ChildField.ConstructorField.class,
        ChildField.NestingField.class,
        ChildField.SplitTableField.class,
        ChildField.SplitLookupTableField.class);

    private void validateVariantIsSupportedAtNestedDepth(GraphitronField field, List<ValidationError> errors) {
        // Stubbed variants already surfaced by validateVariantIsImplemented, don't double-report.
        if (TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS.containsKey(field.getClass())) {
            return;
        }
        if (!NESTED_WIREABLE_LEAVES.contains(field.getClass())) {
            errors.add(new ValidationError(RejectionKind.DEFERRED,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': " + field.getClass().getSimpleName()
                    + " is not yet supported under NestingField — see graphitron-rewrite/roadmap/stub-non-table-scalar-child-leaves.md",
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
                errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                    coord,
                    "Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' exists on the first but not the second",
                    other.location()
                ));
                continue;
            }
            if (!rf.getClass().equals(of.getClass())) {
                errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                    coord,
                    "Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' classifies as " + rf.getClass().getSimpleName() + " on the first but "
                        + of.getClass().getSimpleName() + " on the second",
                    other.location()
                ));
                continue;
            }
            if (rf instanceof ChildField.ColumnField rcf && of instanceof ChildField.ColumnField ocf) {
                if (!rcf.column().sqlName().equals(ocf.column().sqlName())) {
                    errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                        coord,
                        "Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' resolves to column '" + rcf.column().sqlName() + "' on the first but '"
                            + ocf.column().sqlName() + "' on the second",
                        other.location()
                    ));
                } else if (!rcf.column().columnClass().equals(ocf.column().columnClass())) {
                    errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                        coord,
                        "Nested type '" + nestedTypeName + "' shared across '" + repParent
                            + "' and '" + otherParent + "': field '" + name
                            + "' has Java type '" + rcf.column().columnClass() + "' on the first but '"
                            + ocf.column().columnClass() + "' on the second",
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
                errors.add(new ValidationError(RejectionKind.DEFERRED,
                    coord,
                    "Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' classifies as " + rf.getClass().getSimpleName()
                        + " which is not yet supported across multiple parents — see graphitron-rewrite/roadmap/stub-non-table-scalar-child-leaves.md",
                    other.location()
                ));
            }
        }
        // Report fields present on `other` but not on `rep`.
        String coord = otherParent + "." + other.name();
        for (var name : otherByName.keySet()) {
            if (!repByName.containsKey(name)) {
                errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                    coord,
                    "Nested type '" + nestedTypeName + "' shared across '" + repParent
                        + "' and '" + otherParent + "': field '" + name
                        + "' exists on the second but not the first",
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
            errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': @service on a table-bound return type requires a Sources parameter for DataLoader batching",
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
            errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': @service on a table-bound return type requires the parent table '" + parentTable.tableName() + "' to have a primary key",
                field.location()
            ));
        }
    }
    private void validateServiceRecordField(no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
    }
    private void validateRecordTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateRecordLookupTableField(no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField field, Map<String, GraphitronType> types, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
        if (field.returnType().wrapper() instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
            errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
                field.qualifiedName(),
                "Field '" + field.qualifiedName() + "': lookup fields must not return a connection",
                field.location()
            ));
        }
        validateCardinality(field.qualifiedName(), field.location(), field.returnType().wrapper(), errors);
    }
    private void validateRecordField(no.sikt.graphitron.rewrite.model.ChildField.RecordField field, List<ValidationError> errors) {}

    private void validateComputedField(no.sikt.graphitron.rewrite.model.ChildField.ComputedField field, List<ValidationError> errors) {
        validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
    }
    private void validatePropertyField(no.sikt.graphitron.rewrite.model.ChildField.PropertyField field, List<ValidationError> errors) {}
    private void validateMultitableReferenceField(no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField field, List<ValidationError> errors) {
        errors.add(new ValidationError(RejectionKind.INVALID_SCHEMA,
            field.qualifiedName(),
            "Field '" + field.qualifiedName() + "': @multitableReference is not supported in record-based output",
            field.location()
        ));
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
    private void validateUnclassifiedType(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType type, List<ValidationError> errors) {
        errors.add(new ValidationError(RejectionKind.AUTHOR_ERROR,
            type.name(),
            "Type '" + type.name() + "': " + type.reason(),
            type.location()
        ));
    }

    private void validateUnclassifiedField(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField field, List<ValidationError> errors) {
        errors.add(new ValidationError(field.kind(),
            field.qualifiedName(),
            "Field '" + field.qualifiedName() + "': " + field.reason(),
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
}
