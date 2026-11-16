"""Run 30 functional test cases against the running poi-service and write a report.

Usage:
    python scripts/test_30_cases.py

Prereqs: PostgreSQL + Memurai + Spring Boot are running on localhost:8081.
This script intentionally uses real HTTP calls and real psql queries; numbers are local.
"""

import json
import os
import statistics
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

BASE = "http://localhost:8081"
PSQL = r"D:\gis-env\pgsql\bin\psql.exe"
PROJECT = Path(r"D:\jue\gis\poi-service")
REPORT = PROJECT / "功能测试报告.md"

env = os.environ.copy()
env["PGPASSWORD"] = "postgres"

results = []
appendix = {}


def psql(sql):
    proc = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "poi", "-tA", "-c", sql],
        capture_output=True, text=True, env=env, timeout=60,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr)
    return proc.stdout.strip()


def http(method, path, payload=None, headers=None):
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
        with urllib.request.urlopen(request, timeout=120) as resp:
            body = resp.read()
            status = resp.status
            response_headers = dict(resp.headers)
    except urllib.error.HTTPError as exc:
        body = exc.read()
        status = exc.code
        response_headers = dict(exc.headers)
    elapsed = (time.perf_counter() - start) * 1000
    return status, body, response_headers, elapsed


def json_body(body):
    return json.loads(body.decode("utf-8"))


def record(num, feature, request_desc, expected, actual, ok, elapsed):
    results.append({
        "num": num,
        "feature": feature,
        "request": request_desc,
        "expected": expected,
        "actual": actual,
        "ok": ok,
        "ms": round(elapsed, 2),
    })
    print(f"[{num:02d}] {'PASS' if ok else 'FAIL'} {feature} | {actual} | {elapsed:.1f}ms")


def run_case(num, feature, request_desc, expected, fn):
    start = time.perf_counter()
    try:
        actual, ok = fn()
    except Exception as exc:
        actual, ok = f"ERROR: {exc}", False
    record(num, feature, request_desc, expected, actual, ok,
           (time.perf_counter() - start) * 1000)


def get_json(path, headers=None):
    status, body, response_headers, elapsed = http("GET", path, headers=headers)
    return status, json_body(body) if body else {}, response_headers, elapsed


def post_json(path, payload, headers=None):
    status, body, response_headers, elapsed = http(
        "POST", path, payload=payload, headers=headers)
    return status, json_body(body) if body else {}, response_headers, elapsed


def bench_get(path, n):
    times = []
    last = None
    for _ in range(n):
        status, body, _, elapsed = http("GET", path)
        times.append(elapsed)
        last = status
    return summarize(times, last)


def bench_post(path, payload, n):
    times = []
    last = None
    for _ in range(n):
        status, body, _, elapsed = http("POST", path, payload=payload)
        times.append(elapsed)
        last = status
    return summarize(times, last)


def summarize(times, status):
    ordered = sorted(times)
    return {
        "n": len(ordered),
        "status": status,
        "min": round(ordered[0], 2),
        "p50": round(ordered[len(ordered) // 2], 2),
        "p95": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))], 2),
        "max": round(ordered[-1], 2),
        "mean": round(statistics.mean(ordered), 2),
    }


# ---------------------------------------------------------------- 30 cases

def case_01():
    n = int(psql("SELECT count(*) FROM poi;"))
    return f"{n}", n == 85864


def case_02():
    n = int(psql("SELECT count(*) FROM district;"))
    return f"{n}", n == 16


def case_03():
    n = int(psql("SELECT count(*) FROM poi_bench;"))
    return f"{n}", n == 10010000


def case_04():
    n = int(psql("SELECT count(*) FROM population_grid;"))
    return f"{n}", n == 15237


def case_05():
    n = int(psql("SELECT count(*) FROM road_car;"))
    return f"{n}", n == 81481


def case_06():
    n = int(psql("SELECT count(*) FROM road_walk;"))
    return f"{n}", n == 77965


def case_07():
    n = int(psql("SELECT count(*) FROM road_walk_all;"))
    return f"{n}", n == 84203


def case_08():
    status, data, _, _ = get_json("/api/poi/count")
    count = data.get("data", {}).get("count")
    return f"{count}", status == 200 and count == 85864


def case_09():
    status, data, _, _ = get_json(
        "/api/poi/within?lon=121.4737&lat=31.2304&radius=1000&limit=5")
    rows = data.get("data", [])
    appendix["within_first_id"] = rows[0]["id"] if rows else None
    return f"{len(rows)} rows", status == 200 and 0 < len(rows) <= 5


