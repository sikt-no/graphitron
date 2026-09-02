package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row-construction core for the {@code @lookupKey} VALUES + JOIN keyset: the generated
 * {@code <field>InputRows} helper (typed {@code Row<N+1>[]} off the lookup arguments) and the
 * type fragments the enclosing SELECT composes with. Driven entirely by the
 * {@link ColumnMapping} data; the two argument sources ({@code DataFetchingEnvironment} at
 * root / batched sites, {@code SelectedField} at inline sites) fork only the root-extraction
 * statements, selected by {@link ArgSource}.
 *
 * <p>Moved here from the legacy lookup emitter when the projection renderer became the
 * inline path's host; the legacy hosts delegate through it, so both migration sides keep one
 * derivation of the row construction (typed {@code DSL.val(value, col.getDataType())} per cell,
 * so jOOQ applies the target column's Converter internally and renders a plain JDBC bind).
 */
public final class LookupRows {

    /** Directive context surfaced in {@link ValuesJoinRowBuilder}'s arity-cap error messages. */
    private static final String DIRECTIVE_CONTEXT = "@lookupKey";

    private static final ClassName LIST_CLASS = ClassName.get("java.util", "List");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    private LookupRows() {}

    /** Which runtime carrier the helper's root-argument extraction reads. */
    public enum ArgSource {
        /** {@code DataFetchingEnvironment env} parameter; roots read {@code env.getArgument(name)}. */
        ENV,
        /** {@code SelectedField sf} parameter; roots read {@code sf.getArguments().get(name)}. */
        SELECTED_FIELD
    }

    /**
     * Generates the {@code private static Row<N+1>[] <methodName>(<carrier>, <TargetTable> table)}
     * helper: one typed {@code DSL.row(DSL.inline(i), DSL.val(v, table.COL.getDataType()), …)}
     * per input index. Row count is the length of the first list-typed argument, broadcasting
     * scalar arguments across all rows ({@code n = 1} with no list arg); returns a zero-length
     * array when the list arg is null or empty, and callers short-circuit on
     * {@code rows.length == 0}.
     */
    public static MethodSpec buildInputRowsMethod(ColumnMapping cm, String methodName,
            TypeName targetTableClass, ArgSource argSource, String diagnosticName) {
        List<Slot> slots = requireSlots(cm, diagnosticName);
        Map<String, RootSource> roots = rootSources(slots);

        var builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ValuesJoinRowBuilder.rowArrayType(slots, Slot::targetColumn, DIRECTIVE_CONTEXT));
        switch (argSource) {
            case ENV -> builder.addParameter(
                ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env");
            case SELECTED_FIELD -> builder.addParameter(
                ClassName.get("graphql.schema", "SelectedField"), "sf");
        }
        builder.addParameter(targetTableClass, "table");

        // Extract each root argument into a local. Lists are List<?> (nullable); scalars and
        // composite-key input types both come in as Object (the Map<String,Object> for a
        // composite root is downcast per column via slotValueExpr). env.getArgument is
        // <T>-inferred so its cast is implicit; the SelectedField map read takes an explicit
        // cast for list roots.
        for (var root : roots.values()) {
            switch (argSource) {
                case ENV -> {
                    if (root.list()) {
                        builder.addStatement("$T<?> $L = env.getArgument($S)",
                            LIST_CLASS, root.localName(), root.argName());
                    } else {
                        builder.addStatement("$T $L = env.getArgument($S)",
                            Object.class, root.localName(), root.argName());
                    }
                }
                case SELECTED_FIELD -> {
                    if (root.list()) {
                        builder.addStatement("$T<?> $L = ($T<?>) sf.getArguments().get($S)",
                            LIST_CLASS, root.localName(), LIST_CLASS, root.argName());
                    } else {
                        builder.addStatement("$T $L = sf.getArguments().get($S)",
                            Object.class, root.localName(), root.argName());
                    }
                }
            }
        }

        addRowBuildingCore(builder, slots, roots);
        return builder.build();
    }

    /** {@code Row<N+1>[]} for the helper's return / call-site local declaration. */
    public static TypeName rowArrayType(ColumnMapping cm, String diagnosticName) {
        return ValuesJoinRowBuilder.rowArrayType(requireSlots(cm, diagnosticName),
            Slot::targetColumn, DIRECTIVE_CONTEXT);
    }

    /** {@code Table<Record<N+1><Integer, c1, …>>} for the VALUES derived-table local. */
    public static TypeName inputTableType(ColumnMapping cm, String diagnosticName) {
        return ValuesJoinRowBuilder.inputTableType(requireSlots(cm, diagnosticName),
            Slot::targetColumn, DIRECTIVE_CONTEXT);
    }

    /** Alias-args for {@code DSL.values(rows).as(alias, "idx", "<sqlName1>", …)}. */
    public static CodeBlock aliasArgs(ColumnMapping cm, String alias, String diagnosticName) {
        return ValuesJoinRowBuilder.aliasArgs(requireSlots(cm, diagnosticName), Slot::targetColumn, alias);
    }

    /** USING-args: {@code <tableLocal>.<COL1>, <tableLocal>.<COL2>, …}. */
    public static CodeBlock usingArgs(ColumnMapping cm, String tableLocal, String diagnosticName) {
        return ValuesJoinRowBuilder.usingArgs(requireSlots(cm, diagnosticName), Slot::targetColumn, tableLocal);
    }

    /**
     * The VALUES derived table's SQL alias for a lookup coordinate's body, a render-side
     * formula like the table-local names ({@link TableLocal}); the rendered alias appears in
     * the executed SQL, so the launcher and any remaining legacy host must spell it identically.
     */
    public static String inputTableAlias(String fieldName) {
        return fieldName + "Input";
    }

    // ------------------------------------------------------------------------------------------
    // Internals (the flat slot view and the shared row-building tail)
    // ------------------------------------------------------------------------------------------

    /**
     * Flat per-slot view of a {@link ColumnMapping}'s args. One slot per
     * {@code ScalarLookupArg}; one slot per binding for {@code MapInput} / {@code DecodedRecord}.
     * {@code decodeBinding} is non-null only for NodeId-decoded slots; the decode runs once per
     * input row at the arg layer, so {@link #addRowBuildingCore} hoists the decode call to a
     * per-row local shared across all bindings of the same arg.
     */
    private record Slot(
            String argName,
            ColumnRef targetColumn,
            boolean list,
            String compositeFieldName,
            CallSiteExtraction extraction,
            DecodeBinding decodeBinding) {

        boolean isComposite() { return compositeFieldName != null; }
    }

    /**
     * Per-binding slot for a NodeId-decoded arg: the extraction it decodes through, and which
     * positional {@code Record<N>} value to read. Lookup keys only ever carry the throwing arm.
     */
    private record DecodeBinding(CallSiteExtraction.NodeIdDecodeKeys extraction, int index) {

        HelperRef.Decode decodeMethod() {
            return extraction.decodeMethod();
        }
    }

    private static List<Slot> requireSlots(ColumnMapping cm, String diagnosticName) {
        if (cm.args().isEmpty()) {
            // The resolver enforces non-empty lookup args before classification; reaching this
            // is a generator-side bug, not a schema error.
            throw new IllegalStateException(
                "lookup coordinate '" + diagnosticName + "' has no lookup args; classifier "
                + "invariant violated");
        }
        return flattenSlots(cm);
    }

    private static List<Slot> flattenSlots(ColumnMapping cm) {
        var slots = new java.util.ArrayList<Slot>();
        for (var arg : cm.args()) {
            switch (arg) {
                case ColumnMapping.LookupArg.ScalarLookupArg s -> {
                    // Hoist the per-row decode for arity-1 NodeId-as-lookup-key (Throw or Skip)
                    // onto the same DecodeBinding mechanism DecodedRecord uses; the slot's own
                    // extraction folds to Direct so slotValueExpr reads from the hoisted
                    // Record1<T>.value1() local.
                    if (s.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys nid) {
                        slots.add(new Slot(s.argName(), s.targetColumn(), s.list(), null,
                            new CallSiteExtraction.Direct(), new DecodeBinding(nid, 0)));
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
                            new CallSiteExtraction.Direct(), new DecodeBinding(d.extraction(), b.index())));
                    }
                }
            }
        }
        return slots;
    }

    /**
     * Describes the top-level argument backing one or more {@link Slot}s. All slots sharing a
     * root arg share a single extracted local, which is what makes composite-key input types
     * work; {@code list} is the outer argument's cardinality, inherited by every rooted slot.
     */
    private record RootSource(String argName, boolean list, String localName) {
        static RootSource of(String argName, boolean list) {
            String camel = toCamelCase(argName);
            return new RootSource(argName, list, list ? camel + "Keys" : camel);
        }
    }

    private static Map<String, RootSource> rootSources(List<Slot> slots) {
        var roots = new LinkedHashMap<String, RootSource>();
        for (var slot : slots) {
            roots.computeIfAbsent(slot.argName(), k -> RootSource.of(k, slot.list()));
        }
        return roots;
    }

    /**
     * Shared row-building tail: row-count computation, typed-row-array creation, per-row decode
     * locals for NodeId-decoded args (Throw fails the request, Skip drops the row from the
     * VALUES set and trims the array), typed-value loop, return.
     */
    private static void addRowBuildingCore(MethodSpec.Builder builder,
            List<Slot> slots, Map<String, RootSource> roots) {
        var primaryList = roots.values().stream().filter(RootSource::list).findFirst().orElse(null);
        if (primaryList == null) {
            builder.addStatement("int n = 1");
        } else {
            String local = primaryList.localName();
            builder.addStatement("int n = $L == null ? 0 : $L.size()", local, local);
        }

        var arrayCode = CodeBlock.builder();
        ValuesJoinRowBuilder.emitRowArrayDecl(arrayCode, slots, Slot::targetColumn, DIRECTIVE_CONTEXT, "rows", "n");
        builder.addCode(arrayCode.build());

        Map<String, DecodeBinding> decodeArgs = new LinkedHashMap<>();
        for (var slot : slots) {
            if (slot.decodeBinding() != null) {
                decodeArgs.putIfAbsent(slot.argName(), slot.decodeBinding());
            }
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
            // The only failure mode a lookup key can take: a malformed or wrong-type id is a client
            // mistake and fails the field. The carrier's pruning sibling belongs to a polymorphic
            // root's per-participant branches, where a miss means another branch owns the id; a
            // lookup has one target table and no sibling to hand it to.
            builder.addStatement("throw $T.newErrorException().message($S).build()", graphqlErr,
                "Decoded NodeId did not match the expected type for argument '" + argName + "'");
            builder.endControlFlow();
        }

        CodeBlock cells = ValuesJoinRowBuilder.cellsCode(
            slots, Slot::targetColumn, CodeBlock.of("$T.inline(i)", DSL), "table",
            (slot, idx) -> slotValueExpr(slot, roots.get(slot.argName())));
        builder.addStatement("rows[i] = $T.row($L)", DSL, cells);
        builder.endControlFlow();
        builder.addStatement("return rows");
    }

    private static String decodeRecordLocal(String rootLocalName) {
        return rootLocalName + "Rec";
    }

    /**
     * The value expression that reads one lookup column's raw value inside the row-building
     * loop: NodeId-decoded slots read the per-row decode local, composite slots drill into the
     * root's {@code Map} by field name, plain slots read the local directly (element {@code i}
     * of a list root).
     */
    private static CodeBlock slotValueExpr(Slot slot, RootSource root) {
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

    private static String toCamelCase(String snakeName) {
        var parts = snakeName.split("_");
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            sb.append(parts[i], 1, parts[i].length());
        }
        return sb.toString();
    }
}
