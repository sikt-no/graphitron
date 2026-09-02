package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * Unit-tier coverage of the sealed-hierarchy enforcement and structural shape of
 * {@link DomainReturnType}. The abstract {@link OutputField#domainReturnType()} method already
 * enforces compile-time coverage of every leaf permit in {@link RootField} and {@link ChildField};
 * this test is the belt-and-suspenders meta-test the spec calls for: it walks the sealed-permit
 * graph via reflection and verifies that every leaf record class declares (or inherits) the
 * abstract method's implementation, plus that {@link DomainReturnType}'s structural arms compare
 * as expected.
 */
@UnitTier
class DomainReturnTypeCoverageTest {

    /** Every concrete leaf in {@link RootField}'s and {@link ChildField}'s sealed-permit graph has a {@code domainReturnType} method body. */
    @Test
    void everyOutputFieldLeafImplementsDomainReturnType() throws NoSuchMethodException {
        List<Class<?>> leaves = collectLeaves(OutputField.class);
        // Sanity check: the graph must produce a non-trivial set so a future hierarchy-walk bug
        // can't make this assertion trivially hold.
        assertThat(leaves)
            .as("OutputField permits should include at least one ChildField and one RootField leaf")
            .isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (Class<?> leaf : leaves) {
            // The abstract method's contract is "declared on every concrete leaf"; reflection
            // succeeds on either a directly-declared method or an inherited concrete method,
            // but if a leaf failed to override the abstract method the compiler would have
            // refused — so observing the method here is the right proxy for compile-time
            // coverage.
            var m = leaf.getMethod("domainReturnType");
            if (Modifier.isAbstract(m.getModifiers())) {
                missing.add(leaf.getName());
            }
        }
        assertThat(missing)
            .as("Every leaf in the OutputField sealed graph must answer domainReturnType()")
            .isEmpty();
    }

    /** {@link DomainReturnType.Record} arm equality is structural on the contained {@link TableRef}. */
    @Test
    void recordArm_structuralEquality() {
        var t1 = stubFilmTable();
        var t2 = stubFilmTable();
        assertThat(new DomainReturnType.Record(t1)).isEqualTo(new DomainReturnType.Record(t2));
        assertThat(new DomainReturnType.Record(t1)).isNotEqualTo(new DomainReturnType.Plain(ClassName.get(String.class)));
    }

    /** {@link DomainReturnType.TableRecord} arm equality is structural on the {@link ClassName}. */
    @Test
    void tableRecordArm_structuralEquality() {
        var cls1 = ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord");
        var cls2 = ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord");
        assertThat(new DomainReturnType.TableRecord(cls1))
            .isEqualTo(new DomainReturnType.TableRecord(cls2));
        assertThat(new DomainReturnType.TableRecord(cls1))
            .isNotEqualTo(new DomainReturnType.Record(stubFilmTable()));
    }

    /** {@link DomainReturnType.Plain} arm equality is structural on the {@link ClassName}. */
    @Test
    void plainArm_structuralEquality() {
        assertThat(new DomainReturnType.Plain(ClassName.get(String.class)))
            .isEqualTo(new DomainReturnType.Plain(ClassName.get(String.class)));
        assertThat(new DomainReturnType.Plain(ClassName.get(String.class)))
            .isNotEqualTo(new DomainReturnType.Plain(ClassName.get(Integer.class)));
    }

    /** {@link DomainReturnType.NoClaim} is a value, so two of them are one value to the group-by. */
    @Test
    void noClaimArm_structuralEquality() {
        assertThat(new DomainReturnType.NoClaim()).isEqualTo(new DomainReturnType.NoClaim());
        assertThat(new DomainReturnType.NoClaim())
            .isNotEqualTo(new DomainReturnType.Plain(ClassName.get(Object.class)));
    }

    /**
     * The root's split is Claim / NoClaim, and the three comparable arms sit under {@link
     * DomainReturnType.Claim}. The conflict reduction's group-by is declared over {@code Claim},
     * so a fourth arm added under the root without a home in that seal cannot silently join the
     * comparison, and one added under {@code Claim} must state its equality semantics.
     */
    @Test
    void rootSplitsIntoClaimAndNoClaim() {
        assertThat(DomainReturnType.class.getPermittedSubclasses())
            .containsExactlyInAnyOrder(DomainReturnType.Claim.class, DomainReturnType.NoClaim.class);
        assertThat(DomainReturnType.Claim.class.getPermittedSubclasses())
            .containsExactlyInAnyOrder(
                DomainReturnType.Record.class,
                DomainReturnType.TableRecord.class,
                DomainReturnType.Plain.class);
        assertThat(new DomainReturnType.NoClaim()).isNotInstanceOf(DomainReturnType.Claim.class);
    }

    /**
     * The shared result-return rule asks the table question first: a result type carrying both a
     * resolved table and a reflected backing class claims the typed record, never
     * {@code Plain(XRecord)}, which would falsify {@link DomainReturnType.Plain}'s "no jOOQ
     * surface" contract.
     */
    @Test
    void resultReturnRule_asksTheTableQuestionFirst() {
        var table = stubFilmTable();
        var withTable = new ReturnTypeRef.ResultReturnType(
            "FilmDetails", new FieldWrapper.Single(true), "com.example.jooq.tables.records.FilmRecord", table);
        assertThat(DomainReturnType.claimForResultReturn(withTable))
            .isEqualTo(new DomainReturnType.TableRecord(CatalogRefs.recordClass(table)));

        var classOnly = new ReturnTypeRef.ResultReturnType(
            "FilmDto", new FieldWrapper.Single(true), "com.example.Outer$Nested", null);
        assertThat(DomainReturnType.claimForResultReturn(classOnly))
            .as("a nested backing class is spelled Outer.Nested, the one mint's spelling")
            .isEqualTo(new DomainReturnType.Plain(ClassName.get("com.example", "Outer", "Nested")));

        var neither = new ReturnTypeRef.ResultReturnType(
            "Ungrounded", new FieldWrapper.Single(true), null, null);
        assertThat(DomainReturnType.claimForResultReturn(neither))
            .isEqualTo(new DomainReturnType.NoClaim());
    }

    /** The validator's diagnostic message embeds arm names without permit-internal {@code Wrap.X} tokens. */
    @Test
    void toString_usesArmNamesForRejectionMessage() {
        var record = new DomainReturnType.Record(stubFilmTable());
        var tableRecord = new DomainReturnType.TableRecord(
            ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord"));
        var plain = new DomainReturnType.Plain(ClassName.get(String.class));
        assertThat(record.toString()).isEqualTo("Record(film)");
        assertThat(tableRecord.toString()).isEqualTo("TableRecord(FilmRecord)");
        assertThat(plain.toString()).isEqualTo("Plain(java.lang.String)");
        // A no-claim participant still renders in the rejection's participant list, as a
        // statement rather than as a placeholder class.
        assertThat(new DomainReturnType.NoClaim().toString()).isEqualTo("makes no source-type claim");
    }

    /** {@link MutationField.MutationDmlRecordField} answers {@link DomainReturnType.Record} from its resolved write target. */
    @Test
    void mutationDmlRecordField_answersRecordArm() {
        // Validator's load-bearing assumption: DML mutation producer hands a sparse Record at
        // env.getSource(). This unit-tier pin is the canonical assertion for the domain-return-type
        // conflict half against MutationServiceRecordField.
        var permit = mutationDmlPermitFixture();
        assertThat(permit.domainReturnType()).isInstanceOf(DomainReturnType.Record.class);
        assertThat(((DomainReturnType.Record) permit.domainReturnType()).table().tableName())
            .isEqualTo("film");
    }

    private static TableRef stubFilmTable() {
        return new TableRef(
            "film",
            "FILM",
            "com.example.jooq.tables.Film",
            "com.example.jooq.tables.records.FilmRecord",
            "com.example.jooq.Tables",
            List.of(),
            List.of());
    }

    private static MutationField.MutationDmlRecordField mutationDmlPermitFixture() {
        var tableRef = stubFilmTable();
        var tableInputArg = new no.sikt.graphitron.rewrite.ArgumentRef.InputTypeArg.TableInputArg(
            "in", "FilmInput", true, false, tableRef, List.of(), java.util.Optional.empty(),
            List.of(), List.of(), List.of());
        return new MutationField.MutationDmlRecordField(
            "Mutation", "createFilm", null,
            new ReturnTypeRef.ResultReturnType("FilmPayload", new FieldWrapper.Single(true), null, null),
            new OperationMember.Write.Insert(tableInputArg), java.util.Optional.empty());
    }

    private static List<Class<?>> collectLeaves(Class<?> sealed) {
        List<Class<?>> out = new ArrayList<>();
        collectLeavesInto(sealed, out);
        return out;
    }

    private static void collectLeavesInto(Class<?> node, List<Class<?>> out) {
        Class<?>[] permits = node.getPermittedSubclasses();
        if (permits == null || permits.length == 0) {
            if (node.isInterface() || Modifier.isAbstract(node.getModifiers())) return;
            out.add(node);
            return;
        }
        for (Class<?> p : permits) collectLeavesInto(p, out);
    }
}
