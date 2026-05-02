package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.util.ValuesJoinRowBuilder;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.QueryField;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.SELECTED_FIELD;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.toCamelCase;

/**
 * Emits the VALUES + JOIN lookup select for a {@link LookupField}, driven by its
 * {@link LookupMapping}. Two call sites consume this emitter — the Phase 1 root-lookup path
 * and the Phase 2a inline-child-lookup path — with slightly different plumbing at each.
 *
 * <p><b>Root-lookup path (Phase 1)</b> — {@link QueryField.QueryLookupTableField}.
 * {@link #buildInputRowsMethod} emits the input-rows helper into the enclosing {@code *Fetchers}
 * class (args read from {@code DataFetchingEnvironment}). {@link #buildFetcherBody} emits the
 * fetcher body: helper call, empty short-circuit, {@code DSL.values(rows).as(...)} derived
 * table, {@code .join(input).using(table.COL1, table.COL2, …)}, and
 * {@code .orderBy(input.field("idx"))} to preserve input ordering. {@code USING} is safe because
 * the root lookup's {@code FROM} side is the target table only — no FK chain can bring in a
 * duplicate column name.
 *
 * <p><b>Child-lookup path (Phase 2a)</b> — {@link ChildField.LookupTableField}.
 * {@link #buildChildInputRowsMethod} emits the input-rows helper into the enclosing type class
 * (e.g. {@code Film}); args come from a {@code SelectedField} instead of the outer
 * {@code DataFetchingEnvironment} because the lookup is projected inline by the parent's
 * {@code $fields}. The VALUES derived-table join is emitted by
 * {@link InlineLookupTableFieldEmitter} using an explicit {@code ON} predicate rather than
 * {@code USING} — the child path's FK chain may traverse a junction table whose column name
 * collides with the lookup-key target column (see {@code InlineLookupTableFieldEmitter}'s
 * class-level Javadoc).
 *
 * <p>Row construction is shared between both paths: typed {@code DSL.val(value, col.getDataType())}
 * per cell, so jOOQ applies the target column's Converter internally and renders a plain JDBC
 * bind — no SQL-level {@code CAST}. See {@link #addRowBuildingCore}.
 *
 * <p>See {@code docs/argument-resolution.md} for the full design rationale across both paths.
 *
 * <p>Emitted {@code <fieldName>InputRows} helpers take the aliased target {@code Table} as a
 * parameter — see "Helper-locality" in {@code docs/rewrite-design-principles.md}.
 */
final class LookupValuesJoinEmitter {

    /** Directive context surfaced in {@link ValuesJoinRowBuilder}'s arity-cap error messages. */
    private static final String DIRECTIVE_CONTEXT = "@lookupKey";

    private LookupValuesJoinEmitter() {}

    /**
     * Flat per-slot view of a {@link ColumnMapping}'s args, internal to this emitter.
     * One slot per {@link ColumnMapping.LookupArg.ScalarLookupArg}; one slot per binding
     * for {@link ColumnMapping.LookupArg.MapInput} and {@link ColumnMapping.LookupArg.DecodedRecord}.
     *
     * <p>{@code decodeBinding} is non-null only for {@link ColumnMapping.LookupArg.DecodedRecord}
     * slots; it carries the per-NodeType {@code decode<TypeName>} helper and the binding's
     * positional index into the returned {@code Record<N>}. The decode runs once per input row
     * at the arg layer, so {@link #addRowBuildingCore} hoists the decode call to a per-row local
     * shared across all bindings of the same arg.
     */
    private record Slot(
            String argName,
            ColumnRef targetColumn,
            boolean list,
            String compositeFieldName,
            no.sikt.graphitron.rewrite.model.CallSiteExtraction extraction,
            DecodeBinding decodeBinding) {

        boolean isComposite() { return compositeFieldName != null; }
    }

