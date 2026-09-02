package no.sikt.graphitron.model.grammar;

import graphql.language.ArrayValue;
import graphql.language.NullValue;
import graphql.language.StringValue;
import graphql.schema.GraphQLDirectiveContainer;

import java.util.List;
import java.util.Optional;

/**
 * Reads a value off an applied SDL directive argument. The parse-boundary helper every directive
 * reader shares, so one applied-directive argument has one decoding.
 *
 * <p>Lives with the fact tier rather than with the generator because reading a consumer's SDL is
 * what the fact tier does, and the readers above it call downward.
 */
public final class DirectiveArgs {

    private DirectiveArgs() {}

    /**
     * Returns the stripped String value of an applied directive argument, if present.
     */
    public static Optional<String> argString(GraphQLDirectiveContainer container, String directive, String arg) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return Optional.empty();
        var argument = dir.getArgument(arg);
        if (argument == null) return Optional.empty();
        Object value = argument.getValue();
        if (value instanceof StringValue sv) return Optional.of(sv.getValue().strip());
        if (value instanceof String s) return Optional.of(s.strip());
        return Optional.empty();
    }

    /**
     * Returns the String values of a list applied-directive argument, or an empty list if absent.
     */
    public static List<String> argStringList(GraphQLDirectiveContainer container, String directive, String arg) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return List.of();
        var argument = dir.getArgument(arg);
        if (argument == null) return List.of();
        Object value = argument.getValue();
        if (value instanceof StringValue sv) return List.of(sv.getValue().strip());
        if (value instanceof String s) return List.of(s.strip());
        if (value instanceof ArrayValue av) {
            return av.getValues().stream()
                .map(v -> v instanceof NullValue ? null : ((StringValue) v).getValue().strip())
                .toList();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(v -> v == null ? null : v.toString().strip())
                .toList();
        }
        return List.of();
    }
}
