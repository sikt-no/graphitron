package no.sikt.graphitron.rewrite.catalog;

import graphql.language.ArrayValue;
import graphql.language.Description;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * Projects the post-merge {@link TypeDefinitionRegistry}'s directive
     * definitions into the {@link LspSchemaSnapshot.Built.Current} shape the
     * LSP consumes through the snapshot side-channel. Pre-conditions: the
     * registry parsed cleanly (callers in {@code GraphQLRewriteGenerator}
     * throw before reaching this method on parse failure) and reflects the
     * full multi-file {@code extend type} merge plus the bundled-directives
     * overlay. No bundled-directive filter is applied; the LSP's
     * {@code DirectiveResolution} encodes bundled-shadows-snapshot
     * precedence so redundant entries are observationally invisible.
     */
    @LoadBearingClassifierCheck(
        key = "snapshot-built-implies-clean-parse",
        description = "Only invoked on a clean post-merge registry; parse failures throw upstream "
            + "in GraphQLRewriteGenerator before reaching this site, so a Built.Current snapshot "
            + "never reflects a partial parse."
    )
    @LoadBearingClassifierCheck(
        key = "snapshot-directive-roundtrip-faithful",
        description = "Every DirectiveDefinition in the input registry produces exactly one "
            + "DirectiveShape with the same name; the LSP's unknown-directive arm and phase-2 "
            + "arg/hover consumers rely on round-trip faithfulness."
    )
    public static LspSchemaSnapshot.Built.Current buildSnapshot(TypeDefinitionRegistry registry) {
        return buildSnapshot(registry, null, null);
    }

    /**
     * Full projection: directive surface plus per-type backing shapes. The
     * three-arg form is what the production pipeline calls; the
     * {@link #buildSnapshot(TypeDefinitionRegistry)} overload exists so unit
     * tests of the directive arm can run without spinning up the full
     * classifier + jOOQ catalog.
     *
     * <p>When {@code schema} or {@code catalog} is {@code null} the
     * type-backing map is empty (back-compat for the one-arg overload only).
     */
    @LoadBearingClassifierCheck(
        key = "java-record-type-backs-record-class",
        description = "A classifier-produced JavaRecordType / JavaRecordInputType implies the "
            + "backing class is a Java record on the consumer's classpath. The LSP's "
            + "@field(name:)-on-record arms (FieldCompletions / Diagnostics / Hovers) consume the "
            + "Record attribute components without re-checking that the class is in fact a record; "
            + "silent classifier widening would degrade completions instead of failing loudly."
    )
    public static LspSchemaSnapshot.Built.Current buildSnapshot(
        TypeDefinitionRegistry registry, GraphitronSchema schema, CompletionData catalog
    ) {
        var directives = new ArrayList<DirectiveShape>();
        for (var def : registry.getDirectiveDefinitions().values()) {
            directives.add(new DirectiveShape(
                def.getName(),
                projectInputValues(def.getInputValueDefinitions()),
                descriptionOf(def.getDescription())
            ));
        }
        var typesByName = (schema == null || catalog == null)
            ? Map.<String, TypeBackingShape>of()
            : projectTypesByName(schema, catalog);
        return new LspSchemaSnapshot.Built.Current(directives, typesByName);
    }

    /**
     * Walks the lifted {@link GraphitronSchema} and projects each typed
     * variant into a {@link TypeBackingShape}. The dispatch is exhaustive on
     * the {@code GraphitronType} sealed permits, so any future variant trips
     * a compile error here. Catalog-side data ({@link CompletionData#externalReferences})
     * supplies the record-component / accessor-method lists; the projector
     * itself does no class-file reading.
     */
    private static Map<String, TypeBackingShape> projectTypesByName(
        GraphitronSchema schema, CompletionData catalog
    ) {
        var out = new LinkedHashMap<String, TypeBackingShape>();
        for (var entry : schema.types().entrySet()) {
            out.put(entry.getKey(), projectType(entry.getValue(), catalog));
        }
        return Map.copyOf(out);
    }

    private static TypeBackingShape projectType(GraphitronType type, CompletionData catalog) {
        return switch (type) {
            case GraphitronType.JavaRecordType t -> projectRecord(t.fqClassName(), catalog);
            case GraphitronType.JavaRecordInputType t -> projectRecord(t.fqClassName(), catalog);
            case GraphitronType.PojoResultType.Backed t -> projectPojo(t.fqClassName(), catalog);
            case GraphitronType.PojoInputType t -> t.fqClassName() == null
                ? new TypeBackingShape.NoBacking.UnbackedResult()
                : projectPojo(t.fqClassName(), catalog);
            case GraphitronType.JooqRecordType t -> new TypeBackingShape.JooqRecordBacking(t.fqClassName(), null);
            case GraphitronType.JooqRecordInputType t -> new TypeBackingShape.JooqRecordBacking(t.fqClassName(), null);
            case GraphitronType.JooqTableRecordType t -> new TypeBackingShape.JooqRecordBacking(t.fqClassName(), tableNameOf(t.table()));
            case GraphitronType.JooqTableRecordInputType t -> new TypeBackingShape.JooqRecordBacking(t.fqClassName(), tableNameOf(t.table()));
            case GraphitronType.TableType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.NodeType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.TableInterfaceType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.TableInputType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.RootType ignored -> new TypeBackingShape.NoBacking.Root();
            case GraphitronType.InterfaceType ignored -> new TypeBackingShape.NoBacking.UnclassifiedInterface();
            case GraphitronType.UnionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.PojoResultType.NoBacking ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ErrorType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EnumType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ScalarType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ConnectionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EdgeType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.PageInfoType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.PlainObjectType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.UnclassifiedType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
        };
    }

    private static String tableNameOf(TableRef ref) {
        return ref == null ? null : ref.tableName();
    }

    private static TypeBackingShape projectRecord(String fqClassName, CompletionData catalog) {
        var slots = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqClassName))
            .findFirst()
            .map(r -> r.recordComponents().stream()
                .map(rc -> new TypeBackingShape.MemberSlot(rc.name(), rc.displayType()))
                .toList())
            .orElse(List.of());
        return new TypeBackingShape.RecordBacking(fqClassName, slots);
    }

    private static TypeBackingShape projectPojo(String fqClassName, CompletionData catalog) {
        var ref = catalog.externalReferences().stream()
            .filter(r -> r.className().equals(fqClassName))
            .findFirst();
        if (ref.isEmpty()) {
            return new TypeBackingShape.PojoBacking(fqClassName, List.of());
        }
        var accessors = new ArrayList<TypeBackingShape.MemberSlot>();
        for (var method : ref.get().methods()) {
            if (!method.parameters().isEmpty()) continue;
            var slot = beanAccessorSlot(method);
            if (slot != null) accessors.add(slot);
        }
        return new TypeBackingShape.PojoBacking(fqClassName, accessors);
    }

    private static TypeBackingShape.MemberSlot beanAccessorSlot(CompletionData.Method method) {
        String name = method.name();
        String field;
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            field = lowercaseFirst(name.substring(3));
        } else if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            field = lowercaseFirst(name.substring(2));
        } else {
            return null;
        }
        return new TypeBackingShape.MemberSlot(field, method.returnType());
    }

    private static String lowercaseFirst(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static List<InputValueShape> projectInputValues(List<InputValueDefinition> defs) {
        var shapes = new ArrayList<InputValueShape>();
        for (var def : defs) {
            shapes.add(new InputValueShape(
                def.getName(),
                projectType(def.getType()),
                descriptionOf(def.getDescription())
            ));
        }
        return shapes;
    }

    private static TypeShape projectType(Type<?> type) {
        return projectType(type, false);
    }

    private static TypeShape projectType(Type<?> type, boolean nonNull) {
        if (type instanceof NonNullType nn) {
            return projectType(nn.getType(), true);
        }
        if (type instanceof ListType lt) {
            return new TypeShape.List(projectType(lt.getType(), false), nonNull);
        }
        if (type instanceof TypeName tn) {
            return new TypeShape.Named(tn.getName(), nonNull);
        }
        throw new IllegalStateException("Unexpected graphql-java type node: " + type.getClass());
    }

    private static Optional<String> descriptionOf(Description description) {
        if (description == null) return Optional.empty();
        var content = description.getContent();
        return content == null || content.isEmpty() ? Optional.empty() : Optional.of(content);
    }

    public static CompletionData build(JooqCatalog jooq, GraphQLSchema assembled, RewriteContext ctx) {
        Path jooqSourceRoot = ctx.basedir().resolve("target/generated-sources/jooq");
        String jooqPkgPath = ctx.jooqPackage().replace('.', '/');
        return new CompletionData(
            buildTables(jooq, jooqSourceRoot, jooqPkgPath),
            buildScalars(assembled),
            buildExternalReferences(ctx),
            ctx.namedReferences(),
            buildNodeMetadata(assembled)
        );
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
     */
    private static Map<String, CompletionData.NodeMetadata> buildNodeMetadata(GraphQLSchema assembled) {
        var out = new LinkedHashMap<String, CompletionData.NodeMetadata>();
        for (var type : assembled.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType obj)) continue;
            GraphQLAppliedDirective node = obj.getAppliedDirective("node");
            if (node == null) continue;
            out.put(obj.getName(), new CompletionData.NodeMetadata(
                readStringArg(node, "typeId"),
                readStringListArg(node, "keyColumns")
            ));
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
            for (var v : av.getValues()) {
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
     * <p>Reads from {@link RewriteContext#classpathRoots()} — every reactor
     * project's compile-output directory, populated by the mojo from
     * {@code MavenSession.getAllProjects()}. Falls back to {@code
     * <basedir>/target/classes} as a single-root default when the context
     * carries no classpathRoots, so unit-tier callers built off
     * {@link RewriteContext}'s six-arg overload still get the same scope
     * pre-multi-module support shipped.
     */
    private static List<CompletionData.ExternalReference> buildExternalReferences(RewriteContext ctx) {
        var roots = ctx.classpathRoots().isEmpty()
            ? List.of(ctx.basedir().resolve("target/classes"))
            : ctx.classpathRoots();
        return ClasspathScanner.scan(roots, ctx.jooqPackage());
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
