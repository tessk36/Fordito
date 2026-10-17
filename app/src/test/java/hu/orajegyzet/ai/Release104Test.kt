package hu.orajegyzet.ai

import hu.orajegyzet.data.GanttTaskEntity
import hu.orajegyzet.data.ProjectEntity
import hu.orajegyzet.data.ProjectEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Az 1.0.4 új, tiszta logikájának unit tesztjei (P1, P3, P4). */
class Release104Test {

    // ---------- P1 / 1.1: modellformátum felismerése (LiteRT-LM) ----------

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun litertlmHeaderIsAccepted() {
        val f = GemmaModel.detectFormat("LITERTLM".toByteArray(Charsets.ISO_8859_1), "gemma-4-E2B-it.litertlm")
        assertEquals(GemmaModel.Format.LITERTLM, f)
        assertNull(GemmaModel.rejectionMessage(f))
    }

    @Test fun litertlmHeaderWinsOverWrongFileName() {
        // pl. a régi verziók „gemma.task" néven mentették a Gemma 4 fájlt
        assertEquals(GemmaModel.Format.LITERTLM,
            GemmaModel.detectFormat("LITERTLM".toByteArray(Charsets.ISO_8859_1), "gemma.task"))
    }

    @Test fun mediapipeTaskIsRejectedWithLitertlmHint() {
        val f = GemmaModel.detectFormat(bytes(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        assertEquals(GemmaModel.Format.TASK, f)
        assertTrue(GemmaModel.rejectionMessage(f)!!.contains(".litertlm"))
    }

    @Test fun mediapipeTfliteIsRejected() {
        val f = GemmaModel.detectFormat(bytes(0x18, 0, 0, 0, 'T'.code, 'F'.code, 'L'.code, '3'.code))
        assertEquals(GemmaModel.Format.TFLITE, f)
        assertNotNull(GemmaModel.rejectionMessage(f))
    }

    @Test fun whisperGgmlIsRejected() {
        val f = GemmaModel.detectFormat("lmggxxxx".toByteArray(Charsets.ISO_8859_1))
        assertEquals(GemmaModel.Format.GGML, f)
        assertTrue(GemmaModel.rejectionMessage(f)!!.contains("Whisper"))
    }

    @Test fun truncatedLitertlmGetsDownloadHint() {
        val f = GemmaModel.detectFormat(bytes(0, 0, 0, 0, 0, 0, 0, 0), "gemma-4-E2B-it.litertlm")
        assertEquals(GemmaModel.Format.UNKNOWN, f)
        assertTrue(GemmaModel.rejectionMessage(f, "gemma-4-E2B-it.litertlm")!!.contains("letöltés"))
    }

    @Test fun unknownIsRejected() {
        val f = GemmaModel.detectFormat(bytes(1, 2, 3, 4, 5, 6, 7, 8), "valami.bin")
        assertEquals(GemmaModel.Format.UNKNOWN, f)
        assertNotNull(GemmaModel.rejectionMessage(f, "valami.bin"))
    }

    // ---------- P4: blokkolás és tokenbecslés ----------

    @Test fun splitBlocksRespectsLimitAndKeepsAllText() {
        val sentence = "Ez egy tesztmondat a blokkolás ellenőrzéséhez."
        val text = List(200) { sentence }.joinToString(" ")
        val blocks = OfflinePipeline.splitBlocks(text, 1500)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.length <= 1500 })
        assertEquals(text.replace(" ", ""), blocks.joinToString("").replace(" ", ""))
    }

