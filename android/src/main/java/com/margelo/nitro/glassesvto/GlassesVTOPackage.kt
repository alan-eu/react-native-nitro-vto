package com.margelo.nitro.glassesvto

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager
import com.margelo.nitro.glassesvto.views.HybridGlassesVTOViewManager

/**
 * React Native Package for GlassesVTO library.
 * Registers the HybridGlassesVTOViewManager with React Native.
 */
class GlassesVTOPackage : BaseReactPackage() {

    override fun getModule(
        name: String,
        reactContext: ReactApplicationContext
    ): NativeModule? = null

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
        ReactModuleInfoProvider { HashMap() }

    override fun createViewManagers(
        reactContext: ReactApplicationContext
    ): List<ViewManager<*, *>> {
        val viewManagers = ArrayList<ViewManager<*, *>>()
        viewManagers.add(HybridGlassesVTOViewManager())
        return viewManagers
    }

    companion object {
        init {
            GlassesVTOOnLoad.initializeNative()
        }
    }
}
