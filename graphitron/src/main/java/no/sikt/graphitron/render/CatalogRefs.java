package no.sikt.graphitron.render;

import no.sikt.graphitron.command.CatalogColumn;
import no.sikt.graphitron.command.CatalogTable;
import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;

import java.util.List;

/**
 * The lift from a command row's captured catalog names into the emit library's types: the one
 * place a {@link CatalogTable} or {@link CatalogColumn} becomes something javapoet can spell.
 *
 * <p>This class is the whole of what the command tier's name-carrying costs. A row states which
 * class a table is generated as, because that is a fact about the consumer's catalog and the store
 * holds it; how that name is written into a source file is an emit decision, and this is where it
 * is made. Splitting it that way is what lets a producer build a row without the emit library and
 * without a live catalog, which is the property the plan tier is shaped around.
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
     * lifts through {@link #columnType}; the two spellings are told apart by the carrier rather
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
        return ColumnRef.decodeBindingType(column.javaTypeName());
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
}