    @Test fun splitBlocksCutsOverlongSentence() {
        val longSentence = "a".repeat(5000)
        val blocks = OfflinePipeline.splitBlocks(longSentence, 2000)
        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it.length <= 2000 })
    }

    @Test fun tokenEstimateIsAboutFourCharsPerToken() {
        assertEquals(250, GemmaSession.estimateTokens("x".repeat(1000)))
        assertEquals(4000, GemmaSession.MAX_TOKENS)
        assertTrue(GemmaSession.estimateTokens("x".repeat(16000)) + 500 > GemmaSession.MAX_TOKENS)
    }

    // ---------- P3: tényblokk és sablonváz ----------

    private val today = LocalDate.of(2026, 10, 14)
    private val project = ProjectEntity(id = "p1", code = "PRJ-01", name = "Teszt projekt", ragStatus = "amber",
        budget = "10 M Ft", spent = "4 M Ft")
    private val events = listOf(
        ProjectEventEntity(projectId = "p1", date = "2026-10-01", type = "decision", title = "Új beszállító kiválasztása"),
        ProjectEventEntity(projectId = "p1", date = "2026-10-05", type = "risk", title = "Csúszik a licencbeszerzés", impact = "high"),
        ProjectEventEntity(projectId = "p1", date = "2026-10-06", type = "risk", title = "Lezárt kockázat", status = "resolved")
    )
    private val tasks = listOf(
        GanttTaskEntity(projectId = "p1", title = "Lejárt feladat", dueDate = "2026-10-10", assignee = "Kovács Anna"),
        GanttTaskEntity(projectId = "p1", title = "Kész feladat", dueDate = "2026-10-01", progress = 100, status = "completed"),
        GanttTaskEntity(projectId = "p1", title = "Jövő heti feladat", dueDate = "2026-10-20", progress = 30, status = "in_progress"),
        GanttTaskEntity(projectId = "p1", title = "Távoli feladat", dueDate = "2026-12-31")
    )

    @Test fun overdueAndDueSoon() {
        assertEquals(listOf("Lejárt feladat"), ReportBuilder.overdueTasks(tasks, today).map { it.title })
        assertEquals(listOf("Jövő heti feladat"), ReportBuilder.dueSoonTasks(tasks, today, 14).map { it.title })
    }

    @Test fun factBlockContainsFactsAndKeepsRag() {
        val f = ReportBuilder.factBlock(ReportBuilder.STATUS, project, events, tasks, today)
        assertTrue(f.contains("PRJ-01"))
        assertTrue(f.contains("SÁRGA"))
        assertTrue(f.contains("lejárt feladat: 1"))
        assertTrue(f.contains("nyitott kockázat: 1"))
        assertTrue(f.contains("4 M Ft / 10 M Ft"))
    }

    @Test fun shortenedFactBlockKeepsOnlyOverdueAndSoon() {
        val f = ReportBuilder.factBlock(ReportBuilder.EXECUTIVE, project, events, tasks, today, shortenTasks = true)
        assertTrue(f.contains("Lejárt feladat"))
        assertTrue(f.contains("Jövő heti feladat"))
        assertFalse(f.contains("Távoli feladat"))
    }

    @Test fun templateNeverContainsPromptText() {
        for (type in listOf(ReportBuilder.EXECUTIVE, ReportBuilder.STATUS, ReportBuilder.QA)) {
            val t = ReportBuilder.templateDraft(type, project, events, tasks, today)
            assertFalse(type, t.contains("Készíts"))
            assertFalse(type, t.contains("Végezz"))
            assertFalse(type, t.contains("Kizárólag a megadott"))
            assertTrue(type, t.contains("Offline vázlat"))
            assertTrue(type, t.contains("SÁRGA"))
        }
    }

    @Test fun executiveTemplateListsDecisionRiskAndOverdue() {
        val t = ReportBuilder.templateDraft(ReportBuilder.EXECUTIVE, project, events, tasks, today)
        assertTrue(t.contains("Új beszállító kiválasztása"))
        assertTrue(t.contains("Csúszik a licencbeszerzés"))
        assertFalse(t.contains("Lezárt kockázat"))
        assertTrue(t.contains("Lejárt feladat – Kovács Anna"))
        assertTrue(t.contains("Következő határidő:** Jövő heti feladat – 2026-10-20"))
    }

    @Test fun promptContainsRulesAndFacts() {
        val facts = ReportBuilder.factBlock(ReportBuilder.EXECUTIVE, project, events, tasks, today)
        val p = ReportBuilder.prompt(ReportBuilder.EXECUTIVE, facts)
        assertTrue(p.contains("Kizárólag a megadott tényeket"))
        assertTrue(p.contains("PRJ-01"))
    }
}

