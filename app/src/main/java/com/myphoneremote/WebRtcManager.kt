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
        @Volatile private var initialized = false

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

    private var authorized = false
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

            val servers = listOf(
                PeerConnection.IceServer.builder(
                    "stun:stun.l.google.com:19302"
                ).createIceServer()
            )

            peer = factory!!.createPeerConnection(
                PeerConnection.RTCConfiguration(servers),
                object : PeerConnection.Observer {

                    override fun onSignalingChange(
                        state: PeerConnection.SignalingState?
                    ) {}

                    override fun onIceConnectionChange(
                        state: PeerConnection.IceConnectionState?
                    ) {
                        onStatus(state?.name ?: "UNKNOWN")
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
                        stream?.videoTracks?.firstOrNull()?.let {
                            onVideo(it, egl!!.eglBaseContext)
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
                            onVideo(it, egl!!.eglBaseContext)
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

                peer!!.addTrack(
                    track,
                    listOf("screen_stream")
                )

                capturer!!.startCapture(
                    720,
                    1280,
                    60
                )
            }

            listen()

            if (controller) makeOffer()

        } catch (e: Exception) {
            Log.e("WebRtcManager", "Setup error", e)
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
                ).child("sdp").getValue(String::class.java)

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

                        val text = item.child("sdp")
                            .getValue(String::class.java)
                            ?: return@forEach

                        val mid = item.child("mid")
                            .getValue(String::class.java)

                        val index = item.child("index")
                            .getValue(Int::class.java) ?: 0

                        val candidate = IceCandidate(
                            mid,
                            index,
                            text
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
