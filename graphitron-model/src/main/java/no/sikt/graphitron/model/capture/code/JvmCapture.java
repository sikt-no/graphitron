package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_DECLARED_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The classpath as the classes it declares: every public class the run can see, with its
 * supertypes, its methods and their parameters, its record components, and the type arguments
 * written inside any of those.
 *
 * <p>The general reading of a corpus {@link CodeCapture} reads for one question at a time. That one
 * says what an author may write at each directive; this one says what is there. Both are wanted
 * while the first cannot answer everything asked of it, and this leaves when it can.
 *
 * <h2>After the arms, and that is the point of the order</h2>
 *
 * <p>Runs after {@link CodeCapture} in {@link no.sikt.graphitron.model.run.ModelCapture}, so no
 * {@code code_} arm can read a {@code jvm_} row: there are none written yet when the arms are
 * filled. The direction has to hold for this family to be removable at all, and a sequence holds it
 * where a rule would have to be remembered.
 *
 * <p>Reads the classpath itself rather than taking a reading. The scan carries generic signatures,
 * which is what {@code jvm_declared_type_ref} decomposes and what the {@code declared_} columns
 * beside each erased one hold; a reading that had dropped them could not fill this family.
 */
public final class JvmCapture {

    private JvmCapture() {}

    /**
     * Writes what the classpath declares.
     */
    public static void capture(DSLContext dsl, ClasspathSourceCapture.Reading reading,
                               LocalDateTime readAt) {
        if (reading.census().entries().isEmpty()) {
            return;
        }
        // The reading the corpus reader performed, rendered into this family's vocabulary. Both
        // spellings come off one pass over the classfiles, so the erased forms this family holds
        // and the declared ones beside them cannot disagree about what was there.
        write(dsl, ClasspathScanner.project(reading.census()), reading.read(), readAt);
    }

    /**
     * The same, over a reading the caller performed.
     *
     * <p>For a fixture that states a census and has no classpath behind it. Handing one a
     * directory to scan would be asking it to own a classpath rather than describe one, and the
     * facts it means to state are the ones the census already holds. A run reads its own corpus
     * through the entry above and never arrives here.
     *
     * <p>Lossier than a scan, and the loss is the census's rather than this writer's: what the
     * caller did not read, this cannot write. It goes when the derivations that read this family
     * from a fixture's census do.
     *
     * @deprecated for a fixture with no classpath behind its census; it goes with them.
     */
    @Deprecated(forRemoval = true)
    public static void captureStated(DSLContext dsl,
                                     List<CompletionData.ExternalReference> classes,
                                     LocalDateTime readAt) {
        if (classes.isEmpty()) {
            return;
        }
        var read = new LinkedHashSet<String>();
        classes.forEach(reference -> read.add(
            reference.sourceName() == null ? "" : reference.sourceName()));
        write(dsl, classes, List.copyOf(read), readAt);
    }

