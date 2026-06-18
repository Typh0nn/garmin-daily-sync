#!/usr/bin/env python3
"""
garmin_sync.py v5 — garminconnect → GitHub JSON
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tryby:
  --mode morning      wczorajszy sen + dzisiejsze BB po przebudzeniu
  --mode evening      dzisiejszy intraday 30-min
  --mode activities   dzisiejsze aktywności (basen, trening: czas, kalorie, tętno)

Env vars (automatyczne w Actions):
  GITHUB_TOKEN, GITHUB_REPOSITORY

Env vars (sekrety ręczne):
  GARMIN_EMAIL, GARMIN_PASSWORD

ZMIANY v4 → v5:
  • BB poranne: pierwszy pomiar PO przebudzeniu (sleepEndTimestampGMT),
    zamiast pierwszego pomiaru doby (który łapał środek nocy = zaniżone).
  • Nowy tryb activities + plik data/activities.json.
"""

import argparse
import json
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta
import os

from garminconnect import Garmin, GarminConnectAuthenticationError
from github import Auth, Github, GithubException

# ─── CONFIG ────────────────────────────────────────────────────────────────────

REPO_NAME      = os.environ["GITHUB_REPOSITORY"]
GH_TOKEN       = os.environ["GITHUB_TOKEN"]
DAILY_FILE     = "data/daily.json"
INTRADAY_FILE  = "data/intraday.json"
ACTIVITIES_FILE = "data/activities.json"
MAX_DAILY      = 30
MAX_INTRADAY   = 7
MAX_ACTIVITIES = 30

# ─── AUTH ──────────────────────────────────────────────────────────────────────

def garmin_auth() -> Garmin:
    try:
        api = Garmin(
            email=os.environ["GARMIN_EMAIL"],
            password=os.environ["GARMIN_PASSWORD"],
        )
        api.login()
        print("Garmin: zalogowano OK")
        return api
    except GarminConnectAuthenticationError as e:
        print(f"Błąd logowania Garmin: {e}")
        sys.exit(1)

# ─── GITHUB HELPERS ────────────────────────────────────────────────────────────

def gh_read(repo, path: str):
    f = None
    try:
        f = repo.get_contents(path)
        content = f.decoded_content.decode("utf-8-sig").strip()
        if not content:
            return [], f.sha
        return json.loads(content), f.sha
    except json.JSONDecodeError:
        return [], f.sha if f else None
    except GithubException:
        return [], None

def gh_write(repo, path: str, data: list, sha, message: str):
    content = json.dumps(data, ensure_ascii=False, indent=2)
    if sha:
        repo.update_file(path, message, content, sha)
    else:
        repo.create_file(path, message, content)
    print(f"GitHub: '{path}' zapisany ({len(data)} rekordów)")

# ─── GARMIN FETCHERS ───────────────────────────────────────────────────────────

def _parse_gmt(ts) -> datetime | None:
    """sleepEndTimestampGMT bywa epoch-ms (int) albo ISO string."""
    if ts is None:
        return None
    if isinstance(ts, (int, float)):
        return datetime.fromtimestamp(ts / 1000)
    try:
        return datetime.fromisoformat(str(ts).replace("Z", "").split(".")[0])
    except ValueError:
        return None

def fetch_sleep(api: Garmin, date_str: str) -> dict:
    try:
        data  = api.get_sleep_data(date_str)
        dto   = data.get("dailySleepDTO", {})
        score = dto.get("sleepScores", {}).get("overall", {}).get("value")
        return {
            "sleep_score":    score,
            "deep_min":       round((dto.get("deepSleepSeconds")  or 0) / 60),
            "light_min":      round((dto.get("lightSleepSeconds") or 0) / 60),
            "rem_min":        round((dto.get("remSleepSeconds")   or 0) / 60),
            "awake_min":      round((dto.get("awakeSleepSeconds") or 0) / 60),
            "hrv_last_night": dto.get("lastNight"),
            "hrv_weekly_avg": dto.get("hrvWeeklyAverage"),
            "rhr":            dto.get("restingHeartRate"),
            "spo2_avg":       round(dto.get("averageSpO2Value") or 0) or None,
            "_sleep_end":     dto.get("sleepEndTimestampGMT"),  # do BB po przebudzeniu
        }
    except Exception as e:
        print(f"[BŁĄD] sleep ({date_str}): {e}")
        return {}

