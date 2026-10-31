package com.myphoneremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.security.SecureRandom

class MainActivity : Activity() {

    private lateinit var layout: LinearLayout

    private val auth = FirebaseAuth.getInstance()

    private val database by lazy {
        FirebaseDatabase.getInstance().reference
    }

    private var pairingCode: String? = null

    private var pairingListener: ValueEventListener? = null
    private var pairingRef: DatabaseReference? = null

    private var webRtcManager: WebRtcManager? = null

    private var videoRenderer: SurfaceViewRenderer? = null
    private var rendererEglContext: EglBase.Context? = null
    private var remoteVideoTrack: VideoTrack? = null

    private val SCREEN_CAPTURE_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pairingCode = getPreferences(MODE_PRIVATE)
            .getString("pairing_code", null)

        if (auth.currentUser != null) {
            showHome()
        } else {
            auth.signInAnonymously().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    showHome()
                } else {
                    createPage("CONNECTION ERROR")

                    addText(
                        task.exception?.localizedMessage
                            ?: "Firebase authentication failed"
                    )

                    addButton("RETRY") {
                        recreate()
                    }
                }
            }
        }
    }

    private fun createPage(title: String) {
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(30, 45, 30, 30)
            setBackgroundColor(Color.WHITE)
        }

        val heading = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 35)
        }

        layout.addView(heading)
        setContentView(layout)
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
                bottomMargin = 15
            }
        )
    }

    private fun savePairingCode(code: String) {
        pairingCode = code

        getPreferences(MODE_PRIVATE)
            .edit()
            .putString("pairing_code", code)
            .apply()
    }

    private fun showHome() {
        removePairingListener()
        stopViewer()

        createPage("MY PHONE REMOTE")

        addText("Choose your phone mode")

        addButton("PHONE 1 - CONTROLLER") {
            showController()
        }

        addButton("PHONE 2 - RECEIVER") {
            showReceiver()
        }
    }

    private fun showController() {
        removePairingListener()
        stopViewer()

        createPage("CONTROLLER DASHBOARD")

        addText("Phone 1 - OPPO Reno 14")

        addButton("CONNECT TO PHONE 2") {
            showControllerPairing()
        }

        addButton("LIVE SCREEN VIEWER") {
            startViewer()
        }

        addButton("CONNECTION STATUS") {
            showConnectionStatus()
        }

        addButton("BACK") {
            showHome()
        }
    }

    private fun showReceiver() {
        removePairingListener()
        stopViewer()

        createPage("RECEIVER DASHBOARD")

        addText("Phone 2 - Realme 6 Pro")

        addButton("PAIR WITH CONTROLLER") {
            generatePairingCode()
        }

        addButton("START SCREEN SHARING") {
            requestScreenCapture()
        }

        addButton("STOP SCREEN SHARING") {
            stopScreenShareService()
        }

        addButton("CONNECTION STATUS") {
            showConnectionStatus()
        }

        addButton("BACK") {
            showHome()
        }
    }

    private fun generatePairingCode() {
        removePairingListener()

        val uid = auth.currentUser?.uid ?: return

        val code = (
            100000 + SecureRandom().nextInt(900000)
        ).toString()

        val ref = database.child("pairing").child(code)

        val data = mapOf(
            "receiverUid" to uid,
            "controllerUid" to "",
            "status" to "waiting",
            "createdAt" to System.currentTimeMillis()
        )

        ref.setValue(data)
            .addOnSuccessListener {
                savePairingCode(code)

                createPage("PHONE 2 PAIRING")

                addText("Your pairing code:")
                addText(code)

                addText("Enter this code on Phone 1.")

                addButton("BACK TO HOME") {
                    showHome()
                }

                listenForPairing(ref)
            }
            .addOnFailureListener {
                showMessage(
                    it.localizedMessage ?: "Pairing failed"
                )
            }
    }

    private fun listenForPairing(ref: DatabaseReference) {
        removePairingListener()

        pairingRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status")
                    .getValue(String::class.java)

                if (status == "requested") {
                    removePairingListener()

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Pairing Request")
                        .setMessage(
                            "Allow Phone 1 to connect?"
                        )
                        .setPositiveButton("ALLOW") { _, _ ->
                            ref.child("status")
                                .setValue("connected")
                                .addOnSuccessListener {
                                    showConnected()
                                }
                                .addOnFailureListener {
                                    showMessage(
                                        it.localizedMessage ?: "Error"
                                    )
                                }
                        }
                        .setNegativeButton("DENY") { _, _ ->
                            ref.child("status")
                                .setValue("denied")
                        }
                        .setCancelable(false)
                        .show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                showMessage(error.message)
            }
        }

        pairingListener = listener
        ref.addValueEventListener(listener)
    }

    private fun showControllerPairing() {
        removePairingListener()

        createPage("CONNECT TO PHONE 2")

        addText("Enter the 6-digit code from Phone 2")

        val input = EditText(this).apply {
            hint = "6-digit pairing code"

            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER

            filters = arrayOf(
                android.text.InputFilter.LengthFilter(6)
            )
        }

        layout.addView(input)

        addButton("CONNECT") {
            val code = input.text.toString().trim()

            if (!code.matches(Regex("[0-9]{6}"))) {
                showMessage("Enter a valid 6-digit code")
            } else {
                requestPairing(code)
            }
        }

        addButton("BACK") {
            showController()
        }
    }

    private fun requestPairing(code: String) {
        val uid = auth.currentUser?.uid ?: return

        val ref = database.child("pairing").child(code)

        ref.get().addOnSuccessListener { snapshot ->
            val status = snapshot.child("status")
                .getValue(String::class.java)

            if (!snapshot.exists() || status != "waiting") {
                showMessage("Invalid or unavailable pairing code")
                return@addOnSuccessListener
            }

            ref.child("controllerUid")
                .setValue(uid)
                .addOnSuccessListener {
                    ref.child("status")
                        .setValue("requested")
                        .addOnSuccessListener {
                            savePairingCode(code)

                            createPage("WAITING FOR APPROVAL")

                            addText(
                                "Approve connection on Phone 2"
                            )

                            addButton("BACK") {
                                showController()
                            }

                            listenForControllerApproval(ref)
                        }
                        .addOnFailureListener {
                            showMessage(
                                it.localizedMessage ?: "Error"
                            )
                        }
                }
                .addOnFailureListener {
                    showMessage(
                        it.localizedMessage ?: "Error"
                    )
                }
        }.addOnFailureListener {
            showMessage(it.localizedMessage ?: "Error")
        }
    }

    private fun listenForControllerApproval(
        ref: DatabaseReference
    ) {
        removePairingListener()

        pairingRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status")
                    .getValue(String::class.java)

                when (status) {
                    "connected" -> {
                        removePairingListener()
                        showConnected()
                    }

                    "denied" -> {
                        removePairingListener()
                        showMessage("Pairing denied")
                        showController()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                showMessage(error.message)
            }
        }

        pairingListener = listener
        ref.addValueEventListener(listener)
    }

    private fun showConnected() {
        createPage("CONNECTED")

        addText("Phone pairing successful!")

        addButton("CONTROLLER DASHBOARD") {
            showController()
        }

        addButton("RECEIVER DASHBOARD") {
            showReceiver()
        }
    }

    private fun requestScreenCapture() {
        val code = pairingCode

        if (code.isNullOrBlank()) {
            showMessage("Pair with Phone 1 first")
            return
        }

        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        try {
            startActivityForResult(
                manager.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST
            )
        } catch (e: Exception) {
            showMessage(
                e.localizedMessage ?: "Capture request failed"
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode != SCREEN_CAPTURE_REQUEST) return

        if (resultCode != RESULT_OK || data == null) {
            showMessage("Screen capture permission denied")
            return
        }

        val code = pairingCode ?: return

        val serviceIntent = Intent(
            this,
            ScreenShareService::class.java
        ).apply {
            action = ScreenShareService.ACTION_START

            putExtra(
                ScreenShareService.EXTRA_RESULT_CODE,
                resultCode
            )

            putExtra(
                ScreenShareService.EXTRA_RESULT_DATA,
                data
            )

            putExtra(
                ScreenShareService.EXTRA_PAIRING_CODE,
                code
            )
        }

        try {
            startForegroundService(serviceIntent)

            createPage("SCREEN SHARING")

            addText(
                "Screen sharing service started.\n" +
                "Open LIVE SCREEN VIEWER on Phone 1."
            )

            addButton("STOP SCREEN SHARING") {
                stopScreenShareService()
            }

            addButton("BACK") {
                showReceiver()
            }

        } catch (e: Exception) {
            showMessage(
                e.localizedMessage ?: "Unable to start service"
            )
        }
    }

    private fun stopScreenShareService() {
        val intent = Intent(
            this,
            ScreenShareService::class.java
        ).apply {
            action = ScreenShareService.ACTION_STOP
        }

        startService(intent)

        showMessage("Screen sharing stopped")
        showReceiver()
    }

    private fun startViewer() {
        val code = pairingCode

        if (code.isNullOrBlank()) {
            showMessage("Pair with Phone 2 first")
            return
        }

        stopViewer()

        createPage("LIVE SCREEN VIEWER")

        val statusText = TextView(this).apply {
            text = "Connecting..."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        layout.addView(statusText)

        val renderer = SurfaceViewRenderer(this)

        videoRenderer = renderer

        layout.addView(
            renderer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        addButton("STOP VIEWER") {
            stopViewer()
            showController()
        }

        webRtcManager = WebRtcManager(
            context = applicationContext,
            code = code,
            controller = true,

            onVideo = { track, eglContext ->
                runOnUiThread {
                    if (videoRenderer !== renderer) {
                        return@runOnUiThread
                    }

                    if (rendererEglContext == null) {
                        renderer.init(eglContext, null)
                        rendererEglContext = eglContext
                    }

                    remoteVideoTrack?.removeSink(renderer)

                    remoteVideoTrack = track
                    track.addSink(renderer)

                    statusText.text = "LIVE SCREEN"
                }
            },

            onStatus = { status ->
                runOnUiThread {
                    if (videoRenderer === renderer) {
                        statusText.text = "Status: $status"
                    }
                }
            }
        )

        webRtcManager?.start()
    }

    private fun stopViewer() {
        val renderer = videoRenderer

        if (renderer != null) {
            remoteVideoTrack?.removeSink(renderer)
        }

        remoteVideoTrack = null

        webRtcManager?.close()
        webRtcManager = null

        renderer?.release()
        videoRenderer = null
        rendererEglContext = null
    }

    private fun showConnectionStatus() {
        createPage("CONNECTION STATUS")

        addText(
            "Firebase Authentication: " +
                if (auth.currentUser != null) {
                    "Active"
                } else {
                    "Inactive"
                }
        )

        addText(
            "Pairing code: " +
                (pairingCode ?: "Not paired")
        )

        addText(
            "Live connection status is shown in the viewer."
        )

        addButton("BACK TO HOME") {
            showHome()
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun removePairingListener() {
        val listener = pairingListener
        val ref = pairingRef

        if (listener != null && ref != null) {
            ref.removeEventListener(listener)
        }

        pairingListener = null
        pairingRef = null
    }

    override fun onDestroy() {
        removePairingListener()
        stopViewer()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showHome()
    }
}
