package no.sikt.graphitron.datafetchers.standard.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Mutation resolvers - Resolvers with nodeId for mutations")
public class NodeIdResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/edit/standard";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("A mutation that uses nodeIdStrategy")
    void nodeIdStrategyWithBatching() {
        assertGeneratedContentContains(
                "withBatching/nodeId", Set.of(CUSTOMER_INPUT_TABLE),
                ".mutationForMutation(_iv_transform.getCtx(), _iv_nodeIdStrategy, _mi_inRecord)",
                ".mutationForMutation(_iv_ctx, _iv_nodeIdStrategy, _mi_inRecord, _iv_selectionSet)"
        );
    }

    @Test
    @DisplayName("Assert overlapping fields")
    void assertInputColumns() {
        assertGeneratedContentContains(
                "assertInputColumns", Set.of(NODE),
                        """
                        FilmActorInput _mi_in = ResolverHelpers.transformDTO(_iv_env.getArgument("in"), FilmActorInput.class);
                        var _iv_transform = new RecordTransformer(_iv_env);
                        var _mi_inRecord = _iv_transform.filmActorInputToJOOQRecord(_mi_in, _iv_nodeIdStrategy, "in");
                        var _unpacked_id = _mi_in.getId() != null ? _iv_nodeIdStrategy.unpackIdValues("FilmActor", _mi_in.getId(), FilmActor.FILM_ACTOR.getPrimaryKey().getFieldsArray()) : null;
                        var _val_id_ACTOR_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null;
                        var _val_actor_ACTOR_ID = _mi_in.getActor();
                        if (_val_id_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
                            throw new IllegalArgumentException("Field id and field actor differs in value but writes to the same column.");
                        }
                        """
        );
    }

    @Test
    @DisplayName("Assert overlapping fields with listed input record")
    void assertListedInputColumns() {
        assertGeneratedContentContains(
                "assertListedInputColumns", Set.of(NODE),
                """
                        List<FilmActorInput> _mi_in = ResolverHelpers.transformDTOList(_iv_env.getArgument("in"), FilmActorInput.class);
                        var _iv_transform = new RecordTransformer(_iv_env);
                        var _mi_inRecordList = _iv_transform.filmActorInputToJOOQRecord(_mi_in, _iv_nodeIdStrategy, "in");
                        for (var _nit__mi_in : _mi_in) {
                            var _unpacked_id = _nit__mi_in.getId() != null ? _iv_nodeIdStrategy.unpackIdValues("FilmActor", _nit__mi_in.getId(), FilmActor.FILM_ACTOR.getPrimaryKey().getFieldsArray()) : null;
                            var _val_id_ACTOR_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null;
                            var _val_actor_ACTOR_ID = _nit__mi_in.getActor();
                            if (_val_id_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
                                throw new IllegalArgumentException("Field id and field actor differs in value but writes to the same column.");
                            }
                        }
                        """
                );
    }

    @Test
    @DisplayName("Assert overlapping NodeIds")
    void assertOverlappingNodeIds() {
        assertGeneratedContentContains("assertOverlappingNodeIds", Set.of(NODE),
                """
                        var _val_id_ACTOR_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null;
                        var _val_secondId_ACTOR_ID = _unpacked_secondId != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_secondId[0]) : null;
                        if (_val_id_ACTOR_ID != null && _val_secondId_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_secondId_ACTOR_ID)) {
                            throw new IllegalArgumentException("Field id and field secondId differs in value but writes to the same column.");
                        }
                        var _val_id_FILM_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.FILM_ID, _unpacked_id[1]) : null;
                        var _val_secondId_FILM_ID = _unpacked_secondId != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.FILM_ID, _unpacked_secondId[1]) : null;
                        if (_val_id_FILM_ID != null && _val_secondId_FILM_ID != null && !_val_id_FILM_ID.equals(_val_secondId_FILM_ID)) {
                            throw new IllegalArgumentException("Field id and field secondId differs in value but writes to the same column.");
                        }
                        """
        );
    }

    @Test
    @DisplayName("Assert many overlapping fields")
    void assertMultipleOverlappingFields() {
        assertGeneratedContentContains("assertManyOverlappingFields", Set.of(NODE),
                """
                            var _val_id_ACTOR_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_id[0]) : null;
                            var _val_secondId_ACTOR_ID = _unpacked_secondId != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.ACTOR_ID, _unpacked_secondId[0]) : null;
                            var _val_actor_ACTOR_ID = _nit__mi_in.getActor();
                            if (_val_id_ACTOR_ID != null && _val_secondId_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_secondId_ACTOR_ID)) {
                                throw new IllegalArgumentException("Field id and field secondId differs in value but writes to the same column.");
                            }
                            if (_val_id_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_id_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
                                throw new IllegalArgumentException("Field id and field actor differs in value but writes to the same column.");
                            }
                            if (_val_secondId_ACTOR_ID != null && _val_actor_ACTOR_ID != null && !_val_secondId_ACTOR_ID.equals(_val_actor_ACTOR_ID)) {
                                throw new IllegalArgumentException("Field secondId and field actor differs in value but writes to the same column.");
                            }
                            var _val_id_FILM_ID = _unpacked_id != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.FILM_ID, _unpacked_id[1]) : null;
                            var _val_secondId_FILM_ID = _unpacked_secondId != null ? _iv_nodeIdStrategy.getFieldValue(FilmActor.FILM_ACTOR.FILM_ID, _unpacked_secondId[1]) : null;
                            if (_val_id_FILM_ID != null && _val_secondId_FILM_ID != null && !_val_id_FILM_ID.equals(_val_secondId_FILM_ID)) {
                                throw new IllegalArgumentException("Field id and field secondId differs in value but writes to the same column.");
                            }
                        """);
    }

    @Test
    @DisplayName("A delete mutation that uses nodeIdStrategy")
    void nodeIdStrategyInDeleteMutation() {
        GeneratorConfig.setUseJdbcBatchingForDeletes(false);
        assertGeneratedContentContains(
                "withReturning/withNodeIdStrategy", Set.of(CUSTOMER_INPUT_TABLE),
                ".mutationForMutation(_iv_ctx, _iv_nodeIdStrategy, _mi_inRecord, _iv_selectionSet)"
        );
        GeneratorConfig.setUseJdbcBatchingForDeletes(true);
    }
}
