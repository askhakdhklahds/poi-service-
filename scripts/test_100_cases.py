"""Run 100+ real-data functional test cases against the running poi-service.

Usage:
    python scripts/test_100_cases.py

Prereqs: PostgreSQL + Memurai + Spring Boot on localhost:8081.
Every case uses the real 85,864 Shanghai POIs, 16 districts, WorldPop grid,
OSM road networks and the running HTTP API. SQL cases query the same database
independently, so each result can be explained in an interview.

The script paces HTTP calls (default 0.8s) to stay below the 120/min rate limit.
Set TEST_PACE_SECONDS=0 to disable pacing.
"""

import json
import math
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

BASE = "http://localhost:8081"
PSQL = r"D:\gis-env\pgsql\bin\psql.exe"
PROJECT = Path(r"D:\jue\gis\poi-service")
REPORT = PROJECT / "真实数据测试报告-100例.md"
PACE = float(os.getenv("TEST_PACE_SECONDS", "0.8"))

CASES = []
results = []
state = {}

WEB_MERCATOR_BBOX = (120.8, 30.6, 122.1, 31.95)


def psql(sql):
    env = os.environ.copy()
    env["PGPASSWORD"] = "postgres"
    env["PGCLIENTENCODING"] = "UTF8"
    proc = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "poi", "-tA", "-c", sql],
        capture_output=True, text=True, encoding="utf-8", env=env, timeout=180,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr)
    return proc.stdout.strip()


def http(method, path, payload=None, headers=None, retries=1):
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8", **(headers or {})},
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=180) as resp:
            body = resp.read()
            status = resp.status
            response_headers = dict(resp.headers)
    except urllib.error.HTTPError as exc:
        body = exc.read()
        status = exc.code
        response_headers = dict(exc.headers)
    elapsed = (time.perf_counter() - start) * 1000
    if PACE > 0:
        time.sleep(PACE)
    if status == 429 and retries > 0:
        time.sleep(5)
        return http(method, path, payload=payload, headers=headers, retries=retries - 1)
    return status, body, response_headers, elapsed


def header(headers, name):
    for key, value in (headers or {}).items():
        if key.lower() == name.lower():
            return value
    return None


def get_json(path, headers=None):
    status, body, response_headers, elapsed = http("GET", path, headers=headers)
    return status, json.loads(body.decode("utf-8")) if body else {}, response_headers, elapsed


def post_json(path, payload, headers=None):
    status, body, response_headers, elapsed = http(
        "POST", path, payload=payload, headers=headers)
    return status, json.loads(body.decode("utf-8")) if body else {}, response_headers, elapsed


def add(feature, request_desc, expected, fn):
    CASES.append((feature, request_desc, expected, fn))


def sql_eq(sql, expected):
    def fn():
        actual = psql(sql)
        return f"{actual}", actual == str(expected)
    return fn


def api_count_case(path, expected, label=None):
    def fn():
        status, data, _, _ = get_json(path)
        actual = data.get("data", {}).get("count") if isinstance(data.get("data"), dict) \
            else len(data.get("data", []))
        return f"status={status}, count={actual}", status == 200 and actual == expected
    return fn


def within_rows(radius=1000, category=None, limit=5,
                lon=121.4737, lat=31.2304):
    query = urllib.parse.urlencode({
        "lon": lon, "lat": lat, "radius": radius, "limit": limit,
        **({"category": category} if category else {}),
    })
    status, data, _, _ = get_json("/api/poi/within?" + query)
    return status, data.get("data", [])


def nearest_rows(limit=10, category=None, lon=121.4737, lat=31.2304):
    query = urllib.parse.urlencode({
        "lon": lon, "lat": lat, "limit": limit,
        **({"category": category} if category else {}),
    })
    status, data, _, _ = get_json("/api/poi/nearest?" + query)
    return status, data.get("data", [])


def buffer_counts(radius=1000, lon=121.4737, lat=31.2304):
    query = urllib.parse.urlencode({"lon": lon, "lat": lat, "radius": radius})
    status, data, _, _ = get_json("/api/analysis/buffer?" + query)
    rows = data.get("data", [])
    return status, {row["category"]: row["cnt"] for row in rows}


# ---------------------------------------------------------------- A. data

