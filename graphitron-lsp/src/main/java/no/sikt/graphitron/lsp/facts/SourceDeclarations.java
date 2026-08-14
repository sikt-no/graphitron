package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FIELD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;

/**
 * The doc comment the java-source family holds for a declaration a surface has already named. The
 * one reader of that family, so the tie-breaks below are stated once rather than per surface.
 *
 * <p>Unscoped by graph, deliberately: the family partitions on the source file, not on a source
 * membership, so a doc comment is a fact about a file every graph in the session shares. The
 * {@link StoreHandle} is here for its query surface, not for its scope.
 *
 * <p>Two shapes, because two kinds of caller need it. A query that already carries a class name on
 * its own side (a catalog table's FQN, a classpath census row) takes the correlated
 * {@code javadocOf} field and pays for one query; a caller holding plain names takes the direct
 * lookup. Both resolve the same way: absence is a missing row and reads as empty text, and where a
 * malformed source tree declares one name in two files the first in file order wins, which is
 * arbitrary but stated and stable across re-parses.
 */
public final class SourceDeclarations {

    private SourceDeclarations() {}

    /**
     * The doc comment of the class named by {@code className} on the enclosing query's own side, as a
     * correlated scalar select. A select rather than a join because
     * {@code java_class_declaration} is keyed on {@code (file, class_name)}: one name declared in two
     * files is two rows, which a join would multiply the enclosing row by.
     */
    public static Field<String> classJavadocOf(Field<String> className) {
        return field(select(JAVA_CLASS_DECLARATION.JAVADOC)
            .from(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.CLASS_NAME.eq(className))
            .and(JAVA_CLASS_DECLARATION.JAVADOC.isNotNull())
            .orderBy(JAVA_CLASS_DECLARATION.FILE)
            .limit(1));
    }

    /**
     * The doc comment of the field named by {@code fieldName} on the class named by
     * {@code className}, both on the enclosing query's own side. Correlated for the same reason
     * {@link #classJavadocOf} is.
     */
    public static Field<String> fieldJavadocOf(Field<String> className, Field<String> fieldName) {
        return field(select(JAVA_FIELD_DECLARATION.JAVADOC)
            .from(JAVA_FIELD_DECLARATION)
            .where(JAVA_FIELD_DECLARATION.CLASS_NAME.eq(className))
            .and(JAVA_FIELD_DECLARATION.FIELD_NAME.eq(fieldName))
            .and(JAVA_FIELD_DECLARATION.JAVADOC.isNotNull())
            .orderBy(JAVA_FIELD_DECLARATION.FILE)
            .limit(1));
    }

    /** The doc comment on the named class, or empty where no parsed source declares one. */
    public static String classJavadoc(StoreHandle store, String classFqn) {
        if (classFqn == null) return "";
        return text(store.dsl()
            .select(JAVA_CLASS_DECLARATION.JAVADOC)
            .from(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.CLASS_NAME.eq(classFqn))
            .and(JAVA_CLASS_DECLARATION.JAVADOC.isNotNull())
            .orderBy(JAVA_CLASS_DECLARATION.FILE)
            .limit(1)
            .fetchOne(JAVA_CLASS_DECLARATION.JAVADOC));
    }

    /**
     * The doc comment on the named field of the named class, or empty where none is parsed. A record
     * component is a field at this grain, and so is a generated table class's column constant, which
     * is what makes both reachable through one lookup.
     */
    public static String fieldJavadoc(StoreHandle store, String classFqn, String fieldName) {
        if (classFqn == null || fieldName == null) return "";
        return text(store.dsl()
            .select(JAVA_FIELD_DECLARATION.JAVADOC)
            .from(JAVA_FIELD_DECLARATION)
            .where(JAVA_FIELD_DECLARATION.CLASS_NAME.eq(classFqn))
            .and(JAVA_FIELD_DECLARATION.FIELD_NAME.eq(fieldName))
            .and(JAVA_FIELD_DECLARATION.JAVADOC.isNotNull())
            .orderBy(JAVA_FIELD_DECLARATION.FILE)
            .limit(1)
            .fetchOne(JAVA_FIELD_DECLARATION.JAVADOC));
    }

    /**
     * The doc comment of the overload of {@code methodName} that declares {@code arity} parameters,
     * falling back to any declaration of the name when no overload declares that many. Two tiers
     * because the arity a consumer holds is itself a resolution: SDL names a method by name alone, so
     * an arity is whatever the census offered first, and declining on it would lose a comment the
     * source plainly carries.
     */
    public static String methodJavadoc(StoreHandle store, String classFqn, String methodName, int arity) {
        var byArity = methodJavadocByArity(store, classFqn, methodName);
        if (byArity.isEmpty()) return "";
        String exact = byArity.get(arity);
        return exact != null ? exact : byArity.firstEntry().getValue();
    }

    /**
     * Doc comments for one method name keyed by the arity the source declares, in (file,
     * declaration) order. Arity is what the two populations can be joined on: a parse reads
     * unqualified parameter types as written where the classfile carries erased ones, so
     * {@code java_method_declaration} counts parameters and {@code jvm_method} spells a descriptor,
     * and the count is their only common ground. Two same-arity overloads therefore share one
     * comment, the first in that order.
     */
    public static SequencedMap<Integer, String> methodJavadocByArity(
        StoreHandle store, String classFqn, String methodName
    ) {
        var byArity = new LinkedHashMap<Integer, String>();
        if (classFqn == null || methodName == null) return byArity;
        var rows = store.dsl()
            .select(JAVA_METHOD_DECLARATION.PARAMETER_COUNT, JAVA_METHOD_DECLARATION.JAVADOC)
            .from(JAVA_METHOD_DECLARATION)
            .where(JAVA_METHOD_DECLARATION.CLASS_NAME.eq(classFqn))
            .and(JAVA_METHOD_DECLARATION.METHOD_NAME.eq(methodName))
            .and(JAVA_METHOD_DECLARATION.JAVADOC.isNotNull())
            .orderBy(JAVA_METHOD_DECLARATION.FILE, JAVA_METHOD_DECLARATION.ORDINAL)
            .fetch();
        for (var row : rows) {
            byArity.putIfAbsent(row.value1(), row.value2());
        }
        return byArity;
    }

    private static String text(String javadoc) {
        return javadoc == null ? "" : javadoc;
    }
}
