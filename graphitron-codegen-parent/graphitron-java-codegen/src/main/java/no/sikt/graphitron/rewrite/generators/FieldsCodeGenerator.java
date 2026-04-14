package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.SourcesRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates a {@link TypeSpec} for one {@code <TypeName>Fields} class in {@code rewrite.types}.
 *
 * <p>The class is named after the GraphQL type (e.g. {@code FilmFields} for GraphQL type
 * {@code Film}). This is distinct from the SQL-scope class in {@code rewrite.tables}, which is
 * named after the jOOQ table class.
 *
 * <p>Each class contains:
 * <ul>
 *   <li>For most fields: one {@code public static Object fieldName(DataFetchingEnvironment env)}
 *       stub throwing {@link UnsupportedOperationException}.</li>
 *   <li>For {@link QueryField.QueryLookupTableField}: an async data fetcher stub returning
 *       {@code CompletableFuture<List<Record>>} and a bespoke synchronous
 *       {@code lookupFieldName(DataFetchingEnvironment env, SelectedField sel)} stub.</li>
 *   <li>For {@link ChildField.ServiceTableField} with a resolved service method reference:
 *       an async DataLoader-based data fetcher and a rows method
 *       {@code loadFieldName(List<Row1<T>>, DataFetchingEnvironment, SelectedField)} (where T is
 *       the parent PK column Java type) that extracts
 *       arguments, calls the service, and delegates to the table's {@code selectMany}/
 *       {@code selectOne}.</li>
 *   <li>A {@code wiring()} method that registers each data fetcher by method reference.</li>
 * </ul>
 */
public class FieldsCodeGenerator {

    private static final ClassName ENV               = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTED_FIELD    = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName TYPE_WIRING       = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER    = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");
    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName LIST              = ClassName.get("java.util", "List");
    private static final ClassName RECORD            = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT            = ClassName.get("org.jooq", "Result");
    private static final ClassName ROW               = ClassName.get("org.jooq", "Row");
    private static final ClassName SORT_FIELD        = ClassName.get("org.jooq", "SortField");
    private static final ClassName DSL               = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DATA_LOADER       = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName GRAPHITRON_CONTEXT = ClassName.get("no.sikt.graphql", "GraphitronContext");

    /**
     * Generates the {@code *Fields} class TypeSpec for the given GraphQL type.
     *
     * @param typeName    the GraphQL type name (e.g. {@code "Language"})
     * @param parentTable the resolved {@link TableRef} for the type, or {@code null} for root types
     * @param fields      the classified fields belonging to this type
     */
    public TypeSpec generate(String typeName, TableRef parentTable, List<GraphitronField> fields) {
        var className = typeName + "Fields";
        var builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC);

        boolean needsGraphitronContextHelper = false;

        for (var field : fields) {
            if (field instanceof QueryField.QueryLookupTableField lookup) {
                builder.addMethod(buildLookupDataFetcher(lookup));
                builder.addMethod(buildLookupMethod(lookup));
            } else if (field instanceof ChildField.ServiceTableField sf
                    && parentTable != null) {
                builder.addMethod(buildServiceDataFetcher(sf, sf.method(), sf.returnType(), parentTable, className));
                builder.addMethod(buildServiceRowsMethod(sf, sf.method(), sf.returnType(), sf.returnType().table(), parentTable));
                needsGraphitronContextHelper = true;
            } else if (field instanceof ChildField.SplitTableField stf) {
                builder.addMethod(buildSplitQueryDataFetcher(stf));
                builder.addMethod(buildSplitRowsMethod(stf));
            } else if (field instanceof ChildField.ColumnField cf && parentTable != null) {
                builder.addMethod(buildColumnFieldFetcher(cf, parentTable));
            } else if (field instanceof QueryField.QueryTableField qtf) {
                builder.addMethod(buildQueryTableFieldFetcher(qtf));
            } else {
                builder.addMethod(buildFieldStub(field.name()));
            }
        }

        if (needsGraphitronContextHelper) {
            builder.addMethod(buildGraphitronContextHelper());
        }

        builder.addMethod(buildWiringMethod(typeName, className, fields));