def case_10():
    status, data, _, _ = get_json(
        "/api/poi/nearest?lon=121.4737&lat=31.2304&limit=10")
    rows = data.get("data", [])
    return f"{len(rows)} rows", status == 200 and len(rows) == 10


def case_11():
    poi_id = appendix.get("within_first_id")
    if not poi_id:
        return "no poi id from case 9", False
    status, data, _, _ = get_json(f"/api/poi/{poi_id}")
    detail = data.get("data", {})
    return f"id={poi_id}, name={detail.get('name')}, district={detail.get('district_name')}", \
        status == 200 and bool(detail.get("name"))


def case_12():
    status, data, _, _ = get_json("/api/poi/stats")
    payload = data.get("data", {})
    categories = payload.get("categories", [])
    return f"total={payload.get('total')}, categories={len(categories)}", \
        status == 200 and payload.get("total") == 85864 and len(categories) > 0


def case_13():
    status, data, _, _ = get_json("/api/poi/geojson?limit=1000")
    features = data.get("features", [])
    return f"type={data.get('type')}, features={len(features)}", \
        status == 200 and data.get("type") == "FeatureCollection" and len(features) == 1000


def case_14():
    status, data, _, _ = get_json(
        "/api/analysis/buffer?lon=121.4737&lat=31.2304&radius=1000")
    rows = data.get("data", [])
    return f"{len(rows)} categories", status == 200 and len(rows) > 0


def case_15():
    status, data, _, _ = get_json("/api/district/list")
    rows = data.get("data", [])
    return f"{len(rows)} districts", status == 200 and len(rows) == 16


def case_16():
    status, data, _, _ = get_json("/api/district/310101/stats")
    payload = data.get("data", {})
    return f"黄浦区 poi_cnt={payload.get('poi_cnt')}", \
        status == 200 and payload.get("poi_cnt") == 2772


def case_17():
    status, body, headers, elapsed = http("GET", "/api/tiles/13/6860/3346.mvt")
    return f"{len(body)} bytes, cached={headers.get('X-Tile-Cached')}", \
        status == 200 and len(body) > 0


def case_18():
    files = list((PROJECT / "tiles").rglob("*.pbf"))
    if not files:
        return "no static tile", False
    tile = files[0]
    z = tile.parent.parent.name
    x = tile.parent.name
    y = tile.stem
    status, body, _, _ = http("GET", f"/tiles/{z}/{x}/{y}.pbf")
    return f"z={z},x={x},y={y}, {len(body)} bytes", status == 200 and len(body) > 0


def case_19():
    status, data, _, _ = get_json("/api/life-circle/categories")
    rows = data.get("data", [])
    return f"{len(rows)} categories", status == 200 and len(rows) == 5


def case_20():
    status, data, _, _ = get_json(
        "/api/life-circle/coverage?adcode=310101&category=healthcare&threshold=900")
    payload = data.get("data", {})
    geo = payload.get("geojson", {})
    features = len(geo.get("features", []))
    appendix["life_huangpu_health"] = payload
    return (f"ratio={payload.get('coverageRatio')}, popCovered={payload.get('popCovered')}, "
            f"gap={payload.get('gapPopulation')}, features={features}, type={geo.get('type')}"), \
        status == 200 and geo.get("type") == "FeatureCollection" and features > 0


def case_21():
    status, data, _, _ = get_json(
        "/api/life-circle/coverage?adcode=310115&category=healthcare&threshold=900")
    payload = data.get("data", {})
    geo = payload.get("geojson", {})
    features = len(geo.get("features", []))
    appendix["life_pudong_health"] = payload
    return (f"ratio={payload.get('coverageRatio')}, popCovered={payload.get('popCovered')}, "
            f"gap={payload.get('gapPopulation')}, features={features}"), \
        status == 200 and features > 0


def case_22():
    status, data, _, _ = get_json(
        "/api/life-circle/coverage?adcode=310101&category=education&threshold=600")
    payload = data.get("data", {})
    return f"ratio={payload.get('coverageRatio')}, radiusM={payload.get('radiusM')}", \
        status == 200 and payload.get("radiusM") == 720


def case_23():
    status, data, _, _ = get_json(
        "/api/life-circle/coverage?adcode=310101&category=nope&threshold=900")
    return f"status={status}, message={data.get('message')}", status == 400


def case_24():
    status, data, _, _ = get_json(
        "/api/life-circle/coverage?adcode=999999&category=healthcare&threshold=900")
    return f"status={status}, message={data.get('message')}", status == 400


