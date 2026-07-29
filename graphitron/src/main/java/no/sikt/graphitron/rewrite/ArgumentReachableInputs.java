package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The argument-reachability closure over SDL input types: every input type referenced by some
 * field argument, transitively through nested input components. A type-grain schema fact with
 * more than one consumer (the input-record emit family's membership, and eventually the
 * compile graph's {@code inputRecord} nodes, which today over-collect by taking every
 * {@code InputType}), so it is computed once post-walk and landed on
 * {@link GraphitronSchema#argumentReachableInputs()} beside the other post-walk folds
 * ({@code arrivals}, {@code reachableSourceShapes}); no emit-side site re-derives it.
 *
 * <p>Seeds from every {@code GraphQLObjectType} field's arguments in the assembled schema (the
 * model's root and table-backed variants carry no {@code schemaType()} accessor, so the
 * assembled schema is the authoritative source for SDL argument walking), then closes through
 * nested input components via the classified {@link GraphitronType.InputType}s' own field
 * definitions. Introspection types ({@code __}-prefixed) are excluded. Non-reachable inputs are
 * dead schema.
 */
public final class ArgumentReachableInputs {

    private ArgumentReachableInputs() {}

    static Set<String> compute(Map<String, GraphitronType> types, GraphQLSchema assembled) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>();

        for (var namedType : assembled.getAllTypesAsList()) {
            if (namedType.getName().startsWith("__")) continue;
            if (!(namedType instanceof GraphQLObjectType objType)) continue;
            for (GraphQLFieldDefinition field : objType.getFieldDefinitions()) {
                for (GraphQLArgument arg : field.getArguments()) {
                    seedInputArg(arg.getType(), reachable, work);
                }
            }
        }

        while (!work.isEmpty()) {
            String name = work.poll();
            GraphQLInputObjectType schemaType =
                types.get(name) instanceof GraphitronType.InputType it ? it.schemaType() : null;
            if (schemaType == null) continue;
            for (var f : schemaType.getFieldDefinitions()) {
                seedInputArg(f.getType(), reachable, work);
            }
        }

        return Set.copyOf(reachable);
    }

    private static void seedInputArg(GraphQLType type, Set<String> reachable, Deque<String> work) {
        var base = GraphQLTypeUtil.unwrapAll(type);
        if (base instanceof GraphQLInputObjectType in) {
            String name = in.getName();
            if (reachable.add(name)) {
                work.add(name);
            }
        }
    }
}
