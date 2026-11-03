package com.auto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.AspectRatio
import com.auto.core.DesignTokens
import com.auto.core.FontCatalog
import com.auto.core.IntermediateFormat
import com.auto.core.MediaLibrary
import com.auto.core.ScriptTag
import com.auto.core.UpscaleEngine
import com.auto.core.UpscaleFactor
import com.auto.core.UpscaleProfile

/**
 * Phase 0, 5, 9 & Performance Directive: Complete Settings Screen.
 * Sections:
 * 1. Export Defaults (Resolution, Aspect Ratio, FPS, Captions)
 * 2. Performance & AI Upscaling (Profile: Manual/Fast/Balanced/Maximum, Scale, Format, Strict Mode, Verified Real-ESRGAN v3 model info)
 * 3. Storage & Cache (Free space via StatFs, Upscale Cache Size Limit, Clear Upscale Cache, Clear Temporary Files)
 * 4. Accessibility & Workspace Preferences (High-contrast borders, Safe-area guides, Remember workspace, Touch target >= 44 dp)
 * 5. Diagnostics (Last-run local 11-stage table, Upscale median/p90/slowest & decode/infer/encode split, Java/Native heap peaks, Share/Clear Log)
 * 6. Fonts & Open Source Licences (63 bundled font files inventory, Latin/Devanagari/Telugu/Malayalam status, SIL OFL v1.1 viewer)
 * 7. About (Manva / AutoAndroid 1.0, com.auto.app, offline-first guarantee)
 */
