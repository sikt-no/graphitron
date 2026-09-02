package no.sikt.graphitron.render;

import no.sikt.graphitron.command.CatalogColumn;
import no.sikt.graphitron.command.CatalogTable;
import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;
import java.util.Map;

/**
 * The lift from a captured catalog name into the emit library's types: the one place a
 * {@link CatalogTable}, a {@link CatalogColumn} or one of the borrowed model refs
 * ({@link TableRef}, {@link ColumnRef}) becomes something javapoet can spell.
 *
 * <p>This class is the whole of what the command tier's name-carrying costs. A row states which
 * class a table is generated as, because that is a fact about the consumer's catalog and the store
 * holds it; how that name is written into a source file is an emit decision, and this is where it
 * is made. Splitting it that way is what lets a producer build a row without the emit library and
 * without a live catalog, which is the property the plan tier is shaped around.
 *
 * <p>The refs and the rows are lifted here together on purpose. Both carry names because both are
 * read back from a store that holds names, and a second lift beside this one would be the parallel
 * mechanism {@link TableRef}'s own javadoc warns against growing.
 *
 * <p>Every method here is a pure function of the row. Nothing looks anything up, and nothing
 * decides anything a producer could have: a lift that had to consult something would mean the row
 * was missing a fact.
 */
public final class CatalogRefs {

    private CatalogRefs() {}

    /** A captured class name as the emit library's reference to it. */
    public static ClassName className(String name) {
        return ClassName.bestGuess(name);
    }

    /**
     * A source-form Java type name as the emit library's type, arrays included: the lift for a
     * name written the way source writes it ({@code java.lang.Integer[]}), which is the form a
     * routine parameter's type rides in. A column's type rides the reflected form instead and
     * lifts through {@link #decodeBindingType}; the two spellings are told apart by the carrier rather
     * than sniffed, so neither decode has to guess which it was handed.
     */
    public static TypeName typeName(String sourceForm) {
        return sourceForm.endsWith("[]")
            ? ArrayTypeName.of(typeName(sourceForm.substring(0, sourceForm.length() - 2)))
            : className(sourceForm);
    }

    /** The generated table class: the declared type of a local holding an alias of the table. */
    public static ClassName tableClass(CatalogTable table) {
        return className(table.tableClassName());
    }

    /**
     * {@code <Table> <alias> = <Tables>.<TABLE>.as("<alias>");} — one aliased table local, the
     * declaration every re-read statement opens with for each table it names.
     */
    public static CodeBlock aliasDeclaration(CatalogTable table, String alias) {
        return CodeBlock.of("$T $L = $T.$L.as($S)", tableClass(table), alias,
            className(table.constantsClassName()), table.javaFieldName(), alias);
    }

    /** {@code <Tables>.<TABLE>.<COLUMN>} — a column reached through its schema's constants. */
    public static CodeBlock constantColumn(CatalogTable table, CatalogColumn column) {
        return CodeBlock.of("$T.$L.$L", className(table.constantsClassName()),
            table.javaFieldName(), column.javaName());
    }

    /**
     * A column's bound Java type, decoded from the captured name. Array-safe by construction: the
     * captured form is the raw {@code Class.getName()} spelling, which writes an array as a JVM
     * descriptor, and that descriptor is exactly what a plain class-name parse dies on. An array
     * column is an ordinary column in a consumer's database, so the decode that handles one is the
     * only correct decode here.
     */
    public static TypeName columnType(CatalogColumn column) {
        return decodeBindingType(column.javaTypeName());
    }

    /**
     * The jOOQ record type of a captured key tuple: {@code Record1<Integer>} for one column,
     * {@code Record2<Integer, String>} for two, and so on. What a write's key capture is declared
     * as, the capture being a projection of exactly these columns.
     */
    public static TypeName keyRecordType(List<CatalogColumn> columns) {
        var container = ClassName.get("org.jooq", "Record" + columns.size());
        return ParameterizedTypeName.get(container,
            columns.stream().map(CatalogRefs::columnType).toArray(TypeName[]::new));
    }

