# Demo Postgres image: official postgres:16 plus the HypoPG extension, which Phase 2
# needs for hypothetical-index testing. pg_stat_statements ships with core contrib.
FROM postgres:16
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-16-hypopg \
 && rm -rf /var/lib/apt/lists/*
