package dev.kdriver.core.utils

import io.ktor.client.engine.*
import io.ktor.client.engine.js.*

actual fun getWebSocketClientEngine(): HttpClientEngineFactory<*> = Js
actual fun getHttpApiClientEngine(): HttpClientEngineFactory<*> = Js
