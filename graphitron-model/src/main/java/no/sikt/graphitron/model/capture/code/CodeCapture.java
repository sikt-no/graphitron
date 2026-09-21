package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ClassfileCensus;
import no.sikt.graphitron.model.classpath.ScalarConstantInput;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Rows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD_PARAMETER_TABLE;
import static no.sikt.graphitron.model.Tables.CODE_EXTERNAL_FIELD_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_EXCEPTION;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.CODE_CONSTRUCTION;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_SLOT;
import static no.sikt.graphitron.model.Tables.CODE_WRITE_SLOT;
import static no.sikt.graphitron.model.Tables.CODE_SCALAR_CONSTANT;
import static no.sikt.graphitron.model.Tables.CODE_SERVICE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.INTENT_DELIVERY_CONTAINER;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
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
 * <p>Four arms so far, and they divide by corpus rather than by kind. Two read the whole declared
 * classpath, because what they admit is a library's to declare: the constants
 * {@code @scalarType(scalar:)} names, and the throwables an {@code @error} handler names. Declared
 * rather than whole, a dependency the module did not declare being nameable at no coordinate at
 * all; {@link #nameable} is where that bound is stated. Two read the
 * reactor, a condition and a lifter both being consumer code by construction, and the three
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

    /** The type a {@code @service} parameter has when the run's own jOOQ context is passed to it. */
    private static final String JOOQ_DSL_CONTEXT = "org.jooq.DSLContext";

    /** The role a condition's parameter plays when its declaration names one generated table. */
    private static final String TABLE_CONCRETE = "TABLE_CONCRETE";

    /**
     * The entry origins the reactor built: this module's own output and its siblings'. A declared
     * or transitive dependency is somebody else's code, which is what the arms over consumer code
     * are scoped away from.
     */
    private static final Set<String> REACTOR_ORIGINS =
        Set.of(ClasspathEntry.Origin.PROJECT.name(), ClasspathEntry.Origin.REACTOR.name(),
            ClasspathEntry.Origin.SIBLING.name());

    private CodeCapture() {}

    /**
     * The entries any arm here may read: everything but a dependency the module did not declare.
     *
     * <p>The bound is the same one the arms are, one scope up. An arm says what may be written at a
     * coordinate, and nothing a transitive dependency carries may be written anywhere: naming one
     * is the undeclared-dependency antipattern, and the build refuses it rather than resolving it
     * quietly. Admitting such a class would make the store offer an author a name the run then
     * rejects, which is worse than not offering it, the refusal arriving after the writing.
     *
     * <p>In front of the reading rather than over its result, because the cost of the reading is
     * decompression and the only cut that moves it is opening fewer jars. The reactor arms narrow
     * again from here, and they narrow the classes rather than the entries: those two arms read the
     * whole of what this leaves, so one reading of the bytes still serves all four.
     */
    private static List<ClasspathEntry> nameable(List<ClasspathEntry> entries) {
        return entries.stream()
            .filter(entry -> entry.origin() != ClasspathEntry.Origin.TRANSITIVE)
            .toList();
    }

    /**
     * Makes the code rows of the classpath be what its classfiles now declare.
     *
     * @param reading the classpath as the corpus reader read it, which is where the entries and
     *                their provenance are recorded; this gatherer writes its own family and none
     *                of that
     * @param loader  the run's codegen classloader, for the one fact a classfile cannot answer;
     *                null falls back to the thread's, which is what a caller with no catalog has
     */
    public static void capture(DSLContext dsl, ClasspathSourceCapture.Reading reading,
                               ClassLoader loader, LocalDateTime touchedAt) {
        var census = reading.census();
        if (census.entries().isEmpty()) {
            return;
        }
        // The entry rows are the corpus reader's, not this gatherer's: one reading of the
        // classpath records where its rows came from, and two writers of that would be two
        // answers to the same question.
        scalarConstants(dsl, census.classes(), loader, touchedAt);
        var reactor = reactorClasses(census);
        var ancestry = new ClassAncestry(census.classes(), loader);
        throwables(dsl, census.classes(), ancestry, touchedAt);
        methods(dsl, reactor, ancestry, touchedAt);
        // Every entry read, not only the ones that declared something, so an entry that stopped
        // declaring a member loses its row.
        sweep(dsl, reading.read(), touchedAt);
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
     * The reactor's public methods, what each declares, and which directive may name each.
     *
     * <p>One pass because the facts divide that way. What a method returns, takes and throws is a
     * property of the declaration and does not vary by the coordinate somebody reached it through,
     * so it is written once; which directive may name it is three different questions and three
     * membership relations. Holding the declaration's facts per arm held one fact as many times as
     * there were arms pointing at the method, and let the copies disagree.
     *
     * <p>The arms partition these methods today and the code does not rely on that. A condition is
     * a method returning exactly {@code org.jooq.Condition}; a lifter is the whole of that
     * directive's contract; and a service is what is left, because {@code @service} resolution
     * applies no filter of its own and a candidate for it is anything that is not already an
     * answer to something else.
     */
    private static void methods(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                ClassAncestry ancestry, LocalDateTime touchedAt) {
        record Declared(String source, String className, ClassfileCensus.MethodAt at) {}
        var found = new ArrayList<Declared>();
        for (ClassfileCensus.ClassAt at : classes) {
            for (ClassfileCensus.MethodAt method : at.methods()) {
                found.add(new Declared(at.source(), at.className(), method));
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var containers = deliveryContainers(dsl);
        types(dsl, found.stream()
            .map(d -> new Member(d.source(), d.className(), d.at()))
            .toList(), containers, touchedAt);

        var m = CODE_METHOD;
        var methodRows = found.stream().collect(Rows.toRowList(
            d -> val(d.source(), m.SOURCE_NAME),
            d -> val(d.className(), m.CLASS_NAME),
            d -> val(d.at().name(), m.METHOD_NAME),
            d -> val(d.at().descriptor(), m.DESCRIPTOR),
            d -> val(d.at().isStatic(), m.IS_STATIC),
            d -> val(d.at().qualifiedReturnType(), m.RESULT_TYPE),
            d -> val(touchedAt, m.TOUCHED_AT)));
        BindBatch.execute(dsl, methodRows, markers ->
            dsl.insertInto(m, m.SOURCE_NAME, m.CLASS_NAME, m.METHOD_NAME, m.DESCRIPTOR, m.IS_STATIC,
                    m.RESULT_TYPE, m.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(m.IS_STATIC, excluded(m.IS_STATIC))
                .set(m.RESULT_TYPE, excluded(m.RESULT_TYPE))
                .set(m.TOUCHED_AT, excluded(m.TOUCHED_AT)));
        exceptions(dsl, found.stream()
            .map(d -> new Member(d.source(), d.className(), d.at()))
            .toList(), touchedAt);
        parameters(dsl, found.stream()
            .map(d -> new Member(d.source(), d.className(), d.at()))
            .toList(), ancestry, containers, touchedAt);

        slots(dsl, classes, touchedAt);
        construction(dsl, classes, containers, touchedAt);

        arm(dsl, CODE_CONDITION_METHOD, found.stream()
            .filter(d -> isConditionMethod(d.at()))
            .map(d -> new Member(d.source(), d.className(), d.at())).toList(), touchedAt);
        arm(dsl, CODE_EXTERNAL_FIELD_METHOD, found.stream()
            .filter(d -> isLifterMethod(d.at(), ancestry))
            .map(d -> new Member(d.source(), d.className(), d.at())).toList(), touchedAt);
        arm(dsl, CODE_SERVICE_METHOD, found.stream()
            .filter(d -> !isConditionMethod(d.at()) && !isLifterMethod(d.at(), ancestry))
            .map(d -> new Member(d.source(), d.className(), d.at())).toList(), touchedAt);
    }

    /**
     * The types the entry's signatures mention, and what each resolves to.
     *
     * <p>Written before anything that names one, and written once however many positions carry it:
     * a type is a fact about itself, so a parameter and a result that spell it alike are two
     * references to one row rather than two statements of the same thing. Over this module's main
     * sources that is thirteen hundred positions over two hundred types.
     */
    private static void types(DSLContext dsl, List<Member> members,
                              Map<String, Container> containers, LocalDateTime touchedAt) {
        record Written(String source, String typeName, String displayName, Delivery delivery) {}
        var byKey = new java.util.LinkedHashMap<String, Written>();
        for (Member member : members) {
            byKey.putIfAbsent(member.source() + '\u0000' + member.at().qualifiedReturnType(),
                new Written(member.source(), member.at().qualifiedReturnType(),
                    member.at().declaredReturnType(),
                    deliveryOf(member.at().returnTypeRefs(), containers)));
            for (ClassfileCensus.ParameterAt parameter : member.at().parameters()) {
                byKey.putIfAbsent(member.source() + '\u0000' + parameter.qualifiedType(),
                    new Written(member.source(), parameter.qualifiedType(),
                        parameter.declaredType(),
                        deliveryOf(parameter.typeRefs(), containers)));
            }
        }
        // Every class a peel lands on is a type too, and gets a row of its own so the relations
        // that hang facts on a class have something to key to. A landing class is named at a
        // position inside some declared type rather than being one, so without this the store
        // could say what a type delivers and could say nothing further about what it delivered.
        for (Written written : List.copyOf(byKey.values())) {
            if (written.delivery() == null) {
                continue;
            }
            String element = written.delivery().elementClass();
            byKey.putIfAbsent(written.source() + '\u0000' + element,
                new Written(written.source(), element, simpleName(element), null));
        }
        if (byKey.isEmpty()) {
            return;
        }
        writeTypes(dsl, byKey.values().stream()
            .map(row -> new Named(row.source(), row.typeName(), row.displayName()))
            .toList(), touchedAt);

        var resolved = byKey.values().stream().filter(row -> row.delivery() != null).toList();
        if (resolved.isEmpty()) {
            return;
        }
        var e = CODE_TYPE_ELEMENT;
        var elementRows = resolved.stream().collect(Rows.toRowList(
            row -> val(row.source(), e.SOURCE_NAME),
            row -> val(row.typeName(), e.TYPE_NAME),
            row -> val(row.delivery().elementClass(), e.ELEMENT_CLASS),
            row -> val(row.delivery().deliversMany(), e.IS_MANY),
            row -> val(touchedAt, e.TOUCHED_AT)));
        BindBatch.execute(dsl, elementRows, markers ->
            dsl.insertInto(e, e.SOURCE_NAME, e.TYPE_NAME, e.ELEMENT_CLASS, e.IS_MANY, e.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(e.ELEMENT_CLASS, excluded(e.ELEMENT_CLASS))
                .set(e.IS_MANY, excluded(e.IS_MANY))
                .set(e.TOUCHED_AT, excluded(e.TOUCHED_AT)));
    }

    /**
     * The member names each class offers an author, and the method that reads each.
     *
     * <p>A class takes exactly one arm, chosen by its declared form: a record answers with its
     * components and anything else with its getters. The discriminator is the class's and not the
     * member's, which is what keeps a slot name unambiguous about where it came from.
     *
     * <p>The record arm reads the components rather than the methods, and the difference is not
     * pedantry. A record's accessors are ordinary public methods, and so are the {@code toString},
     * {@code hashCode} and {@code equals} it generates beside them: none is synthetic and none
     * takes an argument, so a rule that admitted every no-argument public method would offer an
     * author {@code toString} as a member. The {@code Record} attribute names the components, and
     * matching them to their accessors is what tells the two apart.
     *
     * <p>The bean rule is the other arm's and is a rule about the name: {@code get} or {@code is}
     * followed by an upper-case letter offers the remainder with its first letter lowered. No arm
     * reads the return type, because an author who wrote {@code isTitle} returning a String meant
     * that member and a rule that second-guessed the type would hide it.
     */
    private static void slots(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                              LocalDateTime touchedAt) {
        record Slot(String source, String className, ClassfileCensus.MethodAt at, String slotName,
                    String origin, Integer position) {}
        var found = new ArrayList<Slot>();
        for (ClassfileCensus.ClassAt at : classes) {
            boolean isRecord = "RECORD".equals(at.kind());
            var components = new java.util.HashMap<String, Integer>();
            for (ClassfileCensus.ComponentAt component : at.components()) {
                components.putIfAbsent(component.name(), component.position());
            }
            for (ClassfileCensus.MethodAt method : at.methods()) {
                if (!method.parameters().isEmpty()) {
                    continue;
                }
                if (isRecord) {
                    Integer position = components.get(method.name());
                    if (position != null) {
                        found.add(new Slot(at.source(), at.className(), method, method.name(),
                            "RECORD_COMPONENT", position));
                    }
                    continue;
                }
                String property = beanProperty(method.name());
                if (property != null) {
                    found.add(new Slot(at.source(), at.className(), method, property,
                        "BEAN_ACCESSOR", null));
                }
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var t = CODE_TYPE_SLOT;
        var rows = found.stream().collect(Rows.toRowList(
            row -> val(row.source(), t.SOURCE_NAME),
            row -> val(row.className(), t.CLASS_NAME),
            row -> val(row.at().name(), t.METHOD_NAME),
            row -> val(row.at().descriptor(), t.DESCRIPTOR),
            row -> val(row.slotName(), t.SLOT_NAME),
            row -> val(row.origin(), t.ORIGIN),
            row -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR,
                    t.SLOT_NAME, t.ORIGIN, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.SLOT_NAME, excluded(t.SLOT_NAME))
                .set(t.ORIGIN, excluded(t.ORIGIN))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));

    }

    /** The property a getter offers, or null where the name is not one. */
    private static String beanProperty(String methodName) {
        String get = propertyAfter(methodName, "get");
        return get != null ? get : propertyAfter(methodName, "is");
    }

    private static String propertyAfter(String methodName, String prefix) {
        if (!methodName.startsWith(prefix) || methodName.length() <= prefix.length()) {
            return null;
        }
        char first = methodName.charAt(prefix.length());
        if (first == Character.toLowerCase(first)) {
            return null;
        }
        return Character.toLowerCase(first) + methodName.substring(prefix.length() + 1);
    }

    /**
     * How a value of each class a generator has to make is made, and what goes into it.
     *
     * <p>Reached rather than enumerated, which is what keeps the relation about the generator's
     * problem instead of about the classpath. A type at a result position is only read, so it owes
     * accessors and nothing here. A type at a parameter position has to exist before it can be
     * passed, so what it delivers is constructed, and whatever that constructor takes is
     * constructed in turn. The walk starts at the parameter positions and closes over the
     * constructors it finds, so a class nothing is ever passed has no row however constructible it
     * looks.
     *
     * <p>A class the walk reaches and cannot make is silence rather than a row saying so. An
     * interface, an enum and a class whose constructors all take arguments it cannot supply are the
     * same answer to a consumer, which is that this is not a class a generator can hand over, and
     * the walk stops there rather than recording a shape nobody can emit.
     */
    private static void construction(DSLContext dsl, List<ClassfileCensus.ClassAt> classes,
                                     Map<String, Container> containers, LocalDateTime touchedAt) {
        var byName = new java.util.LinkedHashMap<String, ClassfileCensus.ClassAt>();
        for (ClassfileCensus.ClassAt at : classes) {
            byName.putIfAbsent(at.className(), at);
        }
        var queue = new java.util.ArrayDeque<String>();
        var reached = new java.util.LinkedHashSet<String>();
        for (ClassfileCensus.ClassAt at : classes) {
            for (ClassfileCensus.MethodAt method : at.methods()) {
                for (ClassfileCensus.ParameterAt parameter : method.parameters()) {
                    delivered(parameter.typeRefs(), containers, byName, reached, queue);
                }
            }
        }

        record Made(ClassfileCensus.ClassAt at, ClassfileCensus.MethodAt constructor, String shape) {}
        var made = new ArrayList<Made>();
        while (!queue.isEmpty()) {
            ClassfileCensus.ClassAt at = byName.get(queue.poll());
            boolean positional = "RECORD".equals(at.kind());
            ClassfileCensus.MethodAt constructor = positional
                ? canonicalConstructor(at) : noArgumentConstructor(at);
            if (constructor == null) {
                continue;
            }
            made.add(new Made(at, constructor, positional ? "POSITIONAL" : "SETTERS"));
            // What goes in has to be made too, on the same terms the parameter positions above
            // reached this class by.
            if (positional) {
                for (ClassfileCensus.ParameterAt parameter : constructor.parameters()) {
                    delivered(parameter.typeRefs(), containers, byName, reached, queue);
                }
            } else {
                for (ClassfileCensus.MethodAt setter : settersOf(at, byName)) {
                    delivered(setter.parameters().getFirst().typeRefs(), containers, byName,
                        reached, queue);
                }
            }
        }
        if (made.isEmpty()) {
            return;
        }
        // The types this pass is about to key to, before it keys to them. The classes it reached
        // through a constructor are ones no method walk named, so the signature pass cannot have
        // written them.
        var named = new java.util.LinkedHashMap<String, Named>();
        for (Made row : made) {
            named.putIfAbsent(row.at().source() + '\u0000' + row.at().className(),
                new Named(row.at().source(), row.at().className(),
                    simpleName(row.at().className())));
            var carried = "POSITIONAL".equals(row.shape())
                ? row.constructor().parameters()
                : settersOf(row.at(), byName).stream()
                    .map(method -> method.parameters().getFirst())
                    .toList();
            for (ClassfileCensus.ParameterAt parameter : carried) {
                named.putIfAbsent(row.at().source() + '\u0000' + parameter.qualifiedType(),
                    new Named(row.at().source(), parameter.qualifiedType(),
                        parameter.declaredType()));
            }
        }
        writeTypes(dsl, List.copyOf(named.values()), touchedAt);

        var c = CODE_CONSTRUCTION;
        var constructionRows = made.stream().collect(Rows.toRowList(
            row -> val(row.at().source(), c.SOURCE_NAME),
            row -> val(row.at().className(), c.TYPE_NAME),
            row -> val(row.shape(), c.SHAPE),
            row -> val(row.constructor().descriptor(), c.DESCRIPTOR),
            row -> val(touchedAt, c.TOUCHED_AT)));
        BindBatch.execute(dsl, constructionRows, markers ->
            dsl.insertInto(c, c.SOURCE_NAME, c.TYPE_NAME, c.SHAPE, c.DESCRIPTOR, c.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(c.SHAPE, excluded(c.SHAPE))
                .set(c.DESCRIPTOR, excluded(c.DESCRIPTOR))
                .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT)));

        record Slot(String source, String className, String methodName, String descriptor,
                    int position, String slotName, String slotType) {}
        var slots = new ArrayList<Slot>();
        for (Made row : made) {
            if ("POSITIONAL".equals(row.shape())) {
                var components = row.at().components();
                var parameters = row.constructor().parameters();
                for (int i = 0; i < parameters.size() && i < components.size(); i++) {
                    slots.add(new Slot(row.at().source(), row.at().className(), "<init>",
                        row.constructor().descriptor(), i, components.get(i).name(),
                        parameters.get(i).qualifiedType()));
                }
                continue;
            }
            for (ClassfileCensus.MethodAt setter : settersOf(row.at(), byName)) {
                slots.add(new Slot(row.at().source(), row.at().className(), setter.name(),
                    setter.descriptor(), 0, setterProperty(setter),
                    setter.parameters().getFirst().qualifiedType()));
            }
        }
        if (slots.isEmpty()) {
            return;
        }
        var w = CODE_WRITE_SLOT;
        var slotRows = slots.stream().collect(Rows.toRowList(
            row -> val(row.source(), w.SOURCE_NAME),
            row -> val(row.className(), w.TYPE_NAME),
            row -> val(row.methodName(), w.METHOD_NAME),
            row -> val(row.descriptor(), w.DESCRIPTOR),
            row -> val(row.position(), w.POSITION),
            row -> val(row.slotName(), w.SLOT_NAME),
            row -> val(row.slotType(), w.SLOT_TYPE),
            row -> val(touchedAt, w.TOUCHED_AT)));
        BindBatch.execute(dsl, slotRows, markers ->
            dsl.insertInto(w, w.SOURCE_NAME, w.TYPE_NAME, w.METHOD_NAME, w.DESCRIPTOR, w.POSITION,
                    w.SLOT_NAME, w.SLOT_TYPE, w.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(w.SLOT_NAME, excluded(w.SLOT_NAME))
                .set(w.SLOT_TYPE, excluded(w.SLOT_TYPE))
                .set(w.TOUCHED_AT, excluded(w.TOUCHED_AT)));
    }

    /** Queues what a declared type delivers, where that is a class this reading read. */
    private static void delivered(List<ClassfileCensus.TypeRefAt> refs,
                                  Map<String, Container> containers,
                                  Map<String, ClassfileCensus.ClassAt> byName,
                                  java.util.Set<String> reached,
                                  java.util.Deque<String> queue) {
        Delivery delivery = deliveryOf(refs, containers);
        if (delivery == null || !byName.containsKey(delivery.elementClass())) {
            return;
        }
        if (reached.add(delivery.elementClass())) {
            queue.add(delivery.elementClass());
        }
    }

    /**
     * The constructor whose parameters are the record's components, which is the one that makes a
     * record in one call. Matched on the erased parameter types in header order, so a second
     * constructor a record declares for convenience is not mistaken for it.
     */
    private static ClassfileCensus.MethodAt canonicalConstructor(ClassfileCensus.ClassAt at) {
        var components = at.components();
        for (ClassfileCensus.MethodAt constructor : at.constructors()) {
            var parameters = constructor.parameters();
            if (parameters.size() != components.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < parameters.size(); i++) {
                if (!parameters.get(i).type().equals(components.get(i).type())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return constructor;
            }
        }
        return null;
    }

    /**
     * The no-argument constructor, which is what a class filled one member at a time starts from.
     *
     * <p>An abstract class has none for this purpose however many it declares. A public constructor
     * on an abstract class is there for its subclasses to chain to and calling it is not a thing any
     * emitter can do, so a rule reading constructors alone would admit a class nothing can make.
     */
    private static ClassfileCensus.MethodAt noArgumentConstructor(ClassfileCensus.ClassAt at) {
        if (at.isAbstract() || "INTERFACE".equals(at.kind()) || "ANNOTATION".equals(at.kind())) {
            return null;
        }
        return at.constructors().stream()
            .filter(constructor -> constructor.parameters().isEmpty())
            .findFirst()
            .orElse(null);
    }

    /**
     * Every setter a class offers, its superclasses' included, nearest declaration winning.
     *
     * <p>What an author may fill is what the class offers rather than what its own file declares,
     * so a member a base class declares is one the emitter reaches through the subclass and has to
     * be offered here too. The walk is the extends chain and stops where the classpath does, which
     * is the same best effort every walk past a classfile makes: a base class no entry declares
     * contributes what it cannot be read for, which is nothing.
     *
     * <p>Overriding is by name and descriptor, which is what overriding is, and the nearest class
     * wins because that is the method the call actually reaches.
     */
    private static List<ClassfileCensus.MethodAt> settersOf(
        ClassfileCensus.ClassAt at, Map<String, ClassfileCensus.ClassAt> byName) {
        var offered = new ArrayList<ClassfileCensus.MethodAt>();
        var overridden = new java.util.HashSet<String>();
        var walked = new java.util.LinkedHashSet<String>();
        ClassfileCensus.ClassAt walking = at;
        while (walking != null && walked.add(walking.className())) {
            for (ClassfileCensus.MethodAt method : walking.methods()) {
                if (setterProperty(method) == null) {
                    continue;
                }
                if (overridden.add(method.name() + method.descriptor())) {
                    offered.add(method);
                }
            }
            walking = walking.supertypes().stream()
                .filter(supertype -> "EXTENDS".equals(supertype.declaredVia()))
                .map(supertype -> byName.get(supertype.name()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        }
        return offered;
    }

    /**
     * The property a setter fills, or null where the method is not one: {@code set} followed by an
     * upper-case letter, taking exactly one argument. The read side's rule in the other direction,
     * and it reads no type for the same reason that one does not.
     */
    private static String setterProperty(ClassfileCensus.MethodAt method) {
        if (method.parameters().size() != 1 || method.isStatic()) {
            return null;
        }
        return propertyAfter(method.name(), "set");
    }

    /** One type as this gatherer names it: where it was read, its key, and how it renders. */
    private record Named(String source, String typeName, String displayName) {}

    /**
     * The type rows, idempotently.
     *
     * <p>Two passes write them, and neither is the other's subset. The signature pass reaches a
     * type by reading a method, and the construction pass reaches one by following what a
     * constructor takes, which is a signature no method walk visits. So the relation is filled
     * where a type is first needed rather than in one place that would have to anticipate both.
     */
    private static void writeTypes(DSLContext dsl, List<Named> types, LocalDateTime touchedAt) {
        if (types.isEmpty()) {
            return;
        }
        var t = CODE_TYPE;
        var rows = types.stream().collect(Rows.toRowList(
            row -> val(row.source(), t.SOURCE_NAME),
            row -> val(row.typeName(), t.TYPE_NAME),
            row -> val(row.displayName(), t.DISPLAY_NAME),
            row -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TYPE_NAME, t.DISPLAY_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.DISPLAY_NAME, excluded(t.DISPLAY_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /** A binary class name as a surface renders it, which for a bare class is its last segment. */
    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** One method as this gatherer passes it around: where it was read, and what it declares. */
    private record Member(String source, String className, ClassfileCensus.MethodAt at) {}

    /**
     * One arm's membership. Every arm has the same four columns because membership is the whole of
     * what an arm says once the declaration's facts are held once, so they take one writer rather
     * than three that would have to agree.
     */
    private static void arm(DSLContext dsl, org.jooq.impl.TableImpl<?> table,
                            List<Member> members, LocalDateTime touchedAt) {
        if (members.isEmpty()) {
            return;
        }
        var t = CODE_SERVICE_METHOD;
        var rows = members.stream().collect(Rows.toRowList(
            member -> val(member.source(), t.SOURCE_NAME),
            member -> val(member.className(), t.CLASS_NAME),
            member -> val(member.at().name(), t.METHOD_NAME),
            member -> val(member.at().descriptor(), t.DESCRIPTOR),
            member -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(table, table.field(t.SOURCE_NAME), table.field(t.CLASS_NAME),
                    table.field(t.METHOD_NAME), table.field(t.DESCRIPTOR), table.field(t.TOUCHED_AT))
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(table.field(t.TOUCHED_AT), excluded(table.field(t.TOUCHED_AT))));
    }


    /**
     * The {@code throws} clause, one row per class named. Read by the {@code @error} channel
     * check and by {@code @condition}'s admission, which refuses a set of same-named declarations
     * that disagree on it.
     */
    private static void exceptions(DSLContext dsl, List<Member> members, LocalDateTime touchedAt) {
        record Thrown(Member member, String exceptionClass) {}
        var rows = new ArrayList<Thrown>();
        for (Member member : members) {
            for (String thrown : member.at().declaredExceptions()) {
                rows.add(new Thrown(member, thrown));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        var e = CODE_METHOD_EXCEPTION;
        var bound = rows.stream().collect(Rows.toRowList(
            row -> val(row.member().source(), e.SOURCE_NAME),
            row -> val(row.member().className(), e.CLASS_NAME),
            row -> val(row.member().at().name(), e.METHOD_NAME),
            row -> val(row.member().at().descriptor(), e.DESCRIPTOR),
            row -> val(row.exceptionClass(), e.EXCEPTION_CLASS),
            row -> val(touchedAt, e.TOUCHED_AT)));
        BindBatch.execute(dsl, bound, markers ->
            dsl.insertInto(e, e.SOURCE_NAME, e.CLASS_NAME, e.METHOD_NAME, e.DESCRIPTOR,
                    e.EXCEPTION_CLASS, e.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(e.TOUCHED_AT, excluded(e.TOUCHED_AT)));
    }

    /** Whether the condition arm admits this method, which is its return type and nothing else. */
    private static boolean isConditionMethod(ClassfileCensus.MethodAt method) {
        return JOOQ_CONDITION.equals(method.returnType());
    }

    /** Whether the lifter arm admits this method, which is every clause of its contract. */
    private static boolean isLifterMethod(ClassfileCensus.MethodAt method, ClassAncestry ancestry) {
        return method.isStatic() && JOOQ_FIELD.equals(method.returnType())
            && method.parameters().size() == 1
            && ancestry.isA(method.parameters().getFirst().type(), JOOQ_TABLE);
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


    private record Exception(String source, String className, ClassfileCensus.MethodAt at) {}

    /** A catalog table as this gatherer keys it, which is sql_table's own primary key. */
    private record TableAt(String source, String schema, String name) {}


    /**
     * The parameters, their roles and what each contains, plus the catalog table a concrete table
     * position names.
     *
     * <p>The role is one column over four exclusive values rather than one vocabulary per arm,
     * because a position typed as a jOOQ table is typed that way whoever is asking. What that
     * means at a coordinate is the arm's reading of it.
     */
    private static void parameters(DSLContext dsl, List<Member> members, ClassAncestry ancestry,
                                   Map<String, Container> containers, LocalDateTime touchedAt) {
        record At(Member member, ClassfileCensus.ParameterAt at, String role, String extraction) {}
        var found = new ArrayList<At>();
        for (Member member : members) {
            for (ClassfileCensus.ParameterAt parameter : member.at().parameters()) {
                String root = rootClass(parameter);
                found.add(new At(member, parameter, roleOf(parameter, ancestry),
                    root != null && ancestry.isEnum(root) ? "ENUM_VALUE_OF" : "DIRECT"));
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var p = CODE_METHOD_PARAMETER;
        var rows = found.stream().collect(Rows.toRowList(
            row -> val(row.member().source(), p.SOURCE_NAME),
            row -> val(row.member().className(), p.CLASS_NAME),
            row -> val(row.member().at().name(), p.METHOD_NAME),
            row -> val(row.member().at().descriptor(), p.DESCRIPTOR),
            row -> val(row.at().position(), p.POSITION),
            row -> val(row.at().name(), p.PARAMETER_NAME),
            row -> val(row.at().qualifiedType(), p.PARAMETER_TYPE),
            row -> val(row.role(), p.ROLE),
            row -> val(row.extraction(), p.EXTRACTION),
            row -> val(touchedAt, p.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(p, p.SOURCE_NAME, p.CLASS_NAME, p.METHOD_NAME, p.DESCRIPTOR, p.POSITION,
                    p.PARAMETER_NAME, p.PARAMETER_TYPE, p.ROLE, p.EXTRACTION, p.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(p.PARAMETER_NAME, excluded(p.PARAMETER_NAME))
                .set(p.PARAMETER_TYPE, excluded(p.PARAMETER_TYPE))
                .set(p.ROLE, excluded(p.ROLE))
                .set(p.EXTRACTION, excluded(p.EXTRACTION))
                .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT)));

        record Resolved(At at, TableAt table) {}
        var catalog = tablesByClass(dsl);
        var resolved = new ArrayList<Resolved>();
        for (At row : found) {
            if (!TABLE_CONCRETE.equals(row.role())) {
                continue;
            }
            var table = catalog.get(rootClass(row.at()));
            if (table != null) {
                resolved.add(new Resolved(row, table));
            }
        }
        if (resolved.isEmpty()) {
            return;
        }
        var pt = CODE_CONDITION_METHOD_PARAMETER_TABLE;
        var resolvedRows = resolved.stream().collect(Rows.toRowList(
            row -> val(row.at().member().source(), pt.SOURCE_NAME),
            row -> val(row.at().member().className(), pt.CLASS_NAME),
            row -> val(row.at().member().at().name(), pt.METHOD_NAME),
            row -> val(row.at().member().at().descriptor(), pt.DESCRIPTOR),
            row -> val(row.at().at().position(), pt.POSITION),
            row -> val(row.table().source(), pt.TABLE_SOURCE_NAME),
            row -> val(row.table().schema(), pt.TABLE_SCHEMA),
            row -> val(row.table().name(), pt.TABLE_NAME),
            row -> val(touchedAt, pt.TOUCHED_AT)));
        BindBatch.execute(dsl, resolvedRows, markers ->
            dsl.insertInto(pt, pt.SOURCE_NAME, pt.CLASS_NAME, pt.METHOD_NAME, pt.DESCRIPTOR,
                    pt.POSITION, pt.TABLE_SOURCE_NAME, pt.TABLE_SCHEMA, pt.TABLE_NAME,
                    pt.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(pt.TABLE_SOURCE_NAME, excluded(pt.TABLE_SOURCE_NAME))
                .set(pt.TABLE_SCHEMA, excluded(pt.TABLE_SCHEMA))
                .set(pt.TABLE_NAME, excluded(pt.TABLE_NAME))
                .set(pt.TOUCHED_AT, excluded(pt.TOUCHED_AT)));
    }

    /**
     * What one parameter position is for, decided here rather than by a reader.
     *
     * <p>The table test is assignability asked of the erasure, which is the question the generator
     * asks and the one a generated table class answers through a chain the census does not hold.
     * Concrete or not is then the declaration's own answer: a position written as
     * {@code org.jooq.Table} itself, raw or wildcarded, names no table, and neither does one written
     * as a type variable, whose erasure is a bound the source never wrote.
     */
    private static String roleOf(ClassfileCensus.ParameterAt at, ClassAncestry ancestry) {
        if (ancestry.isA(at.type(), JOOQ_DSL_CONTEXT)) {
            return "DSL_CONTEXT";
        }
        if (!ancestry.isA(at.type(), JOOQ_TABLE)) {
            return "OTHER";
        }
        String root = rootClass(at);
        return root == null || JOOQ_TABLE.equals(root) ? "TABLE_ANY" : TABLE_CONCRETE;
    }

    /** The class the declaration names at the root of the type, or null where it names none. */
    private static String rootClass(ClassfileCensus.ParameterAt at) {
        return at.typeRefs().stream()
            .filter(ref -> ref.path().isEmpty())
            .map(ClassfileCensus.TypeRefAt::referencedClass)
            .findFirst()
            .orElse(null);
    }

    /**
     * The catalog's tables by the generated class an author's parameter would name, across every
     * partition the store holds.
     *
     * <p>Across partitions rather than within a graph's, because a code row is shared between the
     * graphs that read its entry and must not mean different things to two of them. The class name
     * is what makes that safe: a generated class belongs to exactly one package and a package to
     * one partition, so at most one partition answers. A name two partitions do claim is a genuine
     * ambiguity, and it is dropped rather than guessed, which reads downstream as a concrete
     * position the catalog could not resolve.
     */
    private static Map<String, TableAt> tablesByClass(DSLContext dsl) {
        var byClass = new HashMap<String, TableAt>();
        var ambiguous = new HashSet<String>();
        dsl.select(SQL_TABLE.CLASS_FQN, SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA,
                SQL_TABLE.TABLE_NAME)
            .from(SQL_TABLE)
            .forEach(row -> {
                String fqn = row.value1();
                var at = new TableAt(row.value2(), row.value3(), row.value4());
                var seen = byClass.put(fqn, at);
                if (seen != null && !seen.equals(at)) {
                    ambiguous.add(fqn);
                }
            });
        ambiguous.forEach(byClass::remove);
        return byClass;
    }




    /** What a declared type finally hands back, and whether it hands back many. */
    private record Delivery(String elementClass, boolean deliversMany) {}

    /** One container the delivery walk peels: which position carries the payload, and whether
     *  arriving through it means many rather than one. */
    private record Container(String elementIndex, boolean multiplies) {}

    /**
     * The container vocabulary, read from the relation that states it rather than spelled again.
     *
     * <p>It is a constant with no declared owner, so reading it crosses no ownership and waits on
     * no gatherer; what it buys is that the list of containers exists once. When the view that
     * peels them at read time retires, the vocabulary moves with the decision rather than being
     * copied to follow it.
     */
    private static Map<String, Container> deliveryContainers(DSLContext dsl) {
        var c = INTENT_DELIVERY_CONTAINER;
        return dsl.select(c.CONTAINER_CLASS, c.ELEMENT_INDEX, c.MULTIPLIES)
            .from(c)
            .fetchMap(Record3::value1, row -> new Container(row.value2(), row.value3()));
    }

    /**
     * The class a declared type arrives at once every delivery container is peeled off it, or null
     * where it names no class at all.
     *
     * <p>A loop rather than a fixed number of steps, which is the whole of what moving this to
     * capture buys beyond the cost: the rule stated in SQL has to be unrolled, so it answers to a
     * depth and stops. Peeling ends where the type is not a container, and also where a container's
     * payload position names nothing, which is what an unbounded wildcard is: a reader of
     * {@code List<?>} is given the list, because that is as far as the declaration goes.
     */
    private static Delivery deliveryOf(List<ClassfileCensus.TypeRefAt> refs,
                                       Map<String, Container> containers) {
        var byPath = new HashMap<String, String>();
        for (ClassfileCensus.TypeRefAt ref : refs) {
            byPath.put(ref.path(), ref.referencedClass());
        }
        String element = byPath.get("");
        if (element == null) {
            return null;
        }
        String path = "";
        boolean many = false;
        var walked = new HashSet<String>();
        while (walked.add(path)) {
            var container = containers.get(element);
            if (container == null) {
                break;
            }
            String next = path.isEmpty() ? container.elementIndex()
                : path + "." + container.elementIndex();
            String at = byPath.get(next);
            if (at == null) {
                break;
            }
            many |= container.multiplies();
            path = next;
            element = at;
        }
        return new Delivery(element, many);
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
        for (org.jooq.Table<?> table : List.of(
                CODE_CONDITION_METHOD_PARAMETER_TABLE,
                CODE_METHOD_PARAMETER, CODE_METHOD_EXCEPTION, CODE_WRITE_SLOT, CODE_CONSTRUCTION,
                CODE_TYPE_SLOT,
                CODE_SERVICE_METHOD, CODE_CONDITION_METHOD, CODE_EXTERNAL_FIELD_METHOD,
                CODE_METHOD, CODE_TYPE_ELEMENT, CODE_TYPE,
                CODE_THROWABLE_SUPERTYPE, CODE_THROWABLE, CODE_SCALAR_CONSTANT)) {
            dsl.deleteFrom(table)
                .where(table.field(CODE_METHOD.SOURCE_NAME).in(sources))
                .and(table.field(CODE_METHOD.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }
}
