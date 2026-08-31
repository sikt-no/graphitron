package no.sikt.graphitron.model;

import no.sikt.graphitron.model.catalog.GrainSentence;
import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.META_GATHERER_CORPUS;
import static no.sikt.graphitron.model.Tables.META_GATHERER_DEPENDENCY;
import static no.sikt.graphitron.model.Tables.META_GRAIN;
import static no.sikt.graphitron.model.Tables.META_RELATION;
import static no.sikt.graphitron.model.Tables.META_RELATION_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Closes the declaration rosters around {@code meta_relation} against the observed schema: the
 * cross-row and cross-relation halves of the model whose per-row half the DDL's own keys and
 * {@code CHECK} constraints already hold.
 *
 * <p>The undeclared roster is the migration's ratchet. Every relation the schema declares either
 * carries a {@code meta_relation} row or stands on the frozen roster in
 * {@code undeclared-relations.txt}, which only shrinks: declaring a relation removes its line, and
 * a new relation is on no frozen roster, so it cannot arrive undeclared. The prose gates bind per
 * declared row, so they tighten as the roster drains rather than waiting for it to empty.
 */
class MetaDeclarationGateTest {

    @Test
    @DisplayName("every declaration names an observed relation, and the undeclared roster only shrinks")
    void theUndeclaredRosterOnlyShrinks() {
        withStore(dsl -> {
            var observed = new HashSet<>(dsl.select(META_RELATION_FAMILY.RELATION_NAME)
                .from(META_RELATION_FAMILY).fetch(0, String.class));
            var declared = new HashSet<>(dsl.select(META_RELATION.RELATION_NAME)
                .from(META_RELATION).fetch(0, String.class));

            assertThat(declared)
                .as("declarations naming relations the schema does not declare")
                .isSubsetOf(observed);

            var undeclared = observed.stream()
                .filter(relation -> !declared.contains(relation))
                .collect(Collectors.toSet());
            assertThat(undeclared)
                .as("the observed relations with no meta_relation row, against the frozen roster;"
                    + " a missing entry is a new relation that must be declared rather than added"
                    + " to the roster, an extra entry is a declared or retired relation whose line"
                    + " must be removed")
                .containsExactlyInAnyOrderElementsOf(frozenRoster());
        });
    }

    @Test
    @DisplayName("a declared relation's comment is its grain sentence and its example, verbatim")
    void theCommentEchoesTheDeclaration() {
        withStore(dsl -> assertThat(echoOffenders(dsl))
            .as("declared relations whose comment and declaration drift, or whose grain_text"
                + " is not a single terminated sentence")
            .isEmpty());
    }

    @Test
    @DisplayName("a declared relation's grain lives in a corpus its owner reads, where the owner reads any")
    void ownerAndGrainAgreeAboutTheCorpus() {
        withStore(dsl -> assertThat(corpusOffenders(dsl))
            .as("declared relations whose grain lives in a corpus their owner does not read;"
                + " an owner with no corpus rows is exempt, crossing being its job")
            .isEmpty());
    }

    /**
     * The fact model page's ownership rule as a query: a view belongs to its owner, so what it
     * reads must be the owner's own or something the owner declares a dependency on. Binds only
     * where both ends are declared, so its reach grows with the migration; the hand-written
     * producers read through jOOQ code no stored definition exposes, and stay
     * {@code CaptureCorpusIsolationTest}'s to cover.
     */
    @Test
    @DisplayName("a declared view reads only relations its owner owns or depends on")
    void aDeclaredViewReadsOnlyWhatItsOwnerMay() {
        withStore(dsl -> assertThat(viewOffenders(dsl))
            .as("declared views reading outside their owner's declared dependency set")
            .isEmpty());
    }

    @Test
    @DisplayName("a declared base table's primary key is its grain expressed as columns")
    void aDeclaredTableKeyMatchesItsGrain() {
        withStore(dsl -> assertThat(keyOffenders(dsl))
            .as("declared base tables whose primary key disagrees with their grain's key shape;"
                + " an unkeyed declared table owes a key before it owes anything else")
            .isEmpty());
    }

