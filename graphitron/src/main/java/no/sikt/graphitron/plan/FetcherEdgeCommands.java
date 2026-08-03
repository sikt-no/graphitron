package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.FetcherEdgeCommand;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Produces the fetcher edge relation ({@link FetcherEdgeRelation}): one row per coordinate of
 * the covered families, each row naming the generated units the coordinate's emitted fetcher
 * methods reference. Membership has one home, the total membership-and-production switches
 * below ({@link LauncherCommands}' shape): each leaf either mints the coordinate's row or is
 * outside the relation by the fact ({@code null}), with no default arm, so a new leaf is a
 * compile-time decision here rather than a silent non-member.
 *
 * <p>The covered families, with each family's target derivation:
 *
 * <ul>
 *   <li><b>Polymorphic children</b> ({@link ChildField.InterfaceField},
 *       {@link ChildField.UnionField}, {@link ChildField.BatchedInterfaceField},
 *       {@link ChildField.BatchedUnionField}): the per-typename SELECT helpers project each
 *       table-bound participant's projection class, so the targets are the
 *       {@link ParticipantRef.TableBound} participants' type classes.</li>
 *   <li><b>Polymorphic roots</b> ({@link QueryField.QueryInterfaceField},
 *       {@link QueryField.QueryUnionField}): the same participant type classes (the stage-2
 *       selects live on the root's fetchers class too), plus the participant-filter glue
 *       classes, derived from the condition relation's rows for the coordinate, never by
 *       re-evaluating filter predicates.</li>
 *   <li><b>Discriminated children and service polymorphics</b>
 *       ({@link ChildField.TableInterfaceField},
 *       {@link QueryField.QueryServicePolymorphicField},
 *       {@link QueryField.QueryServiceTableInterfaceField},
 *       {@link MutationField.MutationServicePolymorphicField},
 *       {@link MutationField.MutationServiceTableInterfaceField}): their by-PK re-projections
 *       reference each table-bound participant's projection class. Joined-table participants
 *       contribute detail columns, not a projection call, so only the
 *       {@link ParticipantRef.TableBound} arm mints a target.</li>
 *   <li><b>Node lookups</b> ({@link QueryField.QueryNodeField},
 *       {@link QueryField.QueryNodesField}): the thin entry delegates to the node dispatch
 *       unit, so the one target is the {@code QueryNodeFetcher} global.</li>
 *   <li><b>Routine writes</b> ({@link MutationField.MutationRoutineWriteField}): the
 *       post-commit SELECT projects the terminus type inline, so the target is the return
 *       type's projection class.</li>
 *   <li><b>DML and payload mutations</b> ({@link MutationField.DmlTableField} and the four
 *       payload arms): targets are derived from the condition relation's rows for the
 *       coordinate. No mutation leaf is SQL-generating, so no mutation coordinate has a
 *       condition row today and these families are empty by derivation; the arms keep the
 *       derivation rather than hard-coding the absence, so a condition row appearing at a
 *       mutation coordinate would surface as an edge, not a gap.</li>
 * </ul>
 *
 * <p>Deliberate non-members, by the emitted shape: the {@code @service} table passthroughs
 * ({@link QueryField.QueryServiceTableField}, {@link MutationField.MutationServiceTableField})
 * hand the developer's records straight through with no re-projection, so they reference no
 * generated unit; {@link ChildField.ErrorsField} and the {@code @error} type fetchers read the
 * routed {@code GraphQLError} payload and reference nothing generated; the read-side
 * {@code NodeIdEncoder} uses stay leaf-derived in the recompile-graph edge view (boundaries
 * decode and encode, so encode-ness is a boundary fact, not a row fact).
 */
public final class FetcherEdgeCommands {

    private FetcherEdgeCommands() {}

    public static FetcherEdgeRelation produce(GraphitronSchema schema, ConditionRelation conditions,
            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<FetcherEdgeCommand>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                collect(field, conditions, units, rows);
            }
        }
        return new FetcherEdgeRelation(rows);
    }

    private static void collect(GraphitronField field, ConditionRelation conditions,
            GeneratedUnits units, List<FetcherEdgeCommand> rows) {
        var row = switch (field) {
            case QueryField qf -> rowOf(qf, conditions, units);
            case MutationField mf -> rowOf(mf, conditions, units);
            case ChildField cf -> {
                if (cf instanceof ChildField.NestingField nf) {
                    // Nested coordinates have no fieldsOf entry; the walk reaches them through
                    // the nesting field's own children (the condition producer's precedent).
                    for (var nested : nf.nestedFields()) {
                        collect(nested, conditions, units, rows);
                    }
                }
                yield childRowOf(cf, units);
            }
            case InputField ignored -> null;
            case GraphitronField.UnclassifiedField ignored -> null;
        };
        if (row != null) {
            rows.add(row);
        }
    }

    /**
     * The root query family's membership-and-production switch, total with no default. The
     * migrated root SELECT kinds are launcher rows (their references ride the launcher
     * relation's source, WHERE and result slots); the service passthroughs reference nothing
     * generated (graphql-java traverses the returned records).
     */
    private static FetcherEdgeCommand rowOf(QueryField field, ConditionRelation conditions,
            GeneratedUnits units) {
        return switch (field) {
            case QueryField.QueryTableField ignored -> null;
            case QueryField.QueryRoutineTableField ignored -> null;
            case QueryField.QueryTableInterfaceField ignored -> null;
            case QueryField.QueryInterfaceField f ->
                row(f.parentTypeName(), f.name(), units, targets -> {
                    addParticipantTypeClasses(targets, f.participants(), units);
                    addConditionGlueTargets(targets, f.parentTypeName(), f.name(), conditions);
                });
            case QueryField.QueryUnionField f ->
                row(f.parentTypeName(), f.name(), units, targets -> {
                    addParticipantTypeClasses(targets, f.participants(), units);
                    addConditionGlueTargets(targets, f.parentTypeName(), f.name(), conditions);
                });
            case QueryField.QueryNodeField f -> row(f.parentTypeName(), f.name(), units,
                targets -> targets.add(units.queryNodeFetcher()));
            case QueryField.QueryNodesField f -> row(f.parentTypeName(), f.name(), units,
                targets -> targets.add(units.queryNodeFetcher()));
            case QueryField.QueryServiceTableField ignored -> null;
            case QueryField.QueryServiceRecordField ignored -> null;
            case QueryField.QueryServicePolymorphicField f ->
                row(f.parentTypeName(), f.name(), units,
                    targets -> addParticipantTypeClasses(targets, f.participants(), units));
            case QueryField.QueryServiceTableInterfaceField f ->
                row(f.parentTypeName(), f.name(), units,
                    targets -> addParticipantTypeClasses(targets, f.participants(), units));
        };
    }

    /**
     * The mutation family's membership-and-production switch, total with no default. The DML
     * writes' re-select projections ride their reentry launcher rows; the encode/decode
     * plumbing is the edge view's leaf-derived {@code NodeIdEncoder} concern; what remains here
     * is the routine write's inline terminus projection, the service polymorphics' participant
     * projections, and the condition-relation derivation for the write-side WHERE (empty today,
     * see the class javadoc).
     */
    private static FetcherEdgeCommand rowOf(MutationField field, ConditionRelation conditions,
            GeneratedUnits units) {
        return switch (field) {
            case MutationField.DmlTableField f ->
                glueOnlyRow(f.parentTypeName(), f.name(), conditions, units);
            case MutationField.MutationRoutineWriteField f -> row(f.parentTypeName(), f.name(), units,
                targets -> targets.add(units.typeClass(f.returnType().returnTypeName())));
            case MutationField.MutationServiceTableField ignored -> null;
            case MutationField.MutationServiceRecordField ignored -> null;
            case MutationField.MutationServicePolymorphicField f ->
                row(f.parentTypeName(), f.name(), units,
                    targets -> addParticipantTypeClasses(targets, f.participants(), units));
            case MutationField.MutationServiceTableInterfaceField f ->
                row(f.parentTypeName(), f.name(), units,
                    targets -> addParticipantTypeClasses(targets, f.participants(), units));
            case MutationField.MutationDmlRecordField ignored -> null;
            case MutationField.MutationBulkDmlRecordField ignored -> null;
            case MutationField.MutationUpdatePayloadField f ->
                glueOnlyRow(f.parentTypeName(), f.name(), conditions, units);
            case MutationField.MutationBulkUpdatePayloadField f ->
                glueOnlyRow(f.parentTypeName(), f.name(), conditions, units);
            case MutationField.MutationDeletePayloadField f ->
                glueOnlyRow(f.parentTypeName(), f.name(), conditions, units);
            case MutationField.MutationBulkDeletePayloadField f ->
                glueOnlyRow(f.parentTypeName(), f.name(), conditions, units);
        };
    }

    /**
     * The child family's membership-and-production switch, total with no default: the
     * polymorphic and discriminated deliveries mint their participant-projection rows;
     * everything else is outside by the fact (launcher rows for the batched, service and pivot
     * deliveries; the projection relation for the inline SQL children; parent-row or routed
     * reads for the rest).
     */
    private static FetcherEdgeCommand childRowOf(ChildField field, GeneratedUnits units) {
        return switch (field) {
            case ChildField.InterfaceField f -> row(f.parentTypeName(), f.name(), units,
                targets -> addParticipantTypeClasses(targets, f.participants(), units));
            case ChildField.UnionField f -> row(f.parentTypeName(), f.name(), units,
                targets -> addParticipantTypeClasses(targets, f.participants(), units));
            case ChildField.BatchedInterfaceField f -> row(f.parentTypeName(), f.name(), units,
                targets -> addParticipantTypeClasses(targets, f.participants(), units));
            case ChildField.BatchedUnionField f -> row(f.parentTypeName(), f.name(), units,
                targets -> addParticipantTypeClasses(targets, f.participants(), units));
            case ChildField.TableInterfaceField f -> row(f.parentTypeName(), f.name(), units,
                targets -> addParticipantTypeClasses(targets, f.participants(), units));
            // Launcher rows carry these coordinates' references (source projection, WHERE glue,
            // connection runtime refs).
            case ChildField.BatchedTableField ignored -> null;
            case ChildField.BatchedPivotField ignored -> null;
            case ChildField.ServiceTableField ignored -> null;
            case ChildField.ServiceRecordField ignored -> null;
            // Inline SQL children compose inside their hosting projection unit; the projection
            // relation carries the callee and glue references.
            case ChildField.TableField ignored -> null;
            case ChildField.NestingField ignored -> null;
            case ChildField.PivotField ignored -> null;
            case ChildField.PivotSlotField ignored -> null;
            // Reads off the arrived source (or the routed error payload): no generated-unit
            // reference beyond the blanket scaffolding. The NodeId-encoding leaves' encoder
            // reference is the edge view's leaf-derived concern.
            case ChildField.ColumnBackedField ignored -> null;
            case ChildField.ColumnBackedReferenceField ignored -> null;
            case ChildField.ParticipantColumnReferenceField ignored -> null;
            case ChildField.ComputedField ignored -> null;
            case ChildField.RecordCompositeField ignored -> null;
            case ChildField.RecordReadField ignored -> null;
            case ChildField.SingleRecordIdField ignored -> null;
            case ChildField.SingleRecordIdFieldFromReturning ignored -> null;
            case ChildField.ErrorsField ignored -> null;
        };
    }

    private static FetcherEdgeCommand row(String parentTypeName, String fieldName,
            GeneratedUnits units, java.util.function.Consumer<LinkedHashSet<UnitRef>> fill) {
        var targets = new LinkedHashSet<UnitRef>();
        fill.accept(targets);
        if (targets.isEmpty()) {
            return null;
        }
        return new FetcherEdgeCommand(
            FieldCoordinates.coordinates(parentTypeName, fieldName),
            units.fetchers(parentTypeName),
            List.copyOf(targets));
    }

    /**
     * A row whose only targets are the coordinate's condition glue classes, present exactly
     * when the condition relation carries rows for the coordinate. Derivation, not
     * re-evaluation: the fact "this coordinate's fetcher calls generated glue" is read off the
     * condition relation, the one producer of glue.
     */
    private static FetcherEdgeCommand glueOnlyRow(String parentTypeName, String fieldName,
            ConditionRelation conditions, GeneratedUnits units) {
        return row(parentTypeName, fieldName, units,
            targets -> addConditionGlueTargets(targets, parentTypeName, fieldName, conditions));
    }

    /**
     * One target per table-bound participant's projection class, in declaration order. The
     * joined-table and unbound arms contribute no target: a joined-table participant's
     * contribution is detail columns behind a gated LEFT JOIN, and an unbound participant has
     * no SQL branch at all.
     */
    private static void addParticipantTypeClasses(LinkedHashSet<UnitRef> targets,
            List<ParticipantRef> participants, GeneratedUnits units) {
        for (var participant : participants) {
            if (participant instanceof ParticipantRef.TableBound tb) {
                targets.add(units.typeClass(tb.typeName()));
            }
        }
    }

    /** The distinct glue classes the condition relation's rows for the coordinate land on. */
    private static void addConditionGlueTargets(LinkedHashSet<UnitRef> targets,
            String parentTypeName, String fieldName, ConditionRelation conditions) {
        var coordinate = FieldCoordinates.coordinates(parentTypeName, fieldName);
        for (var row : conditions.rows()) {
            if (row.coordinate().equals(coordinate)) {
                targets.add(row.glue().owner());
            }
        }
    }
}
