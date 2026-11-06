package com.apk3rde.app

object AppConfig {
    const val SITE_URL = "https://apk3rade.mangoad.online"
    const val START_URL = "https://apk3rade.mangoad.online/index.php"
    const val SITE_DOMAIN = "mangoad.online"
    const val NOTIF_API = "/index.php?apk3_chat_api=1&chat_action=get_notif_center"

    fun isInternalHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        return host == SITE_DOMAIN || host.endsWith(".$SITE_DOMAIN")
    }
}
