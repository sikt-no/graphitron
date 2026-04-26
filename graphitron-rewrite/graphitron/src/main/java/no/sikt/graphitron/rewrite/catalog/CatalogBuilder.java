package no.sikt.graphitron.rewrite.catalog;

import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles a {@link CompletionData} snapshot the LSP queries against. Sources
 * tables / columns / FK references from {@link JooqCatalog} and scalar types
 * from the parsed {@link GraphQLSchema}; everything else stays empty in
 * Phase 2 (service methods need their own enumeration source, picked up in
 * Phase 5).
 *
 * <p>Designed to run hot: a single pass over the jOOQ catalog plus a single
 * pass over the assembled schema's type list. The dev goal calls
 * {@link no.sikt.graphitron.rewrite.GraphQLRewriteGenerator#buildCatalog()}
 * on every classpath-watcher trigger; this class is the workhorse behind
 * that call.
 */
public final class CatalogBuilder {

    private CatalogBuilder() {}

    public static CompletionData build(JooqCatalog jooq, GraphQLSchema assembled) {
        return new CompletionData(
            buildTables(jooq),
            buildScalars(assembled),
            List.of()
        );
    }

    private static List<CompletionData.Table> buildTables(JooqCatalog jooq) {
        var tables = new ArrayList<CompletionData.Table>();
        for (String tableName : jooq.allTableSqlNames()) {
            tables.add(buildTable(jooq, tableName));
        }
        return List.copyOf(tables);
    }

    private static CompletionData.Table buildTable(JooqCatalog jooq, String tableName) {
        Optional<JooqCatalog.TableEntry> entryOpt = jooq.findTable(tableName);
        Table<?> jooqTable = entryOpt.map(JooqCatalog.TableEntry::table).orElse(null);

        var columns = jooq.allColumnsOf(tableName).stream()
            .map(c -> new CompletionData.Column(
                c.sqlName(),
                c.columnClass(),
                c.nullable(),
                ""
            ))
            .toList();

        var references = jooqTable == null
            ? List.<CompletionData.Reference>of()
            : buildReferencesFor(jooq, jooqTable);

        return new CompletionData.Table(
            tableName,
            commentOf(jooqTable),
            CompletionData.SourceLocation.UNKNOWN,
            columns,
            references
        );
    }

    /**
     * Outbound + inbound foreign-key references for a single table. The
     * {@code keyName} stored on each reference is the jOOQ-generated Java
     * constant on the {@code Keys} class (e.g. {@code FILM__FILM_LANGUAGE_ID_FKEY}),
     * which is the format the Rust LSP's existing matchers expect; the SQL
     * constraint name is the fallback when the {@code Keys} class is not
     * resolvable.
     */
    private static List<CompletionData.Reference> buildReferencesFor(JooqCatalog jooq, Table<?> table) {
        var refs = new ArrayList<CompletionData.Reference>();
        for (ForeignKey<?, ?> fk : table.getReferences()) {
            String targetTable = fk.getKey().getTable().getName();
            refs.add(new CompletionData.Reference(targetTable, keyConstant(jooq, fk), false));
        }
        // Inbound: any FK on another table that points at this one.
        String thisName = table.getName();
        for (String otherName : jooq.allTableSqlNames()) {
            if (otherName.equalsIgnoreCase(thisName)) continue;
            Table<?> other = jooq.findTable(otherName).map(JooqCatalog.TableEntry::table).orElse(null);
            if (other == null) continue;
            for (ForeignKey<?, ?> fk : other.getReferences()) {
                if (fk.getKey().getTable().getName().equalsIgnoreCase(thisName)) {
                    refs.add(new CompletionData.Reference(otherName, keyConstant(jooq, fk), true));
                }
            }
        }
        return List.copyOf(refs);
    }

    private static String keyConstant(JooqCatalog jooq, ForeignKey<?, ?> fk) {
        return jooq.fkJavaConstantName(fk.getName()).orElse(fk.getName());
    }

    private static String commentOf(Table<?> table) {
        if (table == null) return "";
        String comment = table.getComment();
        return comment == null ? "" : comment;
    }

    private static List<CompletionData.TypeData> buildScalars(GraphQLSchema assembled) {
        return assembled.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLScalarType)
            .map(t -> (GraphQLScalarType) t)
            .filter(t -> !t.getName().startsWith("__"))
            .map(CatalogBuilder::toTypeData)
            .toList();
    }

    private static CompletionData.TypeData toTypeData(GraphQLScalarType s) {
        String description = s.getDescription();
        return new CompletionData.TypeData(
            s.getName(),
            List.of(),
            description == null ? "" : description,
            sourceLocation(s)
        );
    }

    private static CompletionData.SourceLocation sourceLocation(GraphQLScalarType s) {
        var def = s.getDefinition();
        if (def == null || def.getSourceLocation() == null) {
            return CompletionData.SourceLocation.UNKNOWN;
        }
        var loc = def.getSourceLocation();
        String uri = loc.getSourceName() == null ? "" : "file://" + loc.getSourceName();
        return new CompletionData.SourceLocation(uri, loc.getLine(), loc.getColumn());
    }
}
