import csv
import json
import math
import os
import subprocess

PSQL = r"D:\gis-env\pgsql\bin\psql.exe"
HOST = "127.0.0.1"
USER = "postgres"
DB = "poi"
BASE = r"D:\jue\gis\poi-service"
DATA = os.path.join(BASE, "scripts", "data")
WGS_CSV = os.path.join(DATA, "poi_wgs.csv")
GCJ_CSV = os.path.join(DATA, "poi_gcj.csv")

env = os.environ.copy()
env["PGPASSWORD"] = "postgres"
env["PGCLIENTENCODING"] = "UTF8"


def psql(sql):
    result = subprocess.run(
        [PSQL, "-h", HOST, "-U", USER, "-d", DB, "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=env
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr)
    return result.stdout


def out_of_china(lng, lat):
    return not (72.004 <= lng <= 137.8347 and 0.8293 <= lat <= 55.8271)


def transform_lat(x, y):
    ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def transform_lng(x, y):
    ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lng, lat):
    if out_of_china(lng, lat):
        return lng, lat
    dlat = transform_lat(lng - 105.0, lat - 35.0)
    dlng = transform_lng(lng - 105.0, lat - 35.0)
    radlat = lat / 180.0 * math.pi
    magic = math.sin(radlat)
    magic = 1 - 0.00669342162296594323 * magic * magic
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtmagic) * math.pi)
    dlng = (dlng * 180.0) / (6378245.0 / sqrtmagic * math.cos(radlat) * math.pi)
    return lng + dlng, lat + dlat


def convert_coords(coords):
    if not coords:
        return coords
    if isinstance(coords[0], (int, float)):
        lng, lat = coords[0], coords[1]
        glng, glat = wgs84_to_gcj02(lng, lat)
        return [glng, glat] + coords[2:]
    return [convert_coords(c) for c in coords]


def main():
    print("export wgs csv ...", flush=True)
    if os.path.exists(WGS_CSV):
        os.remove(WGS_CSV)
    wgs_path = WGS_CSV.replace("\\", "/")
    gcj_path = GCJ_CSV.replace("\\", "/")
    psql(rf"\copy (SELECT id, name, category, sub_category, lng, lat FROM poi ORDER BY id) TO '{wgs_path}' CSV")

    print("convert wgs84 -> gcj02 ...", flush=True)
    with open(WGS_CSV, newline="", encoding="utf-8") as f, \
         open(GCJ_CSV, "w", newline="", encoding="utf-8") as g:
        reader = csv.reader(f)
        writer = csv.writer(g)
        for row in reader:
            pid, name, category, sub_category, lng, lat = row
            glng, glat = wgs84_to_gcj02(float(lng), float(lat))
            writer.writerow([pid, name, category, sub_category, f"{glng:.8f}", f"{glat:.8f}"])

    print("create and load poi_gcj ...", flush=True)
    psql("DROP TABLE IF EXISTS poi_gcj")
    psql("""
        CREATE TABLE poi_gcj (
            id bigint PRIMARY KEY,
            name text,
            category text,
            sub_category text,
            lng double precision,
            lat double precision,
            geom geometry(Point, 4326)
        )
    """)
    psql(rf"\copy poi_gcj(id, name, category, sub_category, lng, lat) FROM '{gcj_path}' CSV")
    psql("UPDATE poi_gcj SET geom = ST_SetSRID(ST_MakePoint(lng, lat), 4326)")
    psql("CREATE INDEX idx_poi_gcj_geom ON poi_gcj USING GIST (geom)")
    psql("ANALYZE poi_gcj")

    print("convert districts geojson ...", flush=True)
    district_src = os.path.join(DATA, "shanghai_districts.json")
    district_dst = os.path.join(BASE, "src", "main", "resources", "static", "districts_gcj.json")
    with open(district_src, encoding="utf-8") as f:
        data = json.load(f)
    for feature in data.get("features", []):
        geom = feature.get("geometry")
        if geom and "coordinates" in geom:
            geom["coordinates"] = convert_coords(geom["coordinates"])
    with open(district_dst, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)

    center = wgs84_to_gcj02(121.4737, 31.2304)
    print(f"gcj center: {center[0]:.6f},{center[1]:.6f}", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
