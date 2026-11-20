package jp.relative.tech.one_touch_controller.application

import jp.relative.tech.one_touch_controller.domain.config.AppLanguage
import jp.relative.tech.one_touch_controller.domain.config.StringTranslator

/**
 * Multi-language translation lookup (English is the key; missing entries fall back to English).
 * `%1`, `%2`, ... are replaced by the extra arguments.
 */
object I18n {
    fun translate(language: AppLanguage, text: String, vararg values: Any?): String {
        var result = if (language == AppLanguage.EN) text else (translations[text]?.get(language) ?: text)
        values.forEachIndexed { index, value ->
            result = result.replace("%" + (index + 1), value.toString())
        }
        return result
    }

    fun forLanguage(language: AppLanguage): StringTranslator =
        StringTranslator { text, values -> translate(language, text, *values) }

    fun dynamic(languageProvider: () -> AppLanguage): StringTranslator =
        StringTranslator { text, values -> translate(languageProvider(), text, *values) }

    private fun entry(ja: String, zh: String): Map<AppLanguage, String> =
        mapOf(AppLanguage.JA to ja, AppLanguage.ZH to zh)

    // ---------- The words: English -> translations by AppLanguage ----------
    private val translations: Map<String, Map<AppLanguage, String>> = mapOf(
        "PC server" to entry("PCサーバー", "电脑服务器"),
        "Running (port %1)" to entry("実行中（ポート %1）", "运行中（端口 %1）"),
        "Stopped" to entry("停止中", "已停止"),
        "Could not start the server:\n%1\n\nIs another copy of the server already running on port %2?" to entry(
            "サーバーを起動できませんでした:\n%1\n\nポート %2 で別のサーバーがすでに動いていませんか？",
            "无法启动服务器:\n%1\n\n端口 %2 上是否已有另一个服务器在运行？"
        ),
        "Stop server" to entry("サーバーを停止", "停止服务器"),
        "Start server" to entry("サーバーを起動", "启动服务器"),
        "Type this IP in the phone app (or tap Find PC):" to entry(
            "このIPをスマホアプリに入力してください（または「PCを探す」をタップ）:",
            "请在手机应用中输入此 IP（或点“查找电脑”）:"
        ),
        "No network found. Connect to Wi-Fi or turn on the Mobile hotspot." to entry(
            "ネットワークが見つかりません。Wi-Fiに接続するか、モバイルホットスポットをオンにしてください。",
            "未找到网络。请连接 Wi-Fi 或打开移动热点。"
        ),
        "iPhone / any browser: scan the code or open" to entry(
            "iPhone / ブラウザー：コードを読み取るか、次を開いてください",
            "iPhone / 任意浏览器：扫描二维码或打开"
        ),
        "In Safari: Share > Add to Home Screen. You still need the access token below." to entry(
            "Safariでは：共有 > ホーム画面に追加。下のアクセストークンも必要です。",
            "在 Safari 中：共享 > 添加到主屏幕。仍需要下面的访问令牌。"
        ),
        "Wrong address? Use the next one (%1/%2)" to entry(
            "アドレスが違う場合は次へ（%1/%2）",
            "地址不对？换下一个（%1/%2）"
        ),
        "Access token" to entry("アクセストークン", "访问令牌"),
        "Generate" to entry("生成", "生成"),
        "The token cannot be empty." to entry("トークンを空にすることはできません。", "令牌不能为空。"),
        "Internet access is on, so the token must have at least %1 characters. Turn internet access off first, or press Generate." to entry(
            "インターネットアクセスがオンのため、トークンは %1 文字以上必要です。先にインターネットアクセスをオフにするか、「生成」を押してください。",
            "互联网访问已开启，令牌至少需要 %1 个字符。请先关闭互联网访问，或按“生成”。"
        ),
        "Apply token" to entry("トークンを適用", "应用令牌"),
        "Internet access (Cloudflare tunnel)" to entry("インターネットアクセス（Cloudflareトンネル）", "互联网访问（Cloudflare 隧道）"),
        "Internet access needs an access token of at least %1 characters, because anyone on the internet could try to guess it.\n\nPress \"Generate\", then \"Apply token\", and enter the new token in your phone app / web page." to entry(
            "インターネットアクセスには %1 文字以上のアクセストークンが必要です。インターネット上の誰でも推測を試せるためです。\n\n「生成」、「トークンを適用」の順に押して、新しいトークンをスマホアプリ / Webページに入力してください。",
            "互联网访问需要至少 %1 个字符的访问令牌，因为互联网上的任何人都可能尝试猜测。\n\n请依次按“生成”和“应用令牌”，然后在手机应用 / 网页中输入新令牌。"
        ),
        "Could not start the tunnel:\n%1" to entry("トンネルを開始できませんでした:\n%1", "无法启动隧道:\n%1"),
        "Start the server first." to entry("先にサーバーを起動してください。", "请先启动服务器。"),
        "Needs an access token with at least %1 characters (press Generate, then Apply token)." to entry(
            "%1 文字以上のアクセストークンが必要です（「生成」、「トークンを適用」の順に押してください）。",
            "需要至少 %1 个字符的访问令牌（请依次按“生成”和“应用令牌”）。"
        ),
        "Open on any phone, any network:" to entry("どのスマホ、どのネットワークからでも開けます:", "任何手机、任何网络都可以打开:"),
        "Android app: type %1 in the IP box. You still need the access token." to entry(
            "Androidアプリ：IP欄に %1 を入力してください。アクセストークンも必要です。",
            "Android 应用：在 IP 栏输入 %1。仍需要访问令牌。"
        ),
        "Copy address" to entry("アドレスをコピー", "复制地址"),
        "Shutdown/restart/scripts are blocked over the internet" to entry(
            "インターネット経由のシャットダウン・再起動・スクリプトはブロックされています",
            "通过互联网的关机/重启/脚本已被拦截"
        ),
        "Allow shutdown, restart and scripts over the internet" to entry(
            "インターネット経由のシャットダウン・再起動・スクリプトを許可する",
            "允许通过互联网关机、重启和运行脚本"
        ),
        "Allow viewing this PC's screen from a phone" to entry(
            "スマホからこのPCの画面を見ることを許可する",
            "允许从手机查看此电脑的屏幕"
        ),
        "A phone is viewing this PC's screen right now" to entry(
            "今、スマホがこのPCの画面を見ています",
            "现在有手机正在查看此电脑的屏幕"
        ),
        "Anyone who has the address AND the token can control this PC. The address changes every time you turn this on." to entry(
            "アドレスとトークンの両方を持つ人は誰でもこのPCを操作できます。アドレスはオンにするたびに変わります。",
            "同时拥有地址和令牌的人都可以控制此电脑。每次开启时地址都会改变。"
        ),
        "Automation routines" to entry("自動化ルーティン", "自动化例程"),
        "None loaded" to entry("読み込まれていません", "未加载"),
        "Could not open Notepad. The file is here:\n%1" to entry(
            "メモ帳を開けませんでした。ファイルの場所:\n%1",
            "无法打开记事本。文件位置:\n%1"
        ),
        "Edit file" to entry("ファイルを編集", "编辑文件"),
        "Reload" to entry("再読み込み", "重新加载"),
        "Recording: %1 actions so far. Press F9 when the task is finished." to entry(
            "記録中：現在 %1 件の操作。作業が終わったら F9 を押してください。",
            "正在录制：已有 %1 个操作。任务完成后请按 F9。"
        ),
        "Recording starts in %1..." to entry("%1 秒後に記録を開始します...", "%1 秒后开始录制..."),
        "Record a task once (mouse and keyboard) and run it again from your phone." to entry(
            "作業を一度記録（マウスとキーボード）して、スマホからもう一度実行できます。",
            "把任务录制一次（鼠标和键盘），之后可以从手机再次运行。"
        ),
        "Could not start recording:\n%1" to entry("記録を開始できませんでした:\n%1", "无法开始录制:\n%1"),
        "Recording" to entry("記録中", "录制中"),
        "Record" to entry("記録", "录制"),
        "Activity log" to entry("動作ログ", "活动日志"),
        "Clear log" to entry("ログを消去", "清除日志"),
        "Nothing was recorded" to entry("何も記録されませんでした", "没有录制到任何内容"),
        "Save this routine?" to entry("このルーティンを保存しますか？", "保存这个例程吗？"),
        "No mouse or keyboard action arrived before F9 was pressed." to entry(
            "F9 が押されるまでに、マウスやキーボードの操作はありませんでした。",
            "在按下 F9 之前没有任何鼠标或键盘操作。"
        ),
        "%1 actions, about %2 seconds. It will appear in the Routines list of the remote apps." to entry(
            "%1 件の操作、約 %2 秒。リモートアプリのルーティン一覧に表示されます。",
            "%1 个操作，约 %2 秒。它会出现在遥控应用的例程列表中。"
        ),
        "Name of the routine" to entry("ルーティンの名前", "例程名称"),
        "Could not save the routine:\n%1" to entry("ルーティンを保存できませんでした:\n%1", "无法保存例程:\n%1"),
        "Save" to entry("保存", "保存"),
        "Discard" to entry("破棄", "放弃"),
        "Download cloudflared?" to entry("cloudflared をダウンロードしますか？", "下载 cloudflared 吗？"),
        "Internet access needs the free program \"cloudflared\" from Cloudflare (about 55 MB).\n\nIt will be downloaded from github.com/cloudflare/cloudflared and saved in your user folder (.onetouch). Download it now?" to entry(
            "インターネットアクセスには、Cloudflareの無料プログラム「cloudflared」（約55 MB）が必要です。\n\ngithub.com/cloudflare/cloudflared からダウンロードして、ユーザーフォルダー（.onetouch）に保存します。今すぐダウンロードしますか？",
            "互联网访问需要 Cloudflare 的免费程序“cloudflared”（约 55 MB）。\n\n它将从 github.com/cloudflare/cloudflared 下载，并保存到你的用户文件夹（.onetouch）。现在下载吗？"
        ),
        "Download failed:\n%1" to entry("ダウンロードに失敗しました:\n%1", "下载失败:\n%1"),
        "Download" to entry("ダウンロード", "下载"),
        "Cancel" to entry("キャンセル", "取消"),
        "Notice" to entry("お知らせ", "提示"),
        "OK" to entry("OK", "确定"),
        "One Touch Controller - running" to entry("One Touch Controller - 実行中", "One Touch Controller - 运行中"),
        "One Touch Controller - stopped" to entry("One Touch Controller - 停止中", "One Touch Controller - 已停止"),
        "Open window" to entry("ウィンドウを開く", "打开窗口"),
        "Exit" to entry("終了", "退出"),
        "Still running in the system tray. Right-click the icon to exit." to entry(
            "システムトレイで動作中です。終了するにはアイコンを右クリックしてください。",
            "仍在系统托盘中运行。右键单击图标可退出。"
        ),
        "Off" to entry("オフ", "关闭"),
        "Online" to entry("オンライン", "在线"),
        "Connecting..." to entry("接続中...", "正在连接..."),
        "Starting the tunnel..." to entry("トンネルを開始しています...", "正在启动隧道..."),
        "Downloading cloudflared..." to entry("cloudflared をダウンロード中...", "正在下载 cloudflared..."),
        "Still waiting for an address. Check the PC's internet connection." to entry(
            "アドレスを待っています。PCのインターネット接続を確認してください。",
            "仍在等待地址。请检查电脑的互联网连接。"
        ),
        "Settings" to entry("設定", "设置"),
        "Theme" to entry("テーマ", "主题"),
        "Dark" to entry("ダーク", "深色"),
        "Light" to entry("ライト", "浅色"),
        "Accent color" to entry("アクセントカラー", "主题色"),
        "Language" to entry("言語", "语言"),
        "Done" to entry("完了", "完成"),
        "Guard mode" to entry("ガードモード", "守护模式"),
        "On: the PC's own mouse and keyboard are blocked. Your phone still works." to entry(
            "オン：PC本体のマウスとキーボードはブロックされています。スマホからの操作は使えます。",
            "开启：电脑本身的鼠标和键盘已被锁定。手机仍可操作。"
        ),
        "Off. Turn it on from your phone when you leave the PC." to entry(
            "オフ。PCから離れるときにスマホからオンにしてください。",
            "关闭。离开电脑时请用手机开启。"
        ),
        "Set a Guard password first." to entry("先にガードパスワードを設定してください。", "请先设置守护密码。"),
        "Turn on" to entry("オンにする", "开启"),
        "Turn off" to entry("オフにする", "关闭"),
        "Guard password" to entry("ガードパスワード", "守护密码"),
        "New Guard password" to entry("新しいガードパスワード", "新的守护密码"),
        "Save password" to entry("パスワードを保存", "保存密码"),
        "Guard password saved." to entry("ガードパスワードを保存しました。", "守护密码已保存。"),
        "Letters and numbers, 4 to 32, capital letters do not matter. Anyone at the PC must type it to unlock. Ctrl+Alt+Del and holding the power button cannot be blocked by any program." to entry(
            "英字と数字で4～32文字。大文字・小文字は区別しません。PCの前にいる人はロック解除にこのパスワードが必要です。Ctrl+Alt+Delと電源ボタンの長押しは、どのプログラムでもブロックできません。",
            "字母和数字，4～32 位，不区分大小写。在电脑前的人必须输入它才能解锁。任何程序都无法拦截 Ctrl+Alt+Del 和长按电源键。"
        ),
        "Use 4 to 32 letters and numbers only." to entry(
            "英字と数字のみで4～32文字にしてください。",
            "请只使用 4～32 位字母和数字。"
        ),
        "Set a guard password in the PC window first" to entry(
            "先にPCのウィンドウでガードパスワードを設定してください",
            "请先在电脑窗口中设置守护密码"
        ),
        "Guard mode works on Windows only" to entry("ガードモードはWindowsでのみ使えます", "守护模式只能在 Windows 上使用"),
        "Guard mode could not start" to entry("ガードモードを開始できませんでした", "无法启动守护模式"),
        "This PC is locked" to entry("このPCはロックされています", "此电脑已锁定"),
        "It is being used remotely with One Touch Controller." to entry(
            "One Touch Controllerでリモート操作中です。",
            "正在通过 One Touch Controller 远程使用。"
        ),
        "Type the Guard password and press Enter." to entry(
            "ガードパスワードを入力してEnterを押してください。",
            "请输入守护密码并按 Enter。"
        ),
        "Too many wrong passwords. Wait %1 seconds." to entry(
            "パスワードの間違いが多すぎます。%1 秒お待ちください。",
            "密码错误次数过多，请等待 %1 秒。"
        ),
        "Checking..." to entry("確認中...", "正在确认..."),
        "Wrong password" to entry("パスワードが違います", "密码错误"),
        "Windows locked the PC, so Guard mode turned itself off." to entry(
            "Windowsがロックされたため、ガードモードは自動でオフになりました。",
            "Windows 已锁定电脑，守护模式已自动关闭。"
        ),
        "Home" to entry("ホーム", "主页"),
        "Internet" to entry("インターネット", "互联网"),
        "Routines" to entry("ルーティン", "例程"),
        "Security" to entry("セキュリティ", "安全"),
        "Log" to entry("ログ", "日志"),
        "Run a routine here or from your phone." to entry(
            "ここまたはスマホからルーティンを実行できます。",
            "可以在这里或手机上运行例程。"
        ),
        "No routines yet. Press \"Edit file\" to write one, or record a task below." to entry(
            "ルーティンはまだありません。「ファイルを編集」で書くか、下で作業を記録してください。",
            "还没有例程。请按“编辑文件”编写，或在下方录制任务。"
        ),
        "%1 steps" to entry("%1 ステップ", "%1 个步骤"),
        "Running..." to entry("実行中...", "运行中..."),
        "Run" to entry("実行", "运行"),
        "Record a task" to entry("作業を記録", "录制任务"),
        "Record a task once (mouse and keyboard) and run it again from here or from your phone." to entry(
            "作業を一度記録（マウスとキーボード）して、ここまたはスマホからもう一度実行できます。",
            "把任务录制一次（鼠标和键盘），之后可以在这里或手机上再次运行。"
        ),
        "Run \"%1\" on this PC? It contains shutdown, restart or script steps." to entry(
            "このPCで「%1」を実行しますか？シャットダウン、再起動、またはスクリプトの手順が含まれています。",
            "在此电脑上运行“%1”吗？它包含关机、重启或脚本步骤。"
        ),
        "Are you sure?" to entry("実行しますか？", "确定吗？"),
        "Delete \"%1\"?" to entry("「%1」を削除しますか？", "删除“%1”吗？"),
        "The routine and its recording are deleted. This cannot be undone." to entry(
            "ルーティンとその記録が削除されます。元に戻せません。",
            "例程及其录制内容将被删除。此操作无法撤销。"
        ),
        "The routine is removed from the routines file. This cannot be undone." to entry(
            "ルーティンがルーティンファイルから削除されます。元に戻せません。",
            "例程将从例程文件中删除。此操作无法撤销。"
        ),
        "Delete" to entry("削除", "删除"),
        "Guard mode is on" to entry("ガードモード中です", "守护模式已开启"),
        "Message from the owner's phone" to entry("持ち主のスマホからのメッセージ", "来自主人手机的消息"),
        "Click to close" to entry("クリックで閉じる", "点击关闭"),
        "Recording: %1 actions so far. Press F9 or Stop when the task is finished." to entry(
            "記録中：現在 %1 件の操作。作業が終わったら F9 か「停止」を押してください。",
            "正在录制：已有 %1 个操作。任务完成后请按 F9 或“停止”。"
        ),
        "Stop" to entry("停止", "停止")
    )
}
