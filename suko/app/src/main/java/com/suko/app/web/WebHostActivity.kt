package com.suko.app.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Steps 16-18: shows one page from the app's own assets (assets/web/...) in a WebView that can never reach the internet.
 * Extra EXTRA_PAGE = path under assets, e.g. "web/studio/smoke.html". Files for the page: set [WebInputs.files] first.
 *
 * Offline rules: the page is served from https://appassets.androidplatform.net/ by [shouldInterceptRequest] (the app answers
 * every request itself), every other address gets a 403 and is counted, the web view's own network loads are switched off,
 * and the app has no INTERNET permission.
 */
class WebHostActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var web: WebView
    private lateinit var bridge: SukoBridge
    private lateinit var core: BridgeCore
    private val blocked = AtomicInteger(0)
    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private var chooserSingle = false

    private val chooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val cb = chooserCallback
        chooserCallback = null
        cb?.onReceiveValue(if (uris.isEmpty()) null else (if (chooserSingle) uris.take(1) else uris).toTypedArray())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra(EXTRA_TITLE) ?: title
        val page = intent.getStringExtra(EXTRA_PAGE) ?: "web/studio/index.html"
        if (WebRules.route("${WebConfig.ORIGIN}/$page") !is Route.Asset) { finish(); return }

        core = BridgeCore(NativeMethods.defaults(PhoneEngine.methods(applicationContext)), File(cacheDir, "bridge"))
        core.cleanLeftovers()
        val inputs = InputRegistry(WebInputs.files)
        val saves = SaveRegistry { name, mime -> MediaStoreSaver.open(this, name, mime) }

        web = WebView(this)
        // An app screen, not a web page: the view is dark from the first frame (no white flash while the page loads) and has no overscroll glow.
        web.setBackgroundColor(0xFF0B0D12.toInt())
        web.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        bridge = SukoBridge(
            core, saves, inputs, { blocked.get() },
            { js -> runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) } },
            { where -> runOnUiThread { Toast.makeText(this, "Saved: $where", Toast.LENGTH_LONG).show() } },
            { runOnUiThread { if (web.canGoBack()) web.goBack() else finish() } },
        )
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false              // no file:// at all: everything comes through the interceptor
            allowContentAccess = true            // content:// pictures chosen in the file picker
            blockNetworkLoads = true             // belt and braces next to the interceptor
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            safeBrowsingEnabled = false          // Safe Browsing would phone home
        }
        web.addJavascriptInterface(bridge, "SukoBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.method != "GET") { blocked.incrementAndGet(); return denied() }
                return serve(request.url.toString(), inputs)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !WebRules.isOurs(request.url.toString())      // never navigate away from our own pages
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooserCallback?.onReceiveValue(null)
                chooserCallback = cb
                chooserSingle = params.mode != FileChooserParams.MODE_OPEN_MULTIPLE
                return try { chooser.launch(arrayOf("*/*")); true } catch (e: Exception) { chooserCallback = null; cb.onReceiveValue(null); false }
            }
            override fun onJsAlert(v: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@WebHostActivity).setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsConfirm(v: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@WebHostActivity).setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("SukoWeb", "${m.messageLevel()} ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        setContentView(web)
        web.loadUrl("${WebConfig.ORIGIN}/$page")
    }

    private fun denied(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private fun ok(mime: String, stream: java.io.InputStream): WebResourceResponse =
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime == "application/json") "utf-8" else null, 200, "OK",
            mapOf("Cache-Control" to "no-store"), stream)

    private fun serve(url: String, inputs: InputRegistry): WebResourceResponse =
        when (val r = WebRules.route(url)) {
            is Route.Asset -> try { ok(WebRules.mimeFor(r.path), assets.open(r.path)) } catch (e: java.io.IOException) { notFound() }
            is Route.Bridge -> core.take(r.id)?.let { ok("application/json", ByteArrayInputStream(it)) } ?: notFound()
            is Route.Input -> inputs.get(r.index)?.let { f -> try { ok(f.mime, f.open()) } catch (e: Exception) { notFound() } } ?: notFound()
            Route.Denied -> { blocked.incrementAndGet(); denied() }
        }

    override fun onDestroy() {
        chooserCallback?.onReceiveValue(null)
        chooserCallback = null
        if (::bridge.isInitialized) bridge.close()
        if (::web.isInitialized) { web.removeJavascriptInterface("SukoBridge"); web.destroy() }
        super.onDestroy()
    }
}
