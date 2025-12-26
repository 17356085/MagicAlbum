-- 恢复样例数据：用户与主题（threads）
-- 目标：提供约 21 条主题，便于前端联调

USE MagicAlbum;

-- 样例作者（若不存在则插入固定 id=3）
INSERT INTO users (id, username, email, phone, password_hash)
VALUES (3, 'fuckuser', 'fuckuser@example.com', '00000000000', 'dummy')
ON DUPLICATE KEY UPDATE username=VALUES(username);

-- 动画区（anime）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '动画讨论：近期新番推荐', '# 新番整理与推荐', FALSE, 'NORMAL' FROM sections s WHERE s.slug='anime';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '动画角色讨论：久远寺相关', '# 久远寺有珠的剧情讨论', FALSE, 'NORMAL' FROM sections s WHERE s.slug='anime';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '动画作画与分镜细节', '# 作画与分镜分析', FALSE, 'NORMAL' FROM sections s WHERE s.slug='anime';

-- 音乐区（music）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '音乐分享：本周循环歌单', '# 歌单与评论', FALSE, 'NORMAL' FROM sections s WHERE s.slug='music';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '器乐讨论：吉他与键盘', '# 器乐练习方法', FALSE, 'NORMAL' FROM sections s WHERE s.slug='music';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '作曲技巧：和声与编配', '# 和声与编配入门', FALSE, 'NORMAL' FROM sections s WHERE s.slug='music';

-- 游戏区（game）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '主机平台近期佳作', '# 推荐与评测', FALSE, 'NORMAL' FROM sections s WHERE s.slug='game';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, 'PC 平台硬件升级建议', '# 显卡与散热', FALSE, 'NORMAL' FROM sections s WHERE s.slug='game';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '手游抽卡与养成心得', '# 抽卡概率与养成路径', FALSE, 'NORMAL' FROM sections s WHERE s.slug='game';

-- F1（f1）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, 'F1 赛事最新新闻', '# 车队与车手动态', FALSE, 'NORMAL' FROM sections s WHERE s.slug='f1';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '技术讨论：空气动力学', '# 空气动力学简析', FALSE, 'NORMAL' FROM sections s WHERE s.slug='f1';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '历史回顾：经典比赛', '# 经典时刻分享', FALSE, 'NORMAL' FROM sections s WHERE s.slug='f1';

-- 科技数码（tech）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '手机拍照对比与调色', '# 摄影后期', FALSE, 'NORMAL' FROM sections s WHERE s.slug='tech';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '笔记本选购建议', '# 性能与续航平衡', FALSE, 'NORMAL' FROM sections s WHERE s.slug='tech';

-- 编程（coding）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '前端框架对比：Vue/React', '# 优缺点与选型', FALSE, 'NORMAL' FROM sections s WHERE s.slug='coding';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '后端服务：Spring Boot 实战', '# 配置与调试', FALSE, 'NORMAL' FROM sections s WHERE s.slug='coding';

-- 美食（food）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '家常菜谱分享：简单三菜一汤', '# 做法与心得', FALSE, 'NORMAL' FROM sections s WHERE s.slug='food';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '探店记录：本周新店打卡', '# 店铺评价', FALSE, 'NORMAL' FROM sections s WHERE s.slug='food';

-- 模型（model）样例主题
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '拼装模型：喷涂与打磨', '# 工具与技巧', FALSE, 'NORMAL' FROM sections s WHERE s.slug='model';
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '收藏分享：近期入手清单', '# 收藏心得', FALSE, 'NORMAL' FROM sections s WHERE s.slug='model';

-- 阅读（reading）样例主题（如果存在该分区）
INSERT INTO threads (section_id, author_id, title, content_md, spoiler, status)
SELECT s.id, 3, '书籍分享：本月读书清单', '# 读后感与推荐', FALSE, 'NORMAL' FROM sections s WHERE s.slug='reading';