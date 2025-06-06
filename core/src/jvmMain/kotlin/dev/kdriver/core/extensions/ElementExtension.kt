package dev.kdriver.core.extensions

import dev.kdriver.cdp.domain.input
import dev.kdriver.core.dom.Element

suspend fun Element.sendKeysWithSpecialChars(text: String) {
    apply("(elem) => elem.focus()")
    for (cluster in text.graphemeClusters()) {
        if (cluster.all { it.code in 32..126 }) {
            sendKeys(cluster)
        } else {
            tab.input.insertText(cluster)
        }
    }
}
