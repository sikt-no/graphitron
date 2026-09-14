package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
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
 * <p><b>Every fields container, not every object type, and an interface coordinate covered only
 * where the implementations carry it.</b> The walk classifies the fields of object types alone, so
 * a field an interface declares is one it never stands on: that field is lowered at each
 * implementing object type, and the mint sites dispose of it there. The argument census has no such
 * scope, its {@code ARGUMENT} arm reading the captured argument entries with no kind join at all,
 * so an interface-declared coordinate left out of this sweep is a census member with no row
 * anywhere and the residual reports a shape the generator carries out one declaration below.
 *
 * <p>What makes that coordinate covered is not its interface-ness but the instruction standing at
 * the coordinates the generator does lower, which is the condition
 * {@link #carriedByEveryImplementation} states. SDL forces every implementation to redeclare the
 * field and its arguments and forces none of them to repeat a directive, so an instruction written
 * on an interface argument alone reaches no lowering at all: the implementations' own arguments
 * classify as whatever they say, a plain column filter included, and the encoded id is compared
 * against the key column exactly as the unguarded rail always did. Covering every interface
 * coordinate unconditionally would silence that, which is this rule's own failure class arriving
 * through the sweep. The input-field site needs no exception: the instruction there is written on
 * the input type, so each implementation's use site is one too, and the condition holds by
 * construction.
 *
 * <p>The census-side alternative, scoping the census to object-declared use sites, states an
 * exclusion one relation out that cannot see the difference between those two cases, and would keep
 * stating it the day the walk does stand on an interface-declared field. Minting here instead, the
 * sweep stops of its own accord as soon as the registry holds that field's entry.
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
            if (!(type instanceof GraphQLFieldsContainer container)) {
                continue;
            }
            for (var fieldDef : container.getFieldDefinitions()) {
                var classified = entries.get(
                    FieldCoordinates.coordinates(container.getName(), fieldDef.getName()));
                if (classified != null && !(classified instanceof GraphitronField.UnclassifiedField)) {
                    continue;
                }
                for (var at : coordinatesUnder(container.getName(), fieldDef)) {
                    if (container instanceof GraphQLInterfaceType iface
                            && !carriedByEveryImplementation(schema, iface, fieldDef, at)) {
                        continue;
                    }
                    ledger.recordNotReached(at);
                }
            }
        }
    }

    /**
     * Whether every object type implementing {@code iface} carries {@code at}'s own instruction at
     * its copy of the slot, which is what makes the interface's coordinate one the generator
     * lowers elsewhere rather than one it drops.
     *
     * <p>Decided off SDL and the same {@link #instructed} predicate the sweep enumerates with, so
     * the two sides of the comparison cannot disagree about what an instruction is. The census is
     * deliberately not consulted: a sweep that read the population it exists to leave alone could
     * empty it.
     *
     * <p>Every implementation, not any: an implementation that does not repeat the instruction is
     * one whose own coordinate carries no decode and draws no census row to be reported at, so the
     * interface's row is the only place the drop can be named. An interface with no implementations
     * satisfies this vacuously, which is the safe direction and not an oversight: there is no
     * lowering coordinate to name, and no request can reach the field either, so a report would
     * name a drop that cannot happen.
     */
    private static boolean carriedByEveryImplementation(
            GraphQLSchema schema, GraphQLInterfaceType iface, GraphQLFieldDefinition fieldDef,
            NodeIdDecodeCoordinate at) {
        for (var implementation : schema.getImplementations(iface)) {
            var implField = implementation.getFieldDefinition(fieldDef.getName());
            if (implField == null || !coordinatesUnder(implementation.getName(), implField)
                    .contains(rootedAt(implementation.getName(), at))) {
                return false;
            }
        }
        return true;
    }

    /** {@code at} as the implementation would spell it: the same coordinate under its own type. */
    private static NodeIdDecodeCoordinate rootedAt(String rootTypeName, NodeIdDecodeCoordinate at) {
        return switch (at) {
            case NodeIdDecodeCoordinate.Argument a -> new NodeIdDecodeCoordinate.Argument(
                rootTypeName, a.rootFieldName(), a.rootArgumentName());
            case NodeIdDecodeCoordinate.InputField f -> new NodeIdDecodeCoordinate.InputField(
                rootTypeName, f.rootFieldName(), f.rootArgumentName(), f.descent());
        };
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
