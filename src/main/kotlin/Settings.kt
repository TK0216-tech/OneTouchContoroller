// Settings of the PC window: light or dark theme, accent colour and language.
// They are saved in  ~/.onetouch/settings.properties  and used by the whole window
// (the colours and tr("...") below read them).

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import java.util.Properties

/** The three choices. Changing one of them redraws the whole window at once. */
object Look {
    private val file = File(System.getProperty("user.home"), ".onetouch/settings.properties")

    var dark by mutableStateOf(true)
    var accent by mutableIntStateOf(0)        // position in the `accents` list
    var lang by mutableStateOf("en")          // "en", "ja" or "zh"

    fun load() {
        // first start: use the language of Windows
        lang = when (Locale.getDefault().language) { "ja" -> "ja"; "zh" -> "zh"; else -> "en" }
        try {
            if (!file.isFile) return
            val p = Properties()
            file.inputStream().use { p.load(it) }
            dark = p.getProperty("dark", "true") != "false"
            accent = (p.getProperty("accent", "0").toIntOrNull() ?: 0).coerceIn(accents.indices)
            lang = p.getProperty("lang", lang).takeIf { it in setOf("en", "ja", "zh") } ?: lang
        } catch (e: Exception) {
            // a broken settings file is not a reason to stop: the defaults are used
        }
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("dark", dark.toString())
            p.setProperty("accent", accent.toString())
            p.setProperty("lang", lang)
            file.outputStream().use { p.store(it, "One Touch Controller - window settings") }
        } catch (e: Exception) {
            // not saved: the choice still works until the program is closed
        }
    }
}

/** One accent colour: the bright version for the dark theme, a deeper one for the light theme. */
class AccentChoice(val onDark: Color, val onLight: Color, val inkOnDark: Color)

val accents = listOf(
    AccentChoice(Color(0xFF3DD6C6), Color(0xFF0E9488), Color(0xFF052521)),   // teal
    AccentChoice(Color(0xFF6AA8FF), Color(0xFF2563EB), Color(0xFF06183A)),   // blue
    AccentChoice(Color(0xFFA78BFA), Color(0xFF7C3AED), Color(0xFF1C0F45)),   // violet
    AccentChoice(Color(0xFFFFA24C), Color(0xFFD9650B), Color(0xFF3A1C00)),   // orange
    AccentChoice(Color(0xFFFF7EB6), Color(0xFFDB2777), Color(0xFF3D0A22))    // pink
)

// ---------- The colours of the window (they follow the settings) ----------
val OtcInk: Color get() = if (Look.dark) Color(0xFF0E1420) else Color(0xFFF3F5F9)        // window background
val OtcPanel: Color get() = if (Look.dark) Color(0xFF161E2D) else Color(0xFFFFFFFF)      // cards
val OtcKey: Color get() = if (Look.dark) Color(0xFF263148) else Color(0xFFE3E8F1)        // secondary buttons
val OtcLine: Color get() = if (Look.dark) Color(0xFF2C3850) else Color(0xFFD3DAE6)       // borders
val OtcText: Color get() = if (Look.dark) Color(0xFFE8EDF7) else Color(0xFF131A26)
val OtcMuted: Color get() = if (Look.dark) Color(0xFF8E9BB3) else Color(0xFF5D6B85)
val OtcDanger: Color get() = if (Look.dark) Color(0xFFFF7A7A) else Color(0xFFD83A3A)
val OtcAccent: Color get() = accents[Look.accent.coerceIn(accents.indices)].let { if (Look.dark) it.onDark else it.onLight }
val OtcAccentInk: Color get() = if (Look.dark) accents[Look.accent.coerceIn(accents.indices)].inkOnDark else Color.White

