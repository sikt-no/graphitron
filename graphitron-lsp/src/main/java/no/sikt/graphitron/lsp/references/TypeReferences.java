package no.sikt.graphitron.lsp.references;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.SdlTypeUsages;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAMED_TYPE;

/**
 * Find-references for an SDL type name: the cursor sits on a type's declaration name
 * ({@code type Film}) or on a reference to it ({@code films: [Film!]!}), and the answer is every
 * other site in the schema that uses that type.
 *
 * <p>The reverse of {@code IntraSchemaDefinitions}, and it takes the same two cursor shapes that
 * provider and {@code DeclarationDefinitions} take between them, because the reverse question is
 * asked from the same places the forward one is: an author on a declaration asks who uses this, and
 * an author on a use asks who else does. Both resolve to a type name and then to one population, so
 * the two shapes differ here only in how the name is read off the tree.
 *
 * <p>Where the forward direction reads the workspace's open buffers first and falls back to the
 * store, this reads the store alone. {@code IntraSchemaDefinitions}' javadoc carries why it does
 * the opposite: for one declaration an open buffer holds the whole answer, so preferring it is
 * strictly better. Here a buffer holds only the fraction of the answer that happens to be open, and
 * a list assembled half from live buffers and half from the last capture would be fresher in a way
 * nobody could explain or predict. So the whole list rides one cadence, the capture's, and the user
 * manual says so.
 */
public final class TypeReferences {

    /**
     * The GraphQL spec's own scalars. A cursor on {@code String} is on a type the language defines,
     * and listing every field in the schema that returns one is noise rather than an answer. The
     * same set {@code IntraSchemaDefinitions} declines to jump for, declined here for a different
     * reason: there the population is empty, here it is uselessly large.
     */
    private static final Set<String> BUILTIN_SCALARS = Set.of("Int", "Float", "String", "Boolean", "ID");

    private TypeReferences() {}

    /**
     * The sites using the type the cursor names, or empty when the cursor is not on a type name at
     * all. A cursor on a field or input-value declaration name resolves to no type: whose uses that
     * would mean is the member-usage question, which this surface does not answer yet.
     */
    public static List<Location> compute(
        FileSnapshot file, Optional<StoreHandle> store, Point pos, boolean includeDeclaration
    ) {
        return store.map(handle -> compute(file, handle, pos, includeDeclaration)).orElseGet(List::of);
    }

    public static List<Location> compute(
        FileSnapshot file, StoreHandle store, Point pos, boolean includeDeclaration
    ) {
        if (file == null || file.tree() == null) return List.of();
        return typeNameAt(file, pos)
            .filter(name -> !BUILTIN_SCALARS.contains(name))
            .map(name -> SdlTypeUsages.of(store, name, includeDeclaration))
            .orElseGet(List::of);
    }

    /**
     * The type the cursor names, under either shape. The declaration arm goes through
     * {@link SdlDeclaration#findContaining}, the same primitive the declaration hover and jump key
     * on, so "is this leaf a declaration name?" keeps one answer across the three surfaces that ask.
     * The reference arm is the {@code named_type} test, which is disjoint from it: a name token is
     * either a declaration's or a reference's, never both.
     */
    private static Optional<String> typeNameAt(FileSnapshot file, Point pos) {
        Node root = file.tree().getRootNode();
        var declaration = SdlDeclaration.findContaining(root, pos, file.source());
        if (declaration.isPresent()) {
            return declaration.get() instanceof SdlDeclaration.TypeName typeName
                ? Optional.of(typeName.typeName())
                : Optional.empty();
        }
        Node leaf = root.getDescendant(pos, pos).orElse(null);
        if (leaf == null || !NAME.matches(leaf)) return Optional.empty();
        Node parent = leaf.getParent().orElse(null);
        if (parent == null || !NAMED_TYPE.matches(parent)) return Optional.empty();
        return Optional.of(Nodes.text(leaf, file.source()));
    }
}
