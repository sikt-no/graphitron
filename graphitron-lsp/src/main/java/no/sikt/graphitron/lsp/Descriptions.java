package no.sikt.graphitron.lsp;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;

/**
 * Resolves the description text the hover and completion surfaces render for a
 * catalog element, overlaying the source-derived Javadoc from the LSP-owned
 * {@link SourceWalker.Index} onto the catalog's build-derivable fallback.
 *
 * <p>The catalog ({@link CompletionData}) is built on the generator
 * ({@code .class}) cadence and carries only what is recoverable without parsing
 * sources: the jOOQ table's SQL comment, and nothing for columns / services. The
 * source index is refreshed on the {@code .java} cadence. Reading the Javadoc
 * here, at request time, is what keeps hover and goto-definition from reading two
 * different snapshots of the same declaration during a live edit: both consult
 * the same index.
 *
 * <p>Precedence differs per element because the build-derivable fallback differs:
 * a table's SQL comment is more authoritative than the generated class Javadoc
 * (which usually just echoes the table name), so it wins; for columns, classes,
 * and methods the catalog carries no Javadoc, so the source index is the only
 * source and wins whenever present.
 */
public final class Descriptions {

    private Descriptions() {}

    /** Table: the build-derivable SQL comment wins; else the generated class Javadoc. */
    public static String ofTable(CompletionData.Table table, SourceWalker.Index sourceIndex) {
        if (!table.description().isEmpty()) {
            return table.description();
        }
        return classJavadoc(table.classFqn(), sourceIndex);
    }

    /** Column: the field Javadoc from the source index; else the (empty) catalog fallback. */
    public static String ofColumn(
        CompletionData.Table owningTable, CompletionData.Column column, SourceWalker.Index sourceIndex
    ) {
        if (owningTable != null && owningTable.classFqn() != null) {
            var decl = sourceIndex.fields()
                .get(new SourceWalker.FieldKey(owningTable.classFqn(), column.name()));
            if (decl != null && !decl.javadoc().isEmpty()) {
                return decl.javadoc();
            }
        }
        return column.description();
    }

    /** Service / record class: the class Javadoc from the source index; else the catalog fallback. */
    public static String ofClass(CompletionData.ExternalReference ref, SourceWalker.Index sourceIndex) {
        String javadoc = classJavadoc(ref.className(), sourceIndex);
        return javadoc.isEmpty() ? ref.description() : javadoc;
    }

    /** Method: the method Javadoc from the source index; else the catalog fallback. */
    public static String ofMethod(
        CompletionData.ExternalReference ref, CompletionData.Method method, SourceWalker.Index sourceIndex
    ) {
        var decl = sourceIndex.methods()
            .get(new SourceWalker.MethodKey(ref.className(), method.name(), method.parameters().size()));
        if (decl != null && !decl.javadoc().isEmpty()) {
            return decl.javadoc();
        }
        return method.description();
    }

    /**
     * Class Javadoc from the source index for an FQN, or empty when the class is
     * not indexed. Public so the declaration-name hover arm's
     * {@link no.sikt.graphitron.lsp.parsing.DeclTarget.SourceClass} target
     * (record / POJO / standalone-jOOQ backing class) can overlay it, matching
     * where goto-definition jumps for the same coordinate.
     */
    public static String classJavadoc(String fqn, SourceWalker.Index sourceIndex) {
        if (fqn == null) {
            return "";
        }
        var decl = sourceIndex.classes().get(fqn);
        return decl != null ? decl.javadoc() : "";
    }
}