    /**
     * Per-binding slot for any NodeId-decoded arg: which extraction arm
     * ({@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement Skip}
     * or
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch Throw})
     * governs the per-row decode, and which positional {@code Record<N>} value to read. Used by
     * both {@link ColumnMapping.LookupArg.DecodedRecord} (composite-PK NodeId) and
     * {@link ColumnMapping.LookupArg.ScalarLookupArg} when its extraction is a
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys} arm
     * ({@code index} = 0 in that arity-1 case; {@code Record1.value1()} reads the only slot).
     */
    private record DecodeBinding(
            no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys extraction,
            int index) {

        no.sikt.graphitron.rewrite.model.HelperRef.Decode decodeMethod() {
            return extraction.decodeMethod();
        }
    }

    private static List<Slot> flattenSlots(ColumnMapping cm) {
        var slots = new java.util.ArrayList<Slot>();
        for (var arg : cm.args()) {
            switch (arg) {
                case ColumnMapping.LookupArg.ScalarLookupArg s -> {
                    // Hoist the per-row decode for arity-1 NodeId-as-lookup-key (Throw or Skip)
                    // onto the same DecodeBinding mechanism DecodedRecord uses. The slot's own
                    // extraction folds to Direct so slotValueExpr reads from the hoisted
                    // Record1<T>.value1() local; the Throw/Skip branch fires once per arg per row
                    // in addRowBuildingCore.
                    if (s.extraction() instanceof no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys nid) {
                        slots.add(new Slot(s.argName(), s.targetColumn(), s.list(), null,
                            new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct(),
                            new DecodeBinding(nid, 0)));
                    } else {
                        slots.add(new Slot(s.argName(), s.targetColumn(), s.list(), null, s.extraction(), null));
                    }
                }
                case ColumnMapping.LookupArg.MapInput m -> {
                    for (var b : m.bindings()) {
                        slots.add(new Slot(m.argName(), b.targetColumn(), m.list(), b.fieldName(), b.extraction(), null));
                    }
                }
                case ColumnMapping.LookupArg.DecodedRecord d -> {
                    for (var b : d.bindings()) {
                        slots.add(new Slot(d.argName(), b.targetColumn(), d.list(), null,
                            new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct(),
                            new DecodeBinding(d.extraction(), b.index())));
                    }
                }
            }
        }
        return slots;
    }

    /** Returns the GraphQL field name for a {@link LookupField}, used to derive helper names. */
    static String fieldName(LookupField field) {
        return switch (field) {
            case QueryField.QueryLookupTableField f -> f.name();
            case ChildField.LookupTableField f -> f.name();
            case ChildField.SplitLookupTableField f -> f.name();
            case ChildField.RecordLookupTableField f -> f.name();
        };
    }

    /** The name of the generated input-rows helper method for a lookup field. */
    static String inputRowsMethodName(LookupField field) {
        return fieldName(field) + "InputRows";
    }

    /** The VALUES-table alias used in the fetcher body. */
    static String inputTableAlias(LookupField field) {
        return fieldName(field) + "Input";
    }

    /**
     * Generates the {@code <fieldName>InputRows(DataFetchingEnvironment env, <TargetTable> table) -> Row<N+1>[]}
     * helper method. The helper:
     * <ol>
     *   <li>Extracts each {@code @lookupKey} arg from {@code env}.</li>
     *   <li>Computes row count {@code n} — the length of the first list-typed argument, broadcasting
     *       scalars across all rows. With no list arg, {@code n = 1}.</li>
     *   <li>Builds one {@code DSL.row(DSL.inline(i), DSL.val(v, table.COL.getDataType()), …)} per
     *       index — positional call to the typed {@code Row<N+1>} overload so
     *       {@code Field<Integer>} / {@code Field<ColType>} types flow into the array element.
     *       {@code DSL.val} invokes the target column's Converter on the raw value — no
     *       Java-side {@code .convert()} call and no SQL {@code CAST}.</li>
     *   <li>Returns a typed {@code Row<N+1><Integer, colType1, …>[]} (length 0 when the list arg
     *       is null/empty — callers check {@code rows.length == 0}).</li>
     * </ol>
     *
     * @param field the lookup field (source of {@link LookupMapping})
     * @param targetTableClass the JavaPoet reference to the concrete jOOQ table class (e.g. {@code Film})
     */
    static MethodSpec buildInputRowsMethod(LookupField field, ClassName targetTableClass) {
        List<Slot> slots = requireSlots(field);
        Map<String, RootSource> roots = rootSources(slots);

        var builder = MethodSpec.methodBuilder(inputRowsMethodName(field))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ValuesJoinRowBuilder.rowArrayType(slots, Slot::targetColumn, DIRECTIVE_CONTEXT))
            .addParameter(ENV, "env")
            .addParameter(targetTableClass, "table");

