package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.TableRef;

/**
 * Shared utilities and resolved name records used across all generator classes.
 */
class GeneratorUtils {

    /**
     * The three JavaPoet {@link ClassName}s that every SQL-touching generator method resolves
     * from a {@link TableRef} and the return type name.
     *
     * <ul>
     *   <li>{@link #tablesClass} — jOOQ {@code Tables} constants class
     *       (e.g. {@code no.example.jooq.Tables})</li>
     *   <li>{@link #jooqTableClass} — the concrete jOOQ table class
     *       (e.g. {@code no.example.jooq.tables.Film})</li>
     *   <li>{@link #typeClass} — the generated Graphitron type class
     *       (e.g. {@code no.example.rewrite.types.Film})</li>
     * </ul>
     */
    record ResolvedTableNames(ClassName tablesClass, ClassName jooqTableClass, ClassName typeClass) {

        static ResolvedTableNames of(TableRef tableRef, String returnTypeName) {
            return new ResolvedTableNames(
                ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables"),
                ClassName.get(RewriteConfig.getGeneratedJooqPackage() + ".tables", tableRef.javaClassName()),
                ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", returnTypeName));
        }

        /** Resolves only {@link #tablesClass} and {@link #jooqTableClass} — use when the type class is not needed. */
        static ResolvedTableNames ofTable(TableRef tableRef) {
            return new ResolvedTableNames(
                ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables"),
                ClassName.get(RewriteConfig.getGeneratedJooqPackage() + ".tables", tableRef.javaClassName()),
                null);
        }
    }
}
