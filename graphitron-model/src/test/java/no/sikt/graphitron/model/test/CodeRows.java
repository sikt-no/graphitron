package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.classpath.CompletionData;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.CODE_CONSTRUCTION;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_SLOT;
import static no.sikt.graphitron.model.Tables.CODE_WRITE_SLOT;

/**
 * A stated signature as the reading would have written it down: the method, the type its result
 * names, what that type delivers, and the member slot it offers where it offers one.
 *
 * <p>Here rather than in either caller because a fixture states a signature in two vocabularies and
 * both owe the same rows. {@link SeededStore} states one as a position map and {@link CapturedStore}
 * takes one transcribed by the walk, and a case is about a rule only if it does not also have to be
 * about which of the two families it remembered to seed.
 *
 * <p>The peel here is the reading's, and it agrees with the view's by construction: the container
 * vocabulary is one relation, and the descent stops on the same two conditions, at a position naming
 * no container and at a container whose element position names no class. It differs in one way, and
 * deliberately: it has no depth bound, the reading descending a declaration as far as it goes where
 * a view has to unroll its joins to a fixed depth.
 */
public final class CodeRows {

    private CodeRows() {}

    /** The classes a declared type delivers through, and the argument position each delivers at. */
    private static final Map<String, Integer> CONTAINERS = Map.of(
        "java.util.List", 0, "java.util.Set", 0, "java.util.Collection", 0,
        "java.util.Optional", 0, "java.util.concurrent.CompletableFuture", 0,
        "org.jooq.Result", 0, "java.util.Map", 1);

    /** Which of them multiply, which is what makes a field backed through one a list. */
    private static final Set<String> MULTIPLIES = Set.of(
        "java.util.List", "java.util.Set", "java.util.Collection", "org.jooq.Result");

    private static final Pattern PACKAGED_NAME =
        Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");

    /**
     * Every class of a stated census as the reading would have read it.
     *
     * <p>A record's accessors are synthesised from its components rather than demanded of the
     * fixture, and that is the one place this projection adds something the input did not say. The
     * language guarantees them: a component is an accessor, so a census naming the component has
     * named the accessor, and a fixture spelling both would be spelling one fact twice. Everything
     * else is a projection of what the census already holds.
     */
    public static void writeStated(DSLContext dsl, List<CompletionData.ExternalReference> census,
                                   LocalDateTime readAt) {
        for (CompletionData.ExternalReference at : census) {
            boolean isRecord = "RECORD".equals(at.classKind());
            for (CompletionData.Method method : at.methods()) {
                var positions = positionsOf(method.returnTypeRefs());
                write(dsl, at.sourceName(), at.className(), method.name(), method.descriptor(),
                    positions, readAt);
                if (isRecord) {
                    continue;
                }
                if (method.parameters().isEmpty()) {
                    slot(dsl, at.sourceName(), at.className(), method.name(), method.descriptor(),
                        beanProperty(method.name()), "BEAN_ACCESSOR", readAt);
                    continue;
                }
                String filled = setterProperty(method);
                if (filled != null) {
                    var bound = positionsOf(method.parameters().getFirst().typeRefs());
                    String boundType = resultTypeName(bound, "()Ljava/lang/Object;");
                    type(dsl, at.sourceName(), boundType, readAt);
                    construction(dsl, at.sourceName(), at.className(), "SETTERS", "()V", readAt);
                    writeSlot(dsl, at.sourceName(), at.className(), method.name(),
                        method.descriptor(), 0, filled, boundType, readAt);
                }
            }
            if (!isRecord) {
                continue;
            }
            int position = 0;
            for (CompletionData.RecordComponent component : at.recordComponents()) {
                var positions = positionsOf(component.typeRefs());
                String descriptor = accessorDescriptor(positions);
                write(dsl, at.sourceName(), at.className(), component.name(), descriptor,
                    positions, readAt);
                slot(dsl, at.sourceName(), at.className(), component.name(), descriptor,
                    component.name(), "RECORD_COMPONENT", readAt);
                // And the write side of the same component. A record is made in one call, so the
                // component is both what you read off one and what you pass to make one, and the
                // census states it once for both.
                construction(dsl, at.sourceName(), at.className(), "POSITIONAL", "<canonical>",
                    readAt);
                writeSlot(dsl, at.sourceName(), at.className(), "<init>", "<canonical>",
                    position++, component.name(), resultTypeName(positions, descriptor), readAt);
            }
        }
    }

