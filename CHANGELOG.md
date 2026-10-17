# Fordító

## 0.2.2 – 2026. 10. 17.

- Élő tolmács, új kijelző:
  - futás közben egysoros fejléc (idő, nyelvpár, mód, hang, Stop) – a beállítások csak indítás előtt látszanak;
  - két külön ablak: felül az eredeti, alul a magyar (nagyobb betűvel); fekvő helyzetben egymás mellett;
  - mondatonkénti tagolás és számozás (mondatvégnél vagy 1,5 mp szünet után új sor), a legutóbbi sor kiemelve;
  - leállítás után a feliratok maradnak, alattuk Másolás / Mentés / Törlés.
- A verziószám a Beállítások alján látszik.

## 0.2.0 – 2026. 10. 17.

- **Élő tolmács** (🎧 Élő a főképernyőn): beszélsz, és folyamatosan jön a magyar felirat.
  - Online: Gemini Live, `gemini-3.5-live-translate-preview` modell (WebSocket), kb. 1–2 mp késés; kérésre a magyar hang is szól (visszhangszűrővel).
  - Helyben: beszédszakaszolás szünetnél, Whisper-átirat, Gemma 4 fordítás szakaszonként; kb. 3–6 mp késés. Adatvédelmi zárnál csak ez fut.
- Az élő modell neve a Beállítások → Online részben átírható.
- Az élő tolmácsolás menthető az előzmények közé.
- Új unit tesztek: beszédszakaszoló, Live-üzenetek értelmezése.

## 0.1.0 – 2026. 10. 17. (fork az AssiSten-s 1.1.8-ból)

- Új app: **Fordító**, azonosító `hu.assisten.fordito` (az AssiSten-s mellé települ).
- Fordítás bármilyen nyelvről magyarra, öt átírási móddal (hű, természetes, hivatalos, egyszerű, összefoglaló).
- Bemenet: szöveg, élő felvétel, hangfájl, megosztás más appból, „Fordítás magyarra” a kijelölt szöveg menüjében.
- Whisper: automatikus nyelvfelismerés (`params.language = "auto"`), a szótár nyelvi utalás nélkül.
- Importált hangfájlok átmintavételezése 16 kHz mono-ra (Pcm16kConverter).
- Gemini: hangból egy hívásban átirat + fordítás (JSON), tetszőleges hangformátummal.
- Előzmények kereséssel, újrafordítás más módban, másolás, megosztás, felolvasás.
- Kikerült: órarend, csengetés, iskolai mód, widget, projektasszisztens, jelentések, napi e-mail-kivonat.

---

# Előzmény: AssiSten-s

# Változási Napló (Changelog) – AssiSten-s (korábban Fülelő Hangjegyzet)

## 1.1.8 (versionCode 15) – Összeomlás javítása döntés / feladat törlésekor

- **Hibajavítás:** a Projekt Asszisztensben egy döntés/kockázat vagy egy Gantt-feladat **törlése** összeomlást okozott (NullPointerException). A szerkesztett elem a törlés indításakor már ki volt ürítve, mire a háttérmenet futott. A törlendő elem most előre rögzítődik.

## 1.1.7 (versionCode 14) – Összecsukható Iskolai mód

- A Beállításokban az Iskolai mód is összecsukható szakasz lett (alapból csukva), mint a többi; a fejléc mutatja az állapotát („Iskolai mód: be/ki”).

## 1.1.6 (versionCode 13) – Gemini-modellek lekérése a kulcsoddal

- **Hiba volt:** a Gemini-modellválasztó egy beégetett, 5 elemű listát mutatott, amelyből a `gemini-3.5-flash` és a `gemini-3.7-flash` 2026. október 8-án kivezetésre került.
- Új **„🔄 Frissítés”** gomb (Beállítások → Online (Gemini)): az app a saját API-kulcsoddal lekéri az összes elérhető modellt (ListModels), és a jegyzetkészítésre alkalmasakat mutatja (a TTS, kép, élő, beágyazó stb. modellek kiszűrve). A lista megmarad a következő indításra.
- A választó rádiógombos lista lett, rövid megjegyzéssel (gyors/olcsó, kiegyensúlyozott, legerősebb); a jelenleg kiválasztott modell akkor is látszik, ha már nincs a listán.
- Frissített beépített tartaléklista (`GeminiModels.FALLBACK`): 3.1-flash-lite, 3.5-flash-lite, 3.6-flash, 3.8-flash, flash-latest, 3.1-pro-preview. Ezt használja a 404-es tartalékváltás is.

