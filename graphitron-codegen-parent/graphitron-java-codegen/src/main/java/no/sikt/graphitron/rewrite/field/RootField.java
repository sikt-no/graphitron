package no.sikt.graphitron.rewrite.field;

/**
 * A field on a root operation type (Query or Mutation).
 */
public sealed interface RootField extends GraphitronField
    permits QueryField, MutationField {}