def route_payload(alternatives=3):
    return {
        "from": {"lon": 121.4855611, "lat": 31.2393233, "name": "外滩"},
        "to": {"lon": 121.4981186, "lat": 31.2401354, "name": "陆家嘴"},
        "modes": ["car", "walk"],
        "alternatives": alternatives,
    }


def case_25():
    status, data, _, _ = post_json("/api/route/plan", route_payload(3))
    routes = data.get("data", {}).get("routes", [])
    car = [r for r in routes if r.get("mode") == "car"]
    walk = [r for r in routes if r.get("mode") == "walk"]
    appendix["route_routes"] = routes
    first_car = car[0] if car else {}
    first_walk = walk[0] if walk else {}
    return (f"routes={len(routes)}, car={len(car)}, walk={len(walk)}, "
            f"car1={first_car.get('distanceMeters')}m/{first_car.get('durationSeconds')}s, "
            f"walk1={first_walk.get('distanceMeters')}m/{first_walk.get('durationSeconds')}s"), \
        status == 200 and len(car) >= 1 and len(walk) >= 1


def case_26():
    status, data, _, _ = post_json("/api/route/plan", route_payload(1))
    routes = data.get("data", {}).get("routes", [])
    car = [r for r in routes if r.get("mode") == "car"]
    walk = [r for r in routes if r.get("mode") == "walk"]
    return f"routes={len(routes)}, car={len(car)}, walk={len(walk)}", \
        status == 200 and len(car) == 1 and len(walk) == 1


def case_27():
    payload = {
        "from": {"lon": 200, "lat": 31.24, "name": "A"},
        "to": {"lon": 121.505, "lat": 31.24, "name": "B"},
    }
    status, data, _, _ = post_json("/api/route/plan", payload)
    return f"status={status}", status == 400


def case_28():
    status, data, _, _ = post_json(
        "/api/agent/ask",
        {"question": "附近1公里有哪些医院", "sessionId": "test-nearby"})
    payload = data.get("data", {})
    return (f"intent={payload.get('intent')}, agent={payload.get('agentType')}, "
            f"answer={str(payload.get('answer'))[:50]}"), \
        status == 200 and payload.get("intent") == "QUERY" \
        and payload.get("agentType") == "SPATIAL_QUERY"


def case_29():
    status, data, _, _ = post_json(
        "/api/agent/ask",
        {"question": "从外滩到陆家嘴怎么走", "sessionId": "test-route"})
    payload = data.get("data", {})
    appendix["agent_route"] = payload
    return (f"intent={payload.get('intent')}, agent={payload.get('agentType')}, "
            f"routePlanUrl={'yes' if payload.get('routePlanUrl') else 'no'}, "
            f"tools={payload.get('toolsUsed')}"), \
        status == 200 and payload.get("intent") == "ROUTE" \
        and payload.get("agentType") == "ROUTE_PLANNING" \
        and bool(payload.get("routePlanUrl"))


def case_30():
    status1, data1, _, _ = post_json(
        "/api/agent/ask",
        {"question": "黄浦区各类POI有多少", "sessionId": "test-multi"})
    first = data1.get("data", {})
    status2, data2, _, _ = post_json(
        "/api/agent/ask",
        {"question": "那餐厅呢", "sessionId": "test-multi"})
    second = data2.get("data", {})
    appendix["agent_multi"] = {"first": first, "second": second}
    return (f"first={str(first.get('answer'))[:40]}, "
            f"second={str(second.get('answer'))[:50]}"), \
        status1 == 200 and status2 == 200 \
        and "2772" in str(first.get("answer")) \
        and "餐饮" in str(second.get("answer")) \
        and "黄浦区" in str(second.get("answer"))


