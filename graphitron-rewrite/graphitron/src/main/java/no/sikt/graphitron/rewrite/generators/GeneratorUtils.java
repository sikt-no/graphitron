package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * Shared utilities and resolved name records used across all generator classes.
 *
 * <p>The {@code ClassName} constants below are package-private so generators in this package
 * can import them via {@code import static GeneratorUtils.*}. Having one definition prevents
 * drift between generators that target the same runtime types.
 */
class GeneratorUtils {

    // -----------------------------------------------------------------------
    // Shared ClassName constants (package-private — imported via static import)
    // -----------------------------------------------------------------------

    /** {@code org.jooq.Record} */
    static final ClassName RECORD           = ClassName.get("org.jooq", "Record");
    /** {@code org.jooq.Row} */
    static final ClassName ROW              = ClassName.get("org.jooq", "Row");
    /** {@code org.jooq.Result} */
    static final ClassName RESULT           = ClassName.get("org.jooq", "Result");
    /** {@code org.jooq.Condition} */
    static final ClassName CONDITION        = ClassName.get("org.jooq", "Condition");
    /** {@code org.jooq.SortField} */
    static final ClassName SORT_FIELD       = ClassName.get("org.jooq", "SortField");
    /** {@code org.jooq.impl.DSL} */
    static final ClassName DSL              = ClassName.get("org.jooq.impl", "DSL");
    /** {@code java.util.List} */
    static final ClassName LIST             = ClassName.get("java.util", "List");
    /** {@code graphql.schema.DataFetchingEnvironment} */
    static final ClassName ENV              = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    /** {@code graphql.schema.SelectedField} */
    static final ClassName SELECTED_FIELD   = ClassName.get("graphql.schema", "SelectedField");
    /** {@code <outputPackage>.schema.GraphitronContext} — generated per build; see {@link no.sikt.graphitron.rewrite.generators.util.GraphitronContextInterfaceGenerator}. */
    static ClassName graphitronContext(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", "GraphitronContext");
    }

    // -----------------------------------------------------------------------
    // ResolvedTableNames
    // -----------------------------------------------------------------------

    /**
     * The three JavaPoet {@link ClassName}s that every SQL-touching generator method resolves
     * from a {@link TableRef} and the return type name.
     *
     * <ul>
     *   <li>{@link #tablesClass} — jOOQ {@code Tables} constants class
     *       (e.g. {@code no.example.jooq.Tables})</li>
     *   <li>{@link #jooqTableClass} — the concrete jOOQ table class
     *       (e.g. {@code no.example.jooq.tables.Film})</li>
     *   <li>{@link #typeClass} — the generated Graphitron type class
     *       (e.g. {@code no.example.types.Film})</li>
     * </ul>
     */
    record ResolvedTableNames(ClassName tablesClass, ClassName jooqTableClass, ClassName typeClass) {

        static ResolvedTableNames of(TableRef tableRef, String returnTypeName, String outputPackage, String jooqPackage) {
            return new ResolvedTableNames(
                ClassName.get(jooqPackage, "Tables"),
                ClassName.get(jooqPackage + ".tables", tableRef.javaClassName()),
                ClassName.get(outputPackage + ".types", returnTypeName));
        }

        /** Resolves only {@link #tablesClass} and {@link #jooqTableClass} — use when the type class is not needed. */
        static ResolvedTableNames ofTable(TableRef tableRef, String jooqPackage) {
            return new ResolvedTableNames(
                ClassName.get(jooqPackage, "Tables"),
                ClassName.get(jooqPackage + ".tables", tableRef.javaClassName()),
                null);
        }

        /**
         * The name of the jOOQ table-alias local variable declared in the emitted fetcher
         * body (e.g. {@code filmTable}, {@code languageTable}). Derived from the simple
         * name of {@link #jooqTableClass} with the first character lowered so a Java
         * identifier is produced. Using {@code jooqTableClass} as the source keeps both
         * ends of the emitted declaration ({@code <JooqTableClass> <alias> = Tables.X})
         * referencing the same field. The rename exists because the jOOQ table class and
         * the generated mapper class typically share a simple name; keeping the local
         * entity-prefixed ({@code filmTable}, not {@code table}) lets the importer import
         * both without qualifying either.
         */
        String tableLocalName() {
            var simple = jooqTableClass.simpleName();
            return Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + "Table";
        }
    }

    // -----------------------------------------------------------------------
    // Common code-block fragments
    // -----------------------------------------------------------------------

