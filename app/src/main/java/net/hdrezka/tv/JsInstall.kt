package net.hdrezka.tv

import android.webkit.WebView

/**
 * Выполнить выражение [call], которое обращается к `window.[global]`.
 * Большой скрипт [installJs] пересылается в страницу, только если его ещё нет в текущем документе, —
 * иначе каждое нажатие и каждый опрос плеера гоняли бы по ~10 КБ кода.
 */
fun WebView.evalInstalled(global: String, installJs: String, call: String, callback: ((String?) -> Unit)? = null) {
    evaluateJavascript("(function(){if(!window.$global)return '$NOT_INSTALLED';return $call;})()") { result ->
        if (result == "\"$NOT_INSTALLED\"") {
            evaluateJavascript("$installJs;$call") { callback?.invoke(it) }
        } else {
            callback?.invoke(result)
        }
    }
}

private const val NOT_INSTALLED = "__foxinstall__"
