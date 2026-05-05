package no.sikt.graphitron.rewrite;

import graphql.language.ArrayValue;
import graphql.schema.GraphQLType;
import graphql.language.BooleanValue;
import graphql.language.NullValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.ConditionJoin;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import org.jooq.ForeignKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared build-time state and stateless utilities used by {@link TypeBuilder},
 * {@link FieldBuilder}, and {@link ServiceCatalog}.
 *
 * <p>{@link #types} is {@code null} until {@link TypeBuilder#buildTypes()} completes its first
 * pass and sets it. All code that reads {@code types} is called only after that point.
 */
class BuildContext {

    private static final Logger NODE_ID_SHIM_LOGGER = LoggerFactory.getLogger(BuildContext.class);
    private static final Logger ID_REF_SHIM_LOGGER =
        LoggerFactory.getLogger(BuildContext.class.getName() + ".idRefShim");

    // ===== Directive names =====

    static final String DIR_TABLE               = "table";
    static final String DIR_RECORD              = "record";
    static final String DIR_DISCRIMINATE        = "discriminate";
    static final String DIR_NODE                = "node";
    static final String DIR_NOT_GENERATED       = "notGenerated";
    static final String DIR_MULTITABLE_REFERENCE = "multitableReference";
    static final String DIR_NODE_ID             = "nodeId";
    static final String DIR_FIELD               = "field";
    static final String DIR_REFERENCE           = "reference";
    static final String DIR_ERROR               = "error";
    static final String DIR_TABLE_METHOD        = "tableMethod";
    static final String DIR_DEFAULT_ORDER       = "defaultOrder";
    static final String DIR_SPLIT_QUERY         = "splitQuery";
    static final String DIR_SERVICE             = "service";
    static final String DIR_EXTERNAL_FIELD      = "externalField";
    static final String DIR_LOOKUP_KEY          = "lookupKey";
    static final String DIR_ORDER_BY            = "orderBy";
    static final String DIR_CONDITION           = "condition";
    static final String DIR_MUTATION            = "mutation";
    static final String DIR_DISCRIMINATOR       = "discriminator";
    static final String DIR_AS_CONNECTION       = "asConnection";
    static final String DIR_BATCH_KEY_LIFTER    = "batchKeyLifter";

    // ===== Argument names =====

    static final String ARG_CONTEXT_ARGUMENTS  = "contextArguments";
    static final String ARG_RECORD             = "record";
    static final String ARG_SERVICE_REF        = "service";
    static final String ARG_TABLE_METHOD_REF   = "tableMethodReference";
    static final String ARG_EXTERNAL_FIELD_REF = "reference";
    static final String ARG_METHOD             = "method";
    static final String ARG_ARG_MAPPING        = "argMapping";
    static final String ARG_VALUE              = "value";
    static final String ARG_NAME               = "name";
    static final String ARG_ON                 = "on";
    static final String ARG_TYPE_ID            = "typeId";
    static final String ARG_KEY_COLUMNS        = "keyColumns";
    static final String ARG_TYPE_NAME          = "typeName";
    static final String ARG_PATH               = "path";
    static final String ARG_KEY                = "key";
    static final String ARG_CONDITION          = "condition";
    static final String ARG_TABLE_REF          = "table";
    static final String ARG_INDEX              = "index";
    static final String ARG_FIELDS             = "fields";
    static final String ARG_PRIMARY_KEY        = "primaryKey";
    static final String ARG_DIRECTION          = "direction";
    static final String ARG_COLLATE            = "collate";
    static final String ARG_HANDLERS           = "handlers";
    static final String ARG_HANDLER            = "handler";
    static final String ARG_CLASS_NAME         = "className";
    static final String ARG_CODE               = "code";
    static final String ARG_SQL_STATE          = "sqlState";
    static final String ARG_MATCHES            = "matches";
    static final String ARG_DESCRIPTION        = "description";
    static final String ARG_DEFAULT_FIRST_VALUE = "defaultFirstValue";
    static final String ARG_CONNECTION_NAME     = "connectionName";
    static final String ARG_OVERRIDE            = "override";
    static final String ARG_LIFTER              = "lifter";
    static final String ARG_TARGET_COLUMNS      = "targetColumns";

    // ===== Shared state =====

    final GraphQLSchema schema;
    final JooqCatalog catalog;
    final RewriteContext ctx;
    /**
     * Populated by {@link TypeBuilder#buildTypes()} at the end of its first pass.
     * All accesses after that point are safe; nothing reads it before it is set.
     */
    Map<String, GraphitronType> types;
    /**
     * Set by {@link GraphitronSchemaBuilder} immediately after constructing {@link ServiceCatalog}.
     * Used by {@link #resolveConditionRef} for condition-join method reflection.
     */
    ServiceCatalog svc;

    /**
     * Non-fatal advisories collected during classification. Surfaced to the Maven log by
     * the plugin's validate / generate mojos; never fail the build. See {@link BuildWarning}.
     */
    private final List<BuildWarning> warnings = new ArrayList<>();
    private final Map<String, List<String>> typeNamesByTableKey;
    private final NodeIdLeafResolver nodeIdLeafResolver;

    BuildContext(GraphQLSchema schema, JooqCatalog catalog, RewriteContext ctx) {
        this.schema = schema;
        this.catalog = catalog;
        this.ctx = ctx;
        this.typeNamesByTableKey = buildTypeNamesByTableKey(schema);
        this.nodeIdLeafResolver = new NodeIdLeafResolver(this);
    }

    /**
     * Resolver for {@code @nodeId} leaf shape (same-table lookup vs FK-target filter). Constructed
     * once per {@code BuildContext} and shared by both {@link #classifyInputField} (input-field
     * leaves) and {@link FieldBuilder#classifyArgument} (top-level argument leaves) so the
     * shape decision lives in one place.
     */
    NodeIdLeafResolver nodeIdLeafResolver() {
        return nodeIdLeafResolver;
    }

    RewriteContext ctx() {
        return ctx;
    }

    void addWarning(BuildWarning warning) {
        warnings.add(warning);
    }

    List<BuildWarning> warnings() {
        return List.copyOf(warnings);
    }

    // ===== Directive-reading helpers =====

    /**
     * Returns the stripped String value of an applied directive argument, if present.
     */
    static Optional<String> argString(GraphQLDirectiveContainer container, String directive, String arg) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return Optional.empty();
        var argument = dir.getArgument(arg);
        if (argument == null) return Optional.empty();
        Object value = argument.getValue();
        if (value instanceof StringValue sv) return Optional.of(sv.getValue().strip());
        if (value instanceof String s) return Optional.of(s.strip());
        return Optional.empty();
    }

    /**
     * Returns the String values of a list applied-directive argument, or an empty list if absent.
     */
    static List<String> argStringList(GraphQLDirectiveContainer container, String directive, String arg) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return List.of();
        var argument = dir.getArgument(arg);
        if (argument == null) return List.of();
        Object value = argument.getValue();
        if (value instanceof StringValue sv) return List.of(sv.getValue().strip());
        if (value instanceof String s) return List.of(s.strip());
        if (value instanceof ArrayValue av) {
            return av.getValues().stream()
                .map(v -> v instanceof NullValue ? null : ((StringValue) v).getValue().strip())
                .toList();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(v -> v == null ? null : v.toString().strip())
                .toList();
        }
        return List.of();
    }

    /**
     * Returns the boolean value of an applied directive argument, handling both AST literal form
     * ({@link BooleanValue}) and coerced form ({@link Boolean}). Returns {@code defaultValue} if
     * the directive or argument is absent, or the value cannot be interpreted as a boolean.
     */
    static boolean argBoolean(GraphQLDirectiveContainer container, String directive, String arg, boolean defaultValue) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return defaultValue;
        var argument = dir.getArgument(arg);
        if (argument == null) return defaultValue;
        Object value = argument.getValue();
        if (value instanceof BooleanValue bv) return bv.isValue();
        if (value instanceof Boolean b) return b;
        return defaultValue;
    }

    /**
     * Casts an object to {@code Map<String, Object>}. Used when processing input object values
     * returned by graphql-java after directive coercion.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    // ===== Source-location helpers =====

    static SourceLocation locationOf(GraphQLObjectType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLInterfaceType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLUnionType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLFieldDefinition field) {
        var def = field.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLInputObjectField field) {
        var def = field.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLInputObjectType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    /** Dispatches to the correct typed overload for any {@link GraphQLNamedType}. */
    static SourceLocation locationOf(GraphQLNamedType namedType) {
        return switch (namedType) {
            case GraphQLObjectType t    -> locationOf(t);
            case GraphQLInterfaceType t -> locationOf(t);
            case GraphQLUnionType t     -> locationOf(t);
            default                     -> null;
        };
    }

    // ===== Connection-type helpers =====

    /**
     * Returns {@code true} when {@code typeName} refers to a Relay connection type — i.e. an object
     * type whose {@code edges} field's element type has a {@code node} field.
     */
    boolean isConnectionType(String typeName) {
        if (!(schema.getType(typeName) instanceof GraphQLObjectType connType)) return false;
        var edgesField = connType.getFieldDefinition("edges");
        if (edgesField == null) return false;
        var edgeType = GraphQLTypeUtil.unwrapAll(edgesField.getType());
        return edgeType instanceof GraphQLObjectType edgeObj && edgeObj.getFieldDefinition("node") != null;
    }

    /** Returns the nullability of the {@code edges.node} field for a confirmed connection type. */
    boolean connectionItemNullable(String connectionTypeName) {
        var connType = (GraphQLObjectType) schema.getType(connectionTypeName);
        var edgesField = connType.getFieldDefinition("edges");
        var edgeType = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(edgesField.getType());
        var nodeField = edgeType.getFieldDefinition("node");
        return !(nodeField.getType() instanceof GraphQLNonNull);
    }

    /** Returns the element type name for a confirmed connection type by navigating {@code edges.node}. */
    String connectionElementTypeName(String connectionTypeName) {
        var connType = (GraphQLObjectType) schema.getType(connectionTypeName);
        var edgesField = connType.getFieldDefinition("edges");
        var edgeType = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(edgesField.getType());
        var nodeField = edgeType.getFieldDefinition("node");
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(nodeField.getType())).getName();
    }

    // ===== Field type helpers =====

    /** Returns the unwrapped base type name of a field definition. */
    static String baseTypeName(GraphQLFieldDefinition fieldDef) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
    }

    /**
     * Converts a type name and wrapper into the correct {@link ReturnTypeRef} variant by
     * consulting the populated {@link #types} map.
     */
    ReturnTypeRef resolveReturnType(String targetTypeName, FieldWrapper wrapper) {
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableBackedType tbt)
            return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tbt.table(), wrapper);
        if (target instanceof InterfaceType || target instanceof UnionType)
            return new ReturnTypeRef.PolymorphicReturnType(targetTypeName, wrapper);
        if (target instanceof ResultType rt)
            return new ReturnTypeRef.ResultReturnType(targetTypeName, wrapper, rt.fqClassName());
        return new ReturnTypeRef.ScalarReturnType(targetTypeName, wrapper);
    }

    // ===== Error-message helpers =====

    /**
     * Builds a {@code "; did you mean: X, Y, Z"} hint string for error messages, listing
     * {@code candidates} sorted by Levenshtein distance from {@code attempt}.
     */
    static String candidateHint(String attempt, List<String> candidates) {
        return candidateHint(attempt, candidates, "; did you mean: ");
    }

    /**
     * Builds a hint string with a custom {@code prefix} for error messages, listing
     * {@code candidates} sorted by Levenshtein distance from {@code attempt}.
     */
    static String candidateHint(String attempt, List<String> candidates, String prefix) {
        if (candidates.isEmpty()) return "";
        String lc = attempt.toLowerCase();
        return prefix + candidates.stream()
            .sorted(Comparator.comparingInt(c -> levenshteinDistance(lc, c.toLowerCase())))
            .limit(5)
            .collect(Collectors.joining(", "));
    }

    private static int levenshteinDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1))
                    curr[j] = prev[j - 1];
                else
                    curr[j] = 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    // ===== Reference path parsing =====

    /**
     * Carries the result of {@link #parsePath}: either a fully resolved list of path elements or
     * an error message. When {@code errorMessage()} is non-null the {@code elements()} list is
     * empty and the containing field must be classified as an unclassified variant.
     */
    record ParsedPath(List<JoinStep> elements, String errorMessage) {
        boolean hasError() { return errorMessage != null; }
    }

    /**
     * Resolves a GraphQL type name to its expected jOOQ {@code TableRecord} class via the
     * {@code @table} directive on the type. Returns empty when the type isn't in the schema,
     * isn't {@code @table}-annotated, or the catalog can't resolve the table name.
     *
     * <p>Used by {@link ServiceDirectiveResolver}'s parent-table consistency check: a
     * {@code @service} child whose SOURCES element type is a typed {@code TableRecord}
     * subtype must match the parent's expected record class.
     */
    Optional<Class<?>> recordClassForTypeName(String typeName) {
        if (typeName == null) return Optional.empty();
        var raw = schema.getType(typeName);
        if (!(raw instanceof GraphQLObjectType obj) || !obj.hasAppliedDirective(DIR_TABLE)) {
            return Optional.empty();
        }
        String tableName = argString(obj, DIR_TABLE, ARG_NAME).orElse(typeName.toLowerCase());
        return catalog.findRecordClass(tableName);
    }

    /**
     * Resolves a SQL table name to a {@link TableRef} by looking it up in the jOOQ catalog.
     * Accepts both unqualified ({@code "film"}) and schema-qualified ({@code "public.film"})
     * directive values, routed through {@link JooqCatalog#findTable(String)}.
     *
     * <p>Returns empty when:
     * <ul>
     *   <li>the catalog is unavailable</li>
     *   <li>the (qualified or unique-unqualified) name does not match any table</li>
     *   <li>the name is unqualified and matches tables in two or more schemas</li>
     *   <li>the schema package has no generated {@code Tables} class (degenerate codegen)</li>
     * </ul>
     *
     * <p>Callers route empty to {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType}
     * or {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedField}; emitters never
     * see a partial ref.
     */
    Optional<TableRef> resolveTable(String sqlName) {
        return catalog.findTable(sqlName).asEntry().map(e -> e.toTableRef(sqlName));
    }

    /**
     * Builds the {@link Rejection} to attach to an {@code UnclassifiedType} /
     * {@code UnclassifiedField} when a table-name lookup did not produce a {@code Resolved}
     * variant. Switches over the {@link JooqCatalog.TableResolution} sub-taxonomy directly:
     *
     * <ul>
     *   <li>{@link JooqCatalog.TableResolution.Ambiguous} → structural rejection that names the
     *       colliding schemas and suggests qualified forms (the user typed something legitimate
     *       but we cannot pick a winner without more information).</li>
     *   <li>{@link JooqCatalog.TableResolution.NotInCatalog} → {@link Rejection#unknownTable}
     *       rejection with the Levenshtein-ranked candidate hint over every table in the catalog.
     *       Covers genuinely missing names and qualified misses (the qualified form never matches
     *       any schema's bare-name candidate set).</li>
     * </ul>
     *
     * <p>The string-arg overload looks the table up itself; the variant-arg overload is wired
     * directly from rejection sites that already hold a {@link JooqCatalog.TableResolution}
     * result so they avoid a redundant catalog query.
     */
    Rejection unknownTableRejection(String sqlName) {
        return unknownTableRejection(catalog.findTable(sqlName), sqlName);
    }

    Rejection unknownTableRejection(JooqCatalog.TableResolution failure, String sqlName) {
        return switch (failure) {
            case JooqCatalog.TableResolution.Resolved r -> throw new IllegalArgumentException(
                "unknownTableRejection called for resolved table '" + sqlName + "'");
            case JooqCatalog.TableResolution.NotInCatalog n -> Rejection.unknownTable(
                "table '" + sqlName + "' could not be resolved in the jOOQ catalog",
                sqlName, catalog.allTableSqlNames());
            case JooqCatalog.TableResolution.Ambiguous a -> {
                String qualifiedHints = a.schemas().stream()
                    .map(s -> "'" + s + "." + sqlName + "'")
                    .collect(Collectors.joining(", "));
                yield Rejection.structural(
                    "@table(name: '" + sqlName + "') is ambiguous: defined in schemas " + a.schemas()
                        + "; qualify as " + qualifiedHints);
            }
        };
    }

    /**
     * Builds the {@link Rejection} for an FK-name lookup that did not produce a
     * {@link JooqCatalog.ForeignKeyResolution.Resolved} variant. Sibling of
     * {@link #unknownTableRejection}; surfaces a Levenshtein-ranked candidate hint over every
     * FK constraint name in the catalog so a typo in {@code @reference(key: "...")} (or in the
     * inferred FK name picked up by the {@code IdReference} synthesis shim) reaches the schema
     * author with a fix-it suggestion rather than just a "not in catalog" string.
     */
    Rejection unknownForeignKeyRejection(String fkName) {
        return Rejection.unknownForeignKey(
            "foreign key '" + fkName + "' could not be resolved in the jOOQ catalog",
            fkName, catalog.allForeignKeySqlNames());
    }

    /**
     * Resolves a jOOQ {@code Table} + {@code Field} list to a list of {@link ColumnRef}s for FK
     * source/target column population. Returns an empty list when the catalog is unavailable.
     */
    private List<ColumnRef> resolveFkColumnRefs(org.jooq.Table<?> table, List<? extends org.jooq.Field<?>> fields) {
        return fields.stream()
            .map(f -> catalog.findColumn(table, f.getName()))
            .<JooqCatalog.ColumnEntry>flatMap(Optional::stream)
            .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()))
            .toList();
    }

    /**
     * Parses the {@code @reference(path:)} directive on {@code container} into a {@link ParsedPath},
     * or — when the directive is absent or carries an empty {@code path:} list — infers a single-hop
     * {@link FkJoin} from the catalog when exactly one foreign key connects {@code startSqlTableName}
     * to {@code targetSqlTableName}.
     *
     * <p>Inference fires only when all three of: {@code startSqlTableName != null},
     * {@code targetSqlTableName != null}, and the two names differ (case-insensitive) — the same
     * constraints as {@link JooqCatalog#findForeignKeysBetweenTables}. Zero or multiple FKs produce
     * a {@code ParsedPath} with a non-null {@code errorMessage} asking the author to write an
     * explicit {@code @reference} directive.
     *
     * <p>Returns {@code ParsedPath(List.of(), null)} when both inference preconditions are
     * unsatisfied (typical for {@code ColumnReferenceField}, service reconnect, and same-table
     * {@code @externalField} / {@code @tableMethod} sites).
     *
     * <p>{@code fieldName} is the GraphQL field name and is used to compute per-step aliases
     * ({@code fieldName + "_" + stepIndex}). {@code startSqlTableName} is the SQL table name at
     * the start of the path (the parent type's table), or {@code null} when the source is not
     * table-backed — in which case FK direction is inferred as forward and connectivity is not
     * validated. {@code targetSqlTableName} is the SQL name of the return type's table when known,
     * or {@code null} to disable implicit-path inference at this site.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String fieldName,
            String startSqlTableName, String targetSqlTableName) {
        return parsePath(container, fieldName, startSqlTableName, targetSqlTableName, /*isList=*/false);
    }

    /**
     * Variant of {@link #parsePath} that accepts the field's list-cardinality, used to
     * disambiguate self-referential FK direction at synthesis time. For {@code category.parent}
     * the parent (source) holds the FK; for {@code category.children} the child (target) holds it,
     * even though both navigate the same FK constraint. Cardinality is the only reliable signal
     * here — the table-name comparison cannot resolve self-ref direction.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String fieldName,
            String startSqlTableName, String targetSqlTableName, boolean isList) {
        var directive = container.getAppliedDirective(DIR_REFERENCE);
        var pathArg = directive != null ? directive.getArgument(ARG_PATH) : null;
        List<?> elements;
        if (pathArg != null) {
            Object pathValue = pathArg.getValue();
            elements = pathValue instanceof List<?> l ? l : List.of(pathValue);
        } else {
            elements = List.of();
        }

        var resolvedElements = new ArrayList<JoinStep>();
        var errors = new ArrayList<String>();
        String currentSource = startSqlTableName;
        int stepIndex = 0;

        for (var v : elements) {
            if (v instanceof Map<?, ?>) {
                parsePathElement(asMap(v), currentSource, fieldName, stepIndex, resolvedElements, errors, isList);
                stepIndex++;
                if (!resolvedElements.isEmpty()) {
                    var last = resolvedElements.getLast();
                    currentSource = last instanceof FkJoin fk ? fk.targetTable().tableName() : null;
                }
            }
        }

        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors));
        }
        if (resolvedElements.isEmpty()
                && startSqlTableName != null
                && targetSqlTableName != null
                && !startSqlTableName.equalsIgnoreCase(targetSqlTableName)) {
            var fks = catalog.findForeignKeysBetweenTables(startSqlTableName, targetSqlTableName);
            if (fks.size() == 1) {
                var stepResolution = synthesizeFkJoin(fks.get(0), startSqlTableName, fieldName, 0, null, /*selfRefFkOnSource=*/!isList);
                switch (stepResolution) {
                    case FkJoinResolution.Resolved r -> resolvedElements.add(r.fkJoin());
                    case FkJoinResolution.UnknownTable u -> {
                        return new ParsedPath(List.of(),
                            unknownTableRejection(u.failure(), u.requestedName()).message());
                    }
                    case FkJoinResolution.UnknownForeignKey uf -> {
                        return new ParsedPath(List.of(),
                            unknownForeignKeyRejection(uf.fkName()).message());
                    }
                }
            } else {
                return new ParsedPath(List.of(),
                    fkCountMessage(startSqlTableName, targetSqlTableName, fks, /*directiveAbsent=*/true));
            }
        }
        return new ParsedPath(List.copyOf(resolvedElements), null);
    }

    /**
     * Builds an {@link FkJoin} step for a foreign key that connects {@code sourceSqlName} to some
     * other table, or surfaces the catalog-failure shape when one of the resolution inputs
     * (endpoint table, FK constraint name) is missing. Traversal direction is inferred from which
     * side of the FK the source name touches (case-insensitive). The step alias follows the
     * explicit-path convention, {@code fieldName + "_" + stepIndex}, so inferred and explicit
     * position-0 steps produce record-equivalent {@code FkJoin} values for the same shape.
     *
     * <p>{@code sourceSqlName} must be non-null; callers gate inference on that precondition.
     * {@code whereFilter} is {@code null} for pure inference; the {@code {table:}} and
     * {@code {key:}} branches in {@link #parsePathElement} may pass a resolved {@link MethodRef}
     * when the element carries a {@code condition:} sub-argument.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link FkJoinResolution.Resolved} when both endpoint tables and the FK constraint name
     *       resolve through the catalog;</li>
     *   <li>{@link FkJoinResolution.UnknownTable} carrying the failing
     *       {@link JooqCatalog.TableResolution} (always {@code NotInCatalog} or {@code Ambiguous})
     *       when an endpoint cannot be resolved;</li>
     *   <li>{@link FkJoinResolution.UnknownForeignKey} when the FK constraint name itself is
     *       absent from every schema's {@code Keys} class.</li>
     * </ul>
     * Callers switch over this result and route each failure shape through the matching diagnostic
     * builder ({@link #unknownTableRejection} / {@link #unknownForeignKeyRejection}).
     */
    /**
     * @param selfRefFkOnSource for self-referential FKs (where {@code f.getTable()} equals
     *     {@code f.getKey().getTable()}), the table-name comparison is ambiguous. The caller
     *     supplies this hint: {@code true} when the parent holds the FK (single-cardinality
     *     traversal, e.g. {@code category.parent}), {@code false} when the child holds the FK
     *     (list-cardinality traversal, e.g. {@code category.children}). Ignored for non-self-ref
     *     FKs, where the table-name comparison resolves direction.
     */
    FkJoinResolution synthesizeFkJoin(ForeignKey<?, ?> f, String sourceSqlName, String fieldName,
            int stepIndex, MethodRef whereFilter, boolean selfRefFkOnSource) {
        String fkSideTable  = f.getTable().getName();
        String keySideTable = f.getKey().getTable().getName();
        boolean isSelfRef = fkSideTable.equalsIgnoreCase(keySideTable);
        boolean fkOnSource = isSelfRef
            ? selfRefFkOnSource
            : sourceSqlName.equalsIgnoreCase(fkSideTable);
        String targetSqlName = fkOnSource ? keySideTable : fkSideTable;

        var fkResolution = catalog.findForeignKeyByName(f.getName());
        if (!(fkResolution instanceof JooqCatalog.ForeignKeyResolution.Resolved fkResolved)) {
            return new FkJoinResolution.UnknownForeignKey(f.getName());
        }

        var targetResolution = catalog.findTable(targetSqlName);
        if (!(targetResolution instanceof JooqCatalog.TableResolution.Resolved targetResolved)) {
            return new FkJoinResolution.UnknownTable(targetSqlName, targetResolution);
        }
        var originResolution = catalog.findTable(sourceSqlName);
        if (!(originResolution instanceof JooqCatalog.TableResolution.Resolved originResolved)) {
            return new FkJoinResolution.UnknownTable(sourceSqlName, originResolution);
        }

        TableRef targetTable = targetResolved.entry().toTableRef(targetSqlName);
        TableRef originTable = originResolved.entry().toTableRef(sourceSqlName);
        List<ColumnRef> fkSideCols  = resolveFkColumnRefs(f.getTable(), f.getFields());
        List<ColumnRef> keySideCols = resolveFkColumnRefs(f.getKey().getTable(), f.getKey().getFields());
        // Synthesis-time orientation: bake the FK-direction decision into each slot pair so
        // emitter sites read direction-blind (target.<targetSide>.eq(source.<sourceSide>))
        // regardless of whether the FK lives on the source or the target table.
        List<JoinSlot.FkSlot> slots = new java.util.ArrayList<>(fkSideCols.size());
        for (int i = 0; i < fkSideCols.size(); i++) {
            ColumnRef fkCol  = fkSideCols.get(i);
            ColumnRef keyCol = keySideCols.get(i);
            slots.add(fkOnSource
                ? new JoinSlot.FkSlot(fkCol, keyCol)
                : new JoinSlot.FkSlot(keyCol, fkCol));
        }
        String alias = fieldName + "_" + stepIndex;
        return new FkJoinResolution.Resolved(new FkJoin(fkResolved.ref(), originTable,
            targetTable, slots, whereFilter, alias));
    }

    /**
     * Sub-taxonomy of outcomes for {@link #synthesizeFkJoin}. Lifts the prior
     * {@code Optional<FkJoin>} return into a typed switch over the three catalog-failure shapes
     * an FK-join construction can hit. Diagnostic builders read the carried failure data
     * directly: {@link UnknownTable#failure} routes through {@link #unknownTableRejection};
     * {@link UnknownForeignKey#fkName} routes through {@link #unknownForeignKeyRejection}.
     */
    public sealed interface FkJoinResolution {
        /** Both endpoint tables and the FK name resolved; the {@link FkJoin} is ready. */
        record Resolved(FkJoin fkJoin) implements FkJoinResolution {}

        /**
         * One of the FK's endpoint tables did not resolve. {@code requestedName} is the SQL
         * name of the failing endpoint (the one that produced a non-{@code Resolved} variant);
         * {@code failure} carries the {@link JooqCatalog.TableResolution} so the diagnostic
         * builder can pick {@code unknownTable} vs. {@code structural} ambiguity prose.
         */
        record UnknownTable(String requestedName, JooqCatalog.TableResolution failure)
                implements FkJoinResolution {}

        /**
         * The FK constraint name itself is absent from every schema's {@code Keys} class.
         * {@code fkName} is the SQL constraint name the caller asked for.
         */
        record UnknownForeignKey(String fkName) implements FkJoinResolution {}

        /**
         * Project to {@link Optional}{@code <FkJoin>} for callers that ignore the failure
         * sub-taxonomy. Diagnostic-bearing callers must switch on the variant instead.
         */
        default Optional<FkJoin> asFkJoin() {
            return this instanceof Resolved r ? Optional.of(r.fkJoin()) : Optional.empty();
        }
    }

    /**
     * Renders the zero-FK / multi-FK error message shared by the two inference call sites:
     * empty-elements inference in {@link #parsePath} ({@code directiveAbsent = true}) and the
     * {@code {table:}} branch of {@link #parsePathElement} ({@code directiveAbsent = false}).
     *
     * <p>When {@code directiveAbsent} is true, both arms append "; add a @reference directive to
     * specify the join path" — that's the actionable fix when the user omitted the directive
     * entirely. When false, the zero-FK arm just states the fact (user already wrote
     * {@code {table: "..."}}, so telling them to add a directive is noise) and the multi-FK arm
     * instead enumerates the candidate FK names under "— use 'key' to specify which: …".
     */
    private String fkCountMessage(String source, String target, List<ForeignKey<?, ?>> fks, boolean directiveAbsent) {
        if (fks.isEmpty()) {
            String msg = "no foreign key found between tables '" + source + "' and '" + target + "'";
            if (directiveAbsent) {
                msg += "; add a @reference directive to specify the join path."
                    + " The catalog has no FK directly connecting these two tables, so a single-hop"
                    + " '@reference(path: [{key: \"<fk-name>\"}])' will not resolve. Either chain"
                    + " through an intermediate table that has FKs to both"
                    + " ('@reference(path: [{key: \"<fk-to-intermediate>\"}, {key: \"<fk-from-intermediate>\"}])'),"
                    + " or join on a non-FK predicate via '@reference(path: [{condition: {...}}])'";
            }
            return msg;
        }
        String msg = "multiple foreign keys found between tables '" + source + "' and '" + target + "'";
        String fkNames = fks.stream().map(ForeignKey::getName).collect(Collectors.joining(", "));
        if (directiveAbsent) {
            msg += "; add a @reference directive to specify which one"
                + " — candidates: " + fkNames
                + " (e.g. '@reference(path: [{key: \"" + fks.get(0).getName() + "\"}])')";
        } else {
            msg += " — use 'key' to specify which: " + fkNames;
        }
        return msg;
    }

    /**
     * Resolves one {@code @reference} path element into a {@link JoinStep} and appends it to
     * {@code out}. Errors are accumulated in {@code errors}.
     *
     * <p>{@code currentSourceSqlName} is the SQL table name at the current position in the chain,
     * or {@code null} when the source is not a table-backed type. When non-null, FK connectivity is
     * validated (the FK must touch the current source table) and traversal direction is determined
     * precisely. When null, forward traversal (FK-side → key-side) is assumed without validation.
     *
     * <p>{@code fieldName} and {@code stepIndex} are used to compute the step's alias as
     * {@code fieldName + "_" + stepIndex}.
     */
    private void parsePathElement(Map<String, Object> element, String currentSourceSqlName,
            String fieldName, int stepIndex, List<JoinStep> out, List<String> errors, boolean isList) {
        Object keyRaw = element.get(ARG_KEY);
        Object tableRaw = element.get(ARG_TABLE_REF);
        Object conditionRaw = element.get(ARG_CONDITION);

        Optional<String> keyName = Optional.ofNullable(keyRaw)
            .map(Object::toString)
            .filter(s -> !s.isBlank());
        Optional<String> tableName = Optional.ofNullable(tableRaw)
            .map(Object::toString)
            .filter(s -> !s.isBlank());
        boolean hasCondition = conditionRaw instanceof Map;

        String alias = fieldName + "_" + stepIndex;

        if (keyName.isPresent()) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            if (fk.isEmpty()) {
                errors.add("key '" + keyName.get() + "' could not be resolved in the jOOQ catalog"
                    + candidateHint(keyName.get(), catalog.allForeignKeySqlNames()));
                return;
            }
            var f = fk.get();
            String fkSideTable  = f.getTable().getName();
            String keySideTable = f.getKey().getTable().getName();
            if (currentSourceSqlName != null
                && !currentSourceSqlName.equalsIgnoreCase(fkSideTable)
                && !currentSourceSqlName.equalsIgnoreCase(keySideTable)) {
                errors.add("key '" + f.getName() + "' does not connect to table '" + currentSourceSqlName + "'"
                    + candidateHint(currentSourceSqlName, List.of(fkSideTable, keySideTable)));
                return;
            }
            String effectiveSourceSqlName = currentSourceSqlName != null ? currentSourceSqlName : fkSideTable;
            MethodRef whereFilter = null;
            if (hasCondition) {
                var res = resolveConditionRef(asMap(conditionRaw));
                if (res.error() != null) {
                    errors.add(res.error());
                    return;
                }
                whereFilter = res.ref();
            }
            var keyResolution = synthesizeFkJoin(f, effectiveSourceSqlName, fieldName, stepIndex, whereFilter, /*selfRefFkOnSource=*/!isList);
            switch (keyResolution) {
                case FkJoinResolution.Resolved r -> out.add(r.fkJoin());
                case FkJoinResolution.UnknownTable u ->
                    errors.add(unknownTableRejection(u.failure(), u.requestedName()).message());
                case FkJoinResolution.UnknownForeignKey uf ->
                    errors.add(unknownForeignKeyRejection(uf.fkName()).message());
            }
            return;
        }
        if (tableName.isPresent()) {
            if (currentSourceSqlName == null) {
                errors.add("path element with 'table' requires a known source table — use 'key' instead to name the FK explicitly");
                return;
            }
            var fks = catalog.findForeignKeysBetweenTables(currentSourceSqlName, tableName.get());
            if (fks.size() != 1) {
                errors.add(fkCountMessage(currentSourceSqlName, tableName.get(), fks, /*directiveAbsent=*/false));
                return;
            }
            MethodRef whereFilter = null;
            if (hasCondition) {
                var res = resolveConditionRef(asMap(conditionRaw));
                if (res.error() != null) {
                    errors.add(res.error());
                    return;
                }
                whereFilter = res.ref();
            }
            var tableStepResolution = synthesizeFkJoin(fks.get(0), currentSourceSqlName, fieldName, stepIndex, whereFilter, /*selfRefFkOnSource=*/!isList);
            switch (tableStepResolution) {
                case FkJoinResolution.Resolved r -> out.add(r.fkJoin());
                case FkJoinResolution.UnknownTable u ->
                    errors.add(unknownTableRejection(u.failure(), u.requestedName()).message());
                case FkJoinResolution.UnknownForeignKey uf ->
                    errors.add(unknownForeignKeyRejection(uf.fkName()).message());
            }
            return;
        }
        if (hasCondition) {
            Map<String, Object> condMap = asMap(conditionRaw);
            var res = resolveConditionRef(condMap);
            if (res.error() != null) {
                errors.add(res.error());
            } else if (res.ref() != null) {
                out.add(new ConditionJoin(res.ref(), alias));
            } else {
                errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
            }
            return;
        }
        errors.add("path element has neither 'key', 'table', nor 'condition'");
    }

    /**
     * Result of {@link #resolveConditionRef}: exactly one of {@code ref} and {@code error} is
     * non-null. {@code error} carries an actionable message (parser failure, unknown argMapping
     * source, reflection failure); the path-step caller surfaces it directly. The "no class /
     * no method" case maps to a generic resolution error.
     */
    private record ConditionResolution(MethodRef ref, String error) {}

    /**
     * Resolves an {@code ExternalCodeReference} condition map to a {@link MethodRef} via
     * {@link ServiceCatalog#reflectTableMethod}. Returns a {@link ConditionResolution} with
     * either {@code ref} or {@code error} populated.
     *
     * <p>The deprecated {@code name:} form is resolved via {@link RewriteContext#namedReferences()},
     * exactly as in {@link FieldBuilder#parseExternalRef}.
     *
     * <p>For path-step {@code @condition}, no GraphQL arguments are in scope, so the slot set
     * is empty and any non-empty {@code argMapping} fails through {@link
     * ArgBindingMap.Result.UnknownArgRef}. Parse-time errors from {@code argMapping} itself also
     * surface, with the path-step site context wrapped around the message.
     */
    private ConditionResolution resolveConditionRef(Map<String, Object> conditionMap) {
        String className = Optional.ofNullable(conditionMap.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(conditionMap.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String name = Optional.ofNullable(conditionMap.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (name != null) {
                className = ctx.namedReferences().get(name);
            }
        }
        if (className == null || methodName == null || svc == null) {
            return new ConditionResolution(null, null);
        }
        String rawArgMapping = Optional.ofNullable(conditionMap.get(ARG_ARG_MAPPING)).map(Object::toString).orElse(null);
        var parsed = ArgBindingMap.parseArgMapping(rawArgMapping);
        if (parsed instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new ConditionResolution(null,
                "path-step @condition: " + pe.message());
        }
        var segmentChains = ((ArgBindingMap.ParsedArgMapping.Ok) parsed).overrides();
        var bindingResult = ArgBindingMap.of(java.util.Map.<String, graphql.schema.GraphQLInputType>of(), segmentChains);
        if (bindingResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new ConditionResolution(null,
                "path-step @condition: no GraphQL arguments are in scope at a path-step @condition; "
                + u.message());
        }
        if (bindingResult instanceof ArgBindingMap.Result.PathRejected p) {
            return new ConditionResolution(null,
                "path-step @condition: " + p.message());
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(className, methodName, argBindings, Set.of(), null);
        return result.failed() ? new ConditionResolution(null, null) : new ConditionResolution(result.ref(), null);
    }

    private String extractConditionQualifiedName(Map<String, Object> conditionMap) {
        Object name = conditionMap.get(ARG_NAME);
        if (name != null) return name.toString();
        String cls    = Optional.ofNullable(conditionMap.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String method = Optional.ofNullable(conditionMap.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (cls != null && method != null) return "method '" + method + "' in class '" + cls + "'";
        if (cls != null) return "class '" + cls + "'";
        return "unknown";
    }

    // ===== @condition directive parsing (shared with TypeBuilder / FieldBuilder) =====

    /**
     * Parsed representation of a {@code @condition} directive applied to any
     * {@link GraphQLDirectiveContainer} (field, argument, or input-object field).
     *
     * <p>{@code argMapping} stores parsed segment chains keyed by Java target; single-segment
     * chains preserve R53 wire-compat, multi-segment chains carry R84 path expressions. The
     * call site supplies the slot-type oracle when calling {@link ArgBindingMap#of}.
     */
    record ConditionDirective(
        String className,
        String methodName,
        boolean override,
        List<String> contextArguments,
        Map<String, List<String>> argMapping,
        String argMappingError
    ) {}

    /**
     * Reads a {@code @condition} directive from a field, argument, or input-object-field
     * container. Returns {@code null} when the directive is absent or could not be parsed
     * (e.g. missing {@code className}/{@code method}).
     *
     * <p>Moved from {@code FieldBuilder} (was private) so both {@link FieldBuilder} and
     * {@link TypeBuilder} can reach it via {@code ctx}.
     */
    ConditionDirective readConditionDirective(GraphQLDirectiveContainer container) {
        var dir = container.getAppliedDirective(DIR_CONDITION);
        if (dir == null) return null;
        var condArg = dir.getArgument(ARG_CONDITION);
        if (condArg == null || condArg.getValue() == null) return null;
        Map<String, Object> ref = asMap(condArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String refName = Optional.ofNullable(ref.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (refName != null) className = ctx.namedReferences().get(refName);
        }
        if (className == null || methodName == null) return null;
        boolean override = argBoolean(container, DIR_CONDITION, ARG_OVERRIDE, false);
        List<String> ctxArgs = argStringList(container, DIR_CONDITION, ARG_CONTEXT_ARGUMENTS);
        String rawArgMapping = Optional.ofNullable(ref.get(ARG_ARG_MAPPING)).map(Object::toString).orElse(null);
        var parsed = ArgBindingMap.parseArgMapping(rawArgMapping);
        if (parsed instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new ConditionDirective(className, methodName, override, ctxArgs, Map.of(), pe.message());
        }
        var segmentChains = ((ArgBindingMap.ParsedArgMapping.Ok) parsed).overrides();
        return new ConditionDirective(className, methodName, override, ctxArgs, segmentChains, null);
    }

    /**
     * Builds an {@link ArgConditionRef} from a {@code @condition} directive on one
     * {@link GraphQLInputObjectField}. Reflects the condition method via
     * {@link ServiceCatalog#reflectTableMethod} with {@code inputFieldName} in {@code argNames}
     * and any declared {@code contextArguments}. Appends an error and returns
     * {@link Optional#empty()} on reflection failure — mirrors {@code FieldBuilder.buildArgCondition}.
     */
    Optional<ArgConditionRef> buildInputFieldCondition(
            GraphQLInputObjectField field, String inputFieldName, List<String> errors) {
        var cond = readConditionDirective(field);
        if (cond == null) return Optional.empty();
        if (cond.argMappingError() != null) {
            errors.add("input field '" + inputFieldName + "' @condition: " + cond.argMappingError());
            return Optional.empty();
        }
        var bindingResult = ArgBindingMap.of(java.util.Map.of(field.getName(), field.getType()), cond.argMapping());
        if (bindingResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            errors.add("input field '" + inputFieldName + "' @condition: " + u.message());
            return Optional.empty();
        }
        if (bindingResult instanceof ArgBindingMap.Result.PathRejected p) {
            errors.add("input field '" + inputFieldName + "' @condition: " + p.message());
            return Optional.empty();
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argBindings, Set.copyOf(cond.contextArguments()), null);
        if (result.failed()) {
            errors.add("input field '" + inputFieldName + "' @condition: " + result.rejection().message());
            return Optional.empty();
        }
        var methodRef = result.ref();
        return Optional.of(new ArgConditionRef(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()),
            cond.override()));
    }

    /**
     * Result of inferring or extracting the {@code typeName:} argument for a bare {@code @nodeId}
     * directive. Exactly one of {@link #typeName} and {@link #error} is non-null.
     */
    private record NodeIdTypeNameInference(String typeName, InputFieldResolution.Unresolved error) {}

    /**
     * Resolves the {@code typeName:} for a {@code @nodeId} directive on an input field, either
     * by reading the explicit argument or, when absent, by looking up the {@code @table}-annotated
     * object type that backs {@code resolvedTable}. The disambiguation rules apply only to the
     * inference path: zero or multiple matching object types both yield a friendly diagnostic.
     */
    private NodeIdTypeNameInference inferNodeIdTypeName(
            GraphQLInputObjectField field, TableRef resolvedTable, String fieldName) {
        Optional<String> explicit = argString(field, DIR_NODE_ID, ARG_TYPE_NAME);
        if (explicit.isPresent()) {
            return new NodeIdTypeNameInference(explicit.get(), null);
        }
        var candidates = findGraphQLTypesForTable(resolvedTable.tableName());
        if (candidates.isEmpty()) {
            return new NodeIdTypeNameInference(null, new InputFieldResolution.Unresolved(fieldName, null,
                "@nodeId without typeName: cannot infer node type — no @table-annotated object type"
                + " maps to table '" + resolvedTable.tableName() + "'."
                + " Add typeName: explicitly."));
        }
        if (candidates.size() > 1) {
            return new NodeIdTypeNameInference(null, new InputFieldResolution.Unresolved(fieldName, null,
                "@nodeId without typeName: is ambiguous — multiple object types map to table '"
                + resolvedTable.tableName() + "': " + String.join(", ", candidates)
                + ". Specify typeName: explicitly."));
        }
        return new NodeIdTypeNameInference(candidates.get(0), null);
    }

    // ===== Input-field classifier (shared between TypeBuilder and FieldBuilder) =====

    /**
     * Classifies a single {@link GraphQLInputObjectField} against {@code resolvedTable}, producing
     * an {@link InputFieldResolution}: either a fully classified {@link InputField} variant
     * (possibly with a {@code condition}) or an unresolved result with a diagnostic message.
     *
     * <p>Extracted from {@code TypeBuilder.buildInputField} so both {@link TypeBuilder}
     * (type-build pass, {@code @table} inputs) and {@link FieldBuilder} (argument-classify pass,
     * plain inputs) share the same decision tree.
     *
     * <p>Condition reflection failures append to {@code errors} and leave the
     * {@code condition} field empty — the field still classifies as its structural variant.
     * Column-miss and path-resolution failures return {@link InputFieldResolution.Unresolved}.
     *
     * <p>{@code expandingTypes} guards against circular plain-input nesting; callers start
     * with an empty set.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "nodeid-fk.direct-fk-keys-match",
        reliesOn = "The @nodeId FK-target arms construct InputField.ColumnReferenceField /"
            + " CompositeColumnReferenceField only on NodeIdLeafResolver.Resolved.FkTarget.DirectFk."
            + " The TranslatedFk arm routes to InputFieldResolution.Unresolved with a deferred-emission"
            + " hint so emitter consumers (walkInputFieldConditions → implicit body params) never see"
            + " a JOIN-with-translation shape they cannot bind directly.")
    InputFieldResolution classifyInputField(
            GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable,
            Set<String> expandingTypes, List<String> errors) {
        String name = field.getName();
        if (field.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new InputFieldResolution.Unresolved(name, null,
                "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");
        }
        GraphQLType type = field.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean hasFieldDir = field.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDir
            ? argString(field, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        if ("ID".equals(typeName) && !list && field.hasAppliedDirective(DIR_NODE_ID)) {
            // Bare @nodeId (no typeName:) means "use the typeName of the @table-annotated object
            // type that maps to this input's resolved table". The inference disambiguates against
            // the GraphQL schema, not the SQL catalog, because the same SQL table may legitimately
            // back several object types; we need a unique GraphQL identity to use as typeName.
            var inferredTypeName = inferNodeIdTypeName(field, resolvedTable, name);
            if (inferredTypeName.error() != null) return inferredTypeName.error();
            String refTypeName = inferredTypeName.typeName();
            // ctx.types may be null during the first type-builder pass; resolve via
            // schema + catalog directly so this classifier works in both passes.
            var rawGqlType = schema.getType(refTypeName);
            if (rawGqlType == null) {
                return new InputFieldResolution.Unresolved(name, null,
                    "@nodeId(typeName:) type '" + refTypeName + "' does not exist in the schema"
                    + candidateHint(refTypeName, schema.getAllTypesAsList().stream()
                        .map(t -> t.getName()).filter(n -> !n.startsWith("__")).toList()));
            }
            // Must be a table-backed object type to be a NodeType
            if (!(rawGqlType instanceof GraphQLObjectType gqlObjType)
                    || !gqlObjType.hasAppliedDirective(DIR_TABLE)) {
                return new InputFieldResolution.Unresolved(name, null,
                    "@nodeId(typeName:) type '" + refTypeName
                    + "' is not a node type (not a @table-annotated object type)");
            }
            String targetTableName = argString(gqlObjType, DIR_TABLE, ARG_NAME)
                .orElse(refTypeName.toLowerCase());
            // Prefer catalog metadata; fall back to ctx.types (when available post-first-pass)
            // for SDL-only @node types.
            String targetTypeId;
            List<ColumnRef> targetKeyColumns;
            var metaOpt = catalog.nodeIdMetadata(targetTableName);
            if (metaOpt.isPresent()) {
                targetTypeId = metaOpt.get().typeId();
                targetKeyColumns = metaOpt.get().keyColumns();
            } else if (types != null && types.get(refTypeName) instanceof NodeType nt) {
                targetTypeId = nt.typeId();
                targetKeyColumns = nt.nodeKeyColumns();
            } else if (gqlObjType.hasAppliedDirective(DIR_NODE)) {
                // First-pass / SDL-only @node: fall back to the catalog primary key, matching
                // the same-table list branch and the cross-table FK branch below.
                targetTypeId = argString(gqlObjType, DIR_NODE, ARG_TYPE_ID)
                    .orElse(refTypeName);
                targetKeyColumns = catalog.findPkColumns(targetTableName).stream()
                    .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()))
                    .toList();
                if (targetKeyColumns.isEmpty()) {
                    return new InputFieldResolution.Unresolved(name, null,
                        "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                        + "' which has @node but no resolvable key columns (no catalog metadata, "
                        + "no @node(keyColumns:), no primary key)");
                }
            } else {
                return new InputFieldResolution.Unresolved(name, null,
                    "@nodeId(typeName:) type '" + refTypeName
                    + "' is not a node type (missing @node or KjerneJooqGenerator metadata)");
            }
            var targetTableResolution = catalog.findTable(targetTableName);
            if (!(targetTableResolution instanceof JooqCatalog.TableResolution.Resolved targetTableResolved)) {
                return new InputFieldResolution.Unresolved(name, null,
                    "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                    + "': " + unknownTableRejection(targetTableResolution, targetTableName).message());
            }
            TableRef targetTable = targetTableResolved.entry().toTableRef(targetTableName);
            var nodeRefPath = parsePath(field, name, resolvedTable.tableName(), targetTable.tableName());
            if (nodeRefPath.hasError()) {
                return new InputFieldResolution.Unresolved(name, null, nodeRefPath.errorMessage());
            }
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
            return buildInputNodeIdReference(
                parentTypeName, name, locationOf(field), typeName, nonNull, /* list= */ false,
                resolvedTable, refTypeName, targetTable.tableName(), targetTypeId,
                targetKeyColumns, nodeRefPath.elements(), cond);
        }
        // [ID!] with @nodeId(typeName: T), optionally pinned by @reference(path: [{key: K}]):
        // same-table → ColumnField / CompositeColumnField (filter by own primary key);
        // FK-target → ColumnReferenceField / CompositeColumnReferenceField with single-hop
        // joinPath. The shape decision is delegated to NodeIdLeafResolver so it stays in lockstep
        // with argument-level @nodeId leaves in FieldBuilder.classifyArgument.
        if ("ID".equals(typeName) && list && field.hasAppliedDirective(DIR_NODE_ID)) {
            var resolved = nodeIdLeafResolver.resolve(field, name, resolvedTable);
            switch (resolved) {
                case NodeIdLeafResolver.Resolved.Rejected r ->
                    { return new InputFieldResolution.Unresolved(name, null, r.message()); }
                case NodeIdLeafResolver.Resolved.SameTable st -> {
                    // Input-field filter leaves always use Skip semantics: malformed ids drop
                    // silently to "no row matches", never throw. Argument-level lookup leaves
                    // pick Throw at their own classify site.
                    Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
                    var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(st.decodeMethod());
                    if (st.keyColumns().size() == 1) {
                        return new InputFieldResolution.Resolved(new InputField.ColumnField(
                            parentTypeName, name, locationOf(field), typeName, nonNull, /* list= */ true,
                            st.keyColumns().get(0), cond, extraction));
                    }
                    return new InputFieldResolution.Resolved(new InputField.CompositeColumnField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, /* list= */ true,
                        st.keyColumns(), cond, extraction));
                }
                case NodeIdLeafResolver.Resolved.FkTarget.DirectFk direct -> {
                    Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
                    var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(direct.decodeMethod());
                    if (direct.keyColumns().size() == 1) {
                        return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(
                            parentTypeName, name, locationOf(field), typeName, nonNull, list,
                            direct.keyColumns().get(0), direct.joinPath(), cond, extraction));
                    }
                    return new InputFieldResolution.Resolved(new InputField.CompositeColumnReferenceField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        direct.keyColumns(), direct.joinPath(), cond, extraction));
                }
                case NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk translated ->
                    { return new InputFieldResolution.Unresolved(name, null,
                        FieldBuilder.translatedFkRejectionReason(translated.refTypeName(), resolvedTable.tableName())); }
            }
        }
        if (field.hasAppliedDirective(DIR_REFERENCE)) {
            var path = parsePath(field, name, resolvedTable.tableName(), null);
            if (path.hasError()) return new InputFieldResolution.Unresolved(name, null, path.errorMessage());
            return svc.resolveColumnForReference(columnName, path.elements(), resolvedTable.tableName())
                .<InputFieldResolution>map(col -> {
                    Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
                    return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        col, path.elements(), cond,
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
                })
                .orElseGet(() -> new InputFieldResolution.Unresolved(name, columnName,
                    "no column '" + columnName + "' reachable via @reference path"));
        }
        // Nesting: field type is a plain input object (no @table).
        var baseType = GraphQLTypeUtil.unwrapAll(type);
        if (baseType instanceof GraphQLInputObjectType nestedInputType
                && !nestedInputType.hasAppliedDirective(DIR_TABLE)) {
            if (expandingTypes.contains(typeName)) {
                return new InputFieldResolution.Unresolved(name, null,
                    "circular input type reference detected while expanding '" + typeName + "'");
            }
            var newExpanding = new LinkedHashSet<>(expandingTypes);
            newExpanding.add(typeName);
            var failures = new ArrayList<InputFieldResolution.Unresolved>();
            var resolvedFields = new ArrayList<InputField>();
            for (var nested : nestedInputType.getFieldDefinitions()) {
                var res = classifyInputField(nested, typeName, resolvedTable, newExpanding, errors);
                switch (res) {
                    case InputFieldResolution.Resolved r -> resolvedFields.add(r.field());
                    case InputFieldResolution.Unresolved u -> failures.add(u);
                }
            }
            if (!failures.isEmpty()) {
                String reasons = failures.stream()
                    .map(u -> "'" + u.fieldName() + "': " + u.reason())
                    .collect(Collectors.joining("; "));
                String hint = failures.stream()
                    .map(InputFieldResolution.Unresolved::lookupColumn)
                    .filter(c -> c != null)
                    .findFirst()
                    .map(c -> candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName())))
                    .orElse("");
                return new InputFieldResolution.Unresolved(name, null,
                    "nested input type '" + typeName + "' has unresolvable fields: " + reasons + hint);
            }
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
            return new InputFieldResolution.Resolved(new InputField.NestingField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                List.copyOf(resolvedFields), cond));
        }
        String tableName = resolvedTable.tableName();
        // Synthesis shim: ID! or [ID!] on a @table input whose column name (from @field(name:) or
        // the GraphQL field name) hits the qualifier map for the resolved table AND the target
        // table has KjerneJooqGenerator node metadata. Runs before column lookup so
        // @field(name: "X_ID") fields are intercepted before the case-insensitive column match
        // would shadow the qualifier reverse-map. The shim routes to the same column-shaped
        // carriers as the canonical [ID!] @nodeId(typeName: T) branch above. Retirement plan:
        // graphitron-rewrite/roadmap/retire-synthesis-shims.md.
        if ("ID".equals(typeName)
                && !field.hasAppliedDirective(DIR_NODE_ID)
                && !field.hasAppliedDirective(DIR_REFERENCE)) {
            String shimFkName = catalog.buildQualifierMap(tableName).get(columnName.toLowerCase());
            if (shimFkName != null) {
                String qualifier = catalog.qualifierForFk(tableName, shimFkName)
                    .orElseThrow(() -> new IllegalStateException(
                        "qualifierForFk returned empty for FK '" + shimFkName + "' on table '"
                        + tableName + "' — should be unreachable"));
                Optional<String> targetTableOpt = catalog.findForeignKey(shimFkName)
                    .map(fk -> fk.getKey().getTable().getName());
                Optional<String> targetTypeOpt = targetTableOpt.flatMap(this::findGraphQLTypeForTable);
                // Gate: target table carries __NODE_TYPE_ID. Same KjerneJooqGenerator-project
                // sentinel the scalar bare-ID NodeId shim uses; if the target isn't a node type the
                // canonical replacement we'd emit (@nodeId(typeName:)) wouldn't typecheck either.
                var shimTargetMeta = catalog.nodeIdMetadata(targetTableOpt.orElse(""));
                if (shimTargetMeta.isPresent() && targetTypeOpt.isPresent()) {
                    boolean fkAmbiguous = catalog
                        .findUniqueFkToTable(tableName, targetTableOpt.get())
                        .isEmpty();
                    String canonical = fkAmbiguous
                        ? "@nodeId(typeName: \"" + targetTypeOpt.get() + "\")"
                          + " @reference(path: [{key: \"" + shimFkName + "\"}])"
                        : "@nodeId(typeName: \"" + targetTypeOpt.get() + "\")";
                    ID_REF_SHIM_LOGGER.warn(
                        "input field '{}.{}' synthesizes a NodeId reference from qualifier '{}'"
                        + " (FK '{}'); replace the legacy form with {} to drop the synthesis shim."
                        + " The shim will be removed in a future release;"
                        + " see graphitron-rewrite/roadmap/retire-id-reference-synthesis-shim.md",
                        parentTypeName, name, qualifier, shimFkName, canonical);
                    Optional<ArgConditionRef> shimRefCond = buildInputFieldCondition(field, name, errors);
                    var shimFkOpt = catalog.findForeignKey(shimFkName);
                    if (shimFkOpt.isEmpty()) {
                        return new InputFieldResolution.Unresolved(name, null,
                            "synthesis shim FK '" + shimFkName + "' not found in catalog");
                    }
                    // Synthesis shim is for input-side NodeId refs; the parent (input field's
                    // backing table) holds the FK by the shim's invariant, so selfRefFkOnSource
                    // is fixed at true.
                    var shimFkResolution = synthesizeFkJoin(shimFkOpt.get(), tableName, name, 0, null, /*selfRefFkOnSource=*/true);
                    var shimTargetResolution = catalog.findTable(targetTableOpt.get());
                    if (!(shimFkResolution instanceof FkJoinResolution.Resolved shimFkResolved)) {
                        return switch (shimFkResolution) {
                            case FkJoinResolution.UnknownTable u -> new InputFieldResolution.Unresolved(name, null,
                                unknownTableRejection(u.failure(), u.requestedName()).message());
                            case FkJoinResolution.UnknownForeignKey uf -> new InputFieldResolution.Unresolved(name, null,
                                unknownForeignKeyRejection(uf.fkName()).message());
                            case FkJoinResolution.Resolved r -> throw new IllegalStateException("unreachable");
                        };
                    }
                    if (!(shimTargetResolution instanceof JooqCatalog.TableResolution.Resolved shimTargetResolved)) {
                        return new InputFieldResolution.Unresolved(name, null,
                            unknownTableRejection(shimTargetResolution, targetTableOpt.get()).message());
                    }
                    List<JoinStep> shimJoinPath = List.of(shimFkResolved.fkJoin());
                    return buildInputNodeIdReference(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        shimTargetResolved.entry().toTableRef(targetTableOpt.get()),
                        targetTypeOpt.get(), targetTableOpt.get(),
                        shimTargetMeta.get().typeId(), shimTargetMeta.get().keyColumns(),
                        shimJoinPath, shimRefCond);
                }
            }
        }
        var colEntry = catalog.findColumn(tableName, columnName);
        if (colEntry.isPresent()) {
            var e = colEntry.get();
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
            return new InputFieldResolution.Resolved(new InputField.ColumnField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()), cond,
                new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
        }
        // NodeId synthesis shim: scalar ID field with no @nodeId directive whose backing table
        // carries node-identity metadata (__NODE_TYPE_ID / __NODE_KEY_COLUMNS constants emitted
        // by KjerneJooqGenerator). Fires a per-site deprecation diagnostic; the canonical form is
        // to declare @nodeId explicitly, which routes through the bare-@nodeId branch above. See
        // graphitron-rewrite/roadmap/retire-synthesis-shims.md.
        if ("ID".equals(typeName) && !list && !field.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta = catalog.nodeIdMetadata(tableName);
            if (nodeIdMeta.isPresent()) {
                NODE_ID_SHIM_LOGGER.warn("input field '{}.{}' synthesizes a NodeId-decoded column"
                    + " without '@nodeId'; declare the directive explicitly. The synthesis shim"
                    + " will be removed in a future release."
                    + " See graphitron-rewrite/roadmap/retire-synthesis-shims.md",
                    parentTypeName, name);
                // Arity-1 lands on InputField.ColumnField with extraction =
                // NodeIdDecodeKeys.SkipMismatchedElement; arity > 1 lands on
                // InputField.CompositeColumnField with the same extraction (narrowed at the type
                // level). HelperRef.Decode is resolved off the matching NodeType.
                var keyColumns = nodeIdMeta.get().keyColumns();
                var decodeMethod = resolveDecodeHelperForTable(tableName, nodeIdMeta.get().typeId(), keyColumns);
                if (decodeMethod == null) {
                    return new InputFieldResolution.Unresolved(name, null,
                        "@nodeId synthesis shim on input field '" + name + "': unable to resolve"
                        + " the NodeType backing table '" + tableName + "' (zero or multiple"
                        + " GraphQL types map to it). Declare @nodeId(typeName: T) explicitly.");
                }
                Optional<ArgConditionRef> shimCond = buildInputFieldCondition(field, name, errors);
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(decodeMethod);
                if (keyColumns.size() == 1) {
                    return new InputFieldResolution.Resolved(new InputField.ColumnField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        keyColumns.get(0), shimCond, extraction));
                }
                return new InputFieldResolution.Resolved(new InputField.CompositeColumnField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    keyColumns, shimCond, extraction));
            }
        }
        return new InputFieldResolution.Unresolved(name, columnName,
            "no column '" + columnName + "' found in table '" + tableName + "'");
    }

    /**
     * Returns the GraphQL type name for the given SQL table name, or empty when zero or multiple
     * {@code @table}-annotated object types claim that table name (case-insensitive). Used by the
     * id-reference synthesis shim to map the FK's target table back to a GraphQL type name; the
     * shim then builds an {@link InputField.ColumnReferenceField} or
     * {@link InputField.CompositeColumnReferenceField} carrying the resolved type's
     * {@link no.sikt.graphitron.rewrite.GraphitronType.NodeType} key columns.
     */
    private Optional<String> findGraphQLTypeForTable(String sqlTableName) {
        var candidates = findGraphQLTypesForTable(sqlTableName);
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * Builds the column-shaped carrier for {@code @nodeId(typeName: T)} input fields referencing
     * another table. Used by both the scalar bare-{@code @nodeId} branch (originally
     * {@code id: ID! @nodeId(typeName: T)}) and the {@code [ID!] @nodeId(typeName: T)} list filter
     * branch.
     *
     * <p>Routes by {@code targetKeyColumns.size()}: arity-1 to a single-column
     * {@link InputField.ColumnReferenceField} with extraction =
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement}; arity &gt; 1
     * to {@link InputField.CompositeColumnReferenceField} narrowed to the same extraction arm.
     * The {@code list} flag flows through to the carrier, which steers the body emitter onto
     * {@code ColumnPredicate.Eq} / {@code RowEq} (scalar) or {@code In} / {@code RowIn} (list).
     *
     * <p>Failure mode is Skip (not Throw) because {@code @nodeId} in input-object filter context
     * surfaces a malformed id as "no row matches"; never throws. Input filters are not
     * contract-violation surfaces.
     */
    private InputFieldResolution buildInputNodeIdReference(
            String parentTypeName, String name, graphql.language.SourceLocation location,
            String typeName, boolean nonNull, boolean list, TableRef parentTable,
            String refTypeName, String targetTableName, String targetTypeId,
            List<ColumnRef> targetKeyColumns, List<JoinStep> joinPath,
            Optional<ArgConditionRef> cond) {
        if (targetKeyColumns.isEmpty()) {
            return new InputFieldResolution.Unresolved(name, null,
                "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                + "' which has no resolvable key columns (no NodeId metadata, no @node(keyColumns:),"
                + " and no primary key) — declare key columns on the target type or surface the"
                + " metadata via KjerneJooqGenerator");
        }
        var decodeMethod = resolveDecodeHelperForTable(targetTableName, targetTypeId, targetKeyColumns);
        if (decodeMethod == null) {
            return new InputFieldResolution.Unresolved(name, null,
                "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                + "' which has no GraphQL type backing and no resolvable decode helper"
                + " — declare a @table-annotated object type for the target or surface the metadata"
                + " via KjerneJooqGenerator");
        }
        var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(decodeMethod);
        if (targetKeyColumns.size() == 1) {
            return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(parentTypeName, name, location,
                typeName, nonNull, list, targetKeyColumns.get(0), joinPath, cond,
                extraction));
        }
        return new InputFieldResolution.Resolved(new InputField.CompositeColumnReferenceField(parentTypeName, name, location,
            typeName, nonNull, list, targetKeyColumns, joinPath, cond, extraction));
    }

    /**
     * Resolves the {@code decode<TypeName>} {@link no.sikt.graphitron.rewrite.model.HelperRef.Decode}
     * for the NodeType backing the given SQL table, or {@code null} when no unique mapping exists.
     * Used by the {@code @nodeId} synthesis shim and other input-side classifier paths that need
     * the per-Node decode helper but only have the SQL table name in scope.
     *
     * <p>Prefers reading from {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType#decodeMethod()}
     * when the {@code types} map is populated (post-first-pass). Falls back to inline
     * construction via the canonical {@code outputPackage + ".util" + "NodeIdEncoder"} +
     * {@code "decode" + typeName} formula otherwise; both BuildContext and TypeBuilder compute
     * the same name string from the GraphQL type name, so drift is bounded.
     */
    no.sikt.graphitron.rewrite.model.HelperRef.Decode resolveDecodeHelperForTable(
            String sqlTableName,
            String fallbackTypeNameOrTypeId,
            java.util.List<no.sikt.graphitron.rewrite.model.ColumnRef> keyColumns) {
        var encoderClass = no.sikt.graphitron.javapoet.ClassName.get(
            ctx.outputPackage() + ".util",
            no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator.CLASS_NAME);
        var typeNameOpt = findGraphQLTypeForTable(sqlTableName);
        if (typeNameOpt.isPresent()) {
            String typeName = typeNameOpt.get();
            if (types != null && types.get(typeName) instanceof no.sikt.graphitron.rewrite.model.GraphitronType.NodeType nt) {
                return nt.decodeMethod();
            }
            return new no.sikt.graphitron.rewrite.model.HelperRef.Decode(encoderClass, "decode" + typeName, keyColumns);
        }
        // No unique @table-annotated GraphQL object type backs this table (e.g. orphan-input
        // schemas where only an `input Foo @table(name: "bar")` exists). Fall back to the
        // metadata's typeId (the wire-format identifier) as the helper-method suffix; this
        // matches NodeType.encodeMethod / decodeMethod resolution in the default case where
        // typeId equals the GraphQL type name. The customized-typeId / no-NodeType combination
        // is only reachable through the synthesis shim, which is on a retirement track (see
        // graphitron-rewrite/roadmap/retire-synthesis-shims.md).
        if (fallbackTypeNameOrTypeId == null || fallbackTypeNameOrTypeId.isBlank()) return null;
        return new no.sikt.graphitron.rewrite.model.HelperRef.Decode(
            encoderClass, "decode" + fallbackTypeNameOrTypeId, keyColumns);
    }

    /**
     * Returns every {@code @table}-annotated object type whose table name matches
     * {@code sqlTableName} (case-insensitive), in schema declaration order. Multiple object
     * types may legitimately share a table; callers that need a unique mapping handle the
     * ambiguous-and-empty cases themselves (see bare-{@code @nodeId} typeName inference).
     */
    List<String> findGraphQLTypesForTable(String sqlTableName) {
        return typeNamesByTableKey.getOrDefault(sqlTableName.toLowerCase(), List.of());
    }

    private static Map<String, List<String>> buildTypeNamesByTableKey(GraphQLSchema schema) {
        if (schema == null) return Map.of();
        var building = new HashMap<String, List<String>>();
        for (var t : schema.getAllTypesAsList()) {
            if (!(t instanceof GraphQLObjectType o) || !o.hasAppliedDirective(DIR_TABLE)) continue;
            var key = argString(o, DIR_TABLE, ARG_NAME).orElse(o.getName()).toLowerCase();
            building.computeIfAbsent(key, k -> new ArrayList<>()).add(o.getName());
        }
        return building.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }
}
