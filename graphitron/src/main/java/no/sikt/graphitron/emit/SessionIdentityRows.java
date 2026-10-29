package no.sikt.graphitron.emit;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.CODE_CLASS;
import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SESSION_HANDLE_MEMBER;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_HANDLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_SUPPLIED;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;

/**
 * Session identity as the store states it: whether the graph supplies identity per request, the
 * handle class the mount returns when one is named, and the members that class offers, each with
 * the accessor to call and what it yields. The generators that emit the session types, the
 * connection hook and the guarded member reads take this value, so none of them re-asks the
 * question of the configuration or the classpath.
 *
 * <p>Two statements at two grains: the graph's row with its handle, and the handle's members. The
 * members are the unambiguous ones. A property two accessors offer has no single thing to read, and
 * the binding that names it is a stored defect that fails the build before anything is emitted, so
 * it never reaches an emitter; it is left out here so an emitter cannot choose between them.
 *
 * <p>Holds no handle on the store. A yield or a handle is decoded once, here, from the spelling the
 * store keeps into the type the emitters write.
 *
 * @param supplied whether the graph supplies session identity per request
 * @param handle   the handle class and its members, when the mount returns one the classpath holds
 */
public record SessionIdentityRows(boolean supplied, Optional<Handle> handle) {

    /**
     * The {@code Configuration.data()} key the generated runtime writes the mount's handle under,
     * and the guarded member read reads. One constant so the writer (the connection runtime's
     * acquisition) and the reader cannot drift; the key is scoped per pinned connection because
     * {@code data()} is jOOQ's per-{@code Configuration} map.
     */
    public static final String HANDLE_DATA_KEY = "no.sikt.graphitron.session.handle";

    /**
     * The {@code GraphQLContext} key the owned factory writes the request's {@code SessionState}
     * under and the execution instrumentation reads it from.
     */
    public static final String CONTEXT_KEY = "no.sikt.graphitron.session.state";

    public SessionIdentityRows {
        Objects.requireNonNull(handle, "handle");
        if (handle.isPresent() && !supplied) {
            throw new IllegalArgumentException("a handle without the session switch is not a state");
        }
    }

    /** One member of the handle: the name an author writes, the accessor to call, what it yields. */
    public record Member(String name, String accessor, TypeName type) {}

    /** The handle class as a type to emit against, with its unambiguous members by name. */
    public record Handle(TypeName type, List<Member> members) {
        public Handle {
            members = List.copyOf(members);
        }

        /** The member an author named, if the handle offers it. */
        public Optional<Member> member(String name) {
            return members.stream().filter(m -> m.name().equals(name)).findFirst();
        }
    }

    /** No identity at all: what a generator run without a store, or a build with no block, reads. */
    public static SessionIdentityRows empty() {
        return new SessionIdentityRows(false, Optional.empty());
    }

    /** Identity supplied per request with no handle, for drivers that build the value directly. */
    public static SessionIdentityRows handleLess() {
        return new SessionIdentityRows(true, Optional.empty());
    }

    /** Identity supplied per request with {@code handle}, for drivers that build the value directly. */
    public static SessionIdentityRows handled(Handle handle) {
        return new SessionIdentityRows(true, Optional.of(handle));
    }

    /**
     * The one membership fact the emit plan, the connection runtime and the dev executor share:
     * whether a hook unit, the session types and the mount call are emitted at all.
     */
    public boolean emitsHookImplementation() {
        return supplied;
    }

    /** The handle's type, when the mount returns one. */
    public Optional<TypeName> handleType() {
        return handle.map(Handle::type);
    }

