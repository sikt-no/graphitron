package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

/**
 * Where the generated column-value helper lives, {@code <outputPackage>.util.ColumnValues}, and the
 * one call on it a filter emits: {@code fromWire}, the column's own coercion of a client's wire
 * value behind the size bound every {@code ColumnValues} entry point applies. Generator configuration
 * plus a fixed convention, as {@link NodeIdEncoderRef} is.
 */
public final class ColumnValuesRef {

    /** The simple name the column-values generator emits, restated here rather than imported across tiers. */
    private static final String SIMPLE_NAME = "ColumnValues";

    private ColumnValuesRef() {}

    /** The column-values class for a run emitting into {@code outputPackage}. */
    public static ClassName of(String outputPackage) {
        return ClassName.get(outputPackage + ".util", SIMPLE_NAME);
    }

    /**
     * {@code ColumnValues.fromWire(<dataType>, <value>)}: {@code value} as a value of the column whose
     * {@code DataType} is {@code dataType}, {@code null} where the coercion answers none or the value is
     * too large to parse at all.
     */
    public static CodeBlock fromWire(String outputPackage, CodeBlock dataType, CodeBlock value) {
        return CodeBlock.of("$T.fromWire($L, $L)", of(outputPackage), dataType, value);
    }
}
