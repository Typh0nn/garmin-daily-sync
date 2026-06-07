#!/usr/bin/env python3
"""
garmin_sync.py — Garmin Connect → Google Sheets
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tryby:
  --mode morning   wczorajszy sen + dzisiejsze BB o poranku → zakładka Daily
  --mode evening   dzisiejszy intraday 30-min → zakładka Intraday + uzupełnienie Daily

Env vars wymagane:
  GARMIN_EMAIL, GARMIN_PASSWORD
  GSHEET_CREDENTIALS  (JSON service account jako string)
  GSHEET_ID           (ID arkusza Google Sheets)
"""

import argparse
import json
import os
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta

import garth
import gspread
from google.oauth2.service_account import Credentials

# ─── CONFIG ────────────────────────────────────────────────────────────────────

GSHEET_ID = os.environ["GSHEET_ID"]

GSCOPES = [
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive",
]

DAILY_HEADERS = [
    "Date", "Sleep Score", "Deep (min)", "Light (min)", "REM (min)",
    "Awake (min)", "HRV last night", "HRV weekly avg",
    "RHR", "SpO2 avg", "BB morning", "BB evening",
    "Stress avg day", "Stress max day",
]

INTRADAY_HEADERS = [
    "Date", "Time", "BB", "Stress avg", "Stress max", "HR avg",
]

# ─── AUTH ──────────────────────────────────────────────────────────────────────

def garmin_auth():
    """Login z email/hasło. Tokeny cache'owane w /tmp/garth."""
    token_dir = "/tmp/garth"
    os.makedirs(token_dir, exist_ok=True)
    try:
        garth.resume(token_dir)
        # Szybki test że sesja działa
        garth.connectapi("/wellness-service/wellness/heartRate/" + date.today().strftime("%Y-%m-%d"))
        print("Garmin: sesja wznowiona z tokenów")
    except Exception:
        print("Garmin: logowanie przez email/hasło...")
        garth.login(os.environ["GARMIN_EMAIL"], os.environ["GARMIN_PASSWORD"])
        garth.save(token_dir)
        print("Garmin: zalogowano OK")


def gsheets_client() -> gspread.Client:
    """Zwraca autoryzowanego klienta gspread z service account."""
    info = json.loads(os.environ["GSHEET_CREDENTIALS"])
    creds = Credentials.from_service_account_info(info, scopes=GSCOPES)
    return gspread.authorize(creds)

# ─── SHEET HELPERS ─────────────────────────────────────────────────────────────

def get_or_create_ws(ss, title: str, headers: list) -> gspread.Worksheet:
    """Pobierz lub utwórz zakładkę z nagłówkami."""
    try:
        return ss.worksheet(title)
    except gspread.WorksheetNotFound:
        ws = ss.add_worksheet(title=title, rows=5000, cols=max(len(headers) + 2, 10))
        ws.append_row(headers, value_input_option="RAW")
        print(f"Zakładka '{title}' utworzona")
        return ws


def find_row(ws, date_str: str) -> int | None:
    """Zwróć numer wiersza (1-based) dla daty lub None."""
    col = ws.col_values(1)
    for i, val in enumerate(col):
        if val == date_str:
            return i + 1
    return None

# ─── GARMIN FETCHERS ───────────────────────────────────────────────────────────

