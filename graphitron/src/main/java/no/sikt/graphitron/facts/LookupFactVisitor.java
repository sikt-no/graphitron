package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the lookup trigger ({@link LookupFacts}) from every reachable field coordinate
 * (arguments carrying {@code @lookupKey}), every reachable input object field carrying it, and
 * the input-type reference edges the transitive closure needs. This visitor is the sole
 * producer of all three populations; the directive name has exactly one home here.
 *
 * <p>The closure is a fixpoint over the recorded edges rather than a bounded recursion, so a
 * cyclic input-type graph terminates and arbitrarily deep applications are seen (the
 * classifier's former recursive walk capped at depth 10, a silent false negative this gather
 * retires).
 */
public final class LookupFactVisitor implements FactVisitor {

    /** The lookup-key directive; classification-side constants delegate here. */
    public static final String DIR_LOOKUP_KEY = "lookupKey";

    private final Map<GraphQLFieldDefinition, LookupFacts.FieldRow> fieldRows = new IdentityHashMap<>();
    private final Map<GraphQLInputObjectField, LookupFacts.InputFieldRow> inputFieldRows = new IdentityHashMap<>();
    /** Input-type reference edges: parent input type name to referenced input type names. */
    private final Map<String, Set<String>> inputTypeEdges = new HashMap<>();
    /** Input types with a directly-annotated field. */
    private final Set<String> directlyMarked = new HashSet<>();

    @Override
    public Set<FactSubjectKind> kinds() {
        return Set.of(FactSubjectKind.FIELD_COORDINATE, FactSubjectKind.INPUT_OBJECT_FIELD);
    }

    @Override
    public void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        var lookupArgs = new ArrayList<String>();
        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_LOOKUP_KEY)) {
                lookupArgs.add(arg.getName());
            }
        }
        if (lookupArgs.isEmpty()) {
            return;
        }
        fieldRows.put(fieldDef, new LookupFacts.FieldRow(parentTypeName, fieldDef.getName(), lookupArgs));
    }

    @Override
    public void visitInputObjectField(GraphQLInputObjectType parent, GraphQLInputObjectField field) {
        if (GraphQLTypeUtil.unwrapAll(field.getType()) instanceof GraphQLInputObjectType referenced) {
            inputTypeEdges.computeIfAbsent(parent.getName(), n -> new HashSet<>())
                .add(referenced.getName());
        }
        if (!field.hasAppliedDirective(DIR_LOOKUP_KEY)) {
            return;
        }
        directlyMarked.add(parent.getName());
        inputFieldRows.put(field, new LookupFacts.InputFieldRow(parent.getName(), field.getName()));
    }

    /** The gathered relation; read once by {@link GatheredFacts#gather}'s slot fill. */
    LookupFacts relation() {
        return new LookupFacts(fieldRows, inputFieldRows, closeOverEdges());
    }

    /** Fixpoint: a type is lookup-bearing when directly marked or referencing a bearing type. */
    private Set<String> closeOverEdges() {
        var bearing = new HashSet<>(directlyMarked);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var entry : inputTypeEdges.entrySet()) {
                if (!bearing.contains(entry.getKey())
                        && entry.getValue().stream().anyMatch(bearing::contains)) {
                    bearing.add(entry.getKey());
                    grew = true;
                }
            }
        }
        return bearing;
    }
}
