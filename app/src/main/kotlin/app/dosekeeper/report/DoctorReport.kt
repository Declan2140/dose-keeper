package app.dosekeeper.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import app.dosekeeper.R
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.data.AppDatabase
import app.dosekeeper.data.DoseLog
import app.dosekeeper.data.MedStatus
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import app.dosekeeper.ui.describe
import app.dosekeeper.ui.supplyLine
import app.dosekeeper.ui.supplyOutlook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A printable PDF for a doctor, pharmacist or vet: one person's medicines, how many doses were
 * taken, skipped or missed, and every dose in the date range. Drawn with Android's own PdfDocument,
 * so it needs no library and no internet.
 */
class DoctorReport(private val context: Context, private val db: AppDatabase) {

    class Result(val pages: Int, val doses: Int)

    /** Writes the report for [profile] covering [from] to [to] inclusive. */
    suspend fun write(uri: Uri, profile: Profile, from: LocalDate, to: LocalDate): Result = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val allMeds = db.medicines().getAll().filter { it.profileId == profile.id }
        val medIds = allMeds.map { it.id }.toSet()
        val logs = db.doseLogs().between(start, end).filter { it.medicineId in medIds }
        val withLogs = logs.map { it.medicineId }.toSet()
        // Current medicines, plus archived ones that still have doses in the range.
        val meds = allMeds.filter { it.medStatus != MedStatus.ARCHIVED || it.id in withLogs }
            .sortedWith(compareBy<Medicine> { it.medStatus.ordinal }.thenBy { it.name.lowercase() })

