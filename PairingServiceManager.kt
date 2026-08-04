package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import org.json.JSONObject

class PairingServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "PairingSvcMgr"
    }

    private var pairingManager: PairingManager? = null
    private var mobileActionExecutor: MobileActionExecutor? = null
    private var pairingWsClient: PairingWebSocketClient? = null
    private var currentEndpoint: String = ""

    fun isPaired(): Boolean = pairingManager?.isPaired() == true

    fun getPairingManager(): PairingManager? = pairingManager

    fun initialize(endpoint: String, forceTailscaleIp: String? = null, forcePort: Int? = null) {
        currentEndpoint = endpoint
        if (pairingManager == null) {
            pairingManager = PairingManager(context)
        }
        if (!pairingManager!!.isPaired()) {
            Log.d(TAG, "No paired devices — skipping")
            return
        }
        if (pairingWsClient?.isCurrentlyConnected() == true) {
            Log.d(TAG, "Already connected")
            return
        }

        mobileActionExecutor = MobileActionExecutor(context)
        val deviceId = pairingManager!!.getDeviceId()
        val deviceName = pairingManager!!.getDeviceName()
        val accessToken = pairingManager!!.getAccessToken() ?: return

        // استخدام Tailscale IP مباشر لو موجود
        val pairedDesktop = pairingManager!!.getPairedDesktops().firstOrNull()
        val tailscaleIp = forceTailscaleIp ?: pairedDesktop?.tailscaleIp
        val port = forcePort ?: pairedDesktop?.port ?: 8000

        val wsUrl = if (tailscaleIp?.isNotEmpty() == true) {
            "ws://$tailscaleIp:$port"
        } else {
            "wss://$endpoint"
        }

        val pairedList = pairingManager!!.getPairedDesktops().map { Pair(it.deviceId, it.name) }

        Log.d(TAG, "Starting Pairing WS for $deviceId at $wsUrl")

        pairingWsClient?.disconnect()
        pairingWsClient?.release()

        pairingWsClient = PairingWebSocketClient(
            context = context,
            serverWsUrl = wsUrl,
            deviceId = deviceId,
            accessToken = accessToken,
            deviceName = deviceName,
            pairedDevices = pairedList,
            onTaskReceived = { taskJson ->
                Log.d(TAG, "Task: ${taskJson.optString("action")}")
                mobileActionExecutor?.execute(taskJson)
                pairingWsClient?.sendTaskResult(
                    taskId = taskJson.optString("task_id", ""),
                    status = "success",
                    originalSender = taskJson.optString("from", null)
                )
            },
            onPeerOnline = { peerId, name ->
                Log.d(TAG, "Peer online: $name ($peerId)")
                pairingManager?.setDesktopOnline(peerId, true)
            },
            onPeerOffline = { peerId ->
                Log.d(TAG, "Peer offline: $peerId")
                pairingManager?.setDesktopOnline(peerId, false)
            },
            onDisconnected = {
                Log.d(TAG, "Pairing WS disconnected")
                pairingManager?.markAllDesktopsOffline()
            },
            onStatusChange = { connected ->
                Log.d(TAG, "Pairing status: $connected")
                if (!connected) {
                    pairingManager?.markAllDesktopsOffline()
                }
            }
        )
        pairingWsClient?.connect()
    }

    fun disconnect() {
        pairingWsClient?.disconnect()
    }

    fun release() {
        disconnect()
        pairingWsClient?.release()
        pairingWsClient = null
        mobileActionExecutor = null
        pairingManager = null
    }

    fun getPairedDesktops(): List<PairedDesktop> =
        pairingManager?.getPairedDesktops() ?: emptyList()
}
