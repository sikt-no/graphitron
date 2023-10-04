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
     * @param param Name of the argument.
     * @return The String value of the directive argument, if it exists.
     */
    public static Optional<String> getOptionalDirectiveArgumentString(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return Optional.ofNullable((StringValue) getArgument(container, directive, param.getName()))
                .map(StringValue::getValue);
    }

    /**
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param param Name of the argument.
     * @return The name of the enum value for this argument, if it exists.
     */
    public static Optional<String> getOptionalDirectiveArgumentEnum(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return Optional.ofNullable((EnumValue) getArgument(container, directive, param.getName()))
                .map(EnumValue::getName);
    }

    /**
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param param Name of the argument.
     * @return List of String values of the directive argument, if it exists.
     */
    public static List<String> getOptionalDirectiveArgumentStringList(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return getOptionalDirectiveArgumentStringList(container, directive.getName(), param);
    }

    private static List<String> getOptionalDirectiveArgumentStringList(DirectivesContainer<?> container, String directiveName, GenerationDirectiveParam param) {
        var dir = container.getDirectives(directiveName);
        if (dir == null || dir.isEmpty()) {
            return List.of();
        }

        var args = dir.get(0).getArgument(param.getName());
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
     * @param param Name of the argument.
     * @return The Boolean value of the directive argument, if it exists.
     */
    public static Optional<Boolean> getOptionalDirectiveArgumentBoolean(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return Optional.ofNullable((BooleanValue) getArgument(container, directive, param.getName()))
                .map(BooleanValue::isValue);
    }

    /**
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param param     Name of the argument.
     * @return Object fields of the directive argument, if it exists.
     */
    public static Optional<List<ObjectField>> getOptionalDirectiveArgumentObjectFields(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return (Optional.ofNullable((ObjectValue) getArgument(container, directive, param.getName()))).map(ObjectValue::getObjectFields);
    }

    /**
     * Get a directive argument value. This assumes that the argument is required and will exist.
     *
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param param Name of the argument.
     * @return The String value of the directive argument. An exception is thrown if this does not exist.
     */
    public static String getDirectiveArgumentString(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return getOptionalDirectiveArgumentString(container, directive, param)
                .orElseThrow(getIllegalArgumentExceptionSupplier(param.getName(), directive.getName()));
    }

    /**
     * Get a directive argument value. This assumes that the argument is required and will exist.
     *
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param param Name of the argument.
     * @return The name of the enum value for this argument. An exception is thrown if this does not exist.
     */
    public static String getDirectiveArgumentEnum(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return getOptionalDirectiveArgumentEnum(container, directive, param)
                .orElseThrow(getIllegalArgumentExceptionSupplier(param.getName(), directive.getName()));
    }

    /**
     * Get a directive argument value. This assumes that the argument is required and will exist.
     *
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param param Name of the argument.
     * @return The Boolean value of the directive argument. An exception is thrown if this does not exist.
     */
    public static Boolean getDirectiveArgumentBoolean(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return getOptionalDirectiveArgumentBoolean(container, directive, param)
                .orElseThrow(getIllegalArgumentExceptionSupplier(param.getName(), directive.getName()));
    }

    /**
     * Get a directive argument value. This assumes that the argument is required and will exist.
     *
     * @param container The graph element to be inspected.
     * @param directive The directive this argument should be set on.
     * @param param     Name of the argument.
     * @return Object fields of the directive argument. An exception is thrown if this does not exist.
     */
    public static List<ObjectField> getDirectiveArgumentObjectFields(DirectivesContainer<?> container, GenerationDirective directive, GenerationDirectiveParam param) {
        directive.checkParamIsValid(param);
        return getOptionalDirectiveArgumentObjectFields(container, directive, param)
                .orElseThrow(getIllegalArgumentExceptionSupplier(param.getName(), directive.getName()));
    }

    public static <T extends NamedNode<T>> Optional<T> getOptionalObjectFieldByName(List<T> fields, GenerationDirectiveParam param) {
        var name = param.getName();
        return fields
                .stream()
                .filter(objectField -> objectField.getName().equals(name))
                .findFirst();
    }

    public static <T extends NamedNode<T>> T getObjectFieldByName(List<T> fields, GenerationDirectiveParam param) {
        return getOptionalObjectFieldByName(fields, param).orElseThrow(() ->
                new IllegalArgumentException(
                        "Field with name '"
                                + param.getName()
                                + "' not found in '"
                                + fields.stream().map(NamedNode::getName).collect(Collectors.joining(", "))
                                + "'."
                )
        );
    }

    public static String stringValueOf(ObjectField objectField) {
        return ((StringValue) objectField.getValue()).getValue();
    }

    private static Supplier<IllegalArgumentException> getIllegalArgumentExceptionSupplier(String arg, String directive) {
        return () -> new IllegalArgumentException("No argument '" + arg + "' found for directive '" + directive + "'.");
    }
}
