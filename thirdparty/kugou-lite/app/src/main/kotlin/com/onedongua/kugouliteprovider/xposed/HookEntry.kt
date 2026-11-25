package com.onedongua.kugouliteprovider.xposed

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed(modulePackageName = Constants.PROVIDER_PACKAGE_NAME)
open class HookEntry : IYukiHookXposedInit {

    override fun onHook() {

        YukiHookAPI.encase {
            loadApp("com.kugou.android.lite", KugouLiteProvider)
        }
    }

    override fun onInit() {
        YukiHookAPI.configs {
            debugLog {
                isEnable = true
                tag = "KugouLiteProvider"
            }
        }
    }
}