DATA_CASES = [
    ("POI 总数", "SELECT count(*) FROM poi;", 85864),
    ("行政区数", "SELECT count(*) FROM district;", 16),
    ("压测数据量", "SELECT count(*) FROM poi_bench;", 10010000),
    ("人口网格数", "SELECT count(*) FROM population_grid;", 15237),
    ("驾车路网边数", "SELECT count(*) FROM road_car;", 81481),
    ("步行路网边数", "SELECT count(*) FROM road_walk;", 77965),
    ("步行回退路网边数", "SELECT count(*) FROM road_walk_all;", 84203),
    ("步行 v2 路网边数", "SELECT count(*) FROM road_walk_v2;", 76824),
    ("最大连通分量边数", "SELECT count(*) FROM road_main;", 84203),
    ("POI 大类数", "SELECT count(DISTINCT category) FROM poi;", 8),
    ("大类/小类组合数", "SELECT count(DISTINCT (category, sub_category)) FROM poi;", 38),
    ("source_id 唯一数", "SELECT count(DISTINCT source_id) FROM poi;", 85864),
    ("geom 空值数", "SELECT count(*) FROM poi WHERE geom IS NULL;", 0),
    ("geom_4549 空值数", "SELECT count(*) FROM poi WHERE geom_4549 IS NULL;", 0),
]
for label, sql, expected in DATA_CASES:
    add(f"数据完整性：{label}", sql, str(expected), sql_eq(sql, expected))

add("数据完整性：区内人口合计",
    "SELECT round(sum(pop)) FROM population_grid WHERE district_adcode IS NOT NULL;",
    "24870895",
    sql_eq("SELECT round(sum(pop)) FROM population_grid WHERE district_adcode IS NOT NULL;",
           24870895))
add("数据完整性：区内人口网格数",
    "SELECT count(*) FROM population_grid WHERE district_adcode IS NOT NULL;",
    "9422",
    sql_eq("SELECT count(*) FROM population_grid WHERE district_adcode IS NOT NULL;", 9422))
add("数据完整性：有网格的行政区数",
    "SELECT count(DISTINCT district_adcode) FROM population_grid WHERE district_adcode IS NOT NULL;",
    "16",
    sql_eq("SELECT count(DISTINCT district_adcode) FROM population_grid "
           "WHERE district_adcode IS NOT NULL;", 16))
add("数据完整性：行政区均为 MultiPolygon",
    "SELECT count(*) FROM district WHERE GeometryType(geom) = 'MULTIPOLYGON';",
    "16",
    sql_eq("SELECT count(*) FROM district WHERE GeometryType(geom) = 'MULTIPOLYGON';", 16))


def case_api_poi_count():
    status, data, _, _ = get_json("/api/poi/count")
    count = data.get("data", {}).get("count")
    return f"status={status}, count={count}", status == 200 and count == 85864


add("数据完整性：/api/poi/count", "GET /api/poi/count", "85864", case_api_poi_count)


def case_bbox_outliers():
    n = int(psql("""
        SELECT count(*) FROM poi
        WHERE ST_X(geom) NOT BETWEEN 120.8 AND 122.1
           OR ST_Y(geom) NOT BETWEEN 30.6 AND 31.95;
    """))
    return f"bbox 外 POI={n}（边界/海岛/个别脏点）", n <= 10


add("数据完整性：经纬度范围", "SELECT ... WHERE 超出上海 bbox", "<=10 个边界/脏点",
    case_bbox_outliers)


# ---------------------------------------------------------------- B. spatial

def case_within_limit():
    status, rows = within_rows(limit=5)
    return f"status={status}, rows={len(rows)}", status == 200 and len(rows) == 5


def case_within_sorted():
    status, rows = within_rows(limit=200)
    distances = [row["distance_m"] for row in rows]
    return f"rows={len(rows)}, first={distances[0] if distances else None}m", \
        status == 200 and distances == sorted(distances)


def case_within_radius():
    status, rows = within_rows(limit=500)
    bad = [row["distance_m"] for row in rows if row["distance_m"] > 1000]
    return f"rows={len(rows)}, over_radius={len(bad)}", status == 200 and not bad


