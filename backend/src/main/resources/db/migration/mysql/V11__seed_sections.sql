-- 预置分区数据：动画、音乐、游戏、F1
INSERT INTO sections (name, slug, description, rules, visible)
VALUES 
  ('动画', 'anime', '动画作品交流与讨论', NULL, TRUE),
  ('音乐', 'music', '音乐分享、鉴赏与创作交流', NULL, TRUE),
  ('游戏', 'game', '主机/PC/移动游戏讨论与攻略', NULL, TRUE),
  ('F1', 'f1', '一级方程式赛事新闻与技术讨论', NULL, TRUE);