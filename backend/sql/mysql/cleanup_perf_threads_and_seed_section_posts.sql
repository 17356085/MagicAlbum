USE MagicAlbum;

SET @sample_author_id := (SELECT id FROM users ORDER BY id LIMIT 1);

DELETE FROM threads
WHERE title LIKE 'perf thread%'
   OR title LIKE '[性能测试] 自动生成主题%';

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '本季动画第一集观感：节奏、作画和角色登场',
       '## 第一集观感记录

![](https://source.unsplash.com/1200x675/?anime,illustration)

这一帖用来聊新番开局的三个重点：角色登场是否清晰、分镜是否服务情绪、第一集有没有把核心冲突立起来。

我比较在意的是演出节奏。好的第一集不一定要塞满设定，但至少要让观众知道“为什么要继续看下一集”。',
       FALSE, 'NORMAL', NOW() - INTERVAL 1 HOUR, NOW() - INTERVAL 1 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'anime'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '本季动画第一集观感：节奏、作画和角色登场');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '动画分镜笔记：沉默镜头为什么会有力量',
       '## 分镜讨论

![](https://source.unsplash.com/1200x675/?storyboard,animation)

有些桥段没有台词，却比长对白更有压迫感。这里可以集中记录你喜欢的沉默镜头：构图、角色站位、留白、环境音，以及它们如何推动人物关系。',
       FALSE, 'NORMAL', NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 2 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'anime'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '动画分镜笔记：沉默镜头为什么会有力量');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '本周循环歌单：适合夜间写东西的几首歌',
       '## 歌单分享

![](https://images.unsplash.com/photo-1511379938547-c1f69419868d?auto=format&fit=crop&w=1200&q=80)

这周想收集一些适合夜间写作、画画或整理照片时播放的歌。偏器乐、后摇、爵士、电子都可以，重点是不要太抢注意力。',
       FALSE, 'NORMAL', NOW() - INTERVAL 3 HOUR, NOW() - INTERVAL 3 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'music'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '本周循环歌单：适合夜间写东西的几首歌');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '键盘与吉他合奏时，怎样给彼此留空间',
       '## 编配小记

![](https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?auto=format&fit=crop&w=1200&q=80)

键盘铺底很容易占掉中频，吉他扫弦也容易糊成一片。可以从音区、节奏型、延音和音色厚度四个角度拆开讨论。',
       FALSE, 'NORMAL', NOW() - INTERVAL 4 HOUR, NOW() - INTERVAL 4 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'music'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '键盘与吉他合奏时，怎样给彼此留空间');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '视觉小说推荐：适合慢慢读完的作品',
       '## 游戏推荐

![](https://source.unsplash.com/1200x675/?visual-novel,game)

想整理一些适合夜晚安静阅读的视觉小说。可以从文本风格、音乐、演出、节奏和结局余味几个维度推荐。',
       FALSE, 'NORMAL', NOW() - INTERVAL 5 HOUR, NOW() - INTERVAL 5 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'game'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '视觉小说推荐：适合慢慢读完的作品');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '最近玩到停不下来的独立游戏',
       '## 独立游戏交流

![](https://source.unsplash.com/1200x675/?indie-game,gaming)

欢迎分享近期让你惊喜的独立游戏。不限平台，最好写一下推荐理由：玩法核心、关卡节奏、美术方向，或者有没有让你记住的音乐。',
       FALSE, 'NORMAL', NOW() - INTERVAL 6 HOUR, NOW() - INTERVAL 6 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'game'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '最近玩到停不下来的独立游戏');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, 'F1 观赛入门：轮胎策略到底怎么看',
       '## F1 技术讨论

![](https://images.unsplash.com/photo-1503376780353-7e6692767b70?auto=format&fit=crop&w=1200&q=80)

同一场比赛里，硬胎、中性胎、软胎的选择会直接改变进站窗口。这里可以聊聊退化、undercut、overcut 和安全车对策略的影响。',
       FALSE, 'NORMAL', NOW() - INTERVAL 7 HOUR, NOW() - INTERVAL 7 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'f1'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = 'F1 观赛入门：轮胎策略到底怎么看');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '排位赛之后可以观察哪些信号',
       '## 赛前观察

![](https://images.unsplash.com/photo-1542362567-b07e54358753?auto=format&fit=crop&w=1200&q=80)

排位名次只是表层信息。长距离速度、尾速、轮胎暖胎速度、天气变化和维修区位置，都会影响正赛走势。',
       FALSE, 'NORMAL', NOW() - INTERVAL 8 HOUR, NOW() - INTERVAL 8 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'f1'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '排位赛之后可以观察哪些信号');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '手机摄影对比：白天、夜景和人像分别看什么',
       '## 数码体验

![](https://source.unsplash.com/1200x675/?smartphone,photography)

手机影像不只看像素。白天看宽容度和色彩，夜景看涂抹和高光控制，人像看肤色、边缘识别和焦外过渡。',
       FALSE, 'NORMAL', NOW() - INTERVAL 9 HOUR, NOW() - INTERVAL 9 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'tech'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '手机摄影对比：白天、夜景和人像分别看什么');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '轻薄本选购：性能、屏幕和续航怎么取舍',
       '## 选购讨论

