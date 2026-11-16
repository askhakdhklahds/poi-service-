ALTER TABLE poi ADD COLUMN IF NOT EXISTS lng double precision;
ALTER TABLE poi ADD COLUMN IF NOT EXISTS lat double precision;
ALTER TABLE poi ADD COLUMN IF NOT EXISTS geom_4549 geometry(Point, 4549);
ALTER TABLE poi ADD COLUMN IF NOT EXISTS source_id text;
ALTER TABLE poi ADD COLUMN IF NOT EXISTS created_at timestamptz DEFAULT now();
ALTER TABLE poi ADD COLUMN IF NOT EXISTS sub_category text;

CREATE INDEX IF NOT EXISTS idx_poi_geom_4549 ON poi USING GIST (geom_4549);
CREATE INDEX IF NOT EXISTS idx_poi_category ON poi (category);
CREATE INDEX IF NOT EXISTS idx_poi_sub_category ON poi (sub_category);
CREATE INDEX IF NOT EXISTS idx_poi_cat_4549 ON poi (category, geom_4549);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'poi'
          AND indexdef ILIKE '%UNIQUE%source_id%'
    ) THEN
        CREATE UNIQUE INDEX uq_poi_source_id ON poi (source_id);
    END IF;
END $$;
