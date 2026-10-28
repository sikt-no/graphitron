package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import org.jooq.DataType;
import org.jooq.impl.SQLDataType;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How a node-id key slot's text is parsed back into a value of its column: the first step of the
 * generated {@code ColumnValues.exact}, decided at generation time from the column alone.
 *
 * <p>The encoder writes a key value as its column's Java type's {@code toString()}, which on a
 * converter-backed column is the converter's user type. jOOQ's own coercion of text through a
 * converted {@code DataType} parses as the converter's database type and then converts, which loses
 * whatever the user type's text carries that the database type cannot hold: the time of day of a
 * date-only database type bound to {@code LocalDateTime}. The arms:
 *
 * <ul>
 *   <li>{@link ColumnType}: no converter, so the column's own data type parses the text.</li>
 *   <li>{@link UserType}: a converter to a type jOOQ has a built-in text form for, so the text is
 *       parsed as that type and the converter runs on the typed value.</li>
 *   <li>{@link DatabaseType}: a converter to any other class, which jOOQ cannot parse text into, so
 *       the text is parsed as the database type and the converter's {@code from} gives the value;
 *       the column trip decides whether that is the value the id was minted from.</li>
 * </ul>
 */
public sealed interface SlotParse {

    /** A column with no converter: parse with the column's own data type. */
    record ColumnType() implements SlotParse {}

    /** A converter whose user type {@code userClass} jOOQ parses text into: parse as that type. */
    record UserType(String userClass) implements SlotParse {}

    /** A converter to a class jOOQ has no text form for: parse as the database type. */
    record DatabaseType() implements SlotParse {}

    /**
     * The class names jOOQ has a built-in text form for: the {@code getType()} of every public
     * static {@link DataType} constant on {@link SQLDataType}, less {@code java.lang.Object}, the
     * {@code OTHER} type, whose parse would accept any text. Read off the constants rather than
     * asked per class, because jOOQ answers an unknown class by logging a warning and throwing.
     */
    Set<String> TEXT_FORM_CLASSES = Arrays.stream(SQLDataType.class.getFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) && DataType.class.isAssignableFrom(f.getType()))
        .map(f -> {
            try {
                return ((DataType<?>) f.get(null)).getType().getName();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        })
        .filter(name -> !Object.class.getName().equals(name))
        .collect(Collectors.toUnmodifiableSet());

    /** The arm a key slot over {@code column} takes. */
    static SlotParse of(ColumnRef column) {
        if (column.columnClass().equals(column.databaseType())) {
            return new ColumnType();
        }
        return TEXT_FORM_CLASSES.contains(column.columnClass())
            ? new UserType(column.columnClass())
            : new DatabaseType();
    }

    /**
     * The {@code Class<?>[]} argument {@code NodeIdEncoder.decodeColumns} takes for a key over
     * {@code columns}, in key order: a class literal for a {@link UserType} slot and {@code null}
     * for the others, or a bare {@code null} when no slot is a {@link UserType}, which is every key
     * with no converter.
     */
    static CodeBlock parseClasses(List<ColumnRef> columns) {
        var arms = columns.stream().map(SlotParse::of).toList();
        if (arms.stream().noneMatch(UserType.class::isInstance)) {
            return CodeBlock.of("null");
        }
        var literals = arms.stream()
            .map(arm -> arm instanceof UserType user
                ? CodeBlock.of("$T.class", CatalogRefs.decodeBindingType(user.userClass()))
                : CodeBlock.of("null"))
            .collect(CodeBlock.joining(", "));
        TypeName classOfWildcard = ParameterizedTypeName.get(
            ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName.OBJECT));
        return CodeBlock.of("new $T { $L }", ArrayTypeName.of(classOfWildcard), literals);
    }
}
