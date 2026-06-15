-- =============================================================================
-- Demo database init. Runs automatically the first time the Docker volume is created.
--
-- This is your TEST FIXTURE. The goal is not "a database with data in it" — it is a
-- database with SPECIFIC, KNOWN problems, so you can verify the agent finds the right
-- answer. You should know the correct diagnosis before the agent runs.
--
-- This file gives you a minimal starting point with ONE planted problem and a clear knob
-- to scale up. Expand it (see the TODO list at the bottom) into the full fixture.
-- =============================================================================

-- --- Extensions -------------------------------------------------------------
-- pg_stat_statements is loaded via shared_preload_libraries (see docker-compose.yml);
-- this just exposes the view in this database.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- HypoPG (hypothetical indexes) is needed for Phase 2. It is NOT bundled in the stock
-- postgres image, so this is commented out — enabling it here would make `docker compose up`
-- fail. When you reach Phase 2, build a custom image that installs postgresql-16-hypopg
-- and then uncomment the next line.
-- CREATE EXTENSION IF NOT EXISTS hypopg;


-- --- Schema -----------------------------------------------------------------
CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    email       TEXT NOT NULL,
    country     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT NOT NULL,          -- NOTE: deliberately NOT indexed (planted problem)
    status       TEXT NOT NULL,            -- skewed: mostly 'completed'
    total_cents  BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- --- Seed -------------------------------------------------------------------
-- Knob: bump these up to make the planner's decisions realistic. The planted problems
-- only "hurt" at scale — a seq scan over 10k rows is cheap, so the agent has nothing to
-- find. Aim for ORDERS in the tens of millions once you're ready (start small to iterate).
--   customers: 100k   orders: ~2M   (starter values — increase later)
INSERT INTO customers (email, country)
SELECT 'user' || g || '@example.com',
       -- skewed country distribution: most rows are 'SA'
       CASE WHEN random() < 0.85 THEN 'SA'
            WHEN random() < 0.5  THEN 'AE'
            ELSE 'US' END
FROM generate_series(1, 100000) AS g;

INSERT INTO orders (customer_id, status, total_cents)
SELECT (1 + floor(random() * 100000))::bigint,
       -- skewed status: ~90% completed, the rest split among rarer states
       CASE WHEN random() < 0.90 THEN 'completed'
            WHEN random() < 0.6  THEN 'pending'
            WHEN random() < 0.5  THEN 'cancelled'
            ELSE 'refunded' END,
       (floor(random() * 50000))::bigint
FROM generate_series(1, 2000000) AS g;

-- Index the primary keys only (BIGSERIAL gives those for free). customer_id is intentionally
-- left unindexed so the agent can discover the missing-FK-index problem on the join/filter.

-- Build planner statistics so estimates are sane for the indexed columns.
ANALYZE customers;
-- TODO (planted problem: stale stats): leave `orders` UN-analyzed for one scenario, so the
-- agent has to notice bad row estimates and consider ANALYZE as the fix. For now we analyze it.
ANALYZE orders;

-- Reset stats so the agent measures fresh numbers from the workload you run, not the seed.
SELECT pg_stat_statements_reset();


