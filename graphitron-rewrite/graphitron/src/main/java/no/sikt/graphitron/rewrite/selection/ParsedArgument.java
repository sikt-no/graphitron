package no.sikt.graphitron.rewrite.selection;

/**
 * A name–value pair representing a single argument in a field or an object-value field.
 */
public record ParsedArgument(String name, ParsedValue value) {}
