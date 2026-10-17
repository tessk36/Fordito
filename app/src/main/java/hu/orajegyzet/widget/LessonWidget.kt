package hu.orajegyzet.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import hu.orajegyzet.MainActivity
import hu.orajegyzet.data.Db
import java.time.LocalDate
import java.time.LocalDateTime

class LessonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LessonWidget()
}

/**
 * Okosabb widget: aktuális/következő óra + mai jegyzetek száma + felvétel-indító
 * gomb. A gomb a MainActivity-t indítja ACTION_START_REC-kel (azonnali felvétel),
 * a kártya többi része megnyitja az appot.
 */
class LessonWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = LocalDateTime.now()
        val nowMin = now.hour * 60 + now.minute
        val today = LocalDate.now().toString()

        val lessons = Db.get(context).lessonDao().allOnce()
            .filter { it.dayOfWeek == now.dayOfWeek.value }
            .sortedBy { it.startMin }
        val current = lessons.firstOrNull { nowMin in it.startMin until it.endMin }
        val next = lessons.firstOrNull { it.startMin > nowMin }
        val target = current ?: next

        // mai jegyzetek száma (kész)
        val notesToday = Db.get(context).noteDao().doneOn(today).size

        provideContent {
            WidgetContent(
                title = target?.subject ?: "Nincs több óra ma",
                subtitle = target?.let {
                    val pre = if (current != null) "most" else "köv."
                    "%s · %d:%02d–%d:%02d".format(pre, it.startMin / 60, it.startMin % 60, it.endMin / 60, it.endMin % 60)
                } ?: "Koppints az indításhoz",
                notesToday = notesToday,
                lessonId = current?.id
            )
        }
    }

    @Composable
    private fun WidgetContent(title: String, subtitle: String, notesToday: Int, lessonId: Long?) {
        val ctx = LocalContext.current
        val openApp = actionStartActivity(Intent(ctx, MainActivity::class.java))
        val startRec = actionStartActivity(
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START_REC)
                .apply { if (lessonId != null) putExtra("lessonId", lessonId) }
        )

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFFF2C14E)))
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(openApp)
        ) {
            Text("AssiSten-s", style = TextStyle(fontSize = 11.sp,
                color = ColorProvider(Color(0xFF6E5A1E))))
            Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = ColorProvider(Color(0xFF26215C))))
            Text(subtitle, style = TextStyle(fontSize = 12.sp,
                color = ColorProvider(Color(0xFF4A3F12))))
            Spacer(GlanceModifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(Color(0xFF26215C)))
                        .cornerRadius(10.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable(startRec)
                ) {
                    Text("● Felvétel", style = TextStyle(fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, color = ColorProvider(Color(0xFFFFFFFF))))
                }
                Spacer(GlanceModifier.defaultWeight())
                Text("$notesToday jegyzet ma", style = TextStyle(fontSize = 12.sp,
                    color = ColorProvider(Color(0xFF4A3F12))))
            }
        }
    }
}
