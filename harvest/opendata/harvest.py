#!/usr/bin/env python3
"""Open-data city harvests on CKAN (Boston), Socrata (Cincinnati) and
ArcGIS FeatureServer (Detroit): fetch -> normalize -> validate.

Usage:  python harvest.py boston|cincinnati|detroit [--limit-rows N]
Output: out/<key>/{places.json, fetch_log.json, validate_report.json,
        manifest.json}
Then:   python ../../tools/packs/build_pack_opendata.py <key>

Read-only GETs. Every family pages deterministically (CKAN _id,
Socrata :id, ArcGIS objectIds) and the fetched row count is checked
against the portal's own count before emitting.
"""
import argparse, os, sys, urllib.parse
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "socrata_cities"))
import common  # noqa: E402
from sources import _place, _finish, _iso, _coord, NO_SCORE  # noqa: E402

CKAN = "https://data.boston.gov/api/3/action/"
SOCRATA = "https://{domain}/resource/{dataset}.json"
LOUISVILLE = ("https://services1.arcgis.com/79kfd2K6fskCAkyg/arcgis/rest/"
              "services/FoodServiceData/FeatureServer")
ARCGIS_DETROIT = ("https://services2.arcgis.com/qvkbeam7Wirps6zC/arcgis/rest/"
                  "services/food_service_establishment_inspections/FeatureServer")

SOURCES = {
    "boston": {
        "region": "us-ma", "state": "MA", "name": "Massachusetts (Boston)",
        "source_id": "ma-boston", "jurisdiction": "MA-BOSTON-ISD",
        "jur_name": "City of Boston Inspectional Services Department",
        "dataset": "food-establishment-inspections", "county": "Suffolk",
        "model": "PASS_FAIL", "resource": "4582bec6-2b4f-4f9e-bc55-cbaa73117f4c",
        "detail": "https://data.boston.gov/dataset/food-establishment-inspections",
        "attribution": "City of Boston — Food Establishment Inspections, "
                       "Analyze Boston (food-establishment-inspections). "
                       "Public data; City of Boston open data terms of use.",
    },
    "cincinnati": {
        "region": "us-oh", "state": "OH", "name": "Ohio (Cincinnati)",
        "source_id": "oh-cincinnati", "jurisdiction": "OH-CINCINNATI-HEFD",
        "jur_name": "City of Cincinnati Health Department — Food Safety Program",
        "dataset": "rg6p-b3h3", "domain": "data.cincinnati-oh.gov",
        "county": "Hamilton", "model": "VIOLATIONS_ONLY",
        "detail": "https://data.cincinnati-oh.gov/d/rg6p-b3h3",
        "attribution": "City of Cincinnati — Food Safety Program inspections, "
                       "Cincinnati Open Data (rg6p-b3h3). Public data; City of "
                       "Cincinnati open data terms of use.",
    },
    "louisville": {
        "region": "us-ky", "state": "KY", "name": "Kentucky (Louisville)",
        "source_id": "ky-louisville", "jurisdiction": "KY-LOUISVILLE-LMPHW",
        "jur_name": "Louisville Metro Department of Public Health & Wellness",
        "dataset": "FoodServiceData", "county": "Jefferson",
        "model": "SCORE_100_HIGH", "detail": "https://louvue.com/",
        "attribution": "Louisville Metro Department of Public Health & Wellness — "
                       "Restaurant Inspection Scores, Louisville Metro KY ArcGIS "
                       "(FoodServiceData). Public data; Louisville Metro open data terms.",
    },
    "detroit": {
        "region": "us-mi", "state": "MI", "name": "Michigan (Detroit)",
        "source_id": "mi-detroit", "jurisdiction": "MI-DETROIT-HD",
        "jur_name": "Detroit Health Department — Environmental Health Services",
        "dataset": "food_service_establishment_inspections", "county": "Wayne",
        "model": "VIOLATIONS_ONLY", "detail": "https://data.detroitmi.gov/pages/restaurant-inspections",
        "attribution": "City of Detroit — Restaurant Inspections, Detroit Open "
                       "Data Portal (food_service_establishment_inspections). "
                       "Public data; City of Detroit open data terms of use.",
    },
}


