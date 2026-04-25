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
import no.sikt.graphitron.rewrite.model.GraphitronType;
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

    // ===== Argument names =====

    static final String ARG_CONTEXT_ARGUMENTS  = "contextArguments";
    static final String ARG_RECORD             = "record";
    static final String ARG_SERVICE_REF        = "service";
    static final String ARG_TABLE_METHOD_REF   = "tableMethodReference";
    static final String ARG_METHOD             = "method";
    static final String ARG_VALUE              = "value";
    static final String ARG_NAME               = "name";
    static final String ARG_ON                 = "on";
    static final String ARG_TYPE_ID            = "typeId";
    static final String ARG_KEY_COLUMNS        = "keyColumns";
    static final String ARG_TYPE_NAME          = "typeName";
    static final String ARG_JAVA_NAME          = "javaName";
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

    BuildContext(GraphQLSchema schema, JooqCatalog catalog, RewriteContext ctx) {
        this.schema = schema;
        this.catalog = catalog;
        this.ctx = ctx;
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
     * Resolves a SQL table name to a {@link TableRef} by looking it up in the jOOQ catalog.
     * Returns a minimal {@code TableRef} (SQL name only, empty PK columns) when the catalog is
     * unavailable or the table cannot be found.
     */
    private TableRef resolveTable(String sqlName) {
        return catalog.findTable(sqlName).map(e -> {
            var pk = e.table().getPrimaryKey();
            List<ColumnRef> pkColumns = pk == null ? List.of() : pk.getFields().stream()
                .map(f -> catalog.findColumn(e.table(), f.getName()))
                .<JooqCatalog.ColumnEntry>flatMap(Optional::stream)
                .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()))
                .toList();
            return new TableRef(sqlName, e.javaFieldName(), e.table().getClass().getSimpleName(), pkColumns);
        }).orElse(new TableRef(sqlName, "", "", List.of()));
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
                parsePathElement(asMap(v), currentSource, fieldName, stepIndex, resolvedElements, errors);
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
                resolvedElements.add(synthesizeFkJoin(fks.get(0), startSqlTableName, fieldName, 0, null));
            } else {
                return new ParsedPath(List.of(),
                    fkCountMessage(startSqlTableName, targetSqlTableName, fks, /*directiveAbsent=*/true));
            }
        }
        return new ParsedPath(List.copyOf(resolvedElements), null);
    }

    /**
     * Builds an {@link FkJoin} step for a foreign key that connects {@code sourceSqlName} to some
     * other table. Traversal direction is inferred from which side of the FK the source name
     * touches (case-insensitive). The step alias follows the explicit-path convention,
     * {@code fieldName + "_" + stepIndex}, so inferred and explicit position-0 steps produce
     * record-equivalent {@code FkJoin} values for the same shape.
     *
     * <p>{@code sourceSqlName} must be non-null; callers gate inference on that precondition.
     * {@code whereFilter} is {@code null} for pure inference; the {@code {table:}} branch in
     * {@link #parsePathElement} may pass a resolved {@link MethodRef} when the element carries
     * a {@code condition:} sub-argument.
     */
    private FkJoin synthesizeFkJoin(ForeignKey<?, ?> f, String sourceSqlName, String fieldName,
            int stepIndex, MethodRef whereFilter) {
        String fkSideTable  = f.getTable().getName();
        String keySideTable = f.getKey().getTable().getName();
        String targetSqlName = sourceSqlName.equalsIgnoreCase(fkSideTable) ? keySideTable : fkSideTable;
        String fkJavaConstant = catalog.fkJavaConstantName(f.getName()).orElse("");
        TableRef targetTable = resolveTable(targetSqlName);
        TableRef sourceTable = resolveTable(sourceSqlName);
        List<ColumnRef> sourceColumns = resolveFkColumnRefs(f.getTable(), f.getFields());
        List<ColumnRef> targetColumns = resolveFkColumnRefs(f.getKey().getTable(), f.getKey().getFields());
        String alias = fieldName + "_" + stepIndex;
        return new FkJoin(f.getName(), fkJavaConstant, sourceTable, sourceColumns, targetTable, targetColumns, whereFilter, alias);
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
            if (directiveAbsent) msg += "; add a @reference directive to specify the join path";
            return msg;
        }
        String msg = "multiple foreign keys found between tables '" + source + "' and '" + target + "'";
        if (directiveAbsent) {
            msg += "; add a @reference directive to specify the join path";
        } else {
            String fkNames = fks.stream().map(ForeignKey::getName).collect(Collectors.joining(", "));
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
            String fieldName, int stepIndex, List<JoinStep> out, List<String> errors) {
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
            String targetSqlName;
            if (currentSourceSqlName == null) {
                targetSqlName = keySideTable;
            } else if (currentSourceSqlName.equalsIgnoreCase(fkSideTable)) {
                targetSqlName = keySideTable;
            } else if (currentSourceSqlName.equalsIgnoreCase(keySideTable)) {
                targetSqlName = fkSideTable;
            } else {
                errors.add("key '" + f.getName() + "' does not connect to table '" + currentSourceSqlName + "'"
                    + candidateHint(currentSourceSqlName, List.of(fkSideTable, keySideTable)));
                return;
            }
            String fkJavaConstant = catalog.fkJavaConstantName(f.getName()).orElse("");
            TableRef targetTable = resolveTable(targetSqlName);
            TableRef sourceTable = resolveTable(currentSourceSqlName != null ? currentSourceSqlName
                : (targetSqlName.equalsIgnoreCase(keySideTable) ? fkSideTable : keySideTable));
            List<ColumnRef> sourceColumns = resolveFkColumnRefs(f.getTable(), f.getFields());
            List<ColumnRef> targetColumns = resolveFkColumnRefs(f.getKey().getTable(), f.getKey().getFields());
            MethodRef whereFilter = hasCondition ? resolveConditionRef(asMap(conditionRaw)) : null;
            out.add(new FkJoin(f.getName(), fkJavaConstant, sourceTable, sourceColumns, targetTable, targetColumns, whereFilter, alias));
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
            MethodRef whereFilter = hasCondition ? resolveConditionRef(asMap(conditionRaw)) : null;
            out.add(synthesizeFkJoin(fks.get(0), currentSourceSqlName, fieldName, stepIndex, whereFilter));
            return;
        }
        if (hasCondition) {
            Map<String, Object> condMap = asMap(conditionRaw);
            MethodRef resolved = resolveConditionRef(condMap);
            if (resolved != null) {
                out.add(new ConditionJoin(resolved, alias));
            } else {
                errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
            }
            return;
        }
        errors.add("path element has neither 'key', 'table', nor 'condition'");
    }

    /**
     * Resolves an {@code ExternalCodeReference} condition map to a {@link MethodRef} via
     * {@link ServiceCatalog#reflectTableMethod}. Returns {@code null} when the class/method cannot
     * be determined or reflection fails — the caller adds a diagnostic error in that case.
     *
     * <p>The deprecated {@code name:} form is resolved via {@link RewriteContext#namedReferences()},
     * exactly as in {@link FieldBuilder#parseExternalRef}.
     */
    private MethodRef resolveConditionRef(Map<String, Object> conditionMap) {
        String className = Optional.ofNullable(conditionMap.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(conditionMap.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String name = Optional.ofNullable(conditionMap.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (name != null) {
                className = ctx.namedReferences().get(name);
            }
        }
        if (className == null || methodName == null || svc == null) return null;
        var result = svc.reflectTableMethod(className, methodName, Set.of(), Set.of(), null);
        return result.failed() ? null : result.ref();
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
     */
    record ConditionDirective(
        String className,
        String methodName,
        boolean override,
        List<String> contextArguments
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
        return new ConditionDirective(className, methodName, override, ctxArgs);
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
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            Set.of(inputFieldName), Set.copyOf(cond.contextArguments()), null);
        if (result.failed()) {
            errors.add("input field '" + inputFieldName + "' @condition: " + result.failureReason());
            return Optional.empty();
        }
        var methodRef = result.ref();
        return Optional.of(new ArgConditionRef(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()),
            cond.override()));
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
    InputFieldResolution classifyInputField(
            GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable,
            Set<String> expandingTypes, List<String> errors) {
        String name = field.getName();
        GraphQLType type = field.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean hasFieldDir = field.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDir
            ? argString(field, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        if ("ID".equals(typeName) && !list && field.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> refTypeName = argString(field, DIR_NODE_ID, ARG_TYPE_NAME);
            if (refTypeName.isPresent()) {
                // ctx.types may be null during the first type-builder pass; resolve via
                // schema + catalog directly so this classifier works in both passes.
                var rawGqlType = schema.getType(refTypeName.get());
                if (rawGqlType == null) {
                    return new InputFieldResolution.Unresolved(name, null,
                        "@nodeId(typeName:) type '" + refTypeName.get() + "' does not exist in the schema"
                        + candidateHint(refTypeName.get(), schema.getAllTypesAsList().stream()
                            .map(t -> t.getName()).filter(n -> !n.startsWith("__")).toList()));
                }
                // Must be a table-backed object type to be a NodeType
                if (!(rawGqlType instanceof GraphQLObjectType gqlObjType)
                        || !gqlObjType.hasAppliedDirective(DIR_TABLE)) {
                    return new InputFieldResolution.Unresolved(name, null,
                        "@nodeId(typeName:) type '" + refTypeName.get()
                        + "' is not a node type (not a @table-annotated object type)");
                }
                String targetTableName = argString(gqlObjType, DIR_TABLE, ARG_NAME)
                    .orElse(refTypeName.get().toLowerCase());
                // Prefer catalog metadata; fall back to ctx.types (when available post-first-pass)
                // for SDL-only @node types.
                String targetTypeId;
                List<ColumnRef> targetKeyColumns;
                var metaOpt = catalog.nodeIdMetadata(targetTableName);
                if (metaOpt.isPresent()) {
                    targetTypeId = metaOpt.get().typeId();
                    targetKeyColumns = metaOpt.get().keyColumns();
                } else if (types != null && types.get(refTypeName.get()) instanceof NodeType nt) {
                    targetTypeId = nt.typeId();
                    targetKeyColumns = nt.nodeKeyColumns();
                } else if (gqlObjType.hasAppliedDirective(DIR_NODE)) {
                    // @node declared without catalog metadata — no error, but empty keyColumns
                    // (caller must ensure table has real metadata or be aware of the empty list).
                    targetTypeId = argString(gqlObjType, DIR_NODE, ARG_TYPE_ID)
                        .orElse(refTypeName.get());
                    targetKeyColumns = List.of();
                } else {
                    return new InputFieldResolution.Unresolved(name, null,
                        "@nodeId(typeName:) type '" + refTypeName.get()
                        + "' is not a node type (missing @node or KjerneJooqGenerator metadata)");
                }
                TableRef targetTable = resolveTable(targetTableName);
                var nodeRefPath = parsePath(field, name, resolvedTable.tableName(), targetTable.tableName());
                if (nodeRefPath.hasError()) {
                    return new InputFieldResolution.Unresolved(name, null, nodeRefPath.errorMessage());
                }
                Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
                return new InputFieldResolution.Resolved(new InputField.NodeIdReferenceField(
                    parentTypeName, name, locationOf(field), refTypeName.get(), nonNull,
                    resolvedTable, targetTypeId, targetKeyColumns,
                    nodeRefPath.elements(), cond));
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
                        col, path.elements(), cond));
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
            for (var nested : nestedInputType.getFieldDefinitions().stream()
                    .filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED)).toList()) {
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
        var colEntry = catalog.findColumn(tableName, columnName);
        if (colEntry.isPresent()) {
            var e = colEntry.get();
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
            return new InputFieldResolution.Resolved(new InputField.ColumnField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()), cond));
        }
        // NodeId: scalar ID field whose backing table carries node-identity metadata
        // (__NODE_TYPE_ID / __NODE_KEY_COLUMNS constants emitted by KjerneJooqGenerator). Two
        // routes produce InputField.NodeIdField here: the declared form (field carries @nodeId)
        // and the migration-shim form (bare scalar ID on a node-type table). The shim fires a
        // per-site deprecation diagnostic — the canonical form is to declare @nodeId explicitly.
        // Shim is retired at R7. See rewrite-roadmap.md.
        if ("ID".equals(typeName) && !list) {
            Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta = catalog.nodeIdMetadata(tableName);
            if (nodeIdMeta.isPresent()) {
                if (!field.hasAppliedDirective(DIR_NODE_ID)) {
                    NODE_ID_SHIM_LOGGER.warn("input field '{}.{}' synthesizes NodeIdField without"
                        + " '@nodeId' — declare the directive explicitly; synthesis shim will be"
                        + " removed in a future release. See rewrite-roadmap.md",
                        parentTypeName, name);
                }
                return new InputFieldResolution.Resolved(new InputField.NodeIdField(
                    parentTypeName, name, locationOf(field),
                    nodeIdMeta.get().typeId(), nodeIdMeta.get().keyColumns()));
            }
        }
        return new InputFieldResolution.Unresolved(name, columnName,
            "no column '" + columnName + "' found in table '" + tableName + "'");
    }
}