def fetch_body_battery(api: Garmin, date_str: str) -> list[dict]:
    try:
        data = api.get_body_battery(date_str)
        out  = []
        for day in (data if isinstance(data, list) else [data]):
            for e in day.get("bodyBatteryValuesArray", []):
                if len(e) >= 2 and e[1] is not None:
                    out.append({"time": datetime.fromtimestamp(e[0] / 1000), "bb": e[1]})
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] body battery ({date_str}): {e}")
        return []

def fetch_stress(api: Garmin, date_str: str) -> list[dict]:
    try:
        data = api.get_stress_data(date_str)
        out  = []
        for e in data.get("stressValuesArray", []):
            if len(e) >= 2 and e[1] >= 0:
                out.append({"time": datetime.fromtimestamp(e[0] / 1000), "stress": e[1]})
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] stress ({date_str}): {e}")
        return []

def fetch_heart_rate(api: Garmin, date_str: str) -> list[dict]:
    try:
        data = api.get_heart_rates(date_str)
        out  = []
        for e in data.get("heartRateValues", []):
            if e and len(e) >= 2 and e[1] and e[1] > 0:
                out.append({"time": datetime.fromtimestamp(e[0] / 1000), "hr": e[1]})
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] heart rate ({date_str}): {e}")
        return []

def morning_bb(bb_today: list[dict], sleep_end_raw) -> int | None:
    """
    BB po przebudzeniu = pierwszy pomiar BB o/po sleepEndTimestampGMT.
    Fallback: max z okna 5:00–11:00 (gdyby brakowało czasu końca snu).
    """
    if not bb_today:
        return None
    sleep_end = _parse_gmt(sleep_end_raw)
    if sleep_end:
        after = [r for r in bb_today if r["time"] >= sleep_end]
        if after:
            return after[0]["bb"]
    window = [r for r in bb_today if 5 <= r["time"].hour < 11]
    if window:
        return max(r["bb"] for r in window)
    return bb_today[0]["bb"]

def fetch_activities(api: Garmin, date_str: str) -> list[dict]:
    """Aktywności z danego dnia: typ, czas, czas trwania, kalorie, tętno."""
    try:
        acts = api.get_activities_by_date(date_str, date_str)
        out = []
        for a in acts:
            start = a.get("startTimeLocal", "")
            time_str = start.split(" ")[1][:5] if " " in start else "?"
            out.append({
                "date":         date_str,
                "time":         time_str,
                "type":         (a.get("activityType") or {}).get("typeKey", "?"),
                "name":         a.get("activityName", ""),
                "duration_min": round((a.get("duration") or 0) / 60, 1),
                "calories":     round(a.get("calories") or 0) or None,
                "hr_avg":       round(a.get("averageHR")) if a.get("averageHR") else None,
                "hr_max":       round(a.get("maxHR")) if a.get("maxHR") else None,
            })
        return out
    except Exception as e:
        print(f"[BŁĄD] activities ({date_str}): {e}")
        return []

