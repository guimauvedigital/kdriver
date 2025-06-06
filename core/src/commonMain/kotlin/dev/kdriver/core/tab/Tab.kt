package dev.kdriver.core.tab

import dev.kaccelero.serializers.Serialization
import dev.kdriver.cdp.domain.*
import dev.kdriver.cdp.domain.Target
import dev.kdriver.core.browser.Browser
import dev.kdriver.core.browser.BrowserTarget
import dev.kdriver.core.connection.Connection
import dev.kdriver.core.dom.Element
import dev.kdriver.core.utils.filterRecurse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory

class Tab(
    websocketUrl: String,
    messageListeningScope: CoroutineScope,
    eventsBufferSize: Int,
    targetInfo: Target.TargetInfo,
    owner: Browser? = null,
) : Connection(websocketUrl, messageListeningScope, eventsBufferSize, targetInfo, owner), BrowserTarget {

    private val logger = LoggerFactory.getLogger("Tab")

    suspend fun get(
        url: String = "about:blank",
        newTab: Boolean = false,
        newWindow: Boolean = false,
    ): Tab {
        val browser = owner ?: throw IllegalStateException(
            "This tab has no browser reference, so you can't use get()"
        )

        val openNewTab = newWindow || newTab

        return if (openNewTab) {
            browser.get(url, newTab = true, newWindow = newWindow)
        } else {
            page.navigate(url)
            wait()
            this
        }
    }

    suspend fun back() {
        runtime.evaluate("window.history.back()")
    }

    suspend fun forward() {
        runtime.evaluate("window.history.forward()")
    }

    suspend fun reload(
        ignoreCache: Boolean = true,
        scriptToEvaluateOnLoad: String? = null,
    ) {
        page.reload(
            ignoreCache = ignoreCache,
            scriptToEvaluateOnLoad = scriptToEvaluateOnLoad
        )
    }

    suspend inline fun <reified T> evaluate(
        expression: String,
        awaitPromise: Boolean = false,
    ): T? {
        val result = runtime.evaluate(
            expression = expression,
            returnByValue = true,
            userGesture = true,
            awaitPromise = awaitPromise,
            allowUnsafeEvalBlockedByCSP = true,
        )
        result.exceptionDetails?.let { throw EvaluateException(it) }
        return result.result.value?.let {
            Serialization.json.decodeFromJsonElement<T>(it)
        }
    }

    suspend fun setUserAgent(
        userAgent: String? = null,
        acceptLanguage: String? = null,
        platform: String? = null,
    ) {
        val ua = userAgent
            ?: evaluate<String>("navigator.userAgent")
            ?: throw IllegalStateException("Could not read existing user agent from navigator object")

        network.setUserAgentOverride(
            userAgent = ua,
            acceptLanguage = acceptLanguage,
            platform = platform
        )
    }

    suspend fun getWindow(): dev.kdriver.cdp.domain.Browser.GetWindowForTargetReturn {
        return browser.getWindowForTarget(targetId)
    }

    suspend fun getContent(): String {
        val doc = dom.getDocument(depth = -1, pierce = true)
        return dom.getOuterHTML(backendNodeId = doc.root.backendNodeId).outerHTML
    }

    suspend fun activate() {
        val targetId = targetInfo?.targetId
            ?: throw IllegalArgumentException("target is null")
        target.activateTarget(targetId)
    }

    suspend fun bringToFront() {
        activate()
    }

    suspend fun maximize() {
        setWindowState(state = "maximize")
    }

    suspend fun minimize() {
        setWindowState(state = "minimize")
    }

    suspend fun fullscreen() {
        setWindowState(state = "fullscreen")
    }

    suspend fun setWindowState(
        left: Int = 0,
        top: Int = 0,
        width: Int = 1280,
        height: Int = 720,
        state: String = "normal",
    ) {
        val availableStates = listOf("minimized", "maximized", "fullscreen", "normal")
        val (windowId, _) = getWindow()

        val stateName = availableStates.find { stateName ->
            state.lowercase().all { it in stateName }
        } ?: throw IllegalStateException(
            "Could not determine any of $availableStates from input '$state'"
        )

        val windowState = dev.kdriver.cdp.domain.Browser.WindowState.valueOf(stateName.uppercase())

        val bounds = if (windowState == dev.kdriver.cdp.domain.Browser.WindowState.NORMAL) {
            dev.kdriver.cdp.domain.Browser.Bounds(
                left = left,
                top = top,
                width = width,
                height = height,
                windowState = windowState
            )
        } else {
            // Ensure we're in NORMAL state before switching to others
            setWindowState(state = "normal")
            dev.kdriver.cdp.domain.Browser.Bounds(windowState = windowState)
        }

        browser.setWindowBounds(windowId, bounds)
    }

    suspend fun scrollDown(amount: Int = 25) {
        val (_, bounds) = getWindow()
        val yDistance = bounds.height?.times((amount / 100.0))?.unaryMinus()

        input.synthesizeScrollGesture(
            x = 0.0,
            y = 0.0,
            yDistance = yDistance,
            yOverscroll = 0.0,
            xOverscroll = 0.0,
            preventFling = true,
            repeatDelayMs = 0,
            speed = 7777
        )
    }

    suspend fun scrollUp(amount: Int = 25) {
        val (_, bounds) = getWindow()
        val yDistance = bounds.height?.times((amount / 100.0))

        input.synthesizeScrollGesture(
            x = 0.0,
            y = 0.0,
            yDistance = yDistance,
            yOverscroll = 0.0,
            xOverscroll = 0.0,
            preventFling = true,
            repeatDelayMs = 0,
            speed = 7777
        )
    }

    suspend fun waitForReadyState(
        until: String = "interactive", // "loading", "interactive", or "complete"
        timeoutSeconds: Int = 10,
    ): Boolean {
        val startTime = Clock.System.now().toEpochMilliseconds()

        while (true) {
            val readyState = evaluate<String>("document.readyState")
            if (readyState == until) {
                return true
            }

            val elapsed = (Clock.System.now().toEpochMilliseconds() - startTime) / 1000
            if (elapsed > timeoutSeconds) {
                throw IllegalStateException("Timeout waiting for readyState == $until")
            }

            delay(100) // wait 100 ms
        }
    }

    suspend fun select(
        selector: String,
        timeoutSeconds: Long = 10L,
    ): Element {
        val trimmedSelector = selector.trim()
        val startTime = Clock.System.now().toEpochMilliseconds()

        var item = querySelector(trimmedSelector)
        while (item == null) {
            wait()

            item = querySelector(trimmedSelector)

            val elapsed = (Clock.System.now().toEpochMilliseconds() - startTime) / 1000
            if (elapsed > timeoutSeconds) {
                throw IllegalStateException("time ran out while waiting for: $selector")
            }

            delay(500) // sleep for 0.5 seconds
        }
        return item
    }

    suspend fun querySelectorAll(
        selector: String,
        node: DOM.Node? = null,
    ): List<Element> {
        val lastMap = mutableMapOf<Int, Boolean>()

        val doc = if (node == null) {
            dom.getDocument(-1, true).root
        } else {
            var docNode = node
            if (node.nodeName == "IFRAME") {
                docNode = node.contentDocument ?: node
            }
            docNode
        }

        val nodeIds = try {
            dom.querySelectorAll(doc.nodeId, selector)
        } catch (e: Exception) {
            if (node != null && e.message?.contains("could not find node", ignoreCase = true) == true) {
                val last = lastMap[node.nodeId]

                if (last == true) {
                    // Remove the marker to avoid infinite recursion
                    lastMap.remove(node.nodeId)
                    return emptyList()
                }

                if (node is Element) node.update()

                // Mark as retried once
                lastMap[node.nodeId] = true

                return querySelectorAll(selector, node)
            } else {
                disableDomAgent()
                throw e
            }
        }.nodeIds

        if (nodeIds.isEmpty()) return emptyList()

        val items = mutableListOf<Element>()
        for (nid in nodeIds) {
            val innerNode = filterRecurse(
                doc,
                predicate = { it.nodeId == nid },
                getChildren = { it.children },
                getShadowRoots = { it.shadowRoots }
            )
            if (innerNode != null) {
                val elem = Element(innerNode, this, doc)
                items.add(elem)
            }
        }
        return items
    }

    suspend fun querySelector(
        selector: String,
        node: DOM.Node? = null,
    ): Element? {
        val lastMap = mutableMapOf<Int, Boolean>()

        val trimmedSelector = selector.trim()

        val doc = if (node == null) {
            dom.getDocument(-1, true).root
        } else {
            if (node.nodeName == "IFRAME") node.contentDocument ?: node
            else node
        }

        val nodeId = try {
            dom.querySelector(doc.nodeId, trimmedSelector)
        } catch (e: Exception) {
            if (node != null && e.message?.contains("could not find node", ignoreCase = true) == true) {
                val last = lastMap[node.nodeId]

                if (last == true) {
                    // Remove the marker to avoid infinite recursion
                    lastMap.remove(node.nodeId)
                    return null
                }

                if (node is Element) node.update()

                // Mark as retried once
                lastMap[node.nodeId] = true

                return querySelector(trimmedSelector, node)
            } else {
                disableDomAgent()
                throw e
            }
        }.nodeId

        if (nodeId == null) return null

        val foundNode = filterRecurse(
            doc,
            predicate = { it.nodeId == nodeId },
            getChildren = { it.children },
            getShadowRoots = { it.shadowRoots }
        )
        return foundNode?.let { Element(it, this, doc) }
    }

    suspend fun disableDomAgent() {
        try {
            dom.disable()
        } catch (_: Exception) {
            logger.debug("Ignoring DOM.disable exception")
        }
    }

    override fun toString(): String {
        return "Tab: ${this@Tab.targetInfo?.toString() ?: "no target"}"
    }

}
