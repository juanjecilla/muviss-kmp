package com.codingpit.muviss

/** iOS reports its own namespace, so the Play crash gate never reads an iOS crash (#422). */
internal actual val crashReleaseNamespace: String = "com.codingpit.muviss.ios"
