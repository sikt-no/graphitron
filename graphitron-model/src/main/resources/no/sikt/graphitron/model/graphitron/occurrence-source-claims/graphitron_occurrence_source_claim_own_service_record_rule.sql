SELECT DISTINCT o.graph_name, o.coordinate, 'ownServiceRecord' AS rule, 'TABLE' AS kind, r.table_source_name, r.table_schema, r.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence_producer op ON op.graph_name = o.graph_name AND op.coordinate = o.coordinate
  JOIN graphitron_occurrence_producer_result r ON r.graph_name = op.graph_name AND r.coordinate = op.coordinate
 WHERE o.kind = 'FIELD' AND op.kind = 'SERVICE' AND op.start = 'CHILD' AND r.table_name IS NOT NULL
