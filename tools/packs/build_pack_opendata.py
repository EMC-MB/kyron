#!/usr/bin/env python3
"""Region-pack builder for harvest/opendata sources (boston, cincinnati,
detroit). Same convert + invariants as build_pack_socrata; the record
conversion is shared, only the source table differs.

Usage: python tools/packs/build_pack_opendata.py <boston|cincinnati|detroit> [--limit N]
Reads harvest/opendata/out/<key>/places.json, writes
<region>-<version>.db.gz + <region>.manifest.json next to it.
"""
import argparse, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, "harvest", "opendata"))
from harvest import SOURCES  # noqa: E402
import build_pack as bp  # noqa: E402
from build_pack_socrata import convert  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source", choices=sorted(SOURCES))
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    cfg = SOURCES[a.source]
    out = os.path.join(REPO, "harvest", "opendata", "out", a.source)
    places = [convert(p, cfg) for p in
              json.load(open(os.path.join(out, "places.json"), encoding="utf-8"))]
    if a.limit:
        places = places[:a.limit]
    src_m = json.load(open(os.path.join(out, "manifest.json"), encoding="utf-8"))
    assert all(p["state"] == cfg["state"] and p["region_id"] == cfg["region"] for p in places)
    if cfg["model"] == "PASS_FAIL":
        assert all(p["score"] == -1 for p in places), "PASS_FAIL score invented"
    for p in places:
        assert (p["lat"] is not None) == (p["geo_status"] == "ACCEPTED" and bool(p["geo_prov"]))
    bp.write_pack(
        region=cfg["region"], state=cfg["state"], name=cfg["name"],
        model=cfg["model"], source_label=cfg["attribution"],
        places=places, generated_at=src_m["generated_at"], out_dir=out,
        limit=a.limit, attribution=cfg["attribution"],
        jurisdictions=[{"jurisdictionId": cfg["jurisdiction"], "name": cfg["jur_name"]}])


if __name__ == "__main__":
    main()