![](https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=1200&q=80)

轻薄本的关键不是参数堆满，而是你最常用的场景。写代码、修图、上课、出差，对 CPU、内存、屏幕和接口的要求都不一样。',
       FALSE, 'NORMAL', NOW() - INTERVAL 10 HOUR, NOW() - INTERVAL 10 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'tech'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '轻薄本选购：性能、屏幕和续航怎么取舍');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, 'Vue 页面卡顿排查：从组件到请求链路',
       '## 编程排查

![](https://images.unsplash.com/photo-1517694712202-14dd9538aa97?auto=format&fit=crop&w=1200&q=80)

页面卡顿可以从三个方向拆：组件重复渲染、列表数据量、接口返回和缓存策略。建议先用浏览器 Performance 面板定位瓶颈，再动代码。',
       FALSE, 'NORMAL', NOW() - INTERVAL 11 HOUR, NOW() - INTERVAL 11 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'coding'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = 'Vue 页面卡顿排查：从组件到请求链路');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, 'Spring Boot 本地环境配置备忘录',
       '## 后端调试

![](https://images.unsplash.com/photo-1498050108023-c5249f4df085?auto=format&fit=crop&w=1200&q=80)

本地环境最容易出问题的是端口、环境变量和数据库实例。建议把 `.env.local.example`、IDEA 启动配置、Docker Compose 的端口对应关系统一检查一遍。',
       FALSE, 'NORMAL', NOW() - INTERVAL 12 HOUR, NOW() - INTERVAL 12 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'coding'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = 'Spring Boot 本地环境配置备忘录');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '周末做饭记录：一锅番茄牛肉和两份便当',
       '## 美食记录

![](https://images.unsplash.com/photo-1504674900247-0877df9cc836?auto=format&fit=crop&w=1200&q=80)

番茄炒出沙之后再下牛肉，汤底会更厚。配菜可以准备西兰花、玉米和溏心蛋，第二天当便当也不会太单调。',
       FALSE, 'NORMAL', NOW() - INTERVAL 13 HOUR, NOW() - INTERVAL 13 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'food'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '周末做饭记录：一锅番茄牛肉和两份便当');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '探店笔记：适合聊天的安静小店',
       '## 探店分享

![](https://images.unsplash.com/photo-1551218808-94e220e084d2?auto=format&fit=crop&w=1200&q=80)

评价一家店不只看味道，也看座位距离、灯光、服务节奏和价格。适合聊天的小店，通常比热门打卡店更值得收藏。',
       FALSE, 'NORMAL', NOW() - INTERVAL 14 HOUR, NOW() - INTERVAL 14 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'food'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '探店笔记：适合聊天的安静小店');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '模型喷涂入门：水补土、遮盖和消光',
       '## 模型制作

![](https://source.unsplash.com/1200x675/?scale-model,craft)

新手最容易忽略的是表面处理。打磨、清洗、上水补土之后再喷色，成品质感会稳定很多。遮盖边缘要压实，最后消光别喷太厚。',
       FALSE, 'NORMAL', NOW() - INTERVAL 15 HOUR, NOW() - INTERVAL 15 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'model'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '模型喷涂入门：水补土、遮盖和消光');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '最近入手的模型工具：哪些真的提升体验',
       '## 工具清单

![](https://source.unsplash.com/1200x675/?model-kit,tools)

斜口钳、笔刀、砂纸、镊子、渗线液这些工具看起来普通，但会直接影响制作体验。欢迎分享你觉得最值的一件工具。',
       FALSE, 'NORMAL', NOW() - INTERVAL 16 HOUR, NOW() - INTERVAL 16 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'model'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '最近入手的模型工具：哪些真的提升体验');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '本月读书清单：小说、随笔和技术书',
       '## 阅读记录

![](https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?auto=format&fit=crop&w=1200&q=80)

可以按“读完”“在读”“想读”三类整理，也可以写几句短评。阅读区不必追求长文，能留下真实感受就很好。',
       FALSE, 'NORMAL', NOW() - INTERVAL 17 HOUR, NOW() - INTERVAL 17 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'reading'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '本月读书清单：小说、随笔和技术书');

INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status, created_at, updated_at, summary_status)
SELECT s.id, @sample_author_id, '读完一本书之后，怎样写一篇不空泛的短评',
       '## 读后感方法

![](https://images.unsplash.com/photo-1519682337058-a94d519337bc?auto=format&fit=crop&w=1200&q=80)

短评可以从一个具体问题开始：这本书解决了什么疑惑？哪一段最有力量？有哪些观点你并不同意？这样写出来会比泛泛而谈更有记忆点。',
       FALSE, 'NORMAL', NOW() - INTERVAL 18 HOUR, NOW() - INTERVAL 18 HOUR, 'PENDING'
FROM sections s
WHERE s.slug = 'reading'
  AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.title = '读完一本书之后，怎样写一篇不空泛的短评');