def case_within_bbox():
    status, rows = within_rows(limit=200)
    west, south, east, north = WEB_MERCATOR_BBOX
    bad = [row for row in rows
           if not (west <= row["lon"] <= east and south <= row["lat"] <= north)]
    return f"rows={len(rows)}, out_of_bbox={len(bad)}", status == 200 and not bad


def case_nearest_limit():
    status, rows = nearest_rows(limit=10)
    return f"status={status}, rows={len(rows)}", status == 200 and len(rows) == 10


def case_nearest_sorted():
    status, rows = nearest_rows(limit=10)
    distances = [row["distance_m"] for row in rows]
    return f"rows={len(rows)}, first={distances[0] if distances else None}m", \
        status == 200 and distances == sorted(distances)


def case_nearest_matches_sql():
    sql = """
        WITH p AS (
            SELECT ST_Transform(ST_SetSRID(ST_MakePoint(121.4737,31.2304),4326),4549) AS pt
        )
        SELECT id FROM poi, p ORDER BY geom_4549 <-> p.pt LIMIT 1;
    """
    expected_id = int(psql(sql))
    status, rows = nearest_rows(limit=1)
    actual_id = rows[0]["id"] if rows else None
    return f"API={actual_id}, SQL={expected_id}", status == 200 and actual_id == expected_id


def case_within_matches_sql():
    sql = """
        WITH p AS (
            SELECT ST_Transform(ST_SetSRID(ST_MakePoint(121.4737,31.2304),4326),4549) AS pt
        )
        SELECT id FROM poi, p
        WHERE ST_DWithin(geom_4549, p.pt, 1000)
        ORDER BY ST_Distance(geom_4549, p.pt) LIMIT 1;
    """
    expected_id = int(psql(sql))
    status, rows = within_rows(limit=1)
    actual_id = rows[0]["id"] if rows else None
    return f"API={actual_id}, SQL={expected_id}", status == 200 and actual_id == expected_id


def case_buffer_total_300():
    status, counts = buffer_counts(radius=300)
    total = sum(counts.values())
    return f"categories={len(counts)}, total={total}", status == 200 and total == 93


def case_buffer_categories_300():
    status, counts = buffer_counts(radius=300)
    return f"categories={sorted(counts)}", status == 200 and len(counts) == 5


def case_buffer_categories_1000():
    status, counts = buffer_counts(radius=1000)
    return f"categories={len(counts)}, total={sum(counts.values())}", \
        status == 200 and len(counts) == 8


CATEGORY_COUNTS_1000 = {
    "餐饮": 315, "交通": 227, "购物": 165, "其他": 110,
    "景点": 71, "商业": 12, "医院": 6, "学校": 3,
}


def category_count_case(category, expected):
    def fn():
        status, rows = within_rows(radius=1000, category=category, limit=500)
        wrong = [row for row in rows if row.get("category") != category]
        return f"status={status}, count={len(rows)}, wrong_category={len(wrong)}", \
            status == 200 and len(rows) == expected and not wrong
    return fn


for category, expected in CATEGORY_COUNTS_1000.items():
    add(f"空间查询：分类 {category} 1000m", f"GET /api/poi/within category={category}",
        f"{expected} rows", category_count_case(category, expected))


def case_unknown_category():
    status, rows = within_rows(category="不存在的分类", limit=10)
    return f"status={status}, rows={len(rows)}", status == 200 and len(rows) == 0


def case_limit_clamp():
    status, rows = within_rows(limit=9999)
    return f"rows={len(rows)}（服务端上限 500）", status == 200 and len(rows) == 500


def case_radius_one():
    status, rows = within_rows(radius=1, limit=10)
    return f"rows={len(rows)}", status == 200 and len(rows) <= 1


def case_radius_large():
    status, rows = within_rows(radius=50000, limit=5)
    return f"rows={len(rows)}", status == 200 and len(rows) == 5


def case_invalid_lon():
    status, _, _, _ = get_json("/api/poi/within?lon=200&lat=31.23&radius=1000")
    return f"status={status}", status == 400


def case_invalid_lat():
    status, _, _, _ = get_json("/api/poi/within?lon=121.47&lat=100&radius=1000")
    return f"status={status}", status == 400


def district_stats_case(adcode, expected):
    def fn():
        status, data, _, _ = get_json(f"/api/district/{adcode}/stats")
        payload = data.get("data", {})
        return f"status={status}, poi_cnt={payload.get('poi_cnt')}", \
            status == 200 and payload.get("poi_cnt") == expected
    return fn


