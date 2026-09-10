package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import no.sikt.graphitron.rewrite.model.GraphitronField;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The post-walk sweep that gives every decoding {@code @nodeId} coordinate the walk did not get to
 * a {@link NodeIdDecodeDisposition.NotReached} row.
 *
 * <p>It is what makes the ledger's obligation a <em>total</em> one, and totality is what lets an
 * absent row mean exactly one thing. Without it the residual would mean three: an instruction the
 * generator dropped, one under a field whose classification aborted for an unrelated reason, and
 * one on a type the walk never entered. Only the first is a defect, and a rule that cannot tell them
 * apart grows a second, wrong-cause message on every already-failing build.
 *
 * <p><b>The population is the walk's own structural fact, never another family's messages.</b> Two
 * facts stand for "did not reach": the field registry holds no entry for the coordinate's owning
 * field (the walk never entered its type, a directiveless nesting target and a structural connection
 * arm being the commonest reasons), or it holds a
 * {@link GraphitronField.UnclassifiedField} (the field's own classification aborted above the
 * argument surface). Reading the population off whether some family currently emits a
 * {@code ValidationError} at the coordinate would instead make it change whenever an unrelated
 * family re-words or re-grains its errors, with nothing failing to say so.
 *
 * <p>A field that classified is deliberately <em>not</em> swept. Its coordinates are the mint
 * sites' to dispose of, and one they left undisposed is the residual this whole mechanism exists to
 * surface; blanketing it here would empty the residual and make the rule vacuous.
 */
final class NodeIdDecodeNotReached {

    private NodeIdDecodeNotReached() {}

    /**
     * Sweeps {@code schema} for the coordinates under fields the walk did not classify, minting a
     * {@code NotReached} row for each. Runs after the walk, and writes only where the ledger has no
     * row: a mint site's own answer always wins.
     */
    static void sweep(GraphQLSchema schema, FieldRegistry registry, NodeIdDecodeLedger ledger) {
        if (schema == null) {
            return;
        }
        var entries = registry.entries();
        for (var type : schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType objectType)) {
                continue;
            }
            for (var fieldDef : objectType.getFieldDefinitions()) {
                var classified = entries.get(
                    FieldCoordinates.coordinates(objectType.getName(), fieldDef.getName()));
                if (classified != null && !(classified instanceof GraphitronField.UnclassifiedField)) {
                    continue;
                }
                for (var at : coordinatesUnder(objectType.getName(), fieldDef)) {
                    ledger.recordNotReached(at);
                }
            }
        }
    }

    /**
     * Every decoding {@code @nodeId} coordinate on {@code fieldDef}'s argument surface: the
     * arguments that carry the directive, and the input fields reachable by descending through
     * input-object-typed fields under them. The descent's first-visit guard is the classification
     * walk's own ({@code ClassifyContext.expandingTypes}), which is also the rule
     * {@code intent_input_occurrence_path} stops its expansion by, so the two enumerate one
     * population.
     */
    private static List<NodeIdDecodeCoordinate> coordinatesUnder(
            String parentTypeName, GraphQLFieldDefinition fieldDef) {
        var out = new ArrayList<NodeIdDecodeCoordinate>();
        for (GraphQLArgument arg : fieldDef.getArguments()) {
            var useSite = ClassifyContext.UseSite.of(parentTypeName, fieldDef.getName(), arg.getName());
            if (instructed(arg.getType(), arg.getName(),
                    arg.hasAppliedDirective(BuildContext.DIR_NODE_ID))) {
                out.add(new NodeIdDecodeCoordinate.Argument(
                    parentTypeName, fieldDef.getName(), arg.getName()));
            }
            if (GraphQLTypeUtil.unwrapAll(arg.getType()) instanceof GraphQLInputObjectType iot) {
                descend(iot, useSite, new LinkedHashSet<>(), out);
            }
        }
        return out;
    }

    private static void descend(GraphQLInputObjectType iot, ClassifyContext.UseSite useSite,
                                Set<String> expanding, List<NodeIdDecodeCoordinate> out) {
        if (!expanding.add(iot.getName())) {
            return;
        }
        try {
            for (var f : iot.getFieldDefinitions()) {
                if (instructed(f.getType(), f.getName(),
                        f.hasAppliedDirective(BuildContext.DIR_NODE_ID))) {
                    out.add(useSite.at(iot.getName(), f.getName()));
                }
                if (GraphQLTypeUtil.unwrapAll(f.getType()) instanceof GraphQLInputObjectType nested) {
                    descend(nested, useSite.descending(iot.getName(), f.getName()), expanding, out);
                }
            }
        } finally {
            expanding.remove(iot.getName());
        }
    }

    /**
     * Whether the census could hold an instruction at this slot: an {@code ID}-typed slot that
     * either carries {@code @nodeId} or is named {@code id}, which is the directive-less basis the
     * relation admits at both decoding sites.
     *
     * <p>Deliberately looser than the census, which additionally demands that the directive-less
     * form's target resolve to exactly one node type. Looser is the safe direction for a sweep over
     * fields the walk never classified: a coordinate covered here that the census does not hold
     * costs nothing, while one the census holds and this misses would be reported as a dropped
     * instruction under a field that already has its own error.
     */
    private static boolean instructed(graphql.schema.GraphQLType type, String slotName,
                                      boolean hasDirective) {
        return (hasDirective || BuildContext.NODE_INTERFACE_ID_FIELD.equals(slotName))
            && GraphQLTypeUtil.unwrapAll(type) instanceof GraphQLNamedType named
            && "ID".equals(named.getName());
    }
}
