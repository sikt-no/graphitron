package no.sikt.graphitron.model.capture.classpath;

import no.sikt.graphitron.model.run.GraphitronStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.jooq.DSLContext;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_FIELD;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_CLASSFILE_SUPERTYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classpath census, against real classfiles.
 *
 * <p>The claim these stand up is what the census does <em>not</em> do: it applies no consumer's
 * vocabulary. A relation narrowed to one directive's needs when the bytes were read cannot be
 * widened by a later reader, so the assertions here are mostly about things being present that no
 * graphitron directive would ask for.
 */
class ClasspathFactCaptureTest {

    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final LocalDateTime SECOND = FIRST.plusMinutes(1);

    /** This module's own compiled classes, which every test tier has on disk by the time it runs. */
    private static final List<Path> ENTRY = List.of(Path.of("target", "classes"));

    @Test
    @DisplayName("classes, their supertypes, methods and parameters are written")
    void theClassfilesAreWritten() {
        withCensus(FIRST, dsl -> {
            assertThat(dsl.select(JVM_CLASSFILE.CLASS_KIND).from(JVM_CLASSFILE)
                    .where(JVM_CLASSFILE.CLASS_NAME
                        .eq("no.sikt.graphitron.model.run.GraphitronStore"))
                    .fetchOne(JVM_CLASSFILE.CLASS_KIND))
                .as("a class of this module, read off its own flags").isEqualTo("CLASS");

            assertThat(dsl.fetchCount(JVM_CLASSFILE_METHOD,
                    JVM_CLASSFILE_METHOD.CLASS_NAME
                        .eq("no.sikt.graphitron.model.run.GraphitronStore")
                        .and(JVM_CLASSFILE_METHOD.METHOD_NAME.eq("capture"))))
                .as("its public method").isEqualTo(1);

            assertThat(dsl.select(JVM_CLASSFILE_PARAMETER.PARAMETER_TYPE)
                    .from(JVM_CLASSFILE_PARAMETER)
                    .where(JVM_CLASSFILE_PARAMETER.METHOD_NAME.eq("capture"))
                    .and(JVM_CLASSFILE_PARAMETER.CLASS_NAME
                        .eq("no.sikt.graphitron.model.run.GraphitronStore"))
                    .orderBy(JVM_CLASSFILE_PARAMETER.POSITION)
                    .fetch(JVM_CLASSFILE_PARAMETER.PARAMETER_TYPE))
                .as("and its parameters, in order, by their erased binary names")
                .containsExactly("no.sikt.graphitron.model.boot.GraphitronModelStore",
                    "no.sikt.graphitron.model.run.GraphIdentity",
                    "no.sikt.graphitron.model.run.SubjectConfig", "java.util.List",
                    "no.sikt.graphitron.model.jooq.JooqCatalog");
        });
    }

    /**
     * The point of the whole exercise: a field is recorded whatever its type. The relation this
     * replaces held only fields typed {@code graphql.schema.GraphQLScalarType}, so its population
     * was a fact about one GraphQL directive rather than about the classpath.
     */
    @Test
    @DisplayName("a field is recorded whatever its type, so no directive decides the population")
    void everyPublicFieldIsRecorded() {
        withCensus(FIRST, dsl -> {
            var types = dsl.selectDistinct(JVM_CLASSFILE_FIELD.FIELD_TYPE)
                .from(JVM_CLASSFILE_FIELD)
                .fetch(JVM_CLASSFILE_FIELD.FIELD_TYPE);

            assertThat(types)
                .as("many types, not the one a directive consumes")
                .hasSizeGreaterThan(1);
            assertThat(dsl.fetchCount(JVM_CLASSFILE_FIELD,
                    JVM_CLASSFILE_FIELD.IS_STATIC.isTrue()))
                .as("and the static ones are told apart rather than being the only ones kept")
                .isPositive();
        });
    }

    /** Object is left out of the supertype rows: the JVM writes it where no author did. */
    @Test
    @DisplayName("java.lang.Object is not recorded as a declared supertype")
    void objectIsNotADeclaredSupertype() {
        withCensus(FIRST, dsl ->
            assertThat(dsl.fetchCount(JVM_CLASSFILE_SUPERTYPE,
                    JVM_CLASSFILE_SUPERTYPE.SUPERTYPE_NAME.eq("java.lang.Object")))
                .as("what remains is the extends clause an author typed").isZero());
    }

    /** A package the caller excludes is left out, which is the caller's policy and not a rule here. */
    @Test
    @DisplayName("an excluded package is left out of the census")
    void anExcludedPackageIsLeftOut() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            ClasspathFactCapture.capture(dsl, ENTRY, "no.sikt.graphitron.model.capture", FIRST);

            assertThat(dsl.fetchCount(JVM_CLASSFILE,
                    JVM_CLASSFILE.CLASS_NAME.like("no.sikt.graphitron.model.capture.%")))
                .as("nothing under the excluded package").isZero();
            assertThat(dsl.fetchCount(JVM_CLASSFILE,
                    JVM_CLASSFILE.CLASS_NAME.like("no.sikt.graphitron.model.run.%")))
                .as("and everything else still there").isPositive();
        }
    }

    /**
     * A second reading of the same entry leaves the same rows, and a class the entry no longer
     * declares goes. A wrong sweep predicate empties the census on the second pass rather than the
     * first, which one capture cannot see.
     */
    @Test
    @DisplayName("reading twice restamps, and a class the entry no longer holds is swept")
    void readingTwiceRestampsAndSweeps() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            ClasspathFactCapture.capture(dsl, ENTRY, null, FIRST);
            int afterFirst = dsl.fetchCount(JVM_CLASSFILE);
            String source = dsl.select(JVM_CLASSFILE.SOURCE_NAME).from(JVM_CLASSFILE)
                .limit(1).fetchOne(JVM_CLASSFILE.SOURCE_NAME);
            dsl.insertInto(JVM_CLASSFILE, JVM_CLASSFILE.SOURCE_NAME,
                    JVM_CLASSFILE.CLASS_NAME, JVM_CLASSFILE.CLASS_KIND, JVM_CLASSFILE.TOUCHED_AT)
                .values(source, "com.example.Deleted", "CLASS", FIRST.minusDays(1))
                .execute();

            ClasspathFactCapture.capture(dsl, ENTRY, null, SECOND);

            assertThat(dsl.fetchCount(JVM_CLASSFILE))
                .as("the same classes, not doubled and not emptied").isEqualTo(afterFirst);
            assertThat(dsl.fetchCount(JVM_CLASSFILE,
                    JVM_CLASSFILE.CLASS_NAME.eq("com.example.Deleted")))
                .as("and what the entry no longer declares is gone").isZero();
        }
    }

    /**
     * A census over this module's own classes, handed to the body as a reader. The body gets the
     * context rather than the store: what these cases are about is the rows, and a case holding
     * the store would be one that could close or reopen it.
     */
    private static void withCensus(LocalDateTime touchedAt, Consumer<DSLContext> body) {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            ClasspathFactCapture.capture(dsl, ENTRY, null, touchedAt);
            body.accept(dsl);
        }
    }
}
