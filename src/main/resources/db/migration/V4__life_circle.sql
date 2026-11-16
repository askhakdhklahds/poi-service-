CREATE TABLE population_grid (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    grid_x          integer NOT NULL,
    grid_y          integer NOT NULL,
    cell_size_m     integer NOT NULL DEFAULT 500,
    lon             double precision NOT NULL,
    lat             double precision NOT NULL,
    pop             double precision NOT NULL CHECK (pop >= 0),
    district_adcode varchar(12),
    geom            geometry(Point, 4326) NOT NULL,
    geom_4549       geometry(Point, 4549) NOT NULL,
    UNIQUE (grid_x, grid_y, cell_size_m)
);

CREATE INDEX idx_population_grid_geom_4549
    ON population_grid USING GIST (geom_4549);
CREATE INDEX idx_population_grid_district_pop
    ON population_grid (district_adcode, pop);

CREATE TABLE population_grid_staging (
    grid_x integer,
    grid_y integer,
    lon    double precision,
    lat    double precision,
    pop    double precision
);

CREATE TABLE facility_standard (
    code              text PRIMARY KEY,
    name_zh           text NOT NULL,
    poi_category      text NOT NULL,
    threshold_seconds integer NOT NULL DEFAULT 900,
    euclid_radius_m   integer NOT NULL DEFAULT 1080,
    enabled           boolean NOT NULL DEFAULT true
);

INSERT INTO facility_standard
    (code, name_zh, poi_category, threshold_seconds, euclid_radius_m, enabled)
VALUES
    ('healthcare', '医疗', '医院', 900, 1080, true),
    ('education', '教育', '学校', 900, 1080, true),
    ('commerce', '商业', '购物', 900, 1080, true),
    ('culture_sports', '文体', '景点', 900, 1080, true),
    ('transport', '交通', '交通', 900, 960, true)
ON CONFLICT (code) DO NOTHING;
