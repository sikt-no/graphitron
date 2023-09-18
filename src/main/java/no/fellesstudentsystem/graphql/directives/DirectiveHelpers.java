package no.fellesstudentsystem.graphql.directives;

import graphql.language.*;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Helper methods for extracting directive information from the schema.
 */
public class DirectiveHelpers {
    private static Value<?> getArgument(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        var dir = container.getDirectives(directive.getName());
        return dir != null && !dir.isEmpty() ? extractArgumentValueFrom(dir.get(0), arg) : null;
    }

    private static Value<?> extractArgumentValueFrom(Directive directive, String arg) {
        var argument = directive.getArgument(arg);
        return argument != null ? argument.getValue() : null;
    }

    /**
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param arg Name of the argument.
     * @return The String value of the directive argument, if it exists.
     */
    public static Optional<String> getOptionalDirectiveArgumentString(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        return Optional.ofNullable((StringValue) getArgument(container, directive, arg))
                .map(StringValue::getValue);
    }

    /**
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param arg Name of the argument.
     * @return The name of the enum value for this argument, if it exists.
     */
    public static Optional<String> getOptionalDirectiveArgumentEnum(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        return Optional.ofNullable((EnumValue) getArgument(container, directive, arg))
                .map(EnumValue::getName);
    }

    /**
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param arg Name of the argument.
     * @return List of String values of the directive argument, if it exists.
     */
    public static List<String> getOptionalDirectiveArgumentStringList(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        return getOptionalDirectiveArgumentStringList(container, directive.getName(), arg);
    }

    private static List<String> getOptionalDirectiveArgumentStringList(DirectivesContainer<?> container, String directiveName, String arg) {
        var dir = container.getDirectives(directiveName);
        if (dir == null || dir.isEmpty()) {
            return List.of();
        }

        var args = dir.get(0).getArgument(arg);
        if (args == null) {
            return List.of();
        }

        var argsValue = args.getValue();

        if (argsValue instanceof StringValue) {
            return List.of(((StringValue) argsValue).getValue());
        }
        return ((ArrayValue) argsValue).getValues().stream()
                .map(stringValue -> stringValue instanceof NullValue ? null : ((StringValue) stringValue).getValue())
                .collect(Collectors.toList());

    }

    /**
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param arg Name of the argument.
     * @return The Boolean value of the directive argument, if it exists.
     */
    public static Optional<Boolean> getOptionalDirectiveArgumentBoolean(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        return Optional.ofNullable((BooleanValue) getArgument(container, directive, arg))
                .map(BooleanValue::isValue);
    }

    /**
     * Get a directive argument value. This assumes that the argument is required and will exist.
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param arg Name of the argument.
     * @return The String value of the directive argument. An exception is thrown if this does not exist.
     */
    public static String getDirectiveArgumentString(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        return getOptionalDirectiveArgumentString(container, directive, arg)
                .orElseThrow(getIllegalArgumentExceptionSupplier(arg, directive.getName()));
    }

    /**
     * Get a directive argument value. This assumes that the argument is required and will exist.
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param arg Name of the argument.
     * @return The name of the enum value for this argument. An exception is thrown if this does not exist.
     */
    public static String getDirectiveArgumentEnum(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        return getOptionalDirectiveArgumentEnum(container, directive, arg)
                .orElseThrow(getIllegalArgumentExceptionSupplier(arg, directive.getName()));
    }
    /**
     * Get a directive argument value. This assumes that the argument is required and will exist.
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param arg Name of the argument.
     * @return The Boolean value of the directive argument. An exception is thrown if this does not exist.
     */
    public static Boolean getDirectiveArgumentBoolean(DirectivesContainer<?> container, GenerationDirective directive, String arg) {
        return getOptionalDirectiveArgumentBoolean(container, directive, arg)
                .orElseThrow(getIllegalArgumentExceptionSupplier(arg, directive.getName()));
    }

    private static Supplier<IllegalArgumentException> getIllegalArgumentExceptionSupplier(String arg, String directive) {
        return () -> new IllegalArgumentException("No argument '" + arg + "' found for directive '" + directive + "'.");
    }
}