/** 1.1.1: a stílusos összefoglalók ténylegesen különböznek, és a szerkezet szavai nem „hallucinációk”. */
class SummaryStylesTest {

    @Test fun everyStyleHasItsOwnStructure() {
        val all = SummaryStyles.Style.values().map { SummaryStyles.instruction(it) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(SummaryStyles.instruction(SummaryStyles.Style.EXECUTIVE).contains("**Lényeg:**"))
        assertTrue(SummaryStyles.instruction(SummaryStyles.Style.MINUTES).contains("**Határozatok:**"))
        assertTrue(SummaryStyles.instruction(SummaryStyles.Style.ACTIONS).contains("| Feladat | Felelős | Határidő |"))
        assertTrue(SummaryStyles.instruction(SummaryStyles.Style.DETAILED).contains("**Kulcsfogalmak:**"))
    }

    @Test fun labelsMapToStyles() {
        assertEquals(SummaryStyles.Style.EXECUTIVE, SummaryStyles.fromLabel("Vezetői"))
        assertEquals(SummaryStyles.Style.MINUTES, SummaryStyles.fromLabel("Jegyzőkönyv"))
        assertEquals(SummaryStyles.Style.ACTIONS, SummaryStyles.fromLabel("Teendők"))
        assertEquals(SummaryStyles.Style.DETAILED, SummaryStyles.fromLabel("Részletes jegyzet"))
    }

    @Test fun templateWordsDoNotTriggerHallucinationFilter() {
        val source = "Kovács Anna vállalta a költségvetés elkészítését péntekig. A tesztelést Péter végzi a jövő héten."
        val minutes = "**Tárgy:** költségvetés\n**Résztvevők:** nem rögzített\n**Napirendi pontok:** 1. költségvetés\n" +
            "**Határozatok:** nem született határozat\n**Felelősök és határidők:** költségvetés – Kovács Anna – péntek"
        val instr = SummaryStyles.instruction(SummaryStyles.Style.MINUTES)
        assertFalse(OfflinePipeline.verifySourceCoherence(minutes, source))
        assertTrue(OfflinePipeline.verifySourceCoherence(minutes, source, allowedText = instr))
    }

    @Test fun cleanLoopsKeepsMarkdownStructure() {
        val table = "| # | Feladat | Felelős | Határidő |\n|---|---|---|---|\n" +
            "| 1 | Költségvetés elkészítése | Kovács Anna | péntek |\n| 2 | Tesztelés | Péter | jövő hét |"
        assertEquals(table, OfflinePipeline.cleanLoops(table))
    }

    @Test fun cleanLoopsCutsRealLoop() {
        val looped = "**Lényeg:** a költségvetés elkészül.\n" +
            List(5) { "A költségvetést Kovács Anna készíti el péntekig." }.joinToString("\n")
        val out = OfflinePipeline.cleanLoops(looped)
        assertTrue(out.startsWith("**Lényeg:**"))
        assertEquals(1, Regex("Kovács Anna").findAll(out).count())
        assertTrue(out.contains("\n"))
    }

    // ---------- 1.1.2: enyhébb, de név/szám-érzékeny egyezésvizsgálat ----------

    private val auditSource = "Ma az ISO 27001 auditról beszéltünk. Kovács Anna elkészíti a kockázatelemzést " +
        "október 20-ig. Péter frissíti a jelszókezelési szabályzatot. A tesztelés a jövő héten indul."

    @Test fun paraphrasedSummaryIsKeptWithoutWarning() {
        val summary = "**Lényeg:** az ISO 27001 audit előkészítése.\n**Döntések:** Kovács Anna kockázatelemzést készít " +
            "október 20-ig, Péter frissíti a jelszókezelési szabályzatot."
        val c = OfflinePipeline.checkCoherence(summary, auditSource, SummaryStyles.instruction(SummaryStyles.Style.EXECUTIVE))
        assertEquals(CoherenceVerdict.OK, c.verdict)
        assertNull(c.warning())
    }

    @Test fun moderateParaphraseIsKeptWithWarning() {
        val summary = "A résztvevők áttekintették a megfelelőségi felkészülést, a dokumentáció hiányosságait, " +
            "a munkamegosztást és az ütemezést; a kockázatelemzés Kovács Anna feladata."
        val c = OfflinePipeline.checkCoherence(summary, auditSource)
        assertTrue(c.ratio >= OfflinePipeline.REJECT_RATIO)
        assertEquals(CoherenceVerdict.WARN, c.verdict)
        assertTrue(c.warning()!!.contains("%"))
    }

    @Test fun inventedNameAndNumberAreFlagged() {
        val summary = "Kovács Anna és Szabó Gergely elkészíti a kockázatelemzést október 20-ig, a keret 500 ezer forint."
        val c = OfflinePipeline.checkCoherence(summary, auditSource)
        assertTrue(c.unknownNames.contains("Szabó"))
        assertTrue(c.unknownNumbers.contains("500"))
        assertFalse(c.unknownNumbers.contains("20"))
        assertEquals(CoherenceVerdict.WARN, c.verdict)
    }

    @Test fun listAndTableNumbersAreNotFlagged() {
        val summary = "| # | Feladat | Felelős | Határidő |\n|---|---|---|---|\n| 1 | Kockázatelemzés | Kovács Anna | október 20 |\n" +
            "| 2 | Szabályzat frissítése | Péter | nincs megadva |"
        val c = OfflinePipeline.checkCoherence(summary, auditSource, SummaryStyles.instruction(SummaryStyles.Style.ACTIONS))
        assertTrue(c.unknownNumbers.isEmpty())
        assertTrue(c.unknownNames.isEmpty())
    }

    @Test fun unrelatedTextIsRejected() {
        val summary = "A focicsapat vasárnap bajnokságot nyert, a szurkolók ünnepeltek a stadionban."
        assertEquals(CoherenceVerdict.REJECT, OfflinePipeline.checkCoherence(summary, auditSource).verdict)
    }
}

/** 1.1.3: a „Mód” megszűnt – a motort az alap elemző, a zár és a hálózat dönti el. */
class EngineRoutingTest {
    private fun d(preferGemini: Boolean, lock: Boolean = false, key: Boolean = true, gemma: Boolean = true, net: Boolean = true) =
        EngineRouting.decide(preferGemini, lock, key, gemma, net)

