package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ScalarConstantInput;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.CODE_EXTERNAL_FIELD_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_SCALAR_CONSTANT;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes what a schema may name in Java: one arm per directive that takes a Java reference, each
 * admitting the members that directive can take.
 *
 * <p>This is not a census with a filter on top. A census answers what the classpath holds, so its
 * one scope rule has to serve every reader and serves none of them exactly; the classfile census
 * this replaces was read by nobody for that reason, the generator resolving its own names
 * reflectively instead. An arm answers what may be written at one coordinate, and the predicate
 * that decides it is the arm's own.
 *
 * <p>Four arms so far, and they divide by corpus rather than by kind. Two read the whole
 * classpath, because what they admit is a library's to declare: the constants
 * {@code @scalarType(scalar:)} names, and the throwables an {@code @error} handler names. Two read
 * the reactor, a condition and a lifter both being consumer code by construction, and the three
 * still to come join them, for what a consumer writes at {@code @service}, {@code @enum} and a
 * reference's condition. Arguments, return types and declared exceptions get no arm of their own,
 * being facts about a method, and a method has exactly one arm.
 *
 * <p>The arms are deliberately specific rather than one method model with a discriminator on it.
 * A relation per arm is what lets each drop the facts its own admission fixes, which is why the
 * condition arm carries no return type and the lifter arm carries neither that nor a static flag:
 * each is one value on every row the arm could ever hold. The supertype over them is a thing to write once several arms exist and their shared
 * payload is a measured fact rather than a prediction.
 *
 * <p>Keyed and swept on the classpath entry, because an entry is shared: several graphs may read
 * one, and a reading replaces the rows of the entries it read and no others.
 */
public final class CodeCapture {

    /** The declared type a constant must have to be nameable in {@code @scalarType(scalar:)}. */
    private static final String SCALAR_TYPE = "graphql.schema.GraphQLScalarType";

    /** The return type a method must have to be nameable in {@code @condition(condition:)}. */
    private static final String JOOQ_CONDITION = "org.jooq.Condition";

    /** The return type a lifter must have to be nameable in {@code @externalField(reference:)}. */
    private static final String JOOQ_FIELD = "org.jooq.Field";

    /** The type that lifter's sole parameter must be, which is the table it lifts from. */
    private static final String JOOQ_TABLE = "org.jooq.Table";

    /**
     * The entry origins the reactor built: this module's own output and its siblings'. A declared
     * or transitive dependency is somebody else's code, which is what the arms over consumer code
     * are scoped away from.
     */
    private static final Set<String> REACTOR_ORIGINS =
        Set.of(ClasspathEntry.Origin.PROJECT.name(), ClasspathEntry.Origin.SIBLING.name());

    private CodeCapture() {}

    /**
     * Makes the code rows of {@code entries} be what their classfiles now declare.
     *
     * @param entries    the classpath as its producer classified them; each carries how it reached
     *                   the classpath, which a bare path could not say
     * @param skipPrefix a package to leave out, or null to leave nothing out
     * @param loader     the run's codegen classloader, for the one fact a classfile cannot answer;
     *                   null falls back to the thread's, which is what a caller with no catalog has
     */
    public static void capture(DSLContext dsl, List<ClasspathEntry> entries, String skipPrefix,
                               ClassLoader loader, LocalDateTime touchedAt) {
        var census = ClassfileCensus.read(entries, skipPrefix);
        if (census.entries().isEmpty()) {
            return;
        }
        sources(dsl, census.entries(), touchedAt);
        scalarConstants(dsl, census.classes(), loader, touchedAt);
        var reactor = reactorClasses(census);
        var ancestry = new ClassAncestry(census.classes(), loader);
        throwables(dsl, census.classes(), ancestry, touchedAt);
        conditionMethods(dsl, reactor, touchedAt);
        externalFieldMethods(dsl, reactor, ancestry, touchedAt);
        // Every entry read, not only the ones that declared something, so an entry that stopped
        // declaring a member loses its row.
        sweep(dsl, census.entries().stream().map(ClassfileCensus.EntryAt::source).toList(),
            touchedAt);
    }