def fetch_ckan(cfg, limit):
    step, off, rows, log = 30000, 0, [], []
    while True:
        q = {"resource_id": cfg["resource"], "limit": step, "offset": off, "sort": "_id"}
        res = common.fetch_json(CKAN + "datastore_search?" + urllib.parse.urlencode(q))["result"]
        page = res["records"]
        rows.extend(page)
        log.append({"offset": off, "rows": len(page)})
        print(f"  {len(rows):,}/{res['total']:,}")
        if len(page) < step or (limit and len(rows) >= limit):
            break
        off += step
    return rows, {"expected": res["total"], "pages": log}


def fetch_socrata(cfg, limit):
    base = SOCRATA.format(domain=cfg["domain"], dataset=cfg["dataset"])
    expected = int(common.fetch_json(base + "?" + urllib.parse.urlencode({"$select": "count(*)"}))[0]["count"])
    step, off, rows, log = 50000, 0, [], []
    while True:
        q = {"$limit": step, "$offset": off, "$order": ":id"}
        page = common.fetch_json(base + "?" + urllib.parse.urlencode(q))
        rows.extend(page)
        log.append({"offset": off, "rows": len(page)})
        print(f"  {cfg['dataset']}: {len(rows):,}/{expected:,}")
        if len(page) < step or (limit and len(rows) >= limit):
            break
        off += step
    return rows, {"expected": expected, "pages": log}


def fetch_arcgis_layer(layer_url, geometry, limit):
    meta = common.fetch_json(layer_url + "/query?" + urllib.parse.urlencode(
        {"where": "1=1", "returnIdsOnly": "true", "f": "json"}))
    expected, oid = len(meta["objectIds"]), meta["objectIdFieldName"]
    feats, off, step = [], 0, 1000
    while True:
        q = {"where": "1=1", "outFields": "*", "orderByFields": oid, "resultOffset": off,
             "resultRecordCount": step, "returnGeometry": "true" if geometry else "false",
             "outSR": "4326", "f": "json"}
        page = common.fetch_json(layer_url + "/query?" + urllib.parse.urlencode(q)).get("features", [])
        feats.extend(page)
        print(f"  {layer_url.rsplit('/', 1)[-1]}: {len(feats):,}/{expected:,}")
        if len(page) < step or (limit and len(feats) >= limit):
            break
        off += step
    if limit:
        feats = feats[:limit]
    return feats, {"expected": expected, "fetched": len(feats)}


def parse_location(loc):
    try:
        lat, lng = (float(x) for x in (loc or "").strip("()").split(","))
        return _coord(lat, lng)
    except (TypeError, ValueError):
        return None, None


def normalize_boston(rows, cfg):
    places = {}
    for r in rows:
        key = (r.get("licenseno") or "").strip()
        name = (r.get("businessname") or r.get("dbaname") or "").strip()
        d, res = _iso(r.get("resultdttm")), (r.get("result") or "").strip()
        if not key or not name:
            continue
        p = places.get(key)
        if p is None:
            lat, lng = parse_location(r.get("location"))
            p = places[key] = _place(cfg, key, name, r.get("address") or "",
                                     r.get("city") or "BOSTON", r.get("zip"),
                                     cfg["county"], lat, lng, cfg["detail"])
        if not d or not res:
            continue
        ins = p["_insp"].get((d, res))
        if ins is None:
            ins = p["_insp"][(d, res)] = {"d": d, "t": "", "s": NO_SCORE, "disp": res, "v": [], "_seen": set()}
        code = (r.get("violation") or "").strip()
        if code and code not in ins["_seen"]:
            ins["_seen"].add(code)
            ins["v"].append({"p": 0, "c": False, "r": f"{code}: {(r.get('violdesc') or '').strip()}"})
    return _finish(places)


def normalize_cincinnati(rows, cfg):
    places = {}
    for r in rows:
        key = (r.get("license_no") or "").strip()
        name = (r.get("business_name") or "").strip()
        d = _iso(r.get("action_date"))
        if not key or not name:
            continue
        p = places.get(key)
        if p is None:
            lat, lng = _coord(r.get("latitude"), r.get("longitude"))
            p = places[key] = _place(cfg, key, name, r.get("address") or "",
                                     (r.get("city") or "").title(), r.get("postal_code"),
                                     cfg["county"], lat, lng, cfg["detail"])
        if not d:
            continue
        ikey = r.get("recordnum_insp") or f"{key}|{d}"
        ins = p["_insp"].get(ikey)
        if ins is None:
            ins = p["_insp"][ikey] = {"d": d, "t": (r.get("insp_type") or "").title(),
                                      "s": NO_SCORE, "disp": "", "v": [], "_seen": set()}
        code = (r.get("code") or "").strip()
        if code and code not in ins["_seen"]:
            ins["_seen"].add(code)
            ins["v"].append({"p": 0, "c": False,
                             "r": f"{code}: {(r.get('violation_description') or '').strip()}"[:300]})
    return _finish(places)


