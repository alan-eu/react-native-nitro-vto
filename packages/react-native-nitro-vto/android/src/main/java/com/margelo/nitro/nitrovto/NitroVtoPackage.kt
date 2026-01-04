package com.margelo.nitro.nitrovto

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager
import com.margelo.nitro.nitrovto.views.HybridNitroVtoViewManager

/**
 * React Native Package for NitroVto library.
 * Registers the HybridNitroVtoViewManager with React Native.
 */
class NitroVtoPackage : BaseReactPackage() {

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
        viewManagers.add(HybridNitroVtoViewManager())
        return viewManagers
    }

    companion object {
        init {
            NitroVtoOnLoad.initializeNative()
        }
    }
}
