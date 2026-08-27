package no.sikt.graphitron.rewrite.catalog;

import graphql.language.ArrayValue;
import graphql.language.Description;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.derive.AuthoredClaimConflicts;
import no.sikt.graphitron.rewrite.derive.FieldClaim;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.RoutineResolution;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * {@link no.sikt.graphitron.rewrite.GraphQLRewriteGenerator#buildOutput()}
 * on every classpath-watcher trigger; this class is the workhorse behind
 * that call.
 *
 * <p>The catalog carries the generated jOOQ table class FQN on each
 * {@link CompletionData.Table} and the {@code Keys} class FQN on each
 * {@link CompletionData.Reference}, but no source positions: the LSP joins those
 * FQNs against the store's {@code java_} family at request time, so jOOQ
 * goto-definition / hover ride the {@code .java} source cadence rather than the
 * generator build cadence. This builder does not walk sources: the
 * {@code description} slots carry the build-derivable fallback only (the table's
 * SQL comment; empty for columns and services), and the LSP overlays the source
 * Javadoc when the store has it.
 */
public final class CatalogBuilder {

    private CatalogBuilder() {}

    /**
     * Walks the lifted {@link GraphitronSchema} and projects each typed
     * variant into a {@link TypeBackingShape}. The dispatch is exhaustive on
     * the {@code GraphitronType} sealed permits, so any future variant trips
     * a compile error here. Each shape names what backs the type and nothing else: what a class
     * offers a member name is a fact about the class, which its consumers read from the store's
     * member-slot relation, so no member list is projected here and the bean rule has one home.
     *
     * <p>Public because its only reader is elsewhere: the walk's backing-class transcription
     * ({@link no.sikt.graphitron.rewrite.derive.TypeBackingClasses}) reduces this projection to
     * the class each shape names, and writes it as the shadow the store-native backing derivation
     * differs against. The snapshot carried this map to the language server until every surface
     * that read it asked the store instead; what the walk decided is still worth stating once, so
     * the switch survives its shipping channel.
     */
    public static Map<String, TypeBackingShape> projectTypesByName(GraphitronSchema schema) {
        var out = new LinkedHashMap<String, TypeBackingShape>();
        for (var entry : schema.types().entrySet()) {
            out.put(entry.getKey(), projectType(entry.getValue()));
        }
        return Map.copyOf(out);
    }

    private static TypeBackingShape projectType(GraphitronType type) {
        return switch (type) {
            case GraphitronType.JavaRecordType t -> new TypeBackingShape.RecordBacking(t.fqClassName());
            case GraphitronType.JavaRecordInputType t -> new TypeBackingShape.RecordBacking(t.fqClassName());
            case GraphitronType.PojoResultType.Backed t -> new TypeBackingShape.PojoBacking(t.fqClassName());
            case GraphitronType.PojoInputType t -> t.fqClassName() == null
                ? new TypeBackingShape.NoBacking.UnbackedResult()
                : new TypeBackingShape.PojoBacking(t.fqClassName());
            case GraphitronType.JooqRecordType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqRecordInputType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqTableRecordType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.JooqTableRecordInputType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.TableType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.NodeType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.TableInterfaceType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.RootType ignored -> new TypeBackingShape.NoBacking.Root();
            case GraphitronType.InterfaceType ignored -> new TypeBackingShape.NoBacking.UnclassifiedInterface();
            case GraphitronType.UnionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ErrorType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EnumType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ScalarType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ConnectionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EdgeType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.PageInfoType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.FacetsType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.FacetValueType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.NestingType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.UnclassifiedType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
        };
    }

    private static String tableNameOf(TableRef ref) {
        return ref == null ? null : ref.tableName();
    }

    private static TypeBackingShape jooqRecordWithTable(String fqClassName, TableRef table) {
        String tableName = tableNameOf(table);
        return tableName == null
            ? new TypeBackingShape.JooqRecordBacking.Standalone(fqClassName)
            : new TypeBackingShape.JooqRecordBacking.WithTable(fqClassName, tableName);
    }

    /**
     * The catalog over a classpath census this builder scans for itself. Kept for the test sites
     * that have no census in hand and no reason to scan one deliberately; production goes through
     * the overload below, which is what makes "one census per pass" structural rather than
     * incidental.
     */
    public static CompletionData build(JooqCatalog jooq, GraphQLSchema assembled, RewriteContext ctx) {
        return build(jooq, assembled, ctx, buildExternalReferences(ctx));
    }

    /**
     * The catalog over a census the caller already scanned. The census arm is the load-bearing one:
     * the fact store's classpath families are written from the same scan, so a pass that let this
     * builder scan its own would parse every consumer class twice and could disagree with the store
     * about what is on the classpath.
     */
    public static CompletionData build(JooqCatalog jooq, GraphQLSchema assembled, RewriteContext ctx,
                                       List<CompletionData.ExternalReference> census) {
        // FQN of the generated jOOQ Keys class (jOOQ emits it at the package
        // root). Both the table classFqn and this Keys FQN are the join keys the
        // LSP resolves against its source index at request time; the catalog
        // carries no source positions and no source-derived Javadoc.
        String keysClassFqn = ctx.jooqPackage() + ".Keys";
        // No source walk here: goto-definition and hover both resolve positions
        // and Javadoc from the LSP-owned source index on the .java cadence. The
        // descriptions this builder sets are the build-derivable fallback only
        // (the jOOQ table's SQL comment; nothing for columns / services), which
        // the LSP overlays the source Javadoc onto when its index has it.
        return new CompletionData(
            buildTables(jooq, keysClassFqn),
            buildScalars(assembled),
            census,
            buildNodeMetadata(assembled, new NodeDeclaration(jooq))
        );
    }

    /** Empty for {@code null} / blank so absent comments degrade to omitted, not empty-string-valued. */
    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * Walks every {@code GraphQLObjectType} in {@code assembled} and records
     * pre-deduction values from each one's {@code @node} directive. Presence
     * in the returned map is the predicate the LSP's {@code @nodeId(typeName:)}
     * arms read; missing axes (the author omitted {@code typeId:} or
     * {@code keyColumns:}) stay null and are not back-filled with classifier
     * deductions. The LSP intentionally operates on author-supplied data only;
     * cases where {@code typeId} or {@code keyColumns} are deduced by the
     * classifier (containing-type / unique-table / PK inference) are invisible
     * to in-editor feedback by design.
     *
     * <p>Those two facts pull in opposite directions once nodehood can be inferred, so they are
     * separated here: <em>presence</em> follows {@link NodeDeclaration}, so a node inferred from
     * {@code implements Node} plus catalog metadata is a node to the LSP exactly as it is to the
     * classifier, while the <em>values</em> stay author-supplied and both axes read null for it.
     * Presence is the predicate; keeping it on the directive would make the editor reject
     * {@code @nodeId(typeName:)} against a type the build accepts.
     */
    private static Map<String, CompletionData.NodeMetadata> buildNodeMetadata(
        GraphQLSchema assembled, NodeDeclaration nodes
    ) {
        var out = new LinkedHashMap<String, CompletionData.NodeMetadata>();
        for (var type : assembled.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType obj)) continue;
            if (!nodes.isNodeType(obj)) continue;
            GraphQLAppliedDirective node = obj.getAppliedDirective("node");
            out.put(obj.getName(), node == null
                ? new CompletionData.NodeMetadata(null, null)
                : new CompletionData.NodeMetadata(
                    readStringArg(node, "typeId"),
                    readStringListArg(node, "keyColumns")));
        }
        return Map.copyOf(out);
    }

    private static String readStringArg(GraphQLAppliedDirective directive, String argName) {
        GraphQLAppliedDirectiveArgument arg = directive.getArgument(argName);
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof StringValue sv) return sv.getValue();
        if (value instanceof String s) return s;
        return null;
    }

    private static List<String> readStringListArg(GraphQLAppliedDirective directive, String argName) {
        GraphQLAppliedDirectiveArgument arg = directive.getArgument(argName);
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof ArrayValue av) {
            var list = new ArrayList<String>(av.getValues().size());
            for (Value<?> v : av.getValues()) {
                if (v instanceof NullValue) {
                    list.add(null);
                } else if (v instanceof StringValue sv) {
                    list.add(sv.getValue());
                }
            }
            return List.copyOf(list);
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<String>(list.size());
            for (var v : list) {
                out.add(v == null ? null : v.toString());
            }
            return List.copyOf(out);
        }
        if (value instanceof StringValue sv) return List.of(sv.getValue());
        if (value instanceof String s) return List.of(s);
        return null;
    }

    /**
     * Class-name candidates for {@code @service} / {@code @condition} /
     * {@code @record} completion, with public methods of each populated
     * straight off the classfile (parameter names included when the
     * consumer compiled with {@code -parameters}).
     *
     * <p>Reads from {@link RewriteContext#classpathRoots()}: every reactor
     * project's compile-output directory, populated by the mojo from
     * {@code MavenSession.getAllProjects()}. Falls back to {@code
     * <basedir>/target/classes} as a single-root default when the context
     * carries no classpathRoots, so unit-tier callers built off
     * {@link RewriteContext}'s six-arg overload get the single-root scope.
     *
     * <p>Public because the capture load reads the same census on its own to fill the store's
     * {@code extension_} family; {@link #build} keeps reading it as one part of the LSP catalog.
     */
    public static List<CompletionData.ExternalReference> buildExternalReferences(RewriteContext ctx) {
        var roots = ctx.classpathRoots().isEmpty()
            ? List.of(no.sikt.graphitron.rewrite.ClasspathEntry.project(
                ctx.basedir().resolve("target/classes")))
            : ctx.classpathRoots();
        // Bytecode-derived structure only; the class / method Javadoc the hover
        // path renders is overlaid from the LSP source index at request time.
        return ClasspathScanner.scan(roots, ctx.jooqPackage());
    }

    private static List<CompletionData.Table> buildTables(JooqCatalog jooq, String keysClassFqn) {
        var tables = new ArrayList<CompletionData.Table>();
        for (String tableName : jooq.allTableSqlNames()) {
            tables.add(buildTable(jooq, tableName, keysClassFqn));
        }
        return List.copyOf(tables);
    }

    private static CompletionData.Table buildTable(JooqCatalog jooq, String tableName, String keysClassFqn) {
        Optional<JooqCatalog.TableEntry> entryOpt = jooq.findTable(tableName).asEntry();
        Table<?> jooqTable = entryOpt.map(JooqCatalog.TableEntry::table).orElse(null);

        // Fully-qualified name of the generated jOOQ table class, e.g.
        // <jooqPackage>.tables.Film; the LSP keys class / field declarations by
        // this FQN when it resolves goto-definition / hover against the source index.
        String classFqn = jooqTable == null ? null : jooqTable.getClass().getName();

        // Build-derivable description only: the jOOQ table's SQL comment. The
        // generated class Javadoc (and column / service Javadoc) is overlaid from
        // the source index by the LSP, so it rides the .java cadence.
        String tableDescription = commentOf(jooqTable);

        var columns = jooq.allColumnsOf(tableName).stream()
            .map(c -> buildColumn(c))
            .toList();

        var references = jooqTable == null
            ? List.<CompletionData.Reference>of()
            : buildReferencesFor(jooq, jooqTable, keysClassFqn);

        return new CompletionData.Table(
            tableName,
            tableDescription,
            classFqn,
            columns,
            references
        );
    }

    /**
     * Builds one column from the jOOQ catalog structure. Carries no source
     * position and no source-derived Javadoc: goto-definition and hover both
     * join {@code (owning-table classFqn, name)} against the LSP-owned source
     * index at request time. The {@code description} is the build-derivable
     * fallback, which for a column is empty: the {@link JooqCatalog.ColumnEntry}
     * shape this reads carries no comment. Not because a column comment is
     * unreachable, which it is not; {@link JooqCatalog#columnFactsOf} reads it off
     * the live field for the catalog-discovery projection and the store census
     * captures it the same way. Hover prefers the source Javadoc the index owns,
     * so this shape never asked for the database's comment.
     */
    private static CompletionData.Column buildColumn(JooqCatalog.ColumnEntry c) {
        return new CompletionData.Column(
            c.javaName(),
            c.columnClass(),
            c.nullable(),
            ""
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
        JooqCatalog jooq, Table<?> table, String keysClassFqn
    ) {
        var refs = new ArrayList<CompletionData.Reference>();
        for (ForeignKey<?, ?> fk : table.getReferences()) {
            String targetTable = fk.getKey().getTable().getName();
            refs.add(new CompletionData.Reference(targetTable, keyConstant(jooq, fk), false, keysClassFqn));
        }
        // Inbound: any FK on another table that points at this one.
        String thisName = table.getName();
        for (String otherName : jooq.allTableSqlNames()) {
            if (otherName.equalsIgnoreCase(thisName)) continue;
            Table<?> other = jooq.findTable(otherName).asEntry().map(JooqCatalog.TableEntry::table).orElse(null);
            if (other == null) continue;
            for (ForeignKey<?, ?> fk : other.getReferences()) {
                if (fk.getKey().getTable().getName().equalsIgnoreCase(thisName)) {
                    refs.add(new CompletionData.Reference(otherName, keyConstant(jooq, fk), true, keysClassFqn));
                }
            }
        }
        return List.copyOf(refs);
    }

    private static String keyConstant(JooqCatalog jooq, ForeignKey<?, ?> fk) {
        return jooq.fkJavaConstantName(fk).orElse(fk.getName());
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
