#!/usr/bin/env python3
"""
garmin_sync.py v2 — Garmin Connect → GitHub JSON
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tryby:
  --mode morning   wczorajszy sen + dzisiejsze BB rano → data/daily.json
  --mode evening   dzisiejszy intraday 30-min         → data/intraday.json
                                                       + uzupełnienie daily.json

Env vars (automatyczne w GitHub Actions):
  GITHUB_TOKEN        — nadawany automatycznie przez Actions
  GITHUB_REPOSITORY   — nadawany automatycznie (np. "Typh0nn/garmin-daily-sync")

Env vars (sekrety ręczne):
  GARMIN_EMAIL
  GARMIN_PASSWORD
"""

import argparse
import json
import os
from collections import defaultdict
from datetime import date, datetime, timedelta

import garth
from github import Github, GithubException

# ─── CONFIG ────────────────────────────────────────────────────────────────────

REPO_NAME      = os.environ["GITHUB_REPOSITORY"]   # "Typh0nn/garmin-daily-sync"
GH_TOKEN       = os.environ["GITHUB_TOKEN"]

DAILY_FILE     = "data/daily.json"
INTRADAY_FILE  = "data/intraday.json"

MAX_DAILY      = 30   # przechowuj ostatnie N dni w daily.json
MAX_INTRADAY   = 7    # przechowuj ostatnie N dni w intraday.json

# ─── GITHUB HELPERS ────────────────────────────────────────────────────────────

def gh_read(repo, path: str) -> tuple[list, str | None]:
    """Wczytaj JSON z repo. Zwraca (data, sha) lub ([], None) jeśli brak."""
    try:
        f = repo.get_contents(path)
        return json.loads(f.decoded_content.decode("utf-8")), f.sha
    except GithubException:
        return [], None


def gh_write(repo, path: str, data: list, sha: str | None, message: str):
    """Zapisz JSON do repo (create lub update)."""
    content = json.dumps(data, ensure_ascii=False, indent=2)
    if sha:
        repo.update_file(path, message, content, sha)
    else:
        repo.create_file(path, message, content)
    print(f"GitHub: zapisano '{path}' ({len(data)} rekordów)")

# ─── GARMIN AUTH ───────────────────────────────────────────────────────────────

def garmin_auth():
    token_dir = "/tmp/garth"
    os.makedirs(token_dir, exist_ok=True)
    try:
        garth.resume(token_dir)
        garth.connectapi(
            "/wellness-service/wellness/dailyHeartRate/"
            + date.today().strftime("%Y-%m-%d")
        )
        print("Garmin: sesja wznowiona")
    except Exception:
        print("Garmin: loguję przez email/hasło...")
        garth.login(os.environ["GARMIN_EMAIL"], os.environ["GARMIN_PASSWORD"])
        garth.save(token_dir)
        print("Garmin: OK")

# ─── GARMIN FETCHERS ───────────────────────────────────────────────────────────

def fetch_sleep(date_str: str) -> dict:
    try:
        resp = garth.connectapi(
            f"/wellness-service/wellness/dailySleepData/{date_str}",
            params={"nonSleepBufferMinutes": 60},
        )
        dto  = resp.get("dailySleepDTO", {})
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
        }
    except Exception as e:
        print(f"[BŁĄD] sleep ({date_str}): {e}")
        return {}


def fetch_body_battery(date_str: str) -> list[dict]:
    try:
        resp = garth.connectapi(
            "/wellness-service/wellness/bodyBattery",
            params={"startDate": date_str, "endDate": date_str},
        )
        out = []
        for day in resp:
            for e in day.get("bodyBatteryValuesArray", []):
                if len(e) >= 2 and e[1] is not None:
                    out.append({"time": datetime.fromtimestamp(e[0] / 1000), "bb": e[1]})
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] body battery ({date_str}): {e}")
        return []


def fetch_stress(date_str: str) -> list[dict]:
    try:
        resp = garth.connectapi(f"/wellness-service/wellness/dailyStress/{date_str}")
        out = []
        for e in resp.get("stressValuesArray", []):
            if len(e) >= 2 and e[1] >= 0:
                out.append({"time": datetime.fromtimestamp(e[0] / 1000), "stress": e[1]})
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] stress ({date_str}): {e}")
        return []


def fetch_heart_rate(date_str: str) -> list[dict]:
    try:
        resp = garth.connectapi(f"/wellness-service/wellness/dailyHeartRate/{date_str}")
        out = []
        for e in resp.get("heartRateValues", []):
            if e and len(e) >= 2 and e[1] and e[1] > 0:
                out.append({"time": datetime.fromtimestamp(e[0] / 1000), "hr": e[1]})
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] heart rate ({date_str}): {e}")
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

