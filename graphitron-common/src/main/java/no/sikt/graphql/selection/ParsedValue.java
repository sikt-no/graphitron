package no.sikt.graphql.selection;

import java.util.List;

/**
 * Sealed hierarchy representing the possible value types in a GraphQL argument.
 */
public sealed interface ParsedValue
        permits ParsedValue.BooleanValue,
                ParsedValue.EnumValue,
                ParsedValue.FloatValue,
                ParsedValue.IntValue,
                ParsedValue.ListValue,
                ParsedValue.NullValue,
                ParsedValue.ObjectValue,
                ParsedValue.StringValue,
                ParsedValue.VariableValue {

    record StringValue(String value) implements ParsedValue {}

    record IntValue(long value) implements ParsedValue {}

    record FloatValue(double value) implements ParsedValue {}

    record BooleanValue(boolean value) implements ParsedValue {}

    record NullValue() implements ParsedValue {}

    /**
     * An unquoted name used as a value, i.e. an enum value.
     */
    record EnumValue(String value) implements ParsedValue {}

    record ListValue(List<ParsedValue> values) implements ParsedValue {}

    /**
     * An inline object value, e.g. {@code {key: "val"}}.
     * Fields are represented as a list of {@link ParsedArgument} entries.
     */
    record ObjectValue(List<ParsedArgument> fields) implements ParsedValue {}

    record VariableValue(String name) implements ParsedValue {}
}