    @Test fun gemmaDefaultStaysLocalEvenWithNetwork() =
        assertEquals(EngineRouting.Route.GEMMA, d(preferGemini = false))

    @Test fun geminiDefaultUsesCloudWithNetwork() =
        assertEquals(EngineRouting.Route.GEMINI, d(preferGemini = true))

    @Test fun geminiDefaultFallsBackToGemmaOffline() =
        assertEquals(EngineRouting.Route.GEMMA, d(preferGemini = true, net = false))

    @Test fun privacyLockNeverUsesGemini() {
        assertEquals(EngineRouting.Route.GEMMA, d(preferGemini = true, lock = true))
        assertEquals(EngineRouting.Route.EXTRACTIVE, d(preferGemini = false, lock = true, gemma = false))
    }

    @Test fun missingGemmaFallsBackToGeminiThenExtract() {
        assertEquals(EngineRouting.Route.GEMINI, d(preferGemini = false, gemma = false))
        assertEquals(EngineRouting.Route.EXTRACTIVE, d(preferGemini = false, gemma = false, net = false))
    }

    @Test fun noApiKeyMeansLocal() =
        assertEquals(EngineRouting.Route.GEMMA, d(preferGemini = true, key = false))

    @Test fun describeMentionsLock() =
        assertTrue(EngineRouting.describe(true, true, true, true).contains("adatvédelmi zár"))
}

/** 1.1.4: modellazonosítás, letöltési linkek, Whisper-szótár. */
class ModelInfoTest {
    private fun header(vocab: Int, audioState: Int, textLayer: Int, mels: Int, ftype: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(ModelInfo.WHISPER_HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x67676d6c)
        listOf(vocab, 1500, audioState, 20, 32, 448, audioState, 20, textLayer, mels, ftype).forEach { b.putInt(it) }
        return b.array()
    }

