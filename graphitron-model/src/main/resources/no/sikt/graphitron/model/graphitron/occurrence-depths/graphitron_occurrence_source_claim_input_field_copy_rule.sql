SELECT DISTINCT o.graph_name, o.coordinate, 'inputFieldCopy' AS rule, 'TABLE' AS kind, pt.table_source_name, pt.table_schema, pt.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_occurrence_source_table pt ON pt.graph_name = p.graph_name AND pt.coordinate = p.coordinate
 WHERE o.kind = 'INPUT_FIELD'