fun otcPcColorScheme(): ColorScheme {
    val container = OtcAccent.copy(alpha = 0.22f).compositeOver(OtcInk)
    return if (Look.dark) darkColorScheme(
        primary = OtcAccent, onPrimary = OtcAccentInk,
        primaryContainer = container, onPrimaryContainer = OtcAccent,
        secondary = OtcAccent, onSecondary = OtcAccentInk,
        secondaryContainer = OtcKey, onSecondaryContainer = OtcText,
        tertiary = OtcAccent, onTertiary = OtcAccentInk,
        background = OtcInk, onBackground = OtcText,
        surface = OtcInk, onSurface = OtcText,
        surfaceVariant = OtcPanel, onSurfaceVariant = OtcText,
        outline = OtcLine, outlineVariant = OtcLine,
        error = OtcDanger, onError = Color(0xFF3A0B0B)
    ) else lightColorScheme(
        primary = OtcAccent, onPrimary = OtcAccentInk,
        primaryContainer = container, onPrimaryContainer = OtcAccent,
        secondary = OtcAccent, onSecondary = OtcAccentInk,
        secondaryContainer = OtcKey, onSecondaryContainer = OtcText,
        tertiary = OtcAccent, onTertiary = OtcAccentInk,
        background = OtcInk, onBackground = OtcText,
        surface = OtcInk, onSurface = OtcText,
        surfaceVariant = OtcPanel, onSurfaceVariant = OtcText,
        outline = OtcLine, outlineVariant = OtcLine,
        error = OtcDanger, onError = Color.White
    )
}

// ---------- Language ----------
/**
 * Returns the text in the chosen language. The English text is the "key":  tr("Save")
 * %1, %2 are replaced by the extra values:  tr("Running (port %1)", PORT)
 * A text that has no translation stays English. (The activity log is always English.)
 */
fun tr(text: String, vararg values: Any?): String {
    var result = if (Look.lang == "en") text else (words[text]?.get(if (Look.lang == "ja") 0 else 1) ?: text)
    values.forEachIndexed { index, value -> result = result.replace("%" + (index + 1), value.toString()) }
    return result
}

// ---------- The settings window ----------
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = OtcPanel,
        title = { Text(tr("Settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Theme"), style = MaterialTheme.typography.labelLarge, color = OtcMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Choice(tr("Dark"), Look.dark) { Look.dark = true; Look.save() }
                    Choice(tr("Light"), !Look.dark) { Look.dark = false; Look.save() }
                }
                Text(tr("Accent color"), style = MaterialTheme.typography.labelLarge, color = OtcMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    accents.forEachIndexed { index, choice ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (Look.dark) choice.onDark else choice.onLight)
                                .then(if (index == Look.accent) Modifier.border(3.dp, OtcText, CircleShape) else Modifier)
                                .clickable { Look.accent = index; Look.save() }
                        )
                    }
                }
                Text(tr("Language"), style = MaterialTheme.typography.labelLarge, color = OtcMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Choice("English", Look.lang == "en") { Look.lang = "en"; Look.save() }
                    Choice("日本語", Look.lang == "ja") { Look.lang = "ja"; Look.save() }
                    Choice("中文", Look.lang == "zh") { Look.lang = "zh"; Look.save() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Done")) } }
    )
}

/** One button of a row of choices; the chosen one is filled. */
@Composable
private fun RowScope.Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val padding = PaddingValues(horizontal = 4.dp)
    if (selected) {
        Button(onClick = onClick, shape = shape, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, shape = shape, contentPadding = padding, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
    }
}

