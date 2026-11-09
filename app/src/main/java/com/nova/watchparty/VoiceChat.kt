package com.nova.watchparty

import android.content.Context
import org.json.JSONObject
import org.webrtc.*

/**
 * Mesh voice chat over WebRTC. Only the SPEAKER creates connections: when your mic is on you send an offer
 * to every other member; listeners answer and WebRTC plays the audio automatically.
 * Signaling goes through the room WebSocket ("rtc" messages). Practical limit: ~6 simultaneous speakers per room.
 */
class VoiceChat(ctx: Context, private val signal: (to: String, data: JSONObject) -> Unit) {
    private val factory: PeerConnectionFactory
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val peers = HashMap<String, PeerConnection>()
    private val pending = HashMap<String, MutableList<IceCandidate>>()
    private val remoteSet = HashSet<String>()
    var speaking = false; private set

    private val ice: List<PeerConnection.IceServer> = buildList {
        add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        if (BuildConfig.TURN_URL.isNotBlank()) add(PeerConnection.IceServer.builder(BuildConfig.TURN_URL.split(",").map { it.trim() })
            .setUsername(BuildConfig.TURN_USERNAME).setPassword(BuildConfig.TURN_CREDENTIAL).createIceServer())
    }

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    // Each member pair can have two connections: "out:<id>" (I speak to them) and "in:<id>" (they speak to me).
    private fun newPeer(key: String, id: String, role: String): PeerConnection? {
        peers[key]?.let { return it }
        val cfg = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                signal(id, JSONObject().put("t", "ice").put("d", role).put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("cand", c.sdp))
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return null
        peers[key] = pc; return pc
    }

    private fun offerTo(id: String) {
        val pc = newPeer("out:$id", id, "o") ?: return
        track?.let { pc.addTrack(it, listOf("nova")) }
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                pc.setLocalDescription(Sdp(), d); signal(id, JSONObject().put("t", "offer").put("d", "o").put("sdp", d.description))
            }
        }, MediaConstraints())
    }

    private fun flush(key: String) { pending.remove(key)?.forEach { peers[key]?.addIceCandidate(it) } }

    /** Handle an incoming signaling message from member [from]. */
    fun onSignal(from: String, d: JSONObject) {
        val fromOfferer = d.optString("d") == "o"
        val key = if (fromOfferer) "in:$from" else "out:$from"   // which of MY connections this message belongs to
        when (d.optString("t")) {
            "offer" -> {
                closePeer(key); val pc = newPeer(key, from, "i") ?: return
                pc.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        remoteSet.add(key); flush(key)
                        pc.createAnswer(object : Sdp() {
                            override fun onCreateSuccess(a: SessionDescription) {
                                pc.setLocalDescription(Sdp(), a); signal(from, JSONObject().put("t", "answer").put("d", "i").put("sdp", a.description))
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, d.getString("sdp")))
            }
            "answer" -> peers[key]?.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() { remoteSet.add(key); flush(key) }
            }, SessionDescription(SessionDescription.Type.ANSWER, d.getString("sdp")))
            "ice" -> {
                val c = IceCandidate(d.optString("mid"), d.optInt("idx"), d.getString("cand"))
                if (remoteSet.contains(key)) peers[key]?.addIceCandidate(c) else pending.getOrPut(key) { mutableListOf() }.add(c)
            }
        }
    }

    /** Call when the member list changes. [others] = member ids except me; [speakers] = ids with mic on. */
    fun sync(others: Set<String>, speakers: Set<String>) {
        if (speaking) others.filter { "out:$it" !in peers }.forEach { offerTo(it) }   // newcomers hear me
        peers.keys.toList().forEach { k ->
            val id = k.substringAfter(':')
            if (id !in others || (k.startsWith("in:") && id !in speakers)) closePeer(k)
        }
    }

    fun setSpeaking(on: Boolean, others: Set<String>) {
        if (on == speaking) return
        speaking = on
        if (on) {
            val src = factory.createAudioSource(MediaConstraints())
            source = src
            track = factory.createAudioTrack("nova-mic", src).also { it.setEnabled(true) }
            others.forEach { offerTo(it) }
        } else {
            peers.keys.filter { it.startsWith("out:") }.forEach { closePeer(it) }
            track?.dispose(); source?.dispose(); track = null; source = null
        }
    }

    private fun closePeer(key: String) { peers.remove(key)?.close(); remoteSet.remove(key); pending.remove(key) }

    fun release() {
        peers.keys.toList().forEach { closePeer(it) }
        track?.dispose(); source?.dispose(); factory.dispose()
    }
}