for adcode, name, expected in [
    ("310101", "黄浦区", 2772),
    ("310104", "徐汇区", 3180),
    ("310115", "浦东新区", 20050),
]:
    add(f"空间查询：{name}行政区统计", f"GET /api/district/{adcode}/stats",
        str(expected), district_stats_case(adcode, expected))


def case_district_sum():
    total = int(psql("SELECT sum(poi_cnt) FROM mv_district_stats;"))
    return f"sum={total}（总 POI 85,864，区外/边界外 2,562）", total == 83302


def case_poi_stats():
    status, data, _, _ = get_json("/api/poi/stats")
    payload = data.get("data", {})
    categories = payload.get("categories", [])
    return f"total={payload.get('total')}, categories={len(categories)}", \
        status == 200 and payload.get("total") == 85864 and len(categories) == 8


def case_point_in_district():
    row = psql("""
        SELECT p.id || '|' || d.name
        FROM poi p JOIN district d ON ST_Contains(d.geom, p.geom)
        WHERE d.adcode = '310101'
        ORDER BY p.id LIMIT 1;
    """)
    poi_id, expected_district = row.split("|", 1)
    status, data, _, _ = get_json(f"/api/poi/{poi_id}")
    detail = data.get("data", {})
    return f"id={poi_id}, API={detail.get('district_name')}, SQL={expected_district}", \
        status == 200 and detail.get("district_name") == expected_district


for label, fn, expected in [
    ("within limit=5", case_within_limit, "5 rows"),
    ("within 距离升序", case_within_sorted, "distance_m 单调不减"),
    ("within 距离不超过半径", case_within_radius, "所有 distance_m <= 1000"),
    ("within 坐标在上海范围", case_within_bbox, "所有点在上海 bbox"),
    ("nearest limit=10", case_nearest_limit, "10 rows"),
    ("nearest 距离升序", case_nearest_sorted, "distance_m 单调不减"),
    ("nearest 与 SQL KNN 一致", case_nearest_matches_sql, "API 首个 id == SQL KNN id"),
    ("within 与 SQL 最近点一致", case_within_matches_sql, "API 首个 id == SQL 最近 id"),
    ("buffer 300m 总数", case_buffer_total_300, "93"),
    ("buffer 300m 分类数", case_buffer_categories_300, "5"),
    ("buffer 1000m 分类数", case_buffer_categories_1000, "8"),
    ("未知分类返回空", case_unknown_category, "0 rows"),
    ("limit 上限夹取", case_limit_clamp, "500 rows"),
    ("radius=1", case_radius_one, "<=1 row"),
    ("radius=50000", case_radius_large, "5 rows"),
    ("非法经度", case_invalid_lon, "400"),
    ("非法纬度", case_invalid_lat, "400"),
    ("行政区统计总和", case_district_sum, "83,302"),
    ("POI 分类统计", case_poi_stats, "85,864 / 8 类"),
    ("点面归属", case_point_in_district, "API district == SQL ST_Contains"),
]:
    add(f"空间查询：{label}", "真实 HTTP / SQL 交叉验证", expected, fn)


# ---------------------------------------------------------------- C. life circle

LIFE_DISTRICTS = [("310101", "黄浦区"), ("310104", "徐汇区"), ("310115", "浦东新区")]
LIFE_CATEGORIES = ["healthcare", "education", "commerce", "transport", "culture_sports"]
LIFE_THRESHOLDS = [300, 600, 900]


def life_case(adcode, category, threshold):
    def fn():
        status, data, _, _ = get_json(
            f"/api/life-circle/coverage?adcode={adcode}&category={category}"
            f"&threshold={threshold}")
        payload = data.get("data", {})
        geo = payload.get("geojson", {})
        features = geo.get("features", [])
        pop_total = payload.get("popTotal", 0)
        pop_covered = payload.get("popCovered", 0)
        ratio = payload.get("coverageRatio")
        gap = payload.get("gapPopulation", 0)
        if status != 200 or ratio is None:
            return f"status={status}, ratio={ratio}", False
        checks = [
            status == 200,
            payload.get("radiusM") == threshold * 1.2,
            pop_total > 0,
            0 <= pop_covered <= pop_total,
            gap == pop_total - pop_covered,
            abs(ratio - pop_covered / pop_total) < 1e-5,
            0 <= ratio <= 1,
            geo.get("type") == "FeatureCollection",
            len(features) == payload.get("cellCount") and len(features) > 0,
            all("covered" in f.get("properties", {}) and "pop" in f.get("properties", {})
                for f in features[:20]),
        ]
        return (f"ratio={ratio:.4f}, pop={pop_covered}/{pop_total}, "
                f"features={len(features)}"), all(checks)
    return fn


