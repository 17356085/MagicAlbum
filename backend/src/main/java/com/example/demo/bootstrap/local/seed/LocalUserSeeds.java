package com.example.demo.bootstrap.local.seed;

import java.util.List;

public final class LocalUserSeeds {
    private LocalUserSeeds() {
    }

    public static List<UserSeed> build() {
        return List.of(
                new UserSeed(1, "akari_frames", "明里", "上海", "专门记录作画和分镜，周末会去拍展会。", "https://bluealbum.local/u/akari_frames", "https://music.example/akari", "akari.frames@example.com", "13900000001"),
                new UserSeed(2, "ren_manga", "阿莲", "北京", "喜欢把漫画分镜、角色成长和实体装帧放在一起聊。", "https://bluealbum.local/u/ren_manga", "https://book.example/ren", "ren.manga@example.com", "13900000002"),
                new UserSeed(3, "sora_cv", "空汐", "广州", "声优广播重度爱好者，也会记现场朗读会感受。", "https://bluealbum.local/u/sora_cv", "https://radio.example/sora", "sora.cv@example.com", "13900000003"),
                new UserSeed(4, "yuki_songbook", "悠纪", "成都", "每季都会做一份 ACG 歌单，偏爱配乐和主题曲关联。", "https://bluealbum.local/u/yuki_songbook", "https://playlist.example/yuki", "yuki.songbook@example.com", "13900000004"),
                new UserSeed(5, "plamo_kai", "凯文", "深圳", "高达和民用模型都玩，最近在研究旧化和补土。", "https://bluealbum.local/u/plamo_kai", "https://model.example/kai", "plamo.kai@example.com", "13900000005"),
                new UserSeed(6, "mika_cut", "未夏", "杭州", "喜欢把动画镜头语言和角色状态联系起来写。", "https://bluealbum.local/u/mika_cut", "https://notes.example/mika", "mika.cut@example.com", "13900000006"),
                new UserSeed(7, "grid_start", "格里德", "南京", "赛历党，常年追 F1、WEC 和房车赛。", "https://bluealbum.local/u/grid_start", "https://motorsport.example/grid", "grid.start@example.com", "13900000007"),
                new UserSeed(8, "apex_lap", "阿策", "武汉", "喜欢看车队策略和轮胎窗口，偶尔也跑模拟器。", "https://bluealbum.local/u/apex_lap", "https://lap.example/apex", "apex.lap@example.com", "13900000008"),
                new UserSeed(9, "wec_night", "夜航", "苏州", "对耐力赛夜赛段和长距离策略特别着迷。", "https://bluealbum.local/u/wec_night", "https://wec.example/night", "wec.night@example.com", "13900000009"),
                new UserSeed(10, "rally_note", "拉力手账", "重庆", "山路、砂石和赛段复盘是我的快乐来源。", "https://bluealbum.local/u/rally_note", "https://rally.example/note", "rally.note@example.com", "13900000010"),
                new UserSeed(11, "simbox_chen", "陈弯心", "天津", "方向盘和踏板升级记录很多，也会分享练习方法。", "https://bluealbum.local/u/simbox_chen", "https://sim.example/chen", "simbox.chen@example.com", "13900000011"),
                new UserSeed(12, "f1_pitwall", "围场墙", "西安", "主要写正赛前瞻、围场流言和技术升级观察。", "https://bluealbum.local/u/f1_pitwall", "https://pitwall.example/f1", "f1.pitwall@example.com", "13900000012"),
                new UserSeed(13, "noodle_map", "阿面", "广州", "一到新城市就会先看面馆和夜宵摊。", "https://bluealbum.local/u/noodle_map", "https://food.example/noodle", "noodle.map@example.com", "13900000013"),
                new UserSeed(14, "pantry_diary", "厨房日志", "厦门", "喜欢把家常菜拆成可复制的流程和时间轴。", "https://bluealbum.local/u/pantry_diary", "https://cook.example/pantry", "pantry.diary@example.com", "13900000014"),
                new UserSeed(15, "brew_sweet", "糖霜", "长沙", "咖啡和甜点双修，乐于记录豆子与奶油的平衡。", "https://bluealbum.local/u/brew_sweet", "https://dessert.example/brew", "brew.sweet@example.com", "13900000015"),
                new UserSeed(16, "spice_route", "香料航线", "西安", "特别喜欢地方风味和食材迁徙带来的差异。", "https://bluealbum.local/u/spice_route", "https://foodmap.example/spice", "spice.route@example.com", "13900000016"),
                new UserSeed(17, "late_snack", "夜宵研究所", "沈阳", "专攻夜市、烧烤和高性价比小馆子。", "https://bluealbum.local/u/late_snack", "https://night.example/snack", "late.snack@example.com", "13900000017"),
                new UserSeed(18, "bento_week", "便当周报", "福州", "研究一周备餐和厨房动线，喜欢把步骤压缩清楚。", "https://bluealbum.local/u/bento_week", "https://mealprep.example/bento", "bento.week@example.com", "13900000018"),
                new UserSeed(19, "paper_ink", "纸墨", "北京", "偏爱中文小说和散文，会写阅读余温。", "https://bluealbum.local/u/paper_ink", "https://literature.example/paper", "paper.ink@example.com", "13900000019"),
                new UserSeed(20, "mystery_lamp", "谜灯", "上海", "推理小说读者，热衷结构和伏笔回收。", "https://bluealbum.local/u/mystery_lamp", "https://mystery.example/lamp", "mystery.lamp@example.com", "13900000020"),
                new UserSeed(21, "verse_cloud", "云诗", "杭州", "诗歌和摄影一起看时，总会有新感觉。", "https://bluealbum.local/u/verse_cloud", "https://poem.example/cloud", "verse.cloud@example.com", "13900000021"),
                new UserSeed(22, "booktide", "书潮", "青岛", "会做年度阅读地图，也喜欢比较译本差异。", "https://bluealbum.local/u/booktide", "https://book.example/tide", "booktide@example.com", "13900000022"),
                new UserSeed(23, "suspense_fold", "折页", "合肥", "悬疑和非虚构来回切换，喜欢读完就复盘。", "https://bluealbum.local/u/suspense_fold", "https://read.example/fold", "suspense.fold@example.com", "13900000023"),
                new UserSeed(24, "reading_north", "北页", "长春", "喜欢冬天读长篇小说，也会做摘录和主题整理。", "https://bluealbum.local/u/reading_north", "https://notes.example/north", "reading.north@example.com", "13900000024"),
                new UserSeed(25, "prime_50mm", "阿焦", "成都", "50mm 定焦用户，偏爱街头人像和夜景。", "https://bluealbum.local/u/prime_50mm", "https://photo.example/prime", "prime.50mm@example.com", "13900000025"),
                new UserSeed(26, "filmgrain_lu", "鹿粒", "大连", "胶片相机和冲扫流程都是我的长期兴趣。", "https://bluealbum.local/u/filmgrain_lu", "https://film.example/lu", "filmgrain.lu@example.com", "13900000026"),
                new UserSeed(27, "tripod_wei", "未栈", "昆明", "风光摄影党，执着于清晨和傍晚的色温变化。", "https://bluealbum.local/u/tripod_wei", "https://landscape.example/wei", "tripod.wei@example.com", "13900000027"),
                new UserSeed(28, "street_echo", "街声", "香港", "喜欢走路拍城市，记录路口、霓虹和人群节奏。", "https://bluealbum.local/u/street_echo", "https://street.example/echo", "street.echo@example.com", "13900000028"),
                new UserSeed(29, "lightmeter_j", "J Meter", "台北", "测光表和镜头镀膜都能聊很久。", "https://bluealbum.local/u/lightmeter_j", "https://gear.example/j", "lightmeter.j@example.com", "13900000029"),
                new UserSeed(30, "rawcurve", "曲线君", "宁波", "更偏爱后期流程和颜色管理，也拍很多旅行照。", "https://bluealbum.local/u/rawcurve", "https://raw.example/curve", "rawcurve@example.com", "13900000030"),
                new UserSeed(31, "otaku_torque", "扭矩宅", "珠海", "白天看比赛晚上补番，对跨圈子话题很有兴趣。", "https://bluealbum.local/u/otaku_torque", "https://mix.example/torque", "otaku.torque@example.com", "13900000031"),
                new UserSeed(32, "kitchen_street", "街角厨房", "汕头", "地方小吃和家庭厨房都爱，擅长拆味型。", "https://bluealbum.local/u/kitchen_street", "https://taste.example/street", "kitchen.street@example.com", "13900000032"),
                new UserSeed(33, "page_and_frame", "页与帧", "济南", "文学、摄影和动画会交叉着看。", "https://bluealbum.local/u/page_and_frame", "https://cross.example/pageframe", "page.and.frame@example.com", "13900000033"),
                new UserSeed(34, "cafe_paddock", "围场咖啡", "澳门", "上午聊排位赛，下午聊手冲和甜点。", "https://bluealbum.local/u/cafe_paddock", "https://cafe.example/paddock", "cafe.paddock@example.com", "13900000034"),
                new UserSeed(35, "lens_and_ink", "镜墨", "南昌", "会把读书摘录变成拍摄主题，喜欢慢慢打磨。", "https://bluealbum.local/u/lens_and_ink", "https://ink.example/lens", "lens.and.ink@example.com", "13900000035"),
                new UserSeed(36, "night_aperture", "夜圈", "兰州", "夜景摄影和深夜食堂是生活里的两个稳定锚点。", "https://bluealbum.local/u/night_aperture", "https://night.example/aperture", "night.aperture@example.com", "13900000036")
        );
    }
}
