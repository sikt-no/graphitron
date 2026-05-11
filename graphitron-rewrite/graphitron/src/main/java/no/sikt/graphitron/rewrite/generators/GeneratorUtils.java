package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.AccessorRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.LifterRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
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

        /** Resolves only {@link #tablesClass} and {@link #jooqTableClass} — use when the type class is not needed. */
        static ResolvedTableNames ofTable(TableRef tableRef) {
            return new ResolvedTableNames(
                tableRef.constantsClass(),
                tableRef.tableClass(),
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
    // SourceKey key-extraction utilities (record-parent fetchers)
    // -----------------------------------------------------------------------

    /**
     * Emits the {@code RowN<...> key = ...} or {@code RecordN<...> key = ...} (or list-shaped
     * variants) statement for a {@code @record}-parent batched DataFetcher, extracting the
     * batch-key value(s) from the parent. Switches on {@link SourceKey#reader()} (with the
     * {@code AccessorCall} arm forking on {@link SourceKey#cardinality()}); the
     * {@code @service}-only readers are unreachable here and rejected eagerly.
     *
     * <ul>
     *   <li>{@link SourceKey.Reader.ColumnRead} (catalog FK on a {@code @record} parent's
     *       backing class) — emits {@code RowN<...>} via {@code DSL.row(...)} over per-column
     *       accessors derived from the {@link GraphitronType.ResultType} variant:
     *       <ul>
     *         <li>{@link GraphitronType.JooqTableRecordType}: {@code ((Record) env.getSource()).get(Tables.T.FK_COL)}</li>
     *         <li>{@link GraphitronType.JooqRecordType}: {@code ((Record) env.getSource()).get("sql_name")}</li>
     *         <li>{@link GraphitronType.JavaRecordType}: {@code ((BackingClass) env.getSource()).lowerCamelCase()}</li>
     *         <li>{@link GraphitronType.PojoResultType}: {@code ((BackingClass) env.getSource()).getLowerCamelCase()}</li>
     *       </ul>
     *   </li>
     *   <li>{@link SourceKey.Reader.SourceRowsCall} ({@code @sourceRows} lifter) — calls the
     *       developer-supplied static lifter on the parent's backing class:
     *       {@code Row1<Long> key = Lifters.method((BackingClass) env.getSource())}. Leaf-PK and
     *       {@code @reference}-composed shapes share emit logic; the path identity is carried by
     *       {@link SourceKey#path()} but not consumed here.</li>
     *   <li>{@link SourceKey.Reader.AccessorCall} + {@link SourceKey.Cardinality#ONE} — single
     *       typed {@code TableRecord} via instance accessor, projected to the element table's PK
     *       columns: <pre>{@code
     *   ElementRecord __elt = ((BackingClass) env.getSource()).<accessor>();
     *   RecordN<...> key = __elt.into(Tables.T.PK1, Tables.T.PK2);
     * }</pre></li>
     *   <li>{@link SourceKey.Reader.AccessorCall} + {@link SourceKey.Cardinality#MANY} —
     *       {@code List<X>} / {@code Set<X>} accessor; each element projects to a
     *       {@code RecordN<...>} key via a typed for-loop over {@code Iterable}: <pre>{@code
     *   List<RecordN<...>> keys = new ArrayList<>();
     *   for (ElementRecord __elt : ((BackingClass) env.getSource()).<accessor>()) {
     *       RecordN<...> __k = __elt.into(Tables.T.PK1, Tables.T.PK2);
     *       keys.add(__k);
     *   }
     * }</pre></li>
     * </ul>
     */
    static CodeBlock buildRecordParentKeyExtraction(
            SourceKey sourceKey,
            GraphitronType.ResultType resultType) {
        TypeName keyType = sourceKey.keyElementType();
        return switch (sourceKey.reader()) {
            case SourceKey.Reader.ColumnRead ignored ->
                buildFkRowKey(sourceKey.columns(), keyType, resultType);
            case SourceKey.Reader.SourceRowsCall src ->
                buildLifterRowKey(src.lifter(), keyType, resultType);
            case SourceKey.Reader.AccessorCall ac ->
                sourceKey.cardinality() == SourceKey.Cardinality.MANY
                    ? buildAccessorKeyMany(sourceKey, ac.accessor(), keyType)
                    : buildAccessorKeySingle(sourceKey, ac.accessor(), keyType);
            case SourceKey.Reader.ServiceTableRecord ignored ->
                throw new IllegalArgumentException(
                    "buildRecordParentKeyExtraction does not handle Reader.ServiceTableRecord "
                    + "(record-parent dispatch path only).");
            case SourceKey.Reader.ServiceUntypedRecord ignored ->
                throw new IllegalArgumentException(
                    "buildRecordParentKeyExtraction does not handle Reader.ServiceUntypedRecord "
                    + "(record-parent dispatch path only).");
            case SourceKey.Reader.ResultRowWalk ignored ->
                throw new IllegalArgumentException(
                    "buildRecordParentKeyExtraction does not handle Reader.ResultRowWalk "
                    + "(single-record DML carrier dispatch path; the parent is the mutation's "
                    + "Result<RecordN<...>>, not a @record-backed Java class).");
        };
    }

    private static CodeBlock buildFkRowKey(
            List<ColumnRef> fkCols, TypeName keyType,
            GraphitronType.ResultType resultType) {
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
                var prt = (GraphitronType.PojoResultType.Backed) resultType;
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
        key = "sourcerow-leafkey-sourcerows-singlehop",
        reliesOn = "The exhaustive sealed switch in buildRecordParentKeyExtraction routes "
            + "Reader.SourceRowsCall (covering both leaf-PK and @reference-composed shapes) here. "
            + "SourceRowDirectiveResolver guarantees the lifter arms are reached only when the "
            + "parent is PojoResultType or JavaRecordType with a non-null fqClassName, so "
            + "backingClassOf and the (BackingClass) env.getSource() coercion below are safe "
            + "without a null check on the backing class.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "sourcerow-pathkey-sourcerows-fkchain",
        reliesOn = "Same Reader.SourceRowsCall arm covers the @reference-composed lifter shape; "
            + "SourceRowDirectiveResolver constructs the path-keyed lifter projection only on "
            + "PojoResultType / JavaRecordType with a non-null fqClassName.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "source-key.source-rows-call-wraps-row",
        reliesOn = "Declares `$T key = Lifters.method((BackingClass) env.getSource())` where "
            + "`$T` is sourceKey.keyElementType(). The @sourceRows lifter signature returns "
            + "RowN<...>; the local must be typed RowN<...> for the assignment to compile. "
            + "SourceKey's compact constructor rejects any SourceRowsCall paired with a wrap "
            + "other than Wrap.Row, so keyElementType() is guaranteed to derive a RowN<...> "
            + "type at this site.")
    private static CodeBlock buildLifterRowKey(
            LifterRef lifter, TypeName keyType,
            GraphitronType.ResultType resultType) {
        ClassName backingClass = backingClassOf(resultType);
        return CodeBlock.builder()
            .addStatement("$T key = $T.$L(($T) env.getSource())",
                keyType, lifter.declaringClass(), lifter.methodName(), backingClass)
            .build();
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "accessor-rowkey-shape-resolved",
        reliesOn = "FieldBuilder.deriveAccessorRecordParentSource produces the AccessorCall + "
            + "Cardinality.ONE projection only after reflection has confirmed the parent backing "
            + "class, the accessor (name, zero-arg, non-bridge, non-synthetic, non-static), and "
            + "the element class (extends TableRecord, mapped table identical to the field's "
            + "@table return). The emitted body casts env.getSource() to the resolved backing "
            + "class and invokes the accessor by name without instanceof guards or null checks; "
            + "the RecordN<...> key is built via __elt.into(Tables.X.PK1, ...) over typed Field "
            + "references on the element table.")
    private static CodeBlock buildAccessorKeySingle(
            SourceKey sourceKey, AccessorRef accessor, TypeName keyType) {
        ClassName backingClass = accessor.parentBackingClass();
        ClassName elementClass = accessor.elementClass();
        TableRef elementTable = sourceKey.target();
        var tablesClass = elementTable.constantsClass();
        String tableField = elementTable.javaFieldName();
        var intoArgs = CodeBlock.builder();
        var pkCols = sourceKey.columns();
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
        reliesOn = "FieldBuilder.deriveAccessorRecordParentSource produces the AccessorCall + "
            + "Cardinality.MANY projection only after reflection has confirmed the parent "
            + "backing class, the accessor (name, zero-arg, non-bridge, non-synthetic, "
            + "non-static), and the element class (extends TableRecord, mapped table identical "
            + "to the field's @table return). The emitted for-loop casts env.getSource() to the "
            + "resolved backing class, invokes the accessor by name without instanceof guards or "
            + "null checks, and projects each element to a RecordN<...> via "
            + "__elt.into(Tables.X.PK1, ...) over typed Field references on the element table.")
    private static CodeBlock buildAccessorKeyMany(
            SourceKey sourceKey, AccessorRef accessor, TypeName keyType) {
        ClassName backingClass = accessor.parentBackingClass();
        ClassName elementClass = accessor.elementClass();
        TableRef elementTable = sourceKey.target();
        var tablesClass = elementTable.constantsClass();
        String tableField = elementTable.javaFieldName();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyType);
        ClassName arrayList = ClassName.get("java.util", "ArrayList");
        var intoArgs = CodeBlock.builder();
        var pkCols = sourceKey.columns();
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
        if (resultType instanceof GraphitronType.PojoResultType.Backed b) {
            return ClassName.bestGuess(b.fqClassName());
        }
        if (resultType instanceof GraphitronType.JavaRecordType jrt) {
            return ClassName.bestGuess(jrt.fqClassName());
        }
        throw new IllegalStateException(
            "Reader.SourceRowsCall must come from a PojoResultType.Backed or JavaRecordType parent; got "
            + resultType.getClass().getSimpleName());
    }

    /**
     * Companion to {@link #buildKeyExtraction} for single-cardinality
     * {@code @splitQuery} fetchers where the SourceKey's columns sit on the parent's FK side.
     * Extracts each key column into a typed local and returns {@code CompletableFuture.completedFuture(null)}
     * before building the {@code RowN} key if any component is {@code null} — a {@code NULL} FK
     * on the parent can never match {@code terminal.pk = parentInput.fk_value}, so dispatching
     * to the DataLoader is a wasted round-trip.
     *
     * <p>Only {@link SourceKey.Wrap.Row} + {@link SourceKey.Reader.ColumnRead} reaches here
     * (single-cardinality {@code @splitQuery} on a {@code @table} parent is the only caller
     * today). The wrap check is asserted at the entry point; the reader narrowing rides on the
     * call-site invariant ({@code @splitQuery} on a table-backed parent projects to
     * {@code ColumnRead}).
     */
    static CodeBlock buildKeyExtractionWithNullCheck(SourceKey sourceKey, TableRef parentTable) {
        if (!(sourceKey.wrap() instanceof SourceKey.Wrap.Row)) {
            throw new IllegalArgumentException(
                "buildKeyExtractionWithNullCheck supports SourceKey.Wrap.Row only, got "
                + sourceKey.wrap().getClass().getSimpleName());
        }
        var tablesClass = parentTable.constantsClass();
        String tableField = parentTable.javaFieldName();
        List<ColumnRef> pkCols = sourceKey.columns();
        TypeName keyType = sourceKey.keyElementType();
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
     * {@code @table}-parent {@code @splitQuery} fetcher. The wrap-axis is the developer's
     * source-shape choice:
     * <ul>
     *   <li>{@link SourceKey.Wrap.Row} →
     *       {@code DSL.row((Record) env.getSource().get(table.col), ...)}</li>
     *   <li>{@link SourceKey.Wrap.Record} →
     *       {@code ((Record) env.getSource()).into(table.col, ...)}</li>
     *   <li>{@link SourceKey.Wrap.TableRecord} →
     *       {@code ((Record) env.getSource()).into(Tables.X)}</li>
     * </ul>
     *
     * <p>The container axis (positional list vs mapped set) is orthogonal and not consulted
     * here: the per-key extraction emits one key value regardless of how the DataLoader frames
     * the batch as a {@code List<K>} or {@code Set<K>}. The resolver-side parent-table
     * consistency check guarantees the {@code TableRecord} arm's class matches the parent's
     * table, so the extraction's projection target is the parent table itself.
     */
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "source-key.service-table-record-target-aligned-empty-path",
        reliesOn = "The Wrap.TableRecord arm emits `parent.into(Tables.X)` against parentTable "
            + "directly — no walk over sourceKey.path() is performed. The arm is reachable only "
            + "from the @service Reader.ServiceTableRecord codepath, where the typed record class "
            + "matches the parent's table (validateTableRecordSourceParentTable on the validator "
            + "side, target-aligned by construction in FieldBuilder). SourceKey's compact "
            + "constructor rejects any ServiceTableRecord with recordType matching target's "
            + "recordClass paired with a non-empty path, so the emitter can safely project "
            + "without first walking a join chain that would otherwise have to land on the "
            + "parent table.")
    static CodeBlock buildKeyExtraction(SourceKey sourceKey, TableRef parentTable) {
        TypeName keyType = sourceKey.keyElementType();
        var tablesClass = parentTable.constantsClass();
        String tableField = parentTable.javaFieldName();
        List<ColumnRef> pkCols = sourceKey.columns();
        return switch (sourceKey.wrap()) {
            case SourceKey.Wrap.Row r -> {
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
            case SourceKey.Wrap.Record r -> {
                var intoArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) intoArgs.add(", ");
                    intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
                }
                yield CodeBlock.builder()
                    .addStatement("$T key = (($T) env.getSource()).into($L)", keyType, RECORD, intoArgs.build())
                    .build();
            }
            case SourceKey.Wrap.TableRecord tr -> CodeBlock.builder()
                .addStatement("$T key = (($T) env.getSource()).into($T.$L)",
                    keyType, RECORD, tablesClass, tableField)
                .build();
        };
    }
}
