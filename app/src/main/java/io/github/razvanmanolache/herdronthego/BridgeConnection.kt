package io.github.razvanmanolache.herdronthego

import org.json.JSONObject

/** The controller and command journal use the same protocol over either authenticated transport. */
interface BridgeConnection {
    var onFrame: ((Frame) -> Unit)?
    var onDisconnect: (() -> Unit)?
    fun connect(host: String, port: Int, user: String, password: String,
                timeoutMs: Int = 15000, onHostKey: (String) -> Boolean): Any
    fun isConnected(): Boolean
    fun closeStdio()
    fun disconnect()
    fun bridgePresent(): Boolean
    fun bridgeVersion(): String
    fun sessions(): List<BridgeSession>
    fun socketAlive(): Boolean
    fun openStdio(sessionName: String)
    fun send(type: String, sessionId: String, payload: JSONObject): String
    fun request(type: String, sessionId: String, payload: JSONObject, expected: String,
                timeoutMs: Long = 30000): Frame
}