for adcode, district in LIFE_DISTRICTS:
    for category in LIFE_CATEGORIES:
        for threshold in LIFE_THRESHOLDS:
            add(f"生活圈：{district}/{category}/{threshold}s",
                f"GET /api/life-circle/coverage?adcode={adcode}&category={category}&threshold={threshold}",
                "ratio=covered/total, radius=threshold*1.2, FeatureCollection",
                life_case(adcode, category, threshold))


def life_exact_case(adcode, category, threshold, expected_ratio,
                    expected_covered, expected_gap):
    def fn():
        status, data, _, _ = get_json(
            f"/api/life-circle/coverage?adcode={adcode}&category={category}"
            f"&threshold={threshold}")
        payload = data.get("data", {})
        ratio = payload.get("coverageRatio")
        return (f"ratio={ratio:.6f}, covered={payload.get('popCovered')}, "
                f"gap={payload.get('gapPopulation')}"), \
            status == 200 \
            and abs(ratio - expected_ratio) < 1e-9 \
            and payload.get("popCovered") == expected_covered \
            and payload.get("gapPopulation") == expected_gap
    return fn


add("生活圈：黄浦医疗15分钟精确值",
    "GET /api/life-circle/coverage?adcode=310101&category=healthcare&threshold=900",
    "ratio≈0.971866, covered=658413, gap=19060",
    life_exact_case("310101", "healthcare", 900,
                    0.9718664067379855, 658413, 19060))
add("生活圈：浦东医疗15分钟精确值",
    "GET /api/life-circle/coverage?adcode=310115&category=healthcare&threshold=900",
    "ratio≈0.319418, covered=1711833, gap=3647388",
    life_exact_case("310115", "healthcare", 900,
                    0.31941816640348447, 1711833, 3647388))


# ---------------------------------------------------------------- D. routes

ROUTE_PAIRS = [
    ("人民广场", 121.4737, 31.2304, "外滩", 121.4855, 31.2393),
    ("外滩", 121.4855, 31.2393, "陆家嘴", 121.4981, 31.2401),
    ("徐家汇", 121.4365, 31.1940, "静安寺", 121.4453, 31.2237),
    ("五角场", 121.5149, 31.3040, "陆家嘴", 121.4981, 31.2401),
    ("虹桥火车站", 121.3270, 31.1948, "人民广场", 121.4737, 31.2304),
    ("上海南站", 121.4290, 31.1548, "徐家汇", 121.4365, 31.1940),
    ("豫园", 121.4920, 31.2270, "南京东路", 121.4806, 31.2403),
    ("中山公园", 121.4200, 31.2200, "徐家汇", 121.4365, 31.1940),
    ("世纪公园", 121.5500, 31.2150, "陆家嘴", 121.4981, 31.2401),
    ("上海站", 121.4550, 31.2490, "外滩", 121.4855, 31.2393),
]


def route_case(a, lon1, lat1, b, lon2, lat2):
    def fn():
        payload = {
            "from": {"lon": lon1, "lat": lat1, "name": a},
            "to": {"lon": lon2, "lat": lat2, "name": b},
            "modes": ["car", "walk"], "alternatives": 1,
        }
        status, data, _, _ = post_json("/api/route/plan", payload)
        routes = data.get("data", {}).get("routes", [])
        car = [r for r in routes if r.get("mode") == "car"]
        walk = [r for r in routes if r.get("mode") == "walk"]

        def valid(route):
            coords = route.get("geometry", {}).get("coordinates", [])
            numbers = [x for point in coords for x in point]
            return (route.get("distanceMeters", 0) > 0
                    and route.get("durationSeconds", 0) > 0
                    and len(coords) >= 2
                    and not any(math.isnan(x) for x in numbers))

        ok = (status == 200 and len(car) >= 1 and len(walk) >= 1
              and all(valid(r) for r in car + walk))
        first_car = car[0] if car else {}
        first_walk = walk[0] if walk else {}
        return (f"car={len(car)}, walk={len(walk)}, "
                f"car1={first_car.get('distanceMeters')}m/{first_car.get('durationSeconds')}s, "
                f"walk1={first_walk.get('distanceMeters')}m/{first_walk.get('durationSeconds')}s"), ok
    return fn