    /**
     * Writes what the classpath declares, and makes the rows of every entry it read be exactly
     * that.
     *
     * <p>Marked and swept per entry, which is the grain the relations are keyed at: every one of
     * them leads with {@code source_name}, and a class leaves the store when the entry that
     * declared it stops declaring it. Graph-free, because what a classfile declares is not a
     * graph's fact; the graph's claim on the entry is the membership row beside it.
     */
    private static void write(DSLContext dsl, List<CompletionData.ExternalReference> classes,
                              List<String> read, LocalDateTime touchedAt) {
        var classRows = new ArrayList<ClassRow>();
        var supertypeRows = new ArrayList<SupertypeRow>();
        var methodRows = new ArrayList<MethodRow>();
        var parameterRows = new ArrayList<ParameterRow>();
        var componentRows = new ArrayList<ComponentRow>();
        var typeRefRows = new ArrayList<TypeRefRow>();
        // One row per class, and the first entry to declare it owns it. The key admits the same
        // class under two entries, so this is the reading's rule rather than the schema's: a class
        // on the classpath twice is one class, and the entry that shadows the other declares
        // nothing a reader of this family can act on.
        var declared = new LinkedHashSet<String>();

        for (CompletionData.ExternalReference reference : classes) {
            String className = reference.className();
            if (!declared.add(className)) {
                continue;
            }
            String source = reference.sourceName() == null ? "" : reference.sourceName();
            classRows.add(new ClassRow(source, className, reference.classKind()));

            for (CompletionData.Supertype supertype : reference.supertypes()) {
                supertypeRows.add(new SupertypeRow(source, className, supertype.className(),
                    supertype.declaredVia()));
            }

            for (CompletionData.Method method : reference.methods()) {
                String descriptor = method.descriptor();
                methodRows.add(new MethodRow(source, className, method.name(), descriptor,
                    method.returnType(), method.declaredReturnType()));
                for (CompletionData.TypeRef ref : method.returnTypeRefs()) {
                    typeRefRows.add(new TypeRefRow(source, className, "METHOD_RETURN",
                        method.name(), descriptor, -1, ref.path(), ref.referencedClass(),
                        ref.variance()));
                }
                int position = 0;
                for (CompletionData.Parameter parameter : method.parameters()) {
                    int at = position++;
                    parameterRows.add(new ParameterRow(source, className, method.name(),
                        descriptor, at, parameter.name(), parameter.type(),
                        parameter.declaredType()));
                    for (CompletionData.TypeRef ref : parameter.typeRefs()) {
                        typeRefRows.add(new TypeRefRow(source, className, "METHOD_PARAMETER",
                            method.name(), descriptor, at, ref.path(), ref.referencedClass(),
                            ref.variance()));
                    }
                }
            }

            int position = 0;
            for (CompletionData.RecordComponent component : reference.recordComponents()) {
                int at = position++;
                componentRows.add(new ComponentRow(source, className, component.name(), at,
                    component.displayType(), component.declaredType()));
                for (CompletionData.TypeRef ref : component.typeRefs()) {
                    typeRefRows.add(new TypeRefRow(source, className, "RECORD_COMPONENT",
                        component.name(), "", -1, ref.path(), ref.referencedClass(),
                        ref.variance()));
                }
            }
        }

        writeClasses(dsl, classRows, touchedAt);
        writeSupertypes(dsl, supertypeRows, touchedAt);
        writeMethods(dsl, methodRows, touchedAt);
        writeParameters(dsl, parameterRows, touchedAt);
        writeComponents(dsl, componentRows, touchedAt);
        writeTypeRefs(dsl, typeRefRows, touchedAt);
        sweep(dsl, read, touchedAt);
    }

    /** What one row of each relation carries, so the walk above states rows and writes none. */
    private record ClassRow(String source, String className, String classKind) {}

    private record SupertypeRow(String source, String className, String supertypeName,
                                String declaredVia) {}

    private record MethodRow(String source, String className, String methodName, String descriptor,
                             String returnType, String declaredReturnType) {}

    private record ParameterRow(String source, String className, String methodName,
                                String descriptor, int position, String parameterName,
                                String parameterType, String declaredParameterType) {}

    private record ComponentRow(String source, String className, String componentName,
                                int position, String displayType, String declaredType) {}

    private record TypeRefRow(String source, String className, String ownerKind, String ownerName,
                              String ownerDescriptor, int ownerPosition, String typePath,
                              String referencedClass, String variance) {}

