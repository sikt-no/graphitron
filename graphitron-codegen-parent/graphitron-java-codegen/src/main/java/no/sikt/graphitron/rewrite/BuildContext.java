package no.sikt.graphitron.rewrite;

import graphql.language.ArrayValue;
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
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.ConditionJoin;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import org.jooq.ForeignKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared build-time state and stateless utilities used by {@link TypeBuilder},
 * {@link FieldBuilder}, and {@link ServiceCatalog}.
 *
 * <p>{@link #types} is {@code null} until {@link TypeBuilder#buildTypes()} completes its first
 * pass and sets it. All code that reads {@code types} is called only after that point.
 */
class BuildContext {

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

    // ===== Shared state =====

    final GraphQLSchema schema;
    final JooqCatalog catalog;
    /**
     * Populated by {@link TypeBuilder#buildTypes()} at the end of its first pass.
     * All accesses after that point are safe; nothing reads it before it is set.
     */
    Map<String, GraphitronType> types;

    BuildContext(GraphQLSchema schema, JooqCatalog catalog) {
        this.schema = schema;
        this.catalog = catalog;
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
     * Builds a {@code "; available: X, Y, Z"} hint string for error messages, listing
     * {@code candidates} sorted by Levenshtein distance from {@code attempt}.
     */
    static String candidateHint(String attempt, List<String> candidates) {
        if (candidates.isEmpty()) return "";
        String lc = attempt.toLowerCase();
        return "; available: " + candidates.stream()
            .sorted(Comparator.comparingInt(c -> levenshteinDistance(lc, c.toLowerCase())))
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
     * Parses the {@code @reference(path:)} directive on {@code container} into a {@link ParsedPath}.
     * Delegates to {@link #parsePath(GraphQLDirectiveContainer, String)} with no known source table.
     *
     * <p>Returns {@code ParsedPath(List.of(), null)} when no {@code @reference} directive is present.
     * Returns a {@code ParsedPath} with a non-null {@code errorMessage} when any path element
     * cannot be resolved.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container) {
        return parsePath(container, null);
    }

    /**
     * Parses the {@code @reference(path:)} directive on {@code container} into a {@link ParsedPath}.
     *
     * <p>Returns {@code ParsedPath(List.of(), null)} when no {@code @reference} directive is present.
     * Returns a {@code ParsedPath} with a non-null {@code errorMessage} when any path element
     * cannot be resolved.
     *
     * <p>When {@code startSqlTableName} is {@code null} (no table-backed source context), direction
     * is inferred as forward (FK-side → key-side) and connectivity is not validated.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String startSqlTableName) {
        var directive = container.getAppliedDirective(DIR_REFERENCE);
        if (directive == null) return new ParsedPath(List.of(), null);

        var pathArg = directive.getArgument(ARG_PATH);
        if (pathArg == null) return new ParsedPath(List.of(), null);

        Object pathValue = pathArg.getValue();
        List<?> elements = pathValue instanceof List<?> l ? l : List.of(pathValue);

        var resolvedElements = new ArrayList<JoinStep>();
        var errors = new ArrayList<String>();
        String currentSource = startSqlTableName;

        for (var v : elements) {
            if (v instanceof Map<?, ?>) {
                parsePathElement(asMap(v), currentSource, resolvedElements, errors);
                if (!resolvedElements.isEmpty()) {
                    var last = resolvedElements.getLast();
                    currentSource = last instanceof FkJoin fk ? fk.targetTableSqlName() : null;
                }
            }
        }

        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors));
        }
        return new ParsedPath(List.copyOf(resolvedElements), null);
    }

    /**
     * Resolves one {@code @reference} path element into a {@link JoinStep} and appends it to
     * {@code out}. Errors are accumulated in {@code errors}.
     *
     * <p>{@code currentSourceSqlName} is the SQL table name at the current position in the chain,
     * or {@code null} when the source is not a table-backed type. When non-null, FK connectivity is
     * validated (the FK must touch the current source table) and traversal direction is determined
     * precisely. When null, forward traversal (FK-side → key-side) is assumed without validation.
     */
    private void parsePathElement(Map<String, Object> element, String currentSourceSqlName,
            List<JoinStep> out, List<String> errors) {
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
            MethodRef whereFilter = hasCondition ? resolveConditionRef(asMap(conditionRaw)) : null;
            out.add(new FkJoin(f.getName(), targetSqlName, whereFilter));
            return;
        }
        if (tableName.isPresent()) {
            if (currentSourceSqlName == null) {
                errors.add("path element with 'table' requires a known source table — use 'key' instead to name the FK explicitly");
                return;
            }
            var fks = catalog.findForeignKeysBetweenTables(currentSourceSqlName, tableName.get());
            if (fks.isEmpty()) {
                errors.add("no foreign key found between tables '" + currentSourceSqlName + "' and '" + tableName.get() + "'");
                return;
            }
            if (fks.size() > 1) {
                var fkNames = fks.stream().map(ForeignKey::getName).collect(Collectors.joining(", "));
                errors.add("multiple foreign keys found between tables '" + currentSourceSqlName + "' and '" + tableName.get()
                    + "' — use 'key' to specify which: " + fkNames);
                return;
            }
            var f = fks.get(0);
            String fkSideTable  = f.getTable().getName();
            String keySideTable = f.getKey().getTable().getName();
            String targetSqlName = currentSourceSqlName.equalsIgnoreCase(fkSideTable) ? keySideTable : fkSideTable;
            MethodRef whereFilter = hasCondition ? resolveConditionRef(asMap(conditionRaw)) : null;
            out.add(new FkJoin(f.getName(), targetSqlName, whereFilter));
            return;
        }
        if (hasCondition) {
            Map<String, Object> condMap = asMap(conditionRaw);
            MethodRef resolved = resolveConditionRef(condMap);
            if (resolved != null) {
                out.add(new ConditionJoin(resolved));
            } else {
                errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
            }
            return;
        }
        errors.add("path element has neither 'key', 'table', nor 'condition'");
    }

    /**
     * Condition resolution via reflection is implemented in a later deliverable (P3).
     * Returns {@code null} to signal that the condition is unresolved.
     */
    private MethodRef resolveConditionRef(Map<String, Object> conditionMap) {
        return null;
    }

    private String extractConditionQualifiedName(Map<String, Object> conditionMap) {
        Object name = conditionMap.get(ARG_NAME);
        return name != null ? name.toString() : "unknown";
    }
}
