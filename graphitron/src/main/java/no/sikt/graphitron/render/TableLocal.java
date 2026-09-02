package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.TableRef;

/**
 * The shared table-local fragment every SQL-composing body reads: the entity-prefixed local name
 * ({@code filmTable}, so the jOOQ table class and the generated projection class can share a
 * simple name without either being import-qualified) and its declaration statement
 * ({@code Film filmTable = Tables.FILM;}). One derivation across the launcher renderer and the
 * legacy fetcher hosts ({@code GeneratorUtils} delegates here), so the fragment cannot fork
 * during the migration window.
 */
public final class TableLocal {

    private TableLocal() {}

    /** The entity-prefixed local name for a jOOQ table class ({@code Film} to {@code filmTable}). */
    public static String name(ClassName jooqTableClass) {
        var simple = jooqTableClass.simpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + "Table";
    }

    /** {@link #name(ClassName)} off the ref's table class. */
    public static String name(TableRef tableRef) {
        return name(CatalogRefs.tableClass(tableRef));
    }

    /** {@code <TableClass> <local> = <Tables>.<FIELD>;} with the caller's resolved class names. */
    public static CodeBlock declare(ClassName jooqTableClass, ClassName constantsClass, String javaFieldName) {
        return CodeBlock.builder()
            .addStatement("$T $L = $T.$L", jooqTableClass, name(jooqTableClass), constantsClass, javaFieldName)
            .build();
    }

    /** {@link #declare(ClassName, ClassName, String)} off the ref's own resolved names. */
    public static CodeBlock declare(TableRef tableRef) {
        return declare(CatalogRefs.tableClass(tableRef), CatalogRefs.constantsClass(tableRef), tableRef.javaFieldName());
    }
}
