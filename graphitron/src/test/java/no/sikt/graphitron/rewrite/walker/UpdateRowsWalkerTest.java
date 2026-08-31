package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.CarrierNullRule;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.UpdateRowsError;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link UpdateRowsWalker}: the PK-or-UK identification and SET/WHERE
 * partition over already-classified {@link InputField} permits. Uses the real fixture catalogs so
 * the jOOQ {@code getPrimaryKey()} / {@code getKeys()} metadata is genuine:
 * <ul>
 *   <li>{@code public} ({@link #PUBLIC}): {@code film} (single PK {@code film_id}, no UK),
 *       {@code film_actor} (composite PK {@code (actor_id, film_id)}), {@code film_list}
 *       (keyless).</li>
 *   <li>{@code nodeidfixture} ({@link #NODE_FIXTURE}): {@code parent_node} (PK {@code pk_id} plus a
 *       non-PK {@code UNIQUE} on {@code alt_key}), {@code bar} (composite PK
 *       {@code (id_1, id_2)}).</li>
 * </ul>
 */
@UnitTier
class UpdateRowsWalkerTest {

    private static final String NODE_FIXTURE = "no.sikt.graphitron.rewrite.nodeidfixture";

    private final UpdateRowsWalker walker = new UpdateRowsWalker();
    private final JooqCatalog PUBLIC = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
    private final JooqCatalog NODE_FIXTURE_CATALOG = new JooqCatalog(NODE_FIXTURE);

    @Test
    void pkOnlyMatch_withExtraColumns_succeedsWithExtrasInSet() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id")),
            columnField("title", col(PUBLIC, "film", "title")),
            columnField("description", col(PUBLIC, "film", "description"))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("film_id");
        assertThat(carrier.keyColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("film_id");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("title", "description");
    }

    @Test
    void pkOnlyMatch_noExtraColumns_rejectsWithNoSetFields() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id"))
        ), PUBLIC, "input");

        assertThat(only(result)).isInstanceOf(UpdateRowsError.NoSetFields.class);
    }

    @Test
    void ukOnlyMatch_pkNotCovered_succeedsWithUniqueKey() {
        var result = walker.walk(null, table("parent_node"), List.of(
            columnField("altKey", col(NODE_FIXTURE_CATALOG, "parent_node", "alt_key")),
            columnField("name", col(NODE_FIXTURE_CATALOG, "parent_node", "name"))
        ), NODE_FIXTURE_CATALOG, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.UniqueKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("alt_key");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName()).containsExactly("name");
    }

    @Test
    void pkPreferredTiebreaker_bothCovered_pkWins() {
        var result = walker.walk(null, table("parent_node"), List.of(
            columnField("pkId", col(NODE_FIXTURE_CATALOG, "parent_node", "pk_id")),
            columnField("altKey", col(NODE_FIXTURE_CATALOG, "parent_node", "alt_key")),
            columnField("name", col(NODE_FIXTURE_CATALOG, "parent_node", "name"))
        ), NODE_FIXTURE_CATALOG, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactly("pk_id");
        // alt_key falls outside the matched PK, so it lands in SET alongside name.
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("alt_key", "name");
    }

    @Test
    void noKeyCovered_rejectsWithNoUniqueKeyCoverage() {
        var result = walker.walk(null, table("film"), List.of(
            columnField("title", col(PUBLIC, "film", "title")),
            columnField("description", col(PUBLIC, "film", "description"))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.NoUniqueKeyCoverage.class);
        var coverage = (UpdateRowsError.NoUniqueKeyCoverage) err;
        assertThat(coverage.candidateKeys()).isNotEmpty();
        assertThat(coverage.table()).isEqualTo("film");
    }

    @Test
    void compositeReferenceStraddlesKey_crossTableFk_partitionsPerColumn() {
        // A CROSS-table FK reference whose lifted columns straddle the (actor_id, film_id) PK is
        // admitted and partitions PER COLUMN. actor_id is in the key and `actorId` already pins it,
        // so the reference contributes only an agreement obligation there: it neither filters nor
        // writes that column. last_update is outside the key, so it is a SET write. Both rows keep
        // the decode slot they sit at in the reference's own record.
        var result = walker.walk(null, table("film_actor"), List.of(
            columnField("actorId", col(PUBLIC, "film_actor", "actor_id")),
            columnField("filmId", col(PUBLIC, "film_actor", "film_id")),
            compositeReferenceField("straddle", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        // WHERE: the two PK columns, both from the plain identity fields. The straddler supplies none.
        assertThat(carrier.keyColumns()).extracting(k -> k.sdlFieldName() + ":" + k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("actorId:actor_id", "filmId:film_id");
        // SET: only the straddler's out-of-key column, at its own decode slot (1, not 0).
        assertThat(carrier.setColumns()).extracting(s -> s.sdlFieldName() + ":" + s.targetColumn().sqlName())
            .containsExactly("straddle:last_update");
        assertThat(carrier.setColumns().get(0).decodeSlot())
            .as("last_update is the second column of the reference's decode record")
            .isEqualTo(1);
        // The in-key column becomes an agreement obligation naming both contributing fields.
        assertThat(carrier.agreementObligations()).singleElement().satisfies(ob -> {
            assertThat(ob.column().sqlName()).isEqualTo("actor_id");
            assertThat(ob.keySide().sdlFieldName()).isEqualTo("actorId");
            assertThat(ob.keySide().decodeSlot()).isEqualTo(0);
            assertThat(ob.referenceSide().sdlFieldName()).isEqualTo("straddle");
            assertThat(ob.referenceSide().decodeSlot())
                .as("actor_id is the first column of the reference's decode record")
                .isEqualTo(0);
        });
    }

    @Test
    void straddlingReference_soleIdentityContributor_suppliesTheKeyColumnItself() {
        // Nothing else pins actor_id, so the straddler's in-key column IS the WHERE predicate rather
        // than an obligation: there is no second value to check it against. It keeps its decode slot,
        // which is what lets the emitter read the right half of the reference's record.
        var result = walker.walk(null, table("film_actor"), List.of(
            columnField("filmId", col(PUBLIC, "film_actor", "film_id")),
            compositeReferenceField("straddle", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.keyColumns()).extracting(k -> k.sdlFieldName() + ":" + k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("filmId:film_id", "straddle:actor_id");
        assertThat(carrier.keyColumns()).filteredOn(k -> k.sdlFieldName().equals("straddle"))
            .singleElement().satisfies(k -> assertThat(k.decodeSlot()).isEqualTo(0));
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactly("last_update");
        assertThat(carrier.agreementObligations())
            .as("a sole contributor has nothing to disagree with")
            .isEmpty();
    }

    @Test
    void nullableStraddlingReference_pinnedByAWholeCarrier_isAdmittedAndClears() {
        // The nullable spelling of the admitted shape, and the case that used to reject. actorId and
        // filmId between them pin the whole key, so the straddler's in-key half is identity supplied
        // by something else: it neither filters nor writes actor_id, contributes an obligation there,
        // and writes last_update. An explicit null on it clears that write and nothing else.
        var result = walker.walk(null, table("film_actor"), List.of(
            columnField("actorId", col(PUBLIC, "film_actor", "actor_id")),
            columnField("filmId", col(PUBLIC, "film_actor", "film_id")),
            nullableCompositeReferenceField("straddle", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.keyColumns()).extracting(k -> k.sdlFieldName() + ":" + k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("actorId:actor_id", "filmId:film_id");
        assertThat(carrier.setColumns()).extracting(s -> s.sdlFieldName() + ":" + s.targetColumn().sqlName())
            .containsExactly("straddle:last_update");
        assertThat(carrier.setColumns().get(0).decodeSlot())
            .as("the out-of-key column keeps its own decode slot")
            .isEqualTo(1);
        assertThat(carrier.agreementObligations()).singleElement().satisfies(ob -> {
            assertThat(ob.column().sqlName()).isEqualTo("actor_id");
            assertThat(ob.keySide().sdlFieldName()).isEqualTo("actorId");
            assertThat(ob.referenceSide().sdlFieldName()).isEqualTo("straddle");
        });
        assertThat(ruleFor(carrier, "straddle"))
            .as("a straddler's SET half is its out-of-key half, so a null clears and nothing else")
            .isInstanceOf(CarrierNullRule.OnExplicitNull.Clears.class);
    }

    @Test
    void nullableStraddlingReference_soleContributorOfAKeyColumn_rejectsNamingTheUnpinnedColumns() {
        // The surviving reject. Nothing but the straddler supplies actor_id, so its in-key half would
        // have to be the WHERE predicate, and an optional field cannot be load-bearing identity:
        // omitted, it leaves the row unidentifiable. The arm names exactly the columns with no other
        // contributor, plus the key and table, because the same spelling is legal wherever they have one.
        var result = walker.walk(null, table("film_actor"), List.of(
            columnField("filmId", col(PUBLIC, "film_actor", "film_id")),
            nullableCompositeReferenceField("straddle", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update")))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.NullableStraddlingReference.class);
        var nullable = (UpdateRowsError.NullableStraddlingReference) err;
        assertThat(nullable.fieldName()).isEqualTo("straddle");
        assertThat(nullable.table()).isEqualTo("film_actor");
        assertThat(sqlNames(nullable.matchedKey().columns())).containsExactlyInAnyOrder("actor_id", "film_id");
        assertThat(sqlNames(nullable.unpinnedColumns())).containsExactly("actor_id");
        assertThat(sqlNames(nullable.columnsOutsideKey())).containsExactly("last_update");
        assertThat(nullable.lspCode()).isEqualTo("graphitron.update-rows.nullable-straddling-reference");
    }

    @Test
    void nullableStraddler_pinnedByANonNullStraddlerAlone_isAdmitted() {
        // The case that pins the identity-contributor definition itself, and the only place the answer
        // is ever settled: no whole carrier supplies actor_id, so a narrower reading (whole carriers
        // pin, straddlers do not) would refuse this. A non-null straddler's winning claim IS the
        // column's WHERE predicate and a non-null field cannot be absent, so it pins exactly as a
        // whole carrier does. `first` still supplies the predicate; the nullable `second` neither
        // filters nor writes actor_id and clears on an explicit null.
        var result = walker.walk(null, table("film_actor"), List.of(
            columnField("filmId", col(PUBLIC, "film_actor", "film_id")),
            compositeReferenceField("first", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update"))),
            nullableCompositeReferenceField("second", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.keyColumns()).filteredOn(k -> k.targetColumn().sqlName().equals("actor_id"))
            .singleElement()
            .satisfies(k -> assertThat(k.sdlFieldName()).isEqualTo("first"));
        assertThat(carrier.agreementObligations()).singleElement().satisfies(ob -> {
            assertThat(ob.column().sqlName()).isEqualTo("actor_id");
            assertThat(ob.keySide().sdlFieldName()).isEqualTo("first");
            assertThat(ob.referenceSide().sdlFieldName()).isEqualTo("second");
        });
        assertThat(ruleFor(carrier, "second")).isInstanceOf(CarrierNullRule.OnExplicitNull.Clears.class);
        assertThat(ruleFor(carrier, "first"))
            .as("a non-null field cannot receive a null, so GraphQL settles it before graphitron sees one")
            .isInstanceOf(CarrierNullRule.OnExplicitNull.CannotArrive.class);
    }

    @Test
    void nullableSelfFkOverlappingTheMatchedKey_isRefusedAsIdentity() {
        // The predicate is uniform over the carrier roles and never consults straddling, which is what
        // makes the self-FK case right for free: it routes every lifted column to SET, so mailbox_id is
        // an ordinary assignment right up to the point where the assigned value is null and the row
        // would be orphaned. The rule names the identity column the refusal is about.
        var result = walker.walk(null, table("email"), List.of(
            compositeColumnField("id", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "message_no"))),
            nullableSelfReferenceField("inReplyTo", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "in_reply_to_no"))),
            columnField("subject", col(PUBLIC, "email", "subject"))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(ruleFor(carrier, "inReplyTo"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                CarrierNullRule.OnExplicitNull.RefusedAsIdentity.class))
            .satisfies(r -> assertThat(sqlNames(r.identityColumns())).containsExactly("mailbox_id"));
    }

    @Test
    void nullableNonStraddlingReference_clears() {
        // Clearing is not a property of the straddle. A nullable cross-table reference wholly outside
        // the matched key writes its whole foreign-key tuple, so a null clears every column of it.
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id")),
            nullableColumnReferenceField("languageRef", List.of(col(PUBLIC, "film", "language_id")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(ruleFor(carrier, "languageRef")).isInstanceOf(CarrierNullRule.OnExplicitNull.Clears.class);
    }

    @Test
    void ownColumnsStraddleKey_stillRejectsWithMixedCarrierKeyMembership() {
        // MixedCarrierKeyMembership survives, narrowed to the own-columns carrier: a same-table
        // composite @nodeId whose OWN columns span the key. Writing half of them would move the row,
        // which is a different act from re-pointing a sibling reference.
        var result = walker.walk(null, table("film_actor"), List.of(
            compositeColumnField("straddle", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update"))),
            columnField("filmId", col(PUBLIC, "film_actor", "film_id"))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.MixedCarrierKeyMembership.class);
        var mixed = (UpdateRowsError.MixedCarrierKeyMembership) err;
        assertThat(mixed.fieldName()).isEqualTo("straddle");
        assertThat(sqlNames(mixed.columnsInKey())).containsExactly("actor_id");
        assertThat(sqlNames(mixed.columnsOutsideKey())).containsExactly("last_update");
    }

    @Test
    void twoStraddlersSharingAnInKeyColumn_firstInInputOrderPinsIt() {
        // Deterministic tiebreak: the first straddler in input-field order supplies the WHERE
        // predicate, the second contributes an obligation against it. Which of them wins is
        // observationally irrelevant for well-formed input, the agreement check running either way,
        // and being deterministic is the point. Which carriers may contend at all is a separate
        // question and is not irrelevant: both here are non-null, and a nullable one never claims
        // (nullableStraddler_pinnedByANonNullStraddlerAlone_isAdmitted is that case).
        var result = walker.walk(null, table("film_actor"), List.of(
            columnField("filmId", col(PUBLIC, "film_actor", "film_id")),
            compositeReferenceField("first", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update"))),
            compositeReferenceField("second", List.of(
                col(PUBLIC, "film_actor", "actor_id"),
                col(PUBLIC, "film_actor", "last_update")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.keyColumns()).filteredOn(k -> k.targetColumn().sqlName().equals("actor_id"))
            .singleElement()
            .satisfies(k -> assertThat(k.sdlFieldName()).isEqualTo("first"));
        assertThat(carrier.agreementObligations()).singleElement().satisfies(ob -> {
            assertThat(ob.column().sqlName()).isEqualTo("actor_id");
            assertThat(ob.keySide().sdlFieldName()).isEqualTo("first");
            assertThat(ob.referenceSide().sdlFieldName()).isEqualTo("second");
        });
    }

    @Test
    void selfFkOverlap_isCarriedAsAnAgreementObligation() {
        // The self-FK overlap rides the same component, so the emitters fold over one fact instead
        // of each intersecting the partitions. mailbox_id is written by the self-FK and pinned by
        // `id`, and the obligation names both sides with the slot each reads.
        var result = walker.walk(null, table("email"), List.of(
            compositeColumnField("id", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "message_no"))),
            selfReferenceField("inReplyTo", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "in_reply_to_no"))),
            columnField("subject", col(PUBLIC, "email", "subject"))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.agreementObligations()).singleElement().satisfies(ob -> {
            assertThat(ob.column().sqlName()).isEqualTo("mailbox_id");
            assertThat(ob.keySide().sdlFieldName()).isEqualTo("id");
            assertThat(ob.keySide().decodeSlot()).isEqualTo(0);
            assertThat(ob.referenceSide().sdlFieldName()).isEqualTo("inReplyTo");
            assertThat(ob.referenceSide().decodeSlot()).isEqualTo(0);
        });
        // The self-FK still writes the shared column: unlike a straddler, its columns are wholly SET.
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .contains("mailbox_id");
    }

    @Test
    void nullableCrossTableReference_notStraddling_isAdmitted() {
        // The nullability rule is scoped to the straddle. A nullable cross-table reference whose
        // columns all sit outside the matched key clears cleanly (the whole FK tuple is on the SET
        // side), so it stays admitted; this is what the rejection message means when it says the same
        // spelling is fine elsewhere.
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id")),
            nullableColumnReferenceField("languageRef", List.of(col(PUBLIC, "film", "language_id")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactly("language_id");
        assertThat(carrier.agreementObligations()).isEmpty();
    }

    @Test
    void selfFkReference_routesAllColumnsToSet_sharedKeyColumnInBothPartitions() {
        // Email's PK is (mailbox_id, message_no). `id` (own composite NodeId) covers the PK
        // → WHERE; `inReplyTo` is a self-FK reference lifting (mailbox_id, in_reply_to_no) — it
        // routes WHOLLY to SET even though mailbox_id is a PK member, because a self-FK points at a
        // sibling row, never this row's identity. mailbox_id then appears in BOTH partitions (the
        // WHERE from `id`, the SET from `inReplyTo`); the emit-side agreement reconciles it.
        var result = walker.walk(null, table("email"), List.of(
            compositeColumnField("id", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "message_no"))),
            selfReferenceField("inReplyTo", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "in_reply_to_no"))),
            columnField("subject", col(PUBLIC, "email", "subject"))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactlyInAnyOrder("mailbox_id", "message_no");
        // WHERE: id's two PK columns only.
        assertThat(carrier.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("mailbox_id", "message_no");
        assertThat(carrier.keyColumns()).extracting(k -> k.sdlFieldName()).containsOnly("id");
        // SET: the self-FK's two columns (whole, including the shared mailbox_id) plus subject.
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("mailbox_id", "in_reply_to_no", "subject");
        var inReplyToSet = carrier.setColumns().stream()
            .filter(s -> s.sdlFieldName().equals("inReplyTo")).toList();
        assertThat(inReplyToSet).extracting(s -> s.targetColumn().sqlName())
            .containsExactly("mailbox_id", "in_reply_to_no");
        // The shared mailbox_id is genuinely in both partitions.
        assertThat(carrier.keyColumns()).anyMatch(k -> k.targetColumn().sqlName().equals("mailbox_id"));
        assertThat(carrier.setColumns()).anyMatch(s -> s.targetColumn().sqlName().equals("mailbox_id"));
    }

    @Test
    void selfFkReference_keyColumnReachableOnlyViaSelfFk_rejectsNoUniqueKeyCoverage() {
        // Coverage is computed over the non-self-FK columns only. Here messageNo covers half
        // the PK; mailbox_id is reachable ONLY through the self-FK `inReplyTo`, so the identity
        // columns {message_no, subject} do not cover (mailbox_id, message_no). A self-FK cannot pin
        // the row it lives on, so coverage correctly fails.
        var result = walker.walk(null, table("email"), List.of(
            columnField("messageNo", col(PUBLIC, "email", "message_no")),
            selfReferenceField("inReplyTo", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "in_reply_to_no"))),
            columnField("subject", col(PUBLIC, "email", "subject"))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.NoUniqueKeyCoverage.class);
        assertThat(((UpdateRowsError.NoUniqueKeyCoverage) err).table()).isEqualTo("email");
    }

    @Test
    void selfFkReference_formerlyBulkRejected_nowAdmitsAndRoutesAllSet() {
        // The bulk self-FK reject is gone. The walker is cardinality-independent —
        // a self-FK @reference routes its lifted columns wholly to SET regardless of the @table arg's list
        // shape (the bulk vs single-row split is the emitter's). This is the same email shape the bulk
        // reject used to fence off; it now admits, with the shared mailbox_id in both partitions for the
        // emit-time WHERE∩SET agreement (the duplicate v-column collapsed by the bulk SET dedup).
        var result = walker.walk(null, table("email"), List.of(
            compositeColumnField("id", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "message_no"))),
            selfReferenceField("inReplyTo", List.of(
                col(PUBLIC, "email", "mailbox_id"),
                col(PUBLIC, "email", "in_reply_to_no"))),
            columnField("subject", col(PUBLIC, "email", "subject"))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        // WHERE: id's two PK columns; SET: the self-FK's columns (whole, incl. the shared mailbox_id) + subject.
        assertThat(carrier.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("mailbox_id", "message_no");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("mailbox_id", "in_reply_to_no", "subject");
        // mailbox_id is genuinely in both partitions (the WHERE∩SET overlap the emit agreement reconciles).
        assertThat(carrier.keyColumns()).anyMatch(k -> k.targetColumn().sqlName().equals("mailbox_id"));
        assertThat(carrier.setColumns()).anyMatch(s -> s.targetColumn().sqlName().equals("mailbox_id"));
    }

    @Test
    void decodeInvolvingSetOverlap_admitsWithoutPlainCollision() {
        // Two SET writers on one column where at least one is a @nodeId decode (the
        // endorsement-style overlap) is admitted — NOT a PlainColumnCollision — and deferred to the
        // emit-time value agreement the bulk SET dedup runs. (Two plain writers on one SET column is the
        // Stage 6b collision reject; a decode among them lifts it to the runtime agreement instead.)
        var result = walker.walk(null, table("film"), List.of(
            columnField("filmId", col(PUBLIC, "film", "film_id")),
            columnField("languageId", col(PUBLIC, "film", "language_id")),
            decodeColumnField("languageRef", col(PUBLIC, "film", "language_id"))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactly("language_id", "language_id");
        assertThat(carrier.setColumns()).extracting(s -> s.sdlFieldName())
            .containsExactlyInAnyOrder("languageId", "languageRef");
    }

    @Test
    void unsupportedShapes_collectedAcrossLoopWithoutShortCircuit() {
        var result = walker.walk(null, table("film"), List.of(
            listNestingField("nested"),
            unboundField("orphan")
        ), PUBLIC, "input");

        var errors = errors(result);
        assertThat(errors).hasSize(2);
        assertThat(errors).allMatch(e -> e instanceof UpdateRowsError.UnsupportedInputFieldShape);
        assertThat(errors).extracting(e -> ((UpdateRowsError.UnsupportedInputFieldShape) e).fieldName())
            .containsExactlyInAnyOrder("nested", "orphan");
    }

    @Test
    void overrideConditionField_rejectsWithOverrideConditionNotSupported() {
        var loc = new SourceLocation(7, 3);
        var result = walker.walk(null, table("film"), List.of(
            conditionOwnedFieldAt("syntheticName", loc)
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.OverrideConditionNotSupported.class);
        var override = (UpdateRowsError.OverrideConditionNotSupported) err;
        assertThat(override.fieldName()).isEqualTo("syntheticName");
        assertThat(override.conditionLocation()).isEqualTo(loc);
    }

    @Test
    void tableWithNoKeys_rejectsWithNoUniqueKeyCoverageAndEmptyCandidates() {
        var result = walker.walk(null, table("film_list"), List.of(
            columnField("title", col(PUBLIC, "film_list", "title")),
            columnField("category", col(PUBLIC, "film_list", "category"))
        ), PUBLIC, "input");

        var err = only(result);
        assertThat(err).isInstanceOf(UpdateRowsError.NoUniqueKeyCoverage.class);
        assertThat(((UpdateRowsError.NoUniqueKeyCoverage) err).candidateKeys()).isEmpty();
    }

    @Test
    void compositePkMatch_throughCompositeNodeIdField_succeeds() {
        var result = walker.walk(null, table("bar"), List.of(
            compositeColumnField("ref", List.of(
                col(NODE_FIXTURE_CATALOG, "bar", "id_1"),
                col(NODE_FIXTURE_CATALOG, "bar", "id_2"))),
            columnField("name", col(NODE_FIXTURE_CATALOG, "bar", "name"))
        ), NODE_FIXTURE_CATALOG, "input");

        var carrier = ok(result);
        assertThat(carrier.matchedKey()).isInstanceOf(MatchedKey.PrimaryKey.class);
        assertThat(sqlNames(carrier.matchedKey().columns())).containsExactlyInAnyOrder("id_1", "id_2");
        // One SDL field produced two key columns sharing the same sdlFieldName.
        assertThat(carrier.keyColumns()).hasSize(2);
        assertThat(carrier.keyColumns()).extracting(k -> k.sdlFieldName()).containsOnly("ref");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName()).containsExactly("name");
    }

    @Test
    void fkReferenceAdmissibility_keyColumnReferenceLandsInWhere_nonKeyInSet() {
        var result = walker.walk(null, table("film"), List.of(
            // FK-reference carrier on the PK column lands in the WHERE half ...
            columnReferenceField("filmRef", List.of(col(PUBLIC, "film", "film_id"))),
            // ... and a reference carrier on a non-key column lands in the SET half.
            columnReferenceField("languageRef", List.of(col(PUBLIC, "film", "language_id")))
        ), PUBLIC, "input");

        var carrier = ok(result);
        assertThat(carrier.keyColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("film_id");
        assertThat(carrier.setColumns()).extracting(s -> s.targetColumn().sqlName()).containsExactly("language_id");
    }

    // --- result helpers ---

    private static UpdateRows.Identified ok(WalkerResult<UpdateRows> r) {
        assertThat(r).isInstanceOf(WalkerResult.Ok.class);
        return (UpdateRows.Identified) ((WalkerResult.Ok<UpdateRows>) r).carrier();
    }

    private static List<Rejection.AuthorError> errors(WalkerResult<UpdateRows> r) {
        assertThat(r).isInstanceOf(WalkerResult.Err.class);
        return ((WalkerResult.Err<UpdateRows>) r).errors();
    }

    private static Rejection.AuthorError only(WalkerResult<UpdateRows> r) {
        var errors = errors(r);
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    private static List<String> sqlNames(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::sqlName).toList();
    }

    /** The stated explicit-null rule for one SET carrier. A missing rule is itself a failure: the
     *  walker states exactly one per carrier contributing to SET. */
    private static CarrierNullRule.OnExplicitNull ruleFor(UpdateRows.Identified carrier, String sdlFieldName) {
        var rules = carrier.nullRules().stream()
            .filter(r -> r.sdlFieldName().equals(sdlFieldName)).toList();
        assertThat(rules).as("one null rule for SET carrier '" + sdlFieldName + "'").hasSize(1);
        return rules.getFirst().rule();
    }

    // --- fixture builders ---

    private static TableRef table(String sqlName) {
        var cn = ClassName.get("fixture", "T");
        return new TableRef(sqlName, sqlName.toUpperCase(), cn, cn, cn, List.of(), List.of());
    }

    private static ColumnRef col(JooqCatalog catalog, String table, String name) {
        var e = catalog.findColumn(table, name).orElseThrow(
            () -> new IllegalStateException("fixture column not found: " + table + "." + name));
        return new ColumnRef(e.sqlName(), e.javaName(), e.columnClass());
    }

    private static InputField.ColumnBackedField columnField(String name, ColumnRef column) {
        return new InputField.ColumnBackedField("In", name, loc(), "Scalar", true, false,
            List.of(column), Optional.empty(), new CallSiteExtraction.Direct());
    }

    // A single-column @nodeId-decode carrier: a ColumnField whose value is only knowable at runtime, so an
    // overlap involving it is decode-involving (admitted, deferred to agreement) rather than a plain collision.
    private static InputField.ColumnBackedField decodeColumnField(String name, ColumnRef column) {
        return new InputField.ColumnBackedField("In", name, loc(), "ID", true, false,
            List.of(column), Optional.empty(), dummyDecode(List.of(column)));
    }

    private static InputField.ColumnBackedField compositeColumnField(String name, List<ColumnRef> columns) {
        return new InputField.ColumnBackedField("In", name, loc(), "ID", true, false,
            columns, Optional.empty(), dummyDecode(columns));
    }

    private static InputField.ColumnBackedReferenceField columnReferenceField(String name, List<ColumnRef> lifted) {
        // Cross-table FK reference (selfReference = false): partitions by key membership.
        return new InputField.ColumnBackedReferenceField("In", name, loc(), "ID", true, false,
            List.of(lifted.getFirst()), List.of(), new FilterBinding.Local(lifted), false,
            Optional.empty(), new CallSiteExtraction.Direct());
    }

    private static InputField.ColumnBackedReferenceField compositeReferenceField(String name, List<ColumnRef> lifted) {
        // Non-null cross-table FK reference (selfReference = false): partitions per column, and a
        // straddle is admitted.
        return new InputField.ColumnBackedReferenceField("In", name, loc(), "ID", true, false,
            lifted, List.of(), new FilterBinding.Local(lifted), false, Optional.empty(), dummyDecode(lifted));
    }

    /** The nullable spelling of {@link #compositeReferenceField}: admitted where its in-key half is
     *  pinned elsewhere, rejected where it is a key column's sole contributor. */
    private static InputField.ColumnBackedReferenceField nullableCompositeReferenceField(
            String name, List<ColumnRef> lifted) {
        return new InputField.ColumnBackedReferenceField("In", name, loc(), "ID", false, false,
            lifted, List.of(), new FilterBinding.Local(lifted), false, Optional.empty(), dummyDecode(lifted));
    }

    /** A nullable single-column cross-table FK reference: nothing to straddle, so always admitted. */
    private static InputField.ColumnBackedReferenceField nullableColumnReferenceField(
            String name, List<ColumnRef> lifted) {
        return new InputField.ColumnBackedReferenceField("In", name, loc(), "ID", false, false,
            List.of(lifted.getFirst()), List.of(), new FilterBinding.Local(lifted), false,
            Optional.empty(), dummyDecode(lifted));
    }

    /** The nullable spelling of {@link #selfReferenceField}: admitted, and refused a clear where one
     *  of its lifted columns is also a matched-key column. */
    private static InputField.ColumnBackedReferenceField nullableSelfReferenceField(
            String name, List<ColumnRef> lifted) {
        return new InputField.ColumnBackedReferenceField("In", name, loc(), "ID", false, false,
            lifted, List.of(), new FilterBinding.Local(lifted), true, Optional.empty(), dummyDecode(lifted));
    }

    private static InputField.ColumnBackedReferenceField selfReferenceField(String name, List<ColumnRef> lifted) {
        // Self-FK reference (selfReference = true): routes all lifted columns to SET.
        return new InputField.ColumnBackedReferenceField("In", name, loc(), "ID", true, false,
            lifted, List.of(), new FilterBinding.Local(lifted), true, Optional.empty(), dummyDecode(lifted));
    }

    // A plain (non-list) NestingField is admitted by flattening it; a list-typed nesting stays
    // unsupported, so this helper builds the list-typed shape for the unsupported-shape coverage.
    private static InputField.NestingField listNestingField(String name) {
        return new InputField.NestingField("In", name, loc(), "Nested", false, true, List.of(), Optional.empty());
    }

    private static InputField.UnboundField unboundField(String name) {
        return new InputField.UnboundField("In", name, loc(), "String", false, false, Optional.empty(), name);
    }

    private static InputField.ConditionOwnedField conditionOwnedFieldAt(String name, SourceLocation location) {
        return new InputField.ConditionOwnedField("In", name, location, "String", false, false,
            new ArgConditionRef(null, true));
    }

    private static CallSiteExtraction.NodeIdDecodeKeys dummyDecode(List<ColumnRef> columns) {
        return new CallSiteExtraction.NodeIdDecodeKeys.ThrowOnMismatch(
            new HelperRef.Decode(ClassName.get("fixture", "Enc"), "decode", columns, "Type"));
    }

    private static SourceLocation loc() {
        return new SourceLocation(1, 1);
    }
}
