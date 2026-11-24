package com.suko.app.intake

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class IntakeUi(
    val busy: Boolean = false,
    val result: IntakeResult = IntakeResult(),
    val summary: IntakeSummary = IntakeSummaries.of(IntakeResult()),
    val error: String? = null,
)

/** Shared intake state for the developer test screen (and later the SVG Editor): everything added so far. */
class IntakeViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication<Application>()
    private val _ui = MutableStateFlow(IntakeUi())
    val ui: StateFlow<IntakeUi> = _ui
    private val root = File(ctx.cacheDir, "intake-test")
    private var runs = 0

    /** Kinds the calling tool uses. Set before adding files. */
    var wanted: Set<FileKind> = Intake.ALL_USABLE

    init { root.deleteRecursively() }

    fun addFiles(uris: List<Uri>) = add(AndroidNodes.files(ctx, uris))

    fun addFolder(tree: Uri) {
        val node = AndroidNodes.tree(ctx, tree)
        if (node == null) _ui.update { it.copy(error = "Could not open that folder.") } else add(listOf(node))
    }

    private fun add(nodes: List<FolderNode>) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = Intake.scan(nodes, File(root, "s${runs++}"), wanted)
                _ui.update { old ->
                    val all = old.result + r
                    old.copy(busy = false, result = all, summary = IntakeSummaries.of(all))
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = "Something went wrong: ${e.message}") }
            }
        }
    }

    fun clear() {
        root.deleteRecursively()
        _ui.value = IntakeUi()
    }
}
