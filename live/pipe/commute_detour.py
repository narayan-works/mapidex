#!/usr/bin/env python3
"""
Mapidex pipe experiment — OSM only (no API key).
OSRM foot route office→home + Overpass POIs → 4 buckets → pick one → JSON.

Usage:
  python3 commute_detour.py
  python3 commute_detour.py --category relax
  python3 commute_detour.py --office 37.7985,-122.4480 --home 37.8055,-122.4400
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import urllib.parse
import urllib.request
from typing import Any

# Defaults ≈ Marina / McDowell → Marina Blvd (from your Walk screenshots). Swap anytime.
DEFAULT_OFFICE = (37.8067218, -122.4318093)  # Founders Inc / Fort Mason B
DEFAULT_HOME = (37.7989871, -122.4349331)  # 2143 Greenwich St

CARD_W, CARD_H = 180, 100
MAX_PER_BUCKET = 3

# OSM tags → buckets (Overpass)
BUCKET_TAGS: dict[str, list[str]] = {
    "relax": [
        'node["leisure"="park"]',
        'node["leisure"="garden"]',
        'node["amenity"="cafe"]',
        'node["tourism"="viewpoint"]',
    ],
    "play": [
        'node["leisure"="playground"]',
        'node["leisure"="pitch"]',
        'node["tourism"="museum"]',
        'node["leisure"="sports_centre"]',
    ],
    "social": [
        'node["amenity"="bar"]',
        'node["amenity"="pub"]',
        'node["amenity"="restaurant"]',
        'node["amenity"="cinema"]',
    ],
    "essentials": [
        'node["shop"="supermarket"]',
        'node["shop"="convenience"]',
        'node["amenity"="pharmacy"]',
        'node["shop"="bakery"]',
    ],
}


def http_get(url: str, timeout: int = 60) -> Any:
    req = urllib.request.Request(url, headers={"User-Agent": "mapidex-pipe/0.1"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_post(url: str, body: str, timeout: int = 90) -> Any:
    data = body.encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "User-Agent": "mapidex-pipe/0.1",
            "Content-Type": "application/x-www-form-urlencoded",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def osrm_foot_route(office: tuple[float, float], home: tuple[float, float]) -> dict[str, Any]:
    # OSRM wants lon,lat
    o_lat, o_lng = office
    h_lat, h_lng = home
    coords = f"{o_lng},{o_lat};{h_lng},{h_lat}"
    url = (
        "https://router.project-osrm.org/route/v1/foot/"
        + coords
        + "?overview=full&geometries=geojson&steps=false"
    )
    data = http_get(url)
    if data.get("code") != "Ok" or not data.get("routes"):
        raise RuntimeError(f"OSRM failed: {data}")
    route = data["routes"][0]
    line = route["geometry"]["coordinates"]  # [lng, lat]
    meters = route["distance"]
    seconds = route["duration"]
    miles = meters / 1609.34
    mins = max(1, int(round(seconds / 60)))
    summary = f"foot · {miles:.1f} mi · {mins} min"
    return {"coords_lnglat": line, "summary": summary, "meters": meters}


def bbox_from_line(coords_lnglat: list[list[float]], pad: float = 0.003) -> tuple[float, float, float, float]:
    lngs = [c[0] for c in coords_lnglat]
    lats = [c[1] for c in coords_lnglat]
    return min(lats) - pad, min(lngs) - pad, max(lats) + pad, max(lngs) + pad


def overpass_pois(south: float, west: float, north: float, east: float) -> list[dict[str, Any]]:
    # One compact bbox pull; classify client-side. Try mirrors on timeout.
    query = f"""[out:json][timeout:90];
