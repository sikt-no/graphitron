package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.BaseSliceTerm;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.TestFixtures.col;
import static no.sikt.graphitron.rewrite.TestFixtures.discriminatorCol;
import static no.sikt.graphitron.rewrite.TestFixtures.tableRef;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier pins on the {@link JoinedTableReprojection} fold over a hand-built schema: the
 * facts the fixture-schema pipeline test ({@code LauncherCommandsPipelineTest}) cannot isolate.
 * The empty fold for a non-interface lookup (every schema-free consumer's fallback), the
 * cross-participant first-wins dedup sharing one alias namespace across both base-slice term
 * kinds, and the deferral for a non-directly-projected column carrier (which the retired inline
 * assembly silently truncated to its first column), including the validator's drain of it.
 */
@UnitTier
class JoinedTableReprojectionTest {

    private static final SourceLocation LOC = new SourceLocation(1, 1, "schema.graphqls");
    private static final ColumnRef PARTY_ID = col("party_id", "PARTY_ID", "java.lang.Integer");
    private static final ColumnRef DISPLAY_NAME = col("display_name", "DISPLAY_NAME", "java.lang.String");
    private static final ColumnRef BIRTH_DATE = col("birth_date", "BIRTH_DATE", "java.lang.String");

    private static ParticipantRef.JoinedTableBound individual() {
        var base = tableRef("party", "PARTY", "Party", List.of(PARTY_ID));
        var detail = tableRef("party_individual", "PARTY_INDIVIDUAL", "PartyIndividual", List.of(PARTY_ID));
        var hop = TestFixtures.fkJoin(TestFixtures.foreignKeyRef("party_individual_party_id_fkey"),
            detail, List.of(PARTY_ID), base, List.of(PARTY_ID), null, "individual_0");
        return new ParticipantRef.JoinedTableBound("Individual", detail, "INDIVIDUAL", hop);
    }

    private static GraphitronSchema schema(List<GraphitronField> individualFields) {
        var base = tableRef("party", "PARTY", "Party", List.of(PARTY_ID));
        var iface = new GraphitronType.TableInterfaceType("Party", LOC, discriminatorCol("party_kind"), base,
            List.of(individual()));
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        for (var f : individualFields) {
            fields.put(FieldCoordinates.coordinates(f.parentTypeName(), f.name()), f);
        }
        return new GraphitronSchema(Map.of("Party", iface), fields);
    }

    private static ChildField.ColumnBackedField directColumn(String name, ColumnRef column) {
        return new ChildField.ColumnBackedField("Individual", name, LOC, List.of(column),
            new CallSiteCompaction.Direct());
    }

    private static ChildField.ColumnBackedReferenceField inheritedRef(String name, ColumnRef baseColumn) {
        var base = tableRef("party", "PARTY", "Party", List.of(PARTY_ID));
        var detail = tableRef("party_individual", "PARTY_INDIVIDUAL", "PartyIndividual", List.of(PARTY_ID));
        var hop = TestFixtures.fkJoin(TestFixtures.foreignKeyRef("party_individual_party_id_fkey"),
            detail, List.of(PARTY_ID), base, List.of(PARTY_ID), null, name + "_0");
        return new ChildField.ColumnBackedReferenceField("Individual", name, LOC, List.of(baseColumn),
            List.of(hop), new CallSiteCompaction.Direct(),
            new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots(hop),
            no.sikt.graphitron.rewrite.model.AliasOwner.shared());
    }

    @Test
    void nonInterfaceLookup_foldsToTheOneEmptyInstance() {
        var schema = schema(List.of());
        assertThat(schema.joinedTableReprojectionOf("NoSuchType"))
            .isSameAs(JoinedTableReprojection.EMPTY);
    }

    @Test
    void baseSliceInterleavesInFieldOrder_detailExclusiveStaysPerParticipant() {
        var schema = schema(List.of(
            directColumn("partyId", PARTY_ID),
            inheritedRef("displayName", DISPLAY_NAME),
            directColumn("birthDate", BIRTH_DATE)));
        var reprojection = schema.joinedTableReprojectionOf("Party");
        // SELECT-list position is a whole-query fact: the shared key (a hop column) lands
        // before the inherited reference because that is the schema field order.
        assertThat(reprojection.baseSlice()).hasSize(2);
        assertThat(reprojection.baseSlice().get(0))
            .isInstanceOfSatisfying(BaseSliceTerm.SharedKey.class, sk -> {
                assertThat(sk.baseColumn()).isEqualTo(PARTY_ID);
                assertThat(sk.alias()).isEqualTo("party_id");
            });
        assertThat(reprojection.baseSlice().get(1))
            .isInstanceOfSatisfying(BaseSliceTerm.InheritedRef.class, ir -> {
                assertThat(ir.fieldName()).isEqualTo("displayName");
                assertThat(ir.baseColumn()).isEqualTo(DISPLAY_NAME);
            });
        assertThat(reprojection.detailFieldsOf("Individual")).singleElement().satisfies(df -> {
            assertThat(df.fieldName()).isEqualTo("birthDate");
            assertThat(df.column()).isEqualTo(BIRTH_DATE);
        });
        assertThat(reprojection.deferrals()).isEmpty();
    }

    @Test
    void nodeIdCompactedCarrier_defersInsteadOfTruncating_validatorDrainsIt() {
        var encode = new CallSiteCompaction.NodeIdEncodeKeys(new HelperRef.Encode(
            ClassName.bestGuess("com.example.NodeIds"), "encodeIndividual", List.of(PARTY_ID)));
        var schema = schema(List.of(
            new ChildField.ColumnBackedField("Individual", "id", LOC, List.of(BIRTH_DATE), encode)));
        var reprojection = schema.joinedTableReprojectionOf("Party");
        assertThat(reprojection.baseSlice()).isEmpty();
        assertThat(reprojection.detailFieldsOf("Individual"))
            .as("the carrier must not land in any emission slice while its shape is deferred")
            .isEmpty();
        assertThat(reprojection.deferrals()).singleElement().satisfies(d -> {
            assertThat(d.typeName()).isEqualTo("Individual");
            assertThat(d.fieldName()).isEqualTo("id");
        });
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .anySatisfy(error -> {
                assertThat(error.rejection()).isInstanceOf(Rejection.Deferred.class);
                assertThat(error.message()).contains("Individual.id");
            });
    }
}
