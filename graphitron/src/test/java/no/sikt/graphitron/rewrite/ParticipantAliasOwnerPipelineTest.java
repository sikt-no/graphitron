package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.AliasOwner;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ResultKeyAliasedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.RejectionKind;

/**
 * The result-key alias namespace as model data, plus the census that guards what the namespace
 * cannot.
 *
 * <p>A single-table discriminated interface's query folds every participant's select terms into
 * one set that dedupes aliased terms by their alias alone, so two participants projecting
 * different SQL under one alias silently lose one of the two. {@link AliasOwner} is the verdict
 * that closes that: a name the participant declares itself is aliased by the participant type, a
 * name the interface declares by the interface (so the agreeing arms still collapse to one term),
 * and everything outside a participant's own projection keeps the bare alias it always had.
 *
 * <p>The verdicts are pinned here rather than only at the renderer, because the value is a model
 * fact stamped once at capture and copied by every consumer; the emitted-halves test is the
 * enforcer that both consumers spell the one stamped value rather than two agreeing derivations.
 * The residual shapes the namespace cannot disambiguate (an interface-declared name two
 * participants resolve differently, a bare alias two spliced nesting units collide on) are
 * deferred rejections, and their agreeing controls are here too, so the census is pinned to fire
 * on disagreement rather than on the participation topology.
 *
 * <p>Driven by the {@code fan_base} / {@code fan_target} / {@code fan_owner} fixture in
 * {@code graphitron-sakila-db/src/main/resources/init.sql}.
 */
@PipelineTier
class ParticipantAliasOwnerPipelineTest {

    /**
     * The canonical shape: {@code target} is declared by each participant over its own FK,
     * {@code owner} by the interface over the one FK both share. {@code Film.language} is the
     * non-participant control, and {@code AlphaDetail} the spliced-nesting one.
     */
    private static final String OWNER_SDL = """
        interface Item @table(name: "fan_base") @discriminate(on: "fan_kind") {
            fanBaseId: Int! @field(name: "fan_base_id")
            owner: Owner @reference(path: [{key: "fan_base_fan_owner_id_fkey"}])
        }
        type Alpha implements Item @table(name: "fan_base") @discriminator(value: "ALPHA") {
            fanBaseId: Int! @field(name: "fan_base_id")
            owner: Owner @reference(path: [{key: "fan_base_fan_owner_id_fkey"}])
            target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            detail: AlphaDetail
        }
        type Beta implements Item @table(name: "fan_base") @discriminator(value: "BETA") {
            fanBaseId: Int! @field(name: "fan_base_id")
            owner: Owner @reference(path: [{key: "fan_base_fan_owner_id_fkey"}])
            target: Target @reference(path: [{key: "fan_base_beta_target_id_fkey"}])
        }
        type AlphaDetail {
            nestedTarget: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
        }
        type Target @table(name: "fan_target") { fanTargetId: Int! @field(name: "fan_target_id") }
        type Owner @table(name: "fan_owner") { fanOwnerId: Int! @field(name: "fan_owner_id") }
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") {
            title: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Query {
            items: [Item!]!
            alphas: [Alpha!]!
            films: [Film!]!
        }
        """;

    @Test
    void aliasOwnerVerdicts_areStampedOnTheModelPerDeclaringTypeAndFieldName() {
        var schema = TestSchemaHelper.buildSchema(OWNER_SDL);
        assertThat(schema.diagnostics())
            .as("the fixture must classify cleanly for the verdicts below to mean anything")
            .noneMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA);

        assertThat(ownerOf(schema, "Alpha", "target"))
            .as("a name only the participant declares is owned by the participant type: that is "
                + "what makes two participants' same-named projections two distinct terms")
            .isEqualTo(new AliasOwner.QualifiedBy("Alpha"));
        assertThat(ownerOf(schema, "Beta", "target"))
            .isEqualTo(new AliasOwner.QualifiedBy("Beta"));

        assertThat(ownerOf(schema, "Alpha", "owner"))
            .as("a name the interface declares is owned by the interface, so every arm mints the "
                + "identical alias and the agreeing terms collapse to one exactly as before")
            .isEqualTo(new AliasOwner.QualifiedBy("Item"));
        assertThat(ownerOf(schema, "Beta", "owner"))
            .isEqualTo(new AliasOwner.QualifiedBy("Item"));

        assertThat(ownerOf(schema, "Film", "language"))
            .as("a non-participant's select list never merges with a sibling's, so qualification "
                + "would churn aliases for no gain")
            .isEqualTo(new AliasOwner.Shared());

