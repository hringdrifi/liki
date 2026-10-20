package jp.liki.pockethid

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues

/** The UI uses this module; Android HID reports remain main's implementation. */
class LikiHidModule : Module() {
    private var bridge: NativeBridge? = null
    private fun backend(): NativeBridge = bridge ?: NativeBridge(
        requireNotNull(appContext.reactContext).applicationContext,
        { appContext.currentActivity },
        { if (bridge != null) sendEvent("statusChanged", it) }
    ).also { bridge = it }

    override fun definition() = ModuleDefinition {
        Name("LikiHid")
        Events("statusChanged")
        AsyncFunction("invoke") { method: String, args: Map<String, Any?> ->
            backend().invoke(method, args)
        }.runOnQueue(Queues.MAIN)
        AsyncFunction("pickFile") { promise: Promise -> backend().pickFile(promise) }.runOnQueue(Queues.MAIN)
        OnActivityResult { _, result -> bridge?.activityResult(result.requestCode, result.resultCode, result.data) }
        OnActivityEntersForeground { bridge?.resume() }
        OnActivityEntersBackground { bridge?.pause() }
        OnActivityDestroys { bridge?.close() }
        OnDestroy { val previous = bridge; bridge = null; previous?.close() }
    }
}

/** Saved by the generated ReactActivity into Android's instance-state bundle. */
object LikiSession {
    var value: String? = null
    var reconnectAddress: String? = null
    var connectedAddress: String? = null
}
