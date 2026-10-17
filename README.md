# Fordító — bármilyen nyelv → magyar (Android)

> Az AssiSten-s 1.1.8 forkja. Saját azonosító (`hu.assisten.fordito`), így az AssiSten-s
> mellé települ, saját adatokkal. Változások: lásd `CHANGELOG.md`.

Szöveget és beszédet fordít bármilyen nyelvről magyarra, és a kimenetet a választott
módban írja át:

| Mód | Mit ad |
|---|---|
| Hű fordítás | pontos, az eredeti szerkezetét követő fordítás |
| Természetes | gördülékeny, magyaros fordítás |
| Hivatalos | hivatalos, udvarias stílus (levélhez, dokumentumhoz) |
| Egyszerű | rövid mondatok, hétköznapi szavak, szakszavak magyarázata |
| Összefoglaló | rövid magyar összefoglaló |

Magyar bemenetnél nem fordít, hanem a kért módban átírja a szöveget.

**Bemenetek:** beírt vagy beillesztett szöveg, élő felvétel (🎙 Beszéd), hangfájl (🎵),
megosztás más appból (szöveg vagy hang), és a kijelölt szöveg menüjében a
„Fordítás magyarra” pont.

**Élő tolmács (0.2):** a 🎧 Élő gombbal folyamatos tolmácsolás – online a Gemini Live
(`gemini-3.5-live-translate-preview`, kb. 1–2 mp késés, kérésre magyar hanggal), vagy helyben
Whisper + Gemma 4-gyel (kb. 3–6 mp késés). A képernyő tolmácsolás közben nem alszik el.

**Motorok** (ugyanaz a logika, mint az AssiSten-s-ben):
- **Helyben:** Whisper (automatikus nyelvfelismeréssel) + Gemma 4 (LiteRT-LM). Semmi nem hagyja el a telefont.
- **Felhő:** Gemini – hangnál egy hívásban átirat és fordítás, a fájl utána törlődik a felhőből.
- **Adatvédelmi zár:** mindig helyben fut.

## 1. Fordítás és telepítés

1. Android Studio → nyisd meg a projektet, várd meg a Gradle sync-et.
2. Kell az **NDK (Side by side)** és a **CMake** (Tools → SDK Manager → SDK Tools), mert a
   Whisper forrásból fordul. Az első build a natív rész miatt 5–20 perc.
3. Build → Build APK(s). Kimenet: `Fordito-0.1.0-debug.apk` / `-release.apk`.

## 2. Modellek

Ugyanazok a fájlok, mint az AssiSten-s-hez (Beállítások → Modellek → importálás):
- **Whisper:** `ggml-large-v3-turbo.bin` vagy `ggml-large-v3-turbo-q5_0.bin` –
  https://huggingface.co/ggerganov/whisper.cpp/tree/main.
  **Többnyelvű modell kell** (a `.en` végűek csak angolul működnek).
- **Gemma 4:** `gemma-4-E2B-it.litertlm` – https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

A két app külön tárhelyet használ, ezért a modelleket a Fordítóba is importálni kell.

**Online mód:** ingyenes Gemini API-kulcs: https://aistudio.google.com/apikey →
Beállítások → Online (Gemini), és a Feldolgozásnál „Gemini (felhő)”.

## 3. Projektszerkezet

```
app/src/main/java/hu/orajegyzet/      (a Kotlin-csomag neve az eredeti maradt)
  App.kt                  — értesítési csatornák, beragadt munkák feloldása
  MainActivity.kt         — navigáció, megosztás fogadása, mikrofonengedély
  data/Translations.kt    — Room: fordítások (fordito.db)
  data/Settings.kt        — DataStore: motor, kulcsok, Whisper, utolsó mód
  rec/RecordingService.kt — mikrofonos előtér-szolgáltatás (M4A, 60 perces korlát)
  work/TranslateWorker.kt — háttérfordítás (szöveg és hang), kész-értesítés
  ai/Translator.kt        — módok, promptok, blokkolás, motorválasztás
  ai/GeminiPipeline.kt    — Gemini: szöveg és hang (beágyazva vagy Files API)
  ai/OfflinePipeline.kt   — Whisper-átirat, 16 kHz mono átmintavételezés
  ai/GemmaEngine.kt       — Gemma 4 (LiteRT-LM)
  ui/TranslatorScreen.kt  — Fordító, Előzmények, részletnézet
  ui/SettingsScreen.kt    — Beállítások
  ui/LiveScreen.kt        — élő tolmács képernyő és vezérlő
  ai/LiveTranslateClient.kt — Gemini Live (WebSocket) fordító kliens
  ai/LiveLocalTranslator.kt — helyi élő tolmács, beszédszakaszoló
  rec/MicStream.kt        — 16 kHz-es élő mikrofonfolyam
```

## 4. Ismert korlátok (0.1)

- A Gemma 4 kis tokenkerete miatt a hosszú szöveg kb. 1600 karakteres részekben fordul;
  a részek határán a stílus kissé eltérhet.
- Ritkább nyelveken a Gemma gyengébb; ilyenkor a Gemini pontosabb.
- A Whisper rövid (pár másodperces) hangnál tévedhet a nyelvben.
