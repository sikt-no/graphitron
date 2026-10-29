package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The bodies that populate a consumer-authored Java bean from a GraphQL input-object {@code Map}:
 * {@code create<Bean>} / {@code create<Bean>List}, and the decode helpers a bean's {@code @nodeId}
 * members call. Two hosts emit them, and that is why they are here rather than in either: a
 * {@code <Type>Fetchers} class for an {@link CallSiteExtraction.InputBean} on a {@code @service}
 * parameter, and a {@code <Parent>Conditions} class ({@link ConditionGlueRenderer}) for one on a
 * {@code @condition} parameter. The generator side reaches these from its own emitter; the
 * conditions class cannot reach that emitter, and a second copy of each body would drift. The
 * arrangement is {@link RecordDecodeFragments}'s.
 *
 * <p>Naming is not here. One generated class's private-static method namespace is that class's to
 * allocate, so the bodies take a {@link Names} and read every helper name, their own and the ones
 * they call, through it.
 *
 * <p>Helper signatures:
 * <pre>
 *   private static Bean createBean(Map&lt;?, ?&gt; raw);
 *   private static List&lt;Bean&gt; createBeanList(Object raw);
 * </pre>
 *
 * <p>The helpers reference only JDK types, the consumer's bean classes and, for a {@code @nodeId}
 * member, the generated records of its node types.
 */
public final class InputBeanFragments {