        val doc = PdfDocument()
        val pages = Writer(context, doc).run {
            header(profile.name, from, to)
            medicines(meds)
            summary(meds, logs)
            doseLog(meds.associateBy { it.id }, logs, zone)
            finish()
        }
        try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        Result(pages, logs.size)
    }

    /** Lays text out top to bottom on US Letter pages, starting a new page when one fills up. */
    private class Writer(context: Context, private val doc: PdfDocument) {
        private val regular = ResourcesCompat.getFont(context, R.font.geist_regular) ?: Typeface.SANS_SERIF
        private val bold = ResourcesCompat.getFont(context, R.font.geist_semibold) ?: Typeface.DEFAULT_BOLD

        private fun paint(face: Typeface, size: Float, color: Int = INK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face; textSize = size; this.color = color
        }

        private val title = paint(bold, 22f)
        private val heading = paint(bold, 13f)
        private val body = paint(regular, 10f)
        private val bodyBold = paint(bold, 10f)
        private val small = paint(regular, 8.5f, MUTED)
        private val missed = paint(bold, 10f, ALERT)
        private val rule = Paint().apply { color = LINE; strokeWidth = 0.6f }
        private val accent = Paint().apply { color = BRAND; strokeWidth = 2f }

        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private lateinit var canvas: Canvas
        private var y = 0f

        private fun newPage() {
            page?.let { footer(); doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNo).create())
            page = p
            canvas = p.canvas
            y = MARGIN
        }

        /** Makes room for [height] points, moving to a new page if needed. */
        private fun need(height: Float) {
            if (page == null || y + height > HEIGHT - MARGIN - FOOTER) newPage()
        }

        private fun footer() {
            val text = "Recorded in Countertop as entered by the user. Not medical advice."
            canvas.drawText(text, MARGIN, HEIGHT - MARGIN + 6f, small)
            val pageText = "Page $pageNo"
            canvas.drawText(pageText, WIDTH - MARGIN - small.measureText(pageText), HEIGHT - MARGIN + 6f, small)
        }

        fun finish(): Int {
            if (page == null) newPage()
            footer()
            doc.finishPage(page!!)
            return pageNo
        }

        private fun line(text: String, p: Paint, x: Float = MARGIN, gap: Float = 4f) {
            for (part in wrap(text, p, WIDTH - MARGIN - x)) {
                need(p.textSize + gap)
                y += p.textSize
                canvas.drawText(part, x, y, p)
                y += gap
            }
        }

        private fun section(name: String) {
            need(heading.textSize + 40f)
            y += 18f
            line(name, heading, gap = 6f)
            canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, rule)
            y += 8f
        }

        fun header(name: String, from: LocalDate, to: LocalDate) {
            newPage()
            line("Medication report", title, gap = 8f)
            line(name, heading, gap = 4f)
            line("${DATE.format(from)} to ${DATE.format(to)}", body)
            line("Made ${DATE.format(LocalDate.now())} with Countertop", small)
            y += 6f
            canvas.drawLine(MARGIN, y, MARGIN + 48f, y, accent)
            y += 4f
        }

        fun medicines(meds: List<Medicine>) {
            section("Medicines")
            if (meds.isEmpty()) {
                line("No medicines recorded.", body)
                return
            }
            for (med in meds) {
                need(48f)
                y += 4f
                line(med.displayName, bodyBold, gap = 3f)
                val schedule = runCatching { med.parsedSchedule.describe() }.getOrDefault("")
                val status = when (med.medStatus) {
                    MedStatus.ACTIVE -> null
                    MedStatus.PAUSED -> "Paused"
                    MedStatus.ARCHIVED -> "Stopped"
                }
                line(listOfNotNull(med.medForm.label, schedule, status).joinToString(" · "), body, MARGIN + 10f, gap = 3f)
                if (med.note.isNotBlank()) line("Note: ${med.note}", body, MARGIN + 10f, gap = 3f)
                if (med.tracksSupply && med.medStatus == MedStatus.ACTIVE) {
                    med.supplyOutlook()?.let { line("Supply: " + supplyLine(it, med.medForm.unit, med.supplyLeft ?: 0.0), body, MARGIN + 10f, gap = 3f) }
                }
                y += 4f
            }
        }

        fun summary(meds: List<Medicine>, logs: List<DoseLog>) {
            section("How doses went")
            val byMed = logs.groupBy { it.medicineId }
            val rows = meds.filter { byMed.containsKey(it.id) }
            if (rows.isEmpty()) {
                line("No doses recorded in this period.", body)
                return
            }
            val cols = floatArrayOf(MARGIN, 300f, 370f, 440f, 510f)
            fun row(cells: List<String>, p: Paint, missedCol: Boolean = false) {
                need(p.textSize + 8f)
                y += p.textSize
                cells.forEachIndexed { i, text ->
                    val paint = if (i == 3 && missedCol) missed else p
                    val fitted = if (i == 0) ellipsize(text, paint, cols[1] - cols[0] - 8f) else text
                    canvas.drawText(fitted, cols[i], y, paint)
                }
                y += 5f
                canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, rule)
                y += 3f
            }
            row(listOf("Medicine", "Taken", "Skipped", "Missed", "Taken %"), bodyBold)
            for (med in rows) {
                val l = byMed[med.id].orEmpty()
                val taken = l.count { it.doseStatus == DoseStatus.TAKEN }
                val skipped = l.count { it.doseStatus == DoseStatus.SKIPPED }
                val miss = l.count { it.doseStatus == DoseStatus.MISSED }
                val scheduled = l.count { it.dueAt != null }
                val pct = if (scheduled == 0) "As needed" else "${(l.count { it.dueAt != null && it.doseStatus == DoseStatus.TAKEN } * 100.0 / scheduled).toInt()}%"
                row(listOf(med.displayName, "$taken", "$skipped", "$miss", pct), body, missedCol = miss > 0)
            }
        }

        fun doseLog(meds: Map<Long, Medicine>, logs: List<DoseLog>, zone: ZoneId) {
            section("Every dose")
            if (logs.isEmpty()) {
                line("Nothing recorded.", body)
                return
            }
            val byDay = logs.groupBy { Instant.ofEpochMilli(it.dueAt ?: it.actionAt).atZone(zone).toLocalDate() }.toSortedMap()
            val cols = floatArrayOf(MARGIN + 10f, 120f, 380f, 450f)
            for ((day, dayLogs) in byDay) {
                need(bodyBold.textSize * 3 + 16f)
                y += 6f
                line(DAY.format(day), bodyBold, gap = 4f)
                for (log in dayLogs.sortedBy { it.dueAt ?: it.actionAt }) {
                    need(body.textSize + 5f)
                    y += body.textSize
                    val due = log.dueAt?.let { TIME.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "As needed"
                    val name = meds[log.medicineId]?.displayName ?: "Deleted medicine"
                    val status = when (log.doseStatus) {
                        DoseStatus.TAKEN -> "Taken"
                        DoseStatus.SKIPPED -> "Skipped"
                        DoseStatus.MISSED -> "Missed"
                    }
                    val at = if (log.doseStatus == DoseStatus.MISSED) "" else "at " + TIME.format(Instant.ofEpochMilli(log.actionAt).atZone(zone))
                    canvas.drawText(due, cols[0], y, body)
                    canvas.drawText(ellipsize(name, body, cols[2] - cols[1] - 8f), cols[1], y, body)
                    canvas.drawText(status, cols[2], y, if (log.doseStatus == DoseStatus.MISSED) missed else body)
                    canvas.drawText(at, cols[3], y, body)
                    y += 5f
                }
            }
        }

        private fun wrap(text: String, p: Paint, width: Float): List<String> {
            val out = mutableListOf<String>()
            var current = ""
            for (word in text.split(' ')) {
                val next = if (current.isEmpty()) word else "$current $word"
                if (p.measureText(next) <= width || current.isEmpty()) current = next
                else { out += current; current = word }
            }
            if (current.isNotEmpty()) out += current
            return out.ifEmpty { listOf("") }
        }

        private fun ellipsize(text: String, p: Paint, width: Float): String {
            if (p.measureText(text) <= width) return text
            var t = text
            while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
            return "$t…"
        }
    }

    companion object {
        /** US Letter in points. */
        private const val WIDTH = 612
        private const val HEIGHT = 792
        private const val MARGIN = 48f
        private const val FOOTER = 18f

        private val INK = Color.rgb(0x11, 0x11, 0x11)
        private val MUTED = Color.rgb(0x6B, 0x72, 0x80)
        private val LINE = Color.rgb(0xDD, 0xDD, 0xDD)
        private val ALERT = Color.rgb(0xC4, 0x2B, 0x1C)
        private val BRAND = Color.rgb(0x1F, 0x6F, 0x5C)

        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
        private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        /** "countertop-report-me-30-days-2026-11-12.pdf", so reports for different periods don't overwrite each other. */
        fun fileName(profile: Profile, days: Long, to: LocalDate): String =
            "countertop-report-${profile.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "report" }}-$days-days-$to.pdf"
    }
}
