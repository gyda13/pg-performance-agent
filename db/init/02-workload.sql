-- Demo workload: plants deliberately bad query patterns so pg_stat_statements accumulates
-- the signals needed for pathology classification.
-- Patterns: N+1, deep OFFSET, implicit cast, unbounded result, leading wildcard.

DO $$ BEGIN
  CREATE EXTENSION IF NOT EXISTS hypopg;
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'hypopg not available — Phase 2 will be skipped: %', SQLERRM;
END $$;

CREATE TABLE IF NOT EXISTS order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    qty         INT NOT NULL,
    price_cents BIGINT NOT NULL
);

INSERT INTO order_items (order_id, product_id, qty, price_cents)
SELECT (1 + floor(random() * 2000000))::bigint,
       (1 + floor(random() * 10000))::bigint,
       (1 + floor(random() * 5))::int,
       (floor(random() * 10000))::bigint
FROM generate_series(1, 2000000);

ANALYZE order_items;

DO $$
DECLARE
  v_id bigint;
BEGIN

  -- N+1 storm: 2000 single-row FK lookups.
  -- EXECUTE ... USING tracks as SELECT ... WHERE id = $1 with calls=2000 in pg_stat_statements.
  -- PERFORM would record as WHERE id = v_id (variable name, not normalized) — wrong signal.
  FOR v_id IN 1..2000 LOOP
    EXECUTE 'SELECT id, email, country FROM customers WHERE id = $1' USING v_id;
  END LOOP;

  -- Deep OFFSET: Postgres must scan and discard 50k+ rows per page.
  FOR v_id IN 1..30 LOOP
    EXECUTE 'SELECT id, status, total_cents FROM orders ORDER BY created_at LIMIT $1 OFFSET $2'
      USING 20, 50000;
    EXECUTE 'SELECT id, status, total_cents FROM orders ORDER BY created_at LIMIT $1 OFFSET $2'
      USING 20, 100000;
  END LOOP;

  -- Implicit cast: id::text kills the btree index on id.
  FOR v_id IN 1..50 LOOP
    EXECUTE 'SELECT id, customer_id, status FROM orders WHERE id::text LIKE $1' USING '10%';
  END LOOP;

  -- Unbounded result: no LIMIT, returns ~120k rows per call.
  FOR v_id IN 1..10 LOOP
    EXECUTE 'SELECT oi.id, oi.order_id, oi.product_id
             FROM order_items oi
             JOIN orders o ON o.id = oi.order_id
             WHERE o.status = $1' USING 'pending';
  END LOOP;

  -- Leading wildcard: LIKE ''%@example.com'' cannot use a btree index on email.
  FOR v_id IN 1..100 LOOP
    EXECUTE 'SELECT id, email FROM customers WHERE email LIKE $1' USING '%@example.com';
  END LOOP;

  -- Stale stats: `events` was never ANALYZEd (see 01-init.sql), so the planner under-estimates
  -- its row count and the join against customers gets a poor plan. The fix is ANALYZE, not an
  -- index — EXPLAIN ANALYZE shows the estimated-vs-actual rows diverging by orders of magnitude.
  FOR v_id IN 1..20 LOOP
    EXECUTE 'SELECT e.type, count(*) FROM events e
             JOIN customers c ON c.id = e.customer_id
             WHERE e.type = $1 GROUP BY e.type' USING 'view';
  END LOOP;

END$$;
