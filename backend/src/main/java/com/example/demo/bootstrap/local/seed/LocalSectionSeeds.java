package com.example.demo.bootstrap.local.seed;

import java.util.List;

public final class LocalSectionSeeds {
    private LocalSectionSeeds() {
    }

    public static List<SectionSeed> build() {
        return List.of(
                new SectionSeed("动画", "anime", "新番、经典动画与分镜演出讨论", "欢迎围绕作品内容、制作与音乐理性交流"),
                new SectionSeed("漫画", "manga", "漫画连载、单行本与作者风格交流", "剧透请提前标注，推荐附上阅读理由"),
                new SectionSeed("配音与声优", "voice-actor", "声优表现、广播节目与舞台活动讨论", "鼓励整理配音代表作与现场感受"),
                new SectionSeed("ACG 音乐", "acg-music", "动画歌曲、配乐与同人音乐分享", "欢迎附上歌单、版本与舞台链接"),
                new SectionSeed("模型手办", "figures", "手办、GK、拼装模型与收藏展示", "晒图可带上购买渠道与维护经验"),
                new SectionSeed("赛车赛事综合", "motorsport", "综合赛车赛事、规则与赛历讨论", "赛事讨论请尽量补充时间与背景"),
                new SectionSeed("F1 围场", "f1-paddock", "F1 正赛、排位、围场新闻与技术观察", "欢迎数据分析，避免无意义车手对喷"),
                new SectionSeed("勒芒与耐力赛", "endurance-racing", "WEC、勒芒 24 小时与 GT 耐力赛讨论", "可分享车组、策略与夜赛观赛体验"),
                new SectionSeed("拉力与房车", "rally-touring", "WRC、房车赛与多路况驾驶文化", "讨论请尽量带赛事名称与年份"),
                new SectionSeed("模拟赛车", "sim-racing", "方向盘、踏板、赛道练习与联机经验", "欢迎硬件搭配、调校和圈速心得"),
                new SectionSeed("美食探店", "foodie", "城市探店、菜单体验与回访建议", "推荐请附上价格区间和招牌菜"),
                new SectionSeed("家庭料理", "home-cooking", "家常菜、备餐与厨房流程优化", "欢迎分享步骤图和失败经验"),
                new SectionSeed("咖啡与甜点", "coffee-dessert", "咖啡豆、器具、甜点与下午茶文化", "鼓励记录参数、风味与搭配"),
                new SectionSeed("地方风味", "regional-food", "地方菜系、旅行觅食与食材故事", "欢迎把地域背景一起写出来"),
                new SectionSeed("文学阅读", "literature", "小说、散文、诗歌与阅读感受", "长评请尽量区分引用与个人理解"),
                new SectionSeed("推理与悬疑", "mystery", "推理小说、悬疑叙事与结构讨论", "请标注核心剧透内容"),
                new SectionSeed("摄影器材", "photo-gear", "相机、镜头、胶片与后期流程交流", "晒参数时也欢迎讲拍摄意图"),
                new SectionSeed("街头与风光摄影", "street-landscape", "街头、人文、风光与旅行摄影分享", "欢迎原图、构图思路与机位说明")
        );
    }
}