for a, lon1, lat1, b, lon2, lat2 in ROUTE_PAIRS:
    add(f"路线：{a}→{b}", f"POST /api/route/plan {a}→{b}",
        ">=1 驾车 + >=1 步行，几何有效", route_case(a, lon1, lat1, b, lon2, lat2))


# ---------------------------------------------------------------- E. agent

AGENT_CASES = [
    ("黄浦区有哪些医院", "QUERY", "agent-q1"),
    ("附近1公里有哪些学校", "QUERY", "agent-q2"),
    ("人民广场附近500米有什么餐厅", "QUERY", "agent-q3"),
    ("黄浦区各类POI有多少", "QUERY", "agent-stats"),
    ("哪个区医院最多", "QUERY", "agent-q5"),
    ("黄浦区和静安区POI数量对比", "QUERY", "agent-q6"),
    ("人民广场到外滩怎么走", "ROUTE", "agent-q7"),
    ("从徐家汇到静安寺怎么走", "ROUTE", "agent-q8"),
    ("外滩到陆家嘴怎么走", "ROUTE", "agent-q9"),
    ("生成黄浦区的空间诊断报告", "REPORT", "agent-q10"),
    ("你好，你能做什么", "GENERAL", "agent-q11"),
    ("那餐厅呢", "QUERY", "agent-stats"),
    ("最近的医院在哪", "QUERY", "agent-q13"),
    ("浦东新区1公里内有哪些商场", "QUERY", "agent-q14"),
    ("上海有哪些区", "QUERY", "agent-q15"),
]


def agent_case(question, expected_intent, session_id):
    def fn():
        status, data, _, _ = post_json(
            "/api/agent/ask", {"question": question, "sessionId": session_id})
        payload = data.get("data", {})
        intent = payload.get("intent")
        agent_type = payload.get("agentType")
        tools = payload.get("toolsUsed") or []
        answer = payload.get("answer") or ""
        ok = status == 200 and intent == expected_intent and bool(agent_type) and bool(answer)
        if expected_intent in ("QUERY", "ROUTE", "REPORT"):
            ok = ok and bool(tools)
        if expected_intent == "ROUTE":
            ok = ok and bool(payload.get("routePlanUrl"))
        if expected_intent == "REPORT":
            ok = ok and agent_type == "REPORT"
        appendix = f"tools={tools[:3]}, answer={answer[:45]}"
        return f"intent={intent}, agent={agent_type}, {appendix}", ok
    return fn


for question, expected_intent, session_id in AGENT_CASES:
    add(f"Agent：{question}", f"POST /api/agent/ask ({expected_intent})",
        f"{expected_intent}，answer 非空，工具证据", agent_case(question, expected_intent, session_id))


# ---------------------------------------------------------------- F. infra

def case_mvt_bytes():
    status, body, headers, _ = http("GET", "/api/tiles/13/6860/3346.mvt")
    return f"{len(body)} bytes, cached={header(headers, 'X-Tile-Cached')}", \
        status == 200 and len(body) > 0


def case_mvt_cache():
    http("GET", "/api/tiles/13/6860/3346.mvt")
    status, body, headers, _ = http("GET", "/api/tiles/13/6860/3346.mvt")
    cached = header(headers, "X-Tile-Cached")
    return f"{len(body)} bytes, cached={cached}", status == 200 and cached == "HIT"


def case_static_tile():
    files = list((PROJECT / "tiles").rglob("*.pbf"))
    if not files:
        return "no static tile", False
    tile = files[0]
    z, x, y = tile.parent.parent.name, tile.parent.name, tile.stem
    status, body, _, _ = http("GET", f"/tiles/{z}/{x}/{y}.pbf")
    return f"z={z},x={x},y={y}, {len(body)} bytes", status == 200 and len(body) > 0


