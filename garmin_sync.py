#!/usr/bin/env python3
"""
garmin_sync.py v3 — garminconnect + token cache w GitHub Secrets
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tryby:
  --mode morning   wczorajszy sen + dzisiejsze BB rano
  --mode evening   dzisiejszy intraday 30-min

Env vars (automatyczne w Actions):
  GITHUB_TOKEN, GITHUB_REPOSITORY

Env vars (sekrety ręczne):
  GARMIN_EMAIL, GARMIN_PASSWORD
  GARMIN_TOKENS   (opcjonalny — cache tokenów, skrypt sam go ustawia)
"""

import argparse
import base64
import json
import os
import subprocess
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta

from garminconnect import Garmin, GarminConnectAuthenticationError
from github import Github, GithubException

# ─── CONFIG ────────────────────────────────────────────────────────────────────

REPO_NAME     = os.environ["GITHUB_REPOSITORY"]
GH_TOKEN      = os.environ["GITHUB_TOKEN"]
TOKEN_FILE    = "/tmp/garmin_tokens.json"

DAILY_FILE    = "data/daily.json"
INTRADAY_FILE = "data/intraday.json"
MAX_DAILY     = 30
MAX_INTRADAY  = 7

# ─── GARMIN AUTH (z cache tokenów) ────────────────────────────────────────────

def save_tokens_to_secret(api: Garmin):
    """Zapisz tokeny jako GitHub Secret GARMIN_TOKENS — reużywane przy kolejnych runach."""
    try:
        token_data = {
            "oauth1": api.garth.oauth1_token.dict() if api.garth.oauth1_token else None,
            "oauth2": api.garth.oauth2_token.dict() if api.garth.oauth2_token else None,
        }
        encoded = base64.b64encode(json.dumps(token_data).encode()).decode()

        # Użyj GitHub CLI do ustawienia sekretu
        result = subprocess.run(
            ["gh", "secret", "set", "GARMIN_TOKENS",
             "--repo", REPO_NAME,
             "--body", encoded],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            print("Tokeny zapisane do GitHub Secret GARMIN_TOKENS")
        else:
            print(f"Nie udało się zapisać tokenów: {result.stderr}")
    except Exception as e:
        print(f"Zapis tokenów pominięty: {e}")


def garmin_auth() -> Garmin:
    """Zaloguj przez tokeny (cache) lub email/hasło (pierwsze uruchomienie)."""
    api = Garmin()

    # Próba 1: użyj cached tokenów z env
    cached = os.environ.get("GARMIN_TOKENS", "")
    if cached:
        try:
            token_data = json.loads(base64.b64decode(cached).decode())
            api.garth.oauth1_token = token_data.get("oauth1")
            api.garth.oauth2_token = token_data.get("oauth2")
            api.display_name  # test czy sesja działa
            print("Garmin: zalogowano z tokenów (cache)")
            return api
        except Exception:
            print("Garmin: tokeny wygasły, loguję ponownie...")

    # Próba 2: email/hasło
    try:
        api = Garmin(
            email=os.environ["GARMIN_EMAIL"],
            password=os.environ["GARMIN_PASSWORD"],
        )
        api.login()
        print("Garmin: zalogowano przez email/hasło")
        save_tokens_to_secret(api)
        return api
    except GarminConnectAuthenticationError as e:
        print(f"Błąd logowania Garmin: {e}")
        sys.exit(1)

# ─── GITHUB HELPERS ────────────────────────────────────────────────────────────

def gh_read(repo, path: str) -> tuple[list, str | None]:
    try:
        f = repo.get_contents(path)
        return json.loads(f.decoded_content.decode("utf-8")), f.sha
    except GithubException:
        return [], None


def gh_write(repo, path: str, data: list, sha: str | None, message: str):
    content = json.dumps(data, ensure_ascii=False, indent=2)
    if sha:
        repo.update_file(path, message, content, sha)
    else:
        repo.create_file(path, message, content)
    print(f"GitHub: '{path}' zapisany ({len(data)} rekordów)")

# ─── GARMIN FETCHERS ───────────────────────────────────────────────────────────

def fetch_sleep(api: Garmin, date_str: str) -> dict:
    try:
        data = api.get_sleep_data(date_str)
        dto  = data.get("dailySleepDTO", {})
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


def fetch_body_battery(api: Garmin, date_str: str) -> list[dict]:
    try:
        data = api.get_body_battery(date_str)
        out  = []
        for day in (data if isinstance(data, list) else [data]):
            for e in day.get("bodyBatteryValuesArray", []):
                if len(e) >= 2 and e[1] is not None:
                    out.append({
                        "time": datetime.fromtimestamp(e[0] / 1000),
                        "bb":   e[1],
                    })
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
                out.append({
                    "time":   datetime.fromtimestamp(e[0] / 1000),
                    "stress": e[1],
                })
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
                out.append({
                    "time": datetime.fromtimestamp(e[0] / 1000),
                    "hr":   e[1],
                })
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

def run_morning(api: Garmin, repo):
    yesterday  = (date.today() - timedelta(days=1)).strftime("%Y-%m-%d")
    today      = date.today().strftime("%Y-%m-%d")
    print(f"[MORNING] sen={yesterday}, BB_rano={today}")

    sleep      = fetch_sleep(api, yesterday)
    bb_today   = fetch_body_battery(api, today)
    bb_morning = bb_today[0]["bb"] if bb_today else None

    daily, sha = gh_read(repo, DAILY_FILE)
    daily = [d for d in daily if d.get("date") != yesterday]
    daily.append({
        "date": yesterday, **sleep,
        "bb_morning": bb_morning,
        "bb_evening": None, "stress_avg_day": None, "stress_max_day": None,
    })
    daily = sorted(daily, key=lambda x: x["date"])[-MAX_DAILY:]
    gh_write(repo, DAILY_FILE, daily, sha,
             f"morning: sen {yesterday}, BB_rano={bb_morning}")
    print(f"sleep={sleep.get('sleep_score')}, HRV={sleep.get('hrv_last_night')}, BB_rano={bb_morning}")


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

    # Intraday JSON
    intraday, sha_i = gh_read(repo, INTRADAY_FILE)
    intraday = [r for r in intraday if r.get("date") != today and r.get("date","") >= cutoff]
    intraday = sorted(intraday + blocks, key=lambda x: (x["date"], x["time"]))
    gh_write(repo, INTRADAY_FILE, intraday, sha_i,
             f"evening: intraday {today} ({len(blocks)} bloków)")

    # Uzupełnij daily
    bb_vals  = [b["bb"]         for b in blocks if b["bb"]         is not None]
    s_avgs   = [b["stress_avg"] for b in blocks if b["stress_avg"] is not None]
    s_maxes  = [b["stress_max"] for b in blocks if b["stress_max"] is not None]

    bb_eve   = bb_vals[-1]                           if bb_vals  else None
    s_avg    = round(sum(s_avgs) / len(s_avgs))      if s_avgs   else None
    s_max    = max(s_maxes)                           if s_maxes  else None

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


# ─── ENTRY POINT ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["morning", "evening"], required=True)
    args = parser.parse_args()

    api  = garmin_auth()
    g    = Github(GH_TOKEN)
    repo = g.get_repo(REPO_NAME)

    if args.mode == "morning":
        run_morning(api, repo)
    else:
        run_evening(api, repo)
