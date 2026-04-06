package no.sikt.graphitron.rewrite.generators.conditions;

import java.util.List;

/**
 * Specification for the conditions class generated for a particular table-mapped GraphQL return type.
 *
 * <p>One instance per unique return type that has at least one {@code @condition}-annotated argument
 * across all query fields. Multiple fields returning the same type are merged: their argument
 * condition specs are de-duplicated by column Java name.
 *
 * @param typeName           GraphQL type name — used as the class name prefix (e.g. "Customer")
 * @param tableJavaFieldName Java field name in the jOOQ {@code Tables} class (e.g. "CUSTOMER")
 * @param args               the filterable arguments, ordered by first occurrence
 */
public record FieldArgConditionSpec(
    String typeName,
    String tableJavaFieldName,
    List<ArgConditionSpec> args
) {}
