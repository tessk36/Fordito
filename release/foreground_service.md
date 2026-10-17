# Előtérbeli Szolgáltatás (Foreground Service) Nyilatkozat Piszkozat

**Alkalmazás**: Fülelő Hangjegyzet (`hu.orajegyzet`)

## 1. Igényelt Előtérbeli Szolgáltatás Típusok

### `microphone` (Mikrofon)
- **Felhasználói funkció**: Folyamatban lévő órai vagy megbeszélési hangfelvétel rögzítése a háttérben vagy lezárt képernyő mellett.
- **Indítás**: Kizárólag közvetlen felhasználói interakcióból (a gomb megnyomásával az appban, értesítésből vagy kezdőképernyős widgetről).
- **Értesítés**: Folyamatos, nem törölhető értesítés jelenik meg az állapotsorban ("Felvétel: [Téma neve]"), amely tartalmazza az azonnali "Leállítás" gombot.
- **Videó demonstráció útmutató Play Console-hoz**:
  1. Nyisd meg az alkalmazást.
  2. Indíts egy új felvételt a "Felvétel Indítása" gombbal.
  3. Lépj ki a kezdőképernyőre vagy zárd le a kijelzőt (az állapotsorban látható a mikrofon felvételi értesítés).
  4. Húzd le az értesítési sávot, és nyomj a "Leállítás" gombra.

### `dataSync` (Adatszinkronizálás / Háttérmunka)
- **Felhasználói funkció**: A rögzített felvétel háttérbeli leiratozása és összefoglalása a WorkManager segítségével (`ProcessingWorker`).
