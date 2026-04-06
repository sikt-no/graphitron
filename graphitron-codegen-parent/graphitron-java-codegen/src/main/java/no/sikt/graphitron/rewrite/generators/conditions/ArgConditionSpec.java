package no.sikt.graphitron.rewrite.generators.conditions;

/**
 * Specification for a single argument-based condition to be generated.
 *
 * @param argName        GraphQL argument name (e.g. "active", "maxRentalRate")
 * @param columnJavaName Java field name on the jOOQ table class (e.g. "ACTIVEBOOL", "RENTAL_RATE")
 * @param graphqlTypeName base GraphQL type name of the argument (e.g. "Boolean", "Float", "MpaaRating")
 * @param columnClassName fully-qualified Java class name of the jOOQ column type
 *                        (e.g. "java.lang.Boolean", "java.math.BigDecimal",
 *                        "no.sikt.graphitron.rewrite.test.jooq.enums.MpaaRating")
 * @param op             comparison operator: "eq", "le", or "ge"
 */
public record ArgConditionSpec(
    String argName,
    String columnJavaName,
    String graphqlTypeName,
    String columnClassName,
    String op
) {}
