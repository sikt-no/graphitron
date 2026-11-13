package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Emits how an input object is made into a class, from one of {@link BuildQuery}'s constructions:
 * one helper per class, taking the input object's map as graphql-java hands it over and answering
 * the class's value.
 *
 * <pre>{@code
 * @SuppressWarnings("unchecked")
 * private static FilmBundleDraft buildDrafts(Map<?, ?> in) {
 *     return new FilmBundleDraft(
 *         (String) in.get("name"),
 *         in.get("limits") instanceof Map<?, ?> group1 ? (Integer) group1.get("capacity") : null,
 *         in.get("films") instanceof List<?> list1
 *             ? list1.stream().map(e -> e instanceof Map<?, ?> m ? buildDrafts_films(m) : null).toList() : null);
 * }
 * }</pre>
 *
 * <p>A record is made by its canonical constructor, every component in header order and a member no
 * field fills taking its default. Anything else is made empty and filled by its setters, a setter
 * called only for a field the client sent, so a member it did not send keeps the value the class
 * gives it. A field's value is read off its map, through a grouping's map where it is grouped, and is
 * {@code null} wherever a map on the way is absent: a scalar or an enum's string as the member takes
 * it, an enum through {@code valueOf} into a Java enum, and a nested input object, or a list of
 * them, through the helper that makes the member's class.
 */
public final class BuildEmitter {

