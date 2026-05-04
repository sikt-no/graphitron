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

        static ResolvedTableNames of(TableRef tableRef, String returnTypeName, String outputPackage) {
            return new ResolvedTableNames(
                tableRef.constantsClass(),
                tableRef.tableClass(),
                ClassName.get(outputPackage + ".types", returnTypeName));
        }

        // Legacy overload kept for the Phase 2b transition. Phase 3 / follow-up sweeps the
        // remaining call sites (~50 in TypeFetcherGenerator and the *DirectiveResolver family)
        // and removes this. The String parameter is retained without a name so that no caller
        // can read or pass through a leftover jooqPackage value.
        @Deprecated
        static ResolvedTableNames of(TableRef tableRef, String returnTypeName, String outputPackage,
                                     @SuppressWarnings("unused") String legacyJooqPackage) {
            return of(tableRef, returnTypeName, outputPackage);
        }

        /** Resolves only {@link #tablesClass} and {@link #jooqTableClass} — use when the type class is not needed. */
        static ResolvedTableNames ofTable(TableRef tableRef) {
            return new ResolvedTableNames(
                tableRef.constantsClass(),
                tableRef.tableClass(),
                null);
        }

        @Deprecated
        static ResolvedTableNames ofTable(TableRef tableRef,
                                          @SuppressWarnings("unused") String legacyJooqPackage) {
            return ofTable(tableRef);
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
     * Emits the {@code RowN<...> key = ...} or {@code RecordN<...> key = ...} (or list-shaped
     * variants) statement for a {@link ChildField.RecordTableField} DataFetcher, extracting the
     * batch-key value(s) from the {@code @record} parent. {@link BatchKey.RowKeyed} and
     * {@link BatchKey.LifterRowKeyed} produce {@code RowN<...>} keys via {@code DSL.row(...)};
     * {@link BatchKey.AccessorKeyedSingle} / {@link BatchKey.AccessorKeyedMany} produce
     * {@code RecordN<...>} keys via {@code record.into(...)} (auto-derived; no developer-facing
     * source on these arms).
     *
     * <p>The narrowed parameter type is exhaustive over {@link BatchKey.RecordParentBatchKey}
     * — the four permits ({@link BatchKey.RowKeyed}, {@link BatchKey.LifterRowKeyed},
     * {@link BatchKey.AccessorKeyedSingle}, {@link BatchKey.AccessorKeyedMany}) are the
     * only routes here. Mis-routing a {@code @service}-only permit
     * ({@link BatchKey.RecordKeyed} et al.) is a compile error, not a runtime
     * {@code IllegalStateException}.
     *
     * <p>{@link BatchKey.RowKeyed} arm reads from the parent's backing Java object via the
     * accessor per {@link GraphitronType.ResultType} variant, then constructs a
     * {@code RowN<...>} via {@code DSL.row(...)} from the extracted scalars:
     * <ul>
     *   <li>{@link GraphitronType.JooqTableRecordType}: {@code ((Record) env.getSource()).get(Tables.T.FK_COL)}</li>
     *   <li>{@link GraphitronType.JooqRecordType}: {@code ((Record) env.getSource()).get("sql_name")}</li>
     *   <li>{@link GraphitronType.JavaRecordType}: {@code ((BackingClass) env.getSource()).lowerCamelCase()}</li>
     *   <li>{@link GraphitronType.PojoResultType} (non-null class): {@code ((BackingClass) env.getSource()).getLowerCamelCase()}</li>
     * </ul>
     *
     * <p>{@link BatchKey.LifterRowKeyed} arm calls the developer-supplied static lifter on the
     * parent's backing class: {@code Row1<Long> key = Lifters.method((BackingClass) env.getSource())}.
     *
     * <p>{@link BatchKey.AccessorKeyedSingle} arm reads a single concrete {@code TableRecord}
     * from a typed instance accessor on the parent's backing class and projects it to the
     * element table's PK columns via {@code record.into(...)}: <pre>{@code
     *   ElementRecord __elt = ((BackingClass) env.getSource()).<accessor>();
     *   RecordN<...> key = __elt.into(Tables.T.PK1, Tables.T.PK2);
     * }</pre>
     *
     * <p>{@link BatchKey.AccessorKeyedMany} arm reads a {@code List<X>} or {@code Set<X>}
     * from the typed accessor and projects each element to a {@code RecordN<...>} key via a
     * typed for-loop over {@code Iterable} (uniform across the {@code List} and {@code Set}
     * declarations the parent class may carry): <pre>{@code
     *   List<RecordN<...>> keys = new ArrayList<>();
     *   for (ElementRecord __elt : ((BackingClass) env.getSource()).<accessor>()) {
     *       RecordN<...> __k = __elt.into(Tables.T.PK1, Tables.T.PK2);
     *       keys.add(__k);
     *   }
     * }</pre>
     */
    static CodeBlock buildRecordParentKeyExtraction(
            BatchKey.RecordParentBatchKey batchKey,
            GraphitronType.ResultType resultType) {
        TypeName keyType = batchKey.keyElementType();
        return switch (batchKey) {
            case BatchKey.RowKeyed rk                -> buildFkRowKey(rk, keyType, resultType);
            case BatchKey.LifterRowKeyed lrk         -> buildLifterRowKey(lrk, keyType, resultType);
            case BatchKey.AccessorKeyedSingle ars -> buildAccessorKeySingle(ars, keyType);
            case BatchKey.AccessorKeyedMany arm   -> buildAccessorKeyMany(arm, keyType);
        };
    }

    private static CodeBlock buildFkRowKey(
            BatchKey.RowKeyed batchKey, TypeName keyType,
            GraphitronType.ResultType resultType) {
        List<ColumnRef> fkCols = batchKey.parentKeyColumns();
        var rowArgs = CodeBlock.builder();
        for (int i = 0; i < fkCols.size(); i++) {
            if (i > 0) rowArgs.add(", ");
            ColumnRef col = fkCols.get(i);
            if (resultType instanceof GraphitronType.JooqTableRecordType jtt) {
                var tablesClass = jtt.table().constantsClass();
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

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "lifter-batchkey-is-lifterrowkeyed",
        reliesOn = "The four-arm sealed switch in buildRecordParentKeyExtraction routes "
            + "BatchKey.LifterRowKeyed here. BatchKeyLifterDirectiveResolver guarantees this arm "
            + "is reached only when the parent is PojoResultType or JavaRecordType with a non-null "
            + "fqClassName, so backingClassOf and the (BackingClass) env.getSource() coercion below "
            + "are safe without a null check on the backing class.")
    private static CodeBlock buildLifterRowKey(
            BatchKey.LifterRowKeyed lrk, TypeName keyType,
            GraphitronType.ResultType resultType) {
        ClassName backingClass = backingClassOf(resultType);
        var lifter = lrk.lifter();
        return CodeBlock.builder()
            .addStatement("$T key = $T.$L(($T) env.getSource())",
                keyType, lifter.declaringClass(), lifter.methodName(), backingClass)
            .build();
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "accessor-rowkey-shape-resolved",
        reliesOn = "FieldBuilder.deriveBatchKeyFromTypedAccessor produces AccessorKeyedSingle "
            + "only after reflection has confirmed the parent backing class, the accessor "
            + "(name, zero-arg, non-bridge, non-synthetic, non-static), and the element class "
            + "(extends TableRecord, mapped table identical to the field's @table return). The "
            + "emitted body casts env.getSource() to the resolved backing class and invokes the "
            + "accessor by name without instanceof guards or null checks; the RecordN<...> key is "
            + "built via __elt.into(Tables.X.PK1, ...) over typed Field references on the element "
            + "table.")
    private static CodeBlock buildAccessorKeySingle(
            BatchKey.AccessorKeyedSingle ars, TypeName keyType) {
        var accessor = ars.accessor();
        ClassName backingClass = accessor.parentBackingClass();
        ClassName elementClass = accessor.elementClass();
        TableRef elementTable = ars.hop().targetTable();
        var tablesClass = elementTable.constantsClass();
        String tableField = elementTable.javaFieldName();
        var intoArgs = CodeBlock.builder();
        var pkCols = ars.targetKeyColumns();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) intoArgs.add(", ");
            intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
        }
        return CodeBlock.builder()
            .addStatement("$T __elt = (($T) env.getSource()).$L()",
                elementClass, backingClass, accessor.methodName())
            .addStatement("$T key = __elt.into($L)", keyType, intoArgs.build())
            .build();
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "accessor-rowkey-shape-resolved",
        reliesOn = "FieldBuilder.deriveBatchKeyFromTypedAccessor produces AccessorKeyedMany "
            + "only after reflection has confirmed the parent backing class, the accessor "
            + "(name, zero-arg, non-bridge, non-synthetic, non-static), and the element class "
            + "(extends TableRecord, mapped table identical to the field's @table return). The "
            + "emitted for-loop casts env.getSource() to the resolved backing class, invokes the "
            + "accessor by name without instanceof guards or null checks, and projects each "
            + "element to a RecordN<...> via __elt.into(Tables.X.PK1, ...) over typed Field "
            + "references on the element table.")
    private static CodeBlock buildAccessorKeyMany(
            BatchKey.AccessorKeyedMany arm, TypeName keyType) {
        var accessor = arm.accessor();
        ClassName backingClass = accessor.parentBackingClass();
        ClassName elementClass = accessor.elementClass();
        TableRef elementTable = arm.hop().targetTable();
        var tablesClass = elementTable.constantsClass();
        String tableField = elementTable.javaFieldName();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyType);
        ClassName arrayList = ClassName.get("java.util", "ArrayList");
        var intoArgs = CodeBlock.builder();
        var pkCols = arm.targetKeyColumns();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) intoArgs.add(", ");
            intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
        }
        // For-loop over Iterable, not stream + .toList(): a typed local in a for-loop pins the
        // inference cheaply, and iterating works uniformly across the List<X> and Set<X>
        // declarations the parent class may carry. The output is always a List<RecordN<...>>
        // because DataLoader.loadMany takes a List.
        var b = CodeBlock.builder()
            .addStatement("$T keys = new $T<>()", keysListType, arrayList)
            .beginControlFlow("for ($T __elt : (($T) env.getSource()).$L())",
                elementClass, backingClass, accessor.methodName())
            .addStatement("$T __k = __elt.into($L)", keyType, intoArgs.build())
            .addStatement("keys.add(__k)")
            .endControlFlow();
        return b.build();
    }

    private static ClassName backingClassOf(GraphitronType.ResultType resultType) {
        if (resultType instanceof GraphitronType.PojoResultType prt) {
            return ClassName.bestGuess(prt.fqClassName());
        }
        if (resultType instanceof GraphitronType.JavaRecordType jrt) {
            return ClassName.bestGuess(jrt.fqClassName());
        }
        throw new IllegalStateException(
            "LifterRowKeyed must come from a PojoResultType or JavaRecordType parent; got "
            + resultType.getClass().getSimpleName());
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
    static CodeBlock buildKeyExtractionWithNullCheck(BatchKey batchKey, TableRef parentTable) {
        if (!(batchKey instanceof BatchKey.RowKeyed rk)) {
            throw new IllegalArgumentException(
                "buildKeyExtractionWithNullCheck supports BatchKey.RowKeyed only, got "
                + batchKey.getClass().getSimpleName());
        }
        var tablesClass = parentTable.constantsClass();
        String tableField = parentTable.javaFieldName();
        List<ColumnRef> pkCols = rk.parentKeyColumns();
        TypeName keyType = batchKey.keyElementType();
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

    /**
     * Emits the {@code RowN<...> key = ...} or {@code RecordN<...> key = ...} statement for a
     * {@code @table}-parent {@code @splitQuery} fetcher. Parameter narrowed to
     * {@link BatchKey.ParentKeyed}: the four catalog-resolvable permits are the only routes
     * here, and {@link BatchKey.LifterRowKeyed} is excluded by the type system, not by a
     * defensive {@code IllegalStateException} arm. The row-vs-record axis is the developer's
     * source-shape choice (Row1 source classifies to {@link BatchKey.RowKeyed} /
     * {@link BatchKey.MappedRowKeyed}; Record1 source to {@link BatchKey.RecordKeyed} /
     * {@link BatchKey.MappedRecordKeyed}; typed {@code TableRecord} source to
     * {@link BatchKey.TableRecordKeyed} / {@link BatchKey.MappedTableRecordKeyed}); the emit
     * forks accordingly: {@code DSL.row((Record) env.getSource().get(table.col), ...)} for Row
     * arms, {@code ((Record) env.getSource()).into(table.col, ...)} for RecordN arms,
     * {@code ((Record) env.getSource()).into(Tables.X)} for typed-{@code TableRecord} arms.
     * The resolver-side parent-table consistency check guarantees the variant's
     * {@code elementClass} matches the parent's table, so the extraction's projection target
     * is the parent table itself.
     */
    static CodeBlock buildKeyExtraction(BatchKey.ParentKeyed batchKey, TableRef parentTable) {
        TypeName keyType = batchKey.keyElementType();
        var tablesClass = parentTable.constantsClass();
        String tableField = parentTable.javaFieldName();
        List<ColumnRef> pkCols = batchKey.parentKeyColumns();
        return switch (batchKey) {
            case BatchKey.RowKeyed _, BatchKey.MappedRowKeyed _ -> {
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
            case BatchKey.RecordKeyed _, BatchKey.MappedRecordKeyed _ -> {
                var intoArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) intoArgs.add(", ");
                    intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
                }
                yield CodeBlock.builder()
                    .addStatement("$T key = (($T) env.getSource()).into($L)", keyType, RECORD, intoArgs.build())
                    .build();
            }
            case BatchKey.TableRecordKeyed _, BatchKey.MappedTableRecordKeyed _ -> CodeBlock.builder()
                .addStatement("$T key = (($T) env.getSource()).into($T.$L)",
                    keyType, RECORD, tablesClass, tableField)
                .build();
        };
    }
}
