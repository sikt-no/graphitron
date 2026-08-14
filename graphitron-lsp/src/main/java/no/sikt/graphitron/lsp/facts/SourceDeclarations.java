package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jooq.Field;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;

import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FIELD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;

/**
 * What the java-source family holds about a declaration a surface has already named: the doc comment
 * a popup renders, and the position an editor jumps to. The one reader of that family, so the
 * tie-breaks below are stated once rather than per surface.
 *
 * <p>Unscoped by graph, deliberately: the family partitions on the source file, not on a source
 * membership, so a declaration is a fact about a file every graph in the session shares. The
 * {@link StoreHandle} is here for its query surface, not for its scope.
 *
 * <p>Two shapes for the doc comment, because two kinds of caller need it. A query that already
 * carries a class name on its own side (a catalog table's FQN, a classpath census row) takes the
 * correlated {@code javadocOf} field and pays for one query; a caller holding plain names takes the
 * direct lookup. Both resolve the same way: absence is a missing row and reads as empty text, and
 * where a malformed source tree declares one name in two files the first in file order wins, which
 * is arbitrary but stated and stable across re-parses.
 *
 * <p>The position lookups answer the same question in the coordinates an editor speaks. The store
 * holds the parse's own convention (a file path, 1-based line and column, and that API's {@code -1}
 * for a declaration it could not position); this is the surface that converts, to a {@code file:}
 * URI and the 0-based pair LSP uses. A declaration the parse left unpositioned is therefore absent
 * here while its doc comment is still readable above, which is exactly the asymmetry the family's
 * schema keeps room for.
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
        return byArityThenName(methodJavadocByArity(store, classFqn, methodName), arity).orElse("");
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

    /**
     * Where an editor jumps for the named class, or empty where no parsed source positions one.
     * File order breaks a tie the same way {@link #classJavadoc} does, so a surface asking both
     * questions about one name is answered from one declaration.
     */
    public static Optional<Location> classLocation(StoreHandle store, String classFqn) {
        if (classFqn == null) return Optional.empty();
        var row = store.dsl()
            .select(JAVA_CLASS_DECLARATION.FILE,
                JAVA_CLASS_DECLARATION.SOURCE_LINE, JAVA_CLASS_DECLARATION.SOURCE_COLUMN)
            .from(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.CLASS_NAME.eq(classFqn))
            .orderBy(JAVA_CLASS_DECLARATION.FILE)
            .limit(1)
            .fetchOne();
        return row == null ? Optional.empty() : location(row.value1(), row.value2(), row.value3());
    }

    /**
     * Where an editor jumps for the named field of the named class. A record component and a
     * generated table class's column constant are both fields at this grain, which is what makes a
     * column jump and a component jump one lookup.
     */
    public static Optional<Location> fieldLocation(StoreHandle store, String classFqn, String fieldName) {
        if (classFqn == null || fieldName == null) return Optional.empty();
        var row = store.dsl()
            .select(JAVA_FIELD_DECLARATION.FILE,
                JAVA_FIELD_DECLARATION.SOURCE_LINE, JAVA_FIELD_DECLARATION.SOURCE_COLUMN)
            .from(JAVA_FIELD_DECLARATION)
            .where(JAVA_FIELD_DECLARATION.CLASS_NAME.eq(classFqn))
            .and(JAVA_FIELD_DECLARATION.FIELD_NAME.eq(fieldName))
            .orderBy(JAVA_FIELD_DECLARATION.FILE)
            .limit(1)
            .fetchOne();
        return row == null ? Optional.empty() : location(row.value1(), row.value2(), row.value3());
    }

    /**
     * Where an editor jumps for the overload of {@code methodName} declaring {@code arity}
     * parameters, falling back to the first declaration of the name when no overload declares that
     * many. The two tiers are {@link #methodJavadoc}'s, for the same reason: SDL names a method by
     * name alone, so the arity a consumer holds is whatever the census offered, and declining on it
     * would refuse to jump to a method the source plainly declares.
     */
    public static Optional<Location> methodLocation(
        StoreHandle store, String classFqn, String methodName, int arity
    ) {
        return byArityThenName(methodLocationByArity(store, classFqn, methodName), arity);
    }

    /**
     * Jump positions for one method name keyed by the arity the source declares, in the same
     * (file, declaration) order {@link #methodJavadocByArity} reads them, and holding the same
     * one-comment-per-arity shape for the same reason: arity is the only ground the parse and the
     * classpath census share. Each map holds only the rows that can answer its own question, so an
     * unpositioned declaration is missing here while its doc comment is still readable there.
     *
     * <p>Two overloads of one arity need no rule beyond the ordering, the first of them winning the
     * slot. That a keyed projection of this family has to drop such a pair and fall back to a
     * name-level view, where a relation simply holds both under their own ordinals, is the
     * difference between the two substrates rather than a policy either surface chose.
     */
    public static SequencedMap<Integer, Location> methodLocationByArity(
        StoreHandle store, String classFqn, String methodName
    ) {
        var byArity = new LinkedHashMap<Integer, Location>();
        if (classFqn == null || methodName == null) return byArity;
        var rows = store.dsl()
            .select(JAVA_METHOD_DECLARATION.PARAMETER_COUNT, JAVA_METHOD_DECLARATION.FILE,
                JAVA_METHOD_DECLARATION.SOURCE_LINE, JAVA_METHOD_DECLARATION.SOURCE_COLUMN)
            .from(JAVA_METHOD_DECLARATION)
            .where(JAVA_METHOD_DECLARATION.CLASS_NAME.eq(classFqn))
            .and(JAVA_METHOD_DECLARATION.METHOD_NAME.eq(methodName))
            .orderBy(JAVA_METHOD_DECLARATION.FILE, JAVA_METHOD_DECLARATION.ORDINAL)
            .fetch();
        for (var row : rows) {
            location(row.value2(), row.value3(), row.value4())
                .ifPresent(location -> byArity.putIfAbsent(row.value1(), location));
        }
        return byArity;
    }

    /**
     * The arity-then-name pick both method lookups make: the exact arity when the source declares
     * one, else the first declaration of the name in the map's order.
     */
    private static <T> Optional<T> byArityThenName(SequencedMap<Integer, T> byArity, int arity) {
        if (byArity.isEmpty()) return Optional.empty();
        T exact = byArity.get(arity);
        return Optional.of(exact != null ? exact : byArity.firstEntry().getValue());
    }

    /**
     * The store's position in the editor's coordinates: a {@code file:} URI and a 0-based
     * line / column, collapsed to a zero-width range at the declaration's first character. The
     * parse's no-position sentinel reads as absence, which is the honest answer to "where does an
     * editor jump" for a declaration nothing positioned.
     */
    private static Optional<Location> location(String file, Integer line, Integer column) {
        if (file == null || line == null || column == null || line < 0 || column < 0) {
            return Optional.empty();
        }
        var start = new Position(Math.max(line - 1, 0), Math.max(column - 1, 0));
        return Optional.of(new Location(Path.of(file).toUri().toString(), new Range(start, start)));
    }

    private static String text(String javadoc) {
        return javadoc == null ? "" : javadoc;
    }
}