def normalize_louisville(feats, cfg):
    places = {}
    for f in feats:
        a = f["attributes"]
        key = str(a.get("EstablishmentID") or "")
        name = (a.get("EstablishmentName") or "").strip()
        d = _iso(a.get("InspectionDate"))
        if not key or not name:
            continue
        p = places.get(key)
        if p is None:
            p = places[key] = _place(cfg, key, name, a.get("Address") or "",
                                     (a.get("City") or "").title(), str(a.get("Zip") or ""),
                                     cfg["county"], None, None, cfg["detail"])
        if not d:
            continue
        ikey = str(a.get("InspectionID") or f"{key}|{d}")
        if ikey not in p["_insp"]:
            score = a.get("score")
            grade = (a.get("Grade") or "").strip()
            p["_insp"][ikey] = {"d": d, "t": (a.get("Ins_TypeDesc") or "").title(),
                                "s": int(score) if isinstance(score, (int, float)) else NO_SCORE,
                                "disp": f"Grade {grade}" if grade else "", "v": [], "_seen": set()}
    return _finish(places)


def normalize_detroit(ests, insps, viols, cfg):
    places, by_insp = {}, {}
    for f in ests:
        a = f["attributes"]
        key = str(a.get("establishment_id") or "")
        name = (a.get("establishment_name") or "").strip()
        if not key or not name:
            continue
        g = f.get("geometry") or {}
        lat, lng = _coord(g.get("y"), g.get("x"))
        places[key] = _place(cfg, key, name, a.get("address") or "", "Detroit",
                             str(a.get("zip_code") or ""), cfg["county"], lat, lng, cfg["detail"])
    for f in insps:
        a = f["attributes"]
        p = places.get(str(a.get("establishment_id") or ""))
        d = _iso(a.get("inspection_date"))
        if p is None or not d:
            continue
        ins = by_insp[str(a["inspection_id"])] = {"d": d, "t": (a.get("inspection_type") or "").title(),
                                                  "s": NO_SCORE, "disp": "", "v": [], "_seen": set()}
        p["_insp"][str(a["inspection_id"])] = ins
    for f in viols:
        a = f["attributes"]
        ins = by_insp.get(str(a.get("inspection_id")))
        code = (a.get("violation_code") or "").strip()
        if ins is None or not code or code in ins["_seen"]:
            continue
        ins["_seen"].add(code)
        ins["v"].append({"p": 0, "c": (a.get("violation_type") or "") == "Priority",
                         "r": f"{code}: {(a.get('violation_description') or '').strip()}"[:300]})
    return _finish(places)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source", choices=sorted(SOURCES))
    ap.add_argument("--limit-rows", type=int, default=0)
    a = ap.parse_args()
    cfg = SOURCES[a.source]
    out = os.path.join(HERE, "out", a.source)
    if a.source == "boston":
        rows, flog = fetch_ckan(cfg, a.limit_rows)
        places = normalize_boston(rows, cfg)
    elif a.source == "cincinnati":
        rows, flog = fetch_socrata(cfg, a.limit_rows)
        places = normalize_cincinnati(rows, cfg)
    elif a.source == "louisville":
        feats, flog = fetch_arcgis_layer(LOUISVILLE + "/0", False, a.limit_rows)
        places = normalize_louisville(feats, cfg)
    else:
        ests, e1 = fetch_arcgis_layer(ARCGIS_DETROIT + "/0", True, a.limit_rows)
        insps, e2 = fetch_arcgis_layer(ARCGIS_DETROIT + "/1", False, a.limit_rows)
        viols, e3 = fetch_arcgis_layer(ARCGIS_DETROIT + "/2", False, a.limit_rows)
        flog = {"establishments": e1, "inspections": e2, "violations": e3}
        places = normalize_detroit(ests, insps, viols, cfg)
    rep = common.validate_places(places, forbid_scores=cfg["model"] != "SCORE_100_HIGH")
    common.emit(out, places=places, fetch_log=flog, validate_report=rep,
                manifest={"generated_at": datetime.now(timezone.utc).isoformat(),
                          "source": a.source, "dataset": cfg["dataset"]})
    print({k: v for k, v in rep.items() if k != "errors"})
    return 1 if rep["error_count"] else 0


if __name__ == "__main__":
    sys.exit(main())
