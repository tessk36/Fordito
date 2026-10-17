package hu.orajegyzet.ai

/**
 * Melyik motor dolgozzon (1.1.3) – tiszta logika, Android nélkül, így tesztelhető.
 *
 * A beállításokból már csak ez a három számít:
 *  - alap elemző (Gemma 4 helyben / Gemini felhő),
 *  - adatvédelmi zár,
 *  - csak Wi-Fi-n (a hálózat-ellenőrzés része).
 * A korábbi „Mód” (Offline / Online / Automatikus) megszűnt: a működés mindig automatikus.
 */
object EngineRouting {

    enum class Route { GEMINI, GEMMA, EXTRACTIVE }

    /**
     * @param network van-e megfelelő hálózat (a „csak Wi-Fi-n” beállítással együtt)
     * @return a ténylegesen futó elemző
     */
    fun decide(
        preferGemini: Boolean,
        privacyLock: Boolean,
        hasApiKey: Boolean,
        gemmaInstalled: Boolean,
        network: Boolean
    ): Route {
        val geminiAvailable = !privacyLock && hasApiKey && network
        return when {
            preferGemini && geminiAvailable -> Route.GEMINI
            gemmaInstalled -> Route.GEMMA
            // Gemma-alap, de nincs telepített modell: tartalékként a Gemini, ha elérhető
            geminiAvailable -> Route.GEMINI
            else -> Route.EXTRACTIVE
        }
    }

    /** Felhasználónak szóló, egysoros leírás a „Jelenleg:” sorhoz. */
    fun describe(
        preferGemini: Boolean,
        privacyLock: Boolean,
        hasApiKey: Boolean,
        gemmaInstalled: Boolean
    ): String {
        val local = if (gemmaInstalled) "Whisper + Gemma 4 (helyben)"
            else "Whisper + kivonat (nincs telepített Gemma-modell)"
        return when {
            privacyLock -> "Leirat és elemzés: $local – adatvédelmi zár"
            preferGemini && !hasApiKey -> "Leirat és elemzés: $local – nincs Gemini API-kulcs"
            preferGemini -> "Leirat és elemzés: Gemini (felhő); hálózat nélkül: $local"
            gemmaInstalled -> "Leirat és elemzés: $local; a Gemini csak kérésre"
            hasApiKey -> "Leirat és elemzés: Gemini (felhő), mert nincs telepített Gemma-modell; hálózat nélkül: kivonat"
            else -> "Leirat és elemzés: $local"
        }
    }
}