        // Extract each root argument into a local. Lists are List<?> (nullable); scalars and
        // composite-key input types both come in as Object (the Map<String,Object> for a
        // composite root is downcast per column via columnValueExpr). env.getArgument is
        // <T>-inferred so the cast is implicit.
        for (var root : roots.values()) {
            if (root.list()) {
                builder.addStatement("$T<?> $L = env.getArgument($S)", LIST, root.localName(), root.argName());
            } else {
                builder.addStatement("$T $L = env.getArgument($S)", Object.class, root.localName(), root.argName());
            }
        }

        addRowBuildingCore(builder, slots, roots);
        return builder.build();
    }

    /**
     * Child-field variant of {@link #buildInputRowsMethod}. Reads {@code @lookupKey} args from a
     * {@link graphql.schema.SelectedField} instead of a {@code DataFetchingEnvironment}, since
     * the args live on the child selection when the lookup is projected inline by a parent's
     * {@code $fields} (argres Phase 2a). Row-construction core is shared with the root variant.
     *
     * <p>Signature: {@code <fieldName>InputRows(SelectedField sf, <TargetTable> table) -> Row<N+1>[]}
     * with the same typed arity as the root variant.
     *
     * <p>{@code SelectedField.getArguments()} returns {@code Map<String, Object>}; list args need
     * an explicit {@code (List<?>)} cast to match the typed-local declaration the shared core
     * expects.
     */
    static MethodSpec buildChildInputRowsMethod(LookupField field, ClassName targetTableClass) {
        List<Slot> slots = requireSlots(field);
        Map<String, RootSource> roots = rootSources(slots);

        var builder = MethodSpec.methodBuilder(inputRowsMethodName(field))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ValuesJoinRowBuilder.rowArrayType(slots, Slot::targetColumn, DIRECTIVE_CONTEXT))
            .addParameter(SELECTED_FIELD, "sf")
            .addParameter(targetTableClass, "table");

        // Extract each root argument. sf.getArguments() is Map<String,Object>; list roots take
        // an explicit cast. Composite-key roots come through as Object (a Map<String,Object>
        // downcast per column via columnValueExpr).
        for (var root : roots.values()) {
            if (root.list()) {
                builder.addStatement("$T<?> $L = ($T<?>) sf.getArguments().get($S)",
                    LIST, root.localName(), LIST, root.argName());
            } else {
                builder.addStatement("$T $L = sf.getArguments().get($S)", Object.class, root.localName(), root.argName());
            }
        }

        addRowBuildingCore(builder, slots, roots);
        return builder.build();
    }

    /** Classifier-invariant check shared by the root and child input-rows builders. */
    private static List<Slot> requireSlots(LookupField field) {
        ColumnMapping cm = (ColumnMapping) field.lookupMapping();
        if (cm.args().isEmpty()) {
            // projectForFilter enforces non-empty LookupMapping before classification; reaching this
            // is a generator-side bug, not a schema error.
            throw new IllegalStateException(
                "LookupField '" + fieldName(field) + "' has no lookup args; classifier invariant violated");
        }
        return flattenSlots(cm);
    }

    /**
     * Shared row-building tail: row-count computation, typed-row-array creation, typed-value
     * loop, return. Assumes each column's value is already in a local named via
     * {@link #argLocalName} and that {@code table} refers to the target-table alias.
     *
     * <p>The typed {@code Row<N+1>} / {@code Record<N+1>} parameterisation ({@code Integer} for
     * {@code idx}, then the columns) is computed by {@link ValuesJoinRowBuilder#rowTypeArgs} via
     * the {@code Slot::targetColumn} projection.
     */
    private static void addRowBuildingCore(MethodSpec.Builder builder,
            List<Slot> slots, Map<String, RootSource> roots) {
        // Row count N — from the first list root's length, or 1 if every root is scalar.
        var primaryList = roots.values().stream().filter(RootSource::list).findFirst().orElse(null);
        if (primaryList == null) {
            builder.addStatement("int n = 1");
        } else {
            String local = primaryList.localName();
            builder.addStatement("int n = $L == null ? 0 : $L.size()", local, local);
        }

        // Typed Row<N+1>[] declaration delegated to the shared row-builder. Caller fills the
        // for-loop body below — including the decode-args block which is lookup-specific and
        // must run before cell construction so each slotValueExpr can read decoded values.
        var arrayCode = CodeBlock.builder();
        ValuesJoinRowBuilder.emitRowArrayDecl(arrayCode, slots, Slot::targetColumn, DIRECTIVE_CONTEXT, "rows", "n");
        builder.addCode(arrayCode.build());

        // Per-row decode locals for NodeId-decoded args (DecodedRecord composite-PK paths and
        // arity-1 ScalarLookupArg paths whose extraction is a NodeIdDecodeKeys arm): one
        // decode<TypeName> call per arg per row, shared across all positional bindings. The
        // failure-mode arm decides what happens on a null return:
        //   - ThrowOnMismatch: synthesised lookup-key paths where a wrong-type id is an
        //     authored-input contract violation. Emit a GraphqlErrorException.
        //   - SkipMismatchedElement: same-table @nodeId filter paths where a malformed id
        //     drops silently to "no row matches". Emit `continue` so the row is dropped from
        //     the VALUES set.
        Map<String, DecodeBinding> decodeArgs = new LinkedHashMap<>();
        for (var slot : slots) {
            if (slot.decodeBinding() != null) {
                decodeArgs.putIfAbsent(slot.argName(), slot.decodeBinding());
            }
        }
        boolean anySkip = decodeArgs.values().stream()
            .anyMatch(db -> db.extraction() instanceof no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement);
        if (anySkip) {
            // Effective row count tracks how many rows survived the per-row Skip checks. The
            // tail trims rows[] to this length when shorter than n; the call site's existing
            // `if (rows.length == 0) return dsl.newResult();` short-circuit then catches the
            // all-skipped case without further bookkeeping.
            builder.addStatement("int effective = 0");
        }
        builder.beginControlFlow("for (int i = 0; i < n; i++)");

        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");
        for (var entry : decodeArgs.entrySet()) {
            String argName = entry.getKey();
            DecodeBinding db = entry.getValue();
            RootSource root = roots.get(argName);
            CodeBlock rawElem = root.list()
                ? CodeBlock.of("$L.get(i)", root.localName())
                : CodeBlock.of("$L", root.localName());
            String recLocal = decodeRecordLocal(root.localName());
            ClassName encoderClass = db.decodeMethod().encoderClass();
            String methodName = db.decodeMethod().methodName();
            TypeName recordType = db.decodeMethod().returnType();
            builder.addStatement("$T $L = ($L instanceof $T _s) ? $T.$L(_s) : null",
                recordType, recLocal, rawElem, String.class, encoderClass, methodName);
            builder.beginControlFlow("if ($L == null)", recLocal);
            switch (db.extraction()) {
                case no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch t ->
                    builder.addStatement("throw $T.newErrorException().message($S).build()", graphqlErr,
                        "Decoded NodeId did not match the expected type for argument '" + argName + "'");
                case no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement s ->
                    builder.addStatement("continue");
            }
            builder.endControlFlow();
        }

        // Per-cell typed values delegated to the row-builder; the slot-to-value bridge stays here
        // because slotValueExpr depends on lookup-specific RootSource / DecodedRecord context.
        // The helper passes the rich Slot back through the callback (no parallel-list bridge).
        CodeBlock cells = ValuesJoinRowBuilder.cellsCode(
            slots, Slot::targetColumn, CodeBlock.of("$T.inline(i)", DSL), "table",
            (slot, idx) -> slotValueExpr(slot, roots.get(slot.argName())));
        if (anySkip) {
            builder.addStatement("rows[effective++] = $T.row($L)", DSL, cells);
        } else {
            builder.addStatement("rows[i] = $T.row($L)", DSL, cells);
        }
        builder.endControlFlow();
        if (anySkip) {
            builder.addStatement("return effective < n ? $T.copyOf(rows, effective) : rows",
                ClassName.get("java.util", "Arrays"));
        } else {
            builder.addStatement("return rows");
        }
    }

    private static String decodeRecordLocal(String rootLocalName) {
        return rootLocalName + "Rec";
    }

    /**
     * Generates the VALUES + JOIN derived-table select body for a lookup field's rows method.
     *
     * <p>Expects two locals already declared in the surrounding method:
     * <ul>
     *   <li>{@code table} — the target jOOQ table alias (from {@link GeneratorUtils#declareTableLocal}).</li>
     *   <li>{@code dsl} — the {@code DSLContext} (declared by the caller after this block's emitted
     *       rows-array declaration, because the empty-input short-circuit uses it).</li>
     * </ul>
     *
     * <p>Emits:
     * <pre>{@code
     * Row<N+1><Integer, colType1, …>[] rows = <fieldName>InputRows(env, table);
     * var dsl = graphitronContext(env).getDslContext(env);
     * if (rows.length == 0) return dsl.newResult();
     * Table<Record<N+1><Integer, colType1, …>> input = DSL.values(rows).as("<fieldName>Input", "idx", "COL1", "COL2", …);
     * return dsl.select(<typeFieldsCall>)
     *           .from(table)
     *           .join(input).using(table.COL1, table.COL2, …)
     *           .where(condition)
     *           .orderBy(input.field("idx"))
     *           .fetch();
     * }</pre>
     *
     * <p>The {@code .where(condition)} clause expects the caller to have declared a
     * {@code Condition condition} local before this block. Callers typically initialise it with
     * {@code DSL.noCondition()} and AND in any non-key filters (field-level {@code @condition},
     * per-arg {@code @condition}); when there are no such filters, the {@code .where(noCondition())}
     * is a no-op that jOOQ optimises away.
     *
     * @param field                the lookup field
     * @param typeFieldsCallStatic the JavaPoet expression for {@code <TypeName>.$fields(env.getSelectionSet(), table, env)}.
     */
    static CodeBlock buildFetcherBody(LookupField field, CodeBlock typeFieldsCall, String srcAlias) {
        ColumnMapping cm = (ColumnMapping) field.lookupMapping();
        List<Slot> slots = flattenSlots(cm);
        String alias = inputTableAlias(field);

        // VALUES column labels — "idx", then one per lookup slot. Labels must match the target
        // column's SQL name (e.g. "film_id"), not the jOOQ Java field name (e.g. "FILM_ID"), because
        // Postgres treats quoted identifiers case-sensitively and USING compares the rendered names.
        CodeBlock aliasArgs = ValuesJoinRowBuilder.aliasArgs(slots, Slot::targetColumn, alias);
        // USING column arguments — references to target-table field constants.
        CodeBlock usingArgs = ValuesJoinRowBuilder.usingArgs(slots, Slot::targetColumn, srcAlias);

        return CodeBlock.builder()
            .addStatement("$T rows = $L(env, $L)",
                ValuesJoinRowBuilder.rowArrayType(slots, Slot::targetColumn, DIRECTIVE_CONTEXT),
                inputRowsMethodName(field), srcAlias)
            .addStatement("$T dsl = graphitronContext(env).getDslContext(env)",
                ClassName.get("org.jooq", "DSLContext"))
            .add("if (rows.length == 0) return dsl.newResult();\n")
            .addStatement("$T input = $T.values(rows).as($L)",
                ValuesJoinRowBuilder.inputTableType(slots, Slot::targetColumn, DIRECTIVE_CONTEXT), DSL, aliasArgs)
            .add("return dsl\n")
            .indent()
            .add(".select($L)\n", typeFieldsCall)
            .add(".from($L)\n", srcAlias)
            .add(".join(input).using($L)\n", usingArgs)
            .add(".where(condition)\n")
            .add(".orderBy(input.field($S))\n", "idx")
            .add(".fetch();\n")
            .unindent()
            .build();
    }

    /**
     * Describes the top-level argument backing one or more {@link Slot}s. All slots sharing a
     * root arg share a single extracted local, which is critical for composite-key input types
     * where several {@code @lookupKey} fields live on one argument.
     *
     * <p>{@code argName} is the GraphQL argument name. {@code list} reflects the outer argument's
     * list cardinality; all slots rooted here inherit this listness. {@code localName} is the
     * Java local-variable name holding the extracted value: {@code toCamelCase(argName)} with a
     * {@code Keys} suffix when list-typed.
     */
    private record RootSource(String argName, boolean list, String localName) {
        static RootSource of(String argName, boolean list) {
            String camel = toCamelCase(argName);
            return new RootSource(argName, list, list ? camel + "Keys" : camel);
        }
    }

    /**
     * Groups lookup columns by their top-level argument, preserving declaration order.
     * A composite-key input argument contributes one entry with multiple columns underneath.
     */
    private static Map<String, RootSource> rootSources(List<Slot> slots) {
        var roots = new LinkedHashMap<String, RootSource>();
        for (var slot : slots) {
            roots.computeIfAbsent(slot.argName(), k -> RootSource.of(k, slot.list()));
        }
        return roots;
    }

    /**
     * The value expression that reads one lookup column's raw value inside the row-building loop,
     * given the root's extracted local. Branches on whether the slot is composite (carries an
     * {@code inputField} drilldown) and on list cardinality:
     * <ul>
     *   <li>Scalar path, scalar root → {@code rootLocal}</li>
     *   <li>Scalar path, list root   → {@code rootLocal.get(i)}</li>
     *   <li>Composite path, scalar root → {@code ((Map<?,?>) rootLocal).get("inputField")}</li>
     *   <li>Composite path, list root   → {@code ((Map<?,?>) rootLocal.get(i)).get("inputField")}</li>
     * </ul>
     * jOOQ's {@code DSL.val(value, table.COL.getDataType())} then wraps the value via the target
     * column's Converter.
     */
    private static CodeBlock slotValueExpr(Slot slot, RootSource root) {
        // NodeId-decoded slots (both DecodedRecord composite-PK and arity-1 ScalarLookupArg
        // with NodeIdDecodeKeys extraction) read from the per-row decode local declared by
        // addRowBuildingCore. The shared local means decode<TypeName> runs once per row across
        // all bindings of the same arg, and the Throw/Skip branch fires once at the hoisted
        // null check rather than inline per cell.
        if (slot.decodeBinding() != null) {
            String recLocal = decodeRecordLocal(root.localName());
            return CodeBlock.of("$L.value$L()", recLocal, slot.decodeBinding().index() + 1);
        }
        if (slot.isComposite()) {
            CodeBlock elem = root.list()
                ? CodeBlock.of("$L.get(i)", root.localName())
                : CodeBlock.of("$L", root.localName());
            return CodeBlock.of("(($T<?, ?>) $L).get($S)", Map.class, elem, slot.compositeFieldName());
        }
        return root.list()
            ? CodeBlock.of("$L.get(i)", root.localName())
            : CodeBlock.of("$L", root.localName());
    }

}
