package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import static no.sikt.graphitron.model.Tables.JVM_CLASS;

/**
 * Whether the classpath census holds a class under the FQN an author wrote: one read of
 * {@code jvm_class}, which is the guard every arm resolving a consumer's class name stands behind.
 *
 * <p>The census's scope is the consumer's module, its reactor siblings, and the dependencies the
 * module declares itself; a class reachable only through a transitive dependency has no row here
 * even though the codegen loader would resolve it. That is the rule, not a gap: the build rejects
 * a schema naming such a class through its nameability check, so what this read cannot promise
 * about codegen resolution the build enforces by that route instead. Every surface reading this
 * answer (diagnostics, goto-definition) therefore speaks about the census's scope, never about
 * what will or will not load.
 *
 * <p>Three outcomes rather than a boolean, because the surfaces need to tell two kinds of no
 * apart. A census carrying classes but not this name is a name the author cannot use as written,
 * whether it is a typo or a class in an undeclared jar; the store holds no fact separating those,
 * so the surfaces state the scope and leave the cause open. A census carrying nothing at all is a
 * consumer who has not compiled yet, and calling every name in their schema unknown while they
 * wait for a build is noise. The distinction was a guard clause reading {@code isEmpty()} before
 * a lookup on each surface that needed it, in the order that made the guard work; here it is one
 * answer, so no reader can hold the two questions in the wrong order.
 */
public final class ClasspathClasses {

    private ClasspathClasses() {}

    /**
     * What the census says about one class name. An enum rather than a sealed hierarchy: no arm
     * carries anything beyond which arm it is, the class's own facts being what
     * {@link ClasspathMethods} and the {@code java_} source family answer.
     */
    public enum Presence {

        /** The census holds this class, so the name resolves. */
        KNOWN,

        /** The census holds classes, and none of them is this one. */
        UNKNOWN,

        /** The census holds no class at all: nothing this graph reads has been compiled yet. */
        NO_CENSUS
    }

    /**
     * The census's answer for {@code classFqn}, matched exactly. A class name is a Java identifier
     * and the consumer writes it the way their source declares it, so this is the one catalog-facing
     * read here that is not case-insensitive.
     */
    public static Presence presenceOf(StoreHandle store, String classFqn) {
        if (store.dsl().fetchExists(JVM_CLASS,
                store.reads(JVM_CLASS.SOURCE_NAME).and(JVM_CLASS.CLASS_NAME.eq(classFqn)))) {
            return Presence.KNOWN;
        }
        return store.dsl().fetchExists(JVM_CLASS, store.reads(JVM_CLASS.SOURCE_NAME))
            ? Presence.UNKNOWN
            : Presence.NO_CENSUS;
    }
}
