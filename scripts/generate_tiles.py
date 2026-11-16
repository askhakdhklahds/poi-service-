import base64
import os
import subprocess
import sys

PSQL = r"D:\gis-env\pgsql\bin\psql.exe"
HOST = "127.0.0.1"
USER = "postgres"
DB = "poi"

TABLE = sys.argv[1] if len(sys.argv) > 1 else "poi"
OUT = sys.argv[2] if len(sys.argv) > 2 else r"D:\jue\gis\poi-service\tiles"

if TABLE not in ("poi", "poi_gcj"):
    raise SystemExit("invalid table")

env = os.environ.copy()
env["PGPASSWORD"] = "postgres"
env["PGCLIENTENCODING"] = "UTF8"


def psql(sql):
    result = subprocess.run(
        [PSQL, "-h", HOST, "-U", USER, "-d", DB, "-tA", "-c", sql],
        capture_output=True, text=True, env=env
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for z in range(9, 14):
        print(f"[{TABLE}] zoom {z} ...", flush=True)
        xy = psql(f"""
            SELECT DISTINCT
              floor((ST_X(geom) + 180.0) / 360.0 * power(2, {z}))::int AS x,
              floor((1.0 - ln(tan(radians(ST_Y(geom))) + 1.0 / cos(radians(ST_Y(geom)))) / pi()) / 2.0 * power(2, {z}))::int AS y
            FROM {TABLE}
            WHERE geom IS NOT NULL
        """)
        count = 0
        for line in xy.splitlines():
            line = line.strip()
            if not line:
                continue
            x_s, y_s = line.split("|")
            x, y = int(x_s), int(y_s)

            if z <= 11:
                grid = {9: 0.02, 10: 0.01, 11: 0.005}[z]
                sql = f"""
                    WITH bounds AS (SELECT ST_TileEnvelope({z}, {x}, {y}) AS geom)
                    SELECT encode(ST_AsMVT(q, 'poi', 4096, 'geom'), 'base64')
                    FROM (
                        SELECT a.category, a.sub_category, a.cnt,
                               ST_AsMVTGeom(ST_Transform(a.geom, 3857), b.geom, 4096, 256, true) AS geom
                        FROM (
                            SELECT p.category,
                                   p.sub_category,
                                   count(*) AS cnt,
                                   ST_Centroid(ST_Collect(p.geom)) AS geom
                            FROM {TABLE} p, bounds b
                            WHERE ST_Intersects(p.geom, ST_Transform(b.geom, 4326))
                            GROUP BY ST_SnapToGrid(p.geom, {grid}), p.category, p.sub_category
                        ) a, bounds b
                    ) q
                """
            else:
                sql = f"""
                    WITH bounds AS (SELECT ST_TileEnvelope({z}, {x}, {y}) AS geom)
                    SELECT encode(ST_AsMVT(q, 'poi', 4096, 'geom'), 'base64')
                    FROM (
                        SELECT p.id, p.name, p.category, p.sub_category, 1 AS cnt,
                               ST_AsMVTGeom(ST_Transform(p.geom, 3857), b.geom, 4096, 256, true) AS geom
                        FROM {TABLE} p, bounds b
                        WHERE ST_Intersects(p.geom, ST_Transform(b.geom, 4326))
                    ) q
                """
            b64 = psql(sql)
            if not b64:
                continue
            data = base64.b64decode(b64)
            folder = os.path.join(OUT, str(z), str(x))
            os.makedirs(folder, exist_ok=True)
            with open(os.path.join(folder, f"{y}.pbf"), "wb") as f:
                f.write(data)
            count += 1
        total += count
        print(f"  wrote {count} tiles", flush=True)
    print(f"done, total {total} tiles", flush=True)


if __name__ == "__main__":
    main()
