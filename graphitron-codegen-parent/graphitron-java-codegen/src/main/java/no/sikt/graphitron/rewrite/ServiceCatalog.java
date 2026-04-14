package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.SourcesRef;
import no.sikt.graphitron.rewrite.model.TableRef;

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

    private final BuildContext ctx;

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
        String terminal = terminalTableSqlNameForReference(path, sourceType);
        if (terminal == null) return Optional.empty();
        return resolveColumnInTable(columnName, terminal);
    }

    /**
     * Walks the FK join path to compute the terminal table SQL name. Returns {@code null} when any
     * path step is not a {@link FkJoin} (i.e. the path contains a condition-only step).
     */
    String terminalTableSqlNameForReference(List<JoinStep> path, TableBackedType sourceType) {
        String current = sourceType.table().tableName();
        for (var step : path) {
            if (!(step instanceof FkJoin fk)) return null;
            current = fk.targetTableSqlName();
        }
        return current;
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
     */
    ServiceReflectionResult reflectServiceMethod(String className, String methodName,
            Set<String> argNames, Set<String> ctxKeys) {
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
            var params = new ArrayList<MethodRef.Param>();
            for (var p : javaMethod.getParameters()) {
                String pName = p.isNamePresent() ? p.getName() : null;
                String displayName = pName != null ? pName : p.getType().getSimpleName();
                String typeName = p.getParameterizedType().getTypeName();
                if (pName != null && argNames.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, new ParamSource.Arg()));
                } else if (pName != null && ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param.Typed(displayName, typeName, new ParamSource.Context()));
                } else {
                    Optional<SourcesRef> sourcesRef = classifySourcesType(p.getParameterizedType());
                    if (sourcesRef.isEmpty()) {
                        return new ServiceReflectionResult(null,
                            "parameter '" + displayName + "' in method '" + methodName
                            + "' has an unrecognized sources type: '" + typeName + "'");
                    }
                    params.add(new MethodRef.Param.Sourced(displayName, sourcesRef.get()));
                }
            }
            return new ServiceReflectionResult(
                new MethodRef(className, methodName, javaMethod.getReturnType().getName(), List.copyOf(params)),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, "class '" + className + "' could not be loaded");
        }
    }

    /**
     * Classifies the element type of a {@code List<?>} SOURCES parameter into a {@link SourcesRef}
     * variant, or returns {@link Optional#empty()} when the type is not recognised.
     */
    static Optional<SourcesRef> classifySourcesType(java.lang.reflect.Type paramType) {
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
                    List<String> pkTypes = Arrays.stream(ept.getActualTypeArguments())
                        .map(java.lang.reflect.Type::getTypeName)
                        .toList();
                    return Optional.of(new SourcesRef.RowKeyed(pkTypes));
                }
            }
            if (rawName.startsWith("org.jooq.Record")) {
                String suffix = rawName.substring("org.jooq.Record".length());
                if (suffix.matches("\\d+")) {
                    List<String> pkTypes = Arrays.stream(ept.getActualTypeArguments())
                        .map(java.lang.reflect.Type::getTypeName)
                        .toList();
                    return Optional.of(new SourcesRef.RecordKeyed(pkTypes));
                }
            }
        } else if (elementType instanceof Class<?> elementClass
                && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            return Optional.of(new SourcesRef.TableRecordKeyed(elementClass.getName()));
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