def fetch_sleep(date_str: str) -> dict:
    """Pobierz podsumowanie snu dla daty (YYYY-MM-DD)."""
    try:
        resp = garth.connectapi(
            f"/wellness-service/wellness/dailySleepData/{date_str}",
            params={"nonSleepBufferMinutes": 60},
        )
        dto = resp.get("dailySleepDTO", {})
        score = dto.get("sleepScores", {}).get("overall", {}).get("value")
        return {
            "sleep_score":    score,
            "deep_min":       round((dto.get("deepSleepSeconds")  or 0) / 60),
            "light_min":      round((dto.get("lightSleepSeconds") or 0) / 60),
            "rem_min":        round((dto.get("remSleepSeconds")   or 0) / 60),
            "awake_min":      round((dto.get("awakeSleepSeconds") or 0) / 60),
            "hrv_last_night": dto.get("lastNight"),
            "hrv_weekly_avg": dto.get("hrvWeeklyAverage"),
            "resting_hr":     dto.get("restingHeartRate"),
            "spo2_avg":       round(dto.get("averageSpO2Value") or 0) or None,
        }
    except Exception as e:
        print(f"[BŁĄD] Sleep ({date_str}): {e}")
        return {}


def fetch_body_battery(date_str: str) -> list[dict]:
    """Pobierz odczyty Body Battery (timestamp + wartość)."""
    try:
        resp = garth.connectapi(
            "/wellness-service/wellness/bodyBattery",
            params={"startDate": date_str, "endDate": date_str},
        )
        out = []
        for day in resp:
            for entry in day.get("bodyBatteryValuesArray", []):
                if len(entry) >= 2 and entry[1] is not None:
                    out.append({
                        "time": datetime.fromtimestamp(entry[0] / 1000),
                        "bb":   entry[1],
                    })
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] Body Battery ({date_str}): {e}")
        return []


def fetch_stress(date_str: str) -> list[dict]:
    """Pobierz stres intraday (co ~3 min, -1 = brak danych)."""
    try:
        resp = garth.connectapi(
            f"/wellness-service/wellness/dailyStress/{date_str}"
        )
        out = []
        for entry in resp.get("stressValuesArray", []):
            if len(entry) >= 2 and entry[1] >= 0:
                out.append({
                    "time":   datetime.fromtimestamp(entry[0] / 1000),
                    "stress": entry[1],
                })
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] Stress ({date_str}): {e}")
        return []


def fetch_heart_rate(date_str: str) -> list[dict]:
    """Pobierz tętno intraday."""
    try:
        resp = garth.connectapi(
            f"/wellness-service/wellness/dailyHeartRate/{date_str}"
        )
        out = []
        for entry in resp.get("heartRateValues", []):
            if entry and len(entry) >= 2 and entry[1] and entry[1] > 0:
                out.append({
                    "time": datetime.fromtimestamp(entry[0] / 1000),
                    "hr":   entry[1],
                })
        return sorted(out, key=lambda x: x["time"])
    except Exception as e:
        print(f"[BŁĄD] Heart Rate ({date_str}): {e}")
        return []


def aggregate_30min(bb_data, stress_data, hr_data, date_str: str) -> list[dict]:
    """Agreguj odczyty intraday do bloków 30-minutowych."""
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

def run_morning(gc: gspread.Client):
    """Wczorajszy sen + dzisiejsze BB o poranku → zakładka Daily."""
    yesterday = (date.today() - timedelta(days=1)).strftime("%Y-%m-%d")
    today     = date.today().strftime("%Y-%m-%d")

    print(f"[MORNING] Sen: {yesterday} | BB otwarcie: {today}")

    sleep     = fetch_sleep(yesterday)
    bb_today  = fetch_body_battery(today)
    bb_morning = bb_today[0]["bb"] if bb_today else None

    ss = gc.open_by_key(GSHEET_ID)
    ws = get_or_create_ws(ss, "Daily", DAILY_HEADERS)

    row = [
        yesterday,
        sleep.get("sleep_score"),
        sleep.get("deep_min"),
        sleep.get("light_min"),
        sleep.get("rem_min"),
        sleep.get("awake_min"),
        sleep.get("hrv_last_night"),
        sleep.get("hrv_weekly_avg"),
        sleep.get("resting_hr"),
        sleep.get("spo2_avg"),
        bb_morning,   # col 11 — BB morning
        None,         # col 12 — BB evening (uzupełni evening run)
        None,         # col 13 — Stress avg day
        None,         # col 14 — Stress max day
    ]

    existing = find_row(ws, yesterday)
    if existing:
        ws.update(f"A{existing}", [row], value_input_option="RAW")
        print(f"Zaktualizowano wiersz {existing} dla {yesterday}")
    else:
        ws.append_row(row, value_input_option="RAW")
        print(f"Dodano nowy wiersz dla {yesterday}")

    print(f"Wynik: sleep={sleep.get('sleep_score')}, "
          f"HRV={sleep.get('hrv_last_night')}, BB_rano={bb_morning}")