    /**
     * The prose, corpus, ownership and key gates all bind per declared row, so this case seeds
     * declarations of its own and proves each gate detects its own violation rather than passing
     * because it never ran. The subjects are real relations of the booted store; only the meta
     * rows are the test's, and the store's shipped declarations are cleared first so a subject
     * this case wants to state something false about stays available as the migration declares
     * relation after relation.
     */
    @Test
    @DisplayName("the gates detect what they claim to, on seeded declarations")
    void theGatesDetectWhatTheyClaimTo() {
        withStore(dsl -> {
            dsl.deleteFrom(META_RELATION).execute();
            dsl.deleteFrom(META_GRAIN).execute();
            dsl.insertInto(META_GRAIN)
                .values("database-table", "One table of one database catalog.",
                    "source_name, table_schema, table_name", "catalog")
                .values("sdl-something", "One thing of the SDL corpus.", "graph_name", "sdl")
                .execute();

            // A well-formed declaration of sql_table, comment re-stated to match: no offenders.
            dsl.insertInto(META_RELATION)
                .values("sql_table", "database-table", "catalog",
                    "One table the consumer database declares.", "For example public.film.",
                    "Written table references need ground to resolve against.")
                .execute();
            dsl.execute("COMMENT ON TABLE sql_table IS 'One table the consumer database declares."
                + " For example public.film.'");
            assertThat(echoOffenders(dsl)).as("a matching echo passes").isEmpty();
            assertThat(corpusOffenders(dsl)).as("a grain in the owner's corpus passes").isEmpty();
            assertThat(keyOffenders(dsl)).as("a key matching the grain shape passes").isEmpty();

            // Drift the comment: the echo gate names it.
            dsl.execute("COMMENT ON TABLE sql_table IS 'Something else entirely.'");
            assertThat(echoOffenders(dsl)).as("a drifted comment is an offender").hasSize(1);

            // A grain_text of two sentences: the sentence gate names it even when the echo holds.
            dsl.update(META_RELATION)
                .set(META_RELATION.GRAIN_TEXT, "One table. The consumer database declares it.")
                .where(META_RELATION.RELATION_NAME.eq("sql_table"))
                .execute();
            dsl.execute("COMMENT ON TABLE sql_table IS 'One table. The consumer database declares"
                + " it. For example public.film.'");
            assertThat(echoOffenders(dsl)).as("a two-sentence grain_text is an offender").hasSize(1);

            // An SDL-corpus grain under the catalog gatherer: the corpus gate names it.
            dsl.update(META_RELATION)
                .set(META_RELATION.GRAIN_NAME, "sdl-something")
                .where(META_RELATION.RELATION_NAME.eq("sql_table"))
                .execute();
            assertThat(corpusOffenders(dsl))
                .as("a grain outside the owner's corpora is an offender").hasSize(1);
            // The same grain under the derivation gatherer, which reads no corpus: exempt.
            dsl.update(META_RELATION)
                .set(META_RELATION.OWNER_NAME, "derivation")
                .where(META_RELATION.RELATION_NAME.eq("sql_table"))
                .execute();
            assertThat(corpusOffenders(dsl)).as("an owner with no corpus rows is exempt").isEmpty();
            // And the key gate: sql_table's real key is not graph_name.
            assertThat(keyOffenders(dsl))
                .as("a key disagreeing with the grain shape is an offender").hasSize(1);

            // A declared view reading a relation owned by a gatherer outside its owner's
            // dependency set: the ownership gate names it, and the declared edge clears it.
            // meta_relation_family reads meta_family and meta_prefixless_relation by definition.
            dsl.insertInto(META_GRAIN)
                .values("observed-relation", "One relation the schema declares.",
                    "relation_name", "catalog")
                .execute();
            dsl.insertInto(META_RELATION)
                .values("meta_family", "observed-relation", "catalog",
                    "One family.", "For example sql_.", "The roster the census closes against.")
                .values("meta_relation_family", "observed-relation", "compile",
                    "One relation.", "For example sql_table.", "The census itself.")
                .execute();
            assertThat(viewOffenders(dsl))
                .as("a view reading another owner's relation with no declared edge")
                .isNotEmpty();
            dsl.insertInto(META_GATHERER_DEPENDENCY).values("compile", "catalog").execute();
            assertThat(viewOffenders(dsl))
                .as("the declared dependency edge clears the read")
                .isEmpty();
        });
    }