    /** The registry rows every code row hangs its source on, one per classpath entry. */
    private static void sources(DSLContext dsl, List<ClassfileCensus.EntryAt> entries,
                                LocalDateTime touchedAt) {
        var t = STORE_SOURCE;
        var rows = entries.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.kind(), t.SOURCE_KIND),
            at -> val(at.origin(), t.ORIGIN),
            at -> val(at.coordinate(), t.COORDINATE),
            at -> val(touchedAt, t.LAST_SEEN),
            at -> val(touchedAt, t.READ_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.ORIGIN, t.COORDINATE, t.LAST_SEEN,
                    t.READ_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.ORIGIN, excluded(t.ORIGIN))
                .set(t.COORDINATE, excluded(t.COORDINATE))
                .set(t.LAST_SEEN, excluded(t.LAST_SEEN))
                .set(t.READ_AT, excluded(t.READ_AT)));
    }

    /**
     * The constants {@code @scalarType(scalar:)} may name: a public static field whose declared
     * type is exactly {@code GraphQLScalarType}.
     *
     * <p>Exactly, rather than assignable to it: the reference an author writes is resolved by
     * reading the field, and a field declared as a supertype would admit a value that is not one.
     * The admission is read from the classfile; the input type beside it cannot be, the coercing
     * being a live object rather than a signature, so that column alone costs a class load and is
     * null wherever the load or the read does not reach one.
     */
    private static void scalarConstants(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                        ClassLoader loader, LocalDateTime touchedAt) {
        record Constant(String source, String className, String fieldName) {}
        var found = new ArrayList<Constant>();
        for (ClassfileCensus.ClassAt at : classes) {
            for (ClassfileCensus.FieldAt field : at.fields()) {
                if (field.isStatic() && SCALAR_TYPE.equals(field.type())) {
                    found.add(new Constant(at.source(), at.className(), field.name()));
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var t = CODE_SCALAR_CONSTANT;
        var rows = found.stream().collect(Rows.toRowList(
            c -> val(c.source(), t.SOURCE_NAME),
            c -> val(c.className(), t.CLASS_NAME),
            c -> val(c.fieldName(), t.FIELD_NAME),
            c -> val(ScalarConstantInput.of(c.className(), c.fieldName(), loader), t.INPUT_TYPE),
            c -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.FIELD_NAME, t.INPUT_TYPE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.INPUT_TYPE, excluded(t.INPUT_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The throwables an {@code @error} handler may name: a class that extends its way to
     * {@code java.lang.Throwable}, and every type it is beside it.
     *
     * <p>The whole classpath and not the reactor, because the exceptions an author maps are the
     * ones no reactor signature names. An unchecked exception is thrown past the method that
     * caused it, so no {@code throws} clause carries it and no arm that reads a signature arrives
     * at one; the jOOQ integrity-constraint exception an {@code @error} handler names is exactly
     * that case.
     *
     * <p>Ancestry is written for the same reason it is followed: it is what tells a checked
     * throwable from an unchecked one and what sorts a specific exception under the family a
     * handler matches on, and none of it can be recomputed here later, the chain running through
     * classes that are on no classpath entry and have no rows.
     */
    private static void throwables(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                   ClassAncestry ancestry, LocalDateTime touchedAt) {
        record Ancestor(String source, String className, String supertypeName) {}
        var found = new ArrayList<ClassfileCensus.ClassAt>();
        var ancestors = new ArrayList<Ancestor>();
        for (ClassfileCensus.ClassAt at : classes) {
            if (!ancestry.isThrowable(at.className())) {
                continue;
            }
            found.add(at);
            for (String supertype : ancestry.ancestorsOf(at.className())) {
                ancestors.add(new Ancestor(at.source(), at.className(), supertype));
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var t = CODE_THROWABLE;
        var rows = found.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.className(), t.CLASS_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
        if (ancestors.isEmpty()) {
            return;
        }
        var s = CODE_THROWABLE_SUPERTYPE;
        var ancestorRows = ancestors.stream().collect(Rows.toRowList(
            a -> val(a.source(), s.SOURCE_NAME),
            a -> val(a.className(), s.CLASS_NAME),
            a -> val(a.supertypeName(), s.SUPERTYPE_NAME),
            a -> val(touchedAt, s.TOUCHED_AT)));
        BindBatch.execute(dsl, ancestorRows, markers ->
            dsl.insertInto(s, s.SOURCE_NAME, s.CLASS_NAME, s.SUPERTYPE_NAME, s.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT)));
    }

    /**
     * The classes of the entries the reactor built, which is the corpus the arms over consumer code
     * read.
     *
     * <p>A predicate over what the producer already classified rather than a second classpath: an
     * entry is the reactor's exactly when its origin is this module's own output or a sibling
     * module's, and a declared or transitive dependency is somebody else's code no matter what it
     * declares. Scoping here and not in the census, because the classpath arms beside these read
     * the whole of it and the two must go on reading one reading of the bytes.
     */
    private static List<ClassfileCensus.ClassAt> reactorClasses(ClassfileCensus.Census census) {
        var reactor = census.entries().stream()
            .filter(at -> REACTOR_ORIGINS.contains(at.origin()))
            .map(ClassfileCensus.EntryAt::source)
            .collect(Collectors.toSet());
        return census.classes().stream()
            .filter(at -> reactor.contains(at.source()))
            .toList();
    }

    /**
     * The methods {@code @condition(condition:)} may name: a method returning exactly
     * {@code org.jooq.Condition}.
     *
     * <p>The return type is the whole admission, and it is read un-erased so a consumer's own type
     * named {@code Condition} cannot pass. Everything else the generator asks of a condition
     * method, that it take at least two parameters and that one of them carry the source table, is
     * a judgement about one directive application rather than about candidacy: an author may write
     * a method that fails it, and the refusal they get should name the method rather than pretend
     * it does not exist.
     *
     * <p>The parameters go in beside the method for the same reason the ancestry goes in beside a
     * throwable: a descriptor states types and nothing else, so the name a binding targets and the
     * declared type that decides which position carries the table are readable now or never.
     */
    private static void conditionMethods(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                         LocalDateTime touchedAt) {
        record Method(String source, String className, ClassfileCensus.MethodAt at) {}
        var found = new ArrayList<Method>();
        for (ClassfileCensus.ClassAt at : classes) {
            for (ClassfileCensus.MethodAt method : at.methods()) {
                if (JOOQ_CONDITION.equals(method.returnType())) {
                    found.add(new Method(at.source(), at.className(), method));
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var t = CODE_CONDITION_METHOD;
        var rows = found.stream().collect(Rows.toRowList(
            m -> val(m.source(), t.SOURCE_NAME),
            m -> val(m.className(), t.CLASS_NAME),
            m -> val(m.at().name(), t.METHOD_NAME),
            m -> val(m.at().descriptor(), t.DESCRIPTOR),
            m -> val(m.at().isStatic(), t.IS_STATIC),
            m -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR, t.IS_STATIC,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.IS_STATIC, excluded(t.IS_STATIC))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));

        record Parameter(String source, String className, String methodName, String descriptor,
                         ClassfileCensus.ParameterAt at) {}
        var parameters = new ArrayList<Parameter>();
        for (Method m : found) {
            for (ClassfileCensus.ParameterAt parameter : m.at().parameters()) {
                parameters.add(new Parameter(m.source(), m.className(), m.at().name(),
                    m.at().descriptor(), parameter));
            }
        }
        if (parameters.isEmpty()) {
            return;
        }
        var p = CODE_CONDITION_METHOD_PARAMETER;
        var parameterRows = parameters.stream().collect(Rows.toRowList(
            row -> val(row.source(), p.SOURCE_NAME),
            row -> val(row.className(), p.CLASS_NAME),
            row -> val(row.methodName(), p.METHOD_NAME),
            row -> val(row.descriptor(), p.DESCRIPTOR),
            row -> val(row.at().position(), p.POSITION),
            row -> val(row.at().name(), p.PARAMETER_NAME),
            row -> val(row.at().type(), p.PARAMETER_TYPE),
            row -> val(touchedAt, p.TOUCHED_AT)));
        BindBatch.execute(dsl, parameterRows, markers ->
            dsl.insertInto(p, p.SOURCE_NAME, p.CLASS_NAME, p.METHOD_NAME, p.DESCRIPTOR, p.POSITION,
                    p.PARAMETER_NAME, p.PARAMETER_TYPE, p.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(p.PARAMETER_NAME, excluded(p.PARAMETER_NAME))
                .set(p.PARAMETER_TYPE, excluded(p.PARAMETER_TYPE))
                .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT)));
    }

    /**
     * The lifters {@code @externalField(reference:)} may name: a public static method taking one
     * jOOQ table and returning {@code org.jooq.Field}.
     *
     * <p>Every clause is checked because every clause is about the method. The generator's one
     * remaining requirement, that the table lifted from is the table the field was written on, is
     * about the application and is left to a reader with the parameter type this writes.
     *
     * <p>The parameter test is assignability and not a name match, a consumer's lifter taking the
     * generated table class for its own table rather than the interface. That class is not in the
     * census, the generated package being the one thing the reading skips, so the chain above it is
     * followed by loading it, which is the same walk the throwables arm makes for the same reason.
     */
    private static void externalFieldMethods(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                             ClassAncestry ancestry, LocalDateTime touchedAt) {
        record Lifter(String source, String className, ClassfileCensus.MethodAt at, String table) {}
        var found = new ArrayList<Lifter>();
        for (ClassfileCensus.ClassAt at : classes) {
            for (ClassfileCensus.MethodAt method : at.methods()) {
                if (!method.isStatic() || !JOOQ_FIELD.equals(method.returnType())
                    || method.parameters().size() != 1) {
                    continue;
                }
                String parameter = method.parameters().getFirst().type();
                if (ancestry.isA(parameter, JOOQ_TABLE)) {
                    found.add(new Lifter(at.source(), at.className(), method, parameter));
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var t = CODE_EXTERNAL_FIELD_METHOD;
        var rows = found.stream().collect(Rows.toRowList(
            l -> val(l.source(), t.SOURCE_NAME),
            l -> val(l.className(), t.CLASS_NAME),
            l -> val(l.at().name(), t.METHOD_NAME),
            l -> val(l.at().descriptor(), t.DESCRIPTOR),
            l -> val(l.table(), t.TABLE_PARAMETER_TYPE),
            l -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR,
                    t.TABLE_PARAMETER_TYPE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TABLE_PARAMETER_TYPE, excluded(t.TABLE_PARAMETER_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * Deletes the rows of the entries this reading read that it did not touch, which are the
     * members those entries no longer declare.
     *
     * <p>The ancestry rows are swept first and on their own instant rather than by cascade: a
     * throwable that kept its row and lost a supertype is a live case, the class having been
     * recompiled against a shallower hierarchy, and a cascade from the parent would never fire
     * for it.
     */
    private static void sweep(DSLContext dsl, List<String> sources, LocalDateTime touchedAt) {
        var lifter = CODE_EXTERNAL_FIELD_METHOD;
        dsl.deleteFrom(lifter)
            .where(lifter.SOURCE_NAME.in(sources))
            .and(lifter.TOUCHED_AT.ne(touchedAt))
            .execute();
        var parameter = CODE_CONDITION_METHOD_PARAMETER;
        dsl.deleteFrom(parameter)
            .where(parameter.SOURCE_NAME.in(sources))
            .and(parameter.TOUCHED_AT.ne(touchedAt))
            .execute();
        var condition = CODE_CONDITION_METHOD;
        dsl.deleteFrom(condition)
            .where(condition.SOURCE_NAME.in(sources))
            .and(condition.TOUCHED_AT.ne(touchedAt))
            .execute();
        var s = CODE_THROWABLE_SUPERTYPE;
        dsl.deleteFrom(s)
            .where(s.SOURCE_NAME.in(sources))
            .and(s.TOUCHED_AT.ne(touchedAt))
            .execute();
        var throwable = CODE_THROWABLE;
        dsl.deleteFrom(throwable)
            .where(throwable.SOURCE_NAME.in(sources))
            .and(throwable.TOUCHED_AT.ne(touchedAt))
            .execute();
        var t = CODE_SCALAR_CONSTANT;
        dsl.deleteFrom(t)
            .where(t.SOURCE_NAME.in(sources))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