    /**
     * A column's bound Java type. Array-safe by construction, which is the whole reason this decode
     * is not a {@link ClassName#bestGuess} call at each read site: the captured name is the raw
     * {@code Class.getName()} spelling, which writes an array as a JVM descriptor
     * ({@code [Ljava.lang.Boolean;}), and that descriptor is exactly what {@code bestGuess} rejects.
     * A boolean-array column is an ordinary column in a consumer's database, so a generator that
     * dies on one is broken rather than unlucky.
     *
     * <p>Null for a ref that carries no real class name. Some fixtures pass a placeholder for
     * {@code columnClass} (an empty string, a key name, a {@code related_n} tag); such refs exist
     * for their {@code sqlName} and {@code javaName} alone and are never emitted, so their type is
     * never read.
     */
    public static TypeName columnType(ColumnRef column) {
        return column == null ? null : decodeBindingType(column.columnClass());
    }

    /**
     * A captured binding type as the emit library's type, arrays included, or null when the name is
     * absent or is not a class name at all. The decode a store-sourced reader needs: it holds a name
     * and no class, the codegen loader being closed by the time it runs.
     */
    public static TypeName decodeBindingType(String bindingType) {
        if (bindingType == null || bindingType.isBlank()) {
            return null;
        }
        if (bindingType.startsWith("[")) {
            TypeName component = decodeBindingType(componentOf(bindingType));
            return component == null ? null : ArrayTypeName.of(component);
        }
        TypeName primitive = PRIMITIVE_DESCRIPTORS.get(bindingType);
        if (primitive != null) {
            return primitive;
        }
        TypeName named = PRIMITIVE_NAMES.get(bindingType);
        if (named != null) {
            return named;
        }
        try {
            return ClassName.bestGuess(bindingType);
        } catch (IllegalArgumentException notAClassName) {
            return null;
        }
    }

    /**
     * One array dimension stripped: {@code [Ljava.lang.Boolean;} to {@code java.lang.Boolean}, and
     * {@code [[I} to {@code [I}, so a nested array recurses one level per call.
     */
    private static String componentOf(String descriptor) {
        String component = descriptor.substring(1);
        if (component.startsWith("L") && component.endsWith(";")) {
            return component.substring(1, component.length() - 1);
        }
        return component;
    }

    /**
     * The primitive spellings {@code Class.getName()} itself produces. A column bound to a
     * primitive is ordinarily boxed by the time jOOQ names its binding type, but the catalog is a
     * consumer's and a fixture's alike, and a name that {@code Class.getName()} can produce is a
     * name this decode has to accept: dropping one to null would type a generated local as nothing.
     */
    private static final Map<String, TypeName> PRIMITIVE_NAMES = Map.of(
        "boolean", TypeName.BOOLEAN, "byte", TypeName.BYTE, "char", TypeName.CHAR,
        "short", TypeName.SHORT, "int", TypeName.INT, "long", TypeName.LONG,
        "float", TypeName.FLOAT, "double", TypeName.DOUBLE, "void", TypeName.VOID);

    /**
     * The JVM's single-letter primitive descriptors, which appear only inside an array: a primitive
     * array keeps its element descriptor where a bare primitive column is spelled by name above.
     */
    private static final Map<String, TypeName> PRIMITIVE_DESCRIPTORS = Map.of(
        "Z", TypeName.BOOLEAN, "B", TypeName.BYTE, "C", TypeName.CHAR, "S", TypeName.SHORT,
        "I", TypeName.INT, "J", TypeName.LONG, "F", TypeName.FLOAT, "D", TypeName.DOUBLE);

    /** The generated jOOQ table class: the declared type of a local holding an alias of the table. */
    public static ClassName tableClass(TableRef table) {
        return ClassName.bestGuess(table.tableClassName());
    }

    /** The generated jOOQ record class this table's rows arrive as. */
    public static ClassName recordClass(TableRef table) {
        return ClassName.bestGuess(table.recordClassName());
    }

    /** The schema's {@code Tables} constants class, through which a column is reached. */
    public static ClassName constantsClass(TableRef table) {
        return ClassName.bestGuess(table.constantsClassName());
    }
}
