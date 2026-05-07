package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the leaf-coverage parser. Covers the brace-tracking that produces the
 * {@code enclosingClass} field, the permits-chain walker that picks the FQN form
 * matching {@code ClassificationTrace}'s emission, and the roadmap-mention join.
 *
 * <p>These tests build small synthetic source files in a {@link TempDir} so the test
 * doesn't depend on the live model layout drifting under it.
 */
class LeafCoverageReportTest {

    @TempDir
    Path tempDir;

    private Path modelDirWith(String... files) throws IOException {
        Path dir = tempDir.resolve("model");
        Files.createDirectories(dir);
        for (int i = 0; i < files.length; i += 2) {
            Files.writeString(dir.resolve(files[i] + ".java"), files[i + 1]);
        }
        return dir;
    }

    @Test
    void parser_associatesNestedRecordWithEnclosingClass_notSealedParent() throws IOException {
        // MutationField.java pattern: an inner sealed (DmlTableField) groups four records
        // declared at the outer (MutationField) level. Trace emits "MutationField.X" because
        // X.getEnclosingClass() is MutationField.
        Path dir = modelDirWith(
            "GraphitronField", """
                package model;
                public sealed interface GraphitronField permits RootField {}
                """,
            "RootField", """
                package model;
                public sealed interface RootField extends GraphitronField permits MutationField {}
                """,
            "MutationField", """
                package model;
                public sealed interface MutationField extends RootField
                    permits MutationField.DmlTableField, MutationField.MutationServiceTableField {

                    sealed interface DmlTableField extends MutationField
                        permits MutationField.MutationInsertTableField,
                                MutationField.MutationDeleteTableField {}

                    record MutationInsertTableField(String name) implements DmlTableField {}
                    record MutationDeleteTableField(String name) implements DmlTableField {}
                    record MutationServiceTableField(String name) implements MutationField {}
                }
                """);

        List<LeafCoverageReport.Leaf> leaves = LeafCoverageReport.parseLeaves(dir);
        // The classifier-side leafName helper builds <enclosing>.<simple>; verify the parser
        // produces the same form so the JOIN against trace.leaf hits.
        assertThat(leaves).extracting(LeafCoverageReport.Leaf::fqn)
            .contains(
                "MutationField.MutationInsertTableField",
                "MutationField.MutationDeleteTableField",
                "MutationField.MutationServiceTableField");
        // No FQN should use the sealed parent (DmlTableField) as the prefix — that mismatches
        // the trace and breaks the JOIN.
        assertThat(leaves).extracting(LeafCoverageReport.Leaf::fqn)
            .noneMatch(fqn -> fqn.startsWith("DmlTableField."));
    }

    @Test
    void parser_handlesRecordsDeclaredInsideSealedInterface() throws IOException {
        // QueryField.java pattern: records nested directly inside the sealed interface. Trace
        // emits "QueryField.X" — same enclosing-class pattern but a single nesting level.
        Path dir = modelDirWith(
            "RootField", """
                package model;
                public sealed interface RootField permits QueryField {}
                """,
            "QueryField", """
                package model;
                /** A field on the Query type. */
                public sealed interface QueryField extends RootField
                    permits QueryField.QueryTableField, QueryField.QueryNodeField {

                    /** Top-level table fetcher. */
                    record QueryTableField(String name) implements QueryField {}
                    /** Relay node lookup. */
                    record QueryNodeField(String name) implements QueryField {}
                }
                """);

        List<LeafCoverageReport.Leaf> leaves = LeafCoverageReport.parseLeaves(dir);
        assertThat(leaves).extracting(LeafCoverageReport.Leaf::fqn)
            .containsExactlyInAnyOrder("QueryField.QueryTableField", "QueryField.QueryNodeField");
        assertThat(leaves).extracting(LeafCoverageReport.Leaf::intent)
            .contains("Top-level table fetcher", "Relay node lookup");
    }

