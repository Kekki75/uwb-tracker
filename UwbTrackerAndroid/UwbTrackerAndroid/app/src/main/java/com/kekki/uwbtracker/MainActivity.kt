package com.kekki.uwbtracker

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var trackingView: TrackingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI in code to keep this a single-file-friendly example.
        val root = FrameLayout(this)
        trackingView = TrackingView(this)
        root.addView(trackingView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val toggleBtn = Button(this).apply {
            text = "Hide panels"
            setOnClickListener {
                trackingView.showPanels = !trackingView.showPanels
                text = if (trackingView.showPanels) "Hide panels" else "Show panels"
            }
        }
        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            marginEnd = 24
            bottomMargin = 24
        }
        root.addView(toggleBtn, btnParams)

        setContentView(root)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
