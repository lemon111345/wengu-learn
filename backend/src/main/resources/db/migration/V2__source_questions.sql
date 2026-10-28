-- #15：题目切分交给模型（决策 011），切分结果存在 source 表，抽取时从这里读取用户预览过的题目。
-- 只有题目类的材料有切分结果，源码、文档为空。
ALTER TABLE source
    ADD COLUMN questions LONGTEXT NULL COMMENT '切分出的题目 JSON：[{id, text, followUps}]，id 从 1 开始';