def case_geojson():
    status, data, _, _ = get_json("/api/poi/geojson?limit=1000")
    features = data.get("features", [])
    return f"type={data.get('type')}, features={len(features)}", \
        status == 200 and data.get("type") == "FeatureCollection" and len(features) == 1000


def case_geojson_max():
    status, data, _, _ = get_json("/api/poi/geojson?limit=50000")
    features = data.get("features", [])
    return f"features={len(features)}", status == 200 and 0 < len(features) <= 50000


def case_login():
    status, data, _, _ = post_json(
        "/api/auth/login", {"username": "admin", "password": "admin123"})
    token = data.get("data", {}).get("token")
    state["token"] = token
    return f"status={status}, token={'yes' if token else 'no'}", status == 200 and bool(token)


def case_login_wrong():
    status, _, _, _ = post_json(
        "/api/auth/login", {"username": "admin", "password": "wrong"})
    return f"status={status}", status == 401


def case_admin_no_token():
    status, _, _, _ = get_json("/api/admin/tile-cache-stats")
    return f"status={status}", status == 403


def case_admin_with_token():
    token = state.get("token")
    if not token:
        return "no token", False
    status, data, _, _ = get_json(
        "/api/admin/tile-cache-stats", headers={"Authorization": "Bearer " + token})
    return f"status={status}, size={data.get('size')}", status == 200


def case_invalid_life_category():
    status, _, _, _ = get_json(
        "/api/life-circle/coverage?adcode=310101&category=nope&threshold=900")
    return f"status={status}", status == 400


def case_invalid_life_district():
    status, _, _, _ = get_json(
        "/api/life-circle/coverage?adcode=999999&category=healthcare&threshold=900")
    return f"status={status}", status == 400


def case_invalid_route_lon():
    status, _, _, _ = post_json("/api/route/plan", {
        "from": {"lon": 200, "lat": 31.23, "name": "A"},
        "to": {"lon": 121.5, "lat": 31.24, "name": "B"},
    })
    return f"status={status}", status == 400


def case_route_missing():
    status, _, _, _ = post_json("/api/route/plan", {})
    return f"status={status}", status == 400


def case_poi_404():
    status, _, _, _ = get_json("/api/poi/99999999")
    return f"status={status}", status == 404


def case_district_list():
    status, data, _, _ = get_json("/api/district/list")
    rows = data.get("data", [])
    return f"status={status}, districts={len(rows)}", status == 200 and len(rows) == 16


def case_life_categories():
    status, data, _, _ = get_json("/api/life-circle/categories")
    rows = data.get("data", [])
    return f"status={status}, categories={len(rows)}", status == 200 and len(rows) == 5


for label, fn, expected in [
    ("动态 MVT 非空", case_mvt_bytes, "bytes > 0"),
    ("MVT 第二次命中 Caffeine", case_mvt_cache, "X-Tile-Cached=HIT"),
    ("预生成瓦片", case_static_tile, "HTTP 200 且非空"),
    ("GeoJSON FeatureCollection", case_geojson, "1000 features"),
    ("GeoJSON limit=50000", case_geojson_max, "0 < features <= 50000"),
    ("登录成功", case_login, "200 + token"),
    ("错误密码", case_login_wrong, "401"),
    ("管理接口无 token", case_admin_no_token, "403"),
    ("管理接口带 token", case_admin_with_token, "200"),
    ("生活圈非法分类", case_invalid_life_category, "400"),
    ("生活圈非法区", case_invalid_life_district, "400"),
    ("路线非法经度", case_invalid_route_lon, "400"),
    ("路线缺少 from/to", case_route_missing, "400"),
    ("POI 详情不存在", case_poi_404, "404"),
    ("行政区列表", case_district_list, "16"),
    ("生活圈分类", case_life_categories, "5"),
]:
    add(f"工程/安全：{label}", "真实 HTTP 请求", expected, fn)


# ---------------------------------------------------------------- run

def category_of(feature):
    for prefix in ("数据完整性", "空间查询", "生活圈", "路线", "Agent", "工程/安全"):
        if feature.startswith(prefix):
            return prefix
    return "其他"