## 1.1.5 (versionCode 12) – Mentett AI-jelentések és havi összefoglaló

- **Hiba volt:** a projektjelentések (Vezetői, Státusz, QA) és a jegyzetek típusos összefoglalói (Vezetői, Jegyzőkönyv, Teendők, Részletes) csak a képernyőn látszottak, bezáráskor elvesztek.
- Új `ai_reports` tábla (adatbázis 5 → 6, adatvesztés nélküli migráció). Mentődik:
  - minden elkészült projektjelentés (a Gemini-újrafogalmazás is, külön tételként);
  - a jegyzetek AI-val (Gemini / Gemma) készült típusos összefoglalói – a jegyzethez és a projekthez is.
- **Projekt Asszisztens → „Jelentések” fül:** a mentett jelentések időrendben, motorral; koppintásra megnyílik, törölhető.
- **Jegyzet nézet → „Mentett összefoglalók”:** lenyitható lista, másolás, törlés.
- **„📅 Havi összefoglaló”** gomb (az utolsó 6 hónapból választható): a hónap megbeszéléseiből (összefoglalóval), a hónapban mentett jelentésekből, döntéseiből, kockázataiból és feladataiból készül, majd a Jelentések fülre mentődik. Gemini → Gemma → offline vázlat útvonalon; Gemmán a 4000 tokenes kerethez arányosan rövidített tényblokkal.
- A projekt törlésekor a hozzá tartozó mentett jelentések is törlődnek.

## 1.1.4 (versionCode 11) – Pontosabb Whisper-leirat, modell-áttekintés, letöltési linkek

- **Szótár a Whispernek** (Beállítások → Feldolgozás → Leirat): nevek, szakszavak vesszővel; a felvétel témája automatikusan hozzáadódik. A Whisper ezek írásmódját követi (initial prompt).
- **Pontosabb átírás** kapcsoló: beam search (5 változat) a gyors, mohó dekódolás helyett; kb. 1,5–2× lassabb. Bekapcsolva a leirat időkorlátja 30 helyett 60 perc.
- A whisper.cpp JNI-hívása megkapja a szótárt és a beam-méretet (`WhisperDecodeConfig`).
- **Betöltött modellek** kártya (Beállítások → Modellek):
  - Whisper: a fájl fejlécéből felismert típus (pl. „large-v3-turbo · q5_0”), méret, eredeti fájlnév; figyelmeztet, ha csak angol (.en) modell van fent;
  - Gemma: változat (E2B / E4B), méret, eredeti fájlnév.
- A Whisper-import ellenőrzi, hogy valódi ggml-modellt választottál-e, és megjegyzi az eredeti nevet.
- **Közvetlen letöltési gombok**: Whisper large-v3-turbo q5_0 / q8_0, Gemma 4 E2B / E4B.

## 1.1.3 (versionCode 10) – Egyszerűbb Feldolgozás-beállítások

- **A „Mód” sor (Offline / Online / Automatikus) megszűnt.** A működés mindig automatikus, a motort az **Alap elemző** dönti el:
  - Gemma 4 (helyben): minden a telefonon készül (Whisper + Gemma 4); a Gemini csak kérésre;
  - Gemini (felhő): a Gemini ír át és elemez; hálózat nélkül automatikusan helyben készül.
- **A „Gemma 4 használata” kapcsoló megszűnt:** ha telepítve van, a Gemma mindig rendelkezésre áll.
- A „csak Wi-Fi-n” kapcsoló csak Gemini alap elemzőnél látszik.
- Megmaradt: **Adatvédelmi zár** (kérésre sem fut Gemini).
- Egyszeri átállás frissítéskor: Offline mód → Gemma 4 alap elemző; Online mód → Gemini alap elemző.
- A színtéma az alap elemzőt követi: Gemma 4 vagy zár = borostyán, Gemini = zöld.
- Új tiszta döntési logika (`EngineRouting`) tesztekkel; a „Jelenleg:” sor is ebből készül.

## 1.1.2 (versionCode 9) – Enyhébb, de név- és számérzékeny ellenőrzés

