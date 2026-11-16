"""Evaluate place-name search accuracy on real Shanghai POIs.

Method:
  - Sample 25 POIs from each of the 8 categories (200 total) from the real
    85,864-row `poi` table.
  - Query A: full POI name -> expected top-1 result within 50 m.
  - Query B: truncated name (remove last 1-2 characters) -> expected one of
    top-3 results within 200 m.
  - Metrics: Top-1 / Top-3 hit rate, median distance error, no-result rate.

This measures the **search/geocoding layer** (name -> coordinates) on real
data. It is NOT an accuracy claim for the raw OSM POI positions themselves,
because OSM has no official ground-truth label for every point.

Usage:
    python scripts/eval_geocoding.py
"""

import json
import math
import os
import statistics
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
REPORT = PROJECT / "地名检索准确率评测.md"
SAMPLE_PER_CATEGORY = 25
EXACT_RADIUS_M = 50
PARTIAL_RADIUS_M = 200
PACE = float(os.getenv("EVAL_PACE_SECONDS", "0.7"))


def psql(sql):
    env = os.environ.copy()
    env["PGPASSWORD"] = "postgres"
    env["PGCLIENTENCODING"] = "UTF8"
    proc = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "poi",
         "-tA", "-F", "\t", "-c", sql],
        capture_output=True, text=True, encoding="utf-8", env=env, timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr)
    return proc.stdout.strip()


def get_json(path):
    request = urllib.request.Request(BASE + path)
    with urllib.request.urlopen(request, timeout=60) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    time.sleep(PACE)
    return body


def distance_m(lon1, lat1, lon2, lat2):
    # Equirectangular approximation; enough for 50-200 m thresholds.
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2)) * 6371000
    y = math.radians(lat2 - lat1) * 6371000
    return math.hypot(x, y)


def search(keyword, limit=8):
    query = urllib.parse.urlencode({"keyword": keyword, "limit": limit})
    return get_json("/api/poi/search?" + query).get("data", [])


def first_hit(results, lon, lat, radius):
    for index, item in enumerate(results):
        if item.get("lon") is None or item.get("lat") is None:
            continue
        if distance_m(lon, lat, item["lon"], item["lat"]) <= radius:
            return index + 1, distance_m(lon, lat, item["lon"], item["lat"])
    if results:
        item = results[0]
        if item.get("lon") is not None:
            return None, distance_m(lon, lat, item["lon"], item["lat"])
    return None, None


def partial_name(name):
    n = len(name)
    if n >= 6:
        return name[:-2]
    if n >= 4:
        return name[:-1]
    return name


def sample():
    sql = f"""
        SELECT name, category, ST_X(geom), ST_Y(geom)
        FROM (
            SELECT name, category, geom,
                   row_number() OVER (PARTITION BY category ORDER BY random()) AS rn
            FROM poi
            WHERE char_length(name) >= 4
        ) t
        WHERE rn <= {SAMPLE_PER_CATEGORY * 2};
    """
    grouped = {}
    for line in psql(sql).splitlines():
        parts = line.split("\t")
        if len(parts) != 4:
            continue
        name, category, lon, lat = parts
        if "未命名" in name:
            continue
        bucket = grouped.setdefault(category, [])
        if len(bucket) < SAMPLE_PER_CATEGORY:
            bucket.append({
                "name": name, "category": category,
                "lon": float(lon), "lat": float(lat),
            })
    rows = [row for bucket in grouped.values() for row in bucket]
    return rows