    /**
     * One signature: the type its result names, what that type delivers, and the method itself.
     *
     * <p>Idempotent on every row, because a fixture states one type under many methods and two
     * entries may state one class alike. What is written second is what was written first.
     */
    public static void write(DSLContext dsl, String sourceName, String className, String methodName,
                             String descriptor, Map<String, String> positions,
                             LocalDateTime readAt) {
        String typeName = resultTypeName(positions, descriptor);
        dsl.insertInto(CODE_TYPE)
            .set(CODE_TYPE.SOURCE_NAME, sourceName)
            .set(CODE_TYPE.TYPE_NAME, typeName)
            .set(CODE_TYPE.DISPLAY_NAME, withoutPackages(typeName))
            .set(CODE_TYPE.TOUCHED_AT, readAt)
            .onDuplicateKeyIgnore()
            .execute();
        String[] delivered = deliveredBy(positions);
        if (delivered != null) {
            dsl.insertInto(CODE_TYPE_ELEMENT)
                .set(CODE_TYPE_ELEMENT.SOURCE_NAME, sourceName)
                .set(CODE_TYPE_ELEMENT.TYPE_NAME, typeName)
                .set(CODE_TYPE_ELEMENT.ELEMENT_CLASS, delivered[0])
                .set(CODE_TYPE_ELEMENT.IS_MANY, Boolean.parseBoolean(delivered[1]))
                .set(CODE_TYPE_ELEMENT.TOUCHED_AT, readAt)
                .onDuplicateKeyIgnore()
                .execute();
        }
        dsl.insertInto(CODE_METHOD)
            .set(CODE_METHOD.SOURCE_NAME, sourceName)
            .set(CODE_METHOD.CLASS_NAME, className)
            .set(CODE_METHOD.METHOD_NAME, methodName)
            .set(CODE_METHOD.DESCRIPTOR, descriptor)
            .set(CODE_METHOD.IS_STATIC, false)
            .set(CODE_METHOD.RESULT_TYPE, typeName)
            .set(CODE_METHOD.TOUCHED_AT, readAt)
            .onDuplicateKeyIgnore()
            .execute();
    }

    /** One member slot, where the name is one; a null name is a method offering none. */
    public static void slot(DSLContext dsl, String sourceName, String className, String methodName,
                            String descriptor, String slotName, String origin,
                            LocalDateTime readAt) {
        if (slotName == null) {
            return;
        }
        dsl.insertInto(CODE_TYPE_SLOT)
            .set(CODE_TYPE_SLOT.SOURCE_NAME, sourceName)
            .set(CODE_TYPE_SLOT.CLASS_NAME, className)
            .set(CODE_TYPE_SLOT.METHOD_NAME, methodName)
            .set(CODE_TYPE_SLOT.DESCRIPTOR, descriptor)
            .set(CODE_TYPE_SLOT.SLOT_NAME, slotName)
            .set(CODE_TYPE_SLOT.ORIGIN, origin)
            .set(CODE_TYPE_SLOT.TOUCHED_AT, readAt)
            .onDuplicateKeyIgnore()
            .execute();
    }

    /** The property a setter fills, or null where the method is not one. */
    public static String setterProperty(CompletionData.Method method) {
        if (method.parameters().size() != 1) {
            return null;
        }
        String name = method.name();
        if (!name.startsWith("set") || name.length() <= 3) {
            return null;
        }
        char first = name.charAt(3);
        return first == Character.toLowerCase(first)
            ? null : Character.toLowerCase(first) + name.substring(4);
    }

    /** That a value of the class can be made, and how. Idempotent; the first statement wins. */
    public static void construction(DSLContext dsl, String sourceName, String className,
                                    String shape, String descriptor, LocalDateTime readAt) {
        type(dsl, sourceName, className, readAt);
        dsl.insertInto(CODE_CONSTRUCTION)
            .set(CODE_CONSTRUCTION.SOURCE_NAME, sourceName)
            .set(CODE_CONSTRUCTION.TYPE_NAME, className)
            .set(CODE_CONSTRUCTION.SHAPE, shape)
            .set(CODE_CONSTRUCTION.DESCRIPTOR, descriptor)
            .set(CODE_CONSTRUCTION.TOUCHED_AT, readAt)
            .onDuplicateKeyIgnore()
            .execute();
    }

    /** One member that goes in when a value of the class is made. */
    public static void writeSlot(DSLContext dsl, String sourceName, String className,
                                 String methodName, String descriptor, int position,
                                 String slotName, String slotType, LocalDateTime readAt) {
        type(dsl, sourceName, slotType, readAt);
        dsl.insertInto(CODE_WRITE_SLOT)
            .set(CODE_WRITE_SLOT.SOURCE_NAME, sourceName)
            .set(CODE_WRITE_SLOT.TYPE_NAME, className)
            .set(CODE_WRITE_SLOT.METHOD_NAME, methodName)
            .set(CODE_WRITE_SLOT.DESCRIPTOR, descriptor)
            .set(CODE_WRITE_SLOT.POSITION, position)
            .set(CODE_WRITE_SLOT.SLOT_NAME, slotName)
            .set(CODE_WRITE_SLOT.SLOT_TYPE, slotType)
            .set(CODE_WRITE_SLOT.TOUCHED_AT, readAt)
            .onDuplicateKeyIgnore()
            .execute();
    }

