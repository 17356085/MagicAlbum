package com.example.demo.bootstrap;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class LocalSampleDataSeeder implements ApplicationRunner {

    private static final String DEFAULT_PASSWORD = "BlueAlbum123";
    private static final Logger log = LoggerFactory.getLogger(LocalSampleDataSeeder.class);

    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ThreadRepository threadRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalSampleDataSeeder(
            SectionRepository sectionRepository,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ThreadRepository threadRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.sectionRepository = sectionRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.threadRepository = threadRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, Section> sectionsBySlug = ensureSections();
        List<UserSeed> userSeeds = buildUserSeeds();
        Map<String, User> usersByUsername = ensureUsers(userSeeds);
        ensureThreads(sectionsBySlug, usersByUsername, userSeeds);
        log.info(
                "Local sample data ready: sections={}, users={}, threads={}, samplePassword={}",
                sectionRepository.count(),
                userRepository.count(),
                threadRepository.count(),
                DEFAULT_PASSWORD
        );
    }

    private Map<String, Section> ensureSections() {
        Map<String, Section> existingBySlug = new HashMap<>();
        for (Section section : sectionRepository.findAll()) {
            existingBySlug.put(section.getSlug(), section);
        }

        for (SectionSeed seed : buildSectionSeeds()) {
            if (existingBySlug.containsKey(seed.slug())) {
                continue;
            }
            Section section = new Section();
            section.setName(seed.name());
            section.setSlug(seed.slug());
            section.setDescription(seed.description());
            section.setRules(seed.rules());
            section.setVisible(true);
            existingBySlug.put(seed.slug(), sectionRepository.save(section));
        }
        return existingBySlug;
    }

    private Map<String, User> ensureUsers(List<UserSeed> userSeeds) {
        Map<String, User> existingByUsername = new HashMap<>();
        for (User user : userRepository.findAll()) {
            existingByUsername.put(user.getUsername(), user);
        }

        for (UserSeed seed : userSeeds) {
            User user = existingByUsername.get(seed.username());
            if (user == null) {
                user = new User();
                user.setUsername(seed.username());
                user.setEmail(seed.email());
                user.setPhone(seed.phone());
                user.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
                user.setCreatedAt(OffsetDateTime.now().minusDays(seed.index() % 18L).minusHours(seed.index()));
                user = userRepository.save(user);
                existingByUsername.put(seed.username(), user);
            }
            ensureProfile(user, seed);
        }
        return existingByUsername;
    }

    private void ensureProfile(User user, UserSeed seed) {
        UserProfile existing = userProfileRepository.findByUserId(user.getId());
        if (existing != null) {
            return;
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(user.getId());
        profile.setNickname(seed.nickname());
        profile.setBio(seed.bio());
        profile.setLocation(seed.location());
        profile.setHomepageUrl(seed.homepage());
        profile.setLinks(List.of(seed.link()));
        userProfileRepository.save(profile);
    }

    private void ensureThreads(Map<String, Section> sectionsBySlug, Map<String, User> usersByUsername, List<UserSeed> userSeeds) {
        if (threadRepository.count() > 0) {
            return;
        }

        List<Thread> threads = new ArrayList<>();
        List<SectionSeed> sectionSeeds = buildSectionSeeds();
        List<String> sectionSlugs = sectionSeeds.stream().map(SectionSeed::slug).toList();
        List<String> intros = List.of(
                "最近刚补完一轮内容，想和大家聊聊这件事最打动我的地方。",
                "这几天反复回看资料后，感觉这个话题比我预想得更值得展开。",
                "整理相册和笔记时突然想到这个角度，发出来和大家一起讨论。",
                "线下和朋友聊完之后意犹未尽，决定把自己的观察写完整一点。"
        );
        List<String> endings = List.of(
                "也欢迎你们分享自己最喜欢的作品、路线或者器材组合。",
                "如果有人已经实际尝试过，特别想听听一手体验。",
                "我先抛砖引玉，后面有新进展再回来补充。",
                "欢迎不同观点，最好也顺手带上你们的推荐清单。"
        );

        for (UserSeed seed : userSeeds) {
            User user = usersByUsername.get(seed.username());
            for (int i = 0; i < 2; i++) {
                String slug = sectionSlugs.get((seed.index() * 2 + i) % sectionSlugs.size());
                Section section = sectionsBySlug.get(slug);
                Thread thread = new Thread();
                thread.setSectionId(section.getId());
                thread.setAuthorId(user.getId());
                thread.setTitle(threadTitle(section.getName(), seed.nickname(), i));
                thread.setContentMd(threadContent(section, seed, intros.get((seed.index() + i) % intros.size()), endings.get((seed.index() + i) % endings.size())));
                thread.setStatus("NORMAL");
                thread.setSpoiler(false);
                Instant createdAt = Instant.now().minusSeconds((long) (seed.index() * 2 + i + 1) * 7200L);
                thread.setCreatedAt(createdAt);
                thread.setUpdatedAt(createdAt.plusSeconds(900));
                thread.setSummaryStatus("PENDING");
                threads.add(thread);
            }
        }
        threadRepository.saveAll(threads);
    }

    private String threadTitle(String sectionName, String nickname, int round) {
        return switch (round) {
            case 0 -> nickname + " 的 " + sectionName + " 入门与近期观察";
            default -> "聊聊我最近在 " + sectionName + " 里最上头的三个点";
        };
    }

    private String threadContent(Section section, UserSeed user, String intro, String ending) {
        return """
                ## 本周想聊的主题

                %s

                我在 **%s** 这个分区里最近最关注的是：

                1. 信息密度高但不容易被新人理解的细节
                2. 社区里常见但值得重新整理的经验
                3. 个人体验和公共讨论之间的落差

                ### 我的观察

                - 我来自 %s，这段时间主要从自己的日常体验出发做记录。
                - 如果只看表面热度，很容易错过真正有复看价值的内容。
                - 把作品、器材、赛历或者菜谱放进同一个坐标系后，会更容易找到自己的偏好。

                ### 我自己的推荐

                - 先找一条最容易坚持的切入路径，再逐步补深度。
                - 与其追求一次性看完全部，不如连续记录三到五次真实体验。
                - 把“为什么喜欢”写出来，讨论会更有意思。

                %s
                """.formatted(intro, section.getName(), user.location(), ending);
    }

    private List<SectionSeed> buildSectionSeeds() {
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

    private List<UserSeed> buildUserSeeds() {
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

    private record SectionSeed(String name, String slug, String description, String rules) {}

    private record UserSeed(
            int index,
            String username,
            String nickname,
            String location,
            String bio,
            String homepage,
            String link,
            String email,
            String phone
    ) {}
}