@Composable
fun SettingsScreen(st: AutoState, shell: Shell) {
    val ctx = LocalContext.current
    var upscaleCacheBytes by remember { mutableLongStateOf(st.upscaleCacheSizeBytes()) }
    var showOflDialog by remember { mutableStateOf(false) }
    val (freeBytes, totalBytes, _) = remember(upscaleCacheBytes) { st.checkDeviceStorage() }

    LaunchedEffect(Unit) {
        st.refreshDiagnostics()
        upscaleCacheBytes = st.upscaleCacheSizeBytes()
    }

    val card = RoundedCornerShape(DesignTokens.RADIUS_L.dp)
    Column(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Text("Settings", color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.TITLE.sp, modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))

        // 1. Export Defaults
        SettingsCard(card, "Export Defaults") {
            Text("Default resolution, aspect ratio, frame rate, and burned-in captions for exports.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row {
                OptionChip("720p", st.resP == 720) { st.resP = 720; st.persistExportSettings() }
                OptionChip("1080p", st.resP == 1080) { st.resP = 1080; st.persistExportSettings() }
                OptionChip("4K", st.resP == 2160) { st.resP = 2160; st.persistExportSettings() }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (a in AspectRatio.values()) {
                    OptionChip(a.label, st.aspect == a) { st.aspect = a; st.persistExportSettings() }
                }
            }
            Row {
                OptionChip("30 fps", st.fps == 30) { st.fps = 30; st.persistExportSettings() }
                OptionChip("60 fps", st.fps == 60) { st.fps = 60; st.persistExportSettings() }
            }
            ToggleRow("Burn captions into exported video by default", st.captionsOn) {
                st.captionsOn = !st.captionsOn
                st.persistExportSettings()
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2. Performance & AI Upscaling
        SettingsCard(card, "Performance & AI Image Upscaling") {
            val spec = UpscaleEngine.SHIPPED_MODEL_SPEC
            Text(
                "Engine: ${spec.id} (${spec.architecture}) · ONNX Runtime 1.17.1 (${MediaLibrary.humanSize(spec.fileSizeBytes)}, ${spec.license}).",
                color = C.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (prof in UpscaleProfile.values()) {
                    OptionChip(prof.label, st.upscaleProfile == prof) {
                        st.upscaleProfile = prof
                        st.persistExportSettings()
                    }
                }
            }
            Text(st.upscaleProfile.description, color = C.VioletLight, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            Row {
                for (u in UpscaleFactor.values()) {
                    OptionChip("Manual ${u.label}", st.upscale == u) { st.upscale = u; st.persistExportSettings() }
                }
            }
            Row {
                for (fmt in IntermediateFormat.values()) {
                    OptionChip(fmt.shortLabel, st.upscaleFormat == fmt) { st.upscaleFormat = fmt; st.persistExportSettings() }
                }
            }
            ToggleRow("Strict Upscale Mode (fail export instead of falling back to original image)", st.strictUpscaleMode) {
                st.strictUpscaleMode = !st.strictUpscaleMode
                st.persistExportSettings()
            }
        }

        Spacer(Modifier.height(12.dp))

        // 3. Storage & Cache
        SettingsCard(card, "Storage & Cache") {
            Text(
                "Free Device Storage: ${MediaLibrary.humanSize(freeBytes)} of ${MediaLibrary.humanSize(totalBytes)} · Persistent Upscale Cache: ${MediaLibrary.humanSize(upscaleCacheBytes)} (Cap: ${st.upscaleCacheLimitMb} MB)",
                color = C.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text("Upscale Cache Size Limit (LRU eviction)", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (mb in UpscaleEngine.CACHE_LIMIT_OPTIONS_MB) {
                    OptionChip("$mb MB", st.upscaleCacheLimitMb == mb) {
                        st.upscaleCacheLimitMb = mb
                        st.persistExportSettings()
                        SafelightExportProcessor.cacheInstance(ctx).enforceLruLimit(mb * 1024L * 1024L)
                        upscaleCacheBytes = st.upscaleCacheSizeBytes()
                    }
                }
            }
            Text(
                "Cache cleanup verifies 'ucache_' ownership and never deletes original user pictures, narration audio, or saved projects.",
                color = C.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            SettingsActionButton("Clear Upscale Cache (${MediaLibrary.humanSize(upscaleCacheBytes)})") {
                st.clearUpscaleCache { freed ->
                    upscaleCacheBytes = st.upscaleCacheSizeBytes()
                    shell.message("Upscale Cache Cleared", "Freed ${MediaLibrary.humanSize(freed)} from persistent AI upscale cache. Saved projects and original media were untouched.")
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsActionButton("Clear Temporary Scratch Files") {
                st.clearTemp { n ->
                    shell.message("Temporary Files Cleared", "Freed ${MediaLibrary.humanSize(n)} from temporary import/export scratch folders.")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 4. Accessibility & Workspace Preferences
        SettingsCard(card, "Accessibility & Workspace Preferences") {
            ToggleRow("High-contrast preview & card borders", st.highContrastBorders) {
                st.highContrastBorders = !st.highContrastBorders
                st.persistExportSettings()
            }
            ToggleRow("Show TV / Shorts safe-area guides on preview", st.showSafeAreaGuides) {
                st.showSafeAreaGuides = !st.showSafeAreaGuides
                st.persistExportSettings()
            }
            ToggleRow("Snap text overlays to center & rule-of-thirds guides", st.snappingEnabled) {
                st.snappingEnabled = !st.snappingEnabled
                st.persistExportSettings()
            }
            ToggleRow("Remember last selected editing workspace (${st.workspaceMode.shortLabel})", st.rememberWorkspace) {
                st.rememberWorkspace = !st.rememberWorkspace
                st.persistExportSettings()
            }
            Text("All interactive touch targets enforce a ≥ 44 dp minimum height and Midnight Violet WCAG contrast tokens.", color = C.Muted, fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))

        // 5. Diagnostics (Phase 0, Section 16 & Performance Directive Phase 1)
        SettingsCard(card, "Diagnostics & Performance Baseline") {
            Text(
                "High-resolution monotonic stage timers (`System.nanoTime()`) and heap peaks. Contains zero filenames, personal data, or network transmission.",
                color = C.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            val rep = st.latestDiagnostics
            if (rep == null) {
                Text("No diagnostic run recorded yet. Run Import, Analyse, or Export to populate stage metrics.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            } else {
                Text(
                    "Last Run: ${rep.resolutionP}p @ ${rep.fps} fps (${rep.aspect}) · ${rep.sceneCount} scenes · Total ${MediaLibrary.humanDuration(rep.totalExportMs)} · Status: ${rep.status}",
                    color = C.VioletLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                rep.upscaleStats?.let { us ->
                    Text(
                        "Upscale Summary: ${us.upscaledCount} upscaled, ${us.cacheHits} cached, ${us.skippedCount} skipped · Median ${us.medianMs} ms · p90 ${us.p90Ms} ms · Slowest #${us.slowestImageIndex} (${us.slowestMs} ms) · Decode ${us.totalDecodeMs} ms / Infer ${us.totalInferenceMs} ms / PNG Write ${us.totalEncodeWriteMs} ms",
                        color = C.Cyan,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                for (w in rep.warnings) {
                    Text("Warning: $w", color = C.Warning, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
                for (stg in rep.stages) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${stg.stage.label} (${stg.itemsDone}/${stg.itemsTotal})", color = C.Text, fontSize = 12.sp, modifier = Modifier.weight(1.2f))
                        Text("${stg.durationMs} ms · ${stg.formattedItemsPerMin()}", color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("J:${stg.javaHeapPeakMb}M N:${stg.nativeHeapPeakMb}M", color = C.Cyan, fontSize = 11.sp)
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    SettingsActionButton("Share Log (JSON)") {
                        if (!DiagnosticsLog.shareLog(ctx)) {
                            shell.message("Diagnostics", "No diagnostic log available to share yet.")
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    SettingsActionButton("Clear Log") {
                        DiagnosticsLog.clear(ctx)
                        st.refreshDiagnostics()
                        shell.message("Diagnostics Cleared", "Cleared local diagnostics history.")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 6. Fonts & Open Source Licences (Phase 2 & Section 11)
        SettingsCard(card, "Fonts, Indian Scripts & Licences") {
            val totalAvail = FontCatalog.allFaces.count { it.available }
            val devaCount = FontCatalog.byScript(ScriptTag.DEVANAGARI).count { it.available }
            Text(
                "$totalAvail font families bundled offline (63 font files, 13.39 MB uncompressed / ~6.8 MB in APK) under the SIL Open Font License v1.1.",
                color = C.Text,
                fontSize = 13.sp
            )
            Text(
                "• Latin: $totalAvail families · Devanagari: $devaCount families (Poppins, Baloo 2, Sans)\n" +
                    "• Telugu & Malayalam: System HarfBuzz shaping active; dedicated bundled font files marked Soon (not in FONTS.zip).",
                color = C.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            SettingsActionButton("View SIL Open Font License v1.1 Notice") { showOflDialog = true }
        }

        Spacer(Modifier.height(12.dp))

        // 7. About
        SettingsCard(card, "About") {
            Text("Manva (AutoAndroid) 1.0 · Package com.auto.app", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "Offline-first Android video studio: Protected Auto Create engine + 10 integrated editing workspaces + Manual Editor + Design Studio + Real-ESRGAN v3 (SRVGGNetCompact) ONNX AI Upscaling + Media3 1.3.1 MP4 Export.",
                color = C.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(28.dp))
    }

    if (showOflDialog) {
        val oflText = remember {
            try { ctx.assets.open("OFL.txt").bufferedReader().use { it.readText() } }
            catch (_: Exception) { "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007 (http://scripts.sil.org/OFL)" }
        }
        AlertDialog(
            onDismissRequest = { showOflDialog = false },
            containerColor = C.Card,
            titleContentColor = C.Text,
            textContentColor = C.Muted,
            title = { Text("SIL Open Font License v1.1") },
            text = {
                Column(Modifier.height(340.dp).verticalScroll(rememberScrollState())) {
                    Text(oflText, fontSize = 12.sp, color = C.Text)
                }
            },
            confirmButton = { TextButton(onClick = { showOflDialog = false }) { Text("Close", color = C.VioletLight) } }
        )
    }
}

@Composable
private fun SettingsCard(shape: RoundedCornerShape, title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.Card)
            .border(1.dp, C.Line, shape)
            .padding(16.dp)
    ) {
        Text(title, color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.SECTION.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun SettingsActionButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DesignTokens.MIN_TOUCH_DP.dp)
            .clip(shape)
            .background(C.Lane)
            .border(1.dp, C.Line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