    private static final ClassName MAP = ClassName.get(Map.class);
    private static final ClassName LIST = ClassName.get(List.class);
    /** {@code Map<?, ?>}: the wire-map type every helper reads through, castable to without a warning. */
    public static final TypeName WILDCARD_MAP = ParameterizedTypeName.get(MAP,
        WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class));

    private InputBeanFragments() {}

    /**
     * The helper names one host allocates for the bean family. Every method answers for a name the
     * host emits; a host that emits a name lazily may register the helper as the name is asked for.
     */
    public interface Names {
        /** {@code create<Bean>}. */
        String createSingular(ClassName beanClass);
        /** {@code create<Bean>List}. */
        String createPlural(ClassName beanClass);
        /** {@code decode<TypeName>Record}, the single-type decode of one node type. */
        String decodeSingular(String nodeTypeName);
        /** {@code decode<TypeName>RecordList}. */
        String decodeList(String nodeTypeName);
        /** {@code decode<Container>Record}, the polymorphic container's dispatch. */
        String decodeContainerSingular(String containerName);
        /** {@code decode<Container>RecordList}. */
        String decodeContainerList(String containerName);
        /** The null-returning per-member helper one container arm calls. */
        String decodeContainerMember(String containerName, String memberTypeName);
    }

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
    public static MethodSpec singularHelper(CallSiteExtraction.InputBean ib, Names names) {
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
     * otherwise downcast the {@code Object} to {@code List<?>}, reject null elements (a non-null SDL
     * element type forbids them), and map each element through the singular helper. The
     * {@code List} suffix is used unconditionally; appending a literal {@code "s"} produces ugly
     * names ({@code createDetailss}) for types already ending in {@code s}, and consumers commonly
     * use such names.
     *
     * <p>The per-element narrowing is a cast to {@code Map<?, ?>}, which has no type arguments to
     * check and so carries no {@code @SuppressWarnings}. Same enforcer as
     * {@link #singularHelper}: the emitted tree compiles under {@code -Werror}, which is what a
     * regression to a concretely-parameterized cast would fail.
     */
    public static MethodSpec pluralHelper(CallSiteExtraction.InputBean ib, Names names) {
        ClassName bean = ib.beanClass();
        TypeName listOfBean = ParameterizedTypeName.get(LIST, bean);
        String pluralName = names.createPlural(bean);
        String singularName = names.createSingular(bean);
        return MethodSpec.methodBuilder(pluralName)
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
                pluralName, singularName, WILDCARD_MAP)
            .build();
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
     * already deliver. A bean with no flattened field emits nothing here.
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
                    MAP, mapLocalName(prefix), parent, path.get(level - 1),
                    MAP, groupPatternName(prefix), groupPatternName(prefix), MAP);
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
     * ({@code ["varighet"] -> "varighetMap"}), the naming scheme the jOOQ-record helpers use on the
     * column axis. Deriving the name from the whole prefix keeps two groups sharing a leaf name apart.
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

    /**
     * The Java type of the per-field local variable (with {@code List<...>} when list-shaped).
     *
     * <p>The polymorphic leaf's own admitted slot type rather than the member's declared name: the
     * member is declared as a jOOQ record supertype, which a reflected signature spells with its
     * generic argument ({@code org.jooq.UpdatableRecord<?>}), and that string is not a class name
     * javapoet can read. The admitted type is the same supertype with the wildcard rebuilt, which is
     * what the container helper returns and therefore the right type for the local it lands in.
     */
    private static TypeName fieldLocalType(CallSiteExtraction.FieldBinding fb) {
        TypeName elt = fb.leaf() instanceof CallSiteExtraction.NodeIdDecodePolymorphicRecord poly
            ? poly.slotType().typeName()
            : ClassName.bestGuess(fb.javaElementTypeName());
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
    private static CodeBlock perFieldValueExpr(CallSiteExtraction.FieldBinding fb, Names names,
            String root) {
        String sdl = fb.mapKey();
        // Exhaustive over CallSiteExtraction with no default: the classifier (InputBeanResolver)
        // produces only Direct / EnumValueOf / InputBean / NodeIdDecodeRecord /
        // NodeIdDecodePolymorphicRecord on a FieldBinding leaf, and the remaining permits are
        // unreachable-by-construction here. Listing every permit (rather than a catch-all default)
        // means a new CallSiteExtraction permit fails this compile until it is handled or
        // explicitly ruled out, instead of silently hitting a runtime throw.
        return switch (fb.leaf()) {
            case CallSiteExtraction.Direct ignored -> directExpr(fb, sdl, root);
            case CallSiteExtraction.EnumValueOf ev -> enumExpr(fb, ev, sdl, root);
            case CallSiteExtraction.InputBean nested -> nestedBeanExpr(fb, nested, sdl, names, root);
            case CallSiteExtraction.NodeIdDecodeRecord rec -> CodeBlock.of("$L($L.get($S))",
                fb.list() ? names.decodeList(rec.typeName()) : names.decodeSingular(rec.typeName()),
                root, sdl);
            case CallSiteExtraction.NodeIdDecodePolymorphicRecord poly -> CodeBlock.of("$L($L.get($S))",
                fb.list()
                    ? names.decodeContainerList(poly.containerName())
                    : names.decodeContainerSingular(poly.containerName()),
                root, sdl);
            case CallSiteExtraction.ContextArg ignored -> throw notALeaf(fb);
            case CallSiteExtraction.SessionHandleRead ignored -> throw notALeaf(fb);
            case CallSiteExtraction.SessionMemberRead ignored -> throw notALeaf(fb);
            case CallSiteExtraction.JooqConvert ignored -> throw notALeaf(fb);
            case CallSiteExtraction.NestedInputField ignored -> throw notALeaf(fb);
            case CallSiteExtraction.NodeIdDecodeKeys ignored -> throw notALeaf(fb);
            // JooqRecord is a top-level @service param extraction, never an InputBean field leaf.
            case CallSiteExtraction.JooqRecord ignored -> throw notALeaf(fb);
            // A list-of-input filter's row read is a condition binding, never a bean field leaf.
            case CallSiteExtraction.ElementRows ignored -> throw notALeaf(fb);
        };
    }

    private static IllegalStateException notALeaf(CallSiteExtraction.FieldBinding fb) {
        return new IllegalStateException(
            "CallSiteExtraction." + fb.leaf().getClass().getSimpleName()
            + " is not a valid InputBean field leaf (field '" + String.join(".", fb.accessPath()) + "'); the"
            + " InputBeanResolver classifier never produces it here");
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
                                            Names names, String root) {
        if (fb.list()) {
            return CodeBlock.of("$L($L.get($S))", names.createPlural(nested.beanClass()), root, sdl);
        }
        // Singular nested bean: narrow the wire value with an instanceof pattern rather than casting
        // it to Map<String, Object>. The cast form is unchecked, and a warning inside an expression
        // cannot carry @SuppressWarnings, so it fails the consumer's build under -Werror. The pattern
        // also subsumes the null guard: a null (or non-Map) value simply does not match.
        return CodeBlock.of("$L.get($S) instanceof $T $L ? $L($L) : null",
            root, sdl, WILDCARD_MAP, nestedLocalName(fb), names.createSingular(nested.beanClass()),
            nestedLocalName(fb));
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
     * Collects {@code ib} and every {@code InputBean} it transitively references through nested
     * leaves into {@code out}, keyed by {@link CallSiteExtraction.InputBean#beanClass()}, first
     * occurrence winning. Two {@code InputBean}s carrying the same bean class are taken to be
     * structurally equal: the resolver maps one Java class to one SDL input-object type.
     */
    public static void collectTransitively(CallSiteExtraction.InputBean ib,
                                           Map<ClassName, CallSiteExtraction.InputBean> out) {
        if (out.putIfAbsent(ib.beanClass(), ib) != null) return;
        for (var fb : ib.fields()) {
            if (fb.leaf() instanceof CallSiteExtraction.InputBean nested) {
                collectTransitively(nested, out);
            }
        }
    }

    /**
     * Collects the {@link CallSiteExtraction.NodeIdDecodeRecord} leaves across the given beans into
     * two dedup maps keyed by node type name, a flat one-level field scan per bean (the caller
     * passes the already-transitively-collected bean set).
     *
     * <p>The key is the node type and not the record class the type's table backs: the body is a
     * function of the node type (its typeId, its key columns and its table all derive from one
     * {@code @node} declaration), and two node types over one table are two bodies that differ in
     * the typeId they check against. First-wins is therefore lossless.
     *
     * <p>Every node type that appears lands in {@code scalarOut}: the scalar helper is always
     * emitted, because the list variant delegates to it per element. A type that appears list-valued
     * anywhere additionally lands in {@code listOut}. List-ness is read off the enclosing
     * {@link CallSiteExtraction.FieldBinding#list()}, not the leaf.
     */
    public static void collectRecordDecoders(Collection<CallSiteExtraction.InputBean> beans,
            Map<String, CallSiteExtraction.NodeIdDecodeRecord> scalarOut,
            Map<String, CallSiteExtraction.NodeIdDecodeRecord> listOut) {
        for (var ib : beans) {
            for (var fb : ib.fields()) {
                if (fb.leaf() instanceof CallSiteExtraction.NodeIdDecodeRecord rec) {
                    scalarOut.putIfAbsent(rec.typeName(), rec);
                    if (fb.list()) {
                        listOut.putIfAbsent(rec.typeName(), rec);
                    }
                }
            }
        }
    }

    /**
     * Collects the {@link CallSiteExtraction.NodeIdDecodePolymorphicRecord} leaves across the given
     * beans into two dedup maps keyed by container name, on {@link #collectRecordDecoders}'s own
     * terms.
     *
     * <p>Keyed on the container name and not on the slot type, even though the helper's return type
     * is the slot's: two slots naming one container are one dispatch. A class hosting the same
     * container at two slot types would need two bodies the container name cannot tell apart; the
     * first slot's type wins the body, and the second slot's call fails the consumer's compile
     * rather than silently mistyping.
     */
    public static void collectPolymorphicDecoders(Collection<CallSiteExtraction.InputBean> beans,
            Map<String, CallSiteExtraction.NodeIdDecodePolymorphicRecord> scalarOut,
            Map<String, CallSiteExtraction.NodeIdDecodePolymorphicRecord> listOut) {
        for (var ib : beans) {
            for (var fb : ib.fields()) {
                if (fb.leaf() instanceof CallSiteExtraction.NodeIdDecodePolymorphicRecord poly) {
                    scalarOut.putIfAbsent(poly.containerName(), poly);
                    if (fb.list()) {
                        listOut.putIfAbsent(poly.containerName(), poly);
                    }
                }
            }
        }
    }

    /**
     * Emits {@code private static List<<Record>> decode<TypeName>RecordList(Object wire)}: one record
     * per element of the wire {@code List} of node ids, through the singular helper. A
     * present-but-wrong-type element throws there, because an input-bean member is materialized
     * input, not a query predicate.
     */
    public static MethodSpec recordDecodeListHelper(CallSiteExtraction.NodeIdDecodeRecord rec,
            Names names) {
        ClassName recordType = CatalogRefs.recordClass(rec.table());
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, recordType);
        return MethodSpec.methodBuilder(names.decodeList(rec.typeName()))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(Object.class, "wire")
            .beginControlFlow("if (!(wire instanceof $T<?> nodeIds))", LIST)
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T records = new $T<>(nodeIds.size())", listOfRecord,
                ClassName.get(java.util.ArrayList.class))
            .beginControlFlow("for (Object element : nodeIds)")
            .addStatement("records.add($L(element))", names.decodeSingular(rec.typeName()))
            .endControlFlow()
            .addStatement("return records")
            .build();
    }

    /**
     * Every method one polymorphic container's decode needs: one null-returning per-candidate
     * helper, in candidate order, then the container helper that peeks the prefix and dispatches
     * over them. The per-candidate helpers are named inside the container's own stem
     * ({@link Names#decodeContainerMember}), so a class hosting both a single-type decode and a
     * polymorphic one over the same record keeps the throwing helper and the standing-aside helper
     * apart.
     */
    public static List<MethodSpec> polymorphicDecodeHelpers(
            CallSiteExtraction.NodeIdDecodePolymorphicRecord poly, Names names, String outputPackage) {
        var out = new ArrayList<MethodSpec>(poly.candidates().size() + 1);
        var typeIds = new ArrayList<String>(poly.candidates().size());
        var helpers = new ArrayList<String>(poly.candidates().size());
        var records = new ArrayList<ClassName>(poly.candidates().size());
        for (var candidate : poly.candidates()) {
            String helper = names.decodeContainerMember(poly.containerName(), candidate.typeName());
            out.add(RecordDecodeFragments.decodeHelperOrNull(helper, poly.encoderClass(),
                candidate.typeId(), candidate.keyColumns(), candidate.table()));
            typeIds.add(candidate.typeId());
            helpers.add(helper);
            records.add(CatalogRefs.recordClass(candidate.table()));
        }
        out.add(RecordDecodeFragments.polymorphicDecodeHelper(
            names.decodeContainerSingular(poly.containerName()), poly.slotType().typeName(),
            poly.encoderClass(), typeIds, helpers, records,
            poly.candidates().stream()
                .map(CallSiteExtraction.PolymorphicCandidate::typeName)
                .collect(Collectors.joining(", ")),
            outputPackage));
        return out;
    }

    /** The list form of the container helper, delegating to the scalar one per element. */
    public static MethodSpec polymorphicDecodeHelperList(
            CallSiteExtraction.NodeIdDecodePolymorphicRecord poly, Names names) {
        return RecordDecodeFragments.polymorphicDecodeHelperList(
            names.decodeContainerList(poly.containerName()), poly.slotType().typeName(),
            names.decodeContainerSingular(poly.containerName()));
    }
}
