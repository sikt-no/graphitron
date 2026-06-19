package no.sikt.graphitron.rewrite.catalog;

import graphql.language.ArrayValue;
import graphql.language.Description;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.NullValue;
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
import no.sikt.graphitron.rewrite.RewriteContext;
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
import no.sikt.graphitron.rewrite.model.TableRef;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>Source-location URIs follow the jOOQ Maven plugin's default output
 * layout under {@code <basedir>/target/generated-sources/jooq/}: each
 * table maps to {@code <pkgPath>/tables/<ClassName>.java} and FK references
 * map to {@code <pkgPath>/Keys.java}. When the consumer's source roots are on
 * the build, the {@link SourceWalker} refines table / column positions from
 * the file head ({@code 0:0}) to the per-line declaration and lifts Javadoc
 * into the {@code description} slots; the file-level synthesis stays as the
 * fallback for the pre-{@code mvn compile} state. URIs that do not exist on
 * disk are reduced to {@link CompletionData.SourceLocation#UNKNOWN} so
 * goto-definition silently no-ops on consumers with non-default jOOQ output
 * paths.
 */
public final class CatalogBuilder {

    private CatalogBuilder() {}

    /**
     * Projects the post-merge {@link TypeDefinitionRegistry}'s directive
     * definitions into the {@link LspSchemaSnapshot.Built.Current} shape the
     * LSP consumes through the snapshot side-channel. Pre-conditions: the
     * registry parsed cleanly (callers in {@code GraphQLRewriteGenerator}
     * throw before reaching this method on parse failure) and reflects the
     * full multi-file {@code extend type} merge plus the bundled-directives
     * overlay. No bundled-directive filter is applied; the LSP's
     * {@code DirectiveResolution} encodes bundled-shadows-snapshot
     * precedence so redundant entries are observationally invisible.
     */
    public static LspSchemaSnapshot.Built.Current buildSnapshot(TypeDefinitionRegistry registry) {
        return buildSnapshot(registry, null, null);
    }

    /**
     * Full projection: directive surface plus per-type backing shapes. The
     * three-arg form is what the production pipeline calls; the
     * {@link #buildSnapshot(TypeDefinitionRegistry)} overload exists so unit
     * tests of the directive arm can run without spinning up the full
     * classifier + jOOQ catalog.
     *
     * <p>When {@code schema} or {@code catalog} is {@code null} the
     * type-backing map is empty (back-compat for the one-arg overload only).
     */
    public static LspSchemaSnapshot.Built.Current buildSnapshot(
        TypeDefinitionRegistry registry, GraphitronSchema schema, CompletionData catalog
    ) {
        var directives = new ArrayList<DirectiveShape>();
        for (var def : registry.getDirectiveDefinitions().values()) {
            directives.add(new DirectiveShape(
                def.getName(),
                projectInputValues(def.getInputValueDefinitions()),
                descriptionOf(def.getDescription())
            ));
        }
        var typesByName = (schema == null || catalog == null)
            ? Map.<String, TypeBackingShape>of()
            : projectTypesByName(schema, catalog);
        var payloadDataFieldByType = (schema == null)
            ? Map.<String, String>of()
            : projectPayloadDataFields(schema);
        var fieldClassifications = (schema == null)
            ? Map.<String, FieldClassification>of()
            : projectFieldClassifications(schema);
        var typeClassifications = (schema == null)
            ? Map.<String, TypeClassification>of()
            : projectTypeClassifications(schema);
        return new LspSchemaSnapshot.Built.Current(
            directives, typesByName, payloadDataFieldByType,
            fieldClassifications, typeClassifications
        );
    }

    /**
     * R159 — projects the carrier-data-field coordinates onto the LSP snapshot. Walks
     * {@link GraphitronSchema#fields()} for fields classified as
     * {@code ChildField.RecordTableField}, {@code SingleRecordIdField}, or
     * {@code SingleRecordIdFieldFromReturning}; each marks its parent type as a single-record
     * carrier whose data field name is the field's own name.
     */
    private static Map<String, String> projectPayloadDataFields(GraphitronSchema schema) {
        var out = new LinkedHashMap<String, String>();
        for (var entry : schema.fields().entrySet()) {
            var field = entry.getValue();
            boolean isPayloadData =
                (field instanceof no.sikt.graphitron.rewrite.model.ChildField.RecordTableField rtf
                    && rtf.sourceKey().reader() instanceof no.sikt.graphitron.rewrite.model.SourceKey.Reader.ProducedRecordRead)
                || field instanceof no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdField
                || field instanceof no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning;
            if (isPayloadData) {
                out.put(field.parentTypeName(), field.name());
            }
        }
        return Map.copyOf(out);
    }

    /**
     * R160 — projects every classified field onto its {@link FieldClassification} variant.
     * Keyed by {@code "ParentType.fieldName"}. Exhaustive on the {@link GraphitronField}
     * sealed permit set; a new permit fails this switch to compile until the LSP-side
     * mapping lands in the same commit.
     *
     * <p>Walks both the output field index ({@link GraphitronSchema#fields()}) and the
     * input fields nested on table-input types. Input types other than
     * {@link GraphitronType.TableInputType} carry no classified {@link InputField} list
     * (the input-field machinery is gated on the table-input pathway), so they contribute
     * no entries here.
     */
    private static Map<String, FieldClassification> projectFieldClassifications(GraphitronSchema schema) {
        var out = new LinkedHashMap<String, FieldClassification>();
        for (var entry : schema.fields().entrySet()) {
            var coord = entry.getKey().getTypeName() + "." + entry.getKey().getFieldName();
            out.put(coord, projectFieldClassification(entry.getValue(), schema));
        }
        for (var type : schema.types().values()) {
            if (type instanceof GraphitronType.TableInputType tit) {
                for (var inputField : tit.inputFields()) {
                    var coord = tit.name() + "." + inputField.name();
                    out.put(coord, projectFieldClassification(inputField, schema));
                }
            }
        }
        return Map.copyOf(out);
    }

    /**
     * R160 — projects a single classified field onto its {@link FieldClassification}
     * variant. The exhaustive switch over the {@code GraphitronField} sealed permits is
     * the load-bearing coverage contract: adding a new permit to {@code ChildField} /
     * {@code QueryField} / {@code MutationField} / {@code InputField} fails this switch
     * to compile until the LSP-side projection lands.
     */
    static FieldClassification projectFieldClassification(GraphitronField field, GraphitronSchema schema) {
        return switch (field) {
            // --- ChildField permits ---
            case ChildField.ColumnField f ->
                new FieldClassification.Column(parentTableName(f, schema), f.columnName());
            case ChildField.ColumnReferenceField f ->
                new FieldClassification.ColumnReference(
                    terminalTableName(f.joinPath()), f.columnName(), fkSteps(f.joinPath()));
            case ChildField.ParticipantColumnReferenceField f ->
                new FieldClassification.ParticipantCrossTable(
                    f.targetTable() != null ? f.targetTable().tableName() : null,
                    f.column().sqlName(),
                    f.fkJoin() != null && f.fkJoin().fk() != null ? f.fkJoin().fk().sqlName() : null,
                    f.aliasName());
            case ChildField.CompositeColumnField f ->
                new FieldClassification.CompositeColumn(parentTableName(f, schema), columnSqlNames(f.columns()));
            case ChildField.CompositeColumnReferenceField f ->
                new FieldClassification.CompositeColumnReference(
                    terminalTableName(f.joinPath()),
                    columnSqlNames(f.columns()),
                    fkSteps(f.joinPath()));
            case ChildField.SingleRecordIdFieldFromReturning ignored ->
                new FieldClassification.SingleRecordIdFromReturning();
            case ChildField.SingleRecordIdField f ->
                new FieldClassification.SingleRecordId(
                    f.sourceKey() != null && f.sourceKey().target() != null
                        ? f.sourceKey().target().tableName() : null);
            case ChildField.TableField f ->
                new FieldClassification.TableTarget(
                    targetTableName(f.returnType()), fkSteps(f.joinPath()), false, false);
            case ChildField.SplitTableField f ->
                new FieldClassification.TableTarget(
                    targetTableName(f.returnType()), fkSteps(f.joinPath()), true, false);
            case ChildField.LookupTableField f ->
                new FieldClassification.TableTarget(
                    targetTableName(f.returnType()), fkSteps(f.joinPath()), false, true);
            case ChildField.SplitLookupTableField f ->
                new FieldClassification.TableTarget(
                    targetTableName(f.returnType()), fkSteps(f.joinPath()), true, true);
            case ChildField.TableMethodField f ->
                new FieldClassification.TableMethod(
                    targetTableName(f.returnType()),
                    f.method() != null ? f.method().className() : null,
                    f.method() != null ? f.method().methodName() : null,
                    false);
            case ChildField.RecordTableMethodField f ->
                new FieldClassification.TableMethod(
                    targetTableName(f.returnType()),
                    f.method() != null ? f.method().className() : null,
                    f.method() != null ? f.method().methodName() : null,
                    true);
            case ChildField.TableInterfaceField f ->
                new FieldClassification.TableInterface(
                    targetTableName(f.returnType()),
                    f.discriminatorColumn(),
                    participantNames(f.participants()));
            case ChildField.InterfaceField f ->
                new FieldClassification.Polymorphic(participantNames(f.participants()));
            case ChildField.UnionField f ->
                new FieldClassification.Polymorphic(participantNames(f.participants()));
            case ChildField.NestingField ignored ->
                new FieldClassification.Nesting();
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
            case ChildField.RecordTableField f ->
                new FieldClassification.RecordTableTarget(
                    targetTableName(f.returnType()), fkSteps(f.joinPath()), false);
            case ChildField.RecordLookupTableField f ->
                new FieldClassification.RecordTableTarget(
                    targetTableName(f.returnType()), fkSteps(f.joinPath()), true);
            case ChildField.RecordField f ->
                new FieldClassification.RecordOrProperty(
                    f.column() != null ? f.column().sqlName() : f.columnName(),
                    accessorName(f.accessor()));
            case ChildField.PropertyField f ->
                new FieldClassification.RecordOrProperty(
                    f.column() != null ? f.column().sqlName() : f.columnName(),
                    accessorName(f.accessor()));
            case ChildField.ComputedField f ->
                new FieldClassification.Computed(
                    f.method() != null ? f.method().className() : null,
                    f.method() != null ? f.method().methodName() : null);
            case ChildField.ErrorsField f ->
                new FieldClassification.Errors(
                    f.errorTypes().stream().map(GraphitronType.ErrorType::name).toList());

            // --- QueryField permits ---
            case QueryField.QueryTableField f ->
                new FieldClassification.QueryTable(targetTableName(f.returnType()), false);
            case QueryField.QueryLookupTableField f ->
                new FieldClassification.QueryTable(targetTableName(f.returnType()), true);
            case QueryField.QueryTableMethodTableField f ->
                new FieldClassification.QueryTableMethod(
                    targetTableName(f.returnType()),
                    f.method() != null ? f.method().className() : null,
                    f.method() != null ? f.method().methodName() : null);
            // R300 day-one: a @routine read is a root table sourced from a generated Routines-class
            // method call, so it projects onto the method-backed QueryTableMethod classification
            // (className = the generated Routines class). A dedicated QueryRoutine classification is a
            // follow-up once the LSP label/hover surface is wired.
            case QueryField.QueryRoutineTableField f ->
                new FieldClassification.QueryTableMethod(
                    targetTableName(f.returnType()),
                    f.routine() != null ? f.routine().routinesClass().canonicalName() : null,
                    f.routine() != null ? f.routine().methodName() : null);
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

            // --- MutationField permits ---
            case MutationField.MutationInsertTableField f -> dmlMutation(f.tableInputArg(), no.sikt.graphitron.rewrite.model.DmlKind.INSERT, f.errorChannel());
            case MutationField.MutationUpdateTableField f ->
                // R246: UPDATE carries InputArgRef instead of TableInputArg; the table name and
                // input type name come off the slim arg surface (both non-Optional by construction).
                new FieldClassification.DmlMutation(
                    f.inputArg().table().tableName(),
                    f.inputArg().inputTypeName(),
                    no.sikt.graphitron.rewrite.model.DmlKind.UPDATE,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationDeleteTableField f ->
                // R266: DELETE carries InputArgRef instead of TableInputArg; the table name and
                // input type name come off the slim arg surface (both non-Optional by construction).
                new FieldClassification.DmlMutation(
                    f.inputArg().table().tableName(),
                    f.inputArg().inputTypeName(),
                    no.sikt.graphitron.rewrite.model.DmlKind.DELETE,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationUpsertTableField f -> dmlMutation(f.tableInputArg(), no.sikt.graphitron.rewrite.model.DmlKind.UPSERT, f.errorChannel());
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
            case MutationField.MutationDmlRecordField f ->
                new FieldClassification.DmlRecord(
                    f.tableInputArg() != null && f.tableInputArg().inputTable() != null
                        ? f.tableInputArg().inputTable().tableName() : null,
                    f.tableInputArg() != null ? f.tableInputArg().typeName() : null,
                    f.kind(),
                    false,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationBulkDmlRecordField f ->
                new FieldClassification.DmlRecord(
                    f.tableInputArg() != null && f.tableInputArg().inputTable() != null
                        ? f.tableInputArg().inputTable().tableName() : null,
                    f.tableInputArg() != null ? f.tableInputArg().typeName() : null,
                    f.kind(),
                    true,
                    errorChannelName(f.errorChannel()));
            // R258: payload-returning UPDATE carries InputArgRef (not TableInputArg); the table /
            // input-type name come off the slim arg surface, and the kind is always UPDATE.
            case MutationField.MutationUpdatePayloadField f ->
                new FieldClassification.DmlRecord(
                    f.inputArg().table().tableName(),
                    f.inputArg().inputTypeName(),
                    no.sikt.graphitron.rewrite.model.DmlKind.UPDATE,
                    false,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationBulkUpdatePayloadField f ->
                new FieldClassification.DmlRecord(
                    f.inputArg().table().tableName(),
                    f.inputArg().inputTypeName(),
                    no.sikt.graphitron.rewrite.model.DmlKind.UPDATE,
                    true,
                    errorChannelName(f.errorChannel()));
            // R266: payload-returning DELETE carries InputArgRef (not TableInputArg); the table /
            // input-type name come off the slim arg surface, and the kind is always DELETE.
            case MutationField.MutationDeletePayloadField f ->
                new FieldClassification.DmlRecord(
                    f.inputArg().table().tableName(),
                    f.inputArg().inputTypeName(),
                    no.sikt.graphitron.rewrite.model.DmlKind.DELETE,
                    false,
                    errorChannelName(f.errorChannel()));
            case MutationField.MutationBulkDeletePayloadField f ->
                new FieldClassification.DmlRecord(
                    f.inputArg().table().tableName(),
                    f.inputArg().inputTypeName(),
                    no.sikt.graphitron.rewrite.model.DmlKind.DELETE,
                    true,
                    errorChannelName(f.errorChannel()));

            // --- InputField permits ---
            case InputField.ColumnField f ->
                new FieldClassification.Column(
                    parentTableName(f, schema),
                    f.column() != null ? f.column().sqlName() : null);
            case InputField.ColumnReferenceField f ->
                new FieldClassification.ColumnReference(
                    terminalTableName(f.joinPath()),
                    f.column() != null ? f.column().sqlName() : null,
                    fkSteps(f.joinPath()));
            case InputField.CompositeColumnField f ->
                new FieldClassification.CompositeColumn(parentTableName(f, schema), columnSqlNames(f.columns()));
            case InputField.CompositeColumnReferenceField f ->
                new FieldClassification.CompositeColumnReference(
                    terminalTableName(f.joinPath()),
                    columnSqlNames(f.columns()),
                    fkSteps(f.joinPath()));
            case InputField.NestingField ignored ->
                new FieldClassification.Nesting();
            case InputField.UnboundField f ->
                new FieldClassification.InputUnbound(
                    f.condition().map(c -> c.filter().className()).orElse(null),
                    f.condition().map(c -> c.filter().methodName()).orElse(null),
                    f.condition().map(c -> c.override()).orElse(false));

            // --- Unclassified ---
            case GraphitronField.UnclassifiedField f ->
                new FieldClassification.Unclassified(f.reason());
        };
    }

    private static FieldClassification dmlMutation(
        no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg inputArg,
        no.sikt.graphitron.rewrite.model.DmlKind kind,
        java.util.Optional<no.sikt.graphitron.rewrite.model.ErrorChannel> errorChannel
    ) {
        return new FieldClassification.DmlMutation(
            inputArg != null && inputArg.inputTable() != null ? inputArg.inputTable().tableName() : null,
            inputArg != null ? inputArg.typeName() : null,
            kind,
            errorChannelName(errorChannel)
        );
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
            if (step instanceof JoinStep.WithTarget t && t.targetTable() != null) {
                return t.targetTable().tableName();
            }
        }
        return null;
    }

    private static List<FieldClassification.FkStep> fkSteps(List<JoinStep> joinPath) {
        if (joinPath == null) return List.of();
        var out = new ArrayList<FieldClassification.FkStep>(joinPath.size());
        for (var step : joinPath) {
            switch (step) {
                case JoinStep.FkJoin fk -> out.add(new FieldClassification.FkStep(
                    fk.targetTable() != null ? fk.targetTable().tableName() : null,
                    fk.fk() != null ? fk.fk().sqlName() : null));
                case JoinStep.ConditionJoin ignored -> out.add(new FieldClassification.FkStep(null, null));
                case JoinStep.LiftedHop lh -> out.add(new FieldClassification.FkStep(
                    lh.targetTable() != null ? lh.targetTable().tableName() : null, null));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> columnSqlNames(List<ColumnRef> columns) {
        if (columns == null) return List.of();
        return columns.stream().map(ColumnRef::sqlName).toList();
    }

    private static List<String> participantNames(List<ParticipantRef> participants) {
        if (participants == null) return List.of();
        return participants.stream().map(ParticipantRef::typeName).toList();
    }

    private static String errorChannelName(java.util.Optional<ErrorChannel> channel) {
        return channel == null ? null : channel.map(ErrorChannel::mappingsConstantName).orElse(null);
    }

    private static String parentTableName(GraphitronField field, GraphitronSchema schema) {
        var parent = schema.type(field.parentTypeName());
        return switch (parent) {
            case GraphitronType.TableType t -> t.table() != null ? t.table().tableName() : null;
            case GraphitronType.NodeType t -> t.table() != null ? t.table().tableName() : null;
            case GraphitronType.TableInterfaceType t -> t.table() != null ? t.table().tableName() : null;
            case GraphitronType.TableInputType t -> t.table() != null ? t.table().tableName() : null;
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
     * R160 — projects every classified type onto its {@link TypeClassification} variant.
     * Exhaustive on the {@link GraphitronType} sealed permits.
     */
    private static Map<String, TypeClassification> projectTypeClassifications(GraphitronSchema schema) {
        var out = new LinkedHashMap<String, TypeClassification>();
        for (var entry : schema.types().entrySet()) {
            out.put(entry.getKey(), projectTypeClassification(entry.getValue()));
        }
        return Map.copyOf(out);
    }

    /**
     * R160 — projects a single classified type onto its {@link TypeClassification} variant.
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
                new TypeClassification.PojoInput(t.fqClassName());
            case GraphitronType.TableInputType t ->
                new TypeClassification.TableInput(t.table() != null ? t.table().tableName() : null);
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
     * a compile error here. Catalog-side data ({@link CompletionData#externalReferences})
     * supplies the record-component / accessor-method lists; the projector
     * itself does no class-file reading.
     */
    private static Map<String, TypeBackingShape> projectTypesByName(
        GraphitronSchema schema, CompletionData catalog
    ) {
        var out = new LinkedHashMap<String, TypeBackingShape>();
        for (var entry : schema.types().entrySet()) {
            out.put(entry.getKey(), projectType(entry.getValue(), catalog));
        }
        return Map.copyOf(out);
    }

    private static TypeBackingShape projectType(GraphitronType type, CompletionData catalog) {
        return switch (type) {
            case GraphitronType.JavaRecordType t -> projectRecord(t.fqClassName(), catalog);
            case GraphitronType.JavaRecordInputType t -> projectRecord(t.fqClassName(), catalog);
            case GraphitronType.PojoResultType.Backed t -> projectPojo(t.fqClassName(), catalog);
            case GraphitronType.PojoInputType t -> t.fqClassName() == null
                ? new TypeBackingShape.NoBacking.UnbackedResult()
                : projectPojo(t.fqClassName(), catalog);
            case GraphitronType.JooqRecordType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqRecordInputType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqTableRecordType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.JooqTableRecordInputType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.TableType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.NodeType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.TableInterfaceType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.TableInputType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.RootType ignored -> new TypeBackingShape.NoBacking.Root();
            case GraphitronType.InterfaceType ignored -> new TypeBackingShape.NoBacking.UnclassifiedInterface();
            case GraphitronType.UnionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ErrorType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EnumType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ScalarType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ConnectionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EdgeType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.PageInfoType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
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

    private static TypeBackingShape projectRecord(String fqClassName, CompletionData catalog) {
        var slots = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqClassName))
            .findFirst()
            .map(r -> r.recordComponents().stream()
                .map(rc -> new TypeBackingShape.MemberSlot(rc.name(), rc.displayType()))
                .toList())
            .orElse(List.of());
        return new TypeBackingShape.RecordBacking(fqClassName, slots);
    }

    private static TypeBackingShape projectPojo(String fqClassName, CompletionData catalog) {
        var ref = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqClassName))
            .findFirst();
        if (ref.isEmpty()) {
            return new TypeBackingShape.PojoBacking(fqClassName, List.of());
        }
        var accessors = new ArrayList<TypeBackingShape.MemberSlot>();
        for (var method : ref.get().methods()) {
            if (!method.parameters().isEmpty()) continue;
            var slot = beanAccessorSlot(method);
            if (slot != null) accessors.add(slot);
        }
        return new TypeBackingShape.PojoBacking(fqClassName, accessors);
    }

    private static TypeBackingShape.MemberSlot beanAccessorSlot(CompletionData.Method method) {
        String name = method.name();
        String field;
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            field = lowercaseFirst(name.substring(3));
        } else if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            field = lowercaseFirst(name.substring(2));
        } else {
            return null;
        }
        return new TypeBackingShape.MemberSlot(field, method.returnType());
    }

    private static String lowercaseFirst(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static List<InputValueShape> projectInputValues(List<InputValueDefinition> defs) {
        var shapes = new ArrayList<InputValueShape>();
        for (var def : defs) {
            shapes.add(new InputValueShape(
                def.getName(),
                projectType(def.getType()),
                descriptionOf(def.getDescription())
            ));
        }
        return shapes;
    }

    private static TypeShape projectType(Type<?> type) {
        return projectType(type, false);
    }

    private static TypeShape projectType(Type<?> type, boolean nonNull) {
        if (type instanceof NonNullType nn) {
            return projectType(nn.getType(), true);
        }
        if (type instanceof ListType lt) {
            return new TypeShape.List(projectType(lt.getType(), false), nonNull);
        }
        if (type instanceof TypeName tn) {
            return new TypeShape.Named(tn.getName(), nonNull);
        }
        throw new IllegalStateException("Unexpected graphql-java type node: " + type.getClass());
    }

    private static Optional<String> descriptionOf(Description description) {
        if (description == null) return Optional.empty();
        var content = description.getContent();
        return content == null || content.isEmpty() ? Optional.empty() : Optional.of(content);
    }

    public static CompletionData build(JooqCatalog jooq, GraphQLSchema assembled, RewriteContext ctx) {
        Path jooqSourceRoot = ctx.basedir().resolve("target/generated-sources/jooq");
        String jooqPkgPath = ctx.jooqPackage().replace('.', '/');
        // Single source walk feeds both halves: per-line table / column positions and
        // Javadoc on the jOOQ half, and class / method positions and Javadoc on the
        // service half. Empty when no source roots are on the build (unit tier,
        // validate-only), in which case every enriched slot stays at its file-level /
        // UNKNOWN fallback.
        SourceWalker.Index sources = SourceWalker.walk(ctx.compileSourceRoots());
        return new CompletionData(
            buildTables(jooq, jooqSourceRoot, jooqPkgPath, sources),
            buildScalars(assembled),
            buildExternalReferences(ctx, sources),
            ctx.namedReferences(),
            buildNodeMetadata(assembled)
        );
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
     */
    private static Map<String, CompletionData.NodeMetadata> buildNodeMetadata(GraphQLSchema assembled) {
        var out = new LinkedHashMap<String, CompletionData.NodeMetadata>();
        for (var type : assembled.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType obj)) continue;
            GraphQLAppliedDirective node = obj.getAppliedDirective("node");
            if (node == null) continue;
            out.put(obj.getName(), new CompletionData.NodeMetadata(
                readStringArg(node, "typeId"),
                readStringListArg(node, "keyColumns")
            ));
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
     * <p>Reads from {@link RewriteContext#classpathRoots()} — every reactor
     * project's compile-output directory, populated by the mojo from
     * {@code MavenSession.getAllProjects()}. Falls back to {@code
     * <basedir>/target/classes} as a single-root default when the context
     * carries no classpathRoots, so unit-tier callers built off
     * {@link RewriteContext}'s six-arg overload still get the same scope
     * pre-multi-module support shipped.
     */
    private static List<CompletionData.ExternalReference> buildExternalReferences(
        RewriteContext ctx, SourceWalker.Index sources
    ) {
        var roots = ctx.classpathRoots().isEmpty()
            ? List.of(ctx.basedir().resolve("target/classes"))
            : ctx.classpathRoots();
        var scanned = ClasspathScanner.scan(roots, ctx.jooqPackage());
        if (sources.isEmpty()) return scanned;
        return enrichExternalReferences(scanned, sources);
    }

    /**
     * Joins the bytecode-derived structure ({@link ClasspathScanner}: class /
     * method names, signatures, record components) with the source-walk index
     * (positions, Javadoc) in one pass. The method join key is
     * {@code (className, methodName, paramCount)}; an overload collision leaves
     * the key absent from the index, so the method keeps {@code UNKNOWN} rather
     * than binding a wrong line. Records are rebuilt rather than mutated.
     */
    private static List<CompletionData.ExternalReference> enrichExternalReferences(
        List<CompletionData.ExternalReference> scanned, SourceWalker.Index sources
    ) {
        var out = new ArrayList<CompletionData.ExternalReference>(scanned.size());
        for (var ref : scanned) {
            var classDecl = sources.classes().get(ref.className());
            var location = classDecl != null ? classDecl.location() : CompletionData.SourceLocation.UNKNOWN;
            var description = classDecl != null && !classDecl.javadoc().isEmpty()
                ? classDecl.javadoc() : ref.description();
            var methods = ref.methods().stream()
                .map(m -> enrichMethod(ref.className(), m, sources))
                .toList();
            out.add(new CompletionData.ExternalReference(
                ref.name(), ref.className(), description, methods, ref.recordComponents(), location));
        }
        return List.copyOf(out);
    }

    private static CompletionData.Method enrichMethod(
        String className, CompletionData.Method method, SourceWalker.Index sources
    ) {
        var key = new SourceWalker.MethodKey(className, method.name(), method.parameters().size());
        var decl = sources.methods().get(key);
        var location = decl != null ? decl.location() : CompletionData.SourceLocation.UNKNOWN;
        var description = decl != null && !decl.javadoc().isEmpty()
            ? decl.javadoc() : method.description();
        return new CompletionData.Method(
            method.name(), method.returnType(), description, method.parameters(), location);
    }

    private static List<CompletionData.Table> buildTables(
        JooqCatalog jooq, Path sourceRoot, String pkgPath, SourceWalker.Index sources
    ) {
        var tables = new ArrayList<CompletionData.Table>();
        for (String tableName : jooq.allTableSqlNames()) {
            tables.add(buildTable(jooq, tableName, sourceRoot, pkgPath, sources));
        }
        return List.copyOf(tables);
    }

    private static CompletionData.Table buildTable(
        JooqCatalog jooq, String tableName, Path sourceRoot, String pkgPath, SourceWalker.Index sources
    ) {
        Optional<JooqCatalog.TableEntry> entryOpt = jooq.findTable(tableName).asEntry();
        Table<?> jooqTable = entryOpt.map(JooqCatalog.TableEntry::table).orElse(null);

        // Fully-qualified name of the generated jOOQ table class, e.g.
        // <jooqPackage>.tables.Film; the source walk keys class / field
        // declarations by this FQN.
        String classFqn = jooqTable == null ? null : jooqTable.getClass().getName();

        var fileLocation = jooqTable == null
            ? CompletionData.SourceLocation.UNKNOWN
            : tableSourceLocation(sourceRoot, pkgPath, jooqTable);

        var classDecl = classFqn == null ? null : sources.classes().get(classFqn);
        // Per-line table position from the walk when present; the file-level
        // synthesis stays as the fallback for the pre-`mvn compile` state where
        // the generated sources do not exist yet.
        var tableDefinition = classDecl != null ? classDecl.location() : fileLocation;

        String sqlComment = commentOf(jooqTable);
        String tableDescription = !sqlComment.isEmpty()
            ? sqlComment
            : (classDecl != null ? classDecl.javadoc() : "");

        var columns = jooq.allColumnsOf(tableName).stream()
            .map(c -> buildColumn(c, classFqn, tableDefinition, sources))
            .toList();

        var references = jooqTable == null
            ? List.<CompletionData.Reference>of()
            : buildReferencesFor(jooq, jooqTable, sourceRoot, pkgPath);

        return new CompletionData.Table(
            tableName,
            tableDescription,
            tableDefinition,
            columns,
            references
        );
    }

    /**
     * Builds one column, refining its position to the per-line field
     * declaration when the source walk has it (keyed by the jOOQ Java field
     * name, matching {@link CompletionData.Column#name()}), and lifting the
     * field's Javadoc into {@code description}. Falls back to the owning
     * table's location when the field is not indexed (sources absent).
     */
    private static CompletionData.Column buildColumn(
        JooqCatalog.ColumnEntry c, String classFqn,
        CompletionData.SourceLocation tableDefinition, SourceWalker.Index sources
    ) {
        var fieldDecl = classFqn == null
            ? null
            : sources.fields().get(new SourceWalker.FieldKey(classFqn, c.javaName()));
        var location = fieldDecl != null ? fieldDecl.location() : tableDefinition;
        var description = fieldDecl != null ? fieldDecl.javadoc() : "";
        return new CompletionData.Column(
            c.javaName(),
            c.columnClass(),
            c.nullable(),
            description,
            location
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
        JooqCatalog jooq, Table<?> table, Path sourceRoot, String pkgPath
    ) {
        var keysLocation = keysSourceLocation(sourceRoot, pkgPath);
        var refs = new ArrayList<CompletionData.Reference>();
        for (ForeignKey<?, ?> fk : table.getReferences()) {
            String targetTable = fk.getKey().getTable().getName();
            refs.add(new CompletionData.Reference(targetTable, keyConstant(jooq, fk), false, keysLocation));
        }
        // Inbound: any FK on another table that points at this one.
        String thisName = table.getName();
        for (String otherName : jooq.allTableSqlNames()) {
            if (otherName.equalsIgnoreCase(thisName)) continue;
            Table<?> other = jooq.findTable(otherName).asEntry().map(JooqCatalog.TableEntry::table).orElse(null);
            if (other == null) continue;
            for (ForeignKey<?, ?> fk : other.getReferences()) {
                if (fk.getKey().getTable().getName().equalsIgnoreCase(thisName)) {
                    refs.add(new CompletionData.Reference(otherName, keyConstant(jooq, fk), true, keysLocation));
                }
            }
        }
        return List.copyOf(refs);
    }

    private static CompletionData.SourceLocation tableSourceLocation(
        Path sourceRoot, String pkgPath, Table<?> jooqTable
    ) {
        Path tableFile = sourceRoot
            .resolve(pkgPath)
            .resolve("tables")
            .resolve(jooqTable.getClass().getSimpleName() + ".java");
        return Files.exists(tableFile)
            ? new CompletionData.SourceLocation(tableFile.toUri().toString(), 0, 0)
            : CompletionData.SourceLocation.UNKNOWN;
    }

    private static CompletionData.SourceLocation keysSourceLocation(Path sourceRoot, String pkgPath) {
        Path keysFile = sourceRoot.resolve(pkgPath).resolve("Keys.java");
        return Files.exists(keysFile)
            ? new CompletionData.SourceLocation(keysFile.toUri().toString(), 0, 0)
            : CompletionData.SourceLocation.UNKNOWN;
    }

    private static String keyConstant(JooqCatalog jooq, ForeignKey<?, ?> fk) {
        return jooq.fkJavaConstantName(fk.getName()).orElse(fk.getName());
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
