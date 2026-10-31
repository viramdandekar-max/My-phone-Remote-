package com.myphoneremote

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.view.Gravity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "My Phone Remote"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val controller = Button(this).apply {
            text = "Phone 1 - Controller"
            setOnClickListener {
                title.text = "Controller Mode Selected"
            }
        }

        val receiver = Button(this).apply {
            text = "Phone 2 - Receiver"
            setOnClickListener {
                title.text = "Receiver Mode Selected"
            }
        }

        layout.addView(title)
        layout.addView(controller)
        layout.addView(receiver)

        setContentView(layout)
    }
}