    @Test fun detectsLargeV3TurboQ5() {
        val w = ModelInfo.parseWhisperHeader(header(51866, 1280, 4, 128, 1008))!!
        assertEquals("large-v3-turbo", w.size)
        assertEquals("q5_0", w.quant)
        assertFalse(w.englishOnly)
    }

    @Test fun detectsLargeV3AndQ8() {
        val w = ModelInfo.parseWhisperHeader(header(51866, 1280, 32, 128, 7))!!
        assertEquals("large-v3", w.size)
        assertEquals("q8_0", w.quant)
    }

    @Test fun flagsEnglishOnlyModel() =
        assertTrue(ModelInfo.parseWhisperHeader(header(51864, 512, 6, 80, 1))!!.englishOnly)

    @Test fun rejectsNonGgml() =
        assertNull(ModelInfo.parseWhisperHeader(ByteArray(ModelInfo.WHISPER_HEADER_BYTES)))

    @Test fun gemmaVariantFromNameOrSize() {
        assertEquals("Gemma 4 E4B", ModelInfo.gemmaVariant("gemma-4-E4B-it.litertlm", 0))
        assertEquals("Gemma 4 E2B", ModelInfo.gemmaVariant("gemma-4-E2B-it.litertlm", 0))
        assertTrue(ModelInfo.gemmaVariant("Gemma (.litertlm)", 3_660_000_000L).startsWith("Gemma 4 E4B"))
        assertTrue(ModelInfo.gemmaVariant("", 2_588_147_712L).startsWith("Gemma 4 E2B"))
    }

    @Test fun downloadsAreDirectHttpsLinks() {
        assertEquals(4, ModelInfo.DOWNLOADS.size)
        assertTrue(ModelInfo.DOWNLOADS.all { it.url.startsWith("https://huggingface.co/") && "/resolve/main/" in it.url })
        assertTrue(ModelInfo.DOWNLOADS.any { it.url.contains("gemma-4-E4B-it.litertlm") })
        assertTrue(ModelInfo.DOWNLOADS.any { it.url.contains("ggml-large-v3-turbo-q5_0.bin") })
    }

    @Test fun whisperPromptBuildsAndCaps() {
        assertEquals("", WhisperPrompt.build(""))
        val p = WhisperPrompt.build("Kovács Anna, ISO 27001; Kovács Anna\nkockázatelemzés", "IT audit")
        assertEquals("Magyar nyelvű megbeszélés. Téma: IT audit. Nevek és szakszavak: Kovács Anna, ISO 27001, kockázatelemzés.", p)
        assertTrue(WhisperPrompt.build(List(200) { "szó$it" }.joinToString(",")).length <= WhisperPrompt.MAX_CHARS)
    }
}

/** 1.1.5: havi összefoglaló tényblokkja és vázlata. */
class MonthlyReportTest {
    private val p = ProjectEntity(id = "p1", code = "PRJ-01", name = "Audit", ragStatus = "green")
    private val month = java.time.YearMonth.of(2026, 10)
    private val events = listOf(
        ProjectEventEntity(projectId = "p1", date = "2026-10-05", type = "decision", title = "Külső auditor megbízása"),
        ProjectEventEntity(projectId = "p1", date = "2026-09-20", type = "decision", title = "Szeptemberi döntés")
    )
    private val tasks = listOf(
        GanttTaskEntity(projectId = "p1", title = "Kockázatelemzés", dueDate = "2026-10-20", assignee = "Kovács Anna"),
        GanttTaskEntity(projectId = "p1", title = "Régi lejárt", dueDate = "2026-09-01"),
        GanttTaskEntity(projectId = "p1", title = "Decemberi", dueDate = "2026-12-15")
    )
    private val meetings = listOf(
        ReportBuilder.MeetingDigest("2026-10-03", "Nyitó megbeszélés", "Az audit ütemezése és a felelősök kijelölése."),
        ReportBuilder.MeetingDigest("2026-10-14", "ISO 27001 audit", "A kockázatelemzést Kovács Anna készíti el.")
    )
    private val saved = listOf(ReportBuilder.SavedDigest("2026-10-14", "Vezetői", "**Lényeg:** az audit halad."))

