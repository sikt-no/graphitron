package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Handles all reflection-based and jOOQ-catalog-based lookups: resolving Java service methods,
 * resolving tables and columns from the jOOQ catalog, and classifying SOURCES parameter types.
 *
 * <p>This is the mirror of {@link JooqCatalog} for the service layer: it wraps the catalog and
 * adds the Java-reflection logic needed to introspect service classes at build time.
 */
class ServiceCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCatalog.class);

    private final BuildContext ctx;
    /** Ensures the -parameters warning is emitted at most once per build. */
    private boolean parametersWarningEmitted = false;

    ServiceCatalog(BuildContext ctx) {
        this.ctx = ctx;
    }

    // ===== Table and column resolution =====

    Optional<TableRef> resolveTable(String sqlName) {
        return ctx.catalog.findTable(sqlName).map(e -> buildTableRef(e, sqlName));
    }

    Optional<TableRef> resolveTableByRecordClass(Class<?> recordClass) {
        return ctx.catalog.findTableByRecordClass(recordClass)
            .map(e -> buildTableRef(e, e.table().getName()));
    }

    private TableRef buildTableRef(JooqCatalog.TableEntry e, String sqlName) {
        var pk = e.table().getPrimaryKey();
        List<ColumnRef> pkColumns = pk == null
            ? List.of()
            : pk.getFields().stream()
                .map(f -> ctx.catalog.findColumn(e.table(), f.getName()))
                .<JooqCatalog.ColumnEntry>flatMap(Optional::stream)
                .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()))
                .toList();
        return new TableRef(sqlName, e.javaFieldName(), e.table().getClass().getSimpleName(), pkColumns);
    }

    Optional<ColumnRef> resolveKeyColumn(String colName, String tableSqlName) {
        return ctx.catalog.findColumn(tableSqlName, colName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
    }

    Optional<ColumnRef> resolveColumn(String columnName, TableBackedType tableType) {
        return resolveColumnInTable(columnName, tableType.table().tableName());
    }

    Optional<ColumnRef> resolveColumnForReference(String columnName, List<JoinStep> path, TableBackedType sourceType) {
        return resolveColumnForReference(columnName, path, sourceType.table().tableName());
    }

    Optional<ColumnRef> resolveColumnForReference(String columnName, List<JoinStep> path, String startSqlTableName) {
        String terminal = terminalTableSqlName(path, startSqlTableName);
        if (terminal == null) return Optional.empty();
        return resolveColumnInTable(columnName, terminal);
    }

    /**
     * Walks the FK join path from {@code startSqlTableName} and returns the terminal table SQL
     * name. Returns {@code null} when any path step is not a {@link FkJoin} (i.e. the path
     * contains a condition-only step whose target table is unknown at build time).
     */
    String terminalTableSqlName(List<JoinStep> path, String startSqlTableName) {
        String current = startSqlTableName;
        for (var step : path) {
            if (!(step instanceof FkJoin fk)) return null;
            current = fk.targetTable().tableName();
        }
        return current;
    }

    /**
     * Walks the FK join path to compute the terminal table SQL name. Returns {@code null} when any
     * path step is not a {@link FkJoin} (i.e. the path contains a condition-only step).
     */
    String terminalTableSqlNameForReference(List<JoinStep> path, TableBackedType sourceType) {
        return terminalTableSqlName(path, sourceType.table().tableName());
    }

    Optional<ColumnRef> resolveColumnInTable(String columnName, String tableSqlName) {
        return ctx.catalog.findColumn(tableSqlName, columnName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
    }

    /**
     * Returns the SQL table name for a GraphQL type name when the type is table-backed, or
     * {@code null} when the type has no associated table.
     */
    String getTableSqlNameForType(String typeName) {
        var type = ctx.types.get(typeName);
        if (type instanceof TableBackedType tbt) return tbt.table().tableName();
        return null;
    }

    // ===== Service method reflection =====

    /**
     * Loads the service class and method via reflection and classifies each parameter.
     *
     * <p>Parameters whose name matches a GraphQL argument get {@link ParamSource.Arg};
     * parameters whose name matches a context key get {@link ParamSource.Context};
     * all others are classified by {@link #classifySourcesType}.
     *
     * <p>{@code parentPkColumns} is the primary-key column list of the parent type's table.
     * Pass {@link List#of()} when the parent is a root operation type or has no backing table.
     *
     * <p>If the compiler was not invoked with {@code -parameters}, any parameter may lack a name.
     * A warning is logged proactively as soon as any nameless parameter is detected.
     */
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            Set<String> argNames, Set<String> ctxKeys, List<ColumnRef> parentPkColumns) {
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, "service reference is incomplete");
        }
        try {
            Class<?> cls = Class.forName(className);
            var methods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
            if (methods.isEmpty()) {
                var declaredMethodNames = Arrays.stream(cls.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .distinct()
                    .toList();
                return new ServiceReflectionResult(null,
                    "method '" + methodName + "' not found in class '" + className + "'"
                    + BuildContext.candidateHint(methodName, declaredMethodNames));
            }
            var javaMethod = methods.get(0);
            if (Arrays.stream(javaMethod.getParameters()).anyMatch(p -> !p.isNamePresent())) {
                emitParametersWarning();
            }
            var params = new ArrayList<MethodRef.Param>();
            for (var p : javaMethod.getParameters()) {
                if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) {
                    String paramName = p.isNamePresent() ? p.getName() : "dsl";
                    params.add(new MethodRef.Param.Typed(paramName,
                        p.getParameterizedType().getTypeName(), new ParamSource.DslContext()));
                    continue;
                }
                String pName = p.isNamePresent() ? p.getName() : null;
                String displayName = pName != null ? pName : p.getType().getSimpleName();
                String typeName = p.getParameterizedType().getTypeName();
                if (pName != null && argNames.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, new ParamSource.Arg(argExtraction(typeName))));
                } else if (pName != null && ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, new ParamSource.Context()));
                } else {
                    Optional<BatchKey> batchKey = classifySourcesType(p.getParameterizedType(), parentPkColumns);
                    if (batchKey.isEmpty()) {
                        if (pName == null) {
                            return new ServiceReflectionResult(null,
                                "parameter names not available for method '" + methodName + "' in class '" + className
                                + "' — compile with -parameters flag (see warning above for instructions)");
                        }
                        return new ServiceReflectionResult(null,
                            "parameter '" + displayName + "' in method '" + methodName
                            + "' has an unrecognized sources type: '" + typeName + "'");
                    }
                    params.add(new MethodRef.Param.Sourced(displayName, batchKey.get()));
                }
            }
            return new ServiceReflectionResult(
                new MethodRef.Basic(className, methodName, javaMethod.getReturnType().getName(), List.copyOf(params)),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, "class '" + className + "' could not be loaded");
        }
    }

    /**
     * Loads the table-method class and method via reflection and classifies each parameter.
     *
     * <p>Parameters whose type is assignable to {@link org.jooq.Table} get {@link ParamSource.Table};
     * parameters whose name matches a GraphQL argument get {@link ParamSource.Arg};
     * parameters whose name matches a context key get {@link ParamSource.Context}.
     * Any other parameter is an error.
     *
     * <p>If the compiler was not invoked with {@code -parameters}, any parameter may lack a name.
     * A warning is logged proactively as soon as any nameless parameter is detected — even if
     * type-based classification would otherwise succeed — so that the user is notified regardless
     * of whether all parameters happen to have distinct types.
     *
     * <p>The method's return type must match {@code expectedReturnClassName} exactly (the FQCN
     * of the generated jOOQ table class for the field's {@code @table}-bound return type).
     * Wider return types like {@code Table<R>} are rejected; the emitter relies on the strict
     * type so the generated fetcher's local can carry the specific table class
     * (e.g. {@code Film table = Service.method(...)}) and feed it into {@code FilmType.$fields(...)}
     * without a downcast.
     */
    ServiceReflectionResult reflectTableMethod(String className, String methodName,
            Set<String> argNames, Set<String> ctxKeys, String expectedReturnClassName) {
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, "table method reference is incomplete");
        }
        try {
            Class<?> cls = Class.forName(className);
            var methods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
            if (methods.isEmpty()) {
                var declaredMethodNames = Arrays.stream(cls.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .distinct()
                    .toList();
                return new ServiceReflectionResult(null,
                    "method '" + methodName + "' not found in class '" + className + "'"
                    + BuildContext.candidateHint(methodName, declaredMethodNames));
            }
            var javaMethod = methods.get(0);
            if (expectedReturnClassName != null
                    && !javaMethod.getReturnType().getName().equals(expectedReturnClassName)) {
                return new ServiceReflectionResult(null,
                    "method '" + methodName + "' in class '" + className
                    + "' must return the generated jOOQ table class '" + expectedReturnClassName
                    + "' for @tableMethod with a @table-bound return type — got '"
                    + javaMethod.getReturnType().getName() + "'");
            }
            if (Arrays.stream(javaMethod.getParameters()).anyMatch(p -> !p.isNamePresent())) {
                emitParametersWarning();
            }
            var params = new ArrayList<MethodRef.Param>();
            boolean foundTable = false;
            for (var p : javaMethod.getParameters()) {
                if (org.jooq.Table.class.isAssignableFrom(p.getType())) {
                    String paramName = p.isNamePresent() ? p.getName() : "table";
                    params.add(new MethodRef.Param.Typed(paramName,
                        p.getParameterizedType().getTypeName(), new ParamSource.Table()));
                    foundTable = true;
                    continue;
                }
                String pName = p.isNamePresent() ? p.getName() : null;
                if (pName == null) {
                    return new ServiceReflectionResult(null,
                        "parameter names not available for method '" + methodName + "' in class '" + className
                        + "' — compile with -parameters flag (see warning above for instructions)");
                }
                String typeName = p.getParameterizedType().getTypeName();
                if (argNames.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(pName, typeName, new ParamSource.Arg(argExtraction(typeName))));
                } else if (ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(pName, typeName, new ParamSource.Context()));
                } else {
                    return new ServiceReflectionResult(null,
                        "parameter '" + pName + "' in method '" + methodName
                        + "' is not a Table<?> parameter, not a GraphQL argument, and not a context key");
                }
            }
            if (!foundTable) {
                return new ServiceReflectionResult(null,
                    "method '" + methodName + "' in class '" + className
                    + "' has no Table<?> parameter — @tableMethod requires exactly one Table<?> parameter");
            }
            return new ServiceReflectionResult(
                new MethodRef.Basic(className, methodName, javaMethod.getReturnType().getName(), List.copyOf(params)),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, "class '" + className + "' could not be loaded");
        }
    }

    private void emitParametersWarning() {
        if (!parametersWarningEmitted) {
            parametersWarningEmitted = true;
            LOGGER.warn("Parameter names are not available — the class was compiled without the -parameters flag.\n"
                + "  To fix: add <compilerArg>-parameters</compilerArg> to maven-compiler-plugin in your pom.xml:\n"
                + "    <plugin>\n"
                + "      <groupId>org.apache.maven.plugins</groupId>\n"
                + "      <artifactId>maven-compiler-plugin</artifactId>\n"
                + "      <configuration>\n"
                + "        <compilerArgs><arg>-parameters</arg></compilerArgs>\n"
                + "      </configuration>\n"
                + "    </plugin>");
        }
    }

    /**
     * Returns the {@link CallSiteExtraction} for a GraphQL {@code Arg} parameter based on its
     * Java type. jOOQ-generated enums get {@link CallSiteExtraction.EnumValueOf}; all other
     * types default to {@link CallSiteExtraction.Direct}.
     *
     * <p>Text-mapped enum detection (String Java type + GraphQL enum with value mappings) requires
     * GraphQL schema access and is handled as a post-processing step in
     * {@link FieldBuilder#enrichArgExtractions}.
     */
    static CallSiteExtraction argExtraction(String typeName) {
        try {
            if (Class.forName(typeName).isEnum()) {
                return new CallSiteExtraction.EnumValueOf(typeName);
            }
        } catch (ClassNotFoundException ignored) {}
        return new CallSiteExtraction.Direct();
    }

    /**
     * Classifies the element type of a {@code List<?>} SOURCES parameter into a {@link BatchKey}
     * variant, or returns {@link Optional#empty()} when the type is not recognised.
     *
     * <p>For column-based variants ({@code RowN<?>}, {@code RecordN<?>}), {@code parentPkColumns}
     * is used as the authoritative key column list. The column types from the parent PK are used
     * in the generated method signature; the user's declared type args are used only to determine
     * the arity and variant. Pass {@link List#of()} when no parent table context is available.
     */
    static Optional<BatchKey> classifySourcesType(java.lang.reflect.Type paramType,
            List<ColumnRef> parentPkColumns) {
        if (!(paramType instanceof java.lang.reflect.ParameterizedType pt)
                || pt.getRawType() != java.util.List.class) {
            return Optional.empty();
        }
        java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
        if (typeArgs.length != 1) {
            return Optional.empty();
        }
        java.lang.reflect.Type elementType = typeArgs[0];

        if (elementType instanceof java.lang.reflect.ParameterizedType ept
                && ept.getRawType() instanceof Class<?> rawClass) {
            String rawName = rawClass.getName();
            if (rawName.startsWith("org.jooq.Row")) {
                String suffix = rawName.substring("org.jooq.Row".length());
                if (suffix.matches("\\d+")) {
                    return Optional.of(new BatchKey.RowKeyed(parentPkColumns));
                }
            }
            if (rawName.startsWith("org.jooq.Record")) {
                String suffix = rawName.substring("org.jooq.Record".length());
                if (suffix.matches("\\d+")) {
                    return Optional.of(new BatchKey.RecordKeyed(parentPkColumns));
                }
            }
        } else if (elementType instanceof Class<?> elementClass
                && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            return Optional.of(new BatchKey.ObjectBased(elementClass.getName()));
        } else if (elementType instanceof Class<?> elementClass) {
            // Non-TableRecord class — result DTO parent
            return Optional.of(new BatchKey.ObjectBased(elementClass.getName()));
        }

        return Optional.empty();
    }

    // ===== Result container =====

    /**
     * Carries the result of {@link #reflectServiceMethod}: either a successfully resolved
     * {@link MethodRef} or a failure reason string.
     */
    record ServiceReflectionResult(MethodRef ref, String failureReason) {
        boolean failed() { return failureReason != null; }
    }
}
