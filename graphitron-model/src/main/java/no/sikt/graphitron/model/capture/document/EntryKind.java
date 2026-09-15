package no.sikt.graphitron.model.capture.document;

/**
 * Which entry relation a row of the entry index came from, as a type rather than as a string.
 *
 * <p>Bound to {@code graphql_ast_entry.entry_kind} by a forced type in the codegen, so the
 * generated column is this enum and a writer names a constant the compiler resolves. The column
 * stays {@code VARCHAR} with a {@code CHECK} naming the same nineteen values: the constraint is
 * what a reader at a SQL prompt sees and what defends a row written by anything other than this
 * code, and the enum is what defends the nineteen literals a writer would otherwise spell by hand.
 * {@code EntryKindVocabularyTest} holds the two to each other, which is the price of stating a
 * vocabulary twice and is cheaper than either half alone.
 *
 * <p>Named for the relation rather than for the grammar, on the column's own terms: a reader
 * wanting more than the position and the enclosing element joins the relation this names, so the
 * name has to be the one they will type.
 */
public enum EntryKind {
    TYPE_DECLARATION,
    DIRECTIVE_DEFINITION,
    SCHEMA_DEFINITION,
    FIELD_DEFINITION,
    ENUM_VALUE_DEFINITION,
    IMPLEMENTS,
    UNION_MEMBER,
    DIRECTIVE_LOCATION,
    OPERATION_TYPE_DEFINITION,
    FIELD_ARGUMENT,
    INPUT_FIELD,
    DIRECTIVE_ARGUMENT,
    TYPE_DIRECTIVE,
    FIELD_DIRECTIVE,
    INPUT_VALUE_DIRECTIVE,
    ENUM_VALUE_DIRECTIVE,
    SCHEMA_DIRECTIVE,
    APPLIED_ARGUMENT,
    VALUE
}