- **Hibajavítás:** a Gemma-összefoglaló gyakran elveszett a „hallucináció-szűrő” miatt. A régi szűrő a szavak 60%-os egyezését várta a leirattal, ami átfogalmazó összefoglalónál (és Whisper-elírásoknál) túl szigorú.
- Új egyezésvizsgálat (`Coherence`):
  - 20% alatt: a válasz elvetve, kivonat készül (a figyelmeztetés megmutatja a pontos egyezést);
  - 20–35% között: a Gemma szövege **megmarad**, sárga figyelmeztetéssel és az egyezés százalékával;
  - külön jelzés, ha az összefoglalóban **a leiratban nem szereplő név vagy szám** van (a lista- és táblázatsorszámok nem számítanak).
- A figyelmeztető kártyán új **„Kivonat”** gomb: egy érintéssel látható a nem AI-s kivonat is.
- A projektjelentések Gemma-ága ugyanezt a logikát használja.

## 1.1.1 (versionCode 8) – Valóban eltérő összefoglaló-típusok, átlátható Gemma-beállítás

- **Hibajavítás – egyforma összefoglalók:** a „Vezetői / Jegyzőkönyv / Teendők / Részletes jegyzet” gombok eddig ugyanazt a JSON-alapú, 8–12 mondatos jegyzetet kérték (Geminin), illetve ugyanazt a „foglald össze N mondatban” utasítást (Gemmán). Mostantól minden típus saját szerkezetet ír elő (`SummaryStyles`):
  - Vezetői: ≤150 szó, Lényeg / Döntések / Kockázatok / Következő lépés;
  - Jegyzőkönyv: Tárgy, Résztvevők, Napirendi pontok, Elhangzottak, Határozatok, Felelősök és határidők;
  - Teendők: táblázat (Feladat | Felelős | Határidő);
  - Részletes jegyzet: témakörönkénti alcímek + Kulcsfogalmak.
- Gemini: új `generateFreeText` hívás kötött JSON-séma nélkül, így a kért szerkezet megmarad (a projektjelentések is ezt használják).
- Gemma: hosszú leiratnál a blokkokból előbb tények gyűjtése, majd a kért stílusban egyetlen végső összefoglaló; a szerkezet szavai (pl. „Határozatok”) nem számítanak hallucinációnak.
- **Beállítások → Feldolgozás:** új „Jelenleg: …” sor mutatja, ténylegesen melyik motor készíti az összefoglalót (és miért). A Gemma-kapcsoló felirata egyértelműbb.
- A Gemma ismétlés-szűrője megőrzi a sortöréseket, így a címsoros, felsorolásos és táblázatos válasz nem esik szét egyetlen bekezdéssé (projektjelentéseknél is).
- Stílusos Gemma-összefoglalónál nagyobb hely marad a válasznak (500 helyett 1200 token), hogy a jegyzőkönyv és a részletes jegyzet ne szakadjon el.
- A jegyzetmegjelenítő táblázatot is rajzol (a „Teendők” típus táblázata nem nyers `|` jelekkel látszik).
- Ha a „Gemma 4 használata” ki van kapcsolva, a telepített modell már nem kényszeríti offline kivonatra a feldolgozást – a Gemini fut.

## 1.1.0 (versionCode 7) – Gemma 4 a LiteRT-LM motoron

- **Motorcsere:** a MediaPipe (`tasks-genai`) helyett a LiteRT-LM (`litertlm-android:0.11.0`, ugyanaz, mint a Google AI Edge Gallery 1.0.20-ban). Így fut a **Gemma 4 E2B** (`gemma-4-E2B-it.litertlm`, 2,59 GB).
- Gyorsító: először GPU, hiba esetén automatikusan CPU; a ténylegesen használt gyorsító látszik az import és a modellteszt eredményében.
- Tokenkeret 2048 → 4000 (a Gallery Gemma 4-beállítása), így ritkábban kell blokkosítani.
- A modell rögzített neve: `files/models/gemma.litertlm`. Indításkor a régi néven (pl. `gemma.task`) mentett, de valójában `.litertlm` tartalmú fájl **automatikusan átneveződik** – a korábban betöltött Gemma 4 újraimportálás nélkül működik.
- Az import csak `.litertlm` fájlt fogad (fejléc: `LITERTLM`), ellenőrzi a szabad helyet és a teljes átmásolást; a régi `.task` fájlra érthető üzenetet ad.
- **Alap elemző választó** (Beállítások → Feldolgozás): Gemma 4 (helyben, alapértelmezett) / Gemini (felhő). Gemma-alapnál a Gemini csak akkor fut, ha nincs telepített Gemma-modell, vagy kérésre („Újrafogalmazás Geminivel”).
- `minSdk` 26 → 31 (Android 12+), a LiteRT-LM igénye.

## 1.0.4 (versionCode 6) – AssiSten-s

