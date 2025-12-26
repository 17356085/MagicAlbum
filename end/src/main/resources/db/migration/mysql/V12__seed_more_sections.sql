-- 追加预置分区数据：科技数码、编程、美食、模型
INSERT INTO sections (name, slug, description, rules, visible, created_at)
VALUES
  ('科技数码', 'tech', '数码产品评测、折腾与交流', NULL, TRUE, NOW()),
  ('编程', 'coding', '编程学习、技术分享与项目交流', NULL, TRUE, NOW()),
  ('美食', 'food', '烹饪心得、美食探店与菜谱分享', NULL, TRUE, NOW()),
  ('模型', 'model', '手办/拼装/高达/潮玩模型交流', NULL, TRUE, NOW());