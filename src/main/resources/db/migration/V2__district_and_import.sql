CREATE TABLE district (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    adcode    varchar(12) NOT NULL UNIQUE,
    name      text NOT NULL,
    geom      geometry(MultiPolygon, 4326) NOT NULL,
    geom_4549 geometry(MultiPolygon, 4549)
);

CREATE INDEX idx_district_geom      ON district USING GIST (geom);
CREATE INDEX idx_district_geom_4549 ON district USING GIST (geom_4549);

CREATE TABLE import_task (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filename    text NOT NULL,
    status      text NOT NULL DEFAULT 'PENDING',
    total_cnt   integer DEFAULT 0,
    success_cnt integer DEFAULT 0,
    failed_cnt  integer DEFAULT 0,
    error_msg   text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE poi_staging (
    name      text,
    category  text,
    lng       double precision,
    lat       double precision,
    source_id text
);
