CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE poi (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text NOT NULL,
    category   text NOT NULL,
    address    text,
    lng        double precision NOT NULL,
    lat        double precision NOT NULL,
    geom       geometry(Point, 4326) NOT NULL,
    geom_4549  geometry(Point, 4549),
    source_id  text UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_poi_geom      ON poi USING GIST (geom);
CREATE INDEX idx_poi_geom_4549 ON poi USING GIST (geom_4549);
CREATE INDEX idx_poi_category  ON poi (category);
CREATE INDEX idx_poi_cat_4549  ON poi (category, geom_4549);