    /** One type, idempotently, for a caller that is about to key to it. */
    public static void type(DSLContext dsl, String sourceName, String typeName,
                            LocalDateTime readAt) {
        dsl.insertInto(CODE_TYPE)
            .set(CODE_TYPE.SOURCE_NAME, sourceName)
            .set(CODE_TYPE.TYPE_NAME, typeName)
            .set(CODE_TYPE.DISPLAY_NAME, withoutPackages(typeName))
            .set(CODE_TYPE.TOUCHED_AT, readAt)
            .onDuplicateKeyIgnore()
            .execute();
    }

    /** The property a getter offers, or null where the name is not one. */
    public static String beanProperty(String methodName) {
        for (String prefix : List.of("get", "is")) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                char first = methodName.charAt(prefix.length());
                if (first != Character.toLowerCase(first)) {
                    return Character.toLowerCase(first) + methodName.substring(prefix.length() + 1);
                }
            }
        }
        return null;
    }

    /**
     * The class the declared type arrives at once its containers are peeled, and whether anything
     * peeled on the way multiplies; null where the type names no class at its root, which is how a
     * primitive, an array and a void result are all stated.
     */
    private static String[] deliveredBy(Map<String, String> positions) {
        String path = "";
        boolean many = false;
        while (true) {
            String named = positions.get(path);
            if (named == null) {
                return null;
            }
            Integer elementIndex = CONTAINERS.get(named);
            if (elementIndex == null) {
                return new String[] {named, String.valueOf(many)};
            }
            String next = path.isEmpty() ? String.valueOf(elementIndex) : path + "." + elementIndex;
            if (!positions.containsKey(next)) {
                return new String[] {named, String.valueOf(many)};
            }
            many = many || MULTIPLIES.contains(named);
            path = next;
        }
    }

    /**
     * The result type as the source wrote it: the position map rendered back into one name, or the
     * descriptor's own return where the map names no class at all. Both spellings are the reading's,
     * a type being keyed by how it was written with its arguments and packages kept.
     */
    private static String resultTypeName(Map<String, String> positions, String descriptor) {
        return positions.containsKey("") ? renderPosition(positions, "")
            : descriptorReturn(descriptor);
    }

    private static String renderPosition(Map<String, String> positions, String path) {
        String named = positions.get(path);
        var arguments = new ArrayList<String>();
        for (int i = 0; ; i++) {
            String child = path.isEmpty() ? String.valueOf(i) : path + "." + i;
            if (!positions.containsKey(child)) {
                break;
            }
            arguments.add(renderPosition(positions, child));
        }
        return arguments.isEmpty() ? named : named + "<" + String.join(", ", arguments) + ">";
    }

    /** What a descriptor says the method hands back, for the results no position map describes. */
    private static String descriptorReturn(String descriptor) {
        String returned = descriptor.substring(descriptor.indexOf(')') + 1);
        int dimensions = 0;
        while (returned.startsWith("[")) {
            dimensions++;
            returned = returned.substring(1);
        }
        String named = switch (returned.charAt(0)) {
            case 'V' -> "void";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'Z' -> "boolean";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'S' -> "short";
            case 'B' -> "byte";
            case 'C' -> "char";
            default -> returned.substring(1, returned.length() - 1).replace('/', '.');
        };
        return named + "[]".repeat(dimensions);
    }

    /**
     * The same type with every package dropped, which is how the reading renders one.
     *
     * <p>The replacement is quoted because a nested class carries a dollar in its binary name and a
     * replacement string is where a dollar means a capture group.
     */
    private static String withoutPackages(String typeName) {
        return PACKAGED_NAME.matcher(typeName).replaceAll(match -> {
            String named = match.group();
            return Matcher.quoteReplacement(named.substring(named.lastIndexOf('.') + 1));
        });
    }

    /** A census transcription's type references as the position map every rule here reads. */
    private static Map<String, String> positionsOf(List<CompletionData.TypeRef> refs) {
        var positions = new java.util.LinkedHashMap<String, String>();
        for (CompletionData.TypeRef ref : refs) {
            positions.putIfAbsent(ref.path(), ref.referencedClass());
        }
        return positions;
    }

    /**
     * The descriptor of a record component's accessor, derived from the erasure at the component's
     * root. A synthesis on the terms {@link #writeStated} states: the accessor exists because the
     * component does, so its key is derivable too, and a component naming no class at its root is
     * given the one shape a fixture cannot mean anything else by.
     */
    private static String accessorDescriptor(Map<String, String> positions) {
        String root = positions.get("");
        return root == null ? "()Ljava/lang/Object;" : "()L" + root.replace('.', '/') + ";";
    }
}