**Átnevezés**
- Az app neve **AssiSten-s** (indítóikon, nyitóképernyő, widget, e-mail-tárgy, APK-név).
  A belső azonosító (`hu.orajegyzet`) és az adatbázis változatlan: a frissítés megtartja az adatokat.

**P1 – Gemma-import javítása**
- A modell mindig rögzített néven kerül a `files/models` mappába (`gemma.task` / `gemma.bin`);
  az eredeti fájlnév csak a beállításokba kerül. Közös modellkeresés: `GemmaModel.file()`.
- Indításkor az 1.0.3 által eredeti néven mentett Gemma automatikusan átnevezésre kerül.
- Az import hibái láthatók (piros szöveg + Snackbar); a `.litertlm` (Gemma 4) érthető üzenettel elutasítva.
- Import után próbabetöltés (120 mp időkorlát); sikertelen betöltésnél a fájl törlődik.
- A modellnév mentése már nem írja felül a titkos mezőket (külön `setGemmaModelName`).

**P2 – Valódi folyamatlépések és forrásjelvény**
- A tréfás, körbeforgó feliratok helyett a valódi lépés látszik (pl. „Leirat: 3/12 darab (Whisper)",
  „Összefoglaló: Gemma (2/4 blokk)"). Új oszlop: `notes.progress` (Room-migráció 4 → 5).
- Minden jegyzeten jelvény: „📱 Gemma" / „☁ Gemini" / „Kivonat (nem AI)" / „Offline vázlat";
  figyelmeztetésnél sárga sáv „Összefoglaló újra" gombbal (`summaryEngine`, `summaryWarning`).

**P3 – Projektjelentések offline is**
- Vezetői összefoglaló, Státuszjelentés és QA-audit kódban összeállított tényblokkból (`ReportBuilder`).
- Útválasztás: adatvédelmi zár → Gemma; Gemini csak engedélyezett módban ÉS tényleges hálózattal,
  hibánál Gemma; modell nélkül „Offline vázlat" sablon. A prompt soha nem kerül a kivonatolóba.
- „Újrafogalmazás Geminivel" gomb, ha a jelentés offline készült és most van hálózat.

**P4 – Stílusos összefoglaló hosszú leiratnál**
- A 2048 tokenes keretbe nem férő szöveget blokkonként foglalja össze (map-reduce), a Gemma
  egyszer töltődik be, blokkonként 120 mp, összesen 10 perc időkorláttal; a meleg Whisper előtte elenged.
- A stílusgombok is ellenőrzik a hálózatot, és Gemini-hibánál Gemmára esnek vissza.

**P5 – Vosk eltávolítása**
- Kikerült a `vosk-android` és a JNA függőség, a Vosk-import és -ág; a régi Vosk-modell indításkor törlődik.

**P6 – Iskolai mód kapcsoló**
- Beállítások → „Iskolai mód (órarend, csengetés, widget)". Kikapcsolva az órarend rejtve,
  a csengetések törölve, a widget letiltva; az adatok megmaradnak. Frissítéskor bekapcsolt, ha van felvitt óra.

## [1.0.2] - 2026-03-24
### Hozzáadva & Fejlesztve
- **Szoftveres biztonság és titkosítás**: Android KeyStore AES/GCM/NoPadding titkosítás bevezetése a Gemini API-kulcsokhoz és az SMTP-jelszavakhoz (`SecretCipher`).
- **Adatbázis migráció és sémaexport**: Room sémaexport engedélyezése (`3.json`) és `.fallbackToDestructiveMigration()` eltávolítása az adatvesztés megelőzésére.
- **Hálózati biztonság**: API-kulcs eltávolítása az URL-ekből, küldése biztonságos `x-goog-api-key` fejlécben.
- **ANR és szálkezelés javítása**: A felvételindítás nem blokkolja a fő szálat; azonnali előtér-szolgáltatási értesítés induláskor (`RecordingService`).
- **16 KB-os lapméret-igazítás (Android 15+)**: C++ CMake linkbeállítások frissítése (`-Wl,-z,max-page-size=16384`) a natív Whisper library-khez.
- **Felületi szerkesztés**: Döntések, kockázatok és Gantt WBS feladatok szerkesztési lehetősége ✏️ ikonokkal. Összecsukható projekt kártyák a tisztább felületért.
- **Hibakezelés**: Korrigált HTTP 400/401/403/429 hibakezelés és méretellenőrzés a felesleges fájlfeltöltések elkerülésére.
