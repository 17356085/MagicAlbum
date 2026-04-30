package com.example.demo.bootstrap.local.seed;

import com.example.demo.sections.entity.Section;

import java.util.List;

public final class LocalThreadSeedText {
    private LocalThreadSeedText() {
    }

    public static List<String> intros() {
        return List.of(
                "最近刚补完一轮内容，想和大家聊聊这件事最打动我的地方。",
                "这几天反复回看资料后，感觉这个话题比我预想得更值得展开。",
                "整理相册和笔记时突然想到这个角度，发出来和大家一起讨论。",
                "线下和朋友聊完之后意犹未尽，决定把自己的观察写完整一点。"
        );
    }

    public static List<String> endings() {
        return List.of(
                "也欢迎你们分享自己最喜欢的作品、路线或者器材组合。",
                "如果有人已经实际尝试过，特别想听听一手体验。",
                "我先抛砖引玉，后面有新进展再回来补充。",
                "欢迎不同观点，最好也顺手带上你们的推荐清单。"
        );
    }

    public static String threadTitle(String sectionName, String nickname, int round) {
        return switch (round) {
            case 0 -> nickname + " 的 " + sectionName + " 入门与近期观察";
            default -> "聊聊我最近在 " + sectionName + " 里最上头的三个点";
        };
    }

    public static String threadContent(Section section, UserSeed user, String intro, String ending) {
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
}
