package no.sikt.graphitron.rewrite.derive;

import java.util.stream.IntStream;

/**
 * The one SDL fixture the register's cost gates capture, shared because both of them need the same
 * property and neither can state it for itself: the registered targets hold rows, and hold them in
 * proportion to schema size. Two do not, measured rather than assumed:
 * {@code intent_mutation_payload_key_membership} and {@code intent_mutation_payload_refusal} are
 * empty at every size, their rules reading a {@code @mutation} payload surface this schema holds
 * fixed. A
 * gate over this fixture that needs a populated target should establish it per target, as
 * {@code RefreshPrerequisiteStatisticsTest} does, rather than read the property here as universal. A gate over empty relations measures its instrument's floor and
 * reports a number, which is the failure mode {@link DerivedReadCostTest}'s own history records
 * three separate times.
 *
 * <p>Shared rather than copied for the reason the size note below gives: what this schema populates
 * is a fact about the register as it stands, re-established every time a registration is added, and
 * a second copy would be the one that quietly stopped populating something. The two gates ask
 * different questions of it, one what a registration costs another reader and the other whether the
 * refresh's plans depend on statistics, and both answers are only worth as much as the population
 * behind them.
 */
final class MaterializedRegistryFixture {

    private MaterializedRegistryFixture() {}

