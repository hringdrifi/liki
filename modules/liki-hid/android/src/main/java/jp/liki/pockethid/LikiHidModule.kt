package jp.liki.pockethid

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/** Expo bridge for the Android HID backend. */
class LikiHidModule : Module() {
    companion object { private val connection = ReloadSession<NativeBridge>() }
    private val client = Any()
    private val main = Handler(Looper.getMainLooper())
    private var lastActivity: WeakReference<Activity>? = null
    private var bridge: NativeBridge? = null
    private fun rememberActivity() { appContext.currentActivity?.let { lastActivity = WeakReference(it) } }
    private fun backend(): NativeBridge {
        bridge?.let { return it }
        rememberActivity()
        val native = connection.acquire(client) {
            NativeBridge(requireNotNull(appContext.reactContext).applicationContext, { null }, {})
        }
        bridge = native
        native.attach({ appContext.currentActivity }, { if (bridge === native) sendEvent("statusChanged", it) })
        return native
    }

    override fun definition() = ModuleDefinition {
        Name("LikiHid")
        Events("statusChanged")
        AsyncFunction("invoke") { method: String, args: Map<String, Any?> ->
            backend().invoke(method, args)
        }.runOnQueue(Queues.MAIN)
        AsyncFunction("preferences") { values: Map<String, String?> ->
            backend().invoke("preferences", mapOf("values" to values))
        }.runOnQueue(Queues.MAIN)
        AsyncFunction("pickFile") { promise: Promise -> backend().pickFile(promise) }.runOnQueue(Queues.MAIN)
        OnActivityResult { _, result -> if (connection.owns(client)) bridge?.activityResult(result.requestCode, result.resultCode, result.data) }
        OnActivityEntersForeground { rememberActivity(); if (connection.owns(client)) bridge?.resume() }
        OnActivityEntersBackground { if (connection.owns(client)) bridge?.pause() }
        OnActivityDestroys {
            if (lastActivity?.get()?.isFinishing == true) {
                connection.release(client, discard = true) { it.close() }; bridge = null
            } else if (connection.owns(client)) bridge?.pause()
        }
        OnDestroy {
            bridge = null
            main.post { connection.release(client) { it.detach() } }
        }
    }
}

/** Saved by the generated ReactActivity into Android's instance-state bundle. */
object LikiSession {
    var value: String? = null
    var reconnectAddress: String? = null
    var connectedAddress: String? = null
}
