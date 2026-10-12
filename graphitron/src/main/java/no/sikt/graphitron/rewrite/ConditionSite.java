package no.sikt.graphitron.rewrite;

/**
 * Where a slot-typed {@code @condition} sits, as the carrier decision for its input-object
 * parameters needs it ({@link ServiceCatalog#reflectTableMethod}). The two arms differ in one fact:
 * whether the consuming coordinate is known when the condition is resolved.
 */
sealed interface ConditionSite {

    /**
     * An argument- or field-level {@code @condition}: resolved at the field it filters, so the
     * consuming coordinate (object type and field) is known, and with it the use site a built bean
     * keys its {@code @nodeId} members on.
     */
    record Consuming(String parentTypeName, String fieldName) implements ConditionSite {}

    /**
     * An input-field {@code @condition}: resolved once per input-type definition and placed per use,
     * so no consuming coordinate exists at resolve time.
     */
    record InputField(String inputTypeName, String fieldName) implements ConditionSite {}
}
