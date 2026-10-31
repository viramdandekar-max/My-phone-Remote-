package com.myphoneremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.security.SecureRandom

class MainActivity : Activity() {

    private lateinit var layout: LinearLayout

    private val auth = FirebaseAuth.getInstance()

    private val database: DatabaseReference by lazy {
        FirebaseDatabase.getInstance().reference
    }

    private var pairingCode: String? = null
    private var pairingListener: ValueEventListener? = null
    private var pairingRef: DatabaseReference? = null

    private val SCREEN_CAPTURE_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth.signInAnonymously().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                showHome()
            } else {
                createPage("CONNECTION ERROR")
                addText(
                    "Firebase Authentication failed: " +
                    (task.exception?.localizedMessage ?: "Unknown error")
                )
                addButton("RETRY") {
                    recreate()
                }
            }
        }
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

    private fun addButton(label: String, action: () -> Unit) {
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
        removePairingListener()
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
        removePairingListener()
        createPage("CONTROLLER DASHBOARD")

        addText("Phone 1 - OPPO Reno 14")

        addButton("CONNECT TO PHONE 2") {
            showControllerPairing()
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
            showConnectionStatus()
        }

        addButton("BACK") {
            showHome()
        }
    }

    private fun showReceiver() {
        removePairingListener()
        createPage("RECEIVER DASHBOARD")

        addText("Phone 2 - Realme 6 Pro")

        addButton("PAIR WITH CONTROLLER") {
            generatePairingCode()
        }

        addButton("START SCREEN SHARING") {
            requestScreenCapture()
        }

        addButton("PERMISSIONS") {
            showFeatureInfo("Permissions")
        }

        addButton("CONNECTION STATUS") {
            showConnectionStatus()
        }

        addButton("BACK") {
            showHome()
        }
    }

    private fun requestScreenCapture() {
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
                "Screen capture request failed: ${e.localizedMessage}"
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                createPage("SCREEN CAPTURE APPROVED")

                addText(
                    "Screen capture permission granted.\n\n" +
                    "Screen streaming is not active yet."
                )

                addButton("BACK TO HOME") {
                    showHome()
                }
            } else {
                showMessage("Screen capture permission denied")
            }
        }
    }

    private fun generatePairingCode() {
        removePairingListener()

        val uid = auth.currentUser?.uid ?: return
        val code = (100000 + SecureRandom().nextInt(900000)).toString()

        pairingCode = code

        val ref = database.child("pairing").child(code)

        val data = mapOf(
            "receiverUid" to uid,
            "status" to "waiting",
            "controllerUid" to "",
            "createdAt" to System.currentTimeMillis()
        )

        ref.setValue(data).addOnSuccessListener {
            createPage("PHONE 2 PAIRING")

            addText("Your pairing code is:")
            addText(code)
            addText("Enter this code on Phone 1.")

            addButton("BACK TO HOME") {
                showHome()
            }

            listenForPairing(ref)
        }.addOnFailureListener {
            showMessage("Failed: ${it.localizedMessage}")
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
                            "Allow Phone 1 to connect to this phone?"
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
                            ref.child("status").setValue("denied")
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

        addText(
            "Enter the 6-digit code shown on Phone 2."
        )

        val input = EditText(this).apply {
            hint = "6-digit pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(
                android.text.InputFilter.LengthFilter(6)
            )
        }

        layout.addView(input)

        addButton("CONNECT") {
            val code = input.text.toString().trim()

            if (code.length != 6) {
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
                            createPage("WAITING FOR APPROVAL")

                            addText(
                                "Approve the request on Phone 2."
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
                    showMessage(it.localizedMessage ?: "Error")
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
                        showMessage(
                            "Pairing denied by Phone 2"
                        )
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
        addText("Both phones are paired.")

        addButton("BACK TO HOME") {
            showHome()
        }
    }

    private fun showConnectionStatus() {
        createPage("CONNECTION STATUS")

        addText(
            "Firebase Authentication: " +
            if (auth.currentUser != null) "Active" else "Inactive"
        )

        addText(
            "Pairing code: " +
            (pairingCode ?: "Not generated on this device")
        )

        addText(
            "This screen does not yet verify " +
            "an active remote session."
        )

        addButton("BACK") {
            showHome()
        }
    }

    private fun showFeatureInfo(feature: String) {
        createPage(feature.uppercase())

        addText(
            "$feature is not active yet. " +
            "Additional Android services and permissions are required."
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
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        showHome()
    }
}