    /**
     * The vacancy gate the family roster already has: a grain nothing declares is inventory that
     * outlived its relation, or arrived ahead of one, and either way a diff should show it. A
     * grain is minted beside its first declared relation, never speculatively.
     */
    @Test
    @DisplayName("every grain has a declared relation at it")
    void everyGrainHasADeclaredRelation() {
        withStore(dsl -> {
            var used = new HashSet<>(dsl.selectDistinct(META_RELATION.GRAIN_NAME)
                .from(META_RELATION).fetch(0, String.class));
            var vacant = dsl.select(META_GRAIN.GRAIN_NAME).from(META_GRAIN)
                .fetch(0, String.class).stream()
                .filter(grain -> !used.contains(grain))
                .toList();
            assertThat(vacant)
                .as("grains no declared relation is at; a roster entry outlived its relations")
                .isEmpty();
        });
    }

    // ===== The gates' own queries, shared with the seeded detection case =====

    /** Echo and sentence findings: comment differs from the declaration, or grain_text is not one sentence. */
    private static List<String> echoOffenders(DSLContext dsl) {
        var comments = relationComments(dsl);
        var offenders = new ArrayList<String>();
        dsl.select(META_RELATION.RELATION_NAME, META_RELATION.GRAIN_TEXT, META_RELATION.EXAMPLE)
            .from(META_RELATION)
            .fetch()
            .forEach(row -> {
                String expected = row.value2() + " " + row.value3();
                String actual = comments.get(row.value1());
                if (!expected.equals(actual)) {
                    offenders.add(row.value1() + " comments '" + actual
                        + "' but declares '" + expected + "'");
                } else if (!GrainSentence.of(expected).equals(row.value2())) {
                    offenders.add(row.value1() + "'s grain_text is not the one sentence"
                        + " GrainSentence extracts from its comment: '" + row.value2() + "'");
                }
            });
        return offenders;
    }

    /** Relations whose grain lives outside every corpus their owner reads; ownerless of corpus is exempt. */
    private static List<String> corpusOffenders(DSLContext dsl) {
        var corporaByGatherer = new HashMap<String, Set<String>>();
        dsl.select(META_GATHERER_CORPUS.GATHERER_NAME, META_GATHERER_CORPUS.CORPUS_NAME)
            .from(META_GATHERER_CORPUS)
            .fetch()
            .forEach(row -> corporaByGatherer
                .computeIfAbsent(row.value1(), k -> new HashSet<>()).add(row.value2()));
        return dsl
            .select(META_RELATION.RELATION_NAME, META_RELATION.OWNER_NAME, META_GRAIN.CORPUS_NAME)
            .from(META_RELATION)
            .join(META_GRAIN).on(META_GRAIN.GRAIN_NAME.eq(META_RELATION.GRAIN_NAME))
            .fetch().stream()
            .filter(row -> {
                var read = corporaByGatherer.get(row.value2());
                return read != null && !read.isEmpty() && !read.contains(row.value3());
            })
            .map(row -> row.value1() + " is owned by " + row.value2()
                + " but its grain lives in " + row.value3())
            .toList();
    }

