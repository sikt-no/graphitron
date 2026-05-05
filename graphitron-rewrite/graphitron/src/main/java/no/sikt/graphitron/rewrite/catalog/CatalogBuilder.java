package no.sikt.graphitron.rewrite.catalog;

import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.RewriteContext;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles a {@link CompletionData} snapshot the LSP queries against. Sources
 * tables / columns / FK references from {@link JooqCatalog}, scalar types from
 * the parsed {@link GraphQLSchema}, and the consumer's compiled service /
 * condition / record class FQNs from {@link ClasspathScanner} over
 * {@code <basedir>/target/classes/}.
 *
 * <p>Designed to run hot: a single pass over the jOOQ catalog plus a single
 * pass over the assembled schema's type list. The dev goal calls
 * {@link no.sikt.graphitron.rewrite.GraphQLRewriteGenerator#buildCatalog()}
 * on every classpath-watcher trigger; this class is the workhorse behind
 * that call.
 *
 * <p>Source-location URIs follow the jOOQ Maven plugin's default output
 * layout under {@code <basedir>/target/generated-sources/jooq/}: each
 * table maps to {@code <pkgPath>/tables/<ClassName>.java}, columns share
 * that file (line refinement deferred), and FK references map to
 * {@code <pkgPath>/Keys.java}. URIs that do not exist on disk are reduced
 * to {@link CompletionData.SourceLocation#UNKNOWN} so goto-definition
 * silently no-ops on consumers with non-default jOOQ output paths.
 */
public final class CatalogBuilder {

    private CatalogBuilder() {}

    public static CompletionData build(JooqCatalog jooq, GraphQLSchema assembled, RewriteContext ctx) {
        Path jooqSourceRoot = ctx.basedir().resolve("target/generated-sources/jooq");
        String jooqPkgPath = ctx.jooqPackage().replace('.', '/');
        return new CompletionData(
            buildTables(jooq, jooqSourceRoot, jooqPkgPath),
            buildScalars(assembled),
            buildExternalReferences(ctx)
        );
    }

    /**
     * Class-name candidates for {@code @service} / {@code @condition} /
     * {@code @record} completion. Phase 5 ships only the FQN; the
     * {@code methods} slot stays empty until method enumeration lands.
     */
    private static List<CompletionData.ExternalReference> buildExternalReferences(RewriteContext ctx) {
        Path classesRoot = ctx.basedir().resolve("target/classes");
        return ClasspathScanner.scan(classesRoot, ctx.jooqPackage()).stream()
            .map(fqn -> new CompletionData.ExternalReference(fqn, fqn, "", List.of()))
            .toList();
    }

    private static List<CompletionData.Table> buildTables(
        JooqCatalog jooq, Path sourceRoot, String pkgPath
    ) {
        var tables = new ArrayList<CompletionData.Table>();
        for (String tableName : jooq.allTableSqlNames()) {
            tables.add(buildTable(jooq, tableName, sourceRoot, pkgPath));
        }
        return List.copyOf(tables);
    }

    private static CompletionData.Table buildTable(
        JooqCatalog jooq, String tableName, Path sourceRoot, String pkgPath
    ) {
        Optional<JooqCatalog.TableEntry> entryOpt = jooq.findTable(tableName).asEntry();
        Table<?> jooqTable = entryOpt.map(JooqCatalog.TableEntry::table).orElse(null);

        var tableDefinition = jooqTable == null
            ? CompletionData.SourceLocation.UNKNOWN
            : tableSourceLocation(sourceRoot, pkgPath, jooqTable);

        var columns = jooq.allColumnsOf(tableName).stream()
            .map(c -> new CompletionData.Column(
                c.javaName(),
                c.columnClass(),
                c.nullable(),
                "",
                tableDefinition
            ))
            .toList();

        var references = jooqTable == null
            ? List.<CompletionData.Reference>of()
            : buildReferencesFor(jooq, jooqTable, sourceRoot, pkgPath);

        return new CompletionData.Table(
            tableName,
            commentOf(jooqTable),
            tableDefinition,
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
    private static List<CompletionData.Reference> buildReferencesFor(
        JooqCatalog jooq, Table<?> table, Path sourceRoot, String pkgPath
    ) {
        var keysLocation = keysSourceLocation(sourceRoot, pkgPath);
        var refs = new ArrayList<CompletionData.Reference>();
        for (ForeignKey<?, ?> fk : table.getReferences()) {
            String targetTable = fk.getKey().getTable().getName();
            refs.add(new CompletionData.Reference(targetTable, keyConstant(jooq, fk), false, keysLocation));
        }
        // Inbound: any FK on another table that points at this one.
        String thisName = table.getName();
        for (String otherName : jooq.allTableSqlNames()) {
            if (otherName.equalsIgnoreCase(thisName)) continue;
            Table<?> other = jooq.findTable(otherName).asEntry().map(JooqCatalog.TableEntry::table).orElse(null);
            if (other == null) continue;
            for (ForeignKey<?, ?> fk : other.getReferences()) {
                if (fk.getKey().getTable().getName().equalsIgnoreCase(thisName)) {
                    refs.add(new CompletionData.Reference(otherName, keyConstant(jooq, fk), true, keysLocation));
                }
            }
        }
        return List.copyOf(refs);
    }

    private static CompletionData.SourceLocation tableSourceLocation(
        Path sourceRoot, String pkgPath, Table<?> jooqTable
    ) {
        Path tableFile = sourceRoot
            .resolve(pkgPath)
            .resolve("tables")
            .resolve(jooqTable.getClass().getSimpleName() + ".java");
        return Files.exists(tableFile)
            ? new CompletionData.SourceLocation(tableFile.toUri().toString(), 0, 0)
            : CompletionData.SourceLocation.UNKNOWN;
    }

    private static CompletionData.SourceLocation keysSourceLocation(Path sourceRoot, String pkgPath) {
        Path keysFile = sourceRoot.resolve(pkgPath).resolve("Keys.java");
        return Files.exists(keysFile)
            ? new CompletionData.SourceLocation(keysFile.toUri().toString(), 0, 0)
            : CompletionData.SourceLocation.UNKNOWN;
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
