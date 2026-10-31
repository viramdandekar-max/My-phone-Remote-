package com.myphoneremote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.webrtc.*

class WebRtcManager(
    private val context: Context,
    private val code: String,
    private val controller: Boolean,
    private val onVideo: (VideoTrack, EglBase.Context) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        @Volatile
        private var initialized = false

        private const val TAG = "WebRtcManager"

        // Add valid TURN server credentials here.
        private const val TURN_URL = ""
        private const val TURN_USERNAME = ""
        private const val TURN_PASSWORD = ""

        @Synchronized
        private fun initialize(context: Context) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }

    private val root = FirebaseDatabase.getInstance().reference
    private val pairing = root.child("pairing").child(code)
    private val signaling = root.child("webrtc").child(code)

    private var pairingListener: ValueEventListener? = null
    private var signalListener: ValueEventListener? = null

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSender: RtpSender? = null

    @Volatile
    private var authorized = false

    @Volatile
    private var closed = false

    private var remotePending = false
    private var remoteSet = false

    private val candidates = mutableListOf<IceCandidate>()
    private val seen = mutableSetOf<String>()

    fun start(capturePermission: Intent? = null) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        if (
            uid == null ||
            !code.matches(Regex("[0-9]{6}")) ||
            (!controller && capturePermission == null)
        ) {
            onStatus("PAIRING_OR_PERMISSION_REQUIRED")
            return
        }

        pairingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (closed) return

                val status = snapshot.child("status")
                    .getValue(String::class.java)

                val expected = snapshot.child(
                    if (controller) "controllerUid" else "receiverUid"
                ).getValue(String::class.java)

                if (status != "connected" || expected != uid) {
                    onStatus("NOT_AUTHORIZED")

                    if (authorized) close()
                    return
                }

                if (!authorized) {
                    authorized = true
                    setup(capturePermission)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onStatus("PAIRING_READ_ERROR: ${error.message}")
            }
        }

        pairing.addValueEventListener(pairingListener!!)
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder(
                "stun:stun.l.google.com:19302"
            ).createIceServer()
        )

        if (
            TURN_URL.isNotBlank() &&
            TURN_USERNAME.isNotBlank() &&
            TURN_PASSWORD.isNotBlank()
        ) {
            servers.add(
                PeerConnection.IceServer.builder(TURN_URL)
                    .setUsername(TURN_USERNAME)
                    .setPassword(TURN_PASSWORD)
                    .createIceServer()
            )
        } else {
            Log.w(TAG, "TURN server is not configured")
        }

        return servers
    }

    private fun setEncoding(bitrate: Int, fps: Int) {
        if (controller || closed) return

        val sender = videoSender ?: return

        try {
            val parameters = sender.parameters

            if (parameters.encodings.isEmpty()) return

            parameters.encodings.forEach { encoding ->
                encoding.maxBitrateBps = bitrate
                encoding.maxFramerate = fps
            }

            if (sender.setParameters(parameters)) {
                Log.i(TAG, "Video bitrate: $bitrate, FPS: $fps")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video encoding update failed", e)
        }
    }

    private fun adaptForConnection(
        state: PeerConnection.IceConnectionState?
    ) {
        if (controller || closed) return

        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                setEncoding(1_500_000, 30)
            }

            PeerConnection.IceConnectionState.DISCONNECTED -> {
                setEncoding(450_000, 15)
            }

            else -> Unit
        }
    }

    private fun setup(permission: Intent?) {
        try {
            initialize(context)

            egl = EglBase.create()

            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        egl!!.eglBaseContext,
                        true,
                        true
                    )
                )
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(
                        egl!!.eglBaseContext
                    )
                )
                .createPeerConnectionFactory()

            val config = PeerConnection.RTCConfiguration(
                iceServers()
            ).apply {
                tcpCandidatePolicy =
                    PeerConnection.TcpCandidatePolicy.ENABLED

                bundlePolicy =
                    PeerConnection.BundlePolicy.MAXBUNDLE

                rtcpMuxPolicy =
                    PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            peer = factory!!.createPeerConnection(
                config,
                object : PeerConnection.Observer {

                    override fun onSignalingChange(
                        state: PeerConnection.SignalingState?
                    ) {}

                    override fun onIceConnectionChange(
                        state: PeerConnection.IceConnectionState?
                    ) {
                        onStatus(state?.name ?: "UNKNOWN")
                        adaptForConnection(state)
                    }

                    override fun onIceConnectionReceivingChange(
                        receiving: Boolean
                    ) {}

                    override fun onIceGatheringChange(
                        state: PeerConnection.IceGatheringState?
                    ) {}

                    override fun onIceCandidate(
                        candidate: IceCandidate?
                    ) {
                        if (candidate != null && !closed) {
                            val side = if (controller) {
                                "controller"
                            } else {
                                "receiver"
                            }

                            signaling.child("candidates")
                                .child(side)
                                .push()
                                .setValue(
                                    mapOf(
                                        "sdp" to candidate.sdp,
                                        "mid" to (candidate.sdpMid ?: ""),
                                        "index" to candidate.sdpMLineIndex
                                    )
                                )
                                .addOnFailureListener {
                                    onStatus("ICE_WRITE_FAILED")
                                }
                        }
                    }

                    override fun onIceCandidatesRemoved(
                        candidates: Array<out IceCandidate>?
                    ) {}

                    override fun onAddStream(
                        stream: MediaStream?
                    ) {
                        stream?.videoTracks
                            ?.firstOrNull()
                            ?.let {
                                if (!closed) {
                                    onVideo(
                                        it,
                                        egl!!.eglBaseContext
                                    )
                                }
                            }
                    }

                    override fun onRemoveStream(
                        stream: MediaStream?
                    ) {}

                    override fun onDataChannel(
                        channel: DataChannel?
                    ) {}

                    override fun onRenegotiationNeeded() {}

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        streams: Array<out MediaStream>?
                    ) {
                        (receiver?.track() as? VideoTrack)?.let {
                            if (!closed) {
                                onVideo(
                                    it,
                                    egl!!.eglBaseContext
                                )
                            }
                        }
                    }
                }
            )

            if (peer == null) {
                onStatus("PEER_CREATION_FAILED")
                close()
                return
            }

            if (controller) {
                peer!!.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    )
                )
            } else {
                capturer = ScreenCapturerAndroid(
                    permission!!,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            onStatus("SCREEN_CAPTURE_STOPPED")
                            close()
                        }
                    }
                )

                source = factory!!.createVideoSource(true)

                helper = SurfaceTextureHelper.create(
                    "ScreenFrames",
                    egl!!.eglBaseContext
                )

                capturer!!.initialize(
                    helper,
                    context.applicationContext,
                    source!!.capturerObserver
                )

                track = factory!!.createVideoTrack(
                    "screen",
                    source
                )

                videoSender = peer!!.addTrack(
                    track,
                    listOf("screen_stream")
                )

                setEncoding(1_500_000, 30)

                capturer!!.startCapture(
                    720,
                    1280,
                    30
                )
            }

            listen()

            if (controller) makeOffer()

        } catch (e: Exception) {
            Log.e(TAG, "Setup error", e)
            onStatus("SETUP_ERROR: ${e.localizedMessage}")
            close()
        }
    }

    private fun makeOffer() {
        peer?.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(
                    sdp: SessionDescription?
                ) {
                    sdp?.let { local(it) }
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(error: String?) {
                    onStatus("OFFER_FAILED: $error")
                }

                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints()
        )
    }

    private fun makeAnswer() {
        peer?.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(
                    sdp: SessionDescription?
                ) {
                    sdp?.let { local(it) }
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(error: String?) {
                    onStatus("ANSWER_FAILED: $error")
                }

                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints()
        )
    }

    private fun local(sdp: SessionDescription) {
        peer?.setLocalDescription(
            object : SdpObserver {
                override fun onCreateSuccess(
                    sdp: SessionDescription?
                ) {}

                override fun onSetSuccess() {
                    if (!closed) {
                        signaling.child(
                            if (controller) "offer" else "answer"
                        ).setValue(
                            mapOf(
                                "type" to sdp.type.name,
                                "sdp" to sdp.description
                            )
                        ).addOnFailureListener {
                            onStatus("SDP_WRITE_FAILED")
                        }
                    }
                }

                override fun onCreateFailure(error: String?) {}

                override fun onSetFailure(error: String?) {
                    onStatus("LOCAL_SDP_FAILED: $error")
                }
            },
            sdp
        )
    }

    private fun remote(sdp: SessionDescription) {
        if (remotePending || remoteSet || closed) return

        remotePending = true

        peer?.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(
                    sdp: SessionDescription?
                ) {}

                override fun onSetSuccess() {
                    remotePending = false
                    remoteSet = true

                    candidates.forEach {
                        peer?.addIceCandidate(it)
                    }

                    candidates.clear()

                    if (!controller) makeAnswer()
                }

                override fun onCreateFailure(error: String?) {}

                override fun onSetFailure(error: String?) {
                    remotePending = false
                    onStatus("REMOTE_SDP_FAILED: $error")
                }
            },
            sdp
        )
    }

    private fun listen() {
        signalListener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                if (closed || !authorized) return

                val sdp = snapshot.child(
                    if (controller) "answer" else "offer"
                ).child("sdp")
                    .getValue(String::class.java)

                if (
                    sdp != null &&
                    !remoteSet &&
                    !remotePending
                ) {
                    remote(
                        SessionDescription(
                            if (controller) {
                                SessionDescription.Type.ANSWER
                            } else {
                                SessionDescription.Type.OFFER
                            },
                            sdp
                        )
                    )
                }

                val side = if (controller) {
                    "receiver"
                } else {
                    "controller"
                }

                snapshot.child("candidates")
                    .child(side)
                    .children
                    .forEach { item ->

                        val id = item.key ?: return@forEach

                        if (!seen.add(id)) return@forEach

                        val value = item.child("sdp")
                            .getValue(String::class.java)
                            ?: return@forEach

                        val mid = item.child("mid")
                            .getValue(String::class.java)

                        val index = item.child("index")
                            .getValue(Int::class.java) ?: 0

                        val candidate = IceCandidate(
                            mid,
                            index,
                            value
                        )

                        if (remoteSet) {
                            peer?.addIceCandidate(candidate)
                        } else {
                            candidates.add(candidate)
                        }
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                onStatus("SIGNALING_DENIED: ${error.message}")
            }
        }

        signaling.addValueEventListener(signalListener!!)
    }

    fun close() {
        if (closed) return

        closed = true
        authorized = false

        pairingListener?.let {
            pairing.removeEventListener(it)
        }

        signalListener?.let {
            signaling.removeEventListener(it)
        }

        pairingListener = null
        signalListener = null

        try {
            capturer?.stopCapture()
        } catch (_: Exception) {}

        capturer?.dispose()
        capturer = null

        peer?.close()
        peer?.dispose()
        peer = null

        videoSender = null

        track?.dispose()
        track = null

        source?.dispose()
        source = null

        helper?.dispose()
        helper = null

        factory?.dispose()
        factory = null

        egl?.release()
        egl = null

        candidates.clear()
        seen.clear()
    }
}