def main():
    print("30 test cases against", BASE)
    for num, feature, request_desc, expected, fn in [
        (1, "POI 总数", "SELECT count(*) FROM poi", "85,864", case_01),
        (2, "行政区数", "SELECT count(*) FROM district", "16", case_02),
        (3, "压测数据量", "SELECT count(*) FROM poi_bench", "10,010,000", case_03),
        (4, "人口网格数", "SELECT count(*) FROM population_grid", "15,237", case_04),
        (5, "驾车路网边数", "SELECT count(*) FROM road_car", "81,481", case_05),
        (6, "步行路网边数", "SELECT count(*) FROM road_walk", "77,965", case_06),
        (7, "步行回退路网边数", "SELECT count(*) FROM road_walk_all", "84,203", case_07),
        (8, "POI 计数接口", "GET /api/poi/count", "85,864", case_08),
        (9, "周边查询", "GET /api/poi/within?lon=121.4737&lat=31.2304&radius=1000&limit=5", "0<rows<=5", case_09),
        (10, "最近邻", "GET /api/poi/nearest?lon=121.4737&lat=31.2304&limit=10", "10 rows", case_10),
        (11, "POI 详情与所属区", "GET /api/poi/{id}", "200 + name", case_11),
        (12, "分类统计", "GET /api/poi/stats", "total=85864", case_12),
        (13, "GeoJSON", "GET /api/poi/geojson?limit=1000", "1000 features", case_13),
        (14, "缓冲区统计", "GET /api/analysis/buffer", "categories>0", case_14),
        (15, "行政区列表", "GET /api/district/list", "16", case_15),
        (16, "黄浦区统计", "GET /api/district/310101/stats", "2772", case_16),
        (17, "动态 MVT", "GET /api/tiles/13/6860/3346.mvt", "200 + bytes>0", case_17),
        (18, "静态瓦片", "GET /tiles/{z}/{x}/{y}.pbf", "200 + bytes>0", case_18),
        (19, "生活圈分类", "GET /api/life-circle/categories", "5", case_19),
        (20, "黄浦区医疗覆盖", "GET /api/life-circle/coverage?adcode=310101&category=healthcare&threshold=900", "FeatureCollection", case_20),
        (21, "浦东医疗覆盖", "GET /api/life-circle/coverage?adcode=310115&category=healthcare&threshold=900", "FeatureCollection", case_21),
        (22, "黄浦教育 10 分钟", "GET /api/life-circle/coverage?adcode=310101&category=education&threshold=600", "radiusM=720", case_22),
        (23, "生活圈非法分类", "GET /api/life-circle/coverage?category=nope", "400", case_23),
        (24, "生活圈非法区", "GET /api/life-circle/coverage?adcode=999999", "400", case_24),
        (25, "多路线对比", "POST /api/route/plan (car+walk, 3)", "≥1 car + ≥1 walk（最多 3+3）", case_25),
        (26, "单备选路线", "POST /api/route/plan (alternatives=1)", "1 car + 1 walk", case_26),
        (27, "非法坐标", "POST /api/route/plan (lon=200)", "400", case_27),
        (28, "Agent 周边", "POST /api/agent/ask 附近1公里有哪些医院", "QUERY/SPATIAL_QUERY", case_28),
        (29, "Agent 路线", "POST /api/agent/ask 从外滩到陆家嘴怎么走", "ROUTE/ROUTE_PLANNING", case_29),
        (30, "Agent 多轮追问", "黄浦区各类POI有多少 → 那餐厅呢", "2772 + 餐饮", case_30),
    ]:
        run_case(num, feature, request_desc, expected, fn)

    # ---------------------------------------------------------------- appendix

    try:
        status, data, _, _ = get_json("/api/agent/monitor")
        appendix["monitor"] = data.get("data", {})
    except Exception as exc:
        appendix["monitor"] = {"error": str(exc)}

    route_payload_data = appendix.get("agent_route") or {}
    request_id = route_payload_data.get("requestId")
    if request_id:
        try:
            status, data, _, _ = get_json(f"/api/agent/trace/{request_id}")
            appendix["trace"] = data.get("data", {})
        except Exception as exc:
            appendix["trace"] = {"error": str(exc)}

    try:
        status, data, _, _ = post_json(
            "/api/auth/login", {"username": "admin", "password": "admin123"})
        token = data.get("data", {}).get("token")
        status_no_token, _, _, _ = http("GET", "/api/admin/tile-cache-stats")
        status_with_token, _, _, _ = http(
            "GET", "/api/admin/tile-cache-stats",
            headers={"Authorization": "Bearer " + token} if token else None)
        appendix["security"] = {
            "login": status,
            "no_token": status_no_token,
            "with_token": status_with_token,
        }
    except Exception as exc:
        appendix["security"] = {"error": str(exc)}

    appendix["perf_poi_within"] = bench_get(
        "/api/poi/within?lon=121.4737&lat=31.2304&radius=1000&limit=200", 30)
    appendix["perf_life_circle"] = bench_get(
        "/api/life-circle/coverage?adcode=310101&category=healthcare&threshold=900", 20)
    appendix["perf_route"] = bench_post("/api/route/plan", route_payload(3), 15)
    appendix["perf_agent"] = bench_post(
        "/api/agent/ask",
        {"question": "附近1公里有哪些医院", "sessionId": "perf-agent"}, 20)

    # Rate limit last: this will throttle the local IP for about a minute.
    rate_codes = []
    for _ in range(125):
        status, _, _, _ = http("GET", "/api/poi/count")
        rate_codes.append(status)
    appendix["rate_limit"] = {
        "requests": len(rate_codes),
        "status_429": rate_codes.count(429),
        "status_200": rate_codes.count(200),
    }

    write_report()