    /**
     * The fixture: {@code units} repetitions of a film/language/inventory/store cluster of node types
     * over real catalog keys, plus one routine field, the mutations, and a routine-carrier cluster
     * per unit, so that nodehood, reference chains, node-id decoding, argument mapping and the
     * carrier family all have rows.
     *
     * <p>The routine-carrier cluster is a mutation-root {@code @routine} field per unit returning a
     * payload type that wraps one nullable data field beside an errors channel, the channel a union
     * whose one member carries {@code @error}. That shape is what populates
     * {@code intent_errors_field} and the relations over it ({@code intent_carrier_data_field},
     * {@code intent_field_error_channel}, {@code intent_mutation_routine_seat},
     * {@code intent_carrier_routine_hop}), all of which held no rows here before it: the fixture's
     * only other {@code @routine} sits on the Query root, so the seat relation was empty, and
     * {@code Film0}'s {@code @reference} fields disqualify it from the carrier view. Scaled with the
     * units so those targets hold rows proportional to schema size, like everything else here.
     *
     * <p>Every registered target is populated by this schema and so is every reader the read-cost
     * gate prices except the defect relations, which hold rows only on a schema with the defect in it
     * and whose emptiness here is the fixture being well-formed rather than being thin. A schema of
     * {@code @table}-bound types with a single scalar field, which is what the scaled fixtures
     * elsewhere in the reactor use, leaves much of {@code meta_materialize}'s roster and most of that
     * gate's readers empty, and a gate over empty relations measures the instrument's floor and
     * nothing else.
     *
     * <p>The per-unit filter input is in that list for the same reason and is the second such arm.
     * Before it the fixture's whole input surface was two fields on two mutation payloads, neither
     * scaled with the units and neither carrying a {@code @reference}, so the input-field resolution
     * relations held a couple of flat rows and their reference walk held none. A gate over relations
     * that size prices the counter rather than the work, and it showed: with the surface flat, the
     * resolving-table registration read as a regression against all three of its readers, and with
     * the surface scaled every one of those three went monotonic. The input is deliberately three
     * shapes rather than one, a plain column name, a nested input object so the descent has depth,
     * and a {@code @reference}-pathed field so the walk has a chain to follow, because the three
     * relations fork on exactly those.
     *
     * <p>{@code inventoryForFilm} is in that list for one target's sake and is the arm to understand
     * before touching it. A node-id argument populates the decode walk's hop relations only where the
     * argument's own scope table differs from the node type's table <em>and</em> exactly one foreign
     * key joins the two, which is what the endpoint view's {@code DISCOVERED_KEY} arm requires. The
     * {@code storeForFilm} arm above looks like it should qualify and does not: film reaches store
     * through inventory rather than directly, so no single key joins them and the endpoint contributes
     * no hop. Returning inventory instead gives the one key inventory declares on film. Without this
     * arm the hop-column target holds no rows at any size and its cell in the read-cost gate is a
     * comparison between two readings of an empty table, which is the state that made that test's own
     * claim about a populated fixture untrue of the hop-column registration. With it, that cell is the
     * widest ratio in the matrix by wall clock, tens of milliseconds registered against seconds
     * unregistered, which is the shape that registration's own registry reason describes.
     *
     * <p>{@code media} is the third such arm and it is a per-unit union of two of the cluster's own
     * bound types with one filter argument over a column both their tables carry. Before it the
     * fixture had no multi-table polymorphic root at all, so the participant fan-out held no rows at
     * any size: the field scope's participant arm, and therefore the branch multiplicity every
     * relation below it inherits, priced as an empty relation. That is the state a previous increment
     * of this work walked into from the other direction, reading a shape choice off a fixture whose
     * units made both correlated arms of a ranked view unselective, and the lesson is the same one in
     * reverse. A gate blind to a shape does not price it conservatively; it prices the instrument's
     * floor and reports a number. The union's members are the existing {@code Film} and
     * {@code Inventory} types rather than new ones, so the arm adds branches to price without adding
     * a table, and the filter column is the key one of them declares on the other, which is what
     * makes the name resolve on both branches instead of on one.
     */
    static String scaledSdl(int units) {
        var sdl = new StringBuilder("""
            interface Node { id: ID! }
            input FilmInput { title: String }
            input FilmKeyInput { filmId: Int! @field(name: "film_id") }
            type Rental @table(name: "rental") { rentalId: Int @field(name: "rental_id") }
            """);
        sdl.append("type Mutation {\n").append("""
              createFilm(in: FilmInput!): Film0 @mutation(typeName: INSERT)
              createFilms(in: [FilmInput!]!): [Film0!]! @mutation(typeName: INSERT)
              deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
              rentFilm%1$d(inventoryId: Int!, customerId: Int!): RentFilmPayload%1$d
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            """.formatted(i)));
        sdl.append("}\n");
        IntStream.range(0, units).forEach(i -> sdl.append("""
            type RentFilmFailed%1$d @error(handlers: [{
                handler: GENERIC,
                className: "org.jooq.exception.IntegrityConstraintViolationException"
              }]) {
              path: [String!]!
              message: String!
            }
            union RentFilmError%1$d = RentFilmFailed%1$d
            type RentFilmPayload%1$d {
              rental: Rental
              errors: [RentFilmError%1$d]
            }
            """.formatted(i)));
        IntStream.range(0, units).forEach(i -> sdl.append("""
            input NestedFilmFilter%1$d {
              releaseYear: Int @field(name: "release_year")
            }
            input FilmFilter%1$d {
              title: String
              nested: NestedFilmFilter%1$d
              inStore: Int @field(name: "store_id")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film%1$d implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
              title: String
              releaseYear: Int @field(name: "release_year")
              language: Language%1$d @reference(path: [{key: "film_language_id_fkey"}])
              inventory: [Inventory%1$d!]! @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Language%1$d implements Node @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
              name: String
            }
            type Inventory%1$d implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
              id: ID! @nodeId
              film: Film%1$d @reference(path: [{key: "inventory_film_id_fkey"}])
              store: Store%1$d @reference(path: [{key: "inventory_store_id_fkey"}])
            }
            type Store%1$d implements Node @table(name: "store") @node(keyColumns: ["store_id"]) {
              id: ID! @nodeId
              inventory: [Inventory%1$d!]! @reference(path: [{key: "inventory_store_id_fkey"}])
            }
            union Media%1$d = Film%1$d | Inventory%1$d
            """.formatted(i)));
        sdl.append("type Query {\n").append("""
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
              films%1$d(filter: FilmFilter%1$d): [Film%1$d!]!
              film%1$d(id: ID! @nodeId(typeName: "Film%1$d")): Film%1$d
              filmsByKey%1$d(film_id: [ID] @lookupKey): [Film%1$d!]!
              storeForFilm%1$d(id: ID! @nodeId(typeName: "Film%1$d")): [Store%1$d!]! @reference(path: [
                {key: "inventory_film_id_fkey"},
                {key: "inventory_store_id_fkey"}
              ])
              inventoryForFilm%1$d(id: ID! @nodeId(typeName: "Film%1$d")): [Inventory%1$d!]!
              media%1$d(filmId: Int @field(name: "film_id")): [Media%1$d!]!
            """.formatted(i)));
        return sdl.append("}\n").toString();
    }
}
