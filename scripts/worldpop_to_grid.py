"""Clip WorldPop 100m population raster to Shanghai and aggregate to a 500m grid.

Output CSV columns: grid_x, grid_y, lon, lat, pop
WorldPop values are people per pixel, so block values are summed (not averaged).
"""

import argparse
import csv
import json
import os

import numpy as np
import rasterio
from rasterio.transform import xy
from rasterio.windows import from_bounds


def bbox_from_geojson(path):
    with open(path, encoding="utf-8") as file:
        data = json.load(file)

    coords = []

    def walk(node):
        if isinstance(node, (list, tuple)):
            if len(node) >= 2 and all(isinstance(v, (int, float)) for v in node[:2]):
                coords.append((float(node[0]), float(node[1])))
            else:
                for item in node:
                    walk(item)
        elif isinstance(node, dict):
            for key in ("features", "geometry", "coordinates"):
                if key in node:
                    walk(node[key])

    walk(data)
    if not coords:
        raise ValueError(f"no coordinates found in {path}")
    lons = [c[0] for c in coords]
    lats = [c[1] for c in coords]
    return min(lons), min(lats), max(lons), max(lats)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", default="scripts/data/chn_pop_2020_100m.tif")
    parser.add_argument("--boundary", default="scripts/data/shanghai_districts.json")
    parser.add_argument("--out", default="scripts/data/pop_grid_500m.csv")
    parser.add_argument("--factor", type=int, default=5,
                        help="100m pixels per grid side; 5 -> about 500m")
    parser.add_argument("--pad", type=float, default=0.01,
                        help="degrees of padding around the boundary bbox")
    args = parser.parse_args()

    left, bottom, right, top = bbox_from_geojson(args.boundary)
    left -= args.pad
    bottom -= args.pad
    right += args.pad
    top += args.pad

    with rasterio.open(args.src) as src:
        window = from_bounds(left, bottom, right, top, transform=src.transform)
        window = window.round_offsets().round_lengths()
        data = src.read(1, window=window)
        transform = src.window_transform(window)
        if src.nodata is not None:
            data = np.where(data == src.nodata, 0, data)
        data = np.where(np.isfinite(data), data, 0)
        data = np.maximum(data, 0).astype("float64")

        rows, cols = data.shape
        factor = max(1, args.factor)
        out_rows = []
        total_pop = 0.0

        for row in range(0, rows, factor):
            row_end = min(row + factor, rows)
            for col in range(0, cols, factor):
                col_end = min(col + factor, cols)
                pop = float(data[row:row_end, col:col_end].sum())
                if pop <= 0:
                    continue
                center_row = row + (row_end - row - 1) / 2.0
                center_col = col + (col_end - col - 1) / 2.0
                lon, lat = xy(transform, center_row, center_col)
                grid_x = (int(window.col_off) + col) // factor
                grid_y = (int(window.row_off) + row) // factor
                out_rows.append((
                    grid_x,
                    grid_y,
                    round(lon, 6),
                    round(lat, 6),
                    round(pop, 3),
                ))
                total_pop += pop

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file)
        writer.writerow(["grid_x", "grid_y", "lon", "lat", "pop"])
        writer.writerows(out_rows)

    print(f"cells={len(out_rows)} total_pop={total_pop:.0f} out={args.out}")


if __name__ == "__main__":
    main()
