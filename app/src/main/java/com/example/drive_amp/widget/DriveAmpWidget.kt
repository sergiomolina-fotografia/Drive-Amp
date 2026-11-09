package com.example.drive_amp.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.drive_amp.MainActivity
import com.example.drive_amp.R
import com.example.drive_amp.data.PlaylistRepository
import com.example.drive_amp.service.DriveAmpMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Claves del estado que muestra el widget (pista actual y si está sonando). */
object WidgetKeys {
    val TITLE = stringPreferencesKey("title")
    val INFO = stringPreferencesKey("info")
    val PLAYING = booleanPreferencesKey("playing")
}

class DriveAmpWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = currentState<Preferences>()
            DriveAmpWidgetContent(
                title = state[WidgetKeys.TITLE] ?: "DRIVE-AMP READY",
                info = state[WidgetKeys.INFO] ?: "Toca ▶ para reproducir",
                playing = state[WidgetKeys.PLAYING] ?: false
            )
        }
    }
}

@Composable
fun DriveAmpWidgetContent(title: String, info: String, playing: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(resId = R.color.winamp_bg))
            .padding(6.dp)
    ) {
        // Cabecera estilo Winamp (toca para abrir la app)
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(resId = R.color.winamp_header))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = "DRIVE-AMP",
                style = TextStyle(
                    color = ColorProvider(resId = R.color.white),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.height(4.dp))

        // Pantalla LED: pista actual
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(resId = R.color.winamp_led))
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Text(
                text = (if (playing) "▶ " else "❚❚ ") + title,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(resId = R.color.winamp_green),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = info,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(resId = R.color.winamp_green),
                    fontSize = 10.sp
                )
            )
        }

        Spacer(modifier = GlanceModifier.height(4.dp))

        // Controles: retroceder, play/pausa, adelantar
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            WidgetButton("⏮", MediaControlAction.PREV, GlanceModifier.defaultWeight())
            Spacer(modifier = GlanceModifier.width(4.dp))
            WidgetButton(if (playing) "❚❚" else "▶", MediaControlAction.PLAY_PAUSE, GlanceModifier.defaultWeight(), highlight = true)
            Spacer(modifier = GlanceModifier.width(4.dp))
            WidgetButton("⏭", MediaControlAction.NEXT, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun WidgetButton(label: String, command: String, modifier: GlanceModifier, highlight: Boolean = false) {
    Box(
        modifier = modifier
            .height(40.dp)
            .background(ColorProvider(resId = if (highlight) R.color.winamp_button_active else R.color.winamp_button))
            .clickable(
                actionRunCallback<MediaControlAction>(
                    actionParametersOf(MediaControlAction.CMD to command)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(resId = R.color.white),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/** Acción de los botones del widget: manda el comando al reproductor (servicio de Media3). */
class MediaControlAction : ActionCallback {
    companion object {
        val CMD = ActionParameters.Key<String>("cmd")
        const val PLAY_PAUSE = "play_pause"
        const val NEXT = "next"
        const val PREV = "prev"
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val cmd = parameters[CMD] ?: return
        WidgetPlayerBridge.send(context.applicationContext, cmd)
    }
}

/** Conecta un MediaController temporal al servicio para ejecutar el comando del widget. */
object WidgetPlayerBridge {
    suspend fun send(context: Context, cmd: String) = withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, DriveAmpMediaService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        try {
            val controller = suspendCancellableCoroutine<MediaController> { cont ->
                future.addListener({
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }

            // Si la app estaba cerrada el reproductor está vacío: se carga la playlist guardada
            // y se retoma desde la última carpeta, pista y segundo donde había quedado
            if (controller.mediaItemCount == 0) {
                val resumption = PlaylistRepository.getPlaybackResumptionData(context)
                if (resumption.mediaItems.isEmpty()) {
                    WidgetUpdater.push(context, "PLAYLIST VACÍA", "Abre la app para agregar audios", false)
                    return@withContext
                }
                controller.setMediaItems(resumption.mediaItems, resumption.startIndex, resumption.startPositionMs)
            }
            if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                controller.prepare()
            }

            when (cmd) {
                MediaControlAction.PLAY_PAUSE ->
                    if (controller.playWhenReady && controller.playbackState != Player.STATE_ENDED) controller.pause()
                    else controller.play()
                MediaControlAction.NEXT -> {
                    if (controller.hasNextMediaItem()) {
                        controller.seekToNextMediaItem()
                        controller.play()
                    } else {
                        val nextList = PlaylistRepository.getNextSavedList(context)
                        if (nextList != null) {
                            val newTracks = PlaylistRepository.activateSavedListAsPlaylist(context, nextList)
                            controller.setMediaItems(PlaylistRepository.toMediaItems(newTracks), 0, 0L)
                            controller.prepare()
                            controller.play()
                        } else if (controller.mediaItemCount > 0) {
                            controller.seekTo(0, 0L)
                            controller.play()
                        }
                    }
                }
                MediaControlAction.PREV -> {
                    if (controller.hasPreviousMediaItem()) {
                        controller.seekToPreviousMediaItem()
                        controller.play()
                    } else if (controller.mediaItemCount > 0) {
                        controller.seekTo(0, 0L)
                        controller.play()
                    }
                }
            }
            // Margen para que los comandos lleguen al servicio antes de soltar la conexión
            delay(500)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            MediaController.releaseFuture(future)
        }
    }
}

/** Actualiza todos los widgets (en orden y descartando estados intermedios). */
object WidgetUpdater {
    private data class WidgetInfo(val context: Context, val title: String, val info: String, val playing: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val latest = MutableStateFlow<WidgetInfo?>(null)

    init {
        scope.launch {
            latest.filterNotNull().collect { apply(it) }
        }
    }

    fun push(context: Context, title: String, info: String, playing: Boolean) {
        latest.value = WidgetInfo(context.applicationContext, title, info, playing)
    }

    private suspend fun apply(w: WidgetInfo) {
        try {
            val ids = GlanceAppWidgetManager(w.context).getGlanceIds(DriveAmpWidget::class.java)
            ids.forEach { id ->
                updateAppWidgetState(w.context, id) { prefs ->
                    prefs[WidgetKeys.TITLE] = w.title
                    prefs[WidgetKeys.INFO] = w.info
                    prefs[WidgetKeys.PLAYING] = w.playing
                }
                DriveAmpWidget().update(w.context, id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class DriveAmpWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DriveAmpWidget()
}
