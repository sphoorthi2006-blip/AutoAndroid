package com.suko.app.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Small front door to the upscale engine, used by the self-test now and by the Auto Editor / SVG Editor exports later.
 * Pipeline per picture (same as the old Sukko app): remove the corner mark, then AI 2x (GPU first, CPU if needed),
 * classic 2x if the AI step cannot run.
 */
object Upscaler {
    class Result(
        val img: ImageProcessor.Img,
        val method: String,        // "AI" or "classic"
        val engine: String,        // "GPU", "CPU 4 threads" or ""
        val note: String?,         // why AI was reduced/skipped, or null
        val markMs: Long,
        val scaleMs: Long,
    )

    fun libraryLoaded(): Boolean = NativeUpscaler.available()

    /** Decodes a picture into ARGB ints, or null if the file is not a readable picture. */
    fun decode(ctx: Context, uri: Uri): ImageProcessor.Img? {
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val img = ImageProcessor.Img(bmp.width, bmp.height, px)
        bmp.recycle()
        return img
    }

    fun run(
        ctx: Context,
        src: ImageProcessor.Img,
        removeMark: Boolean,
        onTile: ((done: Int, total: Int) -> Unit)? = null,
    ): Result {
        ImageProcessor.tuneForDevice(ctx)
        val out = ImageProcessor.process(
            src, removeMark = removeMark, cropStrip = false, aiScale = 2,
            assets = ctx.assets, useGpu = true, onTile = onTile,
        )
        val engine = if (ImageProcessor.lastMethod == "AI") NativeUpscaler.info() else ""
        return Result(out, ImageProcessor.lastMethod, engine, ImageProcessor.aiNote,
            ImageProcessor.lastMarkMs, ImageProcessor.lastScaleMs)
    }

    fun toBitmap(img: ImageProcessor.Img): Bitmap =
        Bitmap.createBitmap(img.px, img.w, img.h, Bitmap.Config.ARGB_8888)
}