    /** Declared views reading a declared relation of another owner with no declared edge. */
    private static List<String> viewOffenders(DSLContext dsl) {
        var ownerByRelation = new HashMap<String, String>();
        dsl.select(META_RELATION.RELATION_NAME, META_RELATION.OWNER_NAME)
            .from(META_RELATION).fetch()
            .forEach(row -> ownerByRelation.put(row.value1(), row.value2()));
        var dependencies = new HashMap<String, Set<String>>();
        dsl.select(META_GATHERER_DEPENDENCY.GATHERER_NAME, META_GATHERER_DEPENDENCY.DEPENDS_ON)
            .from(META_GATHERER_DEPENDENCY).fetch()
            .forEach(row -> dependencies
                .computeIfAbsent(row.value1(), k -> new HashSet<>()).add(row.value2()));
        var declaredViews = dsl.select(META_RELATION.RELATION_NAME, META_RELATION.OWNER_NAME)
            .from(META_RELATION)
            .join(META_RELATION_FAMILY)
            .on(META_RELATION_FAMILY.RELATION_NAME.eq(META_RELATION.RELATION_NAME))
            .where(META_RELATION_FAMILY.RELATION_TYPE.eq("VIEW"))
            .fetch();

        var offenders = new ArrayList<String>();
        for (var view : declaredViews) {
            var mayRead = dependencies.getOrDefault(view.value2(), Set.of());
            for (String read : ViewReferences.relationsReadBy(dsl, view.value1())) {
                String readOwner = ownerByRelation.get(read);
                if (readOwner != null && !readOwner.equals(view.value2())
                    && !mayRead.contains(readOwner)) {
                    offenders.add(view.value1() + " (owned by " + view.value2() + ") reads "
                        + read + " (owned by " + readOwner + ")");
                }
            }
        }
        return offenders;
    }

    /** Declared base tables whose primary key disagrees with their grain's key shape. */
    private static List<String> keyOffenders(DSLContext dsl) {
        var keyShapes = primaryKeyShapes(dsl);
        return dsl
            .select(META_RELATION.RELATION_NAME, META_GRAIN.GRAIN_NAME, META_GRAIN.KEY_SHAPE)
            .from(META_RELATION)
            .join(META_GRAIN).on(META_GRAIN.GRAIN_NAME.eq(META_RELATION.GRAIN_NAME))
            .join(META_RELATION_FAMILY)
            .on(META_RELATION_FAMILY.RELATION_NAME.eq(META_RELATION.RELATION_NAME))
            .where(META_RELATION_FAMILY.RELATION_TYPE.eq("BASE TABLE"))
            .fetch().stream()
            .filter(row -> !row.value3().equals(keyShapes.get(row.value1())))
            .map(row -> row.value1() + " is keyed on '" + keyShapes.get(row.value1())
                + "' but its grain " + row.value2() + " states '" + row.value3() + "'")
            .toList();
    }

    // ===== Reading the observed schema =====

    private static void withStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    /** The frozen roster: one relation name per line, shrink-only, frozen 2026-08-30. */
    private static List<String> frozenRoster() {
        try (InputStream in = MetaDeclarationGateTest.class
            .getResourceAsStream("undeclared-relations.txt")) {
            assertThat(in).as("undeclared-relations.txt beside this test").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every relation's comment, views included, lowercased names, from the engine's catalog. */
    private static Map<String, String> relationComments(DSLContext dsl) {
        var comments = new LinkedHashMap<String, String>();
        dsl.select(field(name("TABLE_NAME"), String.class), field(name("REMARKS"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .fetch()
            .forEach(row -> comments.put(row.value1().toLowerCase(Locale.ROOT), row.value2()));
        return comments;
    }

    /** Every keyed base table's primary key as a comma-separated column list in key order. */
    private static Map<String, String> primaryKeyShapes(DSLContext dsl) {
        var columns = new LinkedHashMap<String, List<String>>();
        dsl.select(field(name("TC", "TABLE_NAME"), String.class),
                field(name("KCU", "COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("TC"))
            .join(table(name("INFORMATION_SCHEMA", "KEY_COLUMN_USAGE")).as("KCU"))
            .on(field(name("KCU", "CONSTRAINT_NAME"), String.class)
                .eq(field(name("TC", "CONSTRAINT_NAME"), String.class)))
            .where(field(name("TC", "CONSTRAINT_TYPE"), String.class).eq("PRIMARY KEY"))
            .and(field(name("TC", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .orderBy(field(name("KCU", "ORDINAL_POSITION"), Integer.class))
            .fetch()
            .forEach(row -> columns
                .computeIfAbsent(row.value1().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(row.value2().toLowerCase(Locale.ROOT)));
        var shapes = new LinkedHashMap<String, String>();
        columns.forEach((tableName, cols) -> shapes.put(tableName, String.join(", ", cols)));
        return shapes;
    }
}