    /** Converts a snake_case GraphQL argument name to lowerCamelCase for use as a Java local variable. */
    static String toCamelCase(String snakeName) {
        var parts = snakeName.split("_");
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            sb.append(parts[i], 1, parts[i].length());
        }
        return sb.toString();
    }

    /**
     * Returns a {@link CodeBlock} that declares a local entity-specific table variable
     * (e.g. {@code filmTable}) from the jOOQ {@code Tables} constants class:
     * <pre>{@code
     * JooqTableClass filmTable = Tables.FILM;
     * }</pre>
     *
     * <p>The local name comes from {@link ResolvedTableNames#tableLocalName()} — the
     * entity-prefixed form disambiguates the import between the jOOQ table class and the
     * generated mapper class (which usually share a simple name).
     */
    static CodeBlock declareTableLocal(ResolvedTableNames names, TableRef tableRef) {
        return CodeBlock.builder()
            .addStatement("$T $L = $T.$L",
                names.jooqTableClass(), names.tableLocalName(),
                names.tablesClass(), tableRef.javaFieldName())
            .build();
    }

    // -----------------------------------------------------------------------
    // BatchKey utilities
    // -----------------------------------------------------------------------

    /**
     * Returns the JavaPoet {@link TypeName} for the DataLoader key element type
     * corresponding to the given {@link BatchKey}.
     *
     * <ul>
     *   <li>{@link BatchKey.RowKeyed} → {@code RowN<A, B, ...>}</li>
     *   <li>{@link BatchKey.RecordKeyed} → {@code RecordN<A, B, ...>}</li>
     *   <li>{@link BatchKey.ObjectBased} → the fully-qualified class name</li>
     * </ul>
     */
    static TypeName keyElementType(BatchKey batchKey) {
        return switch (batchKey) {
            case BatchKey.RowKeyed rk    -> buildRowKeyType(rk.keyColumns());
            case BatchKey.RecordKeyed rk -> buildRecordNKeyType(rk.keyColumns());
            case BatchKey.ObjectBased ob -> ClassName.bestGuess(ob.fqClassName());
        };
    }

    private static TypeName buildRowKeyType(List<ColumnRef> keyColumns) {
        if (keyColumns.isEmpty()) return ROW;
        ClassName rowNClass = ClassName.get("org.jooq", "Row" + keyColumns.size());
        TypeName[] typeArgs = keyColumns.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(rowNClass, typeArgs);
    }

    private static TypeName buildRecordNKeyType(List<ColumnRef> keyColumns) {
        if (keyColumns.isEmpty()) return RECORD;
        ClassName recordNClass = ClassName.get("org.jooq", "Record" + keyColumns.size());
        TypeName[] typeArgs = keyColumns.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(recordNClass, typeArgs);
    }

    /**
     * Emits the {@code RowN<...> key = DSL.row(...)} statement for a {@link ChildField.RecordTableField}
     * DataFetcher, extracting the FK value(s) from the {@code @record} parent's backing Java object.
     *
     * <p>The accessor per ResultType variant:
     * <ul>
     *   <li>{@link GraphitronType.JooqTableRecordType}: {@code ((Record) env.getSource()).get(Tables.T.FK_COL)}</li>
     *   <li>{@link GraphitronType.JooqRecordType}: {@code ((Record) env.getSource()).get("sql_name")}</li>
     *   <li>{@link GraphitronType.JavaRecordType}: {@code ((BackingClass) env.getSource()).lowerCamelCase()}</li>
     *   <li>{@link GraphitronType.PojoResultType} (non-null class): {@code ((BackingClass) env.getSource()).getLowerCamelCase()}</li>
     * </ul>
     */
    static CodeBlock buildRecordKeyExtraction(BatchKey.RowKeyed batchKey, GraphitronType.ResultType resultType, String jooqPackage) {
        TypeName keyType = keyElementType(batchKey);
        List<ColumnRef> fkCols = batchKey.keyColumns();
        var rowArgs = CodeBlock.builder();
        for (int i = 0; i < fkCols.size(); i++) {
            if (i > 0) rowArgs.add(", ");
            ColumnRef col = fkCols.get(i);
            if (resultType instanceof GraphitronType.JooqTableRecordType jtt) {
                var tablesClass = ResolvedTableNames.ofTable(jtt.table(), jooqPackage).tablesClass();
                rowArgs.add("(($T) env.getSource()).get($T.$L.$L)",
                    RECORD, tablesClass, jtt.table().javaFieldName(), col.javaName());
            } else if (resultType instanceof GraphitronType.JooqRecordType) {
                rowArgs.add("(($T) env.getSource()).get($S)", RECORD, col.sqlName());
            } else if (resultType instanceof GraphitronType.JavaRecordType jrt) {
                var backingClass = ClassName.bestGuess(jrt.fqClassName());
                rowArgs.add("(($T) env.getSource()).$L()", backingClass, toCamelCase(col.sqlName()));
            } else {
                var prt = (GraphitronType.PojoResultType) resultType;
                var backingClass = ClassName.bestGuess(prt.fqClassName());
                var accessorBase = toCamelCase(col.sqlName());
                var getter = "get" + Character.toUpperCase(accessorBase.charAt(0)) + accessorBase.substring(1);
                rowArgs.add("(($T) env.getSource()).$L()", backingClass, getter);
            }
        }
        return CodeBlock.builder()
            .addStatement("$T key = $T.row($L)", keyType, DSL, rowArgs.build())
            .build();
    }

    /**
     * Companion to {@link #buildKeyExtraction} for single-cardinality
     * {@code @splitQuery} fetchers where the BatchKey's columns sit on the parent's FK side.
     * Extracts each key column into a typed local and returns {@code CompletableFuture.completedFuture(null)}
     * before building the {@code RowN} key if any component is {@code null} — a {@code NULL} FK
     * on the parent can never match {@code terminal.pk = parentInput.fk_value}, so dispatching
     * to the DataLoader is a wasted round-trip.
     *
     * <p>Only the {@link BatchKey.RowKeyed} variant is handled; single-cardinality
     * {@code @splitQuery} on a {@code @table} parent is the only caller today.
     */
    static CodeBlock buildKeyExtractionWithNullCheck(BatchKey batchKey, TableRef parentTable, String jooqPackage) {
        if (!(batchKey instanceof BatchKey.RowKeyed rk)) {
            throw new IllegalArgumentException(
                "buildKeyExtractionWithNullCheck supports BatchKey.RowKeyed only, got "
                + batchKey.getClass().getSimpleName());
        }
        var tablesClass = ResolvedTableNames.ofTable(parentTable, jooqPackage).tablesClass();
        String tableField = parentTable.javaFieldName();
        List<ColumnRef> pkCols = rk.keyColumns();
        TypeName keyType = keyElementType(batchKey);
        var out = CodeBlock.builder();
        var rowArgs = CodeBlock.builder();
        var nullCheck = CodeBlock.builder();
        for (int i = 0; i < pkCols.size(); i++) {
            ColumnRef col = pkCols.get(i);
            ClassName colType = ClassName.bestGuess(col.columnClass());
            String local = "fkVal" + i;
            out.addStatement("$T $L = (($T) env.getSource()).get($T.$L.$L)",
                colType, local, RECORD, tablesClass, tableField, col.javaName());
            if (i > 0) {
                nullCheck.add(" || ");
                rowArgs.add(", ");
            }
            nullCheck.add("$L == null", local);
            rowArgs.add("$L", local);
        }
        out.beginControlFlow("if ($L)", nullCheck.build());
        out.addStatement("return $T.completedFuture(null)",
            ClassName.get("java.util.concurrent", "CompletableFuture"));
        out.endControlFlow();
        out.addStatement("$T key = $T.row($L)", keyType, DSL, rowArgs.build());
        return out.build();
    }

    static CodeBlock buildKeyExtraction(BatchKey batchKey, TableRef parentTable, String jooqPackage) {
        TypeName keyType = keyElementType(batchKey);
        var tablesClass = ResolvedTableNames.ofTable(parentTable, jooqPackage).tablesClass();
        return switch (batchKey) {
            case BatchKey.RowKeyed rk -> {
                String tableField = parentTable.javaFieldName();
                List<ColumnRef> pkCols = rk.keyColumns();
                var rowArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) rowArgs.add(", ");
                    rowArgs.add("(($T) env.getSource()).get($T.$L.$L)",
                        RECORD, tablesClass, tableField, pkCols.get(i).javaName());
                }
                yield CodeBlock.builder()
                    .addStatement("$T key = $T.row($L)", keyType, DSL, rowArgs.build())
                    .build();
            }
            case BatchKey.RecordKeyed rk -> {
                String tableField = parentTable.javaFieldName();
                List<ColumnRef> pkCols = rk.keyColumns();
                var intoArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) intoArgs.add(", ");
                    intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
                }
                yield CodeBlock.builder()
                    .addStatement("$T key = (($T) env.getSource()).into($L)", keyType, RECORD, intoArgs.build())
                    .build();
            }
            case BatchKey.ObjectBased ob ->
                CodeBlock.builder()
                    .addStatement("$T key = ($T) env.getSource()", keyType, keyType)
                    .build();
        };
    }
}
