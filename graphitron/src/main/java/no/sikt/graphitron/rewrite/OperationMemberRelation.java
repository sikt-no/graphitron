package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlWriteField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.LookupResolution;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.ParticipantFilterField;
import no.sikt.graphitron.rewrite.model.RootField;
import no.sikt.graphitron.rewrite.model.ServiceCallCarrier;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.TargetShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The minted operation member relation: the {@code coordinate -> operation} rows produced from
 * the trigger facts at the end of the classify walk, stored beside the other post-walk folds
 * and read by {@link GraphitronSchema#operationMembersOf}. The trigger-fact successor of the
 * leaf-derived projection ({@link OperationMembers#membersOf}), which survives beside it as the
 * membership-agreement pin's comparison side and as the leaf-local derivation behind
 * {@link OutputField#requiresReFetch()} / {@link OutputField#emitsKeyedReQuery()} until the
 * dissolution slices close the coexistence window.
 *
 * <p>The production is split into two halves with different disciplines:
 * <ul>
 *   <li>{@link #memberKindsOf} decides <b>membership</b> from facts alone: the gathered trigger
 *       slots ({@code @service}, {@code @mutation}, the routine chain, the pagination facts
 *       flowing through the carried window), the target / source shape facts, the type-registry
 *       verdicts, and capability reads ({@link SqlGeneratingField}, {@link LookupResolution},
 *       {@link ParticipantFilterField}). It contains no switch over leaf classes; a membership
 *       decision that needs a new fact adds the fact, never a leaf arm.</li>
 *   <li>{@link #payloadsFor} extracts <b>payloads</b> from the leaf-carried resolutions, the
 *       identity-forking half the additive window sanctions (the resolvers may read
 *       leaf-derived resolutions until each family's dissolution slice moves the resolution
 *       upstream). Its write-verb arms are the one genuinely per-record fork.</li>
 * </ul>
 *
 * <p>Every minted set passes {@link OperationMembers#validateAgainstDeclaredShape}, so the
 * co-occurrence fence gates this production path with the same statement as the projection.
 *
 * <p><b>Domain boundary, stated up front:</b> the relation's rows cover the flat classified
 * index, exactly the coordinates the view served before the keystone. A nesting type's fields
 * (absent from the flat index) deliberately mint no rows here: the record-handoff corpus
 * example proves the same nested coordinate can be reached both as an inline child of a
 * nesting embed and as a record-backed flat coordinate, and the source-dependent reentry
 * member then differs per reach, so a coordinate-keyed row would collapse two truths. Nested
 * leaf instances keep their leaf-local derivation ({@link OperationMembers#membersOf}) until
 * the mixed-reach question is taken up with the delivery fact.
 */
public record OperationMemberRelation(Map<FieldCoordinates, List<OperationMember>> byCoordinate) {

    public OperationMemberRelation {
        Objects.requireNonNull(byCoordinate, "byCoordinate");
    }

    /**
     * The not-computed sentinel for schemas built without the classify walk; readers fall back
     * to the leaf-derived projection on reference identity with this instance, so a walk-built
     * relation that is genuinely empty (an all-record schema) stays authoritative.
     */
    public static final OperationMemberRelation EMPTY = new OperationMemberRelation(Map.of());

    /** The coordinate's minted member rows, or the empty list when it triggers no operation. */
    public List<OperationMember> membersOf(FieldCoordinates coord) {
        return byCoordinate.getOrDefault(coord, List.of());
    }

    /**
     * Mints the relation over the flat classified index (see the domain boundary in the class
     * javadoc).
     *
     * @param preRewriteSchema the pre-rewrite assembled schema whose definition nodes key the
     *                         gathered trigger slots
     * @param fields           the classified field registry (flat index)
     * @param types            the type registry, for the table-bound and node verdicts
     * @param facts            the gathered trigger slots
     */
    static OperationMemberRelation compute(GraphQLSchema preRewriteSchema,
                                           Map<FieldCoordinates, GraphitronField> fields,
                                           Map<String, GraphitronType> types,
                                           GatheredFacts facts) {
        var rows = new LinkedHashMap<FieldCoordinates, List<OperationMember>>();
        for (var entry : fields.entrySet()) {
            if (entry.getValue() instanceof OutputField out) {
                rows.put(entry.getKey(), mint(preRewriteSchema, types, facts, out));
            }
        }
        return new OperationMemberRelation(Map.copyOf(rows));
    }

    private static List<OperationMember> mint(GraphQLSchema preRewriteSchema,
                                              Map<String, GraphitronType> types,
                                              GatheredFacts facts, OutputField leaf) {
        var fieldDef = fieldDefinitionOf(preRewriteSchema, leaf);
        var kinds = memberKindsOf(preRewriteSchema, types, facts, leaf, fieldDef);
        var members = payloadsFor(kinds, leaf);
        return OperationMembers.validateAgainstDeclaredShape(leaf, members);
    }

    /**
     * The pre-rewrite definition node the trigger slots are keyed by, or {@code null} for a
     * coordinate with no SDL definition (a synthesised type's field), whose membership then
     * rides the shape facts alone.
     */
    private static GraphQLFieldDefinition fieldDefinitionOf(GraphQLSchema schema, OutputField leaf) {
        if (!(schema.getType(leaf.parentTypeName()) instanceof GraphQLFieldsContainer container)) {
            return null;
        }
        return container.getFieldDefinition(leaf.name());
    }

    /**
     * The membership half: which member kinds this coordinate triggers, decided from facts (see
     * the class javadoc for the discipline). The rules mirror, per kind, the trigger sentences
     * of the operation model: a table-bound return mints select, the reference fact mints join,
     * the resolved filter surface mints condition rows, an authored or fallback ordering mints
     * orderBy, the carried pagination window mints paginate, the lookup mapping mints lookup,
     * {@code @service} mints serviceCall, {@code @mutation} (or the routine chain on the
     * mutation root) mints the write, the Node signature mints nodeResolve, {@code @pivot}
     * mints pivot, and the received-or-produced record on a bare table target mints the
     * reentry re-select.
     */
    private static Set<OperationMember.Kind> memberKindsOf(GraphQLSchema preRewriteSchema,
                                                           Map<String, GraphitronType> types,
                                                           GatheredFacts facts, OutputField leaf,
                                                           GraphQLFieldDefinition fieldDef) {
        var kinds = EnumSet.noneOf(OperationMember.Kind.class);
        boolean isRoot = leaf instanceof RootField;
        boolean onMutationRoot = isRoot && preRewriteSchema.getMutationType() != null
            && leaf.parentTypeName().equals(preRewriteSchema.getMutationType().getName());

        // The service and write triggers additionally read the leaf's carrier capability: on a
        // claim-conflicted coordinate (reported by the store-backed authored-claim detection)
        // the leaf classifies by arm order, so the losing directive's trigger fact still exists
        // while the leaf carries no payload for it, and its kind must not fire. On every
        // conflict-free schema the capability read is a tautology: the walk either followed the
        // trigger or tombstoned the coordinate out of this mint's domain.
        boolean serviceCapable = leaf instanceof ServiceField || leaf instanceof MethodBackedField;
        boolean serviceTrigger = fieldDef != null && facts.service().rowFor(fieldDef).isPresent()
            && serviceCapable;
        boolean writeCapable = leaf instanceof DmlWriteField
            || leaf instanceof MutationField.MutationRoutineWriteField;
        var writeRow = fieldDef == null || !writeCapable
            ? java.util.Optional.<no.sikt.graphitron.facts.WriteFacts.Row>empty()
            : facts.write().rowFor(fieldDef);
        boolean routineWrite = onMutationRoot && fieldDef != null && writeCapable
            && fieldDef.hasAppliedDirective(BuildContext.DIR_ROUTINE);
        boolean writeTrigger = writeRow.isPresent() || routineWrite;
        boolean nodeTrigger = isRoot && !onMutationRoot && fieldDef != null
            && "Node".equals(baseTypeName(fieldDef))
            && types.get("Node") instanceof GraphitronType.InterfaceType;
        boolean pivotTrigger = fieldDef != null && fieldDef.hasAppliedDirective(BuildContext.DIR_PIVOT);

        if (writeTrigger) {
            kinds.add(OperationMember.Kind.WRITE);
        }
        if (serviceTrigger) {
            kinds.add(OperationMember.Kind.SERVICE_CALL);
        }
        if (nodeTrigger) {
            kinds.add(OperationMember.Kind.NODE_RESOLVE);
        }
        if (pivotTrigger) {
            kinds.add(OperationMember.Kind.PIVOT);
            kinds.add(OperationMember.Kind.JOIN);
        }

        TargetShape shape = leaf.target().shape();
        TargetShape unwrapped = shape instanceof TargetShape.Connection c ? c.inner() : shape;
        boolean primaryClaimsProjection = writeTrigger || nodeTrigger || pivotTrigger
            // A serviceCall claims the projection slot too: the service produces the value and
            // any table surface on the coordinate is the re-select, not a select contribution.
            || serviceTrigger;
        if (!primaryClaimsProjection) {
            boolean tableBoundTarget = switch (unwrapped) {
                // A table-shaped target reads the catalog when its return type resolves to a
                // table-bound verdict. The nesting pass-through also carries a Table-shaped
                // target (the parent's table, not a projection of its own), but its return
                // type is a directiveless type whose verdict is never table-bound (NestingType,
                // or ResultType when a producer registered the carrier first), so the verdict
                // is the discriminating fact.
                case TargetShape.Table _ -> fieldDef == null
                    || switch (types.get(baseTypeName(fieldDef))) {
                        case GraphitronType.TableBackedType _, GraphitronType.InterfaceType _,
                             GraphitronType.ConnectionType _, GraphitronType.EdgeType _ -> true;
                        case null, default -> false;
                    };
                case TargetShape.Interface _, TargetShape.Union _ -> true;
                // A column target reads the catalog exactly when its source is a table row; a
                // record-sourced column read projects off the in-memory producer record.
                case TargetShape.Column _ -> !(leaf instanceof ChildField cf)
                    || cf.sourceShape() == SourceShape.Table;
                case TargetShape.Record _, TargetShape.Field _, TargetShape.Connection _ -> false;
            };
            if (tableBoundTarget) {
                kinds.add(OperationMember.Kind.SELECT);
            }
        }

        if (!referencePathOf(leaf).isEmpty()) {
            kinds.add(OperationMember.Kind.JOIN);
        }
        boolean hasConditionSurface =
            leaf instanceof SqlGeneratingField sgf && !sgf.filters().isEmpty()
            || leaf instanceof ParticipantFilterField pf
               && pf.participantFilters().stream().anyMatch(p -> !p.filters().isEmpty());
        if (hasConditionSurface) {
            kinds.add(OperationMember.Kind.CONDITION);
        }
        if (leaf instanceof SqlGeneratingField sgf && !(sgf.orderBy() instanceof OrderBySpec.None)) {
            kinds.add(OperationMember.Kind.ORDER_BY);
        }
        if (leaf instanceof SqlGeneratingField sgf && sgf.pagination() != null) {
            kinds.add(OperationMember.Kind.PAGINATE);
        }
        if (OperationMembers.lookupResolutionOf(leaf).isKeyed()) {
            kinds.add(OperationMember.Kind.LOOKUP);
        }

        boolean bareTableTarget = shape instanceof TargetShape.Table;
        boolean receivedRecord = leaf instanceof ChildField cf && cf.sourceShape() == SourceShape.Record;
        boolean producedRecord = serviceTrigger || writeRow.isPresent();
        boolean rootServicePassthrough = isRoot && serviceTrigger;
        if (bareTableTarget && (receivedRecord || producedRecord) && !rootServicePassthrough) {
            kinds.add(OperationMember.Kind.REENTRY);
        }
        return kinds;
    }

    /**
     * The reference fact, read off whichever component carries it. A fact accessor pending the
     * reference fact's walked home, not a membership dispatch: the arm set is the component
     * homes, and membership is only ever the path's non-emptiness.
     */
    private static List<?> referencePathOf(OutputField leaf) {
        return switch (leaf) {
            case ChildField.TableTargetField f -> f.joinPath();
            case ChildField.ColumnBackedReferenceField f -> f.joinPath();
            case ChildField.ComputedField f -> f.joinPath();
            case ChildField.ParticipantColumnReferenceField f -> List.of(f.hop());
            default -> List.of();
        };
    }

    /**
     * The payload half: realizes each triggered kind as its member record, reading the
     * leaf-carried resolutions (see the class javadoc). Emission order matches the projection's
     * (primary member, join, conditions, orderBy, paginate, lookup, reentry) so the two
     * productions are list-comparable, not merely set-comparable.
     */
    private static List<OperationMember> payloadsFor(Set<OperationMember.Kind> kinds, OutputField leaf) {
        var members = new ArrayList<OperationMember>();
        if (kinds.contains(OperationMember.Kind.WRITE)) {
            members.add(writePayloadOf(leaf));
        }
        if (kinds.contains(OperationMember.Kind.SERVICE_CALL)) {
            members.add(switch (leaf) {
                case ServiceField sf ->
                    new OperationMember.ServiceCall(new ServiceCallCarrier.StructuredCall(sf.serviceMethodCall()));
                case MethodBackedField mbf ->
                    new OperationMember.ServiceCall(new ServiceCallCarrier.ReflectedMethod(mbf.method()));
                default -> throw new IllegalStateException(
                    "serviceCall membership on a leaf with no call carrier capability: "
                    + leaf.getClass().getSimpleName());
            });
        }
        if (kinds.contains(OperationMember.Kind.NODE_RESOLVE)) {
            members.add(new OperationMember.NodeResolve());
        }
        if (kinds.contains(OperationMember.Kind.PIVOT)) {
            members.add(switch (leaf) {
                // The pivot payload is the leaf's carried component, by identity (PivotSpecField).
                case ChildField.PivotSpecField f -> f.pivot();
                default -> throw new IllegalStateException(
                    "pivot membership on a leaf with no pivot payload component: "
                    + leaf.getClass().getSimpleName());
            });
        }
        if (kinds.contains(OperationMember.Kind.SELECT)) {
            members.add(new OperationMember.Select());
        }
        if (kinds.contains(OperationMember.Kind.JOIN)) {
            members.add(new OperationMember.Join());
        }
        if (kinds.contains(OperationMember.Kind.CONDITION)) {
            if (leaf instanceof ParticipantFilterField pf) {
                for (var participant : pf.participantFilters()) {
                    if (!participant.filters().isEmpty()) {
                        members.add(new OperationMember.Condition.OnParticipant(
                            participant.participant(), participant.filters()));
                    }
                }
            } else {
                var sgf = (SqlGeneratingField) leaf;
                members.add(new OperationMember.Condition.OnReturnTable(
                    sgf.returnType().table(), sgf.filters()));
            }
        }
        if (kinds.contains(OperationMember.Kind.ORDER_BY)) {
            members.add(new OperationMember.OrderBy(((SqlGeneratingField) leaf).orderBy()));
        }
        if (kinds.contains(OperationMember.Kind.PAGINATE)) {
            members.add(new OperationMember.Paginate(((SqlGeneratingField) leaf).pagination()));
        }
        if (kinds.contains(OperationMember.Kind.LOOKUP)) {
            members.add(new OperationMember.Lookup(switch (OperationMembers.lookupResolutionOf(leaf)) {
                case LookupResolution.Keyed keyed -> keyed.mapping();
                case LookupResolution.None _ -> throw new IllegalStateException(
                    "Graphitron relation bug: the LOOKUP kind fired for '" + leaf.qualifiedName()
                    + "' but the leaf resolved no lookup; the kind and payload read one axis");
            }));
        }
        if (kinds.contains(OperationMember.Kind.REENTRY)) {
            members.add(new OperationMember.Reentry());
        }
        return members;
    }

    /**
     * The write verbs' payload extraction: the DML leaves carry the payload as their
     * component (the {@link DmlWriteField} capability), the routine chain carries none.
     */
    private static OperationMember.Write writePayloadOf(OutputField leaf) {
        return switch (leaf) {
            // The write payload is the leaf's carried component, by identity (DmlWriteField).
            case DmlWriteField f -> f.write();
            case MutationField.MutationRoutineWriteField _ -> new OperationMember.Write.RoutineWrite();
            default -> throw new IllegalStateException(
                "write membership on a leaf with no write payload components: "
                + leaf.getClass().getSimpleName());
        };
    }

    private static String baseTypeName(GraphQLFieldDefinition fieldDef) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
    }
}
