package com.myphoneremote

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var layout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun createPage(title: String) {
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(35, 55, 35, 35)
            setBackgroundColor(Color.WHITE)
        }

        val heading = TextView(this).apply {
            text = title
            textSize = 25f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }

        layout.addView(heading)
        setContentView(layout)
    }

    private fun addButton(
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            textSize = 16f
            setOnClickListener { action() }
        }

        layout.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        )
    }

    private fun addText(message: String) {
        val textView = TextView(this).apply {
            text = message
            textSize = 17f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 25)
        }

        layout.addView(textView)
    }

    private fun showHome() {
        createPage("MY PHONE REMOTE")

        addText("Select your phone mode")

        addButton("PHONE 1 - CONTROLLER") {
            showController()
        }

        addButton("PHONE 2 - RECEIVER") {
            showReceiver()
        }
    }

    private fun showController() {
        createPage("CONTROLLER DASHBOARD")

        addText("Phone 1 - OPPO Reno 14")

        addButton("CONNECT TO PHONE 2") {
            showPairing()
        }

        addButton("REMOTE CONTROL") {
            showFeatureInfo("Remote Control")
        }

        addButton("SCREEN SHARING") {
            showFeatureInfo("Screen Sharing")
        }

        addButton("FIND MY PHONE") {
            showFeatureInfo("Find My Phone")
        }

        addButton("CONNECTION STATUS") {
            showFeatureInfo("Connection Status")
        }

        addButton("BACK") {
            showHome()
        }
    }

    private fun showReceiver() {
        createPage("RECEIVER DASHBOARD")

        addText("Phone 2 - Realme 6 Pro")

        addButton("PAIR WITH CONTROLLER") {
            showPairing()
        }

        addButton("PERMISSIONS") {
            showFeatureInfo("Permissions")
        }

        addButton("CONNECTION STATUS") {
            showFeatureInfo("Connection Status")
        }

        addButton("BACK") {
            showHome()
        }
    }

    private fun showPairing() {
        createPage("PHONE PAIRING")

        addText(
            "Secure phone pairing will be added " +
            "in the next development stage."
        )

        addButton("BACK TO HOME") {
            showHome()
        }
    }

    private fun showFeatureInfo(feature: String) {
        createPage(feature.uppercase())

        addText(
            "$feature is not active yet. " +
            "This feature requires additional " +
            "Android services and permissions."
        )

        addButton("BACK TO HOME") {
            showHome()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showHome()
    }
}