    /** The graph's session identity, from the store. */
    public static SessionIdentityRows read(StoreHandle store) {
        var supplied = store.dsl().fetchExists(STORE_GRAPH_SESSION_SUPPLIED,
            STORE_GRAPH_SESSION_SUPPLIED.GRAPH_NAME.eq(store.graphName()));
        if (!supplied) {
            return empty();
        }
        var h = STORE_GRAPH_SESSION_HANDLE;
        var s = STORE_GRAPH_SOURCE;
        var c = CODE_CLASS;
        // The handle counts only where the classpath holds it; a configured class that nothing
        // holds is a build error raised elsewhere, and nothing is emitted against it.
        var handleClass = store.dsl().select(h.HANDLE_CLASS)
            .from(h)
            .where(h.GRAPH_NAME.eq(store.graphName()),
                org.jooq.impl.DSL.exists(org.jooq.impl.DSL.selectOne().from(s)
                    .join(c).on(c.SOURCE_NAME.eq(s.SOURCE_NAME), c.CLASS_NAME.eq(h.HANDLE_CLASS))
                    .where(s.GRAPH_NAME.eq(h.GRAPH_NAME))))
            .fetchOptional(h.HANDLE_CLASS);
        if (handleClass.isEmpty()) {
            return handleLess();
        }
        var m = GRAPHITRON_SESSION_HANDLE_MEMBER;
        var slot = CODE_READ_SLOT;
        record Row(String name, String accessor, String type) {}
        var byName = new TreeMap<String, List<Row>>();
        store.dsl()
            .select(m.MEMBER_NAME, m.METHOD_NAME, slot.SLOT_TYPE)
            .from(m)
            .join(slot).on(slot.SOURCE_NAME.eq(m.SOURCE_NAME), slot.CLASS_NAME.eq(m.CLASS_NAME),
                slot.METHOD_NAME.eq(m.METHOD_NAME), slot.DESCRIPTOR.eq(m.DESCRIPTOR))
            .where(m.GRAPH_NAME.eq(store.graphName()))
            .forEach(r -> byName.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                .add(new Row(r.value1(), r.value2(), r.value3())));
        var members = new ArrayList<Member>();
        for (Map.Entry<String, List<Row>> e : byName.entrySet()) {
            if (e.getValue().size() == 1) {
                var row = e.getValue().get(0);
                members.add(new Member(row.name(), row.accessor(), typeOf(row.type())));
            }
        }
        return handled(new Handle(classOf(handleClass.get()), members));
    }

    /** A class named by its binary name, as the type to emit against. */
    static TypeName classOf(String binaryName) {
        return parse(binaryName.replace('$', '.'));
    }

    /** What the store spells a member's yield as, decoded into the type the emitters write. */
    static TypeName typeOf(String spelling) {
        return parse(spelling);
    }

    // ---- the spelling decode: primitives, qualified names, generics, wildcards, arrays ----

    private static TypeName parse(String spelling) {
        var p = new Parser(spelling.strip());
        var type = p.type();
        if (!p.done()) {
            throw new IllegalArgumentException("unparsed tail of type spelling '" + spelling + "'");
        }
        return type;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean done() {
            return i >= s.length();
        }

        TypeName type() {
            skipSpaces();
            if (s.startsWith("?", i)) {
                i++;
                skipSpaces();
                if (s.startsWith("extends ", i)) {
                    i += "extends ".length();
                    return WildcardTypeName.subtypeOf(type());
                }
                if (s.startsWith("super ", i)) {
                    i += "super ".length();
                    return WildcardTypeName.supertypeOf(type());
                }
                return WildcardTypeName.subtypeOf(Object.class);
            }
            int start = i;
            while (i < s.length() && "<>,[ ".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String name = s.substring(start, i);
            TypeName base = primitive(name);
            if (base == null) {
                ClassName raw = className(name);
                if (i < s.length() && s.charAt(i) == '<') {
                    i++;
                    var args = new ArrayList<TypeName>();
                    do {
                        args.add(type());
                        skipSpaces();
                    } while (consume(','));
                    if (!consume('>')) {
                        throw new IllegalArgumentException("unclosed type arguments in '" + s + "'");
                    }
                    base = ParameterizedTypeName.get(raw, args.toArray(new TypeName[0]));
                } else {
                    base = raw;
                }
            }
            while (s.startsWith("[]", i)) {
                i += 2;
                base = ArrayTypeName.of(base);
            }
            return base;
        }

        private boolean consume(char c) {
            skipSpaces();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                skipSpaces();
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
        }

        private static TypeName primitive(String name) {
            return switch (name) {
                case "boolean" -> TypeName.BOOLEAN;
                case "byte" -> TypeName.BYTE;
                case "short" -> TypeName.SHORT;
                case "int" -> TypeName.INT;
                case "long" -> TypeName.LONG;
                case "char" -> TypeName.CHAR;
                case "float" -> TypeName.FLOAT;
                case "double" -> TypeName.DOUBLE;
                case "void" -> TypeName.VOID;
                default -> null;
            };
        }

        private static ClassName className(String qualified) {
            try {
                return ClassName.bestGuess(qualified);
            } catch (IllegalArgumentException lowerCaseClass) {
                int dot = qualified.lastIndexOf('.');
                return dot < 0 ? ClassName.get("", qualified)
                    : ClassName.get(qualified.substring(0, dot), qualified.substring(dot + 1));
            }
        }
    }
}