def aggregate_30min(bb_data, stress_data, hr_data, date_str: str) -> list[dict]:
    target = datetime.strptime(date_str, "%Y-%m-%d").date()
    blocks: dict = defaultdict(lambda: {"bb": [], "stress": [], "hr": []})

    def slot(dt: datetime) -> datetime:
        return dt.replace(minute=(dt.minute // 30) * 30, second=0, microsecond=0)

    for r in bb_data:
        if r["time"].date() == target:
            blocks[slot(r["time"])]["bb"].append(r["bb"])
    for r in stress_data:
        if r["time"].date() == target:
            blocks[slot(r["time"])]["stress"].append(r["stress"])
    for r in hr_data:
        if r["time"].date() == target:
            blocks[slot(r["time"])]["hr"].append(r["hr"])

    result = []
    for ts in sorted(blocks):
        b = blocks[ts]
        result.append({
            "date":       date_str,
            "time":       ts.strftime("%H:%M"),
            "bb":         round(sum(b["bb"])     / len(b["bb"]))     if b["bb"]     else None,
            "stress_avg": round(sum(b["stress"]) / len(b["stress"])) if b["stress"] else None,
            "stress_max": max(b["stress"])                           if b["stress"] else None,
            "hr_avg":     round(sum(b["hr"])     / len(b["hr"]))     if b["hr"]     else None,
        })
    return result

# ─── TRYB: MORNING ─────────────────────────────────────────────────────────────

def run_morning(api: Garmin, repo):
    yesterday  = (date.today() - timedelta(days=1)).strftime("%Y-%m-%d")
    today      = date.today().strftime("%Y-%m-%d")
    print(f"[MORNING] sen={yesterday}, BB_rano={today}")

    sleep      = fetch_sleep(api, yesterday)
    bb_today   = fetch_body_battery(api, today)
    bb_morning = morning_bb(bb_today, sleep.pop("_sleep_end", None))

    daily, sha = gh_read(repo, DAILY_FILE)
    daily      = [d for d in daily if d.get("date") != yesterday]
    daily.append({
        "date": yesterday, **sleep,
        "bb_morning": bb_morning,
        "bb_evening": None, "stress_avg_day": None, "stress_max_day": None,
    })
    daily = sorted(daily, key=lambda x: x["date"])[-MAX_DAILY:]
    gh_write(repo, DAILY_FILE, daily, sha,
             f"morning: sen {yesterday}, BB_rano={bb_morning}")
    print(f"sleep={sleep.get('sleep_score')}, BB_rano={bb_morning}")

# ─── TRYB: EVENING ─────────────────────────────────────────────────────────────

def run_evening(api: Garmin, repo):
    today  = date.today().strftime("%Y-%m-%d")
    cutoff = (date.today() - timedelta(days=MAX_INTRADAY)).strftime("%Y-%m-%d")
    print(f"[EVENING] intraday={today}")

    blocks = aggregate_30min(
        fetch_body_battery(api, today),
        fetch_stress(api, today),
        fetch_heart_rate(api, today),
        today,
    )

    intraday, sha_i = gh_read(repo, INTRADAY_FILE)
    intraday = [r for r in intraday if r.get("date") != today and r.get("date","") >= cutoff]
    intraday = sorted(intraday + blocks, key=lambda x: (x["date"], x["time"]))
    gh_write(repo, INTRADAY_FILE, intraday, sha_i,
             f"evening: intraday {today} ({len(blocks)} bloków)")

    bb_vals = [b["bb"]         for b in blocks if b["bb"]         is not None]
    s_avgs  = [b["stress_avg"] for b in blocks if b["stress_avg"] is not None]
    s_maxes = [b["stress_max"] for b in blocks if b["stress_max"] is not None]

    bb_eve  = bb_vals[-1]                          if bb_vals  else None
    s_avg   = round(sum(s_avgs) / len(s_avgs))    if s_avgs   else None
    s_max   = max(s_maxes)                         if s_maxes  else None

    daily, sha_d = gh_read(repo, DAILY_FILE)
    idx = next((i for i, d in enumerate(daily) if d.get("date") == today), None)
    if idx is not None:
        daily[idx].update({"bb_evening": bb_eve, "stress_avg_day": s_avg, "stress_max_day": s_max})
    else:
        daily.append({"date": today, "bb_evening": bb_eve,
                      "stress_avg_day": s_avg, "stress_max_day": s_max})
    daily = sorted(daily, key=lambda x: x["date"])[-MAX_DAILY:]
    gh_write(repo, DAILY_FILE, daily, sha_d,
             f"evening: BB_wie={bb_eve}, stress_avg={s_avg}")
    print(f"BB {bb_vals[0] if bb_vals else '?'}→{bb_eve}, stress avg={s_avg}, max={s_max}")

# ─── TRYB: ACTIVITIES ──────────────────────────────────────────────────────────

def run_activities(api: Garmin, repo):
    today  = date.today().strftime("%Y-%m-%d")
    cutoff = (date.today() - timedelta(days=MAX_ACTIVITIES)).strftime("%Y-%m-%d")
    print(f"[ACTIVITIES] {today}")

    todays = fetch_activities(api, today)

    activities, sha = gh_read(repo, ACTIVITIES_FILE)
    activities = [a for a in activities if a.get("date") != today and a.get("date","") >= cutoff]
    activities = sorted(activities + todays, key=lambda x: (x["date"], x.get("time","")))
    gh_write(repo, ACTIVITIES_FILE, activities, sha,
             f"activities: {today} ({len(todays)} aktywności)")
    for a in todays:
        print(f"  {a['time']} {a['type']}: {a['duration_min']}min, "
              f"{a['calories']}kcal, HR avg={a['hr_avg']}/max={a['hr_max']}")

# ─── ENTRY POINT ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["morning", "evening", "activities"], required=True)
    args = parser.parse_args()

    api  = garmin_auth()
    repo = Github(auth=Auth.Token(GH_TOKEN)).get_repo(REPO_NAME)

    if args.mode == "morning":
        run_morning(api, repo)
    elif args.mode == "evening":
        run_evening(api, repo)
    else:
        run_activities(api, repo)
