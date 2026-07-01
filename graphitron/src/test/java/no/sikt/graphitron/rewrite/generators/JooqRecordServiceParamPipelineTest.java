package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R311 pipeline tier: a jOOQ {@code TableRecord} bound <em>directly</em> as a {@code @service} input
 * param (singular {@code Record} or {@code List<Record>}), not as a member of an input bean. The
 * param's SDL input type names jOOQ columns through {@code @field(name:)} and carries a {@code @nodeId}
 * identity that decodes into the record's scalar key, so the param binds on the column axis +
 * scalar-key decode (a {@code CallSiteExtraction.JooqRecord}) and a {@code create<Record>} helper is
 * emitted — never bean-ified on the Java-member axis (the misleading "has no fields matching" the bean
 * path produced on {@code main}).
 *
 * <p>Covers both cardinalities (the consumer's motivating shape is the list), single- and composite-key
 * identity, the regression pin for the original bug, the child-{@code @service} coordinate (the
 * {@code ArgCallEmitter} real arm), and the rejection set. No generated-body string assertions: the
 * presence of the {@code create<Record>} helper plus the carrier's resolved shape is the structural
 * pin. Backed by the real test jOOQ records and {@code TestServiceStub.modifyFilm*} methods.
 */
@PipelineTier
class JooqRecordServiceParamPipelineTest {

    private static final String FILM_RECORD_FQN =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord";
    private static final String FILM_ACTOR_RECORD_FQN =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord";

    private static final String SINGLE_KEY_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
            releaseYear: Int @field(name: "release_year")
        }
        type Query {
            modifyFilm(in: ModifyFilmInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
        }
        """;

    private static final String COMPOSITE_KEY_SDL = """
        type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
        input ModifyFilmActorInput {
            id: ID! @nodeId(typeName: "FilmActor")
        }
        type Query {
            modifyFilmActor(in: ModifyFilmActorInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmActorRecord"})
        }
        """;

    private static final String LIST_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
        }
        type Query {
            modifyFilms(in: [ModifyFilmInput!]!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecords"})
        }
        """;

    @Test
    void singularRecordParam_classifiesToJooqRecord_withColumnsAndKeyDecode() {
        // The param classifies to a JooqRecord carrier (not a Direct, not an InputBean): asserting the
        // arm is itself the pin that it did not bean-ify or stay Direct. table, the two column bindings,
        // and the single-key identity decode are all read off the carrier.
        var jr = carrier(SINGLE_KEY_SDL, "modifyFilm", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_RECORD_FQN);
        assertThat(jr.columnBindings())
            .as("the two plain @field columns bind on the column axis, each a resolved ColumnRef")
            .extracting(cb -> cb.leaf() + "->" + cb.column().sqlName())
            .containsExactly("title->title", "releaseYear->release_year");
        assertThat(jr.keyDecodes()).as("the @nodeId field is the record's identity").hasSize(1);
        var kd = jr.keyDecodes().get(0);
        assertThat(kd.leaf()).as("the decode carries its own Map key").isEqualTo("filmId");
        assertThat(kd.typeId()).isEqualTo("Film");
        assertThat(kd.targetColumns()).extracting(c -> c.sqlName()).containsExactly("film_id");
        assertThat(kd.nonNull()).as("filmId is ID! → non-null identity").isTrue();
    }

    @Test
    void singularRecordParam_emitsCreateRecordHelperOnFetchersClass() {
        // The create<Record> helper's presence is the structural signal that the param classified to a
        // JooqRecord (and that emission did not throw): a Direct/bean path emits no such helper.
        var fetchers = findSpec("QueryFetchers", SINGLE_KEY_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .contains("createFilmRecord");
    }

    @Test
    void compositeKeyRecordParam_keyDecodeCarriesBothColumns() {
        // film_actor PK is (actor_id, film_id): the identity decode carries both, arity 2. The input
        // has no plain @field columns, so columnBindings is empty and the keyDecode satisfies the
        // at-least-one-binding floor on its own.
        var jr = carrier(COMPOSITE_KEY_SDL, "modifyFilmActor", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_ACTOR_RECORD_FQN);
        assertThat(jr.columnBindings()).isEmpty();
        assertThat(jr.keyDecodes()).hasSize(1);
        assertThat(jr.keyDecodes().get(0).targetColumns())
            .extracting(c -> c.sqlName())
            .containsExactly("actor_id", "film_id");
        assertThat(findSpec("QueryFetchers", COMPOSITE_KEY_SDL).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("createFilmActorRecord");
    }

    @Test
    void listRecordParam_derivesListOfJooqRecordInput_andEmitsBothHelpers() {
        // The consumer's real shape (List<…Record> against [Input!]!). The derived ValueShape is
        // ListOf(JooqRecordInput) — the pin that cardinality is handled, not dropped — and the list
        // call site routes through create<Record>List, which delegates per element to the SAME singular
        // create<Record> (one construction site, mapped). Both helpers land on the class.
        var field = TestSchemaHelper.buildSchema(LIST_SDL).field("Query", "modifyFilms");
        var shape = fromArgShape((ServiceField) field);
        assertThat(shape).as("a List<Record> param derives a ListOf wrap").isInstanceOf(ValueShape.ListOf.class);
        var element = ((ValueShape.ListOf) shape).elementShape();
        assertThat(element).isInstanceOf(ValueShape.JooqRecordInput.class);
        assertThat(((ValueShape.JooqRecordInput) element).carrier().table().recordClass().toString())
            .isEqualTo(FILM_RECORD_FQN);
        assertThat(findSpec("QueryFetchers", LIST_SDL).methodSpecs())
            .extracting(MethodSpec::name)
            .as("the list variant plus the singular helper it delegates to")
            .contains("createFilmRecordList", "createFilmRecord");
    }

    @Test
    void listRecordParam_doesNotRegressToHasNoFieldsMatching() {
        // The red-test for the original bug: a List<FilmRecord> param whose input type carries only
        // @nodeId + @field (no Java-member-name overlap) must classify to JooqRecord, never an
        // UnclassifiedField "has no fields matching" — the proof the list shape no longer reaches
        // buildInputBean.
        var field = TestSchemaHelper.buildSchema(LIST_SDL).field("Query", "modifyFilms");
        assertThat(field)
            .as("the List<Record> param binds, it does not bean-ify and reject")
            .isNotInstanceOf(UnclassifiedField.class);
    }

    // ===== Child @service coordinate (ArgCallEmitter real arm) =====

    private static final String CHILD_SDL = """
        type Language implements Node @table(name: "language") @node { id: ID! name: String }
        type Film implements Node @table(name: "film") @node {
            id: ID!
            modifiedLanguage(in: ModifyFilmInput!): Language
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childModifyFilmRecord"})
        }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
        }
        type Query { film: Film }
        """;

    @Test
    void childServiceCoordinate_argClassifiesToJooqRecord_andEmitsHelperViaArgCallEmitter() {
        // The parity pin for the ArgCallEmitter arm: enrich runs for child @service too, so the
        // FilmRecord arg on a @table-parent child field classifies to JooqRecord exactly as the root
        // param does. The createFilmRecord helper on FilmFetchers is the proof that the child rows-
        // method's call site went through the ArgCallEmitter real arm (not a throw, not the old "has no
        // fields matching"). The binding is coordinate-agnostic.
        var field = TestSchemaHelper.buildSchema(CHILD_SDL).field("Film", "modifiedLanguage");
        assertThat(field)
            .as("the child @service field classifies (the arg binds, it does not reject)")
            .isNotInstanceOf(UnclassifiedField.class);
        assertThat(field).isInstanceOf(MethodBackedField.class);
        var jooqArg = ((MethodBackedField) field).method().callParams().stream()
            .map(CallParam::extraction)
            .filter(e -> e instanceof CallSiteExtraction.JooqRecord)
            .map(e -> (CallSiteExtraction.JooqRecord) e)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no JooqRecord arg on the child @service rows-method"));
        assertThat(jooqArg.table().recordClass().toString()).isEqualTo(FILM_RECORD_FQN);
        assertThat(findSpec("FilmFetchers", CHILD_SDL).methodSpecs())
            .extracting(MethodSpec::name)
            .as("the child rows-method's createFilmRecord helper lands on the parent's *Fetchers class")
            .contains("createFilmRecord");
    }

    // ===== Rejections (honest, validate-time UnclassifiedField) =====

    @Test
    void foreignTableNodeIdWithNoFk_rejectsWithFkCountMessage() {
        // R315: the param is a FilmRecord, and @nodeId(typeName: "FilmActor") references a different
        // table (film_actor) → the cross-table FK branch. There is no foreign key whose source is `film`
        // referencing `film_actor` (the FK runs the other way, film_actor → film), so the deduction
        // finds zero directional FKs and rejects with the fk-count message. The old R311 "A NodeId
        // cannot be decoded into a different record type" gate (:325) is gone — a foreign table is now a
        // legitimate FK-reference shape when an FK connects them, and an honest zero-FK rejection when not.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
            input ModifyMismatchInput {
                filmId: ID! @nodeId(typeName: "FilmActor")
            }
            type Query {
                modifyMismatch(in: ModifyMismatchInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyMismatch");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("no foreign key found")
            .contains("film")
            .contains("film_actor");
    }

    @Test
    void twoNodeIdFields_classifyAsTwoKeyDecodes() {
        // R315 flips the former twoNodeIdFields_reject: the :266 single-@nodeId gate is gone, so two
        // @nodeId fields are legal. Here both reference Film (== the param record's table), so both are
        // same-table identity decodes resolving to film_id; their overlapping-column value-agreement is
        // R322's runtime concern, deliberately not asserted here. The pin is that classification no
        // longer rejects.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyTwoIdInput {
                a: ID! @nodeId(typeName: "Film")
                b: ID! @nodeId(typeName: "Film")
            }
            type Query {
                modifyTwoId(in: ModifyTwoIdInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyTwoId");
        assertThat(field).as("two @nodeId fields no longer reject").isNotInstanceOf(UnclassifiedField.class);
        var jr = carrier(sdl, "modifyTwoId", false);
        assertThat(jr.keyDecodes()).hasSize(2);
        assertThat(jr.keyDecodes())
            .as("both reference Film, the param's own table → both resolve to its identity column")
            .allSatisfy(kd -> assertThat(kd.targetColumns()).extracting(c -> c.sqlName()).containsExactly("film_id"));
    }

    @Test
    void fieldResolvingToNoColumn_rejectsWithCandidateHint() {
        // The honest replacement for "has no fields matching": a @field naming a non-existent column
        // rejects naming the field, the binding key, and the table, with a Levenshtein candidate hint.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyBadColInput {
                filmId: ID! @nodeId(typeName: "Film")
                bogus: String @field(name: "nonexistent_col")
            }
            type Query {
                modifyBadCol(in: ModifyBadColInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyBadCol");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("nonexistent_col")
            .contains("no")
            .contains("column")
            .contains("did you mean");
    }

    @Test
    void singularJavaParam_againstListArg_rejectsAtCardinalityGate() {
        // A singular FilmRecord Java param against a [Input!] SDL arg is rejected at the shared
        // cardinality-parity gate, before any JooqRecord is built — never emitted as a create<Record>
        // call against a List wire value that throws ClassCastException at runtime.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyFilmInput { filmId: ID! @nodeId(typeName: "Film") }
            type Query {
                modifyFilm(in: [ModifyFilmInput!]): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyFilm");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason()).contains("cardinalities");
    }

    @Test
    void listJavaParam_againstSingularArg_rejectsAtCardinalityGate() {
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyFilmInput { filmId: ID! @nodeId(typeName: "Film") }
            type Query {
                modifyFilms(in: ModifyFilmInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecords"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyFilms");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason()).contains("cardinalities");
    }

    // ===== R315 FK-reference @nodeId (cross-table) =====

    private static final String FILM_ENDORSEMENT_RECORD_FQN =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmEndorsementRecord";

    private static RewriteContext fixtureCtx(String jooqPackage) {
        return new RewriteContext(List.of(), Path.of(""), Path.of(""),
            DEFAULT_OUTPUT_PACKAGE, jooqPackage, Map.of());
    }

    private static final RewriteContext NODEID_CTX = fixtureCtx("no.sikt.graphitron.rewrite.nodeidfixture");
    private static final RewriteContext IDREF_CTX = fixtureCtx("no.sikt.graphitron.rewrite.idreffixture");

    private static final String PURE_FK_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! }
        type Actor implements Node @table(name: "actor") @node { id: ID! }
        input AssignFilmActorInput {
            filmId: ID! @nodeId(typeName: "Film")
            actorId: ID! @nodeId(typeName: "Actor")
        }
        type Query {
            assignFilmActor(in: AssignFilmActorInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmActorRecord"})
        }
        """;

    @Test
    void pureFkReferences_filmActorSmoke_twoKeyDecodesResolveFkChildColumns() {
        // The motivating shape: every @nodeId is an FK reference to another node type. Two keyDecodes
        // resolve (FK deduced). Smoke only — film_actor's PK columns ARE its FK columns, so this cannot
        // discriminate FK resolution from name-match; the renamed-FK fixtures below do that.
        var jr = carrier(PURE_FK_SDL, "assignFilmActor", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_ACTOR_RECORD_FQN);
        assertThat(jr.keyDecodes())
            .extracting(kd -> kd.leaf() + "->" + kd.targetColumns().get(0).sqlName())
            .containsExactlyInAnyOrder("filmId->film_id", "actorId->actor_id");
        assertThat(findSpec("QueryFetchers", PURE_FK_SDL).methodSpecs())
            .extracting(MethodSpec::name).contains("createFilmActorRecord");
    }

    private static final String ENDORSEMENT_FK_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! }
        input EndorseFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            note: String @field(name: "note")
        }
        type Query {
            endorseFilm(in: EndorseFilmInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmEndorsementRecord"})
        }
        """;

    @Test
    void fkConstraintNotNameMatch_filmEndorsement_targetColumnsAreRenamedFkChild() {
        // The real pin: the FK child column (endorsed_film) is named differently from the referenced
        // parent key (film.film_id). FK-constraint resolution must land the decoded Film id on
        // endorsed_film — a name-match shortcut would produce [film_id] or fail.
        var jr = carrier(ENDORSEMENT_FK_SDL, "endorseFilm", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_ENDORSEMENT_RECORD_FQN);
        assertThat(jr.keyDecodes()).hasSize(1);
        var kd = jr.keyDecodes().get(0);
        assertThat(kd.leaf()).isEqualTo("filmId");
        assertThat(kd.targetColumns())
            .as("decoded Film id lands on the renamed FK child column, not a same-named film_id")
            .extracting(c -> c.sqlName()).containsExactly("endorsed_film");
        assertThat(jr.columnBindings())
            .extracting(cb -> cb.leaf() + "->" + cb.column().sqlName()).containsExactly("note->note");
        assertThat(findSpec("QueryFetchers", ENDORSEMENT_FK_SDL).methodSpecs())
            .extracting(MethodSpec::name).contains("createFilmEndorsementRecord");
    }

    @Test
    void tablePresentOnServiceRecordParam_rejectsWithDropTableMessage() {
        // Convergence by rejection (requirement #3): the motivating input WITH @table classifies as a
        // TableInputType (Graphitron-owns-DML), which contradicts a jOOQ-record @service param. Reject
        // honestly ("drop @table") instead of the bean path's misleading "has no fields matching". The
        // same input WITHOUT @table (PURE_FK_SDL above) classifies to the JooqRecord carrier.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            type Actor implements Node @table(name: "actor") @node { id: ID! }
            input AssignFilmActorTableInput @table(name: "film_actor") {
                filmId: ID! @nodeId(typeName: "Film")
                actorId: ID! @nodeId(typeName: "Actor")
            }
            type Query {
                assignFilmActorTable(in: AssignFilmActorTableInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmActorRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "assignFilmActorTable");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("@table")
            .contains("drop @table")
            .doesNotContain("has no fields matching");
    }

    private static final String MIXED_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! }
        type FilmEndorsement implements Node @table(name: "film_endorsement") @node { id: ID! }
        input EditEndorsementInput {
            endorsementId: ID @nodeId(typeName: "FilmEndorsement")
            filmId: ID! @nodeId(typeName: "Film")
            note: String @field(name: "note")
        }
        type Query {
            editEndorsement(in: EditEndorsementInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmEndorsementRecord"})
        }
        """;

    @Test
    void mixedIdentityFkReferenceAndPlainField() {
        // A record carrying its own @nodeId identity (endorsementId → the PK), an FK-reference @nodeId
        // (filmId → the FK child column), and a plain @field (note). Two keyDecodes resolve to different
        // target columns; one ColumnBinding.
        var jr = carrier(MIXED_SDL, "editEndorsement", false);
        assertThat(jr.keyDecodes()).hasSize(2);
        var byField = jr.keyDecodes().stream().collect(toMap(kd -> kd.leaf(), kd -> kd));
        assertThat(byField.get("endorsementId").targetColumns())
            .as("same-table identity → the record's own PK").extracting(c -> c.sqlName()).containsExactly("endorsement_id");
        assertThat(byField.get("endorsementId").nonNull()).as("ID → nullable identity").isFalse();
        assertThat(byField.get("filmId").targetColumns())
            .as("cross-table FK reference → the FK child column").extracting(c -> c.sqlName()).containsExactly("endorsed_film");
        assertThat(byField.get("filmId").nonNull()).as("ID! → non-null reference").isTrue();
        assertThat(jr.columnBindings())
            .extracting(cb -> cb.leaf() + "->" + cb.column().sqlName()).containsExactly("note->note");
    }

    private static final String REORDERED_SDL = """
        type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
        input AssignReorderedInput {
            ref: ID! @nodeId(typeName: "ReorderedPkParent")
        }
        type Query {
            assignReordered(in: AssignReorderedInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyReorderedFkChild"})
        }
        """;

    @Test
    void reorderedFk_targetColumnsReconciledToDecodeOrder() {
        // Composite / reordered-FK decode-order reconciliation (D3): the FK references
        // reordered_pk_parent (pk_b, pk_c, pk_a) while the node key is (pk_a, pk_b, pk_c). The decode's
        // targetColumns must align with node-key (decode) order [fk_a, fk_b, fk_c], NOT the FK
        // declaration order [fk_b, fk_c, fk_a] a positional zip would land on.
        var jr = carrier(REORDERED_SDL, "assignReordered", false, NODEID_CTX);
        assertThat(jr.keyDecodes()).hasSize(1);
        assertThat(jr.keyDecodes().get(0).targetColumns())
            .extracting(c -> c.sqlName()).containsExactly("fk_a", "fk_b", "fk_c");
    }

    @Test
    void selfFkReferenceOnSameTableNodeId_admits_targetsSelfFkChildColumns() {
        // R328 (D2): a same-table @nodeId(typeName: "Email") carrying an explicit @reference names the
        // self-FK email_in_reply_to_fk. R315's same-table reject ("self-reference ... out of scope") is
        // lifted; the case now routes through the same resolveRecordFkTargetColumns the cross-table
        // branch uses, oriented with selfRefFkOnSource=true. The RecordKeyDecode targets the self-FK's
        // child columns (mailbox_id, in_reply_to_no) on the record — NOT the record's own composite PK
        // (mailbox_id, message_no). mailbox_id is the column shared with the cross-table email→mailbox
        // FK; the runtime value-agreement reconciling a second writer on it is R322's, not asserted here.
        var sdl = """
            type Email implements Node @table(name: "email") @node { id: ID! }
            input ReplyEmailInput {
                inReplyTo: ID! @nodeId(typeName: "Email") @reference(path: [{key: "email_in_reply_to_fk"}])
            }
            type Query {
                replyEmail(in: ReplyEmailInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyEmailRecord"})
            }
            """;
        var jr = carrier(sdl, "replyEmail", false);
        assertThat(jr.table().recordClass().toString())
            .isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.EmailRecord");
        assertThat(jr.keyDecodes()).hasSize(1);
        var kd = jr.keyDecodes().get(0);
        assertThat(kd.leaf()).isEqualTo("inReplyTo");
        assertThat(kd.typeId()).isEqualTo("Email");
        assertThat(kd.targetColumns())
            .as("decoded Email key lands on the self-FK child columns, not the record's own PK")
            .extracting(c -> c.sqlName())
            .containsExactly("mailbox_id", "in_reply_to_no");
    }

    @Test
    void nodeKeyColumnNotCoveredByFk_rejects() {
        // child_ref → parent_node FK references the parent's alternate unique column (alt_key), while the
        // ParentNode NodeType's key is pk_id. The named FK therefore does not cover the node key column,
        // which rejects (the cross-table failure mode that the removed :325 gate's hazard relocates to).
        var sdl = """
            type ParentNode implements Node @table(name: "parent_node") @node { id: ID! }
            input AssignChildRefInput {
                parentRef: ID! @nodeId(typeName: "ParentNode") @reference(path: [{key: "child_ref_parent_alt_key_fkey"}])
            }
            type Query {
                assignChildRef(in: AssignChildRefInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyChildRef"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl, NODEID_CTX).field("Query", "assignChildRef");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("pk_id")
            .contains("not covered by foreign key");
    }

    @Test
    void explicitReferenceKey_studierett_selectsNamedFk() {
        // studierett carries TWO FKs to studieprogram; @reference(key:) selects which one a reference
        // @nodeId resolves through. Pins the directive-arg → selected-FK binding the deduced-FK tests
        // cannot reach (studierett.registrar_studieprogram is the renamed FK child column).
        var sdl = """
            type Studieprogram implements Node @table(name: "studieprogram") @node { id: ID! }
            input AssignStudierettInput {
                programId: ID! @nodeId(typeName: "Studieprogram") @reference(path: [{key: "studierett_registrar_studieprogram_fkey"}])
            }
            type Query {
                assignStudierett(in: AssignStudierettInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyStudierett"})
            }
            """;
        var jr = carrier(sdl, "assignStudierett", false, IDREF_CTX);
        assertThat(jr.keyDecodes()).hasSize(1);
        assertThat(jr.keyDecodes().get(0).targetColumns())
            .as("@reference(key:) selects the renamed FK → its child column")
            .extracting(c -> c.sqlName()).containsExactly("registrar_studieprogram");
    }

    @Test
    void nodeIdWithoutTypeName_rejects() {
        // Unchanged from R311: @nodeId on a jOOQ-record param must name its NodeType explicitly (the
        // param record type alone does not name the NodeType to decode against).
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyNoTypeNameInput {
                filmId: ID! @nodeId
            }
            type Query {
                modifyNoTypeName(in: ModifyNoTypeNameInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyNoTypeName");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason()).contains("typeName");
    }

    @Test
    void ambiguousFk_studierett_rejectsWithoutKey() {
        // The same two-FK shape without @reference(key:) is ambiguous → reject, enumerating the candidate
        // FK names so the author can disambiguate.
        var sdl = """
            type Studieprogram implements Node @table(name: "studieprogram") @node { id: ID! }
            input AssignStudierettAmbigInput {
                programId: ID! @nodeId(typeName: "Studieprogram")
            }
            type Query {
                assignStudierettAmbig(in: AssignStudierettAmbigInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyStudierett"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl, IDREF_CTX).field("Query", "assignStudierettAmbig");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("multiple foreign keys")
            .contains("studierett_registrar_studieprogram_fkey")
            .contains("studierett_studieprogram_id_fkey");
    }

    // ===== R336: nested input-object flatten onto the param record's column axis =====

    private static final String NESTED_FLATTEN_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            details: FilmDetailsInput!
        }
        input FilmDetailsInput {
            title: String @field(name: "title")
            releaseYear: Int @field(name: "release_year")
        }
        type Query {
            modifyFilm(in: ModifyFilmInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
        }
        """;

    @Test
    void nestedGroupingInput_flattensColumnsWithTwoElementPaths() {
        // R336: the directiveless FilmDetailsInput group flattens onto the film table. Each @field leaf
        // binds on the column axis carrying the full access path ["details", "<leaf>"]; the top-level
        // @nodeId identity keeps its single-element path. The flatten is transparent — the nested columns
        // bind exactly as top-level ones would, recorded only on the path.
        var jr = carrier(NESTED_FLATTEN_SDL, "modifyFilm", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_RECORD_FQN);
        assertThat(jr.columnBindings())
            .extracting(cb -> String.join(".", cb.path()) + "->" + cb.column().sqlName())
            .containsExactly("details.title->title", "details.releaseYear->release_year");
        assertThat(jr.keyDecodes()).hasSize(1);
        assertThat(jr.keyDecodes().get(0).path())
            .as("the top-level identity keeps its single-element path").containsExactly("filmId");
        assertThat(jr.keyDecodes().get(0).targetColumns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    @Test
    void mixedTopLevelAndNestedColumns_bothBindOnTheirOwnPaths() {
        // A top-level plain @field and a nested-group @field coexist: the top-level carries a
        // single-element path, the nested one a two-element path. Pins that the flatten does not disturb
        // the top-level form (depth-1 paths are byte-identical to pre-R336).
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input ModifyFilmMixedInput {
                filmId: ID! @nodeId(typeName: "Film")
                title: String @field(name: "title")
                yearGroup: FilmYearInput!
            }
            input FilmYearInput { releaseYear: Int @field(name: "release_year") }
            type Query {
                modifyFilmMixed(in: ModifyFilmMixedInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var jr = carrier(sdl, "modifyFilmMixed", false);
        assertThat(jr.columnBindings())
            .extracting(cb -> String.join(".", cb.path()) + "->" + cb.column().sqlName())
            .containsExactly("title->title", "yearGroup.releaseYear->release_year");
    }

    @Test
    void nestedNodeIdDecode_carriesAccessPath() {
        // A @nodeId identity nested inside a directiveless grouping input: the RecordKeyDecode carries the
        // full access path ["identity", "filmId"] and still resolves to the record's own key column.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input WrappedIdentityInput {
                identity: IdentityGroup!
                title: String @field(name: "title")
            }
            input IdentityGroup { filmId: ID! @nodeId(typeName: "Film") }
            type Query {
                wrapIdentity(in: WrappedIdentityInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var jr = carrier(sdl, "wrapIdentity", false);
        assertThat(jr.keyDecodes()).hasSize(1);
        assertThat(jr.keyDecodes().get(0).path()).containsExactly("identity", "filmId");
        assertThat(jr.keyDecodes().get(0).targetColumns()).extracting(c -> c.sqlName()).containsExactly("film_id");
        assertThat(jr.columnBindings())
            .extracting(cb -> String.join(".", cb.path()) + "->" + cb.column().sqlName())
            .containsExactly("title->title");
    }

    @Test
    void deeplyNestedColumn_carriesFullPath() {
        // Two levels of directiveless grouping: the leaf column's path is the whole chain
        // ["outer", "inner", "title"]. Pins that the flatten recurses to arbitrary depth, not just one level.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input DeepInput {
                filmId: ID! @nodeId(typeName: "Film")
                outer: OuterGroup!
            }
            input OuterGroup { inner: InnerGroup! }
            input InnerGroup { title: String @field(name: "title") }
            type Query {
                modifyDeep(in: DeepInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var jr = carrier(sdl, "modifyDeep", false);
        assertThat(jr.columnBindings()).hasSize(1);
        assertThat(jr.columnBindings().get(0).path()).containsExactly("outer", "inner", "title");
        assertThat(jr.columnBindings().get(0).column().sqlName()).isEqualTo("title");
    }

    // ===== R336 rejections (D3 invariants, honest validate-time UnclassifiedField) =====

    @Test
    void cyclicNestedInput_rejects() {
        // A directiveless nested field typed as the outer input reaches itself — a single record cannot
        // represent a recursive input shape (the column-axis analogue of buildInputBean's recursive reject).
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input CycleInput {
                filmId: ID! @nodeId(typeName: "Film")
                self: CycleInput
            }
            type Query {
                modifyCycle(in: CycleInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyCycle");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("cyclic input shape")
            .contains("CycleInput");
    }

    @Test
    void listValuedNestedGrouping_rejects() {
        // A list-shaped nested grouping field (details: [FilmDetailsInput!]) is a cardinality contradiction:
        // a single backing record has one value per column, so a list of column-groups cannot flatten onto it.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input ListNestInput {
                filmId: ID! @nodeId(typeName: "Film")
                details: [FilmDetailsInput!]
            }
            input FilmDetailsInput { title: String @field(name: "title") }
            type Query {
                modifyListNest(in: ListNestInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyListNest");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("list-shaped")
            .contains("one value per column");
    }

    @Test
    void nestedTableInput_rejectsAsSecondDmlTarget() {
        // A nested input carrying @table is a second DML target, not a column group to flatten — reject
        // rather than silently erase the authored directive (compound multi-table mutations are R122's scope).
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            type Language implements Node @table(name: "language") @node { id: ID! }
            input ModifyWithTableInput {
                filmId: ID! @nodeId(typeName: "Film")
                nested: NestedTableInput!
            }
            input NestedTableInput @table(name: "language") { name: String @field(name: "name") }
            type Query {
                modifyWithTable(in: ModifyWithTableInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyWithTable");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("@table")
            .contains("second DML target")
            .contains("R122");
    }

    @Test
    void plainColumnCollisionAcrossNesting_rejects() {
        // Two plain @field leaves — one top-level, one in a nested group — resolving to the same column
        // would last-write-wins silently. Reject, naming both dotted paths and the column. (Decode-vs-decode
        // / decode-vs-column overlaps stay with R322's value-agreement deferral, not checked here.)
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input NestedCollisionInput {
                filmId: ID! @nodeId(typeName: "Film")
                title: String @field(name: "title")
                details: DetailsCollisionInput!
            }
            input DetailsCollisionInput { aka: String @field(name: "title") }
            type Query {
                modifyCollision(in: NestedCollisionInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyCollision");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("both resolve to column 'title'")
            .contains("two fields cannot populate one column")
            .contains("details.aka");
    }

    @Test
    void twoIdentityDecodesOnOneColumn_admitsTwoKeyDecodes_deferredToRuntimeAgreement() {
        // R322: two @nodeId(typeName: "Film") identity fields both resolve to film_id. Unlike two plain
        // @field's (the build-time reject above), an overlap involving a decode is admitted — the runtime
        // value-agreement check (emitted into createFilmRecord) reconciles it. The carrier carries both
        // decodes targeting the one column; the overlap is data-dependent, so it is not a classify-time fail.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input DualFilmIdInput {
                filmId: ID! @nodeId(typeName: "Film")
                sameFilm: ID! @nodeId(typeName: "Film")
            }
            type Query {
                modifyDual(in: DualFilmIdInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var jr = carrier(sdl, "modifyDual", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_RECORD_FQN);
        assertThat(jr.keyDecodes())
            .as("both @nodeId identity decodes are admitted, each targeting film_id")
            .extracting(kd -> kd.leaf() + "->" + kd.targetColumns().get(0).sqlName())
            .containsExactly("filmId->film_id", "sameFilm->film_id");
    }

    @Test
    void plainFieldPlusDecodeOnOneColumn_admitsBothWriters_deferredToRuntimeAgreement() {
        // R322 decode-vs-column overlap: a plain @field and a @nodeId decode resolve to the same column.
        // Admitted (the field-vs-field reject does not fire because one writer is a decode); reconciled by
        // the runtime agreement check. The carrier carries one column binding and one key decode on film_id.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input FieldPlusNodeInput {
                filmId: ID! @nodeId(typeName: "Film")
                filmIdText: String @field(name: "film_id")
            }
            type Query {
                modifyFieldPlusNode(in: FieldPlusNodeInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var jr = carrier(sdl, "modifyFieldPlusNode", false);
        assertThat(jr.columnBindings())
            .extracting(cb -> cb.leaf() + "->" + cb.column().sqlName())
            .containsExactly("filmIdText->film_id");
        assertThat(jr.keyDecodes())
            .extracting(kd -> kd.leaf() + "->" + kd.targetColumns().get(0).sqlName())
            .containsExactly("filmId->film_id");
    }

    // ===== Helpers =====

    private static CallSiteExtraction.JooqRecord carrier(String sdl, String queryField, boolean list) {
        return carrier(TestSchemaHelper.buildSchema(sdl).field("Query", queryField), list);
    }

    /** Variant against a fixture catalog (nodeidfixture / idreffixture) supplied as a custom context. */
    private static CallSiteExtraction.JooqRecord carrier(String sdl, String queryField, boolean list,
            RewriteContext ctx) {
        return carrier(TestSchemaHelper.buildSchema(sdl, ctx).field("Query", queryField), list);
    }

    private static CallSiteExtraction.JooqRecord carrier(
            no.sikt.graphitron.rewrite.model.GraphitronField field, boolean list) {
        var shape = fromArgShape((ServiceField) field);
        ValueShape.JooqRecordInput jr = list
            ? (ValueShape.JooqRecordInput) ((ValueShape.ListOf) shape).elementShape()
            : (ValueShape.JooqRecordInput) shape;
        return jr.carrier();
    }

    private static ValueShape fromArgShape(ServiceField field) {
        return field.serviceMethodCall().methodArgs().stream()
            .filter(e -> e instanceof MappingEntry.FromArg)
            .map(e -> ((MappingEntry.FromArg) e).shape())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no FromArg on the @service method"));
    }

    private static TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE)
            .stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }
}
