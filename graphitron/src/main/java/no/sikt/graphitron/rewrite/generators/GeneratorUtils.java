package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.AccessorRef;
import no.sikt.graphitron.rewrite.model.Arity;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.LifterRef;
import no.sikt.graphitron.rewrite.model.ServiceKeySource;
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
     * Reserved SQL-alias prefix for result-key-distinct projection of inline reference and
     * computed fields. Aliasing a duplicate reference selection under its runtime <em>result
     * key</em> rather than the schema field name lets {@code a: ref { x } b: ref { y }} mint two
     * distinct SELECT terms instead of two colliding {@code .as(fieldName)} aliases. The prefix
     * reaches generated code only inside string literals: the write side emits
     * {@code .as("__rk_" + <entry>.getKey())} in {@code $project}
     * (the projection renderer's multiset, scalar-subselect, and helper-call arms), the
     * read side
     * {@code DSL.field("__rk_" + env.getField().getResultKey())} ({@link FetcherEmitter}); the
     * concatenation happens at runtime and this constant is the single home both sides drive off.
     *
     * <p>The reserved {@code __}-lead moves the alias out of the client-reachable namespace:
     * GraphQL reserves leading-{@code __} names for introspection only in the schema, and
     * document aliases are unrestricted, so a client could write {@code __actor_id__: ref}
     * or {@code __discriminator__: ref}. The prefix keeps a
     * client-chosen alias from ever colliding with a base-column projection, the polymorphic
     * discriminator projection, or another result key (result keys are unique per flattened
     * selection map by construction; an adversarial {@code __rk_foo} alias mints
     * {@code __rk___rk_foo}, still distinct). It is a string literal in generated output, never a
     * Java identifier, so the dunder-identifier lints do not see it.
     *
     * <p>The literal's one home is {@link no.sikt.graphitron.command.ReservedAliases} (this
     * package may not be imported from {@code render}, whose projection renderer writes the
     * same prefix); this constant is the legacy tree's read of it.
     */
    static final String RESERVED_RK_ALIAS_PREFIX =
        no.sikt.graphitron.command.ReservedAliases.RESULT_KEY_PREFIX;

    /**
     * The owner-aware prefix mint, the legacy tree's read of
     * {@link no.sikt.graphitron.command.ReservedAliases#resultKeyPrefix}. A read whose field
     * carries a qualified {@link no.sikt.graphitron.rewrite.model.AliasOwner} must spell the same
     * prefix the projection wrote, so both halves route through the one mint rather than
     * concatenating the qualifier themselves.
     */
    static String resultKeyPrefix(no.sikt.graphitron.rewrite.model.AliasOwner owner) {
        return no.sikt.graphitron.command.ReservedAliases.resultKeyPrefix(owner);
    }

    /**
     * The default source binding for a record-parent key extraction: the fetcher reads its backing
     * object straight off {@code env.getSource()}. The arm-switch substitutes
     * {@code success.value()} here once it has narrowed the {@code Outcome} source to
     * {@code Success}; see
     * {@link #buildRecordParentKeyExtraction(SourceKey, KeyLift, TableRef, GraphitronType.ResultType, CodeBlock)}.
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
         * body (e.g. {@code filmTable}). The jOOQ table class and the generated mapper class
         * typically share a simple name; keeping the local entity-prefixed
         * ({@code filmTable}, not {@code table}) lets the importer import both without
         * qualifying either. One derivation with the launcher renderer's bodies:
         * {@link no.sikt.graphitron.render.TableLocal} owns the formula.
         */
        String tableLocalName() {
            return no.sikt.graphitron.render.TableLocal.name(jooqTableClass);
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
     * <p>The local name comes from {@link ResolvedTableNames#tableLocalName()}, which documents
     * the entity-prefixed rationale; the statement itself has one derivation with the launcher
     * renderer's bodies in {@link no.sikt.graphitron.render.TableLocal}.
     */
    static CodeBlock declareTableLocal(ResolvedTableNames names, TableRef tableRef) {
        return no.sikt.graphitron.render.TableLocal.declare(
            names.jooqTableClass(), names.tablesClass(), tableRef.javaFieldName());
    }

    // -----------------------------------------------------------------------
    // SourceKey key-extraction utilities (record-parent fetchers)
    // -----------------------------------------------------------------------

    /**
     * Emits the {@code RowN<...> key = ...} or {@code RecordN<...> key = ...} (or list-shaped
     * variants) statement for a class-backed-parent batched DataFetcher, extracting the
     * batch-key value(s) from the parent. Switches exhaustively on the field's {@link KeyLift},
     * with the arity-bearing arms forking on their own {@link Arity}; the emit shape per arm
     * lives in the private helpers below.
     *
     * <p>On the {@link KeyLift.Lifter} arm, leaf-PK and {@code @reference}-composed shapes share
     * emit logic; the path identity is carried first-class on the leaf's {@code joinPath} but not
     * consumed here.
     */
    static CodeBlock buildRecordParentKeyExtraction(
            SourceKey sourceKey,
            KeyLift lift,
            TableRef keyOwnerTable,
            GraphitronType.ResultType resultType) {
        return buildRecordParentKeyExtraction(sourceKey, lift, keyOwnerTable, resultType, SOURCE_FROM_ENV);
    }

    /**
     * Source-bound variant of
     * {@link #buildRecordParentKeyExtraction(SourceKey, KeyLift, TableRef, GraphitronType.ResultType)}.
     * {@code sourceExpr} is the Java expression the backing object is read from before the cast:
     * {@code env.getSource()} on the normal path, {@code success.value()} when this fetcher is an
     * immediate child of a flipped {@code Outcome} payload and the caller has already narrowed
     * {@code env.getSource()} to {@code Outcome.Success}. The cast and accessor logic are identical
     * either way; only the source binding moves, so the arm-switch reuses the field's own key
     * extraction rather than re-deriving it.
     *
     * <p>{@code keyOwnerTable} is the table whose typed {@code Tables.X.COL} constants the
     * {@link KeyLift.Accessor} and single-arity {@link KeyLift.ProducedRecords} arms project the
     * key through: the field's return-type table on the child-field path, the hub table
     * ({@code parentKeyOwnerTable}) on the polymorphic record-parent path. Read off the carrier
     * at the call site; the other lift arms ignore it.
     */
    static CodeBlock buildRecordParentKeyExtraction(
            SourceKey sourceKey,
            KeyLift lift,
            TableRef keyOwnerTable,
            GraphitronType.ResultType resultType,
            CodeBlock sourceExpr) {
        TypeName keyType = sourceKey.keyElementType();
        return switch (lift) {
            case KeyLift.FkColumns ignored ->
                buildFkRowKey(sourceKey.columns(), keyType, resultType, sourceExpr);
            case KeyLift.Lifter l ->
                buildLifterRowKey(l.lifter(), keyType, resultType, sourceExpr);
            case KeyLift.Accessor ac ->
                ac.arity() == Arity.MANY
                    ? buildAccessorKeyMany(sourceKey, keyOwnerTable, ac.accessor(), keyType, sourceExpr)
                    : buildAccessorKeySingle(sourceKey, keyOwnerTable, ac.accessor(), keyType, sourceExpr);
            // Source=target carrier re-fetch: ONE reads the PK off the single produced
            // record; MANY iterates the produced collection, one PK key per element.
            case KeyLift.ProducedRecords pr ->
                pr.arity() == Arity.MANY
                    ? buildProducedRecordsKeyMany(sourceKey, resultType, sourceExpr)
                    : buildKeyExtractionWithNullCheck(sourceKey, keyOwnerTable, sourceExpr);
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
     * {@link #buildProducedRecordsKeyMany} (one key per element of a held collection). Forks on
     * the parent {@link GraphitronType.ResultType}.
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
     * {@link KeyLift.ProducedRecords} at {@link Arity#MANY}: the source is the producer's held
     * collection of target records. Iterates it and builds one {@code RowN} PK key per element,
     * collected into {@code List<key> keys} for the {@code LOAD_MANY} dispatch (one re-projected
     * row per key, scattered by idx). Mirrors {@link #buildAccessorKeyMany} with the source itself
     * as the iterable rather than an accessor's return.
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
            SourceKey sourceKey, TableRef elementTable, AccessorRef accessor,
            TypeName keyType, CodeBlock sourceExpr) {
        ClassName backingClass = accessor.parentBackingClass();
        ClassName elementClass = accessor.elementClass();
        var tablesClass = elementTable.constantsClass();
        String tableField = elementTable.javaFieldName();
        var intoArgs = CodeBlock.builder();
        var pkCols = sourceKey.columns();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) intoArgs.add(", ");
            intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
        }
        // Null-guard: a nullable to-one @table relation that resolves to no row hands the accessor
        // a null nested record. Mirrors buildKeyExtractionWithNullCheck: a key that can't be built
        // must not dispatch the loader. The to-one's faithful "no row" rendering is null, and the
        // fetcher returns CompletableFuture<DataFetcherResult<Record>>, so completedFuture(null)
        // is assignable. Reachability rides on the field's nullability classification on the
        // SourceKey; a non-null reclassification leaves this guard visibly dead rather than
        // silently orphaned.
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
            SourceKey sourceKey, TableRef elementTable, AccessorRef accessor,
            TypeName keyType, CodeBlock sourceExpr) {
        ClassName backingClass = accessor.parentBackingClass();
        ClassName elementClass = accessor.elementClass();
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
        // For-loop over a typed Iterable<Element> local, not stream + .toList(): the typed local
        // pins the inference cheaply and iterates uniformly over the List<X> or Set<X> the parent
        // class declares (AccessorRef carries the element type, not the concrete declaration).
        // The output is always a List<RecordN<...>> because DataLoader.loadMany takes a List.
        //
        // Null-guard: a nullable to-many @table relation whose backing was never populated hands
        // the accessor a null collection. Skipping the loop keeps `keys` empty, and the
        // loadMany(keys, ...) dispatch renders the field as []. This deliberately collapses
        // null-vs-empty (both render []), unlike the ONE arm (buildAccessorKeySingle), which
        // preserves null-vs-present: a to-many has no faithful surface distinction between "never
        // populated" and "zero rows" once the loader returns, whereas a to-one's "no row"
        // faithfully renders as null. Element-level nulls inside a non-null collection stay
        // unguarded (a malformed backing, not a cardinality to model). Reachability rides on the
        // field's nullability classification on the SourceKey; a non-null reclassification leaves
        // the guard visibly dead rather than silently orphaned.
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
            "KeyLift.Lifter must come from a PojoResultType.Backed or JavaRecordType parent; got "
            + resultType.getClass().getSimpleName());
    }

    /**
     * Companion to {@link #buildKeyExtraction} for single-cardinality
     * {@code @splitQuery} fetchers where the SourceKey's columns sit on the parent's FK side.
     * Extracts each key column into a typed local and returns {@code CompletableFuture.completedFuture(null)}
     * before building the {@code RowN} key if any component is {@code null}: a {@code NULL} FK
     * on the parent can never match {@code terminal.pk = parentInput.fk_value}, so dispatching
     * to the DataLoader is a wasted round-trip.
     *
     * <p>Only {@link SourceKey.Wrap.Row} keys reach here (single-cardinality
     * {@code @splitQuery} on a {@code @table} parent, and the single-arity
     * {@link KeyLift.ProducedRecords} re-fetch, are the only callers today). The wrap check is
     * asserted at the entry point; the column-read shape rides on the call-site invariant.
     */
    static CodeBlock buildKeyExtractionWithNullCheck(SourceKey sourceKey, TableRef parentTable) {
        return buildKeyExtractionWithNullCheck(sourceKey, parentTable, SOURCE_FROM_ENV);
    }

    /**
     * Source-bound variant of {@link #buildKeyExtractionWithNullCheck(SourceKey, TableRef)}; reads
     * the key columns off {@code sourceExpr} (e.g. {@code success.value()} under an
     * {@code OUTCOME_SUCCESS} envelope) rather than {@code env.getSource()}. Reads each PK column
     * into a typed local so a {@code null} component binds as a typed {@code null} (not an untyped
     * literal), then short-circuits to {@code completedFuture(null)} if any component is
     * {@code null}. The source=target carrier path relies on both: the typed bind keeps the
     * VALUES-join's {@code =} comparison well-typed, and the null short-circuit returns no row
     * for the LocalContext error sentinel (an empty record with a {@code null} PK).
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
            TypeName colType = col.columnType();
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
     *   <li>{@link SourceKey.Wrap.Row}:
     *       {@code DSL.row(((Record) env.getSource()).get(table.col), ...)}</li>
     *   <li>{@link SourceKey.Wrap.Record}:
     *       {@code ((Record) env.getSource()).into(table.col, ...)}</li>
     *   <li>{@link SourceKey.Wrap.TableRecord}: a fresh typed record carrying the key columns,
     *       each copied off the source by field identity. The contract with the service author is
     *       PK-only; a service needing other columns fetches them itself through the injected
     *       {@code DSLContext}.</li>
     * </ul>
     *
     * <p>The container axis (positional list vs mapped set) is orthogonal and not consulted
     * here: the per-key extraction emits one key value regardless of how the DataLoader frames
     * the batch as a {@code List<K>} or {@code Set<K>}. The resolver-side parent-table
     * consistency check guarantees the {@code TableRecord} arm's class matches the parent's
     * table, so the extraction's projection target is the parent table itself.
     */
    static CodeBlock buildKeyExtraction(SourceKey sourceKey, TableRef parentTable) {
        return buildKeyExtraction(sourceKey, parentTable, SOURCE_FROM_ENV);
    }

    /**
     * Source-bound variant of {@link #buildKeyExtraction(SourceKey, TableRef)}, matching the
     * {@link #buildKeyExtractionWithNullCheck(SourceKey, TableRef, CodeBlock)} sibling:
     * {@code sourceExpr} is the Java expression the key columns are read off, and it is the whole of
     * what varies. The {@code @service} path's class-backed-parent accessor arm binds a local ahead
     * of this block and passes it here; every other caller reads {@code env.getSource()}.
     *
     * <p>{@code keyOwner} is the table whose {@code Tables.X.COL} constants the reads go through. It
     * is the parent's own table on the {@code @splitQuery} and {@code @pivot} paths, where the key is
     * the parent's primary key by construction; on the {@code @service} path it is the table the
     * {@code Sources} element type names, which is the same table only when the parent carries
     * {@code @table}.
     */
    static CodeBlock buildKeyExtraction(SourceKey sourceKey, TableRef keyOwner, CodeBlock sourceExpr) {
        TypeName keyType = sourceKey.keyElementType();
        var tablesClass = keyOwner.constantsClass();
        String tableField = keyOwner.javaFieldName();
        List<ColumnRef> pkCols = sourceKey.columns();
        return switch (sourceKey.wrap()) {
            case SourceKey.Wrap.Row r -> {
                var rowArgs = CodeBlock.builder();
                for (int i = 0; i < pkCols.size(); i++) {
                    if (i > 0) rowArgs.add(", ");
                    rowArgs.add("(($T) $L).get($T.$L.$L)",
                        RECORD, sourceExpr, tablesClass, tableField, pkCols.get(i).javaName());
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
                    .addStatement("$T key = (($T) $L).into($L)", keyType, RECORD, sourceExpr, intoArgs.build())
                    .build();
            }
            case SourceKey.Wrap.TableRecord tr -> {
                var out = CodeBlock.builder();
                out.addStatement("$T source = ($T) $L", RECORD, RECORD, sourceExpr);
                out.addStatement("$T key = new $T()", keyType, keyType);
                // One unconditional read, because only the key columns are ever copied and they are
                // present under their base names on both arrival paths that graphql-java fuses onto
                // this fetcher: a service (or DML) handing back the typed record carries its own PK
                // as a real column, and the SQL-projected generic row a parent <Type>.$project query
                // builds carries them through the field's own gated correlation-key arm (this
                // fetcher only runs when its field is selected, and the selected field's arm
                // projects exactly these columns — same accessor on both sides). Reads
                // are by jOOQ field identity, never a by-name into(...) map, so a sibling multiset
                // alias shadowing a column name cannot poison the extraction.
                for (ColumnRef col : pkCols) {
                    out.addStatement("key.set($T.$L.$L, source.get($T.$L.$L))",
                        tablesClass, tableField, col.javaName(),
                        tablesClass, tableField, col.javaName());
                }
                yield out.build();
            }
        };
    }

    /**
     * The batched child {@code @service} path's key read: one home for all four
     * {@link ServiceKeySource} arms, each of which is the shared wrap-driven
     * {@link #buildKeyExtraction} against a different source binding.
     *
     * <p>The two {@code env.getSource()} arms differ only in what the parent is (a projected table
     * row versus a held typed record), which the emitted expression does not see: both read through
     * the generic {@code Record} interface, and {@code Record.get(Tables.X.COL)} answers the same
     * either way. They stay separate arms because {@code ChildField.sourceShape()} derives off the
     * same seam.
     *
     * <p>The accessor arm binds the returned record into a local first and guards it. An accessor
     * returning a record may legitimately return {@code null} (a nullable to-one that resolved to no
     * row), and the extraction below it reads columns off that record unconditionally, so without the
     * guard the emitted fetcher would NPE at request time rather than resolve the field to null. The
     * short-circuit is {@code completedFuture(null)}, assignable to the fetcher's declared
     * {@code CompletableFuture<DataFetcherResult<V>>} at every service seat. It sits inside the key
     * extraction, not in the pre-registration prelude, so a throwing developer accessor routes
     * through the same disposition as the rest of the synchronous key read instead of escaping
     * {@code DataFetcher.get()} unrouted.
     *
     * <p>The lifter arm is that arm verbatim with a static call in place of the instance one: the
     * author-declared producer can return {@code null} for the same reasons, and what follows the
     * call is the same PK copy into a fresh record by jOOQ field identity, so "the keys carry the
     * key columns, and nothing else" holds even when the producer populated more.
     */
    static CodeBlock buildServiceKeyExtraction(SourceKey sourceKey, ServiceKeySource keySource) {
        return switch (keySource) {
            case ServiceKeySource.FromTableRow row ->
                buildKeyExtraction(sourceKey, row.keyOwner(), SOURCE_FROM_ENV);
            case ServiceKeySource.FromHeldRecord held ->
                buildKeyExtraction(sourceKey, held.keyOwner(), SOURCE_FROM_ENV);
            case ServiceKeySource.FromAccessor acc -> CodeBlock.builder()
                .addStatement("$T keyRecord = (($T) env.getSource()).$L()",
                    acc.accessor().elementClass(), acc.accessor().parentBackingClass(),
                    acc.accessor().methodName())
                .beginControlFlow("if (keyRecord == null)")
                .addStatement("return $T.completedFuture(null)",
                    ClassName.get("java.util.concurrent", "CompletableFuture"))
                .endControlFlow()
                .add(buildKeyExtraction(sourceKey, acc.keyOwner(), CodeBlock.of("keyRecord")))
                .build();
            case ServiceKeySource.FromLifter lifted -> CodeBlock.builder()
                .addStatement("$T keyRecord = $T.$L(($T) env.getSource())",
                    ClassName.bestGuess(lifted.producer().elementClass()),
                    ClassName.bestGuess(lifted.producer().declaringClass()),
                    lifted.producer().methodName(),
                    ClassName.bestGuess(lifted.producer().parentBackingClass()))
                .beginControlFlow("if (keyRecord == null)")
                .addStatement("return $T.completedFuture(null)",
                    ClassName.get("java.util.concurrent", "CompletableFuture"))
                .endControlFlow()
                .add(buildKeyExtraction(sourceKey, lifted.keyOwner(), CodeBlock.of("keyRecord")))
                .build();
        };
    }
}
