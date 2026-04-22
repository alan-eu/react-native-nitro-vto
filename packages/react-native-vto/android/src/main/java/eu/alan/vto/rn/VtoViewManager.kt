package eu.alan.vto.rn

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event
import eu.alan.vto.core.VtoView

/**
 * Old-architecture (classic) React Native bridge for the shared `VtoView`.
 * Wraps an `eu.alan.vto.core.VtoView` instance and plumbs JS props / commands /
 * callbacks to its existing Kotlin API.
 *
 * Kept intentionally thin: all rendering + AR logic lives in vto-core-native.
 */
class VtoViewManager : SimpleViewManager<VtoView>() {

    companion object {
        const val REACT_CLASS = "VtoView"
        const val COMMAND_SWITCH_MODEL = "switchModel"
        const val COMMAND_RESET_SESSION = "resetSession"
        const val EVENT_MODEL_LOADED = "onModelLoaded"
        const val EVENT_FACE_TRACKED = "onFaceTracked"
        const val EVENT_GLASSES_DISPLAYED = "onGlassesDisplayed"
    }

    override fun getName(): String = REACT_CLASS

    override fun createViewInstance(reactContext: ThemedReactContext): VtoView {
        val view = VtoView(reactContext)
        wireCallbacks(view, reactContext)
        return view
    }

    override fun onDropViewInstance(view: VtoView) {
        view.destroy()
        super.onDropViewInstance(view)
    }

    // --- Props -----------------------------------------------------------

    @ReactProp(name = "modelUrl")
    fun setModelUrl(view: VtoView, url: String?) {
        view.setModelUrl(url ?: "")
    }

    @ReactProp(name = "isActive", defaultBoolean = true)
    fun setIsActive(view: VtoView, active: Boolean) {
        view.setIsActive(active)
    }

    @ReactProp(name = "faceMeshOcclusion")
    fun setFaceMeshOcclusion(view: VtoView, enabled: Boolean?) {
        view.setFaceMeshOcclusion(enabled)
    }

    @ReactProp(name = "backPlaneOcclusion")
    fun setBackPlaneOcclusion(view: VtoView, enabled: Boolean?) {
        view.setBackPlaneOcclusion(enabled)
    }

    // Using primitive `Double` + `defaultDouble` because RN old-arch
    // `ViewManagerPropertyUpdater` doesn't accept boxed `java.lang.Double` —
    // `Double?` would compile to that and crash at register time.
    @ReactProp(name = "forwardOffset", defaultDouble = 0.005)
    fun setForwardOffset(view: VtoView, offset: Double) {
        view.setForwardOffset(offset)
    }

    @ReactProp(name = "debug")
    fun setDebug(view: VtoView, enabled: Boolean?) {
        view.setDebug(enabled)
    }

    // --- Commands --------------------------------------------------------

    override fun receiveCommand(view: VtoView, commandId: String, args: ReadableArray?) {
        when (commandId) {
            COMMAND_SWITCH_MODEL -> {
                val url = args?.getString(0) ?: return
                view.switchModel(url)
            }
            COMMAND_RESET_SESSION -> view.resetSession()
            else -> throw IllegalArgumentException(
                "Unknown command for VtoViewManager: $commandId"
            )
        }
    }

    // --- Event type constants for the JS side ----------------------------

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> {
        // React Native old arch wires a view prop `onXxx` to a native event of
        // the same name via the `registrationName` entry. Keeping registration
        // names identical to event names means the JS surface just receives
        // `onModelLoaded` etc. directly.
        return mapOf(
            EVENT_MODEL_LOADED to mapOf("registrationName" to EVENT_MODEL_LOADED),
            EVENT_FACE_TRACKED to mapOf("registrationName" to EVENT_FACE_TRACKED),
            EVENT_GLASSES_DISPLAYED to mapOf("registrationName" to EVENT_GLASSES_DISPLAYED),
        )
    }

    // --- Callback wiring -------------------------------------------------

    private fun wireCallbacks(view: VtoView, reactContext: ThemedReactContext) {
        view.onModelLoaded = { url ->
            dispatch(reactContext, view, EVENT_MODEL_LOADED, Arguments.createMap().apply {
                putString("modelUrl", url)
            })
        }
        view.onFaceTracked = {
            dispatch(reactContext, view, EVENT_FACE_TRACKED, Arguments.createMap())
        }
        view.onGlassesDisplayed = { url ->
            dispatch(reactContext, view, EVENT_GLASSES_DISPLAYED, Arguments.createMap().apply {
                putString("modelUrl", url)
            })
        }
    }

    private fun dispatch(
        reactContext: ThemedReactContext,
        view: VtoView,
        name: String,
        payload: WritableMap,
    ) {
        val surfaceId = UIManagerHelper.getSurfaceId(view)
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, view.id)
        dispatcher?.dispatchEvent(VtoEvent(surfaceId, view.id, name, payload))
    }

    // `Event` uses a self-referencing type parameter (`T : Event<T>`), so the
    // subclass must name itself as `T` — an anonymous `Event<Event<*>>` does
    // not satisfy that bound.
    private class VtoEvent(
        surfaceId: Int,
        viewId: Int,
        private val name: String,
        private val payload: WritableMap,
    ) : Event<VtoEvent>(surfaceId, viewId) {
        override fun getEventName(): String = name
        override fun getEventData(): WritableMap = payload
    }
}