(
  node["amenity"~"cafe|bar|pub|restaurant|cinema|pharmacy"]({south},{west},{north},{east});
  node["leisure"~"park|garden|playground|pitch|sports_centre"]({south},{west},{north},{east});
  node["shop"~"supermarket|convenience|bakery"]({south},{west},{north},{east});
  node["tourism"~"viewpoint|museum"]({south},{west},{north},{east});
);
out body;"""
    mirrors = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.openstreetmap.ru/api/interpreter",
    ]
    last_err: Exception | None = None
    for url in mirrors:
        try:
            data = http_post(url, "data=" + urllib.parse.quote(query), timeout=100)
            return data.get("elements", [])
        except Exception as e:  # noqa: BLE001
            last_err = e
            continue
    raise RuntimeError(f"Overpass failed on all mirrors: {last_err}")


def classify(el: dict[str, Any]) -> str | None:
    tags = el.get("tags") or {}
    mapping = [
        ("relax", {"leisure": {"park", "garden"}, "amenity": {"cafe"}, "tourism": {"viewpoint"}}),
        ("play", {"leisure": {"playground", "pitch", "sports_centre"}, "tourism": {"museum"}}),
        ("social", {"amenity": {"bar", "pub", "restaurant", "cinema"}}),
        ("essentials", {"shop": {"supermarket", "convenience", "bakery"}, "amenity": {"pharmacy"}}),
    ]
    for bucket, rules in mapping:
        for key, values in rules.items():
            if tags.get(key) in values:
                return bucket
    return None


def dist2(a: tuple[float, float], b: tuple[float, float]) -> float:
    return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2


def near_corridor(
    lat: float,
    lng: float,
    line: list[list[float]],
    max_deg: float = 0.0015,
) -> bool:
    # rough degrees ~150m
    for lng0, lat0 in line[:: max(1, len(line) // 40)]:
        if dist2((lat, lng), (lat0, lng0)) <= max_deg * max_deg:
            return True
    return False


def bucket_places(elements: list[dict[str, Any]], line: list[list[float]]) -> dict[str, list[dict[str, Any]]]:
    out: dict[str, list[dict[str, Any]]] = {k: [] for k in BUCKET_TAGS}
    seen: set[str] = set()
    for el in elements:
        if "lat" not in el or "lon" not in el:
            continue
        name = (el.get("tags") or {}).get("name")
        if not name:
            continue
        bucket = classify(el)
        if not bucket:
            continue
        if not near_corridor(el["lat"], el["lon"], line):
            continue
        key = f"{bucket}:{name.lower()}"
        if key in seen:
            continue
        seen.add(key)
        out[bucket].append({"name": name, "lat": el["lat"], "lng": el["lon"]})
    for k in out:
        out[k] = out[k][:MAX_PER_BUCKET]
    return out


def simplify(pts: list[list[float]], max_pts: int = 24) -> list[list[float]]:
    if len(pts) <= max_pts:
        return pts
    step = (len(pts) - 1) / (max_pts - 1)
    return [pts[int(round(i * step))] for i in range(max_pts)]


def fit_path(coords_lnglat: list[list[float]], w: int = CARD_W, h: int = CARD_H) -> dict[str, Any]:
    simplified = simplify(coords_lnglat, 24)
    lngs = [c[0] for c in simplified]
    lats = [c[1] for c in simplified]
    min_lng, max_lng = min(lngs), max(lngs)
    min_lat, max_lat = min(lats), max(lats)
    dx = max(max_lng - min_lng, 1e-9)
    dy = max(max_lat - min_lat, 1e-9)
    pad = 8
    pts = []
    for lng, lat in simplified:
        x = int(pad + (lng - min_lng) / dx * (w - 2 * pad))
        y = int(pad + (1 - (lat - min_lat) / dy) * (h - 2 * pad))  # north up
        pts.append([x, y])
    return {"w": w, "h": h, "pts": pts}


def build(office: tuple[float, float], home: tuple[float, float], selected: str | None) -> dict[str, Any]:
    route = osrm_foot_route(office, home)
    line = route["coords_lnglat"]
    south, west, north, east = bbox_from_line(line)
    elements = overpass_pois(south, west, north, east)
    categories = bucket_places(elements, line)
    path = fit_path(line)

    if not selected:
        # pick first non-empty bucket for a runnable default
        for key in ("relax", "play", "social", "essentials"):
            if categories[key]:
                selected = key
                break
        selected = selected or "relax"

    return {
        "route": {
            "from": "office",
            "to": "home",
            "summary": route["summary"],
            "path": path,
        },
        "categories": categories,
        "selected": selected,
        "selected_places": categories.get(selected, []),
        "meta": {
            "office": {"lat": office[0], "lng": office[1]},
            "home": {"lat": home[0], "lng": home[1]},
            "source": "OSRM + Overpass (OSM)",
        },
    }


def parse_latlng(s: str) -> tuple[float, float]:
    a, b = s.split(",")
    return float(a.strip()), float(b.strip())


def main() -> int:
    p = argparse.ArgumentParser(description="Mapidex OSM commute detour pipe")
    p.add_argument("--office", default=None, help="lat,lng")
    p.add_argument("--home", default=None, help="lat,lng")
    p.add_argument(
        "--category",
        choices=["relax", "play", "social", "essentials"],
        default=None,
    )
    p.add_argument(
        "--out",
        default="/Users/narayan/Documents/GitHub/mapidex/demo/pipe/last_feed.json",
    )
    args = p.parse_args()

    office = parse_latlng(args.office) if args.office else DEFAULT_OFFICE
    home = parse_latlng(args.home) if args.home else DEFAULT_HOME

    print("Fetching OSRM foot route…", file=sys.stderr)
    print("Querying Overpass POIs…", file=sys.stderr)
    feed = build(office, home, args.category)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(feed, f, indent=2)

    # short console summary
    print(json.dumps({
        "summary": feed["route"]["summary"],
        "selected": feed["selected"],
        "counts": {k: len(v) for k, v in feed["categories"].items()},
        "selected_places": [x["name"] for x in feed["selected_places"]],
        "path_pts": len(feed["route"]["path"]["pts"]),
        "wrote": args.out,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
