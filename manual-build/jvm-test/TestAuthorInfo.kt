// JVM test for the author info — NOT part of the app build
// (lives under manual-build/jvm-test, never shipped). Compiles the real
// data-layer source AuthorInfo.kt with kotlinc and runs against pure
// Kotlin only: no device, no Android runtime, no SharedPreferences, no
// Room, no network. The expected strings below are typed from the
// user-confirmed text of 2026-11-14 01:02, so any drift between the
// config and the user's own words fails here.
//
// Covered:
//  1. Config fields equal the user-confirmed values verbatim
//     (display name, contact email).
//     (Origin story / creative philosophy moved to the README appendix
//     in versionCode 23; they are no longer AuthorInfo fields.)
//  2. GitHub URL and licence are provided since the first public release
//     (versionCode 28, v1.0.0), and the show flags are true, so the
//     render layer shows the open-source info section; biography is still
//     null (not provided) and stays hidden.
//  3. sections(): exactly the seven sections, in order; the open-source
//     info section carries the repository address and licence name; the
//     open-source section says the project is published and the licence
//     is not OSI-approved; no "null" text rendered; the old
//     "作者信息暂未公开。" placeholder is gone; the version section
//     carries the caller-supplied real version text and the
//     no-legal-force disclaimer.
package com.artlog.app.data

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}


fun main() {
    // 1. Verbatim user-confirmed values.
    check("display name is the user-confirmed net name",
        AuthorInfo.displayName == "飞舞网络卡死了")
    check("contact email is the user-confirmed public email",
        AuthorInfo.contactEmail == "3569694301@qq.com")

    // 2. Published since v1.0.0 (versionCode 28); biography still not provided.
    check("GitHub URL is the published repository address",
        AuthorInfo.githubUrl == "https://github.com/hongshen6/Artlog")
    check("licence is the custom non-commercial licence name",
        AuthorInfo.license == "Artlog Non-Commercial License, Version 1.0")
    check("biography is null (not provided)",
        AuthorInfo.biography == null)
    check("GitHub block is shown when URL is provided",
        AuthorInfo.hasGithubInfo)
    check("licence block is shown when licence is provided",
        AuthorInfo.hasLicenseInfo)
    check("biography section is hidden when biography is null",
        !AuthorInfo.hasBiography)

    // 3. sections(): order, contents, hiding.
    val sections = AuthorInfo.sections("版本 1.0.0（28）")
    val titles = sections.map { it.title }
    check("sections are exactly the seven sections in order",
        titles == listOf(
            "作者署名", "联系方式",
            "作品与版权", "第三方资源", "开源信息", "关于开源", "版本与声明"
        ))
    check("author name section shows the net name",
        sections[0].body == "飞舞网络卡死了")
    check("contact section shows the public email",
        sections[1].body == "3569694301@qq.com")
    check("works-copyright section keeps the existing wording",
        sections[2].body ==
            "Artlog 本身不主张拥有用户导入的绘画作品、音乐文件、歌词文件或其他个人内容的版权。" +
            "用户应确保自己导入、使用和分享的内容具有相应的合法权利。" +
            "Artlog 中的用户作品和个人数据应由用户自行管理。")
    check("third-party section keeps the existing wording",
        sections[3].body ==
            "应用中的第三方网站入口仅用于导航访问。" +
            "第三方网站的内容、商标、图片和其他资源归各自权利人所有。" +
            "Artlog 不应被描述为这些第三方网站的官方合作产品，除非确有书面合作依据。")
    check("open-source info section shows the repository address and licence name",
        sections[4].body.contains("仓库地址：https://github.com/hongshen6/Artlog") &&
            sections[4].body.contains("许可证：Artlog Non-Commercial License, Version 1.0"))
    check("open-source section says the project is published and the licence is not OSI-approved",
        sections[5].body.contains("项目已在 GitHub 公开") &&
            sections[5].body.contains("不是 OSI 批准的标准开源许可证"))
    check("open-source section no longer says not yet released",
        !sections[5].body.contains("项目尚未正式发布"))
    check("version section carries the caller-supplied real version text",
        sections[6].body.startsWith("版本 1.0.0（28）。"))
    check("version section keeps the no-legal-force disclaimer",
        sections[6].body.contains("本页是项目说明，不是具有法律效力的专业法律文件"))
    check("no biography section is rendered",
        titles.none { it.contains("个人简介") || it.contains("简介") })
    check("open-source info section is rendered",
        titles.contains("开源信息"))
    check("no section renders the literal text \"null\"",
        sections.none { it.body.contains("null") })
    check("old placeholder \"作者信息暂未公开。\" is gone",
        sections.none { it.body.contains("作者信息暂未公开") })
    check("no origin-story / creative-philosophy section is rendered",
        titles.none { it == "开发初衷" || it == "创作理念" })
    check("origin story is not reused as a biography substitute",
        !AuthorInfo.hasBiography)

    println("TestAuthorInfo: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
