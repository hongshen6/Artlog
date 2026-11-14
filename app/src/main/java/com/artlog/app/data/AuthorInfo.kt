package com.artlog.app.data

/**
 * 作者介绍集中配置（最终完善轮）。
 *
 * 这是应用的静态公开内容，不是当前设备用户的本地个人资料：
 * - 不进 SharedPreferences、不进数据库、不进用户个人数据备份；
 * - 没有给普通下载者提供的修改入口；
 * - 不上传到任何网络。
 *
 * 内容来源：用户 2026-11-14 01:02 提供的确认内容（网名、联系方式
 * 邮箱，逐字写入，不得改写、缩写、意译）。开发初衷全文与创作理念
 * 全文自 versionCode 23 起移至项目 README.md 末尾附录，不再由本配置持有。
 *
 * 未提供的内容以 null 表示，渲染层按 null 隐藏对应块，绝不显示 null、
 * 虚构信息或临时占位文字：
 * - 个人简介：未提供（[biography] 为 null），不展示该节；绝不用
 *   开发初衷文字代替个人简介（初衷全文见 README.md 末尾附录）。
 * - GitHub 仓库地址：已提供（versionCode 28 起，首个公开版本）。
 * - 开源许可证：已提供（versionCode 28 起，自定义非商业许可）。
 *
 * [AuthorInfo.sections] 是作者页的唯一节来源：[com.artlog.app.ui.screens]
 * 的 AuthorPage 只负责渲染，从这里取文案，不再往 SettingsScreen.kt 里
 * 追加长文案。开源发布后只需改这里一处（填入真实仓库地址与许可证），
 * 再随新版本发布；补充内容不会自动出现。
 *
 * 纯 Kotlin，无 Android 依赖，可由 JVM 测试直接编译验证。
 */
object AuthorInfo {

    /** 作者署名（网名），用户确认公开。 */
    const val displayName: String = "飞舞网络卡死了"

    /** 联系方式邮箱，用户明确同意公开，用于 Bug 反馈。仅展示，不建反馈中心（P3 范围）。 */
    const val contactEmail: String = "3569694301@qq.com"

    /** GitHub 仓库地址：首个公开版本（versionCode 28）起提供。非 null 时才由渲染层展示。 */
    val githubUrl: String? = "https://github.com/hongshen6/Artlog"

    /** 开源许可证：首个公开版本（versionCode 28）起提供。非 null 时才由渲染层展示。 */
    val license: String? = "Artlog Non-Commercial License, Version 1.0"

    /** 个人简介：用户未单独提供内容。非 null 时才由渲染层展示该节。 */
    val biography: String? = null

    /** 作品与版权：沿用既有文案，不改写（审计报告 B7 原文）。 */
    const val worksCopyrightBody: String = "Artlog 本身不主张拥有用户导入的绘画作品、音乐文件、歌词文件或其他个人内容的版权。" +
        "用户应确保自己导入、使用和分享的内容具有相应的合法权利。" +
        "Artlog 中的用户作品和个人数据应由用户自行管理。"

    /** 第三方资源：沿用既有文案，不改写（审计报告 B7 原文）。 */
    const val thirdPartyBody: String = "应用中的第三方网站入口仅用于导航访问。" +
        "第三方网站的内容、商标、图片和其他资源归各自权利人所有。" +
        "Artlog 不应被描述为这些第三方网站的官方合作产品，除非确有书面合作依据。"

    /**
     * 关于开源：项目已在 GitHub 公开，仓库地址与许可证见本页“开源信息”
     * 一节。许可证为自定义非商业许可，不是 OSI 批准的标准开源许可证。
     */
    const val openSourceBody: String = "项目已在 GitHub 公开，仓库地址与许可证见本页“开源信息”一节。" +
        "许可证为自定义非商业许可（Artlog Non-Commercial License, Version 1.0），" +
        "不是 OSI 批准的标准开源许可证，商业使用须经作者明确书面许可。"

    /** 版本与声明后半句：沿用既有写法，不夸大法律效力。 */
    const val versionDisclaimerSuffix: String = "本页是项目说明，不是具有法律效力的专业法律文件；" +
        "如有疑问，以作者后续发布的正式说明为准。"

    /** GitHub 信息是否可展示：仅当仓库地址已提供。 */
    val hasGithubInfo: Boolean get() = githubUrl != null

    /** 许可证信息是否可展示：仅当许可证已提供。 */
    val hasLicenseInfo: Boolean get() = license != null

    /** 个人简介节是否可展示：仅当个人简介已提供。 */
    val hasBiography: Boolean get() = biography != null

    /** 作者页上的一节（标题 + 正文），供渲染层逐节绘制。 */
    data class Section(val title: String, val body: String)

    /**
     * 作者页的节清单，按任务书结构排序：
     * 作者署名 / 联系方式 /
     * 作品与版权 / 第三方资源 / 关于开源 / 版本与声明。
     *
     * （开发初衷与创作理念两节自 versionCode 23 起移除，全文见项目
     * README.md 末尾附录。）
     *
     * 未提供的内容不产生节：个人简介、GitHub、许可证在对应字段为
     * null 时不出现（不显示 null、不显示占位文字）。将来提供后，
     * 个人简介节插在“作者署名”之后，开源信息节插在“关于开源”之后。
     *
     * @param versionText 版本行，如“版本 1.0.0（28）”，由调用方从真实
     *   PackageInfo 取值传入，本对象不写死版本号。
     */
    fun sections(versionText: String): List<Section> {
        val list = mutableListOf(
            Section("作者署名", displayName),
            Section("联系方式", contactEmail)
        )
        if (hasBiography) {
            // 个人简介未提供时此分支不执行，该节不渲染。
            list += Section("个人简介", biography!!)
        }
        list += Section("作品与版权", worksCopyrightBody)
        list += Section("第三方资源", thirdPartyBody)
        if (hasGithubInfo || hasLicenseInfo) {
            // GitHub 地址与许可证均未提供时此分支不执行，不渲染该节。
            val openSourceInfo = listOfNotNull(
                githubUrl?.let { "仓库地址：$it" },
                license?.let { "许可证：$it" }
            ).joinToString("\n")
            list += Section("开源信息", openSourceInfo)
        }
        list += Section("关于开源", openSourceBody)
        list += Section("版本与声明", "$versionText。$versionDisclaimerSuffix")
        return list
    }
}
