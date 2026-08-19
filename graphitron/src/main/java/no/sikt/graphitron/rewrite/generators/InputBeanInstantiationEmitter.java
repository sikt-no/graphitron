package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Emits the {@code create<TypeName>} / {@code create<TypeName>List} helper methods that populate a
 * consumer-authored Java bean from a GraphQL input-object {@code Map}. Used by
 * {@link TypeFetcherGenerator} at the {@code *Fetchers} class assembly site for any
 * {@link CallSiteExtraction.InputBean} that appears in a method-backed field's
 * {@code callParams()}.
 *
 * <p>One emission per unique {@code beanClass} (the bean-helper queue in
 * {@link TypeFetcherGenerator} performs that dedup). The plural helper is emitted alongside the
 * singular helper unconditionally; it is cheap, and the call-site emitter chooses between them
 * based on the param's Java list-shape.
 *
 * <p>Helper signatures:
 * <pre>
 *   private static Bean createBean(Map&lt;String, Object&gt; raw);
 *   private static List&lt;Bean&gt; createBeanList(Object raw);
 * </pre>
 *
 * <p>Cycle-prevention: the helpers reference only JDK types and the consumer's bean class. They
 * never import a graphitron-emitted record.
 */
final class InputBeanInstantiationEmitter {

    private static final ClassName MAP_STRING_OBJECT_RAW = ClassName.get(Map.class);
    private static final ClassName LIST = ClassName.get(List.class);
    /** {@code Map<?, ?>}: the wire-map type every helper reads through, castable to without a warning. */
    private static final TypeName WILDCARD_MAP = ParameterizedTypeName.get(MAP_STRING_OBJECT_RAW,
        WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class));

    private InputBeanInstantiationEmitter() {}

    /**
     * Emits {@code private static Bean createBean(Map<?, ?> raw)}: null in → null out,
     * otherwise instantiate the bean by populating each field from {@code raw.get(mapKey)}
     * through its per-field transform.
     *
     * <p>The parameter is wildcarded rather than {@code Map<String, Object>} so that a caller
     * holding an untyped wire value can reach it through an {@code instanceof Map<?, ?>} pattern
     * instead of an unchecked cast. That matters because generated sources land in the consumer's
     * build: an unchecked-cast warning there is a hard failure under {@code -Werror}, and no
     * {@code @SuppressWarnings} can be attached to a cast sitting inside an expression. Widening
     * costs nothing at the reading end, since {@code Map.get} takes {@code Object} and every value
     * read out of it is cast to the member's own type regardless.
     *
     * <p>That rule is build-enforced rather than asserted on emitted text: {@code
     * graphitron-sakila-example} compiles the whole emitted tree under {@code -Xlint:all -Werror} at
     * {@code <release>17</release>}, and its {@code FilmReviewGroupedInput} fixture carries a singular
     * nested-bean member, so a regression to the cast form fails that module's build.
     */
    static MethodSpec buildSingularHelper(CallSiteExtraction.InputBean ib) {
        return buildSingularHelper(ib, FetchersHelperNames.bare());
    }

    static MethodSpec buildSingularHelper(CallSiteExtraction.InputBean ib, FetchersHelperNames names) {
        ClassName bean = ib.beanClass();
        var b = MethodSpec.methodBuilder(names.createSingular(bean))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(bean)
            .addParameter(WILDCARD_MAP, "raw")
            .addStatement("if (raw == null) return null");

        // One Map local per distinct grouping prefix, parent before child, so each field local below
        // reads from a single already-descended root regardless of how deep it was flattened from.
        emitGroupDescents(b, ib);
        // Declare a typed local per field via the per-field extraction expression.
        for (var fb : ib.fields()) {
            b.addStatement("$T $L = $L",
                fieldLocalType(fb), fb.javaFieldName(), perFieldValueExpr(fb, names, rootLocal(fb)));
        }
        // Populate the bean: positional ctor for records, no-arg + setters for JavaBeans.
        switch (ib.target()) {
            case RECORD -> {
                var args = CodeBlock.builder();
                boolean first = true;
                for (var fb : ib.fields()) {
                    if (!first) args.add(", ");
                    first = false;
                    args.add("$L", fb.javaFieldName());
                }
                b.addStatement("return new $T($L)", bean, args.build());
            }
            case JAVA_BEAN -> {
                b.addStatement("$T bean = new $T()", bean, bean);
                for (var fb : ib.fields()) {
                    b.addStatement("bean.$L($L)",
                        "set" + capitalize(fb.javaFieldName()), fb.javaFieldName());
                }
                b.addStatement("return bean");
            }
        }
        return b.build();
    }

    /**
     * Emits {@code private static List<Bean> createBeanList(Object raw)}: null in → null out,
     * otherwise downcast the {@code Object} to {@code List<Map<String, Object>>}, reject null
     * elements (a non-null SDL element type forbids them), and map each element through the
     * singular helper. The {@code List} suffix is used unconditionally; appending a literal
     * {@code "s"} produces ugly names ({@code createDetailss}) for types already ending in
     * {@code s}, and consumers commonly use such names.
     *
     * <p>The per-element narrowing is a cast to {@code Map<?, ?>}, which has no type arguments to
     * check and so carries no {@code @SuppressWarnings}. Same enforcer as
     * {@link #buildSingularHelper}: the emitted tree compiles under {@code -Werror}, which is what a
     * regression to a concretely-parameterized cast would fail.
     */
    static MethodSpec buildPluralHelper(CallSiteExtraction.InputBean ib, ClassName enclosingClass) {
        return buildPluralHelper(ib, enclosingClass, FetchersHelperNames.bare());
    }

    static MethodSpec buildPluralHelper(CallSiteExtraction.InputBean ib, ClassName enclosingClass,
            FetchersHelperNames names) {
        ClassName bean = ib.beanClass();
        TypeName listOfBean = ParameterizedTypeName.get(LIST, bean);
        String pluralName = names.createPlural(bean);
        String singularName = names.createSingular(bean);
        var b = MethodSpec.methodBuilder(pluralName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfBean)
            .addParameter(Object.class, "raw")
            .addStatement("if (raw == null) return null")
            .addStatement("$T<?> list = ($T<?>) raw", LIST, LIST)
            // The element cast is to Map<?, ?>, which has no type arguments to check and so needs no
            // @SuppressWarnings: the singular helper accepts the wildcarded map for exactly this reason.
            .addStatement("return list.stream().map(e -> {\n"
                + "  if (e == null) throw new IllegalArgumentException(\"$L: null element not allowed in list argument\");\n"
                + "  return $L(($T) e);\n"
                + "}).toList()",
                pluralName, singularName, WILDCARD_MAP);
        return b.build();
    }

    /**
     * Declares one {@code Map<?, ?>} local per distinct grouping prefix across the bean's bindings,
     * in first-encounter order with every ancestor prefix declared before the prefixes below it:
     *
     * <pre>
     *   Map&lt;?, ?&gt; varighetMap = raw.get("varighet") instanceof Map&lt;?, ?&gt; varighetGroup
     *       ? varighetGroup : Map.of();
     * </pre>
     *
     * <p>An absent, null, or non-{@code Map} group binds the empty map rather than {@code null}, so
     * every hoisted field's expression stays exactly the expression an unflattened field emits: no
     * arm of {@link #perFieldValueExpr} is null-safe against its own root, and a group that is
     * simply not there must yield {@code null} per member, which ordinary {@code Map.get} semantics
     * already deliver. A bean with no flattened field emits nothing here and its helper body is
     * byte-identical to the pre-flattening one.
     */
    private static void emitGroupDescents(MethodSpec.Builder b, CallSiteExtraction.InputBean ib) {
        var declared = new LinkedHashSet<List<String>>();
        for (var fb : ib.fields()) {
            List<String> path = fb.accessPath();
            for (int level = 1; level < path.size(); level++) {
                List<String> prefix = path.subList(0, level);
                if (!declared.add(List.copyOf(prefix))) {
                    continue;
                }
                String parent = level == 1 ? "raw" : mapLocalName(path.subList(0, level - 1));
                b.addStatement("$T<?, ?> $L = $L.get($S) instanceof $T<?, ?> $L ? $L : $T.of()",
                    MAP_STRING_OBJECT_RAW, mapLocalName(prefix), parent, path.get(level - 1),
                    MAP_STRING_OBJECT_RAW, groupPatternName(prefix), groupPatternName(prefix),
                    MAP_STRING_OBJECT_RAW);
            }
        }
    }

    /** The {@code Map} local a binding's value is read from: its group's descent local, or {@code raw}. */
    private static String rootLocal(CallSiteExtraction.FieldBinding fb) {
        List<String> path = fb.accessPath();
        return path.size() == 1 ? "raw" : mapLocalName(path.subList(0, path.size() - 1));
    }

    /**
     * The descent local for a grouping prefix: the camel-joined prefix plus {@code "Map"}
     * ({@code ["varighet"] -> "varighetMap"}), the naming scheme
     * {@link JooqRecordInstantiationEmitter} uses on the column axis. Deriving the name from the
     * whole prefix keeps two groups sharing a leaf name apart.
     */
    private static String mapLocalName(List<String> prefix) {
        return camelJoin(prefix) + "Map";
    }

    /** The pattern variable bound by a descent's {@code instanceof}, named from the same prefix. */
    private static String groupPatternName(List<String> prefix) {
        return camelJoin(prefix) + "Group";
    }

    /** Camel-joins path elements: the first verbatim, each subsequent capitalised and concatenated. */
    private static String camelJoin(List<String> parts) {
        var sb = new StringBuilder(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            sb.append(capitalize(parts.get(i)));
        }
        return sb.toString();
    }

    /** The Java type of the per-field local variable (with {@code List<...>} when list-shaped). */
    private static TypeName fieldLocalType(CallSiteExtraction.FieldBinding fb) {
        ClassName elt = ClassName.bestGuess(fb.javaElementTypeName());
        return fb.list() ? ParameterizedTypeName.get(LIST, elt) : elt;
    }

    /**
     * Expression that produces the typed value to populate the bean's field. Routes by leaf
     * extraction: Direct gives a cast; EnumValueOf decodes via {@code valueOf}; nested InputBean
     * delegates to the recursive {@code createNested(...)} helper.
     *
     * <p>{@code root} is the {@code Map} local the wire value is read from: {@code raw} for a field
     * declared on the bean's own input type, or the enclosing group's descent local for a flattened
     * one. Every arm reads {@code root.get(mapKey)}, so flattening changes the root and nothing else.
     */
    private static CodeBlock perFieldValueExpr(CallSiteExtraction.FieldBinding fb,
            FetchersHelperNames names, String root) {
        String sdl = fb.mapKey();
        // Exhaustive over CallSiteExtraction with no default: the classifier (InputBeanResolver)
        // produces only Direct / EnumValueOf / InputBean / NodeIdDecodeRecord on a FieldBinding
        // leaf, and the remaining permits are unreachable-by-construction here. Listing every
        // permit (rather than a catch-all default) keeps "validator mirrors classifier invariants"
        // honest: a new CallSiteExtraction permit fails *this* compile until it is handled or
        // explicitly ruled out, instead of silently hitting a runtime throw.
        return switch (fb.leaf()) {
            case CallSiteExtraction.Direct ignored -> directExpr(fb, sdl, root);
            case CallSiteExtraction.EnumValueOf ev -> enumExpr(fb, ev, sdl, root);
            case CallSiteExtraction.InputBean nested -> nestedBeanExpr(fb, nested, sdl, names, root);
            case CallSiteExtraction.NodeIdDecodeRecord rec -> recordDecodeExpr(fb, rec, sdl, names, root);
            case CallSiteExtraction.ContextArg ignored -> throw notALeaf(fb);
            case CallSiteExtraction.JooqConvert ignored -> throw notALeaf(fb);
            case CallSiteExtraction.NestedInputField ignored -> throw notALeaf(fb);
            case CallSiteExtraction.NodeIdDecodeKeys ignored -> throw notALeaf(fb);
            // JooqRecord is a top-level @service param extraction, never an InputBean field leaf
            // (the resolver never produces it here); genuinely unreachable.
            case CallSiteExtraction.JooqRecord ignored -> throw notALeaf(fb);
        };
    }

    private static IllegalStateException notALeaf(CallSiteExtraction.FieldBinding fb) {
        return new IllegalStateException(
            "CallSiteExtraction." + fb.leaf().getClass().getSimpleName()
            + " is not a valid InputBean field leaf (field '" + String.join(".", fb.accessPath()) + "'); the"
            + " InputBeanResolver classifier never produces it here");
    }

    /**
     * Routes a jOOQ-record member through its per-record-type {@code decode<RecordType>} helper
     * (emitted by {@link #buildRecordDecodeHelper}), or, when the member is list-valued, through
     * the {@code decode<RecordType>List} variant ({@link #buildRecordDecodeHelperList}). Keeps the
     * bean-field assignment a one-liner; the decode-and-materialize logic lives in the readable
     * statement-form helper.
     */
    private static CodeBlock recordDecodeExpr(CallSiteExtraction.FieldBinding fb,
                                              CallSiteExtraction.NodeIdDecodeRecord rec, String sdl,
                                              FetchersHelperNames names, String root) {
        String helper = fb.list() ? recordDecodeListHelperName(rec, names) : recordDecodeHelperName(rec, names);
        return CodeBlock.of("$L($L.get($S))", helper, root, sdl);
    }

    /** {@code decode<RecordType>}, e.g. {@code decodeFilmRecord}. Named from the target table's record class. */
    private static String recordDecodeHelperName(CallSiteExtraction.NodeIdDecodeRecord rec, FetchersHelperNames names) {
        return names.decodeSingular(rec.table().recordClass());
    }

    /** {@code decode<RecordType>List}, e.g. {@code decodeFilmRecordList}. */
    private static String recordDecodeListHelperName(CallSiteExtraction.NodeIdDecodeRecord rec, FetchersHelperNames names) {
        return names.decodeList(rec.table().recordClass());
    }

    private static CodeBlock directExpr(CallSiteExtraction.FieldBinding fb, String sdl, String root) {
        ClassName elt = ClassName.bestGuess(fb.javaElementTypeName());
        if (fb.list()) {
            // List-shaped Direct: cast the raw Map.get value to List<T>. Null in → null out.
            return CodeBlock.of("$L.get($S) == null ? null : ($T<$T>) $L.get($S)",
                root, sdl, LIST, elt, root, sdl);
        }
        return CodeBlock.of("($T) $L.get($S)", elt, root, sdl);
    }

    private static CodeBlock enumExpr(CallSiteExtraction.FieldBinding fb,
                                       CallSiteExtraction.EnumValueOf ev, String sdl, String root) {
        ClassName enumClass = ClassName.bestGuess(ev.enumClassName());
        if (fb.list()) {
            // List of enums: stream the raw List<String>, valueOf each, collect.
            return CodeBlock.of(
                "$L.get($S) == null ? null : (($T<?>) $L.get($S)).stream()"
                + ".map(s -> s == null ? null : $T.valueOf((String) s)).toList()",
                root, sdl, LIST, root, sdl, enumClass);
        }
        return CodeBlock.of("$L.get($S) == null ? null : $T.valueOf((String) $L.get($S))",
            root, sdl, enumClass, root, sdl);
    }

    private static CodeBlock nestedBeanExpr(CallSiteExtraction.FieldBinding fb,
                                             CallSiteExtraction.InputBean nested, String sdl,
                                             FetchersHelperNames names, String root) {
        String singular = names.createSingular(nested.beanClass());
        String plural = names.createPlural(nested.beanClass());
        if (fb.list()) {
            return CodeBlock.of("$L($L.get($S))", plural, root, sdl);
        }
        // Singular nested bean: narrow the wire value with an instanceof pattern rather than casting
        // it to Map<String, Object>. The cast form is unchecked, and a warning inside an expression
        // cannot carry @SuppressWarnings, so it fails the consumer's build under -Werror. The pattern
        // also subsumes the null guard: a null (or non-Map) value simply does not match.
        return CodeBlock.of("$L.get($S) instanceof $T $L ? $L($L) : null",
            root, sdl, WILDCARD_MAP, nestedLocalName(fb), singular, nestedLocalName(fb));
    }

    /**
     * The pattern variable a singular nested bean is narrowed into, derived from the field's whole
     * access path so two groups holding a same-named nested bean bind distinct locals. Suffixed to
     * stay clear of the field local the enclosing statement is declaring.
     */
    private static String nestedLocalName(CallSiteExtraction.FieldBinding fb) {
        return camelJoin(fb.accessPath()) + "Raw";
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Walks a {@link CallSiteExtraction.InputBean} and collects all unique {@code InputBean}s it
     * transitively references — itself plus any nested {@code InputBean} leaves on its field
     * bindings. Used by the call site to drive helper emission breadth-first.
     *
     * <p>Dedup is by {@link CallSiteExtraction.InputBean#beanClass()}: two top-level
     * {@code InputBean}s carrying the same bean class are assumed structurally equal (the
     * resolver maps the same Java class to the same SDL input-object type, by construction).
     * The first occurrence wins.
     */
    static void collectTransitively(CallSiteExtraction.InputBean ib,
                                     java.util.Map<ClassName, CallSiteExtraction.InputBean> out) {
        if (out.putIfAbsent(ib.beanClass(), ib) != null) return;
        for (var fb : ib.fields()) {
            if (fb.leaf() instanceof CallSiteExtraction.InputBean nested) {
                collectTransitively(nested, out);
            }
        }
    }

    /**
     * Collects the {@link CallSiteExtraction.NodeIdDecodeRecord} leaves across the given beans into
     * two dedup maps keyed by jOOQ record type. The caller passes the already-transitively-collected
     * bean set (e.g. {@code collectTransitively}'s output), so this is a flat one-level field scan
     * per bean rather than a second tree walk.
     *
     * <p>Every record type that appears (scalar- or list-valued) lands in {@code scalarOut}: the
     * scalar {@code decode<RecordType>} helper is always emitted, because the list variant delegates
     * to it per element. A record type that appears list-valued anywhere additionally lands in
     * {@code listOut}, driving the {@code decode<RecordType>List} variant. List-ness is read off the
     * enclosing {@link CallSiteExtraction.FieldBinding#list()}, not the leaf, so the two variants
     * dedup independently and a type used both ways emits both helpers, each once.
     */
    static void collectRecordDecoders(java.util.Collection<CallSiteExtraction.InputBean> beans,
            java.util.Map<ClassName, CallSiteExtraction.NodeIdDecodeRecord> scalarOut,
            java.util.Map<ClassName, CallSiteExtraction.NodeIdDecodeRecord> listOut) {
        for (var ib : beans) {
            for (var fb : ib.fields()) {
                if (fb.leaf() instanceof CallSiteExtraction.NodeIdDecodeRecord rec) {
                    ClassName key = rec.table().recordClass();
                    scalarOut.putIfAbsent(key, rec);
                    if (fb.list()) {
                        listOut.putIfAbsent(key, rec);
                    }
                }
            }
        }
    }

    /**
     * Emits {@code private static <Record> decode<Record>(Object wire)}: decode the base64 NodeId to
     * its raw key values and load them positionally into a fresh target record via
     * {@link org.jooq.Record#fromArray(Object[], org.jooq.Field...)}. Statement form (explicit types,
     * named locals, no {@code var}) per the "generated code is read and debugged" principle.
     *
     * <pre>
     *   private static SakRecord decodeSakRecord(Object wire) {
     *       if (!(wire instanceof String nodeId)) {
     *           return null;
     *       }
     *       String[] values = NodeIdEncoder.decodeValues("Sak", nodeId);
     *       if (values == null || values.length != 1) {
     *           throw GraphqlErrorException.newErrorException().message("...").build();
     *       }
     *       SakRecord decoded = new SakRecord();
     *       decoded.fromArray(values, Tables.SAK.SAK_ID);
     *       return decoded;
     *   }
     * </pre>
     *
     * <p>{@code fromArray} maps the positional key values onto the key columns, coercing each through
     * the column's {@code DataType} / registered {@code Converter} (the
     * {@link org.jooq.Configuration}'s {@code converterProvider} path). One call regardless of key
     * arity, so a composite key just names N fields. This deliberately does <em>not</em> use
     * {@code col.getDataType().convert(Object)}: that overload is deprecated for removal in jOOQ
     * 3.20 (it bypasses the {@code converterProvider} and is buggy for user-defined types), and
     * suppressing the resulting warning on a helper that lands in the consumer's {@code *Fetchers}
     * package would hide the eventual hard compile break.
     * {@code fromArray} is the supported, non-deprecated coercion path and keeps the real compile-tier
     * check (the {@code Tables.<T>.<col>} field references must exist on the record). The local is
     * named {@code decoded}, not {@code record}, since {@code record} is a context-sensitive keyword.
     *
     * <p>A non-{@code String} (null / absent) wire value yields a {@code null} member: graphql-java
     * enforces {@code ID!} non-nullness at the boundary, so for a non-null field the {@code String}
     * branch is always taken; for a nullable field the {@code null} member is correct. A
     * type-mismatch decode ({@code decodeValues} returns {@code null} on a typeId mismatch, or a
     * wrong arity) is an authored-input error and throws, mirroring the {@code ThrowOnMismatch} arm.
     */
    static MethodSpec buildRecordDecodeHelper(CallSiteExtraction.NodeIdDecodeRecord rec) {
        return buildRecordDecodeHelper(rec, FetchersHelperNames.bare());
    }

    static MethodSpec buildRecordDecodeHelper(CallSiteExtraction.NodeIdDecodeRecord rec, FetchersHelperNames names) {
        // One body, two hosts: the conditions class a @condition's key-column projection lands on
        // cannot reach this emitter, so the derivation lives in render/ and this delegates. The name
        // stays this class's namespace's to allocate, which is why it is passed in.
        return no.sikt.graphitron.render.RecordDecodeFragments.decodeHelper(
            recordDecodeHelperName(rec, names),
            new no.sikt.graphitron.rewrite.model.HelperRef.Decode(
                rec.encoderClass(), recordDecodeHelperName(rec, names), rec.keyColumns(),
                rec.typeId()),
            rec.table());
    }

    /**
     * Emits {@code private static List<<Record>> decode<Record>List(Object wire)}: stream the wire
     * {@code List} of base64 NodeIds, materialise one record per element through the singular
     * {@link #buildRecordDecodeHelper} helper, and collect. A present-but-wrong-type element throws
     * (the singular helper already throws on mismatch), because an input-bean member is materialized
     * input, not a query predicate; there is no silent-drop path here, nor anywhere else.
     *
     * <pre>
     *   private static List<SakRecord> decodeSakRecordList(Object wire) {
     *       if (!(wire instanceof List<?> nodeIds)) {
     *           return null;
     *       }
     *       List<SakRecord> records = new ArrayList<>(nodeIds.size());
     *       for (Object element : nodeIds) {
     *           records.add(decodeSakRecord(element));
     *       }
     *       return records;
     *   }
     * </pre>
     */
    static MethodSpec buildRecordDecodeHelperList(CallSiteExtraction.NodeIdDecodeRecord rec) {
        return buildRecordDecodeHelperList(rec, FetchersHelperNames.bare());
    }

    static MethodSpec buildRecordDecodeHelperList(CallSiteExtraction.NodeIdDecodeRecord rec, FetchersHelperNames names) {
        ClassName recordType = rec.table().recordClass();
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, recordType);
        ClassName arrayList = ClassName.get(java.util.ArrayList.class);
        return MethodSpec.methodBuilder(recordDecodeListHelperName(rec, names))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(Object.class, "wire")
            .beginControlFlow("if (!(wire instanceof $T<?> nodeIds))", LIST)
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T records = new $T<>(nodeIds.size())", listOfRecord, arrayList)
            .beginControlFlow("for (Object element : nodeIds)")
            .addStatement("records.add($L(element))", recordDecodeHelperName(rec, names))
            .endControlFlow()
            .addStatement("return records")
            .build();
    }
}
