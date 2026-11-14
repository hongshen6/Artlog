package com.artlog.app.data

/**
 * 视觉灵感网站目录（Artlog 2.0 导航改造）：固定官方目录的本地静态配置。
 *
 * 依据：《视觉灵感网站目录》v1（visual-inspiration-directory.md）与
 * 《视觉灵感网站导航架构设计》§3.1。只收录 A 级（正式收录）网站；
 * B/C/D 级不进本表，只在核查报告与目录文档中留档，获书面确认后
 * 由开发者随发版加入。
 *
 * 规则（与目录文档一致）：
 * - 每个网站一个稳定 slug ID，全分类共用；同一网站属多分类时不
 *   重复建记录，收藏状态按 ID 全局一致。
 * - 只存首页 URL，不存文章/作品深层链接；点击后由系统浏览器打开。
 * - 简介一句话、Artlog 自写，不复制对方站点的文案、标题或摘要。
 * - Artlog 不抓取、不收录标题/作者/摘要，不展示第三方图片。
 * - 分类以目录文件为准：中国摄影在线（cphoto）只属情绪摄影，
 *   不属写实插画（任务书 §4.2 摘要有误，以目录文件为准，见
 *   stage0-check.md §6）。
 */

/** 目录收录等级：仅 A 进入正式目录静态配置。 */
enum class DirectoryGrade {
    A, B, C, D
}

/** 一个官方目录网站。 */
data class VisualWebsite(
    /** 稳定 slug，全分类共用，如 "chinachahua"。 */
    val id: String,
    /** 官方名称。 */
    val name: String,
    /** 一句话简介（Artlog 自写，不复制对方文案）。 */
    val description: String,
    /** 网站类型：插画资讯媒体 / 设计媒体 / 摄影资讯门户 / 杂志官网 / 建筑媒体等。 */
    val type: String,
    /** 官方首页 URL（仅首页，不存深层链接）。 */
    val homepageUrl: String,
    /** 所属分类（复用现有五分类枚举；多分类用 Set 表达）。 */
    val categories: Set<InspirationCategory>,
    /** 收录等级：本表只含 A。 */
    val grade: DirectoryGrade,
)

/** 固定官方目录（A 级，首版 5 家）。 */
object VisualDirectory {
    /**
     * 目录版本号（人可读的变更记录，不参与任何运行时逻辑）。
     * 目录更新规则（下一阶段迭代，数据库 v6）：
     * ① slug 一经发布永不复用（改名 = 新 slug + 旧 slug 退役，旧覆盖
     *    行随旧 slug 失效，不误删）；
     * ② DIRECTORY_VERSION 每次增删改目录 +1。
     */
    const val DIRECTORY_VERSION = 1

    val sites: List<VisualWebsite> = listOf(
        VisualWebsite(
            id = "chinachahua",
            name = "中国插画艺术网",
            description = "专业插画资讯媒体，刊登年鉴作品选登、获奖作品与画家专访。",
            type = "插画资讯媒体",
            homepageUrl = "https://chinachahua.net/",
            categories = setOf(InspirationCategory.ANIME, InspirationCategory.REALISTIC),
            grade = DirectoryGrade.A,
        ),
        VisualWebsite(
            id = "uisdc",
            name = "优设",
            description = "设计媒体与学习平台，提供设计文章、教程与灵感素材。",
            type = "设计媒体",
            homepageUrl = "https://www.uisdc.com/",
            categories = setOf(InspirationCategory.REALISTIC),
            grade = DirectoryGrade.A,
        ),
        VisualWebsite(
            id = "cphoto",
            name = "中国摄影在线",
            description = "摄影资讯门户，刊载理论评论、摄影大家与国际沙龙获奖作品。",
            type = "摄影资讯门户",
            homepageUrl = "https://www.cphoto.net/",
            categories = setOf(InspirationCategory.PHOTOGRAPHY),
            grade = DirectoryGrade.A,
        ),
        VisualWebsite(
            id = "pop-photo",
            name = "大众摄影",
            description = "《大众摄影》杂志官网，发布影赛征稿、作品、展讯与俱乐部活动。",
            type = "杂志官网",
            homepageUrl = "https://www.pop-photo.com.cn/",
            categories = setOf(InspirationCategory.PHOTOGRAPHY),
            grade = DirectoryGrade.A,
        ),
        VisualWebsite(
            id = "archdaily-cn",
            name = "ArchDaily 中文站",
            description = "国际建筑媒体中文版，收录全球建成项目、新闻与竞赛。",
            type = "建筑媒体",
            homepageUrl = "https://www.archdaily.cn/",
            categories = setOf(InspirationCategory.ARCHITECTURE),
            grade = DirectoryGrade.A,
        ),
    )

    /** 该分类下的正式收录网站（按 sites 列表顺序）。 */
    fun sitesFor(category: InspirationCategory): List<VisualWebsite> =
        sites.filter { category in it.categories }

    /** 按稳定 slug 取网站；不存在返回 null。 */
    fun siteById(id: String): VisualWebsite? =
        sites.find { it.id == id }
}