    private static void writeClasses(DSLContext dsl, List<ClassRow> found, LocalDateTime touchedAt) {
        if (found.isEmpty()) {
            return;
        }
        var t = JVM_CLASS;
        var rows = found.stream().collect(Rows.toRowList(
            r -> val(r.source(), t.SOURCE_NAME),
            r -> val(r.className(), t.CLASS_NAME),
            r -> val(r.classKind(), t.CLASS_KIND),
            r -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.CLASS_KIND, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.CLASS_KIND, excluded(t.CLASS_KIND))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void writeSupertypes(DSLContext dsl, List<SupertypeRow> found,
                                        LocalDateTime touchedAt) {
        if (found.isEmpty()) {
            return;
        }
        var t = JVM_CLASS_SUPERTYPE;
        var rows = found.stream().collect(Rows.toRowList(
            r -> val(r.source(), t.SOURCE_NAME),
            r -> val(r.className(), t.CLASS_NAME),
            r -> val(r.supertypeName(), t.SUPERTYPE_NAME),
            r -> val(r.declaredVia(), t.DECLARED_VIA),
            r -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.SUPERTYPE_NAME, t.DECLARED_VIA,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.DECLARED_VIA, excluded(t.DECLARED_VIA))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void writeMethods(DSLContext dsl, List<MethodRow> found,
                                     LocalDateTime touchedAt) {
        if (found.isEmpty()) {
            return;
        }
        var t = JVM_METHOD;
        var rows = found.stream().collect(Rows.toRowList(
            r -> val(r.source(), t.SOURCE_NAME),
            r -> val(r.className(), t.CLASS_NAME),
            r -> val(r.methodName(), t.METHOD_NAME),
            r -> val(r.descriptor(), t.DESCRIPTOR),
            r -> val(r.returnType(), t.RETURN_TYPE),
            r -> val(r.declaredReturnType(), t.DECLARED_RETURN_TYPE),
            r -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR,
                    t.RETURN_TYPE, t.DECLARED_RETURN_TYPE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.RETURN_TYPE, excluded(t.RETURN_TYPE))
                .set(t.DECLARED_RETURN_TYPE, excluded(t.DECLARED_RETURN_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void writeParameters(DSLContext dsl, List<ParameterRow> found,
                                        LocalDateTime touchedAt) {
        if (found.isEmpty()) {
            return;
        }
        var t = JVM_METHOD_PARAMETER;
        var rows = found.stream().collect(Rows.toRowList(
            r -> val(r.source(), t.SOURCE_NAME),
            r -> val(r.className(), t.CLASS_NAME),
            r -> val(r.methodName(), t.METHOD_NAME),
            r -> val(r.descriptor(), t.DESCRIPTOR),
            r -> val(r.position(), t.POSITION),
            r -> val(r.parameterName(), t.PARAMETER_NAME),
            r -> val(r.parameterType(), t.PARAMETER_TYPE),
            r -> val(r.declaredParameterType(), t.DECLARED_PARAMETER_TYPE),
            r -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR, t.POSITION,
                    t.PARAMETER_NAME, t.PARAMETER_TYPE, t.DECLARED_PARAMETER_TYPE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.PARAMETER_NAME, excluded(t.PARAMETER_NAME))
                .set(t.PARAMETER_TYPE, excluded(t.PARAMETER_TYPE))
                .set(t.DECLARED_PARAMETER_TYPE, excluded(t.DECLARED_PARAMETER_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void writeComponents(DSLContext dsl, List<ComponentRow> found,
                                        LocalDateTime touchedAt) {
        if (found.isEmpty()) {
            return;
        }
        var t = JVM_RECORD_COMPONENT;
        var rows = found.stream().collect(Rows.toRowList(
            r -> val(r.source(), t.SOURCE_NAME),
            r -> val(r.className(), t.CLASS_NAME),
            r -> val(r.componentName(), t.COMPONENT_NAME),
            r -> val(r.position(), t.POSITION),
            r -> val(r.displayType(), t.DISPLAY_TYPE),
            r -> val(r.declaredType(), t.DECLARED_TYPE),
            r -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.COMPONENT_NAME, t.POSITION,
                    t.DISPLAY_TYPE, t.DECLARED_TYPE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.POSITION, excluded(t.POSITION))
                .set(t.DISPLAY_TYPE, excluded(t.DISPLAY_TYPE))
                .set(t.DECLARED_TYPE, excluded(t.DECLARED_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void writeTypeRefs(DSLContext dsl, List<TypeRefRow> found,
                                      LocalDateTime touchedAt) {
        if (found.isEmpty()) {
            return;
        }
        var t = JVM_DECLARED_TYPE_REF;
        var rows = found.stream().collect(Rows.toRowList(
            r -> val(r.source(), t.SOURCE_NAME),
            r -> val(r.className(), t.CLASS_NAME),
            r -> val(r.ownerKind(), t.OWNER_KIND),
            r -> val(r.ownerName(), t.OWNER_NAME),
            r -> val(r.ownerDescriptor(), t.OWNER_DESCRIPTOR),
            r -> val(r.ownerPosition(), t.OWNER_POSITION),
            r -> val(r.typePath(), t.TYPE_PATH),
            r -> val(r.referencedClass(), t.REFERENCED_CLASS),
            r -> val(r.variance(), t.VARIANCE),
            r -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.OWNER_KIND, t.OWNER_NAME,
                    t.OWNER_DESCRIPTOR, t.OWNER_POSITION, t.TYPE_PATH, t.REFERENCED_CLASS,
                    t.VARIANCE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.REFERENCED_CLASS, excluded(t.REFERENCED_CLASS))
                .set(t.VARIANCE, excluded(t.VARIANCE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * Deletes the rows of every entry this reading read that this reading did not write.
     *
     * <p>Scoped to the entries read and not to the store: an entry nobody handed this reading is
     * an entry it has nothing to say about, and another graph on a shared classpath is still
     * reading it. Children before parents is not this loop's business, the keys among these
     * relations cascading from the class they hang on.
     */
    private static void sweep(DSLContext dsl, List<String> sources, LocalDateTime touchedAt) {
        if (sources.isEmpty()) {
            return;
        }
        for (Table<?> table : TABLES_TO_SWEEP) {
            dsl.deleteFrom(table)
                .where(table.field(JVM_CLASS.SOURCE_NAME).in(sources))
                .and(table.field(JVM_CLASS.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    /**
     * What the sweep deletes from. Listed rather than found by prefix, on the entry family's
     * reasoning: a relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        JVM_CLASS, JVM_CLASS_SUPERTYPE, JVM_METHOD, JVM_METHOD_PARAMETER, JVM_RECORD_COMPONENT,
        JVM_DECLARED_TYPE_REF);
}