def main():
    start_all = time.time()
    for number, (feature, request_desc, expected, fn) in enumerate(CASES, 1):
        start = time.perf_counter()
        try:
            actual, ok = fn()
        except Exception as exc:
            actual, ok = f"ERROR: {exc}", False
        elapsed = (time.perf_counter() - start) * 1000
        results.append({
            "num": number,
            "category": category_of(feature),
            "feature": feature,
            "request": request_desc,
            "expected": expected,
            "actual": actual,
            "ok": ok,
            "ms": round(elapsed, 2),
        })
        print(f"[{number:03d}] {'PASS' if ok else 'FAIL'} {feature} | {actual} | {elapsed:.1f}ms")

    passed = sum(1 for r in results if r["ok"])
    total = len(results)
    lines = [
        "# 真实数据功能测试报告（100+ 例）",
        "",
        f"生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        "",
        "> 所有用例都基于本机真实数据：85,864 条上海 OSM POI、16 个行政区、"
        "15,237 个人口网格、8 万多条 OSM 路网边；HTTP 结果与 PostgreSQL 独立查询交叉验证。",
        "",
        "## 汇总",
        "",
        "| 类别 | 用例数 | 通过 | 失败 |",
        "|---|---:|---:|---:|",
    ]
    for category in ("数据完整性", "空间查询", "生活圈", "路线", "Agent", "工程/安全"):
        subset = [r for r in results if r["category"] == category]
        if not subset:
            continue
        passed_subset = sum(1 for r in subset if r["ok"])
        lines.append(f"| {category} | {len(subset)} | {passed_subset} | "
                     f"{len(subset) - passed_subset} |")
    lines.append(f"| **合计** | **{total}** | **{passed}** | **{total - passed}** |")
    lines.append("")
    lines.append(f"总耗时：约 {time.time() - start_all:.1f}s；HTTP 调用间隔 {PACE}s（避免触发 120 次/分钟限流）。")
    lines.append("")

    for category in ("数据完整性", "空间查询", "生活圈", "路线", "Agent", "工程/安全"):
        subset = [r for r in results if r["category"] == category]
        if not subset:
            continue
        lines.append(f"## {category}")
        lines.append("")
        lines.append("| # | 用例 | 请求 | 预期 | 实际 | 耗时(ms) | 结果 |")
        lines.append("|---:|---|---|---|---|---:|---|")
        for r in subset:
            actual = r["actual"].replace("|", "\\|")
            expected = r["expected"].replace("|", "\\|")
            request = r["request"].replace("|", "\\|")
            lines.append(
                f"| {r['num']} | {r['feature']} | {request} | {expected} | "
                f"{actual} | {r['ms']} | {'PASS' if r['ok'] else 'FAIL'} |")
        lines.append("")

    lines.extend([
        "## 面试怎么讲",
        "",
        "1. **数据口径**：真实 POI 85,864、16 个区、人口网格 15,237（区内 9,422）、"
        "路网 81,481 条驾车边；1,000 万条是单独的合成压测表，不混用。",
        "2. **交叉验证**：空间查询除了断言接口返回结构，还用 SQL 独立算最近点/分类计数，"
        "两条路径结果一致才算通过。",
        "3. **生活圈**：45 个组合（3 个区 × 5 类设施 × 3 个时间阈值）逐一校验 "
        "ratio = popCovered / popTotal、gap = total - covered、半径 = 阈值 × 1.2m/s。",
        "4. **路线**：10 组真实起终点，校验驾车/步行都有路线、距离时间大于 0、几何无 NaN。",
        "5. **Agent**：15 个真实问法，覆盖查询、路线、报告、闲聊和多轮追问，"
        "校验 intent/agentType/toolsUsed/answer。",
        "6. **工程安全**：MVT 缓存、GeoJSON、登录、RBAC、参数校验、404 等边界。",
        "7. **测试发现问题**：错误密码原本返回 500，100+ 用例套件定位后改为 401，并保留回归用例。",
        "",
        "> 性能数字受缓存、并行度和机器负载影响；本报告记录的是本机单机实测，"
        "面试时说明环境和测法，不把它说成生产容量。",
        "",
    ])
    REPORT.write_text("\n".join(lines), encoding="utf-8")
    print(f"\nReport: {REPORT}")
    print(f"Passed {passed}/{total}")


if __name__ == "__main__":
    main()