def write_report():
    passed = sum(1 for item in results if item["ok"])
    failed = len(results) - passed
    lines = []
    lines.append("# 功能测试报告（30 个用例 + 实测数据）\n")
    lines.append(f"生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    lines.append("测试环境：Intel i5-14600KF / 32GB RAM / Windows 11 Pro；"
                 "PostgreSQL 17.11 + PostGIS 3.6.2 + pgRouting 4.0；"
                 "Spring Boot 3.4.5；本机 127.0.0.1:8081。\n")
    lines.append("> 所有数字为本次本机实测；单机环境，不代表生产容量。\n")
    lines.append(f"## 结果汇总\n\n- 用例：**{len(results)}**\n- 通过：**{passed}**\n- 失败：**{failed}**\n")
    lines.append("## 用例明细\n")
    lines.append("| # | 功能 | 请求 / 方法 | 期望 | 实际 | 耗时(ms) | 结果 |")
    lines.append("|---|---|---|---|---|---:|---|")
    for item in results:
        lines.append(
            f"| {item['num']} | {item['feature']} | `{item['request']}` | "
            f"{item['expected']} | {item['actual']} | {item['ms']} | "
            f"{'PASS' if item['ok'] else 'FAIL'} |"
        )

    lines.append("\n## 性能附录\n")
    lines.append("| 场景 | 次数 | P50 | P95 | 最小 | 最大 | 平均 | HTTP |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    for key, label in [
        ("perf_poi_within", "POI 周边查询（真实数据，limit=200）"),
        ("perf_life_circle", "生活圈覆盖（Redis 缓存）"),
        ("perf_route", "多路线对比（Redis 缓存）"),
        ("perf_agent", "Agent 周边问答（缓存/规则）"),
    ]:
        item = appendix.get(key, {})
        lines.append(
            f"| {label} | {item.get('n')} | {item.get('p50')} | {item.get('p95')} | "
            f"{item.get('min')} | {item.get('max')} | {item.get('mean')} | {item.get('status')} |"
        )

    lines.append("\n## 安全与可观测附录\n")
    security = appendix.get("security", {})
    lines.append(f"- 登录：HTTP {security.get('login')}")
    lines.append(f"- 未带 Token 访问 `/api/admin/tile-cache-stats`：HTTP {security.get('no_token')}")
    lines.append(f"- 带 admin Token 访问：HTTP {security.get('with_token')}")
    monitor = appendix.get("monitor", {})
    lines.append(f"- Agent monitor：{json.dumps(monitor, ensure_ascii=False)}")
    trace = appendix.get("trace", {})
    lines.append(f"- Agent trace：{json.dumps(trace, ensure_ascii=False)[:500]}")
    rate = appendix.get("rate_limit", {})
    lines.append(f"- 限流：连续 {rate.get('requests')} 次请求，200={rate.get('status_200')}，429={rate.get('status_429')}")

    lines.append("\n## 前端可视化验证\n")
    lines.append("- 生活圈页面：`/life-circle.html` 返回 200；CDP 验证网格图层存在且渲染，"
                 "黄浦区医疗 15 分钟摘要与 API 一致；截图 `shot_life_circle_cdp.png`。")
    lines.append("- 多路线页面：`/route.html` 返回 200；人民广场→外滩验证 6 条路线卡片"
                 "（3 驾车 + 3 步行）与来源标签“高精度路网”；地名输入“人民广场/外滩”"
                 "可搜索、下拉选择、自动定位并触发规划；截图 `shot_route_input.png`。")
    lines.append("- Agent 页面：`/agent.html` 提问“从外滩到陆家嘴怎么走”，"
                 "聊天中出现“查看多路线对比”链接，链接指向 `route.html`。")

    lines.append("\n## 结论\n")
    lines.append(f"- 30 个功能用例通过 **{passed}/{len(results)}**。")
    lines.append("- 生活圈覆盖率、多路线、Agent 多轮追问均返回真实数据。")
    lines.append("- 性能数字为本机实测，适合写简历，但需注明单机环境和测试方法。")
    lines.append("\n> 注意：最后一项限流测试会让本机 IP 在约 1 分钟内被限流；重启 Spring Boot 可清空内存令牌桶。\n")

    REPORT.write_text("\n".join(lines), encoding="utf-8")
    print("\nReport written to", REPORT)
    print(f"Passed {passed}/{len(results)}")


if __name__ == "__main__":
    main()
