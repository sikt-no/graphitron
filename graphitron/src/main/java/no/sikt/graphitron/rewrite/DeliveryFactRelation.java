package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DeliveryFact;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The materialized delivery-fact relation: one {@link DeliveryFact} verdict per flat classified
 * coordinate, produced post-walk from the gathered delivery markers and the shape facts, and
 * read through {@link GraphitronSchema#deliveryOf}. Anchor-hood is this fact's first view: the
 * launcher membership predicate joins the verdict with the coordinate's member rows.
 *
 * <p>The production mirrors the trigger disjunction the model binds
 * ({@link DeliveryFact.Trigger}), in classifier-agreement order: a {@code @service} trigger
 * claims the delivery (the call is the delivery, owned by the serviceCall member, so the
 * coordinate reads {@link DeliveryFact.Inline}); a list-valued polymorphic target with a
 * table-bound participant splits by fan-in; a record-handing parent splits a table-bound
 * child; the authored markers split what they are read on (the marker union on a plain
 * table-backed child, the {@code @splitQuery} half on the {@code @pivot} and {@code @routine}
 * chains, mirroring the classifier's per-arm reads of the gathered marker relation); the
 * complement is {@link DeliveryFact.Inline}.
 *
 * <p>During the additive window this production and the classifier's batched-leaf arms read
 * the same marker, source-shape and verdict facts on both sides, pinned for regression rather
 * than independence by the delivery pin ({@code DeliveryFactPinTest}: computed equals
 * {@link DeliveryFact#leafDerivedOf} over the corpus), the same shared-inputs discipline the
 * keystone's record states for the select, join and reentry productions. The leaf crosswalk is
 * the comparison side and the walk-less-schema fallback, never a production source.
 *
 * <p><b>Domain boundary:</b> the flat classified index, exactly the member relation's domain;
 * a nesting type's fields keep the leaf-derived crosswalk, per the mixed-reach reasoning on
 * {@link OperationMemberRelation}.
 */
public record DeliveryFactRelation(Map<FieldCoordinates, DeliveryFact> byCoordinate) {

    public DeliveryFactRelation {
        Objects.requireNonNull(byCoordinate, "byCoordinate");
    }

    /**
     * The not-computed sentinel for schemas built without the classify walk; readers fall back
     * to the leaf-derived crosswalk on reference identity with this instance.
     */
    public static final DeliveryFactRelation EMPTY = new DeliveryFactRelation(Map.of());

    /** The coordinate's delivery verdict, or {@link DeliveryFact.Inline} when absent. */
    public DeliveryFact deliveryOf(FieldCoordinates coord) {
        return byCoordinate.getOrDefault(coord, DeliveryFact.Inline.INSTANCE);
    }

    /** Mints the relation over the flat classified index (see the domain boundary above). */
    static DeliveryFactRelation compute(GraphQLSchema preRewriteSchema,
                                        Map<FieldCoordinates, GraphitronField> fields,
                                        Map<String, GraphitronType> types,
                                        GatheredFacts facts) {
        var rows = new LinkedHashMap<FieldCoordinates, DeliveryFact>();
        for (var entry : fields.entrySet()) {
            if (entry.getValue() instanceof OutputField out) {
                rows.put(entry.getKey(), mint(preRewriteSchema, types, facts, out));
            }
        }
        return new DeliveryFactRelation(Map.copyOf(rows));
    }

    private static DeliveryFact mint(GraphQLSchema preRewriteSchema,
                                     Map<String, GraphitronType> types,
                                     GatheredFacts facts, OutputField leaf) {
        // Roots are the entry points: nothing arrives, so nothing splits.
        if (!(leaf instanceof ChildField child)) {
            return DeliveryFact.Inline.INSTANCE;
        }
        var fieldDef = fieldDefinitionOf(preRewriteSchema, leaf);
        // The service call is the delivery, owned by the serviceCall member (the same
        // slot-claiming rule the member production applies to the projection slot).
        if (fieldDef != null && facts.service().rowFor(fieldDef).isPresent()) {
            return DeliveryFact.Inline.INSTANCE;
        }
        TargetShape shape = leaf.target().shape();
        TargetShape unwrapped = shape instanceof TargetShape.Connection c ? c.inner() : shape;
        boolean listOrConnection = shape instanceof TargetShape.Connection
            || leaf.target() instanceof Target.List;
        // The polymorphic fan-in: the mint rule at the classifier's polymorphic arms, read
        // from the type verdict's participant set. Precedes the record-handed rule because a
        // record parent's polymorphic child batches by the same fan-in, not by the handoff.
        if (unwrapped instanceof TargetShape.Interface || unwrapped instanceof TargetShape.Union) {
            return listOrConnection && anyTableBoundParticipant(types, fieldDef)
                ? new DeliveryFact.Batched(DeliveryFact.Trigger.PolymorphicFanIn.INSTANCE)
                : DeliveryFact.Inline.INSTANCE;
        }
        // The discriminated interface child, the same cardinality rule with the participant
        // conjunct holding by construction (the parse boundary rejects a non-table implementor).
        // It carries a Table-shaped target rather than an Interface one, so it needs its own arm
        // rather than the fan-in's; it precedes the marker reads so the redundant @splitQuery an
        // author may write on it cannot claim the trigger at either cardinality.
        if (discriminatedInterfaceTarget(types, fieldDef)) {
            return listOrConnection
                ? new DeliveryFact.Batched(DeliveryFact.Trigger.PolymorphicFanIn.INSTANCE)
                : DeliveryFact.Inline.INSTANCE;
        }
        boolean tableAnchoredChild = unwrapped instanceof TargetShape.Table
            && singleTableBackedVerdict(types, fieldDef);
        if (child.sourceShape() == SourceShape.Record && tableAnchoredChild) {
            return new DeliveryFact.Batched(DeliveryFact.Trigger.RecordHandedParent.INSTANCE);
        }
        if (fieldDef != null) {
            var markers = facts.delivery().rowFor(fieldDef).orElse(null);
            if (markers != null) {
                boolean pivot = fieldDef.hasAppliedDirective(BuildContext.DIR_PIVOT);
                boolean routine = fieldDef.hasAppliedDirective(BuildContext.DIR_ROUTINE);
                // The @pivot and @routine chains batch on the @splitQuery half alone; the
                // plain table-backed child batches on the marker union. Mirrors the
                // classifier's per-arm reads of the gathered marker relation.
                if (markers.splitQuery() && (tableAnchoredChild || pivot)) {
                    return new DeliveryFact.Batched(DeliveryFact.Trigger.Authored.INSTANCE);
                }
                if (markers.tenantFanOut() && tableAnchoredChild && !routine) {
                    return new DeliveryFact.Batched(DeliveryFact.Trigger.Authored.INSTANCE);
                }
            }
        }
        return DeliveryFact.Inline.INSTANCE;
    }

    /**
     * Whether the polymorphic element's verdict carries a directly table-bound participant:
     * the batched fan-in's second conjunct, read where the classifier's mint rule reads it.
     */
    private static boolean anyTableBoundParticipant(Map<String, GraphitronType> types,
                                                    GraphQLFieldDefinition fieldDef) {
        if (fieldDef == null) {
            return false;
        }
        List<ParticipantRef> participants = switch (types.get(baseTypeName(fieldDef))) {
            case GraphitronType.InterfaceType it -> it.participants();
            case GraphitronType.UnionType ut -> ut.participants();
            case null, default -> List.of();
        };
        return participants.stream().anyMatch(p -> p instanceof ParticipantRef.TableBound);
    }

    /**
     * Whether the return element's verdict is a single-table discriminated interface: the
     * discriminated child's own delivery rule reads this instead of the fan-in's participant
     * scan, the participant conjunct being structural on this shape. A connection verdict
     * resolves through its element, the same unwrapping every other verdict read here does.
     */
    private static boolean discriminatedInterfaceTarget(Map<String, GraphitronType> types,
                                                        GraphQLFieldDefinition fieldDef) {
        if (fieldDef == null) {
            return false;
        }
        return switch (types.get(baseTypeName(fieldDef))) {
            case GraphitronType.TableInterfaceType _ -> true;
            case GraphitronType.ConnectionType conn ->
                types.get(conn.elementTypeName()) instanceof GraphitronType.TableInterfaceType;
            case null, default -> false;
        };
    }

    /**
     * Whether the return element's verdict anchors on one catalog table: the discriminating
     * fact between a batched-capable table child and the shapes that share a Table-shaped
     * target without one (the nesting pass-through's directiveless verdict; the single-table
     * interface child, whose delivery the arm above already answered on cardinality, so this
     * case's {@code false} keeps the marker arms below off that shape). A connection verdict
     * anchors through its element, so authored connection returns stay batched-capable.
     */
    private static boolean singleTableBackedVerdict(Map<String, GraphitronType> types,
                                                    GraphQLFieldDefinition fieldDef) {
        if (fieldDef == null) {
            return false;
        }
        return switch (types.get(baseTypeName(fieldDef))) {
            case GraphitronType.TableInterfaceType _ -> false;
            case GraphitronType.TableBackedType _ -> true;
            case GraphitronType.ConnectionType conn ->
                types.get(conn.elementTypeName()) instanceof GraphitronType.TableBackedType t
                    && !(t instanceof GraphitronType.TableInterfaceType);
            case null, default -> false;
        };
    }

    private static GraphQLFieldDefinition fieldDefinitionOf(GraphQLSchema schema, OutputField leaf) {
        if (!(schema.getType(leaf.parentTypeName()) instanceof GraphQLFieldsContainer container)) {
            return null;
        }
        return container.getFieldDefinition(leaf.name());
    }

    private static String baseTypeName(GraphQLFieldDefinition fieldDef) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
    }
}
