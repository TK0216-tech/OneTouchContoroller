package jp.relative.tech.one_touch_controller.server.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.webAppRoutes(webAppHtml: String) {
    // The plain address (http://<PC IP>:8080 or the https://....trycloudflare.com link) opens the web version
    // in a browser. Programs that do not ask for a web page (like the page's own status check) still get
    // the short text answer.
    get("/") {
        // only a real browser (its User-Agent starts with "Mozilla") gets the page. Apps such as Gesture Operate
        // also say "text/html" in their Accept header, but they only want the short text.
        val wantsPage = call.request.headers["Accept"]?.contains("text/html") == true &&
            call.request.headers["User-Agent"]?.contains("Mozilla") == true
        if (wantsPage) {
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondText(webAppHtml, ContentType.Text.Html)
        } else {
            call.respondText("PC Remote server is running")
        }
    }

    // ---- Web version of the remote (iPhone Safari, any browser): http://<PC IP>:8080/app ----
    // The page itself is public; every action still needs the access token.
    get("/app") {
        call.response.headers.append("Cache-Control", "no-cache")
        call.respondText(webAppHtml, ContentType.Text.Html)
    }
}
