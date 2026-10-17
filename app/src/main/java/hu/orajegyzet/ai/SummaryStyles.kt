package hu.orajegyzet.ai

/**
 * A „Különböző típusú összefoglaló” gombok stílusai (1.1.1).
 * Mindegyik saját, egyértelmű szerkezetet ír elő, és ugyanezt kapja a Gemini és a Gemma is,
 * így a típusok ténylegesen különböznek.
 */
object SummaryStyles {

    enum class Style(val label: String) {
        EXECUTIVE("Vezetői"),
        MINUTES("Jegyzőkönyv"),
        ACTIONS("Teendők"),
        DETAILED("Részletes jegyzet")
    }

    fun fromLabel(label: String): Style = when (label) {
        "Vezetői" -> Style.EXECUTIVE
        "Jegyzőkönyv" -> Style.MINUTES
        "Teendők" -> Style.ACTIONS
        else -> Style.DETAILED
    }

    const val SYSTEM =
        "Magyar nyelvű hivatali asszisztens vagy. Kizárólag a kapott szövegben szereplő tényeket használod, " +
            "nem találsz ki nevet, dátumot vagy számot. Pontosan a kért szerkezetben válaszolsz, " +
            "markdown formázással, bevezető és záró megjegyzés nélkül."

    private const val RULES =
        "\n\nSzabályok: kizárólag a szövegben szereplő tényeket használd; ne találj ki nevet, dátumot vagy számot. " +
            "Magyarul válaszolj, bevezető és záró megjegyzés nélkül, pontosan a megadott szerkezetben."

    fun instruction(style: Style): String = when (style) {
        Style.EXECUTIVE -> """
            Vezetői összefoglalót írj, legfeljebb 150 szóban, pontosan ezzel a szerkezettel:
            **Lényeg:** 1–2 mondat a megbeszélés céljáról és eredményéről.
            **Döntések:** felsorolás (ha nincs: „nem született döntés”).
            **Kockázatok, nyitott kérdések:** legfeljebb 3 pont (ha nincs: „nincs”).
            **Következő lépés:** egyetlen mondat.
            Ne részletezd a vitát, és ne sorold fel az apró feladatokat.
        """.trimIndent() + RULES

        Style.MINUTES -> """
            Hivatalos jegyzőkönyvet írj, pontosan ezzel a szerkezettel:
            **Tárgy:** egy sor.
            **Résztvevők:** csak akik a szövegben név szerint szerepelnek (ha nincs: „nem rögzített”).
            **Napirendi pontok:** számozott lista.
            **Elhangzottak:** napirendi pontonként, múlt időben, harmadik személyben, tárgyszerű mondatokban.
            **Határozatok:** számozott lista (ha nincs: „nem született határozat”).
            **Felelősök és határidők:** lista „feladat – felelős – határidő” formában.
            Részletes legyen: ne vond össze a napirendi pontokat.
        """.trimIndent() + RULES

        Style.ACTIONS -> """
            KIZÁRÓLAG a teendőket gyűjtsd ki, összefoglaló szöveg nélkül, ebben a táblázatban:
            | # | Feladat | Felelős | Határidő |
            |---|---|---|---|
            Ha a felelős vagy a határidő nem hangzott el, írd: „nincs megadva”.
            Ha a szövegben nincs teendő, csak ennyit írj: „A szövegben nem hangzott el teendő.”
        """.trimIndent() + RULES

        Style.DETAILED -> """
            Részletes jegyzetet készíts: témakörönként „##” alcím, alatta felsorolásban a lényeges
            állítások, adatok, példák és érvek. A végén: **Kulcsfogalmak:** vesszővel elválasztva.
            Ne rövidíts: minden érdemi témát vegyél fel, a sorrend kövesse az elhangzottakat.
        """.trimIndent() + RULES
    }

    /** Leirat-blokkok tényeinek kigyűjtése (map-lépés), hogy a végső stílus minden adatot megkapjon. */
    const val BLOCK_FACTS_PROMPT =
        "Gyűjtsd ki az alábbi leirat-részlet minden érdemi tényét tömör pontokban: témák, elhangzott állítások, " +
            "döntések, feladatok felelőssel és határidővel. Csak a szövegben szereplő tényeket használd, magyarul."
}
