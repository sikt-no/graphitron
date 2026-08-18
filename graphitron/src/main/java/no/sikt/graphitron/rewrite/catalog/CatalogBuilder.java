package no.sikt.graphitron.rewrite.catalog;

import graphql.language.ArrayValue;
import graphql.language.Description;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.rewrite.derive.FieldClaim;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.RoutineResolution;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles a {@link CompletionData} snapshot the LSP queries against. Sources
 * tables / columns / FK references from {@link JooqCatalog}, scalar types from
 * the parsed {@link GraphQLSchema}, and the consumer's compiled service /
 * condition / record class FQNs from {@link ClasspathScanner} over
 * {@code <basedir>/target/classes/}.
 *
 * <p>Designed to run hot: a single pass over the jOOQ catalog plus a single
 * pass over the assembled schema's type list. The dev goal calls
 * {@link no.sikt.graphitron.rewrite.GraphQLRewriteGenerator#buildOutput()}
 * on every classpath-watcher trigger; this class is the workhorse behind
 * that call.
 *
 * <p>The catalog carries the generated jOOQ table class FQN on each
 * {@link CompletionData.Table} and the {@code Keys} class FQN on each
 * {@link CompletionData.Reference}, but no source positions: the LSP joins those
 * FQNs against the store's {@code java_} family at request time, so jOOQ
 * goto-definition / hover ride the {@code .java} source cadence rather than the
 * generator build cadence. This builder does not walk sources: the
 * {@code description} slots carry the build-derivable fallback only (the table's
 * SQL comment; empty for columns and services), and the LSP overlays the source
 * Javadoc when the store has it.
 */
public final class CatalogBuilder {

    private CatalogBuilder() {}

    /**
     * The classification projections over a parsed schema, without the detection's conflicts. Its
     * callers are the classifier's own tests, the production pipeline passing the conflicts through
     * the overload below.
     */
    public static LspSchemaSnapshot.Built.Current buildSnapshot(
        TypeDefinitionRegistry registry, GraphitronSchema schema
    ) {
        return buildSnapshot(registry, schema, List.of());
    }

    /**
     * {@link #buildSnapshot(TypeDefinitionRegistry, GraphitronSchema)} plus the
     * detection's field conflicts: each conflict overlays its coordinate's projection with the
     * {@link FieldClassification.Conflicted} arm, so the LSP and MCP surfaces render the rival
     * claims from the claim relations instead of the walk's arm-order winner. The overlay writes
     * only over coordinates the walked projection map already carries (the detection's domain
     * gate makes that true; this states it as the overlay's contract), so the map's documented
     * domain never widens.
     */
    public static LspSchemaSnapshot.Built.Current buildSnapshot(
        TypeDefinitionRegistry registry, GraphitronSchema schema,
        List<AuthoredClaimConflicts.FieldVerdict.Conflict> fieldConflicts
    ) {
        var fieldClassifications = (schema == null)
            ? Map.<String, FieldClassification>of()
            : projectFieldClassifications(schema, fieldConflicts);
        var typeClassifications = (schema == null)
            ? Map.<String, TypeClassification>of()
            : projectTypeClassifications(schema, registry, fieldClassifications);
        return new LspSchemaSnapshot.Built.Current(fieldClassifications, typeClassifications);
    }

    /**
     * Projects every classified field onto its {@link FieldClassification} variant, keyed by
     * {@code "ParentType.fieldName"}, from the output field index
     * ({@link GraphitronSchema#fields()}). Input types carry no classified {@link InputField}
     * list of their own (input fields are resolved per consuming field), so input-field
     * declarations contribute no entries here.
     */
    private static Map<String, FieldClassification> projectFieldClassifications(
        GraphitronSchema schema, List<AuthoredClaimConflicts.FieldVerdict.Conflict> fieldConflicts
    ) {
        var out = new LinkedHashMap<String, FieldClassification>();
        for (var entry : schema.fields().entrySet()) {
            var coord = entry.getKey().getTypeName() + "." + entry.getKey().getFieldName();
            out.put(coord, projectFieldClassification(entry.getValue(), schema));
        }
        // The Conflicted overlay: replace the walk's arm-order winner at each conflicted
        // coordinate with the claims themselves. Existing keys only, per the method contract.
        for (var conflict : fieldConflicts) {
            out.computeIfPresent(conflict.coordinate(), (coord, winner) -> conflictedOf(conflict));
        }
        return Map.copyOf(out);
    }

    /**
     * The one mapping site where the derive-side claim payload lifts into the projection's
     * {@link FieldClassification.Conflicted} view records. The switch is exhaustive over the
     * {@link FieldClaim} permits with no default, so a new claiming classifier fails here to
     * compile until its projection arm exists; positions decode to the catalog module's own
     * {@link CompletionData.SourceLocation} so no graphql-java type crosses the boundary.
     */
    private static FieldClassification.Conflicted conflictedOf(
        AuthoredClaimConflicts.FieldVerdict.Conflict conflict
    ) {
        var claims = new ArrayList<FieldClassification.Claim>(conflict.claims().size());
        for (var claim : conflict.claims()) {
            claims.add(switch (claim) {
                case FieldClaim.Service c -> new FieldClassification.Claim.Service(
                    c.className(), c.method(), c.trigger(), c.decoded(), claimLocation(c.location()));
                case FieldClaim.ExternalField c -> new FieldClassification.Claim.ExternalField(
                    c.className(), c.method(), c.trigger(), c.decoded(), claimLocation(c.location()));
                case FieldClaim.NodeId c -> new FieldClassification.Claim.NodeId(
                    c.nodeTypeRef(), c.trigger(), c.decoded(), claimLocation(c.location()));
                case FieldClaim.LookupKey c -> new FieldClassification.Claim.LookupKey(
                    c.trigger(), c.decoded(), claimLocation(c.location()));
                case FieldClaim.Routine c -> new FieldClassification.Claim.Routine(
                    c.routineRefs(), c.trigger(), c.decoded(), claimLocation(c.location()));
                case FieldClaim.Mutation c -> new FieldClassification.Claim.Mutation(
                    c.operation(), c.tableRef(), c.trigger(), c.decoded(), claimLocation(c.location()));
            });
        }
        return new FieldClassification.Conflicted(claims, conflict.rejection().message());
    }

    /**
     * A claim's own position as the catalog projection carries it, mirroring
     * {@link #putTypeLocation}'s decode ({@code file://} URI, 0-based line and column);
     * {@code null} when the claim row is unpositioned.
     */
    private static CompletionData.SourceLocation claimLocation(SourceLocation loc) {
        if (loc == null || loc.getSourceName() == null) {
            return null;
        }
        int line = Math.max(loc.getLine() - 1, 0);
        int column = Math.max(loc.getColumn() - 1, 0);
        return new CompletionData.SourceLocation("file://" + loc.getSourceName(), line, column);
    }

    /**
     * Projects a single classified field onto its {@link FieldClassification}
     * variant. The exhaustive switch over the {@code GraphitronField} sealed permits is
     * the load-bearing coverage contract: adding a new permit fails this switch to
     * compile until the LSP-side projection lands.
     */
    static FieldClassification projectFieldClassification(GraphitronField field, GraphitronSchema schema) {
        return switch (field) {
            // --- ChildField permits ---
            // Arity is read off the leaf's isComposite() accessor, never re-derived from the
            // size predicate; the Composite* projection variants are a kept denormalized view
            // of the merged column-backed leaves so the wire surface does not churn.
            case ChildField.ColumnBackedField f -> f.isComposite()
                ? new FieldClassification.CompositeColumn(parentTableName(f, schema), columnSqlNames(f.columns()))
                : new FieldClassification.Column(parentTableName(f, schema), f.columns().get(0).sqlName());
            case ChildField.ColumnBackedReferenceField f -> f.isComposite()
                ? new FieldClassification.CompositeColumnReference(
                    terminalTableName(f.joinPath()),
                    columnSqlNames(f.columns()),
                    fkSteps(f.joinPath()))
                : new FieldClassification.ColumnReference(
                    terminalTableName(f.joinPath()), f.columns().get(0).sqlName(), fkSteps(f.joinPath()));
            case ChildField.ParticipantColumnReferenceField f ->
                new FieldClassification.ParticipantCrossTable(
                    f.targetTable() != null ? f.targetTable().tableName() : null,
                    f.column().sqlName(),
                    f.hop() != null ? fkSqlNameOrNull(f.pairs()) : null,
                    f.aliasName());
            case ChildField.SingleRecordIdFieldFromReturning ignored ->
                new FieldClassification.SingleRecordIdFromReturning();
            case ChildField.SingleRecordIdField f ->
                new FieldClassification.SingleRecordId(f.table().tableName());
            case ChildField.TableField f ->
                new FieldClassification.TableTarget(
                    targetTableName(f.returnType()), fkSteps(f.joinPath()), false, f.lookup().isKeyed());
            case ChildField.BatchedTableField f ->
                f.sourceShape() == no.sikt.graphitron.rewrite.model.SourceShape.Table
                    ? new FieldClassification.TableTarget(
                        targetTableName(f.returnType()), fkSteps(f.joinPath()), true, f.lookup().isKeyed())
                    : new FieldClassification.RecordTableTarget(
                        targetTableName(f.returnType()), fkSteps(f.joinPath()), f.lookup().isKeyed());
            // The delivery split collapses on this view, as the polymorphic pair's does below:
            // the completion surface exposes the discriminated shape, and inline-vs-batched is
            // not a fact a completion consumer asks of a coordinate.
            case ChildField.TableInterfaceField f ->
                new FieldClassification.TableInterface(
                    targetTableName(f.returnType()),
                    f.discriminatorColumn(),
                    participantNames(f.participants()));
            case ChildField.BatchedTableInterfaceField f ->
                new FieldClassification.TableInterface(
                    targetTableName(f.returnType()),
                    f.discriminatorColumn(),
                    participantNames(f.participants()));
            case ChildField.InterfaceField f ->
                new FieldClassification.Polymorphic(participantNames(f.participants()));
            case ChildField.UnionField f ->
                new FieldClassification.Polymorphic(participantNames(f.participants()));
            case ChildField.BatchedInterfaceField f ->
                new FieldClassification.Polymorphic(participantNames(f.participants()));
            case ChildField.BatchedUnionField f ->
                new FieldClassification.Polymorphic(participantNames(f.participants()));
            case ChildField.NestingField ignored ->
                new FieldClassification.Nesting();
            // The batched flag is the delivery axis, read off BatchKeyField membership the
            // way DeliveryFact.leafDerivedOf reads it.
            case ChildField.PivotSpecField f ->
                new FieldClassification.Pivot(f.pivot().table().tableName(),
                    f.pivot().discriminator().sqlName(), f.pivot().value().sqlName(),
                    f instanceof no.sikt.graphitron.rewrite.model.BatchKeyField);
            // A projection slot is a by-name read off the pivot record; its LSP surface is the
            // same column-or-accessor shape a record property presents (no accessor to name).
            case ChildField.PivotSlotField f ->
                new FieldClassification.RecordOrProperty(f.readName(), null);
            case ChildField.ServiceTableField f ->
                new FieldClassification.ServiceBacked(
                    f.method() != null ? f.method().className() : null,
                    f.method() != null ? f.method().methodName() : null,
                    true,
                    targetTableName(f.returnType()),
                    errorChannelName(f.errorChannel()));
            case ChildField.ServiceRecordField f ->
                new FieldClassification.ServiceBacked(
                    f.method() != null ? f.method().className() : null,
                    f.method() != null ? f.method().methodName() : null,
                    false,
                    null,
                    errorChannelName(f.errorChannel()));
            // Record-read leaf: the locator arm decides which slot carries the read fact — a
            // resolved or by-name column name, or the accessor member name (no column).
            case ChildField.RecordReadField f -> switch (f.locator()) {
                case no.sikt.graphitron.rewrite.model.ValueLocator.TypedColumn tc ->
                    new FieldClassification.RecordOrProperty(tc.column().sqlName(), null);
                case no.sikt.graphitron.rewrite.model.ValueLocator.ByName bn ->
                    new FieldClassification.RecordOrProperty(bn.sqlName(), null);
                case no.sikt.graphitron.rewrite.model.ValueLocator.JavaAccessor ja ->
                    new FieldClassification.RecordOrProperty(null, accessorName(ja.accessor()));
                case no.sikt.graphitron.rewrite.model.ValueLocator.DefaultRead dr ->
                    new FieldClassification.RecordOrProperty(dr.name(), null);
            };
            // The @service record-composite carrier's data field is a record-backed
            // source passthrough (no column, no accessor): project it onto the record-backed
            // RecordOrProperty label (FallThrough LSP arm, no column resolution).
            case ChildField.RecordCompositeField f ->
                new FieldClassification.RecordOrProperty(f.name(), null);
            case ChildField.ComputedField f ->
                new FieldClassification.Computed(
                    f.method() != null ? f.method().className() : null,
                    f.method() != null ? f.method().methodName() : null);
            case ChildField.ErrorsField f ->
                new FieldClassification.Errors(
                    f.errorTypes().stream().map(GraphitronType.ErrorType::name).toList());

            // --- QueryField permits ---
            // A @routine-sourced read keeps the method-backed RoutineBacked classification
            // (className = the generated Routines class): the LSP hover and jump-to-source
            // route to the routine's call surface, so the source-axis fork preserves the
            // consumer-visible projection the dedicated leaf used to carry.
            case QueryField.QueryTableField f ->
                f.routine() instanceof RoutineResolution.Chain chain
                    ? new FieldClassification.RoutineBacked(
                        targetTableName(f.returnType()),
                        chain.chain().routine().routinesClass().canonicalName(),
                        chain.chain().routine().methodName())
                    : new FieldClassification.QueryTable(targetTableName(f.returnType()), f.lookup().isKeyed());
            case QueryField.QueryNodeField ignored ->
                new FieldClassification.QueryNode(false);
            case QueryField.QueryNodesField ignored ->
                new FieldClassification.QueryNode(true);
            case QueryField.QueryTableInterfaceField f ->
                new FieldClassification.QueryTableInterface(
                    targetTableName(f.returnType()),
                    f.discriminatorColumn(),
                    participantNames(f.participants()));
            case QueryField.QueryInterfaceField f ->
                new FieldClassification.QueryPolymorphic(participantNames(f.participants()));
            case QueryField.QueryUnionField f ->
                new FieldClassification.QueryPolymorphic(participantNames(f.participants()));
            case QueryField.QueryServiceTableField f ->
                new FieldClassification.QueryService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    true,
                    targetTableName(f.returnType()),
                    errorChannelName(f.errorChannel()));
            case QueryField.QueryServiceRecordField f ->
                new FieldClassification.QueryService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    false,
                    null,
                    errorChannelName(f.errorChannel()));
            case QueryField.QueryServicePolymorphicField f ->
                // A @service field returning a multitable interface/union. The @service nature
                // is the salient hover fact; tableBound=false / tableName=null mirrors the
                // record variant (the return is a polymorphic type, not a single @table).
                new FieldClassification.QueryService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    false,
                    null,
                    errorChannelName(f.errorChannel()));
            case QueryField.QueryServiceTableInterfaceField f ->
                // A @service field returning a single-table discriminated interface; the shared
                // @table is reported as the target so hover names the backing table.
                new FieldClassification.QueryService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    true,
                    targetTableName(f.returnType()),
                    errorChannelName(f.errorChannel()));

            // --- MutationField permits ---
            // Like the routine read above, the routine write is a root table sourced
            // from a generated Routines-class method call, so it projects onto the method-backed
            // RoutineBacked classification (className = the generated Routines class; hover and
            // jump-to-source route to the routine's call surface).
            case MutationField.MutationRoutineWriteField f ->
                new FieldClassification.RoutineBacked(
                    targetTableName(f.returnType()),
                    f.chain().routine().routinesClass().canonicalName(),
                    f.chain().routine().methodName());
            // The routine carrier is still routine-backed: hover and jump-to-source route to
            // the routine's call surface exactly as on the direct-return sibling; the reported
            // table is the payload data field's target.
            case MutationField.MutationRoutineWriteRecordField f ->
                new FieldClassification.RoutineBacked(
                    f.targetTable().tableName(),
                    f.routine().routinesClass().canonicalName(),
                    f.routine().methodName());
            // The verb, the table name and the input type name all project off the write arm
            // (the arms' input surfaces differ; the Dml seal and the helpers below fold them).
            case MutationField.DmlTableField f ->
                new FieldClassification.DmlMutation(
                    dmlWriteTableName(f.write()),
                    dmlWriteInputTypeName(f.write()),
                    dmlKindOf(f.write()),
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationServiceTableField f ->
                new FieldClassification.MutationService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    true,
                    f.returnType() != null && f.returnType().table() != null
                        ? f.returnType().table().tableName() : null,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationServiceRecordField f ->
                new FieldClassification.MutationService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    false,
                    null,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationServicePolymorphicField f ->
                // Mutation analogue of QueryServicePolymorphicField.
                new FieldClassification.MutationService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    false,
                    null,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationServiceTableInterfaceField f ->
                // Mutation analogue of QueryServiceTableInterfaceField; reports the shared @table.
                new FieldClassification.MutationService(
                    f.serviceMethodCall().fqClassName(),
                    f.serviceMethodCall().methodName(),
                    true,
                    f.returnType() != null && f.returnType().table() != null
                        ? f.returnType().table().tableName() : null,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationDmlRecordField f ->
                new FieldClassification.DmlRecord(
                    dmlWriteTableName(f.write()),
                    dmlWriteInputTypeName(f.write()),
                    dmlKindOf(f.write()),
                    false,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationBulkDmlRecordField f ->
                new FieldClassification.DmlRecord(
                    dmlWriteTableName(f.write()),
                    dmlWriteInputTypeName(f.write()),
                    dmlKindOf(f.write()),
                    true,
                    errorChannelName(f.errorChannel()));

            // --- InputField permits ---
            // Same arity fold as the ChildField arms above: the Composite* projection variants
            // are a kept denormalized view derived from the merged leaves' isComposite().
            case InputField.ColumnBackedField f -> f.isComposite()
                ? new FieldClassification.CompositeColumn(parentTableName(f, schema), columnSqlNames(f.columns()))
                : new FieldClassification.Column(
                    parentTableName(f, schema),
                    !f.columns().isEmpty() && f.columns().get(0) != null ? f.columns().get(0).sqlName() : null);
            case InputField.ColumnBackedReferenceField f -> f.isComposite()
                ? new FieldClassification.CompositeColumnReference(
                    terminalTableName(f.joinPath()),
                    columnSqlNames(f.columns()),
                    fkSteps(f.joinPath()))
                : new FieldClassification.ColumnReference(
                    terminalTableName(f.joinPath()),
                    !f.columns().isEmpty() && f.columns().get(0) != null ? f.columns().get(0).sqlName() : null,
                    fkSteps(f.joinPath()));
            case InputField.NestingField ignored ->
                new FieldClassification.Nesting();
            case InputField.UnboundField f ->
                new FieldClassification.InputUnbound(
                    f.condition().map(c -> c.filter().className()).orElse(null),
                    f.condition().map(c -> c.filter().methodName()).orElse(null),
                    f.condition().map(c -> c.override()).orElse(false));
            // Value-identical to what the pre-split collapse projected for the same authored
            // shape. The arm is unreachable until input fields contribute projection entries
            // (input-member coordinates, umbrella work), which is also when the input-side
            // classification vocabulary gets its own honest arms.
            case InputField.ConditionOwnedField f ->
                new FieldClassification.InputUnbound(
                    f.condition().filter().className(),
                    f.condition().filter().methodName(),
                    true);

            // --- Unclassified (nothing resolved; conflicted coordinates are overlaid later) ---
            case GraphitronField.UnclassifiedField f ->
                new FieldClassification.Unresolvable(f.reason());
        };
    }

    /** The catalog's verb column, projected off the write payload's sealed arm. */
    private static no.sikt.graphitron.rewrite.model.DmlKind dmlKindOf(
        no.sikt.graphitron.rewrite.model.OperationMember.Write.Dml write
    ) {
        return switch (write) {
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Insert ignored ->
                no.sikt.graphitron.rewrite.model.DmlKind.INSERT;
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Upsert ignored ->
                no.sikt.graphitron.rewrite.model.DmlKind.UPSERT;
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Update ignored ->
                no.sikt.graphitron.rewrite.model.DmlKind.UPDATE;
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Delete ignored ->
                no.sikt.graphitron.rewrite.model.DmlKind.DELETE;
        };
    }

    private static String dmlWriteTableName(
        no.sikt.graphitron.rewrite.model.OperationMember.Write.Dml write
    ) {
        return write.table() != null ? write.table().tableName() : null;
    }

    private static String dmlWriteInputTypeName(
        no.sikt.graphitron.rewrite.model.OperationMember.Write.Dml write
    ) {
        return switch (write) {
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Insert i -> i.input().typeName();
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Upsert u -> u.input().typeName();
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Update u -> u.inputArg().inputTypeName();
            case no.sikt.graphitron.rewrite.model.OperationMember.Write.Delete d -> d.inputArg().inputTypeName();
        };
    }

    private static String targetTableName(
        no.sikt.graphitron.rewrite.model.ReturnTypeRef.TableBoundReturnType ret
    ) {
        return ret != null && ret.table() != null ? ret.table().tableName() : null;
    }

    private static String terminalTableName(List<JoinStep> joinPath) {
        if (joinPath == null || joinPath.isEmpty()) return null;
        for (int i = joinPath.size() - 1; i >= 0; i--) {
            var step = joinPath.get(i);
            var stepTarget = switch (step) {
                case JoinStep.Hop h when h.on() instanceof no.sikt.graphitron.rewrite.model.On.ColumnPairs -> h.targetTable();
                default -> null;
            };
            if (stepTarget != null) {
                return stepTarget.tableName();
            }
        }
        return null;
    }

    private static List<FieldClassification.FkStep> fkSteps(List<JoinStep> joinPath) {
        if (joinPath == null) return List.of();
        var out = new ArrayList<FieldClassification.FkStep>(joinPath.size());
        for (var step : joinPath) {
            switch (step) {
                case JoinStep.Hop hop -> out.add(switch (hop.on()) {
                    case no.sikt.graphitron.rewrite.model.On.ColumnPairs cp ->
                        // Hop.targetTable() is non-null by construction (Hop guards target,
                        // TableExpr.Catalog guards table).
                        new FieldClassification.FkStep(
                            hop.targetTable().tableName(),
                            fkSqlNameOrNull(cp));
                    case no.sikt.graphitron.rewrite.model.On.Predicate ignored ->
                        new FieldClassification.FkStep(null, null);
                    // A lateral routine node carries no FK; the step still lands on the
                    // routine's result table, which is what the LSP-facing projection surfaces.
                    case no.sikt.graphitron.rewrite.model.On.Lateral ignored ->
                        new FieldClassification.FkStep(hop.targetTable().tableName(), null);
                });
            }
        }
        return List.copyOf(out);
    }

    /**
     * The LSP-facing projection surfaces an FK constraint name where the pairs derive from a
     * catalog FK, and {@code null} for the name-matched-key derivation (which has no
     * constraint of its own), the same shape as a condition or lateral step.
     */
    private static String fkSqlNameOrNull(no.sikt.graphitron.rewrite.model.On.ColumnPairs cp) {
        return switch (cp.keying()) {
            case no.sikt.graphitron.rewrite.model.On.Keying.ForeignKey k -> k.fk().sqlName();
            case no.sikt.graphitron.rewrite.model.On.Keying.NameMatchedKey ignored -> null;
        };
    }

    private static List<String> columnSqlNames(List<ColumnRef> columns) {
        if (columns == null) return List.of();
        return columns.stream().map(ColumnRef::sqlName).toList();
    }

    private static List<String> participantNames(List<ParticipantRef> participants) {
        if (participants == null) return List.of();
        return participants.stream().map(ParticipantRef::typeName).toList();
    }

    private static String errorChannelName(java.util.Optional<? extends ErrorChannel> channel) {
        return channel == null ? null : channel.map(ErrorChannel::mappingsConstantName).orElse(null);
    }

    private static String parentTableName(GraphitronField field, GraphitronSchema schema) {
        var parent = schema.type(field.parentTypeName());
        return switch (parent) {
            case GraphitronType.TableType t -> t.table() != null ? t.table().tableName() : null;
            case GraphitronType.NodeType t -> t.table() != null ? t.table().tableName() : null;
            case GraphitronType.TableInterfaceType t -> t.table() != null ? t.table().tableName() : null;
            case null -> null;
            default -> null;
        };
    }

    private static String accessorName(no.sikt.graphitron.rewrite.model.AccessorResolution.Resolved accessor) {
        if (accessor == null) return null;
        return switch (accessor) {
            case no.sikt.graphitron.rewrite.model.AccessorResolution.GetterPrefixed g -> g.method().getName();
            case no.sikt.graphitron.rewrite.model.AccessorResolution.BareName b -> b.method().getName();
            case no.sikt.graphitron.rewrite.model.AccessorResolution.FieldRead fr -> fr.field().getName();
        };
    }

    /**
     * Projects every classified type onto its {@link TypeClassification} variant.
     * Exhaustive on the {@link GraphitronType} sealed permits.
     */
    private static Map<String, TypeClassification> projectTypeClassifications(
        GraphitronSchema schema, TypeDefinitionRegistry registry,
        Map<String, FieldClassification> fieldClassifications
    ) {
        var consumerTables = inputConsumerTables(registry, fieldClassifications);
        var out = new LinkedHashMap<String, TypeClassification>();
        for (var entry : schema.types().entrySet()) {
            var projected = projectTypeClassification(entry.getValue());
            // Consumer-derived pass: a plain input carries the tables its consuming fields resolve
            // it against. A plain input is not a modeled relation, so its table is a per-consumer
            // fact surfaced on this LSP view rather than decided once at classification time; every
            // consumer's table is kept (never collapsed when there is more than one), so the hover
            // shows per-consumer resolution.
            if (projected instanceof TypeClassification.PojoInput pojo) {
                projected = new TypeClassification.PojoInput(
                    pojo.fqClassName(), consumerTables.getOrDefault(entry.getKey(), List.of()));
            }
            out.put(entry.getKey(), projected);
        }
        return Map.copyOf(out);
    }

    /**
     * For each input type consumed as a field argument, the distinct tables its fields resolve
     * against, one per consuming field that resolves to a table. The arg&rarr;field edge is read
     * from SDL shape (which field declares an argument of the input type); the table is read from
     * the consuming field's already-classified target ({@link #resolvedArgTableName}), never from a
     * re-read {@code @table} directive, so the surfaced table matches the call-site resolution and
     * cannot drift from it. {@code @orderBy} arguments are excluded: their input type is a sort
     * wrapper, not a column-resolving filter.
     */
    private static Map<String, List<String>> inputConsumerTables(
        TypeDefinitionRegistry registry, Map<String, FieldClassification> fieldClassifications
    ) {
        var byInput = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var obj : registry.getTypes(graphql.language.ObjectTypeDefinition.class)) {
            for (var fieldDef : obj.getFieldDefinitions()) {
                var fc = fieldClassifications.get(obj.getName() + "." + fieldDef.getName());
                if (fc == null) continue;
                var table = resolvedArgTableName(fc);
                if (table.isEmpty()) continue;
                for (var arg : fieldDef.getInputValueDefinitions()) {
                    boolean orderBy = arg.getDirectives().stream().anyMatch(d -> "orderBy".equals(d.getName()));
                    if (orderBy) continue;
                    String argType = baseTypeName(arg.getType());
                    if (argType == null) continue;
                    byInput.computeIfAbsent(argType, k -> new LinkedHashSet<>()).add(table.get());
                }
            }
        }
        var out = new LinkedHashMap<String, List<String>>();
        byInput.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /**
     * The table a consuming field resolves its arguments against, read off the field's classified
     * target. Empty for a field that resolves to no table (a {@code @service} record return, a
     * scalar/computed field, a polymorphic dispatch), whose arguments do not resolve against a
     * single table. A best-effort projection over the table-bearing {@link FieldClassification}
     * arms; the {@code default} covers the tableless arms.
     */
    private static Optional<String> resolvedArgTableName(FieldClassification fc) {
        return switch (fc) {
            case FieldClassification.QueryTable q -> Optional.ofNullable(q.tableName());
            case FieldClassification.QueryTableInterface q -> Optional.ofNullable(q.tableName());
            case FieldClassification.RoutineBacked q -> Optional.ofNullable(q.tableName());
            case FieldClassification.TableTarget t -> Optional.ofNullable(t.tableName());
            case FieldClassification.RecordTableTarget t -> Optional.ofNullable(t.tableName());
            case FieldClassification.TableInterface t -> Optional.ofNullable(t.tableName());
            default -> Optional.empty();
        };
    }

    private static String baseTypeName(Type<?> type) {
        if (type instanceof NonNullType nn) return baseTypeName(nn.getType());
        if (type instanceof ListType lt) return baseTypeName(lt.getType());
        if (type instanceof TypeName tn) return tn.getName();
        return null;
    }

    /**
     * Projects a single classified type onto its {@link TypeClassification} variant.
     * Exhaustive on the {@code GraphitronType} sealed permits.
     */
    static TypeClassification projectTypeClassification(GraphitronType type) {
        return switch (type) {
            case GraphitronType.TableType t ->
                new TypeClassification.Table(t.table() != null ? t.table().tableName() : null);
            case GraphitronType.NodeType t ->
                new TypeClassification.Node(
                    t.table() != null ? t.table().tableName() : null,
                    t.typeId(),
                    t.nodeKeyColumns() == null ? List.of()
                        : t.nodeKeyColumns().stream().map(ColumnRef::sqlName).toList());
            case GraphitronType.TableInterfaceType t ->
                new TypeClassification.TableInterface(
                    t.table() != null ? t.table().tableName() : null,
                    t.discriminatorColumn(),
                    participantNames(t.participants()));
            case GraphitronType.InterfaceType t ->
                new TypeClassification.Interface(participantNames(t.participants()));
            case GraphitronType.UnionType t ->
                new TypeClassification.Union(participantNames(t.participants()));
            case GraphitronType.JavaRecordType t ->
                new TypeClassification.JavaRecord(t.fqClassName());
            case GraphitronType.JavaRecordInputType t ->
                new TypeClassification.JavaRecordInput(t.fqClassName());
            case GraphitronType.JooqRecordType t ->
                new TypeClassification.JooqRecord(t.fqClassName());
            case GraphitronType.JooqRecordInputType t ->
                new TypeClassification.JooqRecordInput(t.fqClassName());
            case GraphitronType.JooqTableRecordType t ->
                new TypeClassification.JooqTableRecord(
                    t.fqClassName(),
                    t.table() != null ? t.table().tableName() : null);
            case GraphitronType.JooqTableRecordInputType t ->
                new TypeClassification.JooqTableRecordInput(
                    t.fqClassName(),
                    t.table() != null ? t.table().tableName() : null);
            case GraphitronType.PojoResultType.Backed t ->
                new TypeClassification.PojoResult(t.fqClassName());
            case GraphitronType.PojoInputType t ->
                // resolvedTables is filled by the consumer-derived pass in projectTypeClassifications,
                // which has the schema-wide arg→consumer edges this per-type projection cannot see.
                new TypeClassification.PojoInput(t.fqClassName(), List.of());
            case GraphitronType.RootType t ->
                new TypeClassification.Root(t.name());
            case GraphitronType.ConnectionType t ->
                new TypeClassification.Connection(t.elementTypeName(), t.edgeTypeName());
            case GraphitronType.EdgeType t ->
                new TypeClassification.Edge(t.elementTypeName());
            case GraphitronType.PageInfoType ignored ->
                new TypeClassification.PageInfo();
            case GraphitronType.ErrorType t ->
                new TypeClassification.Error(
                    t.handlers() == null ? List.of()
                        : t.handlers().stream().map(CatalogBuilder::handlerKind).toList());
            case GraphitronType.EnumType ignored ->
                new TypeClassification.Enum();
            case GraphitronType.ScalarType t ->
                new TypeClassification.Scalar(
                    t.resolution() != null && t.resolution().javaType() != null
                        ? t.resolution().javaType().toString() : null);
            case GraphitronType.NestingType ignored ->
                new TypeClassification.PlainObject();
            // Synthesised facet container / value types project as plain objects; no
            // facet-specific classification leaf exists.
            case GraphitronType.FacetsType ignored ->
                new TypeClassification.PlainObject();
            case GraphitronType.FacetValueType ignored ->
                new TypeClassification.PlainObject();
            case GraphitronType.UnclassifiedType t ->
                new TypeClassification.Unclassified(t.reason());
        };
    }

    private static String handlerKind(GraphitronType.ErrorType.Handler handler) {
        return switch (handler) {
            case GraphitronType.ErrorType.ExceptionHandler ignored -> "exception";
            case GraphitronType.ErrorType.SqlStateHandler ignored -> "sql-state";
            case GraphitronType.ErrorType.VendorCodeHandler ignored -> "vendor-code";
            case GraphitronType.ErrorType.ValidationHandler ignored -> "validation";
        };
    }

    /**
     * Walks the lifted {@link GraphitronSchema} and projects each typed
     * variant into a {@link TypeBackingShape}. The dispatch is exhaustive on
     * the {@code GraphitronType} sealed permits, so any future variant trips
     * a compile error here. Each shape names what backs the type and nothing else: what a class
     * offers a member name is a fact about the class, which its consumers read from the store's
     * member-slot relation, so no member list is projected here and the bean rule has one home.
     *
     * <p>Public because its only reader is elsewhere: the walk's backing-class transcription
     * ({@link no.sikt.graphitron.rewrite.derive.TypeBackingClasses}) reduces this projection to
     * the class each shape names, and writes it as the shadow the store-native backing derivation
     * differs against. The snapshot carried this map to the language server until every surface
     * that read it asked the store instead; what the walk decided is still worth stating once, so
     * the switch survives its shipping channel.
     */
    public static Map<String, TypeBackingShape> projectTypesByName(GraphitronSchema schema) {
        var out = new LinkedHashMap<String, TypeBackingShape>();
        for (var entry : schema.types().entrySet()) {
            out.put(entry.getKey(), projectType(entry.getValue()));
        }
        return Map.copyOf(out);
    }

    private static TypeBackingShape projectType(GraphitronType type) {
        return switch (type) {
            case GraphitronType.JavaRecordType t -> new TypeBackingShape.RecordBacking(t.fqClassName());
            case GraphitronType.JavaRecordInputType t -> new TypeBackingShape.RecordBacking(t.fqClassName());
            case GraphitronType.PojoResultType.Backed t -> new TypeBackingShape.PojoBacking(t.fqClassName());
            case GraphitronType.PojoInputType t -> t.fqClassName() == null
                ? new TypeBackingShape.NoBacking.UnbackedResult()
                : new TypeBackingShape.PojoBacking(t.fqClassName());
            case GraphitronType.JooqRecordType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqRecordInputType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqTableRecordType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.JooqTableRecordInputType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.TableType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.NodeType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.TableInterfaceType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.RootType ignored -> new TypeBackingShape.NoBacking.Root();
            case GraphitronType.InterfaceType ignored -> new TypeBackingShape.NoBacking.UnclassifiedInterface();
            case GraphitronType.UnionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ErrorType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EnumType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ScalarType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ConnectionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EdgeType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.PageInfoType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.FacetsType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.FacetValueType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.NestingType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.UnclassifiedType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
        };
    }

    private static String tableNameOf(TableRef ref) {
        return ref == null ? null : ref.tableName();
    }

    private static TypeBackingShape jooqRecordWithTable(String fqClassName, TableRef table) {
        String tableName = tableNameOf(table);
        return tableName == null
            ? new TypeBackingShape.JooqRecordBacking.Standalone(fqClassName)
            : new TypeBackingShape.JooqRecordBacking.WithTable(fqClassName, tableName);
    }

    public static CompletionData build(JooqCatalog jooq, GraphQLSchema assembled, RewriteContext ctx) {
        // FQN of the generated jOOQ Keys class (jOOQ emits it at the package
        // root). Both the table classFqn and this Keys FQN are the join keys the
        // LSP resolves against its source index at request time; the catalog
        // carries no source positions and no source-derived Javadoc.
        String keysClassFqn = ctx.jooqPackage() + ".Keys";
        // No source walk here: goto-definition and hover both resolve positions
        // and Javadoc from the LSP-owned source index on the .java cadence. The
        // descriptions this builder sets are the build-derivable fallback only
        // (the jOOQ table's SQL comment; nothing for columns / services), which
        // the LSP overlays the source Javadoc onto when its index has it.
        return new CompletionData(
            buildTables(jooq, keysClassFqn),
            buildScalars(assembled),
            buildExternalReferences(ctx),
            buildNodeMetadata(assembled, new NodeDeclaration(jooq))
        );
    }

    /** Empty for {@code null} / blank so absent comments degrade to omitted, not empty-string-valued. */
    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * Walks every {@code GraphQLObjectType} in {@code assembled} and records
     * pre-deduction values from each one's {@code @node} directive. Presence
     * in the returned map is the predicate the LSP's {@code @nodeId(typeName:)}
     * arms read; missing axes (the author omitted {@code typeId:} or
     * {@code keyColumns:}) stay null and are not back-filled with classifier
     * deductions. The LSP intentionally operates on author-supplied data only;
     * cases where {@code typeId} or {@code keyColumns} are deduced by the
     * classifier (containing-type / unique-table / PK inference) are invisible
     * to in-editor feedback by design.
     *
     * <p>Those two facts pull in opposite directions once nodehood can be inferred, so they are
     * separated here: <em>presence</em> follows {@link NodeDeclaration}, so a node inferred from
     * {@code implements Node} plus catalog metadata is a node to the LSP exactly as it is to the
     * classifier, while the <em>values</em> stay author-supplied and both axes read null for it.
     * Presence is the predicate; keeping it on the directive would make the editor reject
     * {@code @nodeId(typeName:)} against a type the build accepts.
     */
    private static Map<String, CompletionData.NodeMetadata> buildNodeMetadata(
        GraphQLSchema assembled, NodeDeclaration nodes
    ) {
        var out = new LinkedHashMap<String, CompletionData.NodeMetadata>();
        for (var type : assembled.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType obj)) continue;
            if (!nodes.isNodeType(obj)) continue;
            GraphQLAppliedDirective node = obj.getAppliedDirective("node");
            out.put(obj.getName(), node == null
                ? new CompletionData.NodeMetadata(null, null)
                : new CompletionData.NodeMetadata(
                    readStringArg(node, "typeId"),
                    readStringListArg(node, "keyColumns")));
        }
        return Map.copyOf(out);
    }

    private static String readStringArg(GraphQLAppliedDirective directive, String argName) {
        GraphQLAppliedDirectiveArgument arg = directive.getArgument(argName);
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof StringValue sv) return sv.getValue();
        if (value instanceof String s) return s;
        return null;
    }

    private static List<String> readStringListArg(GraphQLAppliedDirective directive, String argName) {
        GraphQLAppliedDirectiveArgument arg = directive.getArgument(argName);
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof ArrayValue av) {
            var list = new ArrayList<String>(av.getValues().size());
            for (Value<?> v : av.getValues()) {
                if (v instanceof NullValue) {
                    list.add(null);
                } else if (v instanceof StringValue sv) {
                    list.add(sv.getValue());
                }
            }
            return List.copyOf(list);
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<String>(list.size());
            for (var v : list) {
                out.add(v == null ? null : v.toString());
            }
            return List.copyOf(out);
        }
        if (value instanceof StringValue sv) return List.of(sv.getValue());
        if (value instanceof String s) return List.of(s);
        return null;
    }

    /**
     * Class-name candidates for {@code @service} / {@code @condition} /
     * {@code @record} completion, with public methods of each populated
     * straight off the classfile (parameter names included when the
     * consumer compiled with {@code -parameters}).
     *
     * <p>Reads from {@link RewriteContext#classpathRoots()}: every reactor
     * project's compile-output directory, populated by the mojo from
     * {@code MavenSession.getAllProjects()}. Falls back to {@code
     * <basedir>/target/classes} as a single-root default when the context
     * carries no classpathRoots, so unit-tier callers built off
     * {@link RewriteContext}'s six-arg overload get the single-root scope.
     *
     * <p>Public because the capture load reads the same census on its own to fill the store's
     * {@code extension_} family; {@link #build} keeps reading it as one part of the LSP catalog.
     */
    public static List<CompletionData.ExternalReference> buildExternalReferences(RewriteContext ctx) {
        var roots = ctx.classpathRoots().isEmpty()
            ? List.of(ctx.basedir().resolve("target/classes"))
            : ctx.classpathRoots();
        // Bytecode-derived structure only; the class / method Javadoc the hover
        // path renders is overlaid from the LSP source index at request time.
        return ClasspathScanner.scan(roots, ctx.jooqPackage());
    }

    private static List<CompletionData.Table> buildTables(JooqCatalog jooq, String keysClassFqn) {
        var tables = new ArrayList<CompletionData.Table>();
        for (String tableName : jooq.allTableSqlNames()) {
            tables.add(buildTable(jooq, tableName, keysClassFqn));
        }
        return List.copyOf(tables);
    }

    private static CompletionData.Table buildTable(JooqCatalog jooq, String tableName, String keysClassFqn) {
        Optional<JooqCatalog.TableEntry> entryOpt = jooq.findTable(tableName).asEntry();
        Table<?> jooqTable = entryOpt.map(JooqCatalog.TableEntry::table).orElse(null);

        // Fully-qualified name of the generated jOOQ table class, e.g.
        // <jooqPackage>.tables.Film; the LSP keys class / field declarations by
        // this FQN when it resolves goto-definition / hover against the source index.
        String classFqn = jooqTable == null ? null : jooqTable.getClass().getName();

        // Build-derivable description only: the jOOQ table's SQL comment. The
        // generated class Javadoc (and column / service Javadoc) is overlaid from
        // the source index by the LSP, so it rides the .java cadence.
        String tableDescription = commentOf(jooqTable);

        var columns = jooq.allColumnsOf(tableName).stream()
            .map(c -> buildColumn(c))
            .toList();

        var references = jooqTable == null
            ? List.<CompletionData.Reference>of()
            : buildReferencesFor(jooq, jooqTable, keysClassFqn);

        return new CompletionData.Table(
            tableName,
            tableDescription,
            classFqn,
            columns,
            references
        );
    }

    /**
     * Builds one column from the jOOQ catalog structure. Carries no source
     * position and no source-derived Javadoc: goto-definition and hover both
     * join {@code (owning-table classFqn, name)} against the LSP-owned source
     * index at request time. The {@code description} is the build-derivable
     * fallback, which for a column is empty: the {@link JooqCatalog.ColumnEntry}
     * shape this reads carries no comment. Not because a column comment is
     * unreachable, which it is not; {@link JooqCatalog#columnFactsOf} reads it off
     * the live field for the catalog-discovery projection and the store census
     * captures it the same way. Hover prefers the source Javadoc the index owns,
     * so this shape never asked for the database's comment.
     */
    private static CompletionData.Column buildColumn(JooqCatalog.ColumnEntry c) {
        return new CompletionData.Column(
            c.javaName(),
            c.columnClass(),
            c.nullable(),
            ""
        );
    }

    /**
     * Outbound + inbound foreign-key references for a single table. The
     * {@code keyName} stored on each reference is the jOOQ-generated Java
     * constant on the {@code Keys} class (e.g. {@code FILM__FILM_LANGUAGE_ID_FKEY}),
     * which is the format the Rust LSP's existing matchers expect; the SQL
     * constraint name is the fallback when the {@code Keys} class is not
     * resolvable.
     */
    private static List<CompletionData.Reference> buildReferencesFor(
        JooqCatalog jooq, Table<?> table, String keysClassFqn
    ) {
        var refs = new ArrayList<CompletionData.Reference>();
        for (ForeignKey<?, ?> fk : table.getReferences()) {
            String targetTable = fk.getKey().getTable().getName();
            refs.add(new CompletionData.Reference(targetTable, keyConstant(jooq, fk), false, keysClassFqn));
        }
        // Inbound: any FK on another table that points at this one.
        String thisName = table.getName();
        for (String otherName : jooq.allTableSqlNames()) {
            if (otherName.equalsIgnoreCase(thisName)) continue;
            Table<?> other = jooq.findTable(otherName).asEntry().map(JooqCatalog.TableEntry::table).orElse(null);
            if (other == null) continue;
            for (ForeignKey<?, ?> fk : other.getReferences()) {
                if (fk.getKey().getTable().getName().equalsIgnoreCase(thisName)) {
                    refs.add(new CompletionData.Reference(otherName, keyConstant(jooq, fk), true, keysClassFqn));
                }
            }
        }
        return List.copyOf(refs);
    }

    private static String keyConstant(JooqCatalog jooq, ForeignKey<?, ?> fk) {
        return jooq.fkJavaConstantName(fk).orElse(fk.getName());
    }

    private static String commentOf(Table<?> table) {
        if (table == null) return "";
        String comment = table.getComment();
        return comment == null ? "" : comment;
    }

    private static List<CompletionData.TypeData> buildScalars(GraphQLSchema assembled) {
        return assembled.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLScalarType)
            .map(t -> (GraphQLScalarType) t)
            .filter(t -> !t.getName().startsWith("__"))
            .map(CatalogBuilder::toTypeData)
            .toList();
    }

    private static CompletionData.TypeData toTypeData(GraphQLScalarType s) {
        String description = s.getDescription();
        return new CompletionData.TypeData(
            s.getName(),
            List.of(),
            description == null ? "" : description,
            sourceLocation(s)
        );
    }

    private static CompletionData.SourceLocation sourceLocation(GraphQLScalarType s) {
        var def = s.getDefinition();
        if (def == null || def.getSourceLocation() == null) {
            return CompletionData.SourceLocation.UNKNOWN;
        }
        var loc = def.getSourceLocation();
        String uri = loc.getSourceName() == null ? "" : "file://" + loc.getSourceName();
        return new CompletionData.SourceLocation(uri, loc.getLine(), loc.getColumn());
    }
}
