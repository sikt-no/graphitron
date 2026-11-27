-- From the connection down, each step a key probe; an edge two connections share is one row.
SELECT DISTINCT et.graph_name, et.type_name, et.kind, n.named_type AS item_type_name
  FROM graphitron_type c
  JOIN graphitron_field e
    ON e.graph_name = c.graph_name AND e.type_name = c.type_name AND e.field_name = 'edges'
  JOIN graphitron_type et
    ON et.graph_name = e.graph_name AND et.type_name = e.named_type AND et.kind = 'OBJECT'
  JOIN graphitron_field n
    ON n.graph_name = et.graph_name AND n.type_name = et.type_name AND n.field_name = 'node'
 WHERE c.kind = 'OBJECT'
