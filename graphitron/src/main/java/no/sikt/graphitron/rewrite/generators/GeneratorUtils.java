package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
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

    /**
     * Reserved SQL-alias affixes for the full-parent-row projection that backs a
     * {@link SourceKey.Wrap.TableRecord} key read (R436). The parent {@code $fields} SELECT
     * projects every column re-aliased as {@code __src_<sqlColumnName>__}, and
     * {@link #buildKeyExtraction(SourceKey, TableRef)}'s {@code TableRecord} arm reconstructs the
     * typed record by reading those same aliases back. The {@code __}-lead is deliberate: GraphQL
     * reserves leading-{@code __} names for introspection, so no client-driven sibling projection
     * alias (multiset object fields, {@code .as(fieldName)} scalar aliases, interface-participant
     * aliases) can ever collide with one. This moves the whole-row read out of the shared
     * base-column namespace where {@code into(Tables.X)} used to map by name and crash on a
     * colliding alias. Producer and consumer must agree on the exact string, so both
     * {@link #reservedSourceAlias(String)} (the sole formatter) and the two emit sites drive off
     * {@link TableRef#allColumns()} — the column set and the alias basis are single-homed there and
     * cannot drift. Reaches generated code only as a string literal, never a Java identifier, so it
     * is invisible to the dunder-identifier lints.
     *
     * @see no.sikt.graphitron.rewrite.generators.TypeClassGenerator (the producer: reserved-alias
     *      full-row projection in {@code $fields})
     */
    static final String RESERVED_SRC_ALIAS_PREFIX = "__src_";
    static final String RESERVED_SRC_ALIAS_SUFFIX = "__";

    /**
     * The reserved SQL alias a parent column is projected under for the full-parent-row read; the
     * inverse operation the {@code TableRecord} key reconstruction reads back by. Basis is the
     * column's SQL name ({@link ColumnRef#sqlName()}, i.e. jOOQ's {@code Field.getName()}), so the
     * projection and extraction sites resolve to byte-identical strings. See
     * {@link #RESERVED_SRC_ALIAS_PREFIX}.
     */
    static String reservedSourceAlias(String columnSqlName) {
        return RESERVED_SRC_ALIAS_PREFIX + columnSqlName + RESERVED_SRC_ALIAS_SUFFIX;
    }

    /**
     * The default source binding for a record-parent key extraction: the fetcher reads its backing
     * object straight off {@code env.getSource()}. The R268 arm-switch substitutes
     * {@code success.value()} here once it has narrowed the {@code Outcome} source to
     * {@code Success}; see {@link #buildRecordParentKeyExtraction(SourceKey, GraphitronType.ResultType, CodeBlock)}.
     */
    static final CodeBlock SOURCE_FROM_ENV = CodeBlock.of("env.getSource()");
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
     * variants) statement for a class-backed-parent batched DataFetcher, extracting the
     * batch-key value(s) from the parent. Switches on {@link SourceKey#reader()} (with the
     * {@code AccessorCall} arm forking on {@link SourceKey#cardinality()}); the
     * {@code @service}-only readers are unreachable here and rejected eagerly.
     *
     * <ul>
     *   <li>{@link SourceKey.Reader.ColumnRead} (catalog FK on a class-backed parent's
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
     *   ElementRecord element = ((BackingClass) env.getSource()).<accessor>();
     *   RecordN<...> key = element.into(Tables.T.PK1, Tables.T.PK2);
     * }</pre></li>
     *   <li>{@link SourceKey.Reader.AccessorCall} + {@link SourceKey.Cardinality#MANY} —
     *       {@code List<X>} / {@code Set<X>} accessor; each element projects to a
     *       {@code RecordN<...>} key via a typed for-loop over {@code Iterable}: <pre>{@code
     *   List<RecordN<...>> keys = new ArrayList<>();
     *   for (ElementRecord element : ((BackingClass) env.getSource()).<accessor>()) {
     *       RecordN<...> key = element.into(Tables.T.PK1, Tables.T.PK2);
     *       keys.add(key);
     *   }
     * }</pre></li>
     * </ul>
     */
    static CodeBlock buildRecordParentKeyExtraction(
            SourceKey sourceKey,
            GraphitronType.ResultType resultType) {
        return buildRecordParentKeyExtraction(sourceKey, resultType, SOURCE_FROM_ENV);
    }

    /**
     * Source-bound variant of {@link #buildRecordParentKeyExtraction(SourceKey, GraphitronType.ResultType)}.
     * {@code sourceExpr} is the Java expression the backing object is read from before the cast:
     * {@code env.getSource()} on the normal path, {@code success.value()} when this fetcher is an
     * immediate child of a flipped {@code Outcome} payload (R268) and the caller has already narrowed
     * {@code env.getSource()} to {@code Outcome.Success}. The cast and accessor logic are identical
     * either way; only the source binding moves, so the arm-switch reuses the field's own key
     * extraction rather than re-deriving it.
     */
    static CodeBlock buildRecordParentKeyExtraction(
            SourceKey sourceKey,
            GraphitronType.ResultType resultType,
            CodeBlock sourceExpr) {
        TypeName keyType = sourceKey.keyElementType();
        return switch (sourceKey.reader()) {
            case SourceKey.Reader.ColumnRead ignored ->
                buildFkRowKey(sourceKey.columns(), keyType, resultType, sourceExpr);
            case SourceKey.Reader.SourceRowsCall src ->
                buildLifterRowKey(src.lifter(), keyType, resultType, sourceExpr);
            case SourceKey.Reader.AccessorCall ac ->
                sourceKey.cardinality() == SourceKey.Cardinality.MANY
                    ? buildAccessorKeyMany(sourceKey, ac.accessor(), keyType, sourceExpr)
                    : buildAccessorKeySingle(sourceKey, ac.accessor(), keyType, sourceExpr);
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
                    + "Result<RecordN<...>>, not a record-backed Java class).");
            // R305 — source=target carrier re-fetch: ONE reads the PK off the single produced
            // record; MANY iterates the produced collection, one PK key per element.
            case SourceKey.Reader.ProducedRecordRead ignored ->
                sourceKey.cardinality() == SourceKey.Cardinality.MANY
                    ? buildProducedRecordsKeyMany(sourceKey, resultType, sourceExpr)
                    : buildKeyExtractionWithNullCheck(sourceKey, sourceKey.target(), sourceExpr);
        };
    }

    private static CodeBlock buildFkRowKey(
            List<ColumnRef> fkCols, TypeName keyType,
            GraphitronType.ResultType resultType, CodeBlock sourceExpr) {
        return CodeBlock.builder()
            .addStatement("$T key = $T.row($L)", keyType, DSL,
                recordColumnReadArgs(fkCols, resultType, sourceExpr))
            .build();
    }

    /**
     * Per-column read of the key tuple off a single record expression, shared by
     * {@link #buildFkRowKey} (one key off {@code env.getSource()}) and
     * {@link #buildProducedRecordsKeyMany} (one key per element of a held collection). Forks on the
     * parent {@link GraphitronType.ResultType}: jOOQ table record / jOOQ record read the column by
     * field or sql-name; Java record / typed POJO read it via the accessor method.
     */
    private static CodeBlock recordColumnReadArgs(
            List<ColumnRef> cols, GraphitronType.ResultType resultType, CodeBlock recordExpr) {
        var rowArgs = CodeBlock.builder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) rowArgs.add(", ");
            ColumnRef col = cols.get(i);
            if (resultType instanceof GraphitronType.JooqTableRecordType jtt) {
                var tablesClass = jtt.table().constantsClass();
                rowArgs.add("(($T) $L).get($T.$L.$L)",
                    RECORD, recordExpr, tablesClass, jtt.table().javaFieldName(), col.javaName());
            } else if (resultType instanceof GraphitronType.JooqRecordType) {
                rowArgs.add("(($T) $L).get($S)", RECORD, recordExpr, col.sqlName());
            } else if (resultType instanceof GraphitronType.JavaRecordType jrt) {
                var backingClass = ClassName.bestGuess(jrt.fqClassName());
                rowArgs.add("(($T) $L).$L()", backingClass, recordExpr, toCamelCase(col.sqlName()));
            } else {
                var prt = (GraphitronType.PojoResultType.Backed) resultType;
                var backingClass = ClassName.bestGuess(prt.fqClassName());
                var accessorBase = toCamelCase(col.sqlName());
                var getter = "get" + Character.toUpperCase(accessorBase.charAt(0)) + accessorBase.substring(1);
                rowArgs.add("(($T) $L).$L()", backingClass, recordExpr, getter);
            }
        }
        return rowArgs.build();
    }

    /**
     * R305 — {@link SourceKey.Reader.ProducedRecordRead} at {@link SourceKey.Cardinality#MANY}: the
     * source is the producer's held collection of target records (a {@code List<XRecord>} on
     * {@code env.getSource()} or {@code Outcome.Success.value()}). Iterates it and builds one
     * {@code RowN} PK key per element, collected into {@code List<key> keys} for the {@code LOAD_MANY}
     * dispatch (one re-projected row per key, scattered by idx). Mirrors {@link #buildAccessorKeyMany}
     * with the source itself as the iterable rather than an accessor's return.
     */
    private static CodeBlock buildProducedRecordsKeyMany(
            SourceKey sourceKey, GraphitronType.ResultType resultType, CodeBlock sourceExpr) {
        TypeName keyType = sourceKey.keyElementType();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyType);
        ClassName arrayList = ClassName.get("java.util", "ArrayList");
        TypeName iterableOfWild = ParameterizedTypeName.get(
            ClassName.get("java.lang", "Iterable"), WildcardTypeName.subtypeOf(Object.class));
        return CodeBlock.builder()
            .addStatement("$T keys = new $T<>()", keysListType, arrayList)
            .beginControlFlow("for ($T element : ($T) $L)", Object.class, iterableOfWild, sourceExpr)
            .addStatement("$T key = $T.row($L)", keyType, DSL,
                recordColumnReadArgs(sourceKey.columns(), resultType, CodeBlock.of("element")))
            .addStatement("keys.add(key)")
            .endControlFlow()
            .build();
    }

    private static CodeBlock buildLifterRowKey(
            LifterRef lifter, TypeName keyType,
            GraphitronType.ResultType resultType, CodeBlock sourceExpr) {
        ClassName backingClass = backingClassOf(resultType);
        return CodeBlock.builder()
            .addStatement("$T key = $T.$L(($T) $L)",
                keyType, lifter.declaringClass(), lifter.methodName(), backingClass, sourceExpr)
            .build();
    }

    private static CodeBlock buildAccessorKeySingle(
            SourceKey sourceKey, AccessorRef accessor, TypeName keyType, CodeBlock sourceExpr) {
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
        // Null-guard: a nullable to-one @table relation that resolves to no row hands the accessor
        // a null nested record (the field's nullability classification on the SourceKey is what
        // makes this reachable — if a future change marks such a field non-null, this guard becomes
        // visibly dead code rather than silently orphaned). Mirrors the FK-side precedent in
        // buildKeyExtractionWithNullCheck: a key that can't be built must not dispatch the loader;
        // the to-one's faithful "no row" rendering is null, and the fetcher returns
        // CompletableFuture<DataFetcherResult<Record>>, so completedFuture(null) is assignable.
        return CodeBlock.builder()
            .addStatement("$T element = (($T) $L).$L()",
                elementClass, backingClass, sourceExpr, accessor.methodName())
            .beginControlFlow("if (element == null)")
            .addStatement("return $T.completedFuture(null)",
                ClassName.get("java.util.concurrent", "CompletableFuture"))
            .endControlFlow()
            .addStatement("$T key = element.into($L)", keyType, intoArgs.build())
            .build();
    }

    private static CodeBlock buildAccessorKeyMany(
            SourceKey sourceKey, AccessorRef accessor, TypeName keyType, CodeBlock sourceExpr) {
        ClassName backingClass = accessor.parentBackingClass();
        ClassName elementClass = accessor.elementClass();
        TableRef elementTable = sourceKey.target();
        var tablesClass = elementTable.constantsClass();
        String tableField = elementTable.javaFieldName();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyType);
        ClassName arrayList = ClassName.get("java.util", "ArrayList");
        TypeName elementsType = ParameterizedTypeName.get(
            ClassName.get("java.lang", "Iterable"), elementClass);
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
        //
        // Null-guard (R269): a nullable to-many @table relation whose backing was never populated
        // hands the accessor a null collection, and the bare for-each would NPE before any
        // .into(...) runs. Hoist the accessor result to a typed local and skip the loop when it is
        // null so `keys` stays empty; the existing loadMany(keys, ...) dispatch then renders the
        // field as []. This deliberately collapses null-vs-empty (both render []), unlike the ONE
        // arm (buildAccessorKeySingle) which preserves null-vs-present (a null nested record
        // renders the field null): a to-many has no faithful surface distinction between "never
        // populated" and "zero rows" once the loader returns, whereas a to-one's "no row"
        // faithfully renders as null. Element-level nulls inside a non-null collection stay
        // unguarded (a malformed backing, not a cardinality to model). The local is typed
        // Iterable<Element> because the loop consumes only the iteration capability, the same
        // List/Set-agnostic reading the comment above relies on; AccessorRef carries the element
        // type, not the parent's concrete List/Set declaration. Whether the relation can be null is
        // the field's nullability classification on the SourceKey; if a future change makes it
        // non-null this guard is visibly dead code rather than silently orphaned.
        var b = CodeBlock.builder()
            .addStatement("$T keys = new $T<>()", keysListType, arrayList)
            .addStatement("$T elements = (($T) $L).$L()",
                elementsType, backingClass, sourceExpr, accessor.methodName())
            .beginControlFlow("if (elements != null)")
            .beginControlFlow("for ($T element : elements)", elementClass)
            .addStatement("$T key = element.into($L)", keyType, intoArgs.build())
            .addStatement("keys.add(key)")
            .endControlFlow()
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
        return buildKeyExtractionWithNullCheck(sourceKey, parentTable, SOURCE_FROM_ENV);
    }

    /**
     * Source-bound variant of {@link #buildKeyExtractionWithNullCheck(SourceKey, TableRef)}; reads
     * the key columns off {@code sourceExpr} (e.g. {@code success.value()} under an
     * {@code OUTCOME_SUCCESS} envelope) rather than {@code env.getSource()}. Reads each PK column
     * into a typed local so a {@code null} component binds as a typed {@code null} (not an untyped
     * literal), then short-circuits to {@code completedFuture(null)} if any component is {@code null}
     * — the R305 source=target carrier path relies on both: the typed bind keeps the VALUES-join's
     * {@code = } comparison well-typed, and the null short-circuit returns no row for the LocalContext
     * error sentinel (an empty record with a {@code null} PK).
     */
    static CodeBlock buildKeyExtractionWithNullCheck(SourceKey sourceKey, TableRef parentTable, CodeBlock sourceExpr) {
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
            out.addStatement("$T $L = (($T) $L).get($T.$L.$L)",
                colType, local, RECORD, sourceExpr, tablesClass, tableField, col.javaName());
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
     *   <li>{@link SourceKey.Wrap.TableRecord} → a typed record reconstructed per-column from the
     *       reserved full-row aliases: {@code XRecord key = new XRecord();
     *       key.set(Tables.X.COL, source.get("__src_col__", ColType.class)); …} (R436)</li>
     * </ul>
     *
     * <p>The container axis (positional list vs mapped set) is orthogonal and not consulted
     * here: the per-key extraction emits one key value regardless of how the DataLoader frames
     * the batch as a {@code List<K>} or {@code Set<K>}. The resolver-side parent-table
     * consistency check guarantees the {@code TableRecord} arm's class matches the parent's
     * table, so the extraction's projection target is the parent table itself.
     *
     * <p>R436: the {@code TableRecord} arm no longer does a whole-record {@code into(Tables.X)}.
     * That mapped the parent row into the typed record <em>by column name</em>, so a sibling
     * projection aliased to a name colliding with a physical column (multiset object fields are the
     * concrete trigger) poisoned the conversion and threw a {@code MappingException}. Instead the
     * parent {@code $fields} projects the full row under reserved {@code __src_<col>__} aliases
     * (see {@link #reservedSourceAlias(String)}) and this arm rebuilds the typed record column by
     * column, reading each value back by its reserved alias with an explicit type. Both sites
     * enumerate {@link TableRef#allColumns()}, so the projected names and the names read here are
     * single-homed and cannot drift. The R426 contract is preserved: the reconstructed record
     * carries every column on the parent table.
     */
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
            case SourceKey.Wrap.TableRecord tr -> {
                var out = CodeBlock.builder();
                out.addStatement("$T source = ($T) env.getSource()", RECORD, RECORD);
                out.addStatement("$T key = new $T()", keyType, keyType);
                for (ColumnRef col : parentTable.allColumns()) {
                    out.addStatement("key.set($T.$L.$L, source.get($S, $T.class))",
                        tablesClass, tableField, col.javaName(),
                        reservedSourceAlias(col.sqlName()),
                        ClassName.bestGuess(col.columnClass()));
                }
                yield out.build();
            }
        };
    }
}
