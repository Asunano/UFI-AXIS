package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String,
    mediaType: String // "video" or "audio"
) {
    val context = LocalContext.current
    val palette = LocalResolvedPalette.current
    val fileName = remember(filePath) { filePath.substringAfterLast("/") }
    val fileExt = remember(fileName) { fileName.substringAfterLast(".", "").lowercase() }
    val prefs = remember { AppPreferences(context) }

    val streamUrl = remember(filePath) {
        val encoded = URLEncoder.encode(filePath, "UTF-8")
        "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/stream?path=$encoded"
    }

    val exoPlayer = remember {
        // ExoPlayer runs entirely on the user's phone — decoding is local, not on the device
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${prefs.token}"))
        val mediaSourceFactory = DefaultMediaSourceFactory(context).apply {
            setDataSourceFactory(httpDataSourceFactory)
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    UfiScreenScaffold(
        title = fileName,
        navController = navController,
        showBack = true
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (mediaType == "video") {
                // ── Video Player ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // ── Audio Player ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    palette.accentContainer,
                                    palette.cardBg
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        // Album art placeholder
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(palette.accent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = palette.accent
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        Text(
                            fileName.substringBeforeLast("."),
                            style = UfiTextStyles.screenTitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.textPrimary
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            fileExt.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary
                        )

                        Spacer(Modifier.height(32.dp))

                        // Player controls
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                    showController()
                                    setShowNextButton(false)
                                    setShowPreviousButton(false)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }
            }

            // ── Bottom info bar ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = palette.cardBg,
                tonalElevation = 1.dp
            ) {
                Row(
                    Modifier.padding(horizontal = Spacing.PagePadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = UfiCardDefaults.chipShape,
                        color = palette.accentContainer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (mediaType == "video") Icons.Default.VideoFile else Icons.Default.AudioFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = palette.textPrimary
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            fileName,
                            style = UfiTextStyles.bodyEmphasis,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            filePath.substringBeforeLast("/"),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