    @Test
    void parser_returnsEmptyIntent_whenJavadocBelongsToParent() throws IOException {
        // Records declared back-to-back share the parent type's javadoc by proximity. The parser
        // should not steal the parent's intent — it returns empty so the report shows
        // "_(no javadoc)_" rather than misattributing.
        Path dir = modelDirWith(
            "RootField", """
                package model;
                public sealed interface RootField permits MutationField {}
                """,
            "MutationField", """
                package model;
                /** Parent javadoc — describes the sealed interface, not any individual record. */
                public sealed interface MutationField extends RootField permits MutationField.A, MutationField.B {
                    record A(String n) implements MutationField {}
                    record B(String n) implements MutationField {}
                }
                """);

        List<LeafCoverageReport.Leaf> leaves = LeafCoverageReport.parseLeaves(dir);
        assertThat(leaves).extracting(LeafCoverageReport.Leaf::simpleName, LeafCoverageReport.Leaf::intent)
            .contains(
                org.assertj.core.groups.Tuple.tuple("A", ""),
                org.assertj.core.groups.Tuple.tuple("B", ""));
    }

    @Test
    void parser_excludesOtherTopLevelHierarchies_fromGraphitronFieldWalk() throws IOException {
        // GraphitronField permits RootField, ChildField, InputField, plus its own nested
        // UnclassifiedField. The GraphitronField walk should only emit UnclassifiedField; the
        // others are walked under their own hierarchy.
        Path dir = modelDirWith(
            "GraphitronField", """
                package model;
                public sealed interface GraphitronField
                    permits RootField, ChildField, InputField, GraphitronField.UnclassifiedField {

                    record UnclassifiedField(String reason) implements GraphitronField {}
                }
                """,
            "RootField", """
                package model;
                public sealed interface RootField extends GraphitronField permits RootField.QueryRoot {
                    record QueryRoot(String name) implements RootField {}
                }
                """,
            "ChildField", """
                package model;
                public sealed interface ChildField extends GraphitronField permits ChildField.ChildLeaf {
                    record ChildLeaf(String name) implements ChildField {}
                }
                """,
            "InputField", """
                package model;
                public sealed interface InputField extends GraphitronField permits InputField.InputLeaf {
                    record InputLeaf(String name) implements InputField {}
                }
                """,
            "GraphitronType", """
                package model;
                public sealed interface GraphitronType permits GraphitronType.TableType {
                    record TableType(String name) implements GraphitronType {}
                }
                """);

        List<LeafCoverageReport.Leaf> leaves = LeafCoverageReport.parseLeaves(dir);
        // GraphitronField hierarchy has exactly UnclassifiedField (its own direct leaf).
        assertThat(leaves.stream()
            .filter(l -> "GraphitronField".equals(l.hierarchy())).toList())
            .extracting(LeafCoverageReport.Leaf::simpleName)
            .containsExactly("UnclassifiedField");
        // The other hierarchies stay isolated.
        assertThat(leaves.stream()
            .filter(l -> "RootField".equals(l.hierarchy())).toList())
            .extracting(LeafCoverageReport.Leaf::simpleName).containsExactly("QueryRoot");
        assertThat(leaves.stream()
            .filter(l -> "ChildField".equals(l.hierarchy())).toList())
            .extracting(LeafCoverageReport.Leaf::simpleName).containsExactly("ChildLeaf");
    }

    @Test
    void parseMentions_findsLeafSimpleNamesInRoadmapBodies() throws IOException {
        Path dir = modelDirWith(
            "RootField", """
                package model;
                public sealed interface RootField permits RootField.QueryTableField {
                    record QueryTableField(String name) implements RootField {}
                }
                """);
        List<LeafCoverageReport.Leaf> leaves = LeafCoverageReport.parseLeaves(dir);

        Path roadmapDir = tempDir.resolve("roadmap");
        Files.createDirectories(roadmapDir);
        Files.writeString(roadmapDir.resolve("foo.md"),
            "---\nid: R42\nstatus: Spec\n---\n\nMentions QueryTableField in body.");
        Files.writeString(roadmapDir.resolve("bar.md"),
            "---\nid: R43\nstatus: Backlog\n---\n\nNo leaf names here.");

        List<LeafCoverageReport.Mention> mentions =
            LeafCoverageReport.parseMentions(roadmapDir, leaves);
        assertThat(mentions).extracting(LeafCoverageReport.Mention::leafSimpleName,
                LeafCoverageReport.Mention::roadmapId)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("QueryTableField", "R42"));
    }
}