    @Test fun factBlockContainsOnlyThisMonth() {
        val f = ReportBuilder.monthlyFactBlock(p, month, meetings, saved, events, tasks)
        assertTrue(f.contains("Időszak: 2026-10"))
        assertTrue(f.contains("Nyitó megbeszélés"))
        assertTrue(f.contains("Vezetői"))
        assertTrue(f.contains("Külső auditor megbízása"))
        assertFalse(f.contains("Szeptemberi döntés"))
        assertTrue(f.contains("Kockázatelemzés"))
        assertTrue(f.contains("Régi lejárt"))      // korábbról áthúzódó, nyitott
        assertFalse(f.contains("Decemberi"))
    }

    @Test fun factBlockRespectsBudget() {
        val many = List(40) { ReportBuilder.MeetingDigest("2026-10-%02d".format(it % 28 + 1), "Megbeszélés $it", "szöveg ".repeat(400)) }
        val f = ReportBuilder.monthlyFactBlock(p, month, many, saved, events, tasks, maxChars = 11_000)
        assertTrue(f.length <= 11_000)
        assertTrue(f.contains("Megbeszélés 39"))   // mindegyik megbeszélés szerepel, rövidítve
    }

    @Test fun templateListsMeetingsAndDecisions() {
        val t = ReportBuilder.monthlyTemplate(p, month, meetings, events, tasks)
        assertTrue(t.contains("havi összefoglaló: 2026-10"))
        assertTrue(t.contains("2026-10-03 – Nyitó megbeszélés"))
        assertTrue(t.contains("Külső auditor megbízása"))
        assertFalse(t.contains("Szeptemberi döntés"))
        assertTrue(t.contains("Offline vázlat"))
    }

    @Test fun monthlyPromptHasStructure() =
        assertTrue(ReportBuilder.prompt(ReportBuilder.MONTHLY, "x").contains("Teendők a következő hónapra"))
}

/** 1.1.6: Gemini-modellek szűrése, rendezése, választólista. */
class GeminiModelsTest {
    private fun e(id: String, vararg m: String) = GeminiModels.Entry(id, methods = m.toList())

    @Test fun filtersToNoteCapableModels() {
        val list = GeminiModels.usable(listOf(
            e("gemini-3.8-flash", "generateContent"),
            e("gemini-3.5-flash-lite", "generateContent", "countTokens"),
            e("gemini-3.8-flash-tts", "generateContent"),
            e("gemini-3.1-flash-image", "generateContent"),
            e("gemini-embedding-2", "embedContent"),
            e("gemini-3.8-live", "bidiGenerateContent"),
            e("gemma-4-31b-it", "generateContent"),
            e("gemini-3.1-pro-preview", "generateContent")
        ))
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview"), list)
    }

    @Test fun sortsNewestFirstLatestLast() {
        val sorted = GeminiModels.sort(listOf("gemini-flash-latest", "gemini-3.1-flash-lite", "gemini-3.6-flash", "gemini-4-argon"))
        assertEquals("gemini-4-argon", sorted.first())
        assertEquals("gemini-flash-latest", sorted.last())
        assertTrue(sorted.indexOf("gemini-3.6-flash") < sorted.indexOf("gemini-3.1-flash-lite"))
    }

    @Test fun pickerKeepsSelectedAndFallsBack() {
        assertTrue(GeminiModels.forPicker(emptyList(), "gemini-3.1-flash-lite").containsAll(GeminiModels.FALLBACK))
        assertTrue("gemini-3.7-flash" in GeminiModels.forPicker(listOf("gemini-3.8-flash"), "gemini-3.7-flash"))
        assertFalse("gemini-3.7-flash" in GeminiModels.FALLBACK)
        assertFalse("gemini-3.5-flash" in GeminiModels.FALLBACK)
    }

    @Test fun encodeDecodeRoundTrip() =
        assertEquals(listOf("a", "b"), GeminiModels.decode(GeminiModels.encode(listOf("a", "b"))))
}
