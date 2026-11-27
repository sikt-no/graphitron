SELECT DISTINCT o.graph_name, o.coordinate, 'rootRoutine' AS rule, 'TABLE' AS kind, st.source_name AS table_source_name, st.table_schema, st.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_field f ON f.graph_name = o.graph_name AND f.coordinate = o.element_coordinate
  JOIN graphql_root_operation ro ON ro.graph_name = f.graph_name AND ro.type_name = f.type_name
  JOIN graphitron_producer_routine pr
    ON pr.graph_name = f.graph_name AND pr.type_name = f.type_name AND pr.field_name = f.field_name
  JOIN sql_table st
    ON st.source_name = pr.routine_source_name AND st.table_schema = pr.table_schema
   AND st.table_name = pr.routine_name AND st.table_type = 'FUNCTION'
 WHERE o.kind = 'FIELD' AND o.depth = 0