        assertThat(nestedOwnerOf(schema, "Alpha", "detail", "nestedTarget"))
            .as("a field declared on a nesting type spliced under a participant anchor stays bare: "
                + "the field is declared on a non-participant, one nesting type may sit under "
                + "several anchors, and graphql-java registers one fetcher per coordinate, so an "
                + "anchor-dependent alias would have no single read to agree with. The census is "
                + "what makes that safe, not the alias")
            .isEqualTo(new AliasOwner.Shared());
    }

    @Test
    void aliasOwner_isUnconditional_soADirectlyQueriedParticipantReadsWhatTheFoldWrote() {
        // The owner is per (type, field) and never per host: a participant's projection unit is
        // its own anchor unit, shared with direct queries on that type, so a context-dependent
        // alias would make the unit's address a cross-product of type and host. The fixture
        // queries Alpha both through the interface and directly.
        var schema = TestSchemaHelper.buildSchema(OWNER_SDL);
        var projection = ProjectionRenderTestSupport.renderProjections(schema, DEFAULT_OUTPUT_PACKAGE)
            .stream().filter(t -> t.name().equals("Alpha")).findFirst().orElseThrow();
        assertThat(projection.toString())
            .as("one unit, one alias, whichever query reaches it")
            .contains("\"__rk_Alpha$\"");
    }

    @Test
    void multiTableInterfaceImplementer_staysShared() {
        // A TableBound implementer of a directiveless multi-table interface is not a participant
        // in this sense: its stage-2 per-typename SELECT is its own statement and never merges
        // with a sibling's select list, so the bare alias stays sound there.
        var schema = TestSchemaHelper.buildSchema("""
            interface Searchable { title: String }
            type Language @table(name: "language") { name: String }
            type Film implements Searchable @table(name: "film") {
                title: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { searchables: [Searchable!]! }
            """);
        assertThat(ownerOf(schema, "Film", "language")).isEqualTo(new AliasOwner.Shared());
    }

    @Test
    void bothEmittedHalvesSpellTheOneStampedOwner() {
        // The enforcer, and the reason the owner is a model component rather than a predicate two
        // consumers evaluate: the write side composes the emitted prefix from the stamped value
        // and the read side composes the same prefix off the same field, so they cannot disagree
        // by construction. This asserts the construction rather than an agreement between two
        // computations.
        var schema = TestSchemaHelper.buildSchema(OWNER_SDL);
        var projections = ProjectionRenderTestSupport.renderProjections(schema, DEFAULT_OUTPUT_PACKAGE);
        var fetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);

        for (var typeName : new String[] {"Alpha", "Beta"}) {
            for (var field : schema.fieldsOf(typeName)) {
                if (!(field instanceof ResultKeyAliasedField rk)) {
                    continue;
                }
                String prefix = ReservedAliases.resultKeyPrefix(rk.aliasOwner());
                var unit = projections.stream().filter(t -> t.name().equals(typeName))
                    .findFirst().orElseThrow();
                assertThat(unit.toString())
                    .as("the projection of " + typeName + "." + field.name()
                        + " writes the stamped prefix")
                    .contains("\"" + prefix + "\"");
                var read = fetchers.stream()
                    .filter(t -> t.name().equals(typeName + "Fetchers"))
                    .findFirst().orElseThrow()
                    .methodSpecs().stream()
                    .filter(m -> m.name().equals(field.name()))
                    .map(MethodSpec::toString)
                    .findFirst().orElseThrow();
                assertThat(read)
                    .as("and its fetcher reads the same prefix, not one it derived itself")
                    .contains("\"" + prefix + "\"");
            }
        }
    }

    @Test
    void participatingInSeveralInterfaces_takesTheLexicographicallyFirstDeclaringOne() {
        // ParticipantRef.TableBound membership is not unique and nothing rejects the double
        // membership, which is what made a plain qualify-or-not rule ill-defined. The
        // representative is deterministic, and the agreement census spans every declaring
        // interface's participant set, which transitively forces one projection identity, so any
        // two aliases that coexist in one fold carry identical SQL.
        //
        // The cost, chosen rather than discovered: a field two of a type's interfaces both declare
        // projects once per distinct representative, so a sibling participant of only the second
        // interface contributes a second term over identical SQL under a different alias. Correct,
        // bounded at one term per declaring interface, and a real if exotic redundancy.
        var schema = TestSchemaHelper.buildSchema("""
            interface Zeta @table(name: "fan_base") @discriminate(on: "fan_kind") {
                fanBaseId: Int! @field(name: "fan_base_id")
                owner: Owner @reference(path: [{key: "fan_base_fan_owner_id_fkey"}])
            }
            interface Item @table(name: "fan_base") @discriminate(on: "fan_kind") {
                fanBaseId: Int! @field(name: "fan_base_id")
                owner: Owner @reference(path: [{key: "fan_base_fan_owner_id_fkey"}])
            }
            type Alpha implements Item & Zeta @table(name: "fan_base") @discriminator(value: "ALPHA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                owner: Owner @reference(path: [{key: "fan_base_fan_owner_id_fkey"}])
            }
            type Owner @table(name: "fan_owner") { fanOwnerId: Int! @field(name: "fan_owner_id") }
            type Query { items: [Item!]! zetas: [Zeta!]! }
            """);
        assertThat(schema.diagnostics())
            .noneMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA);
        assertThat(ownerOf(schema, "Alpha", "owner"))
            .as("both Item and Zeta declare the name; the representative is the first by name, so "
                + "every participant of either interface agrees on one owner per field name")
            .isEqualTo(new AliasOwner.QualifiedBy("Item"));
    }

    // ===== the census: what the namespace cannot disambiguate =====

    @Test
    void mixedParticipation_theInheritedReferenceIsClaimedByTheCrossTableParticipantPass() {
        // Where the mixed shape actually goes, which is not where the alias namespace's write/read
        // agreement guard looks. The TableBound / JoinedTableBound fork is per interface, so a type
        // can be a single-table participant of one discriminated interface and a joined-table
        // participant of another. That would put a qualified alias on a coordinate whose joined
        // route writes the unqualified one; the reprojection fold carries a deferral for exactly
        // that disagreement.
        //
        // It has no population today, and this is why: the participant cross-table pass runs for
        // the interface whose base *is* the type's own table, and it claims every single-hop
        // reference that terminates elsewhere. The coordinate therefore classifies as a
        // ParticipantColumnReferenceField, which is not in the result-key-aliased family at all
        // (it projects under a fixed <Type>_<field> alias), so the joined route mints no inherited
        // reference for it and the guard sees nothing. The guard stays as the cheap backstop on
        // the write/read agreement; this pins where the shape is routed instead, so a change that
        // moves this coordinate back into the result-key family shows up here rather than as a
        // silent read of an alias nothing wrote.
        var schema = TestSchemaHelper.buildSchema("""
            interface Subject @table(name: "jti_subject") @discriminate(on: "subject_kind") {
                displayName: String! @field(name: "display_name")
            }
            interface PersonKind @table(name: "jti_person") @discriminate(on: "subject_kind") {
                fullName: String @field(name: "full_name")
            }
            type Person implements Subject & PersonKind
                    @table(name: "jti_person") @discriminator(value: "PERSON") {
                displayName: String! @reference(path: [{key: "jti_person_subject_fk"}]) @field(name: "display_name")
                fullName: String @field(name: "full_name")
            }
            type Query { subjects: [Subject!]! persons: [PersonKind!]! }
            """);
        assertThat(schema.field("Person", "displayName"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField.class);
        assertThat(schema.field("Person", "displayName"))
            .as("and so it carries no alias-owner verdict: its alias is the fixed participant "
                + "scalar one, minted at capture beside this namespace rather than in it")
            .isNotInstanceOf(ResultKeyAliasedField.class);
        assertThat(schema.joinedTableReprojectionOf("Subject").baseSlice())
            .as("nothing reaches the joined route's base slice, so the agreement guard has no "
                + "population to fire on")
            .isEmpty();
    }

    @Test
    void interfaceDeclaredNameResolvedDifferently_defers() {
        // Every participant's arm mints the one interface-qualified alias, which is what makes the
        // agreeing case one shared term. Two participants resolving it over different FKs is the
        // disagreeing case, and one alias cannot carry both projections.
        var schema = TestSchemaHelper.buildSchema("""
            interface Item @table(name: "fan_base") @discriminate(on: "fan_kind") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Alpha implements Item @table(name: "fan_base") @discriminator(value: "ALPHA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Beta implements Item @table(name: "fan_base") @discriminator(value: "BETA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @reference(path: [{key: "fan_base_beta_target_id_fkey"}])
            }
            type Target @table(name: "fan_target") { fanTargetId: Int! @field(name: "fan_target_id") }
            type Query { items: [Item!]! }
            """);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .anySatisfy(error -> {
                assertThat(error.rejection())
                    .as("legal and meaningful schema the generator does not emit yet, so deferred "
                        + "rather than pinned as an author error")
                    .isInstanceOf(no.sikt.graphitron.model.diagnostics.Rejection.Deferred.class);
                assertThat(error.message())
                    .contains("Beta.target")
                    .contains("Alpha.target")
                    .contains("declared on interface 'Item'");
            });
    }

    @Test
    void interfaceDeclaredNameResolvedIdentically_isAccepted() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Item @table(name: "fan_base") @discriminate(on: "fan_kind") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Alpha implements Item @table(name: "fan_base") @discriminator(value: "ALPHA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Beta implements Item @table(name: "fan_base") @discriminator(value: "BETA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Target @table(name: "fan_target") { fanTargetId: Int! @field(name: "fan_target_id") }
            type Query { items: [Item!]! }
            """);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .as("the shared alias carries one projection, which is what it always did")
            .noneMatch(e -> e.message().contains("declared on interface 'Item'"));
    }

    @Test
    void differentNestingTypesCollidingOnOneKey_defers() {
        // A spliced nesting unit's terms land in the participant's own field set and from there in
        // the fold's one namespace, under the bare alias. Two participants embedding different
        // nesting types that declare the same key over divergent paths is the residual collision
        // qualification cannot reach, so the census is the only guard there is.
        var schema = TestSchemaHelper.buildSchema("""
            interface Item @table(name: "fan_base") @discriminate(on: "fan_kind") {
                fanBaseId: Int! @field(name: "fan_base_id")
            }
            type Alpha implements Item @table(name: "fan_base") @discriminator(value: "ALPHA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                detail: AlphaDetail
            }
            type Beta implements Item @table(name: "fan_base") @discriminator(value: "BETA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                detail: BetaDetail
            }
            type AlphaDetail {
                target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type BetaDetail {
                target: Target @reference(path: [{key: "fan_base_beta_target_id_fkey"}])
            }
            type Target @table(name: "fan_target") { fanTargetId: Int! @field(name: "fan_target_id") }
            type Query { items: [Item!]! }
            """);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .anySatisfy(error -> {
                assertThat(error.rejection())
                    .isInstanceOf(no.sikt.graphitron.model.diagnostics.Rejection.Deferred.class);
                assertThat(error.message())
                    .contains("reached through nesting type");
            });
    }

    @Test
    void sameNestingTypeUnderBothParticipants_isAccepted() {
        // One unit per anchor with identical contributions, so the fold's set dedupes them
        // correctly and there is nothing to reject.
        var schema = TestSchemaHelper.buildSchema("""
            interface Item @table(name: "fan_base") @discriminate(on: "fan_kind") {
                fanBaseId: Int! @field(name: "fan_base_id")
            }
            type Alpha implements Item @table(name: "fan_base") @discriminator(value: "ALPHA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                detail: SharedDetail
            }
            type Beta implements Item @table(name: "fan_base") @discriminator(value: "BETA") {
                fanBaseId: Int! @field(name: "fan_base_id")
                detail: SharedDetail
            }
            type SharedDetail {
                target: Target @reference(path: [{key: "fan_base_alpha_target_id_fkey"}])
            }
            type Target @table(name: "fan_target") { fanTargetId: Int! @field(name: "fan_target_id") }
            type Query { items: [Item!]! }
            """);
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .noneMatch(e -> e.message().contains("reached through nesting type"));
    }

    /**
     * The stamped verdict for a field declared on a nesting type. Nesting children live on their
     * {@code NestingField}'s own list rather than the schema's flat coordinate map, so the read
     * goes through the nesting field the anchor declares.
     */
    private static AliasOwner nestedOwnerOf(GraphitronSchema schema, String anchorTypeName,
            String nestingFieldName, String nestedFieldName) {
        var nesting = (no.sikt.graphitron.rewrite.model.ChildField.NestingField)
            schema.field(anchorTypeName, nestingFieldName);
        var nested = nesting.nestedFields().stream()
            .filter(f -> f.name().equals(nestedFieldName))
            .findFirst().orElseThrow();
        assertThat(nested).isInstanceOf(ResultKeyAliasedField.class);
        return ((ResultKeyAliasedField) nested).aliasOwner();
    }

    /** The stamped verdict for one coordinate. */
    private static AliasOwner ownerOf(GraphitronSchema schema, String typeName, String fieldName) {
        GraphitronField field = schema.field(typeName, fieldName);
        assertThat(field)
            .as("fixture coordinate " + typeName + "." + fieldName + " must classify")
            .isInstanceOf(ResultKeyAliasedField.class);
        return ((ResultKeyAliasedField) field).aliasOwner();
    }
}
