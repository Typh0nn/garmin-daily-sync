# Setup — Garmin → GitHub JSON
## Czas: ~15 minut | Koszt: 0 zł | Google Cloud: niepotrzebny

---

## Wymagane sekrety GitHub (tylko 2)

| Secret | Wartość |
|--------|---------|
| `GARMIN_EMAIL` | email do Garmin Connect |
| `GARMIN_PASSWORD` | hasło do Garmin Connect |

`GITHUB_TOKEN` i `GITHUB_REPOSITORY` — GitHub Actions dostarcza je **automatycznie**.
Nie musisz ich nigdzie dodawać.

---

## KROK 1 — Repo publiczne

1. Wejdź na https://github.com/Typh0nn/garmin-daily-sync
2. **Settings → General** → przewiń na dół do "Danger Zone"
3. **Change visibility → Make public** → potwierdź
4. (Repo musi być publiczne żeby Claude mógł czytać dane przez URL)

---

## KROK 2 — Wgraj pliki do repo

Upewnij się że w repo masz:
```
garmin_sync.py
.github/
  workflows/
    morning.yml
    evening.yml
```

Jeśli masz już repo sklonowane lokalnie:
```bash
# skopiuj nowe pliki, następnie:
git add .
git commit -m "v2: GitHub JSON output"
git push
```

---

## KROK 3 — Utwórz folder data/

GitHub nie przechowuje pustych folderów.
Utwórz pusty plik żeby folder istniał:

```bash
mkdir data
echo "[]" > data/daily.json
echo "[]" > data/intraday.json
git add data/
git commit -m "init data folder"
git push
```

---

## KROK 4 — Dodaj sekrety GitHub

1. GitHub → repo → **Settings → Secrets and variables → Actions**
2. **New repository secret** × 2:

| Name | Value |
|------|-------|
| `GARMIN_EMAIL` | twój email Garmin Connect |
| `GARMIN_PASSWORD` | twoje hasło Garmin Connect |

---

## KROK 5 — Test manualny

1. GitHub → **Actions → Garmin Morning Sync**
2. **Run workflow → Run workflow**
3. Poczekaj ~1 min → zielony ptaszek = sukces
4. Sprawdź czy w repo pojawił się `data/daily.json` z danymi

Powtórz dla **Garmin Evening Sync**.

---

## Gotowe — dane dostępne pod URL

```
https://raw.githubusercontent.com/Typh0nn/garmin-daily-sync/main/data/daily.json
https://raw.githubusercontent.com/Typh0nn/garmin-daily-sync/main/data/intraday.json
```

Claude czyta te URL-e automatycznie podczas morning brief i wieczornego check-inu.

---

## Rozwiązywanie problemów

**Błąd 401 Garmin** → sprawdź email/hasło w Secrets; zaloguj się raz ręcznie przez Garmin Connect w przeglądarce (czasem wymaga potwierdzenia)

**Brak danych (puste pola)** → zegarek musi zsynchronizować się z aplikacją Garmin Connect przed uruchomieniem skryptu (Bluetooth lub WiFi)

**Actions nie uruchamiają się automatycznie** → GitHub dezaktywuje scheduled workflows jeśli repo nie miało żadnej aktywności przez 60 dni; wystarczy jeden ręczny push
