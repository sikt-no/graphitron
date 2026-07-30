package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the connection-synthesis relation: what visiting one Relay connection carrier field
 * synthesised, keyed by the carrier coordinate ({@link #parentTypeName()}, {@link #fieldName()}).
 * Produced by the classify walk's connection synthesis ({@code ConnectionPromoter}) and homed on
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema#connectionSynthesis()} as a post-walk
 * sidecar; consumed by the assembled-schema rebuild, the facet-misuse reduction, and the plan's
 * facet producers.
 *
 * <p>The two arms mirror the two carrier forms. {@link DirectiveDriven} is an
 * {@code @asConnection} on a bare list: the carrier's return type must be rewritten to name the
 * synthesised Connection, so the arm carries the rewrite facts ({@link DirectiveDriven#defaultPageSize()},
 * {@link DirectiveDriven#outerNonNull()}, {@link DirectiveDriven#rewritesCarrierReturnType()}).
 * {@link Structural} is a carrier whose SDL return type already names a declared Connection-shaped
 * type (with or without {@code @asConnection} alongside): nothing about the carrier changes, so
 * the arm carries none of the rewrite facts and consumers fold over the seal as a total switch
 * instead of probing for an optional rewrite.
 *
 * <p>Type-grain facts ({@code elementTypeName}, {@code itemNullable}, {@code edgeTypeName}, the
 * facet list) are deliberately <em>not</em> components of the row: they live on the registered
 * {@link GraphitronType.ConnectionType} / {@link GraphitronType.EdgeType} /
 * {@link GraphitronType.FacetsType} arms, where the type registry reconciles first-wins across
 * carriers minting one shared name; a row-side copy would disagree with the reconciled entry by
 * construction. Rows reference the registry through the relation's typed accessor
 * ({@link no.sikt.graphitron.rewrite.ConnectionSynthesisRelation#connectionTypeOf}) instead.
 */
public sealed interface ConnectionSynthesis {

    /** The carrier coordinate's parent type name (the first half of the relation key). */
    String parentTypeName();

    /** The carrier coordinate's field name (the second half of the relation key). */
    String fieldName();

    /** The Connection type name this carrier mints (directive-driven) or references (structural). */
    String connectionName();

    /**
     * The coordinate-grain names this carrier's visit registered (the Connection, the Edge, and,
     * for a faceted directive-driven carrier, the facets container), each with the arm it was
     * registered as and the absent-from-assembled discriminator. The schema-grain names the visit
     * also touches (the one shared {@code PageInfo}, the reusable {@code *FacetValue} pool) live
     * on the relation, not here.
     */
    List<MintedName> mintedNames();

    /**
     * The sealed {@link GraphitronType} arms connection synthesis can mint; the relation's
     * producer is the single producer of exactly these five, and {@link MintedName} enforces the
     * vocabulary at construction. The test-side synthesised-permit set
     * ({@code HierarchyKindRegistryTest.SYNTHESISED_TYPE_PERMITS}) and the corpus's
     * {@code SynthesisedType} declaration enum both derive from this constant.
     */
    Set<Class<? extends GraphitronType>> MINTED_ARM_VOCABULARY = Set.of(
        GraphitronType.ConnectionType.class,
        GraphitronType.EdgeType.class,
        GraphitronType.PageInfoType.class,
        GraphitronType.FacetsType.class,
        GraphitronType.FacetValueType.class);

    /**
     * One name connection synthesis registered: which sealed arm it was registered as
     * ({@code declaredArm}, restricted to {@link #MINTED_ARM_VOCABULARY}) and whether the name
     * was absent from the assembled schema at registration time ({@code absentFromAssembled}),
     * i.e. whether the post-walk rebuild must add it via {@code additionalType}. Carrying the
     * discriminator here lets the rebuild consume walk output instead of re-probing the schema.
     */
    record MintedName(String name, Class<? extends GraphitronType> declaredArm, boolean absentFromAssembled) {
        public MintedName {
            Objects.requireNonNull(name, "name");
            if (!MINTED_ARM_VOCABULARY.contains(declaredArm)) {
                throw new IllegalArgumentException(
                    "connection synthesis can only mint the declared arm vocabulary; got "
                    + (declaredArm == null ? "null" : declaredArm.getSimpleName()) + " for '" + name + "'");
            }
        }
    }

    /**
     * An {@code @asConnection} carrier on a bare list: the synthesis owns the Connection shape
     * and the carrier's graphql-java return type is rewritten to name it.
     * {@code rewritesCarrierReturnType} is almost always {@code true}; it is {@code false} only
     * when the declared base type already carries the minted connection name, in which case there
     * is no return-type swap to make.
     */
    record DirectiveDriven(
        String parentTypeName,
        String fieldName,
        String connectionName,
        int defaultPageSize,
        boolean outerNonNull,
        boolean rewritesCarrierReturnType,
        List<MintedName> mintedNames
    ) implements ConnectionSynthesis {
        public DirectiveDriven {
            mintedNames = List.copyOf(mintedNames);
        }
    }

    /**
     * A carrier whose SDL return type is a declared Connection-shaped type: the author owns the
     * shape, the visit registers the declared types as connection arms, and nothing about the
     * carrier is rewritten, so this arm carries no rewrite facts at all.
     */
    record Structural(
        String parentTypeName,
        String fieldName,
        String connectionName,
        List<MintedName> mintedNames
    ) implements ConnectionSynthesis {
        public Structural {
            mintedNames = List.copyOf(mintedNames);
        }
    }
}
