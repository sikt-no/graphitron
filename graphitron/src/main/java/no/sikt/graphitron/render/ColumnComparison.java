package no.sikt.graphitron.render;

import no.sikt.graphitron.command.CatalogColumn;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * The single minting surface for "compare two catalog columns", and the one place in the generator
 * that reconciles a Java type disagreement between them.
 *
 * <h2>Why one mint rather than a check per site</h2>
 *
 * A jOOQ {@code Converter} is a <em>client-side type mapping only</em>. It changes the Java type
 * jOOQ hands you for a column and cannot change the column's SQL type. When a consumer's codegen
 * attaches one to a single end of a foreign key (by selecting on a column path rather than on a
 * type, which is ordinary configuration), the two ends <em>diverge</em>: the referencing column
 * stays {@code Field<Short>} while the referenced column becomes {@code Field<String>}. Both
 * columns are still the same SQL type, so {@code a = b} is still a valid, index-usable predicate;
 * it is only the Java spelling {@code a.eq(b)} that no longer type-checks, and the whole generated
 * module then fails to compile.
 *
 * <p>The generator writes that comparison from many places: correlated subquery {@code WHERE}s,
 * name-matched join {@code ON}s, pivot-multiset correlations, joined-detail chains, and the
 * DataLoader parent-input predicates. Those are so many spellings of one question, and a type
 * check copied into each would leave no structural reason the next site picks it up. Routing them
 * through one producer is the shape the tree already uses for this class of problem, the way every
 * {@code VALUES} cell in the generator goes through {@link ValuesJoinRowBuilder#cellsCode}.
 *
 * <h2>The rule</h2>
 *
 * Let {@code L} be the receiver's {@link CatalogRefs#columnType(ColumnRef)} and {@code R} the argument's.
 *
 * <ul>
 *   <li>Either is {@code null}: emit {@code left.eq(right)} unchanged. {@code columnType()} is
 *       nullable for hand-built placeholder refs whose type is never read, and a coerce guessed
 *       from a missing type would be worse than the status quo.</li>
 *   <li>{@code L.equals(R)}: emit {@code left.eq(right)} unchanged. This is every schema that
 *       compiles today, so no approved generated output moves.</li>
 *   <li>Otherwise: emit {@code left.eq(right.coerce(left))}.</li>
 * </ul>
 *
 * <p>{@code coerce} is the operation this needs and {@code cast} is not: {@code cast} puts a real
 * SQL {@code CAST} around the column and costs the index on it, where {@code coerce}'s entire job
 * is to reinterpret a field's Java type while leaving the rendered SQL alone. The emitted SQL
 * therefore does not move, which is the whole safety claim here.
 *
 * <p>The argument is coerced onto the receiver, always. "Coerce the converted side to the raw
 * side" and "coerce to the referenced side" were the alternatives, and both need a second fact
 * threaded in that a {@link TypeName} cannot answer. Since neither operand is a bind value, the
 * direction is invisible in the emitted SQL, so the rule is chosen for mechanical reviewability.
 *
 * <h2>The companion bind rule</h2>
 *
 * Coercion is <em>only</em> about the Java types lining up. It is not where a converter applies.
 * <b>A value binds at the {@code DataType} of the column it was read from, and the comparison
 * coerces.</b> Binding is where the converter genuinely runs (the value really does round-trip
 * through it); the two are separate steps and getting them backwards is how a converter-backed
 * key produces bad SQL rather than a compile error. {@link #equalityAgainstValue} is the two rules
 * applied in that order, and is the only entry point that has to bind anything.
 */
public final class ColumnComparison {

    private ColumnComparison() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    /**
     * {@code <leftAlias>.<leftColumn>.eq(<rightAlias>.<rightColumn>)}, coerced per the class rule
     * when the two columns' Java types diverge. Both operands are aliased table columns; the
     * "alias" may be any Java expression naming a table instance in scope (an alias local, a
     * {@code table} parameter), since it is emitted verbatim ahead of the column's Java name.
     */
    public static CodeBlock equality(String leftAlias, ColumnRef leftColumn,
            String rightAlias, ColumnRef rightColumn) {
        var left = CodeBlock.of("$L.$L", leftAlias, leftColumn.javaName());
        var right = CodeBlock.of("$L.$L", rightAlias, rightColumn.javaName());
        return compare(left, CatalogRefs.columnType(leftColumn), right, CatalogRefs.columnType(rightColumn));
    }

    /**
     * {@link #equality(String, ColumnRef, String, ColumnRef)} for a caller whose columns arrived as
     * command rows rather than as walked model refs. Same rule; what differs is only that a
     * {@link CatalogColumn} carries its bound Java type as the name jOOQ reports rather than as a
     * decoded emit type, so the decode happens here. A store-sourced reader therefore sees the same
     * divergence a catalog-sourced one does: {@code javaTypeName} is {@code Field.getType()}'s own
     * name, which is the post-converter type.
     */
    public static CodeBlock equality(String leftAlias, CatalogColumn leftColumn,
            String rightAlias, CatalogColumn rightColumn) {
        var left = CodeBlock.of("$L.$L", leftAlias, leftColumn.javaName());
        var right = CodeBlock.of("$L.$L", rightAlias, rightColumn.javaName());
        return compare(left, CatalogRefs.decodeBindingType(leftColumn.javaTypeName()),
            right, CatalogRefs.decodeBindingType(rightColumn.javaTypeName()));
    }

    /**
     * {@code <alias>.<column>.eq(<fieldExpression>)} where the right operand is some other
     * {@code Field}-valued expression, typically a {@code parentInput.field(…)} or
     * {@code lookupInput.field(…)} lookup into a {@code VALUES}-derived table. That lookup is
     * typed by a known catalog column rather than being one, so the caller names that column as
     * {@code fieldColumn} and the class rule applies unchanged.
     *
     * @param fieldColumn the catalog column whose type {@code fieldExpression} carries, which is
     *                    the column the lookup's {@code DataType} was spelled from, not the receiver
     */
    public static CodeBlock equalityAgainstField(String alias, ColumnRef column,
            ColumnRef fieldColumn, CodeBlock fieldExpression) {
        var left = CodeBlock.of("$L.$L", alias, column.javaName());
        return compare(left, CatalogRefs.columnType(column), fieldExpression, CatalogRefs.columnType(fieldColumn));
    }

    /**
     * {@code <alias>.<column>.eq(<valueExpression>)} where the right operand is a bare Java value
     * rather than any kind of {@code Field}, a cell read out of a {@code Record}, say. There is
     * nothing for {@code coerce} to attach to, so this is the class's two rules applied in order:
     * bind the value at the {@code DataType} of the column it was read from, which makes it a
     * {@code Field} rendering through that column's converter, then coerce that field onto the
     * receiver.
     *
     * <p>The diverged emission is therefore
     * {@code left.eq(DSL.val(<value>, <owner>.<valueColumn>.getDataType()).coerce(left))}, and the
     * undiverged one is {@code left.eq(<value>)} exactly as before. Reading the value at the
     * receiver's type instead also compiles and is shorter, but it routes the value through
     * {@code Convert.convert} between two user types, which an arbitrary converter's user type
     * does not guarantee; the bind-then-coerce form asks jOOQ only for conversions a registered
     * {@code Converter} already declares.
     *
     * @param valueColumn      the column the value was read from
     * @param valueOwnerTable  that column's owner, which together with it spells the bind's
     *                         {@code DataType}
     */
    public static CodeBlock equalityAgainstValue(String alias, ColumnRef column,
            ColumnRef valueColumn, TableRef valueOwnerTable, CodeBlock valueExpression) {
        TypeName leftType = CatalogRefs.columnType(column);
        TypeName rightType = CatalogRefs.columnType(valueColumn);
        var left = CodeBlock.of("$L.$L", alias, column.javaName());
        if (!diverges(leftType, rightType)) {
            return CodeBlock.of("$L.eq($L)", left, valueExpression);
        }
        var bound = CodeBlock.of("$T.val($L, $T.$L.$L.getDataType())",
            DSL, valueExpression,
            CatalogRefs.constantsClass(valueOwnerTable), valueOwnerTable.javaFieldName(), valueColumn.javaName());
        return CodeBlock.of("$L.eq($L.coerce($L))", left, bound, left);
    }

    private static CodeBlock compare(CodeBlock left, TypeName leftType,
            CodeBlock right, TypeName rightType) {
        return diverges(leftType, rightType)
            ? CodeBlock.of("$L.eq($L.coerce($L))", left, right, left)
            : CodeBlock.of("$L.eq($L)", left, right);
    }

    /**
     * Whether the two operands' Java types are known and disagree. A {@code null} on either side
     * is a placeholder ref carrying no type, which reads as "no reason to coerce" rather than as
     * a divergence.
     */
    private static boolean diverges(TypeName leftType, TypeName rightType) {
        return leftType != null && rightType != null && !leftType.equals(rightType);
    }
}
