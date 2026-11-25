# Lokalny agent na Pixel 10 Pro — plan

Agent działa na telefonie i obserwuje stres, sen, jedzenie, doomscrolling, spotkania i pracę.
Dane bierze z Garmin fenix 7 (przez Bluetooth) i z Pixela. Reaguje na bieżąco powiadomieniem,
czatem albo rozmową głosową.

## Zasady (ustalone z użytkownikiem)

1. **Wszystko lokalnie.** Dane zdrowotne i obserwacje nigdy nie trafiają do repo ani do chmury.
   Repo służy tylko jako miejsce na kod.
2. **Agent obserwuje sam.** Użytkownik ma niczego nie raportować; liczba wymaganych interakcji ma być minimalna.
3. **Bez surowych zapisów:** dźwięk, tekst z ekranu i zdjęcia są przetwarzane w pamięci.
   Do bazy trafiają tylko etykiety i krótkie opisy.
   **Wyjątek:** rozmowy z agentem (czat i rozmowa na żywo) są zapamiętywane w całości.
4. **Głos tylko w rozmowie na żywo.** Agent nie odzywa się sam przez głośnik.
5. **Cisza:** respektuje tryb „Nie przeszkadzać” i „Porę snu” Pixela. W tym czasie wstrzymuje wiadomości i wysyła je później.
6. **Wł./wył. jednym ruchem:** kafelek w szybkich ustawieniach i przycisk w powiadomieniu.
   W trybie oszczędzania energii agent ogranicza działanie.
7. **Zmiany zachowania na trzech poziomach:**
   - pamięć i profil: zmienia sam, z dziennikiem zmian;
   - reguły, progi i prompty: proponuje, użytkownik zatwierdza;
   - kod: agent formułuje prośbę, Claude ją realizuje, użytkownik zatwierdza.
8. **Bezpieczniki:** bez zgody użytkownika agent nie zmienia gwarancji prywatności, uprawnień ani historii.

## Źródła danych

| Źródło | Co daje | Jak |
|---|---|---|
| fenix 7 przez **Gadgetbridge** (zastępuje Garmin Connect) | tętno, stres, Body Battery, HRV, SpO2, oddech, tętno spoczynkowe, kroki, sen (fazy, wynik), treningi | Intent API: `ACTIVITY_SYNC` → `ACTIVITY_SYNC_FINISH` → `TRIGGER_EXPORT` → odczyt wyeksportowanej bazy SQLite |
| Historia aplikacji | która aplikacja i jak długo | `UsageStatsManager` |
| Treść ekranu | tekst w aplikacjach, przewijanie | usługa ułatwień dostępu; czarna lista: bank, menedżer haseł |
| Mikrofon | rodzaj otoczenia (rozmowa, TV, cisza, naczynia…), bez zapisu | YAMNet (MediaPipe Audio), usługa pierwszoplanowa typu microphone |
| Głos użytkownika | „Ty mówisz” / „inna osoba” | własny wzorzec głosu (jednorazowe nagranie, zapisany tylko wektor). Voice Match z Gemini nie ma publicznego API |
| Kamera | opis sceny, posiłek | zdjęcie przy używaniu telefonu i z kontekstu dźwięku; bramkowanie czujnikiem zbliżeniowym, światła i orientacją |
| Kalendarz | spotkania | `CalendarContract` (lokalnie) |
| Zadania | Google Tasks | Google Tasks API (OAuth); jedyny odczyt z chmury, to dane użytkownika |
| Komputer | czas pracy | później: ActivityWatch przez LAN; na start kalendarz i dźwięk klawiatury |
| Metatracker | projekty | później, przez miejsce na kolejne źródła danych |

## Modele

- **Gemma 4 E2B** (LiteRT-LM, akcelerator TPU — w AI Edge Gallery działa na Pixelu użytkownika): mózg w tle, czat i rozmowa na żywo.
- **Gemini Nano (AICore):** tylko gdy aplikacja jest na ekranie (`BACKGROUND_USE_BLOCKED` w tle). Opcjonalnie.
- **YAMNet:** lekki, ciągły klasyfikator dźwięku. Gemma uruchamia się tylko na zdarzenie.
- Model **nie jest douczany**. „Nauka” to statystyki dnia plus tekstowy profil dnia, który użytkownik może przeczytać i poprawić.

## Etapy i kryteria sukcesu