def run_morning(repo):
    yesterday = (date.today() - timedelta(days=1)).strftime("%Y-%m-%d")
    today     = date.today().strftime("%Y-%m-%d")
    print(f"[MORNING] sen={yesterday}, BB_rano={today}")

    sleep      = fetch_sleep(yesterday)
    bb_today   = fetch_body_battery(today)
    bb_morning = bb_today[0]["bb"] if bb_today else None

    daily, sha = gh_read(repo, DAILY_FILE)

    # Usuń ewentualny stary wpis dla yesterday (idempotentne)
    daily = [d for d in daily if d.get("date") != yesterday]

    new_entry = {
        "date":         yesterday,
        **sleep,
        "bb_morning":   bb_morning,
        "bb_evening":   None,        # uzupełni evening run
        "stress_avg_day": None,
        "stress_max_day": None,
    }
    daily.append(new_entry)

    # Zostaw tylko ostatnie MAX_DAILY dni
    daily = sorted(daily, key=lambda x: x["date"])[-MAX_DAILY:]

    gh_write(repo, DAILY_FILE, daily, sha,
             f"morning sync: sen {yesterday}, BB rano {bb_morning}")
    print(f"Wynik: sleep={sleep.get('sleep_score')}, HRV={sleep.get('hrv_last_night')}, BB_rano={bb_morning}")


# ─── TRYB: EVENING ─────────────────────────────────────────────────────────────

def run_evening(repo):
    today = date.today().strftime("%Y-%m-%d")
    print(f"[EVENING] intraday={today}")

    bb_data     = fetch_body_battery(today)
    stress_data = fetch_stress(today)
    hr_data     = fetch_heart_rate(today)
    blocks      = aggregate_30min(bb_data, stress_data, hr_data, today)

    # ── Intraday JSON ────────────────────────────────────────────────────────
    intraday, sha_intra = gh_read(repo, INTRADAY_FILE)

    # Usuń dzisiejsze wpisy (idempotentne), dodaj nowe
    cutoff   = (date.today() - timedelta(days=MAX_INTRADAY)).strftime("%Y-%m-%d")
    intraday = [r for r in intraday if r.get("date") != today and r.get("date", "") >= cutoff]
    intraday.extend(blocks)
    intraday = sorted(intraday, key=lambda x: (x["date"], x["time"]))

    gh_write(repo, INTRADAY_FILE, intraday, sha_intra,
             f"evening sync: intraday {today} ({len(blocks)} bloków)")

    # ── Uzupełnij daily.json: BB wieczór + stres ─────────────────────────────
    bb_vals      = [b["bb"]         for b in blocks if b["bb"]         is not None]
    stress_avgs  = [b["stress_avg"] for b in blocks if b["stress_avg"] is not None]
    stress_maxes = [b["stress_max"] for b in blocks if b["stress_max"] is not None]

    bb_evening     = bb_vals[-1]                                         if bb_vals     else None
    stress_avg_day = round(sum(stress_avgs) / len(stress_avgs))         if stress_avgs else None
    stress_max_day = max(stress_maxes)                                   if stress_maxes else None

    daily, sha_daily = gh_read(repo, DAILY_FILE)

    # Znajdź lub utwórz wpis dla today
    idx = next((i for i, d in enumerate(daily) if d.get("date") == today), None)
    if idx is not None:
        daily[idx]["bb_evening"]     = bb_evening
        daily[idx]["stress_avg_day"] = stress_avg_day
        daily[idx]["stress_max_day"] = stress_max_day
    else:
        daily.append({
            "date":           today,
            "bb_evening":     bb_evening,
            "stress_avg_day": stress_avg_day,
            "stress_max_day": stress_max_day,
        })

    daily = sorted(daily, key=lambda x: x["date"])[-MAX_DAILY:]
    gh_write(repo, DAILY_FILE, daily, sha_daily,
             f"evening sync: BB_wieczór={bb_evening}, stres_avg={stress_avg_day}")

    print(f"Wynik: BB {bb_vals[0] if bb_vals else '?'}→{bb_evening}, "
          f"stress avg={stress_avg_day}, max={stress_max_day}")


# ─── ENTRY POINT ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["morning", "evening"], required=True)
    args = parser.parse_args()

    garmin_auth()

    g    = Github(GH_TOKEN)
    repo = g.get_repo(REPO_NAME)

    if args.mode == "morning":
        run_morning(repo)
    else:
        run_evening(repo)
