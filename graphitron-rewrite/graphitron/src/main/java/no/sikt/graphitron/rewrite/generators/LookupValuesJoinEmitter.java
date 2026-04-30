package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
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

    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName SUPPRESS_WARNINGS = ClassName.get("java.lang", "SuppressWarnings");

    private LookupValuesJoinEmitter() {}

    /** jOOQ exposes typed Row1..Row22 / Record1..Record22; higher arities fall back to raw. */
    private static ClassName rowClass(int arity) {
        return ClassName.get("org.jooq", "Row" + arity);
    }

    private static ClassName recordClass(int arity) {
        return ClassName.get("org.jooq", "Record" + arity);
    }

    /**
     * Returns the {@code Row<N+1>} / {@code Record<N+1>} type arguments: {@code Integer} for the
     * {@code idx} cell, then one per lookup column (its Java type via {@code columnClass()}).
     * Rejects arities &gt;22 — same mechanism as the parent-side cap in
     * {@link SplitRowsMethodEmitter}.
     */
    private static TypeName[] rowTypeArgs(List<Slot> slots) {
        int arity = slots.size() + 1;
        if (arity > 22) {
            throw new IllegalStateException(
                "@lookupKey arity " + slots.size() + " + idx exceeds jOOQ's typed Row/Record arity limit (22)");
        }
        TypeName[] typeArgs = new TypeName[arity];
        typeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < slots.size(); i++) {
            typeArgs[i + 1] = ClassName.bestGuess(slots.get(i).targetColumn().columnClass());
        }
        return typeArgs;
    }

    /**
     * Flat per-slot view of a {@link ColumnMapping}'s args, internal to this emitter.
     * One slot per {@link ColumnMapping.LookupArg.ScalarLookupArg}; one slot per binding
     * for {@link ColumnMapping.LookupArg.MapInput}. {@link ColumnMapping.LookupArg.DecodedRecord}
     * lands in phase (f-C); until then the fixture-zero shape is rejected here.
     */
    private record Slot(
            String argName,
            ColumnRef targetColumn,
            boolean list,
            String compositeFieldName,
            no.sikt.graphitron.rewrite.model.CallSiteExtraction extraction) {

        boolean isComposite() { return compositeFieldName != null; }
    }

    private static List<Slot> flattenSlots(ColumnMapping cm) {
        var direct = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct();
        var slots = new java.util.ArrayList<Slot>();
        for (var arg : cm.args()) {
            switch (arg) {
                case ColumnMapping.LookupArg.ScalarLookupArg s ->
                    slots.add(new Slot(s.argName(), s.targetColumn(), s.list(), null, s.extraction()));
                case ColumnMapping.LookupArg.MapInput m -> {
                    for (var b : m.bindings()) {
                        slots.add(new Slot(m.argName(), b.targetColumn(), m.list(), b.fieldName(), b.extraction()));
                    }
                }
                case ColumnMapping.LookupArg.DecodedRecord d ->
                    throw new IllegalStateException(
                        "DecodedRecord LookupArg emission is not implemented yet (R50 phase g)");
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
        TypeName[] typeArgs = rowTypeArgs(slots);
        Map<String, RootSource> roots = rootSources(slots);

        var builder = MethodSpec.methodBuilder(inputRowsMethodName(field))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ArrayTypeName.of(ParameterizedTypeName.get(rowClass(typeArgs.length), typeArgs)))
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

        addRowBuildingCore(builder, slots, typeArgs, roots);
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
        TypeName[] typeArgs = rowTypeArgs(slots);
        Map<String, RootSource> roots = rootSources(slots);

        var builder = MethodSpec.methodBuilder(inputRowsMethodName(field))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ArrayTypeName.of(ParameterizedTypeName.get(rowClass(typeArgs.length), typeArgs)))
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

        addRowBuildingCore(builder, slots, typeArgs, roots);
        return builder.build();
    }

    /** Classifier-invariant check shared by the root and child input-rows builders. */
    private static List<Slot> requireSlots(LookupField field) {
        if (!(field.lookupMapping() instanceof ColumnMapping cm)) {
            throw new IllegalStateException(
                "buildInputRowsMethod called on a NodeIdMapping field '"
                + fieldName(field) + "'; caller must dispatch on mapping type first");
        }
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
     * <p>{@code typeArgs} — the {@code Row<N+1>} / {@code Record<N+1>} parameterisation
     * ({@code Integer} for {@code idx}, then the columns) computed once by the caller via
     * {@link #rowTypeArgs}.
     */
    private static void addRowBuildingCore(MethodSpec.Builder builder,
            List<Slot> slots, TypeName[] typeArgs,
            Map<String, RootSource> roots) {
        int arity = typeArgs.length;
        TypeName rowType = ParameterizedTypeName.get(rowClass(arity), typeArgs);
        ClassName rawRowClass = rowClass(arity);

        // Row count N — from the first list root's length, or 1 if every root is scalar.
        var primaryList = roots.values().stream().filter(RootSource::list).findFirst().orElse(null);
        if (primaryList == null) {
            builder.addStatement("int n = 1");
        } else {
            String local = primaryList.localName();
            builder.addStatement("int n = $L == null ? 0 : $L.size()", local, local);
        }

        // Typed Row<N+1>[] — one Row<N+1><Integer, colType1, …> per input index. Generic array
        // creation requires the unchecked cast; scoped to this one line. DSL.row(Field<T1>, …,
        // Field<TN>) picks the typed Row<N+1> overload (not Row(Object...) returning untyped
        // RowN), so Field<Integer> from DSL.inline(i) and Field<ColType> from
        // DSL.val(v, table.COL.getDataType()) flow into rows[i]. When n == 0 the loop is a no-op
        // and we return an empty typed array — callers branch on rows.length == 0.
        builder.addCode("@$T({$S, $S})\n", SUPPRESS_WARNINGS, "unchecked", "rawtypes");
        builder.addStatement("$T[] rows = ($T[]) new $T[n]", rowType, rowType, rawRowClass);
        builder.beginControlFlow("for (int i = 0; i < n; i++)");

        var cells = CodeBlock.builder();
        cells.add("$T.inline(i)", DSL);
        for (var slot : slots) {
            cells.add(", ");
            CodeBlock valueExpr = slotValueExpr(slot, roots.get(slot.argName()));
            // DSL.val(value, dataType) — typed Field<T>; jOOQ's Convert + the column's registered
            // Converter coerce the raw env value (String / Integer / enum instance / …) to the
            // column's Java type at bind time. No SQL CAST rendered.
            cells.add("$T.val($L, table.$L.getDataType())",
                DSL, valueExpr, slot.targetColumn().javaName());
        }
        builder.addStatement("rows[i] = $T.row($L)", DSL, cells.build());
        builder.endControlFlow();
        builder.addStatement("return rows");
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
        if (!(field.lookupMapping() instanceof ColumnMapping cm)) {
            throw new IllegalStateException(
                "buildFetcherBody called on a NodeIdMapping field '"
                + fieldName(field) + "'; caller must use buildNodeIdFetcherBody instead");
        }
        List<Slot> slots = flattenSlots(cm);
        String alias = inputTableAlias(field);
        TypeName[] typeArgs = rowTypeArgs(slots);
        int arity = typeArgs.length;
        TypeName rowArrayType = ArrayTypeName.of(ParameterizedTypeName.get(rowClass(arity), typeArgs));
        TypeName inputTableType = ParameterizedTypeName.get(TABLE,
            ParameterizedTypeName.get(recordClass(arity), typeArgs));

        // VALUES column labels — "idx", then one per lookup slot. Labels must match the target
        // column's SQL name (e.g. "film_id"), not the jOOQ Java field name (e.g. "FILM_ID"), because
        // Postgres treats quoted identifiers case-sensitively and USING compares the rendered names.
        var aliasArgs = CodeBlock.builder();
        aliasArgs.add("$S, $S", alias, "idx");
        for (var slot : slots) {
            aliasArgs.add(", $S", slot.targetColumn().sqlName());
        }

        // USING column arguments — references to target-table field constants.
        var usingArgs = CodeBlock.builder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) usingArgs.add(", ");
            usingArgs.add("$L.$L", srcAlias, slots.get(i).targetColumn().javaName());
        }

        return CodeBlock.builder()
            .addStatement("$T rows = $L(env, $L)", rowArrayType, inputRowsMethodName(field), srcAlias)
            .addStatement("$T dsl = graphitronContext(env).getDslContext(env)",
                ClassName.get("org.jooq", "DSLContext"))
            .add("if (rows.length == 0) return dsl.newResult();\n")
            .addStatement("$T input = $T.values(rows).as($L)", inputTableType, DSL, aliasArgs.build())
            .add("return dsl\n")
            .indent()
            .add(".select($L)\n", typeFieldsCall)
            .add(".from($L)\n", srcAlias)
            .add(".join(input).using($L)\n", usingArgs.build())
            .add(".where(condition)\n")
            .add(".orderBy(input.field($S))\n", "idx")
            .add(".fetch();\n")
            .unindent()
            .build();
    }

    /**
     * Describes the top-level argument backing one or more {@link ColumnMapping.LookupColumn}s.
     * All columns sharing a root arg share a single extracted local — critical for composite-key
     * input types where several {@code @lookupKey} fields live on one argument.
     *
     * <p>{@code argName} is the GraphQL argument name. {@code list} reflects the outer argument's
     * list cardinality — all columns rooted here inherit this listness. {@code localName} is the
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
     * given the root's extracted local. Branches on {@link ColumnMapping.LookupColumn#isComposite()}
     * and list cardinality:
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
        CodeBlock raw;
        if (slot.isComposite()) {
            CodeBlock elem = root.list()
                ? CodeBlock.of("$L.get(i)", root.localName())
                : CodeBlock.of("$L", root.localName());
            raw = CodeBlock.of("(($T<?, ?>) $L).get($S)", Map.class, elem, slot.compositeFieldName());
        } else {
            raw = root.list()
                ? CodeBlock.of("$L.get(i)", root.localName())
                : CodeBlock.of("$L", root.localName());
        }
        // R50 phase (f-C): NodeId-as-lookup-key arity-1 fold. The raw String reaches the per-row
        // decode<TypeName> helper inline; a null return is an authored-input contract violation
        // and surfaces as a GraphqlErrorException via the same Supplier throw-in-expression
        // pattern ArgCallEmitter uses for the canonical [ID!] @nodeId fold.
        if (slot.extraction() instanceof no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch ton) {
            ClassName encoderClass = ton.decodeMethod().encoderClass();
            String methodName = ton.decodeMethod().methodName();
            return CodeBlock.of(
                "($L) instanceof String _s ? ($T.$L(_s) instanceof $T _r ? _r.value1() : "
                + "(($T<?>) () -> { throw new $T($S); }).get()) : null",
                raw, encoderClass, methodName, ton.decodeMethod().returnType(),
                ClassName.get("java.util.function", "Supplier"),
                ClassName.get("graphql", "GraphqlErrorException"),
                "Decoded NodeId did not match the expected type for argument '" + slot.argName() + "'");
        }
        return raw;
    }

    /**
     * Generates the fetcher body for a {@link LookupMapping.NodeIdMapping} lookup field.
     * Skips VALUES + JOIN entirely; instead extracts the base64 node ID from the env and
     * emits a {@code NodeIdEncoder.hasId} / {@code hasIds} WHERE predicate.
     *
     * <p>Generated code (scalar key):
     * <pre>{@code
     * String id = env.getArgument("id");
     * var dsl = graphitronContext(env).getDslContext(env);
     * return dsl
     *     .select(Foo.$fields(env.getSelectionSet(), table, env))
     *     .from(table)
     *     .where(condition.and(id == null ? DSL.noCondition()
     *         : NodeIdEncoder.hasId("Bar", id, table.ID_1, table.ID_2)))
     *     .fetch();
     * }</pre>
     *
     * <p>List variant replaces the scalar extraction with {@code List<String> ids = env.getArgument("ids");}
     * and the predicate with {@code NodeIdEncoder.hasIds("Bar", ids, table.ID_1, ...)}.
     */
    static CodeBlock buildNodeIdFetcherBody(LookupField field, CodeBlock typeFieldsCall, String srcAlias, String outputPackage) {
        var mapping = (LookupMapping.NodeIdMapping) field.lookupMapping();
        var nodeIdEncoder = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator.CLASS_NAME);
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");

        var code = CodeBlock.builder();

        if (mapping.list()) {
            code.addStatement("$T<$T> $L = env.getArgument($S)",
                LIST, String.class, toCamelCase(mapping.argName()) + "Keys", mapping.argName());
        } else {
            code.addStatement("$T $L = env.getArgument($S)",
                String.class, toCamelCase(mapping.argName()), mapping.argName());
        }
        code.addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass);

        // Build the key-columns argument list: table.COL1, table.COL2, …
        var keyColArgs = CodeBlock.builder();
        for (int i = 0; i < mapping.nodeKeyColumns().size(); i++) {
            if (i > 0) keyColArgs.add(", ");
            keyColArgs.add("$L.$L", srcAlias, mapping.nodeKeyColumns().get(i).javaName());
        }

        // Build the NodeIdEncoder predicate
        CodeBlock nodeIdPredicate;
        String localName = toCamelCase(mapping.argName());
        if (mapping.list()) {
            String keysLocal = localName + "Keys";
            nodeIdPredicate = CodeBlock.of(
                "$L == null || $L.isEmpty() ? $T.noCondition() : $T.hasIds($S, $L, $L)",
                keysLocal, keysLocal, DSL,
                nodeIdEncoder, mapping.nodeTypeId(), keysLocal,
                keyColArgs.build());
        } else {
            nodeIdPredicate = CodeBlock.of(
                "$L == null ? $T.noCondition() : $T.hasId($S, $L, $L)",
                localName, DSL,
                nodeIdEncoder, mapping.nodeTypeId(), localName,
                keyColArgs.build());
        }

        code.add("return dsl\n")
            .indent()
            .add(".select($L)\n", typeFieldsCall)
            .add(".from($L)\n", srcAlias)
            .add(".where(condition.and($L))\n", nodeIdPredicate)
            .add(mapping.list() ? ".fetch();\n" : ".fetchOne();\n")
            .unindent();

        return code.build();
    }

}