| Etap | Zakres | Kryterium |
|---|---|---|
| 0 | Testy na telefonie: Gadgetbridge + fenix 7; prędkość Gemmy 4 E2B na TPU | widać stres, Body Battery i sen w Gadgetbridge; znana prędkość decode (tokeny/s) |
| 1a | Szkielet: usługa w tle, wł./wył. (kafelek, powiadomienie), historia aplikacji do lokalnej bazy, kalendarz, popup z akcjami, cisza (Nie przeszkadzać), oszczędzanie energii | APK z CI instaluje się; po kilku godzinach widać oś dnia; testowy popup dociera na zegarek |
| 1b | Gadgetbridge: cykliczna synchronizacja i import danych z zegarka | stres i Body Battery w bazie agenta z opóźnieniem ≤ 15 min, bez internetu |
| 1c | Dźwięk otoczenia (YAMNet) | etykiety „rozmowa / cisza / TV…” w osi dnia, reakcja ≤ 5 s |
| 1d | Treść ekranu + czarna lista; Google Tasks | opis „co robisz w telefonie” w osi dnia |
| 2 | Tryb nauki (ok. 10 dni) → profil dnia → propozycje reguł do zatwierdzenia | profil i reguły czytelne i edytowalne |
| 3 | Gemma 4 na TPU: rekomendacje, czat (pamiętany), rozmowa na żywo; **scenariusz spotkania** | po spotkaniu wiadomość z danymi o stresie ≤ ~3 min od jego końca |
| 4 | Kamera kontekstowa, rozpoznawanie głosu, komputer, metatracker | — |

## Blokery i ryzyka

- **Gadgetbridge:** wsparcie fenix 7 jest eksperymentalne (znany problem: zawieszająca się synchronizacja na fenix 7S).
  Pogoda na zegarku wymaga uwierzytelnienia zegarka (opcja „fake OAuth” utrudnia powrót do Garmin Connect).
  **Fallback:** własna aplikacja Connect IQ.
- **Mikrofon i kamera w tle:** usługa musi wystartować z otwartej aplikacji. Po restarcie telefonu trzeba raz otworzyć aplikację.
  Zielona kropka jest zawsze widoczna. W kieszeni kamera nic nie widzi.
- **Instalacja spoza Sklepu Play:** przed włączeniem ułatwień dostępu trzeba zezwolić na „ograniczone ustawienia”.
  Play Protect może ostrzegać. Ochrona zaawansowana może blokować.
- **Aplikacje bankowe** mogą reagować na samo włączenie usługi ułatwień dostępu, nawet jeśli agent ich nie czyta.
- **Gemma na TPU we własnej aplikacji:** trzeba potwierdzić, z jakim plikiem modelu i backendem działa (w AI Edge Gallery działa).
- **Klucz podpisu APK:** przed trybem nauki potrzebny jest stały klucz. Inaczej aktualizacja wymaga reinstalacji i kasuje dane.
- **Budowanie lokalne:** wymaga akceptacji licencji Android SDK przez użytkownika. Na razie buduje GitHub Actions.
- **Google Tasks:** potrzebny projekt Google Cloud z klientem OAuth (tryb testowy, jeden użytkownik).

## Notatki techniczne: baza Gadgetbridge (schemat 163)

Tabele greenDAO (nazwy kolumn w UPPER_SNAKE), wspólne kolumny: `TIMESTAMP`, `DEVICE_ID`, `USER_ID`.

- `GARMIN_ACTIVITY_SAMPLE` — `TIMESTAMP` w **sekundach**; `HEART_RATE`, `STEPS`, `RAW_INTENSITY`, `RAW_KIND`, `DISTANCE_CM`, `ACTIVE_CALORIES`
- `GARMIN_STRESS_SAMPLE` — `TIMESTAMP` w ms; `STRESS`
- `GARMIN_BODY_ENERGY_SAMPLE` — `TIMESTAMP` w ms; `ENERGY` (Body Battery)
- `GARMIN_SLEEP_STAGE_SAMPLE` — ms; `STAGE`
- `GARMIN_SLEEP_STATS_SAMPLE` — ms; `SLEEP_SCORE` i składowe
- `GARMIN_HRV_SUMMARY_SAMPLE` — ms; `WEEKLY_AVERAGE`, `LAST_NIGHT_AVERAGE`, `STATUS_NUM`
- `GARMIN_HRV_VALUE_SAMPLE` — ms; `VALUE`
- `GARMIN_SPO2_SAMPLE` — ms; `SPO2`
- `GARMIN_RESPIRATORY_RATE_SAMPLE` — ms; `RESPIRATORY_RATE`
- `GARMIN_HEART_RATE_RESTING_SAMPLE` — ms; `HEART_RATE`
- `DEVICE` — `_id`, `NAME`, `IDENTIFIER` (MAC)

Źródło: `GBDaoGenerator.java` w repo Gadgetbridge (Codeberg). Schemat się zmienia, więc odczyt musi tolerować brak tabel i kolumn.

## Otwarte kwestie (domyślne wartości do czasu decyzji)

- Zdjęcia: **nie zapisujemy**, tylko opis.
- Lokalizacja w tle lub Wi-Fi z pracy: **wyłączone**; wykrywanie spotkań z kalendarza i dźwięku.
- System operacyjny komputera: nieznany (potrzebny dopiero do ActivityWatch).
- Prędkość decode Gemmy 4 E2B na TPU (tokeny/s): do odczytania ze statystyk w AI Edge Gallery.