# ─── TRYB: EVENING ─────────────────────────────────────────────────────────────

def run_evening(gc: gspread.Client):
    """Dzisiejszy intraday (30-min bloki) → Intraday + uzupełnienie Daily."""
    today = date.today().strftime("%Y-%m-%d")
    print(f"[EVENING] Intraday: {today}")

    bb_data     = fetch_body_battery(today)
    stress_data = fetch_stress(today)
    hr_data     = fetch_heart_rate(today)
    blocks      = aggregate_30min(bb_data, stress_data, hr_data, today)

    ss = gc.open_by_key(GSHEET_ID)

    # ── Zakładka Intraday ────────────────────────────────────────────────────
    ws_intra = get_or_create_ws(ss, "Intraday", INTRADAY_HEADERS)

    # Usuń dzisiejsze wiersze (idempotentne re-run)
    all_vals = ws_intra.get_all_values()
    keep = [r for r in all_vals if r and r[0] != today]
    ws_intra.clear()
    if keep:
        ws_intra.update("A1", keep, value_input_option="RAW")

    rows = [
        [b["date"], b["time"], b["bb"], b["stress_avg"], b["stress_max"], b["hr_avg"]]
        for b in blocks
    ]
    if rows:
        ws_intra.append_rows(rows, value_input_option="RAW")
    print(f"Intraday: {len(blocks)} bloków zapisanych")

    # ── Uzupełnij Daily: BB wieczór + stres dzienny ──────────────────────────
    ws_daily = get_or_create_ws(ss, "Daily", DAILY_HEADERS)

    bb_vals      = [b["bb"]         for b in blocks if b["bb"]         is not None]
    stress_avgs  = [b["stress_avg"] for b in blocks if b["stress_avg"] is not None]
    stress_maxes = [b["stress_max"] for b in blocks if b["stress_max"] is not None]

    bb_evening     = bb_vals[-1]                                         if bb_vals      else None
    stress_avg_day = round(sum(stress_avgs) / len(stress_avgs))         if stress_avgs  else None
    stress_max_day = max(stress_maxes)                                   if stress_maxes else None

    row_idx = find_row(ws_daily, today)
    if row_idx:
        # Zaktualizuj kolumny L-N (12-14)
        ws_daily.update(
            f"L{row_idx}:N{row_idx}",
            [[bb_evening, stress_avg_day, stress_max_day]],
            value_input_option="RAW",
        )
        print(f"Daily zaktualizowane: BB_wieczór={bb_evening}, "
              f"stress_avg={stress_avg_day}, stress_max={stress_max_day}")
    else:
        # Morning nie uruchomiony — utwórz minimalny wiersz
        ws_daily.append_row(
            [today] + [None] * 10 + [bb_evening, stress_avg_day, stress_max_day],
            value_input_option="RAW",
        )
        print(f"Daily: nowy wiersz dla {today} (tylko evening)")

    print("Evening sync zakończony.")

# ─── ENTRY POINT ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Garmin → Google Sheets")
    parser.add_argument(
        "--mode", choices=["morning", "evening"], required=True,
        help="morning: sen + BB otwierający | evening: intraday 30-min",
    )
    args = parser.parse_args()

    garmin_auth()
    gc = gsheets_client()

    if args.mode == "morning":
        run_morning(gc)
    else:
        run_evening(gc)