        return builder.build();
    }

    /**
     * Generates a data fetcher for a {@link ChildField.ColumnField} that reads the column value
     * directly from the jOOQ {@code Record} in the source position.
     *
     * <p>Generated code:
     * <pre>{@code
     * public static Object title(DataFetchingEnvironment env) {
     *     return ((Record) env.getSource()).get(Tables.FILM.TITLE);
     * }
     * }</pre>
     */
    private MethodSpec buildColumnFieldFetcher(ChildField.ColumnField cf, TableRef parentTable) {
        var tablesClass = ClassName.get(GeneratorConfig.getGeneratedJooqPackage(), "Tables");
        return MethodSpec.methodBuilder(cf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("return (($T) env.getSource()).get($T.$L.$L)",
                RECORD, tablesClass, parentTable.javaFieldName(), cf.column().javaName())
            .build();
    }

    /**
     * Generates a data fetcher for a {@link QueryField.QueryTableField} that builds the WHERE
     * condition from argument filters, builds the ORDER BY from the order spec, and delegates
     * to the table's {@code selectMany} or {@code selectOne}.
     *
     * <p>List/connection fields call {@code selectMany} and return {@code Result<Record>}.
     * Single fields call {@code selectOne} and return {@code Record}.
     */
    private MethodSpec buildQueryTableFieldFetcher(QueryField.QueryTableField qtf) {
        var tableRef = qtf.returnType().table();
        var tableClass = ClassName.get(GeneratorConfig.outputPackage() + ".rewrite.tables", tableRef.javaClassName());
        var tablesClass = ClassName.get(GeneratorConfig.getGeneratedJooqPackage(), "Tables");
        boolean isList = !(qtf.returnType().wrapper() instanceof FieldWrapper.Single);

        var returnType = isList
            ? ParameterizedTypeName.get(RESULT, RECORD)
            : RECORD;

        var builder = MethodSpec.methodBuilder(qtf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env");

        // Build condition from filters
        builder.addCode(buildConditionCode(qtf.filters(), tablesClass, tableRef));

        if (isList) {
            // Build orderBy from spec
            builder.addCode(buildOrderByCode(qtf.orderBy(), tablesClass, tableRef));
            builder.addStatement("return $T.selectMany(env, condition, orderBy)", tableClass);
        } else {
            builder.addStatement("return $T.selectOne(env, condition)", tableClass);
        }

        return builder.build();
    }

    /**
     * Generates the {@code Condition condition = ...} local variable from a list of
     * {@link WhereFilter}s. When the list is empty, emits {@code DSL.noCondition()}.
     */
    private CodeBlock buildConditionCode(java.util.List<WhereFilter> filters, ClassName tablesClass, TableRef tableRef) {
        var code = CodeBlock.builder();
        code.addStatement("var condition = $T.noCondition()", DSL);
        for (var filter : filters) {
            switch (filter) {
                case WhereFilter.ColumnFilter cf -> addScalarCondition(code, cf, tablesClass, tableRef);
                case WhereFilter.EnumColumnFilter ef -> addEnumCondition(code, ef, tablesClass, tableRef);
                default -> {} // InputFilter, ConditionFilter: deferred
            }
        }
        return code.build();
    }

    /** Scalar: {@code TABLE.COL.eq(DSL.val(env.getArgument("x"), TABLE.COL))} */
    private void addScalarCondition(CodeBlock.Builder code, WhereFilter.ColumnFilter cf,
            ClassName tablesClass, TableRef tableRef) {
        // col = Tables.FILM.TITLE, val = DSL.val(arg, col)
        String eq = "$T.$L.$L.eq($T.val(env.getArgument($S), $T.$L.$L))";
        Object[] args = {tablesClass, tableRef.javaFieldName(), cf.column().javaName(),
            DSL, cf.name(), tablesClass, tableRef.javaFieldName(), cf.column().javaName()};
        if (cf.nonNull()) {
            code.addStatement("condition = condition.and(" + eq + ")", args);
        } else {
            code.addStatement("if (env.getArgument($S) != null) condition = condition.and(" + eq + ")",
                concat(new Object[]{cf.name()}, args));
        }
    }

    /** Enum: {@code TABLE.COL.eq(DSL.val(EnumClass.valueOf(arg), TABLE.COL))} */
    private void addEnumCondition(CodeBlock.Builder code, WhereFilter.EnumColumnFilter ef,
            ClassName tablesClass, TableRef tableRef) {
        var enumClass = ClassName.bestGuess(ef.enumClassName());
        String eq = "$T.$L.$L.eq($T.val($T.valueOf(env.<$T>getArgument($S)), $T.$L.$L))";
        Object[] args = {tablesClass, tableRef.javaFieldName(), ef.column().javaName(),
            DSL, enumClass, String.class, ef.name(),
            tablesClass, tableRef.javaFieldName(), ef.column().javaName()};
        if (ef.nonNull()) {
            code.addStatement("condition = condition.and(" + eq + ")", args);
        } else {
            code.addStatement("if (env.getArgument($S) != null) condition = condition.and(" + eq + ")",
                concat(new Object[]{ef.name()}, args));
        }
    }

    private static Object[] concat(Object[] a, Object[] b) {
        var result = new Object[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Generates the {@code List<SortField<?>> orderBy = ...} local variable from an
     * {@link OrderBySpec}.
     */
    private CodeBlock buildOrderByCode(OrderBySpec orderBy, ClassName tablesClass, TableRef tableRef) {
        var code = CodeBlock.builder();
        switch (orderBy) {
            case OrderBySpec.Fixed fixed -> {
                if (fixed.columns().isEmpty()) {
                    code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
                } else {
                    var parts = CodeBlock.builder();
                    for (int i = 0; i < fixed.columns().size(); i++) {
                        var col = fixed.columns().get(i);
                        if (i > 0) parts.add(", ");
                        String dir = "ASC".equalsIgnoreCase(fixed.direction()) ? "asc" : "desc";
                        parts.add("$T.$L.$L.$L()", tablesClass, tableRef.javaFieldName(), col.column().javaName(), dir);
                    }
                    code.addStatement("$T<$T<?>> orderBy = $T.of($L)", LIST, SORT_FIELD, LIST, parts.build());
                }
            }
            case OrderBySpec.Argument arg -> {
                // Dynamic @orderBy: stub for now, fall back to base
                if (arg.base() != null) {
                    code.add(buildOrderByCode(arg.base(), tablesClass, tableRef));
                } else {
                    code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
                }
            }
            case OrderBySpec.None none ->
                code.addStatement("$T<$T<?>> orderBy = $T.of()", LIST, SORT_FIELD, LIST);
        }
        return code.build();
    }

    private MethodSpec buildFieldStub(String fieldName) {
        return MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildLookupDataFetcher(QueryField.QueryLookupTableField field) {
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, ParameterizedTypeName.get(LIST, RECORD));
        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildLookupMethod(QueryField.QueryLookupTableField field) {
        var methodName = "lookup" + capitalize(field.name());
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, RECORD))
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Builds the DataLoader-based async data fetcher for a {@link ChildField.ServiceTableField}
     * with a resolved service method.
     *
     * <p>List/connection: returns {@code CompletableFuture<List<Record>>}.
     * Single: returns {@code CompletableFuture<Record>}.
     *
     * <p>The DataLoader key type and key construction expression are determined by the
     * {@link SourcesRef} variant of the SOURCES parameter:
     * <ul>
     *   <li>{@link SourcesRef.RowKeyed} — key is {@code RowN<T1,...>} built via {@code DSL.row(...)}</li>
     *   <li>{@link SourcesRef.RecordKeyed} — key is {@code RecordN<T1,...>} built via {@code record.into(field1, field2, ...)}</li>
     *   <li>{@link SourcesRef.TableRecordKeyed} — key is the whole parent record cast to the declared table-record type</li>
     * </ul>
     */
    private MethodSpec buildServiceDataFetcher(
            ChildField.ServiceTableField sf,
            MethodRef smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef prt,
            String className) {

        boolean isList = !(tb.wrapper() instanceof FieldWrapper.Single);
        var valueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, valueType);

        var sourcesParam = smr.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        var sourcesRef = sourcesParam.sourcesRef();

        TypeName keyType = switch (sourcesRef) {
            case SourcesRef.RowKeyed rk          -> buildRowKeyType(rk.pkJavaTypes());
            case SourcesRef.RecordKeyed rk       -> buildRecordNKeyType(rk.pkJavaTypes());
            case SourcesRef.TableRecordKeyed trk -> ClassName.bestGuess(trk.fqClassName());
            case SourcesRef.ResultKeyed rk       -> ClassName.bestGuess(rk.fqClassName());
        };

        var loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        String rowsMethodName = "load" + capitalize(sf.name());

        var lambdaBlock = CodeBlock.builder()
            .add("(keys, batchEnv) -> {\n")
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("$T sel = dfe.getSelectionSet().getField($S)", SELECTED_FIELD, sf.name())
            .addStatement("return $T.completedFuture($L(keys, dfe, sel))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var methodBuilder = MethodSpec.methodBuilder(sf.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("$T name = graphitronContext(env).getDataLoaderName(env)", String.class)
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.newDataLoaderWithContext($L));\n",
                loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        // Emit the key expression — varies by SourcesRef variant.
        var tablesClass = ClassName.get(GeneratorConfig.getGeneratedJooqPackage(), "Tables");
        switch (sourcesRef) {
            case SourcesRef.RowKeyed rk -> {
                String tableField = prt.javaFieldName();
                List<ColumnRef> pkCols = prt.primaryKeyColumns();
                var rowArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) rowArgs.add(", ");
                    rowArgs.add("(($T) env.getSource()).get($T.$L.$L)",
                        RECORD, tablesClass, tableField, pkCols.get(i).javaName());
                }
                methodBuilder.addStatement("$T key = $T.row($L)", keyType, DSL, rowArgs.build());
            }
            case SourcesRef.RecordKeyed rk -> {
                String tableField = prt.javaFieldName();
                List<ColumnRef> pkCols = prt.primaryKeyColumns();
                var intoArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) intoArgs.add(", ");
                    intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
                }
                methodBuilder.addStatement("$T key = (($T) env.getSource()).into($L)",
                    keyType, RECORD, intoArgs.build());
            }
            case SourcesRef.TableRecordKeyed trk ->
                methodBuilder.addStatement("$T key = ($T) env.getSource()", keyType, keyType);
            case SourcesRef.ResultKeyed rk ->
                methodBuilder.addStatement("$T key = ($T) env.getSource()", keyType, keyType);
        }

        return methodBuilder
            .addStatement("return loader.load(key, env)")
            .build();
    }

    /**
     * Builds the rows method for a {@link ChildField.ServiceTableField}. The rows method:
     * <ol>
     *   <li>Extracts GraphQL arguments from the DFE.</li>
     *   <li>Extracts context values via {@code GraphitronContext.getContextArgument}.</li>
     *   <li>Calls the service method with {@code keys} as the sources parameter.</li>
     *   <li>Returns via the table's {@code selectMany} or {@code selectOne}.</li>
     * </ol>
     *
     * <p>The {@code keys} parameter type mirrors the service method's SOURCES parameter type,
     * derived from the {@link SourcesRef} variant:
     * <ul>
     *   <li>{@link SourcesRef.RowKeyed} — {@code List<RowN<T1,...>>}</li>
     *   <li>{@link SourcesRef.RecordKeyed} — {@code List<RecordN<T1,...>>}</li>
     *   <li>{@link SourcesRef.TableRecordKeyed} — {@code List<SomeTableRecord>}</li>
     * </ul>
     */
    private MethodSpec buildServiceRowsMethod(
            ChildField.ServiceTableField sf,
            MethodRef smr,
            ReturnTypeRef.TableBoundReturnType tb,
            TableRef rt,
            TableRef prt) {

        boolean isList = !(tb.wrapper() instanceof FieldWrapper.Single);
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var returnType = isList ? ParameterizedTypeName.get(LIST, listOfRecord) : listOfRecord;

        var sourcesParam = smr.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        var sourcesRef = sourcesParam.sourcesRef();

        TypeName keysElementType = switch (sourcesRef) {
            case SourcesRef.RowKeyed rk          -> buildRowKeyType(rk.pkJavaTypes());
            case SourcesRef.RecordKeyed rk       -> buildRecordNKeyType(rk.pkJavaTypes());
            case SourcesRef.TableRecordKeyed trk -> ClassName.bestGuess(trk.fqClassName());
            case SourcesRef.ResultKeyed rk       -> ClassName.bestGuess(rk.fqClassName());
        };

        var builder = MethodSpec.methodBuilder("load" + capitalize(sf.name()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ParameterizedTypeName.get(LIST, keysElementType), "keys")
            .addParameter(ENV, "dfe")
            .addParameter(SELECTED_FIELD, "sel");

        // Emit arg and context extraction statements using switch on ParamSource
        for (var param : smr.params()) {
            if (param instanceof MethodRef.Param.Typed t) switch (t.source()) {
                case ParamSource.Arg a -> builder.addStatement(
                    "$T $L = dfe.getArgument($S)",
                    Object.class, t.name(), t.name());
                case ParamSource.Context c -> builder.addStatement(
                    "$T $L = graphitronContext(dfe).getContextArgument(dfe, $S)",
                    Object.class, t.name(), t.name());
                default -> {} // DslContext, Table, SourceTable: not applicable in service rows method
            }
            // MethodRef.Param.Sourced: 'keys' is passed as the first argument directly
        }

        // Build service call argument list — SOURCES param replaced by 'keys'
        var serviceCallArgs = smr.params().stream()
            .map(p -> p instanceof MethodRef.Param.Sourced ? "keys" : p.name())
            .toList();

        builder.addStatement(
            "$T serviceResult = $L.$L($L)",
            Object.class,
            smr.className(),
            smr.methodName(),
            String.join(", ", serviceCallArgs));

        // Return via table selectMany / selectOne (threading dfe for context access)
        var tableClass = ClassName.get(GeneratorConfig.outputPackage() + ".rewrite.tables", rt.javaClassName());
        boolean isRowKeyed = sourcesRef instanceof SourcesRef.RowKeyed;
        String selectManyName = isRowKeyed ? "selectManyByRowKeys" : "selectManyByRecordKeys";
        String selectOneName = isRowKeyed ? "selectOneByRowKeys" : "selectOneByRecordKeys";
        if (isList) {
            builder.addStatement(
                "return $T.$L(keys, dfe, sel, ($T<?>) serviceResult)",
                tableClass, selectManyName, List.class);
        } else {
            builder.addStatement("return $T.$L(keys, dfe, sel, serviceResult)", tableClass, selectOneName);
        }

        return builder.build();
    }

    /**
     * Private static helper added once per {@code *Fields} class that uses service-field DataLoader
     * generation. Retrieves the {@link no.sikt.graphql.GraphitronContext} from the GraphQL context.
     */
    private MethodSpec buildGraphitronContextHelper() {
        return MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(GRAPHITRON_CONTEXT)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($S)", "graphitronContext")
            .build();
    }

    /**
     * Builds the typed {@code Row1<T>} / {@code Row2<T1,T2>} / … key type for a DataLoader from
     * the ordered list of primary-key column Java type names (e.g. {@code ["java.lang.Long"]}).
     *
     * <p>Falls back to the raw {@link #ROW} interface when the list is empty.
     */
    private static TypeName buildRowKeyType(List<String> pkJavaTypes) {
        if (pkJavaTypes.isEmpty()) {
            return ROW;
        }
        ClassName rowNClass = ClassName.get("org.jooq", "Row" + pkJavaTypes.size());
        TypeName[] typeArgs = pkJavaTypes.stream()
            .map(ClassName::bestGuess)
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(rowNClass, typeArgs);
    }

    /**
     * Builds the typed {@code Record1<T>} / {@code Record2<T1,T2>} / … key type for a DataLoader
     * from the ordered list of primary-key column Java type names (e.g. {@code ["java.lang.Long"]}).
     *
     * <p>Falls back to the raw {@link #RECORD} interface when the list is empty.
     */
    private static TypeName buildRecordNKeyType(List<String> pkJavaTypes) {
        if (pkJavaTypes.isEmpty()) {
            return RECORD;
        }
        ClassName recordNClass = ClassName.get("org.jooq", "Record" + pkJavaTypes.size());
        TypeName[] typeArgs = pkJavaTypes.stream()
            .map(ClassName::bestGuess)
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(recordNClass, typeArgs);
    }

    private MethodSpec buildSplitQueryDataFetcher(ChildField.SplitTableField field) {
        var returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, ParameterizedTypeName.get(LIST, RECORD));
        return MethodSpec.methodBuilder(field.name())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(ENV, "env")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSplitRowsMethod(ChildField.SplitTableField field) {
        var sourcesType = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder("rows" + capitalize(field.name()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(sourcesType, "sources")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildWiringMethod(String typeName, String className, List<GraphitronField> fields) {
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, typeName);

        if (fields.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (var field : fields) {
                body.add("\n.dataFetcher($S, $L::$L)", field.name(), className, field.name());
            }
            body.add(";\n");
            body.unindent();
        }

        return MethodSpec.methodBuilder("wiring")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(WIRING_BUILDER)
            .addCode(body.build())
            .build();
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
