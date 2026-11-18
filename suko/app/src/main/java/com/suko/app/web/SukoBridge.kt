package com.suko.app.web

import android.webkit.JavascriptInterface
import java.util.concurrent.Executors

/**
 * Step 17: the object the page sees as window.SukoBridge (see web/src/bridge.ts for the other half).
 * Methods marked @JavascriptInterface run on a WebView background thread, never on the UI thread.
 * Long jobs are moved to [pool]; the page is told they are done with window.__sukoDone(id, ok, message).
 */
class SukoBridge(
    private val core: BridgeCore,
    private val saves: SaveRegistry,
    private val inputs: InputRegistry,
    private val blockedCount: () -> Int,
    /** Runs a piece of JavaScript in the page (the activity posts it to the UI thread). */
    private val runJs: (String) -> Unit,
    private val onSaved: (where: String) -> Unit,
) {
    private val pool = Executors.newSingleThreadExecutor()

    private fun done(id: String, ok: Boolean, message: String) =
        runJs("window.__sukoDone && window.__sukoDone(${WebRules.jsString(id)},$ok,${WebRules.jsString(message)})")

    @JavascriptInterface
    fun call(id: String, method: String, payload: String) {
        pool.execute {
            val r = core.run(id, method, payload)
            done(id, r.ok, r.message)
        }
    }

    @JavascriptInterface
    fun saveBegin(id: String, name: String, mime: String) {
        try { saves.begin(id, name, mime) } catch (e: Exception) { done(id, false, e.message ?: "could not start saving") }
    }

    @JavascriptInterface
    fun saveChunk(id: String, base64: String) {
        try { saves.chunk(id, base64) } catch (e: Exception) { done(id, false, e.message ?: "could not write the file") }
    }

    @JavascriptInterface
    fun saveEnd(id: String) {
        pool.execute {
            try {
                val where = saves.end(id)
                onSaved(where)
                done(id, true, where)
            } catch (e: Exception) {
                done(id, false, e.message ?: "could not finish the file")
            }
        }
    }

    @JavascriptInterface fun inputList(): String = inputs.listJson()

    @JavascriptInterface
    fun info(): String = """{"host":"suko","engine":"${WebConfig.ENGINE}","blocked":${blockedCount()}}"""

    fun close() {
        pool.shutdownNow()
        saves.abortAll()
    }
}
