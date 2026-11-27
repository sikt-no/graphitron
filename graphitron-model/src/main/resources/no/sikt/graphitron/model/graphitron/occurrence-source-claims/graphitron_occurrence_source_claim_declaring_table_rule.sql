SELECT DISTINCT o.graph_name, o.coordinate, 'declaringTable' AS rule, 'TABLE' AS kind, tt.table_source_name, tt.table_schema, tt.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_field f ON f.graph_name = o.graph_name AND f.coordinate = o.element_coordinate
  JOIN graphitron_tabletype tt ON tt.graph_name = f.graph_name AND tt.type_name = f.type_name
 WHERE o.kind = 'FIELD' AND o.depth = 0
