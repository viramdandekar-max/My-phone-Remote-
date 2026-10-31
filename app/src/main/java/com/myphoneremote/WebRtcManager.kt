package com.myphoneremote

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import org.webrtc.*

class WebRtcManager(
    private val context: Context,
    private val sessionId: String,
    private val isCaller: Boolean,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onConnectionState: (String) -> Unit
) {

    companion object {
        private const val TAG = "WebRtcManager"

        private var initialized = false

        @Synchronized
        private fun initializeWebRtc(context: Context) {
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

    private val database = FirebaseDatabase.getInstance()
        .reference
        .child("webrtc")
        .child(sessionId)

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var signalingListener: ValueEventListener? = null

    private var localVideoTrack: VideoTrack? = null

    private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val processedCandidates = mutableSetOf<String>()

    private var closed = false

    fun start(videoTrack: VideoTrack? = null) {

        initializeWebRtc(context)

        localVideoTrack = videoTrack

        val eglBase = EglBase.create()

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(
                    eglBase.eglBaseContext
                )
            )
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder(
                "stun:stun.l.google.com:19302"
            ).createIceServer()
        )

        val configuration = PeerConnection.RTCConfiguration(
            iceServers
        )

        peerConnection = factory?.createPeerConnection(
            configuration,
            object : PeerConnection.Observer {

                override fun onSignalingChange(
                    state: PeerConnection.SignalingState?
                ) {}

                override fun onIceConnectionChange(
                    state: PeerConnection.IceConnectionState?
                ) {
                    onConnectionState(
                        state?.name ?: "UNKNOWN"
                    )
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
                    if (candidate != null) {
                        sendIceCandidate(candidate)
                    }
                }

                override fun onIceCandidatesRemoved(
                    candidates: Array<out IceCandidate>?
                ) {}

                override fun onAddStream(
                    stream: MediaStream?
                ) {
                    stream?.videoTracks?.firstOrNull()?.let {
                        onRemoteVideo(it)
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
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        onRemoteVideo(track)
                    }
                }
            }
        )

        localVideoTrack?.let { track ->
            peerConnection?.addTrack(
                track,
                listOf("screen_stream")
            )
        }

        listenForSignaling()

        if (isCaller) {
            createOffer()
        }
    }

    private fun createOffer() {

        peerConnection?.createOffer(
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription?
                ) {
                    if (description != null) {
                        setLocalDescription(description)
                    }
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Offer failed: $error")
                }

                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints()
        )
    }

    private fun createAnswer() {

        peerConnection?.createAnswer(
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription?
                ) {
                    if (description != null) {
                        setLocalDescription(description)
                    }
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Answer failed: $error")
                }

                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints()
        )
    }

    private fun setLocalDescription(
        description: SessionDescription
    ) {

        peerConnection?.setLocalDescription(
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription?
                ) {}

                override fun onSetSuccess() {

                    val key = if (isCaller) {
                        "offer"
                    } else {
                        "answer"
                    }

                    database.child(key).setValue(
                        mapOf(
                            "type" to description.type.name,
                            "sdp" to description.description
                        )
                    )
                }

                override fun onCreateFailure(error: String?) {}

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Local SDP error: $error")
                }
            },
            description
        )
    }

    private fun setRemoteDescription(
        description: SessionDescription
    ) {

        peerConnection?.setRemoteDescription(
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription?
                ) {}

                override fun onSetSuccess() {

                    remoteDescriptionSet = true

                    pendingCandidates.forEach {
                        peerConnection?.addIceCandidate(it)
                    }

                    pendingCandidates.clear()

                    if (!isCaller &&
                        description.type == SessionDescription.Type.OFFER
                    ) {
                        createAnswer()
                    }
                }

                override fun onCreateFailure(error: String?) {}

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Remote SDP error: $error")
                }
            },
            description
        )
    }

    private fun sendIceCandidate(
        candidate: IceCandidate
    ) {

        val side = if (isCaller) {
            "caller"
        } else {
            "receiver"
        }

        database.child("candidates")
            .child(side)
            .push()
            .setValue(
                mapOf(
                    "sdpMid" to (candidate.sdpMid ?: ""),
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "sdp" to candidate.sdp
                )
            )
    }

    private fun listenForSignaling() {

        signalingListener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {

                if (closed) return

                val remoteKey = if (isCaller) {
                    "answer"
                } else {
                    "offer"
                }

                val descriptionSnapshot =
                    snapshot.child(remoteKey)

                val sdp = descriptionSnapshot
                    .child("sdp")
                    .getValue(String::class.java)

                if (sdp != null && !remoteDescriptionSet) {

                    val type = if (isCaller) {
                        SessionDescription.Type.ANSWER
                    } else {
                        SessionDescription.Type.OFFER
                    }

                    if (peerConnection?.remoteDescription == null) {
                        setRemoteDescription(
                            SessionDescription(type, sdp)
                        )
                    }
                }

                val remoteSide = if (isCaller) {
                    "receiver"
                } else {
                    "caller"
                }

                snapshot.child("candidates")
                    .child(remoteSide)
                    .children
                    .forEach { child ->

                        val key = child.key ?: return@forEach

                        if (!processedCandidates.add(key)) {
                            return@forEach
                        }

                        val candidateSdp = child.child("sdp")
                            .getValue(String::class.java)
                            ?: return@forEach

                        val mid = child.child("sdpMid")
                            .getValue(String::class.java)

                        val index = child.child("sdpMLineIndex")
                            .getValue(Int::class.java) ?: 0

                        val candidate = IceCandidate(
                            mid,
                            index,
                            candidateSdp
                        )

                        if (remoteDescriptionSet) {
                            peerConnection?.addIceCandidate(candidate)
                        } else {
                            pendingCandidates.add(candidate)
                        }
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Signaling error: ${error.message}")
                onConnectionState("SIGNALING_ERROR")
            }
        }

        database.addValueEventListener(signalingListener!!)
    }

    fun close() {

        closed = true

        signalingListener?.let {
            database.removeEventListener(it)
        }

        signalingListener = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        factory?.dispose()
        factory = null
    }
}