    private BuildEmitter() {}

    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName STRING = ClassName.get("java.lang", "String");
    private static final TypeName ANY_MAP = ParameterizedTypeName.get(MAP,
        WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class));

    /**
     * The helpers that make {@code build}'s class, the first named {@code name} and taking the input
     * object's map, the rest making the classes of its nested input objects.
     */
    public static List<MethodSpec> helpers(BuildQuery.Build build, String name, NodeIdDecodeEmitter.Runtime runtime) {
        var out = new ArrayList<MethodSpec>();
        helper(build, name, runtime, out);
        return out;
    }

    /**
     * The helper that makes {@code build}'s jOOQ record from an input object's map: each column set
     * from the first of its fields the client sent, a value through the column's own conversion by
     * {@code fromArray} and an explicit null as null, a column no field was sent for left untouched, so
     * the record's dirty flags are what the client wrote. Every value is loaded before any explicit null
     * is set: each {@code fromArray} clears the dirty flag of a null already on a {@code NOT NULL}
     * column, so a null written first would not survive a later load. Then the node ids decoded
     * into the same record, after every plain write so their agreement check sees it, a conflict
     * naming every input field writing the column; and last a node id sent as an explicit null, which
     * nulls the columns it would have written that no other field wrote.
     */
    public static MethodSpec recordHelper(BuildQuery.RecordBuild build, String name,
                                          NodeIdDecodeEmitter.Runtime runtime) {
        var type = SessionIdentityRows.classOf(build.recordClass());
        var table = SessionIdentityRows.classOf(build.tablesClass());
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(type)
            .addParameter(ANY_MAP, "in")
            .addStatement("$T out = new $T()", type, type);
        // The values first, then the explicit nulls, each column from the first of its fields sent. A
        // field under a grouping is read through it, and an absent or null grouping sends nothing.
        var groups = new int[] {0};
        for (boolean nulls : new boolean[] {false, true}) {
            for (var column : build.columns()) {
                var field = CodeBlock.of("$T.$L.$L", table, build.tableField(), column.columnJavaName());
                boolean first = true;
                for (var path : column.paths()) {
                    var reach = reach(path, groups);
                    var sent = path.getLast();
                    var condition = CodeBlock.of("$L$L.containsKey($S)", reach.condition(), reach.map(), sent);
                    if (first) {
                        method.beginControlFlow("if ($L)", condition);
                        first = false;
                    } else {
                        method.nextControlFlow("else if ($L)", condition);
                    }
                    if (nulls) {
                        method.beginControlFlow("if ($L.get($S) == null)", reach.map(), sent)
                            .addStatement("out.set($L, null)", field)
                            .endControlFlow();
                    } else {
                        method.beginControlFlow("if ($L.get($S) != null)", reach.map(), sent)
                            .addStatement("out.fromArray(new $T[] {$L.get($S)}, $L)", Object.class, reach.map(), sent,
                                field)
                            .endControlFlow();
                    }
                }
                method.endControlFlow();
            }
        }
        var counter = new int[] {0};
        for (var member : build.decodes()) {
            var id = "id" + (++counter[0]);
            var reach = reach(member.path(), groups);
            method.beginControlFlow("if ($L$L.get($S) instanceof $T $L)", reach.condition(), reach.map(),
                member.path().getLast(), STRING, id);
            NodeIdDecodeEmitter.into(method, CodeBlock.of("out"), member.decode().orElseThrow(), id,
                conflictLabel(build, member), runtime);
            method.endControlFlow();
        }
        for (var member : build.decodes()) {
            var decode = member.decode().orElseThrow();
            var sent = member.path().getLast();
            var reach = reach(member.path(), groups);
            method.beginControlFlow("if ($L$L.containsKey($S) && $L.get($S) == null)", reach.condition(), reach.map(),
                sent, reach.map(), sent);
            for (var column : decode.columnJavaNames()) {
                var field = CodeBlock.of("$T.$L.$L", SessionIdentityRows.classOf(decode.tablesClass()),
                    decode.tableField(), column);
                method.beginControlFlow("if (!out.touched($L))", field)
                    .addStatement("out.set($L, null)", field)
                    .endControlFlow();
            }
            method.endControlFlow();
        }
        return method.addStatement("return out").build();
    }

    /** Where a path's last name is read: the map it is in, and what must hold to reach that map. */
    private record Reach(CodeBlock condition, String map) {}

    /**
     * Reaches the map a path's last name is in, through each grouping before it: {@code in} at the
     * top, else a fresh pattern variable bound by an {@code instanceof} chain the condition ends
     * ready for {@code &&}.
     */
    private static Reach reach(List<String> path, int[] groups) {
        var condition = CodeBlock.builder();
        var map = "in";
        for (var group : path.subList(0, path.size() - 1)) {
            var next = "group" + (++groups[0]);
            condition.add("$L.get($S) instanceof $T $L && ", map, group, ANY_MAP, next);
            map = next;
        }
        return new Reach(condition.build(), map);
    }

    /**
     * How a decode's disagreement with another writer of a column names them: every input field
     * writing a column the decode writes, the plain ones first, then the node ids in order, as
     * {@code input fields 'binIdText', 'binId'}.
     */
    private static String conflictLabel(BuildQuery.RecordBuild build, BuildQuery.Member member) {
        var columns = member.decode().orElseThrow().columnJavaNames();
        var writers = new java.util.LinkedHashSet<String>();
        for (var column : build.columns()) {
            if (columns.contains(column.columnJavaName())) {
                column.paths().forEach(path -> writers.add(String.join(".", path)));
            }
        }
        for (var other : build.decodes()) {
            if (other.decode().orElseThrow().columnJavaNames().stream().anyMatch(columns::contains)) {
                writers.add(String.join(".", other.path()));
            }
        }
        return "input fields " + writers.stream().map(w -> "'" + w + "'")
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private static void helper(BuildQuery.Build build, String name, NodeIdDecodeEmitter.Runtime runtime,
                               List<MethodSpec> out) {
        var type = SessionIdentityRows.classOf(build.className());
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked").build())
            .returns(type)
            .addParameter(ANY_MAP, "in");
        out.add(null);
        int index = out.size() - 1;
        var counter = new int[] {0};
        // The node ids, one record per member however many fields decode into it: declared and
        // filled first at POSITIONAL, where they are constructor arguments, and after every plain
        // setter at SETTERS, so the decode's agreement check sees each plain write. A list of node ids
        // is a value of its own, each id decoded into a record of its own.
        var decoded = new java.util.LinkedHashMap<String, List<BuildQuery.Member>>();
        for (var member : build.members()) {
            if (member.decode().isPresent() && !member.list()) {
                decoded.computeIfAbsent(member.slot().slotName(), k -> new ArrayList<>()).add(member);
            }
        }
        if ("POSITIONAL".equals(build.shape())) {
            var records = decodes(method, decoded, runtime, counter);
            var arguments = new ArrayList<CodeBlock>();
            for (var slot : build.slots()) {
                var member = build.members().stream()
                    .filter(m -> m.slot().position() == slot.position()).findFirst();
                arguments.add(member.isEmpty() ? defaultOf(SessionIdentityRows.typeOf(slot.slotType()))
                    : member.get().decode().isPresent() && !member.get().list()
                        ? CodeBlock.of("$L", records.get(slot.slotName()))
                    : value(member.get(), name, runtime, counter, out));
            }
            method.addStatement("return new $T($L)", type, CodeBlock.join(arguments, ", "));
        } else {
            method.addStatement("$T out = new $T()", type, type);
            for (var member : build.members()) {
                if (member.decode().isPresent() && !member.list()) {
                    continue;
                }
                var sent = sent(member.path(), counter);
                method.beginControlFlow("if ($L)", sent)
                    .addStatement("out.$L($L)", member.slot().methodName(), value(member, name, runtime, counter, out))
                    .endControlFlow();
            }
            var records = decodes(method, decoded, runtime, counter);
            for (var group : decoded.values()) {
                var anySent = CodeBlock.join(group.stream().map(m -> sent(m.path(), counter)).toList(), " || ");
                method.beginControlFlow("if ($L)", anySent)
                    .addStatement("out.$L($L)", group.getFirst().slot().methodName(),
                        records.get(group.getFirst().slot().slotName()))
                    .endControlFlow();
            }
            method.addStatement("return out");
        }
        out.set(index, method.build());
    }

    /**
     * Each node id member's record, decoded from every field naming the member into one instance: a
     * record made only when an id arrives, so a member no id fills stays null, and an id
     * {@code decodeInto} refuses refused for the input field it came from. Answers each member's local.
     */
    private static java.util.Map<String, String> decodes(MethodSpec.Builder method,
                                                         java.util.Map<String, List<BuildQuery.Member>> decoded,
                                                         NodeIdDecodeEmitter.Runtime runtime, int[] counter) {
        var locals = new java.util.HashMap<String, String>();
        for (var entry : decoded.entrySet()) {
            var local = "decoded" + (++counter[0]);
            var recordType = SessionIdentityRows.classOf(entry.getValue().getFirst().decode().orElseThrow().recordClass());
            method.addStatement("$T $L = null", recordType, local);
            for (var member : entry.getValue()) {
                var maps = maps(member.path(), counter);
                var read = CodeBlock.of("$L.get($S)", maps.last(), member.path().getLast());
                var id = "id" + (++counter[0]);
                method.beginControlFlow(maps.guard().isEmpty() ? "if ($L instanceof $T $L)" : "if ($L && $L instanceof $T $L)",
                        maps.guard().isEmpty() ? new Object[] {read, STRING, id} : new Object[] {maps.guard(), read, STRING, id})
                    .addStatement("if ($L == null) $L = new $T()", local, local, recordType);
                NodeIdDecodeEmitter.into(method, CodeBlock.of("$L", local), member.decode().orElseThrow(), id,
                    String.join(".", member.path()), runtime);
                method.endControlFlow();
            }
            locals.put(entry.getKey(), local);
        }
        return locals;
    }

    /** Whether the client sent the field: every map on the way is there, and the last holds its name. */
    private static CodeBlock sent(List<String> path, int[] counter) {
        var maps = maps(path, counter);
        var contains = CodeBlock.of("$L.containsKey($S)", maps.last(), path.getLast());
        return maps.guard().isEmpty() ? contains : CodeBlock.of("$L && $L", maps.guard(), contains);
    }

    /** A member's value, read off its map and made into what the member takes. */
    private static CodeBlock value(BuildQuery.Member member, String name, NodeIdDecodeEmitter.Runtime runtime,
                                   int[] counter,
                                   List<MethodSpec> out) {
        var slotType = SessionIdentityRows.typeOf(member.slot().slotType());
        var maps = maps(member.path(), counter);
        var read = CodeBlock.of("$L.get($S)", maps.last(), member.path().getLast());
        CodeBlock made;
        if (member.decoded().isPresent()) {
            // Decoded as a parameter is: a list of node ids each into a record of its own.
            var helperName = name + "_" + member.slot().slotName();
            out.add(NodeIdDecodeEmitter.parameterHelper(helperName, slotType, member.decoded().get(), runtime));
            made = CodeBlock.of("$L($L)", helperName, read);
        } else if (member.nested().isPresent()) {
            var helperName = name + "_" + member.slot().slotName();
            helper(member.nested().get(), helperName, runtime, out);
            made = member.list()
                ? listOf(read, CodeBlock.of("e instanceof $T m ? $L(m) : null", ANY_MAP, helperName), counter)
                : CodeBlock.of("$L instanceof $T map$L ? $L(map$L) : null", read, ANY_MAP, ++counter[0], helperName, counter[0]);
        } else if ("ENUM".equals(member.typeKind()) && !STRING.equals(elementOf(slotType))) {
            var element = elementOf(slotType);
            made = member.list()
                ? listOf(read, CodeBlock.of("e instanceof $T s ? $T.valueOf(s) : null", STRING, element), counter)
                : CodeBlock.of("$L instanceof $T text$L ? $T.valueOf(text$L) : null", read, STRING, ++counter[0], element, counter[0]);
        } else if (slotType.isPrimitive()) {
            made = CodeBlock.of("$L instanceof $T number$L ? number$L : $L", read, slotType.box(), ++counter[0],
                counter[0], defaultOf(slotType));
        } else {
            made = CodeBlock.of("($T) $L", slotType, read);
        }
        return maps.guard().isEmpty() ? made : CodeBlock.of("($L ? $L : null)", maps.guard(), made);
    }

    /** A list read off a map, each element mapped, {@code null} where the map holds no list. */
    private static CodeBlock listOf(CodeBlock read, CodeBlock element, int[] counter) {
        int n = ++counter[0];
        return CodeBlock.of("$L instanceof $T<?> list$L ? list$L.stream().map(e -> $L).toList() : null",
            read, LIST, n, n, element);
    }

    /**
     * The maps a grouped field is read through: a condition binding each grouping's map, empty where
     * the field is not grouped, and the name of the map the field itself is in.
     */
    private record Maps(CodeBlock guard, String last) {}

    private static Maps maps(List<String> path, int[] counter) {
        var conditions = new ArrayList<CodeBlock>();
        String at = "in";
        for (int i = 0; i < path.size() - 1; i++) {
            var group = "group" + (++counter[0]);
            conditions.add(CodeBlock.of("$L.get($S) instanceof $T $L", at, path.get(i), ANY_MAP, group));
            at = group;
        }
        return new Maps(CodeBlock.join(conditions, " && "), at);
    }

    /** A type's element: the type a {@code List} holds, or the type itself. */
    private static TypeName elementOf(TypeName type) {
        return type instanceof ParameterizedTypeName list && LIST.equals(list.rawType())
            ? list.typeArguments().getFirst() : type;
    }

    /** What a member no field fills is made with: zero or false for a primitive, else null. */
    private static CodeBlock defaultOf(TypeName type) {
        if (TypeName.BOOLEAN.equals(type)) return CodeBlock.of("false");
        if (TypeName.CHAR.equals(type)) return CodeBlock.of("'\\0'");
        if (TypeName.INT.equals(type)) return CodeBlock.of("0");
        if (TypeName.LONG.equals(type)) return CodeBlock.of("0L");
        if (TypeName.FLOAT.equals(type)) return CodeBlock.of("0F");
        if (TypeName.DOUBLE.equals(type)) return CodeBlock.of("0D");
        if (type.isPrimitive()) return CodeBlock.of("($T) 0", type);
        return CodeBlock.of("null");
    }

}