// ---------- The words: English to arrayOf(Japanese, Chinese) ----------
private val words: Map<String, Array<String>> = mapOf(
    "PC server" to arrayOf("PCサーバー", "电脑服务器"),
    "Running (port %1)" to arrayOf("実行中（ポート %1）", "运行中（端口 %1）"),
    "Stopped" to arrayOf("停止中", "已停止"),
    "Could not start the server:\n%1\n\nIs another copy of the server already running on port %2?" to arrayOf("サーバーを起動できませんでした:\n%1\n\nポート %2 で別のサーバーがすでに動いていませんか？", "无法启动服务器:\n%1\n\n端口 %2 上是否已有另一个服务器在运行？"),
    "Stop server" to arrayOf("サーバーを停止", "停止服务器"),
    "Start server" to arrayOf("サーバーを起動", "启动服务器"),
    "Type this IP in the phone app (or tap Find PC):" to arrayOf("このIPをスマホアプリに入力してください（または「PCを探す」をタップ）:", "请在手机应用中输入此 IP（或点“查找电脑”）:"),
    "No network found. Connect to Wi-Fi or turn on the Mobile hotspot." to arrayOf("ネットワークが見つかりません。Wi-Fiに接続するか、モバイルホットスポットをオンにしてください。", "未找到网络。请连接 Wi-Fi 或打开移动热点。"),
    "iPhone / any browser: scan the code or open" to arrayOf("iPhone / ブラウザー：コードを読み取るか、次を開いてください", "iPhone / 任意浏览器：扫描二维码或打开"),
    "In Safari: Share > Add to Home Screen. You still need the access token below." to arrayOf("Safariでは：共有 > ホーム画面に追加。下のアクセストークンも必要です。", "在 Safari 中：共享 > 添加到主屏幕。仍需要下面的访问令牌。"),
    "Wrong address? Use the next one (%1/%2)" to arrayOf("アドレスが違う場合は次へ（%1/%2）", "地址不对？换下一个（%1/%2）"),
    "Access token" to arrayOf("アクセストークン", "访问令牌"),
    "Generate" to arrayOf("生成", "生成"),
    "The token cannot be empty." to arrayOf("トークンを空にすることはできません。", "令牌不能为空。"),
    "Internet access is on, so the token must have at least %1 characters. Turn internet access off first, or press Generate." to arrayOf("インターネットアクセスがオンのため、トークンは %1 文字以上必要です。先にインターネットアクセスをオフにするか、「生成」を押してください。", "互联网访问已开启，令牌至少需要 %1 个字符。请先关闭互联网访问，或按“生成”。"),
    "Apply token" to arrayOf("トークンを適用", "应用令牌"),
    "Internet access (Cloudflare tunnel)" to arrayOf("インターネットアクセス（Cloudflareトンネル）", "互联网访问（Cloudflare 隧道）"),
    "Internet access needs an access token of at least %1 characters, because anyone on the internet could try to guess it.\n\nPress \"Generate\", then \"Apply token\", and enter the new token in your phone app / web page." to arrayOf("インターネットアクセスには %1 文字以上のアクセストークンが必要です。インターネット上の誰でも推測を試せるためです。\n\n「生成」、「トークンを適用」の順に押して、新しいトークンをスマホアプリ / Webページに入力してください。", "互联网访问需要至少 %1 个字符的访问令牌，因为互联网上的任何人都可能尝试猜测。\n\n请依次按“生成”和“应用令牌”，然后在手机应用 / 网页中输入新令牌。"),
    "Could not start the tunnel:\n%1" to arrayOf("トンネルを開始できませんでした:\n%1", "无法启动隧道:\n%1"),
    "Start the server first." to arrayOf("先にサーバーを起動してください。", "请先启动服务器。"),
    "Needs an access token with at least %1 characters (press Generate, then Apply token)." to arrayOf("%1 文字以上のアクセストークンが必要です（「生成」、「トークンを適用」の順に押してください）。", "需要至少 %1 个字符的访问令牌（请依次按“生成”和“应用令牌”）。"),
    "Open on any phone, any network:" to arrayOf("どのスマホ、どのネットワークからでも開けます:", "任何手机、任何网络都可以打开:"),
    "Android app: type %1 in the IP box. You still need the access token." to arrayOf("Androidアプリ：IP欄に %1 を入力してください。アクセストークンも必要です。", "Android 应用：在 IP 栏输入 %1。仍需要访问令牌。"),
    "Copy address" to arrayOf("アドレスをコピー", "复制地址"),
    "Shutdown/restart/scripts are blocked over the internet" to arrayOf("インターネット経由のシャットダウン・再起動・スクリプトはブロックされています", "通过互联网的关机/重启/脚本已被拦截"),
    "Allow shutdown, restart and scripts over the internet" to arrayOf("インターネット経由のシャットダウン・再起動・スクリプトを許可する", "允许通过互联网关机、重启和运行脚本"),
    "Allow viewing this PC's screen from a phone" to arrayOf("スマホからこのPCの画面を見ることを許可する", "允许从手机查看此电脑的屏幕"),
    "A phone is viewing this PC's screen right now" to arrayOf("今、スマホがこのPCの画面を見ています", "现在有手机正在查看此电脑的屏幕"),
    "Anyone who has the address AND the token can control this PC. The address changes every time you turn this on." to arrayOf("アドレスとトークンの両方を持つ人は誰でもこのPCを操作できます。アドレスはオンにするたびに変わります。", "同时拥有地址和令牌的人都可以控制此电脑。每次开启时地址都会改变。"),
    "Automation routines" to arrayOf("自動化ルーティン", "自动化例程"),
    "None loaded" to arrayOf("読み込まれていません", "未加载"),
    "Could not open Notepad. The file is here:\n%1" to arrayOf("メモ帳を開けませんでした。ファイルの場所:\n%1", "无法打开记事本。文件位置:\n%1"),
    "Edit file" to arrayOf("ファイルを編集", "编辑文件"),
    "Reload" to arrayOf("再読み込み", "重新加载"),
    "Recording: %1 actions so far. Press F9 when the task is finished." to arrayOf("記録中：現在 %1 件の操作。作業が終わったら F9 を押してください。", "正在录制：已有 %1 个操作。任务完成后请按 F9。"),
    "Recording starts in %1..." to arrayOf("%1 秒後に記録を開始します...", "%1 秒后开始录制..."),
    "Record a task once (mouse and keyboard) and run it again from your phone." to arrayOf("作業を一度記録（マウスとキーボード）して、スマホからもう一度実行できます。", "把任务录制一次（鼠标和键盘），之后可以从手机再次运行。"),
    "Could not start recording:\n%1" to arrayOf("記録を開始できませんでした:\n%1", "无法开始录制:\n%1"),
    "Recording" to arrayOf("記録中", "录制中"),
    "Record" to arrayOf("記録", "录制"),
    "Activity log" to arrayOf("動作ログ", "活动日志"),
    "Clear log" to arrayOf("ログを消去", "清除日志"),
    "Nothing was recorded" to arrayOf("何も記録されませんでした", "没有录制到任何内容"),
    "Save this routine?" to arrayOf("このルーティンを保存しますか？", "保存这个例程吗？"),
    "No mouse or keyboard action arrived before F9 was pressed." to arrayOf("F9 が押されるまでに、マウスやキーボードの操作はありませんでした。", "在按下 F9 之前没有任何鼠标或键盘操作。"),
    "%1 actions, about %2 seconds. It will appear in the Routines list of the remote apps." to arrayOf("%1 件の操作、約 %2 秒。リモートアプリのルーティン一覧に表示されます。", "%1 个操作，约 %2 秒。它会出现在遥控应用的例程列表中。"),
    "Name of the routine" to arrayOf("ルーティンの名前", "例程名称"),
    "Could not save the routine:\n%1" to arrayOf("ルーティンを保存できませんでした:\n%1", "无法保存例程:\n%1"),
    "Save" to arrayOf("保存", "保存"),
    "Discard" to arrayOf("破棄", "放弃"),
    "Download cloudflared?" to arrayOf("cloudflared をダウンロードしますか？", "下载 cloudflared 吗？"),
    "Internet access needs the free program \"cloudflared\" from Cloudflare (about 55 MB).\n\nIt will be downloaded from github.com/cloudflare/cloudflared and saved in your user folder (.onetouch). Download it now?" to arrayOf("インターネットアクセスには、Cloudflareの無料プログラム「cloudflared」（約55 MB）が必要です。\n\ngithub.com/cloudflare/cloudflared からダウンロードして、ユーザーフォルダー（.onetouch）に保存します。今すぐダウンロードしますか？", "互联网访问需要 Cloudflare 的免费程序“cloudflared”（约 55 MB）。\n\n它将从 github.com/cloudflare/cloudflared 下载，并保存到你的用户文件夹（.onetouch）。现在下载吗？"),
    "Download failed:\n%1" to arrayOf("ダウンロードに失敗しました:\n%1", "下载失败:\n%1"),
    "Download" to arrayOf("ダウンロード", "下载"),
    "Cancel" to arrayOf("キャンセル", "取消"),
    "Notice" to arrayOf("お知らせ", "提示"),
    "OK" to arrayOf("OK", "确定"),
    "One Touch Controller - running" to arrayOf("One Touch Controller - 実行中", "One Touch Controller - 运行中"),
    "One Touch Controller - stopped" to arrayOf("One Touch Controller - 停止中", "One Touch Controller - 已停止"),
    "Open window" to arrayOf("ウィンドウを開く", "打开窗口"),
    "Exit" to arrayOf("終了", "退出"),
    "Still running in the system tray. Right-click the icon to exit." to arrayOf("システムトレイで動作中です。終了するにはアイコンを右クリックしてください。", "仍在系统托盘中运行。右键单击图标可退出。"),
    "Off" to arrayOf("オフ", "关闭"),
    "Online" to arrayOf("オンライン", "在线"),
    "Connecting..." to arrayOf("接続中...", "正在连接..."),
    "Starting the tunnel..." to arrayOf("トンネルを開始しています...", "正在启动隧道..."),
    "Downloading cloudflared..." to arrayOf("cloudflared をダウンロード中...", "正在下载 cloudflared..."),
    "Still waiting for an address. Check the PC's internet connection." to arrayOf("アドレスを待っています。PCのインターネット接続を確認してください。", "仍在等待地址。请检查电脑的互联网连接。"),
    "Settings" to arrayOf("設定", "设置"),
    "Theme" to arrayOf("テーマ", "主题"),
    "Dark" to arrayOf("ダーク", "深色"),
    "Light" to arrayOf("ライト", "浅色"),
    "Accent color" to arrayOf("アクセントカラー", "主题色"),
    "Language" to arrayOf("言語", "语言"),
    "Done" to arrayOf("完了", "完成"),
    "Guard mode" to arrayOf("ガードモード", "守护模式"),
    "On: the PC's own mouse and keyboard are blocked. Your phone still works." to arrayOf("オン：PC本体のマウスとキーボードはブロックされています。スマホからの操作は使えます。", "开启：电脑本身的鼠标和键盘已被锁定。手机仍可操作。"),
    "Off. Turn it on from your phone when you leave the PC." to arrayOf("オフ。PCから離れるときにスマホからオンにしてください。", "关闭。离开电脑时请用手机开启。"),
    "Set a Guard password first." to arrayOf("先にガードパスワードを設定してください。", "请先设置守护密码。"),
    "Turn on" to arrayOf("オンにする", "开启"),
    "Turn off" to arrayOf("オフにする", "关闭"),
    "Guard password" to arrayOf("ガードパスワード", "守护密码"),
    "New Guard password" to arrayOf("新しいガードパスワード", "新的守护密码"),
    "Save password" to arrayOf("パスワードを保存", "保存密码"),
    "Guard password saved." to arrayOf("ガードパスワードを保存しました。", "守护密码已保存。"),
    "Letters and numbers, 4 to 32, capital letters do not matter. Anyone at the PC must type it to unlock. Ctrl+Alt+Del and holding the power button cannot be blocked by any program." to arrayOf("英字と数字で4～32文字。大文字・小文字は区別しません。PCの前にいる人はロック解除にこのパスワードが必要です。Ctrl+Alt+Delと電源ボタンの長押しは、どのプログラムでもブロックできません。", "字母和数字，4～32 位，不区分大小写。在电脑前的人必须输入它才能解锁。任何程序都无法拦截 Ctrl+Alt+Del 和长按电源键。"),
    "Use 4 to 32 letters and numbers only." to arrayOf("英字と数字のみで4～32文字にしてください。", "请只使用 4～32 位字母和数字。"),
    "Set a guard password in the PC window first" to arrayOf("先にPCのウィンドウでガードパスワードを設定してください", "请先在电脑窗口中设置守护密码"),
    "Guard mode works on Windows only" to arrayOf("ガードモードはWindowsでのみ使えます", "守护模式只能在 Windows 上使用"),
    "Guard mode could not start" to arrayOf("ガードモードを開始できませんでした", "无法启动守护模式"),
    "This PC is locked" to arrayOf("このPCはロックされています", "此电脑已锁定"),
    "It is being used remotely with One Touch Controller." to arrayOf("One Touch Controllerでリモート操作中です。", "正在通过 One Touch Controller 远程使用。"),
    "Type the Guard password and press Enter." to arrayOf("ガードパスワードを入力してEnterを押してください。", "请输入守护密码并按 Enter。"),
    "Too many wrong passwords. Wait %1 seconds." to arrayOf("パスワードの間違いが多すぎます。%1 秒お待ちください。", "密码错误次数过多，请等待 %1 秒。"),
    "Checking..." to arrayOf("確認中...", "正在确认..."),
    "Wrong password" to arrayOf("パスワードが違います", "密码错误"),
    "Windows locked the PC, so Guard mode turned itself off." to arrayOf("Windowsがロックされたため、ガードモードは自動でオフになりました。", "Windows 已锁定电脑，守护模式已自动关闭。"),
    "Home" to arrayOf("ホーム", "主页"),
    "Internet" to arrayOf("インターネット", "互联网"),
    "Routines" to arrayOf("ルーティン", "例程"),
    "Security" to arrayOf("セキュリティ", "安全"),
    "Log" to arrayOf("ログ", "日志"),
    "Run a routine here or from your phone." to arrayOf("ここまたはスマホからルーティンを実行できます。", "可以在这里或手机上运行例程。"),
    "No routines yet. Press \"Edit file\" to write one, or record a task below." to arrayOf("ルーティンはまだありません。「ファイルを編集」で書くか、下で作業を記録してください。", "还没有例程。请按“编辑文件”编写，或在下方录制任务。"),
    "%1 steps" to arrayOf("%1 ステップ", "%1 个步骤"),
    "Running..." to arrayOf("実行中...", "运行中..."),
    "Run" to arrayOf("実行", "运行"),
    "Record a task" to arrayOf("作業を記録", "录制任务"),
    "Record a task once (mouse and keyboard) and run it again from here or from your phone." to arrayOf("作業を一度記録（マウスとキーボード）して、ここまたはスマホからもう一度実行できます。", "把任务录制一次（鼠标和键盘），之后可以在这里或手机上再次运行。"),
    "Run \"%1\" on this PC? It contains shutdown, restart or script steps." to arrayOf("このPCで「%1」を実行しますか？シャットダウン、再起動、またはスクリプトの手順が含まれています。", "在此电脑上运行“%1”吗？它包含关机、重启或脚本步骤。"),
    "Are you sure?" to arrayOf("実行しますか？", "确定吗？"),
    "Delete \"%1\"?" to arrayOf("「%1」を削除しますか？", "删除“%1”吗？"),
    "The routine and its recording are deleted. This cannot be undone." to arrayOf("ルーティンとその記録が削除されます。元に戻せません。", "例程及其录制内容将被删除。此操作无法撤销。"),
    "The routine is removed from the routines file. This cannot be undone." to arrayOf("ルーティンがルーティンファイルから削除されます。元に戻せません。", "例程将从例程文件中删除。此操作无法撤销。"),
    "Delete" to arrayOf("削除", "删除"),
    "Guard mode is on" to arrayOf("ガードモード中です", "守护模式已开启"),
    "Message from the owner's phone" to arrayOf("持ち主のスマホからのメッセージ", "来自主人手机的消息"),
    "Click to close" to arrayOf("クリックで閉じる", "点击关闭"),
    "Recording: %1 actions so far. Press F9 or Stop when the task is finished." to arrayOf("記録中：現在 %1 件の操作。作業が終わったら F9 か「停止」を押してください。", "正在录制：已有 %1 个操作。任务完成后请按 F9 或“停止”。"),
    "Stop" to arrayOf("停止", "停止")
)