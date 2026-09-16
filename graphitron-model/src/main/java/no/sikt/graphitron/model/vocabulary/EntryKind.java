package no.sikt.graphitron.model.vocabulary;

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
 *
 * <p>Here rather than beside the writer that fills the column, and the gatherer isolation gate is
 * what decides it: a class inside a gatherer's package is that gatherer's private helper, and this
 * is named by the codegen driver as well, which belongs to no gatherer and never will. So the
 * package is the one the gate's second option describes, tier vocabulary that is nobody's
 * gatherer's. The schema states thirty-nine closed vocabularies as a CHECK and this is the first
 * bound to a type; the rest, if they follow, land beside it.
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
