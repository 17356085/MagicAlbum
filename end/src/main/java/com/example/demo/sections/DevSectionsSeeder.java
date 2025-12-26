package com.example.demo.sections;

import com.example.demo.sections.entity.Section;
import com.example.demo.sections.repo.SectionRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!local")
public class DevSectionsSeeder implements ApplicationRunner {
    private final SectionRepository sectionRepository;

    public DevSectionsSeeder(SectionRepository sectionRepository) {
        this.sectionRepository = sectionRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 仅在分区表为空时填充预置数据，避免重复插入
        if (sectionRepository.count() > 0) {
            return;
        }

        List<Section> preset = new ArrayList<>();
        preset.add(create("动画", "anime", "动画作品交流与讨论"));
        preset.add(create("音乐", "music", "音乐分享、鉴赏与创作交流"));
        preset.add(create("游戏", "game", "主机/PC/移动游戏讨论与攻略"));
        preset.add(create("F1", "f1", "一级方程式赛事新闻与技术讨论"));
        preset.add(create("科技数码", "tech", "数码产品评测、折腾与交流"));
        preset.add(create("编程", "coding", "编程学习、技术分享与项目交流"));
        preset.add(create("美食", "food", "烹饪心得、美食探店与菜谱分享"));
        preset.add(create("模型", "model", "手办/拼装/高达/潮玩模型交流"));
        preset.add(create("阅读", "reading", "阅读心得、书评与推荐"));

        sectionRepository.saveAll(preset);
    }

    private Section create(String name, String slug, String description) {
        Section s = new Section();
        s.setName(name);
        s.setSlug(slug);
        s.setDescription(description);
        s.setVisible(true);
        return s;
    }
}