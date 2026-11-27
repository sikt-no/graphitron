package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import java.util.function.Supplier;

/**
 * A client's wire value as a value of the column it is matched against, as
 * {@code graphitron_occurrence_column_conversion} states the conversion. Shared by every emitter that
 * reads a wire value at a column the store states a conversion for: a filter's condition and a
 * lookup's key rows alike.
 */
final class ColumnConversion {

    private ColumnConversion() {}

    /**
     * {@code value} as the column's Java {@code type}: for {@code ENUM_CONSTANT} the column's enum
     * constant of the value's name, graphql-java handing an enum value over as its name, under a
     * pattern variable {@code spelled} names; for {@code DIRECT}, {@code CONVERTED} and anything else
     * through the column's own data type, {@code column} being the column expression, by
     * {@code ColumnValues.fromWire}, which bounds the value's size before jOOQ parses it. The enum
     * arm is not {@code fromWire}'s, since jOOQ resolves an {@code EnumType} from the wire by its
     * literal, and a literal that differs from the GraphQL value's name matches nothing.
     * {@code spelled} is asked only for the enum arm.
     */
    static CodeBlock atColumn(String conversion, TypeName type, CodeBlock column, CodeBlock value,
                              Supplier<String> spelled, ClassName columnValues) {
        if ("ENUM_CONSTANT".equals(conversion)) {
            var name = spelled.get();
            return CodeBlock.of("$L instanceof $T $L ? $T.valueOf($L) : null", value, String.class, name, type, name);
        }
        return CodeBlock.of("$T.fromWire($L.getDataType(), $L)", columnValues, column, value);
    }
}
