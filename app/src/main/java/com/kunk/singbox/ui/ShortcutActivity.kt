package com.kunk.singbox.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.kunk.singbox.R
import com.kunk.singbox.manager.VpnServiceManager

class ShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        moveTaskToBack(true)

        setContentView(R.layout.activity_none)

        // 步骤3: 启动 VPN 服务
        if (intent?.action == ACTION_TOGGLE) {
            VpnServiceManager.toggleVpn(this)
        }

        finish()
    }

    companion object {
        const val ACTION_TOGGLE = "com.kunk.singbox.action.TOGGLE"
    }
}
