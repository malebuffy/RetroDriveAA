package com.codeodyssey.retrodriveaa.projection.auto

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class RetroDriveAADosMirrorPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var frameView: ImageView
    private lateinit var statusView: TextView
    private var latestBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawableResource(android.R.color.black)

        frameView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        statusView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            text = "Launching DOS on phone..."
        }

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            addView(frameView)
            addView(statusView)
        }

        setContentView(root)
    }

    fun updateFrame(bitmap: Bitmap?) {
        if (bitmap == null) {
            if (latestBitmap == null) {
                statusView.text = "Launching DOS on phone..."
                statusView.visibility = TextView.VISIBLE
            }
            return
        }

        val oldBitmap = latestBitmap
        latestBitmap = bitmap
        frameView.setImageBitmap(bitmap)
        statusView.visibility = TextView.GONE
        if (oldBitmap != null && oldBitmap !== bitmap && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }
    }

    fun setStatus(text: String) {
        statusView.text = text
        statusView.visibility = TextView.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        latestBitmap?.takeIf { !it.isRecycled }?.recycle()
        latestBitmap = null
    }
}