def main():
    rows = sample()
    exact_ranks = []
    partial_ranks = []
    exact_errors = []
    partial_errors = []
    no_result_exact = 0
    no_result_partial = 0
    examples = []

    for index, row in enumerate(rows, 1):
        exact = search(row["name"])
        rank, error = first_hit(exact, row["lon"], row["lat"], EXACT_RADIUS_M)
        if not exact:
            no_result_exact += 1
        exact_ranks.append(rank)
        if error is not None:
            exact_errors.append(error)

        query = partial_name(row["name"])
        partial = search(query)
        prank, perror = first_hit(partial, row["lon"], row["lat"], PARTIAL_RADIUS_M)
        prank = prank if prank is not None and prank <= 3 else None
        if not partial:
            no_result_partial += 1
        partial_ranks.append(prank)
        if perror is not None:
            partial_errors.append(perror)

        if index <= 12:
            examples.append({
                "name": row["name"], "category": row["category"],
                "query": query, "exact_rank": rank,
                "exact_error": error, "partial_rank": prank,
                "partial_error": perror,
            })
        print(f"[{index:03d}/{len(rows)}] {row['category']} {row['name']} "
              f"exact_rank={rank} partial_rank={prank}")

    total = len(rows)
    exact_top1 = sum(1 for r in exact_ranks if r == 1) / total
    exact_top3 = sum(1 for r in exact_ranks if r is not None and r <= 3) / total
    partial_top1 = sum(1 for r in partial_ranks if r == 1) / total
    partial_top3 = sum(1 for r in partial_ranks if r is not None) / total

    def pct(value):
        return f"{value * 100:.1f}%"

    def median(values):
        return f"{statistics.median(values):.1f} m" if values else "N/A"

    lines = [
        "# 地名检索准确率评测",
        "",
        f"生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        "",
        "## 评测口径",
        "",
        "- 数据：真实 `poi` 表，按 8 个分类各随机抽 25 条，共 **200 条**。",
        "- 查询 A（完整地名）：用 POI 原名搜索，Top-1 结果落在目标 **50m** 内算命中。",
        "- 查询 B（截断地名）：去掉原名最后 1–2 个字，模拟用户输入不完整，Top-3 内任一结果落在目标 **200m** 内算命中。",
        "- 指标：Top-1 / Top-3 命中率、Top-1 中位距离误差、无结果率。",
        "",
        "> 这是**地名检索定位准确率**，不是 OSM 原始 POI 坐标的绝对准确率。"
        "OSM 没有官方的逐点真值；本评测的 ground truth 是抽中 POI 自身的坐标。",
        "",
        "## 结果",
        "",
        "| 指标 | 结果 |",
        "|---|---:|",
        f"| 样本数 | {total} |",
        f"| 完整地名 Top-1 命中率（≤{EXACT_RADIUS_M}m） | **{pct(exact_top1)}** |",
        f"| 完整地名 Top-3 命中率（≤{EXACT_RADIUS_M}m） | {pct(exact_top3)} |",
        f"| 截断地名 Top-1 命中率（≤{PARTIAL_RADIUS_M}m） | **{pct(partial_top1)}** |",
        f"| 截断地名 Top-3 命中率（≤{PARTIAL_RADIUS_M}m） | **{pct(partial_top3)}** |",
        f"| 完整地名 Top-1 中位距离误差 | {median(exact_errors)} |",
        f"| 截断地名 Top-1 中位距离误差 | {median(partial_errors)} |",
        f"| 完整地名无结果数 | {no_result_exact} |",
        f"| 截断地名无结果数 | {no_result_partial} |",
        "",
        "## 样例",
        "",
        "| POI 原名 | 分类 | 截断查询 | 完整 Top-1 | 完整误差 | 截断 Top-3 | 截断误差 |",
        "|---|---|---|---:|---:|---:|---:|",
    ]
    def fmt_error(value):
        return "" if value is None else f"{value:.1f}m"

    for item in examples:
        lines.append(
            "| {} | {} | {} | {} | {} | {} | {} |".format(
                item["name"], item["category"], item["query"],
                item["exact_rank"], fmt_error(item["exact_error"]),
                item["partial_rank"], fmt_error(item["partial_error"]))
        )

    lines.extend([
        "",
        "## 限制与解释",
        "",
        f"1. 完整地名查询的 ground truth 是 POI 自身的坐标，所以 Top-1 高是正常的；"
        f"它证明检索链路没坏，不代表 OSM 坐标对现实世界 100% 准确。",
        "2. 截断地名更接近真实用户输入，Top-3 命中率更有参考价值；"
        "如果低，说明当前 `ILIKE '%关键词%'` 对短前缀/重名不够鲁棒。",
        "3. 当前搜索是子串匹配，不支持错别字；要提升可加 `pg_trgm` 相似度或中文分词。",
        "4. 如果要在简历写“准确率”，建议写："
        "“200 条真实 POI 地名检索 Top-1 命中率 X%（50m 阈值）、截断输入 Top-3 命中率 Y%（200m 阈值）”，"
        "并注明评测口径。",
        "",
        "> 所有样本来自本机真实数据，随机种子未固定；重跑会得到略有差异的抽样。",
        "",
    ])
    REPORT.write_text("\n".join(lines), encoding="utf-8")
    print(f"\nReport: {REPORT}")
    print(f"exact Top-1={pct(exact_top1)}, exact Top-3={pct(exact_top3)}, "
          f"partial Top-1={pct(partial_top1)}, partial Top-3={pct(partial_top3)}")


if __name__ == "__main__":
    main()
