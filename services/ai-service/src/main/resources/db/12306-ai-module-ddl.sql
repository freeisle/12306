-- ---------------------------------------------------------------------------
-- 12306  AI 智能化模块（ai-service）建表脚本
-- 约定与项目现有库表一致：utf8mb4_unicode_ci、create_time/update_time/del_flag、
-- 主键 id 由应用侧雪花算法生成
-- 说明：向量库（规则知识库 embedding）为非关系型存储
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS `12306_ai` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `12306_ai`;

-- ----------------------------
-- 1. 会话表：一次连续对话一个会话（设计文档 ai_conversation）
-- ----------------------------
CREATE TABLE `t_ai_conversation`
(
    `id`                bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `user_id`           bigint(20) DEFAULT NULL COMMENT '用户ID（匿名客服场景可为空）',
    `scene`             varchar(32) COLLATE utf8mb4_unicode_ci  DEFAULT 'support' COMMENT '场景：support=RAG客服 booking=购票Agent trip=行程规划',
    `title`             varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话标题（首问摘要）',
    `status`            int(3) DEFAULT '0' COMMENT '状态：0-进行中 1-已结束 2-已归档',
    `last_message_time` datetime                                DEFAULT NULL COMMENT '最后消息时间',
    `create_time`       datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`       datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`          tinyint(1) DEFAULT '0' COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`,`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI会话表';

-- ----------------------------
-- 2. 消息表：会话内每条消息（含模型治理元数据，设计文档 ai_message）
--    mode/confidence/latency/references 是 RAG 可解释性与排障的关键字段
-- ----------------------------
CREATE TABLE `t_ai_message`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `conversation_id` bigint(20) NOT NULL COMMENT '会话ID',
    `role`            varchar(16) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '角色：user/assistant/tool',
    `content`         text COLLATE utf8mb4_unicode_ci COMMENT '消息内容',
    `mode`            varchar(16) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '生成模式：LLM/EXTRACTIVE/FALLBACK/BLOCKED',
    `confidence`      varchar(8) COLLATE utf8mb4_unicode_ci   DEFAULT NULL COMMENT '检索置信度：HIGH/MEDIUM/LOW',
    `hit_cache`       tinyint(1) DEFAULT '0' COMMENT '是否语义缓存命中',
    `prompt_tokens`   int(11) DEFAULT NULL COMMENT '提示token数',
    `completion_tokens` int(11) DEFAULT NULL COMMENT '补全token数',
    `latency_ms`      bigint(20) DEFAULT NULL COMMENT '端到端耗时（毫秒）',
    `references_json` text COLLATE utf8mb4_unicode_ci COMMENT '引用来源JSON（source/title/snippet/score）',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT '0' COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`,`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI消息表';

-- ----------------------------
-- 3. 工具调用审计表：Agent 每次 Tool Calling 的入参/出参/成败（设计文档 ai_tool_call）
--    可解释性与资损排障的依据，购票 Agent 阶段必用
-- ----------------------------
CREATE TABLE `t_ai_tool_call`
(
    `id`         bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `message_id` bigint(20) NOT NULL COMMENT '触发该调用的assistant消息ID',
    `tool_name`  varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '工具名（如 queryTickets/createOrder）',
    `params`     text COLLATE utf8mb4_unicode_ci COMMENT '入参JSON（须脱敏：身份证/手机号不得明文）',
    `result`     text COLLATE utf8mb4_unicode_ci COMMENT '出参JSON（可截断）',
    `success`    tinyint(1) DEFAULT '1' COMMENT '是否成功',
    `error_msg`  varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
    `cost_ms`    bigint(20) DEFAULT NULL COMMENT '调用耗时（毫秒）',
    `create_time` datetime                               DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                               DEFAULT NULL COMMENT '修改时间',
    `del_flag`   tinyint(1) DEFAULT '0' COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY `idx_message_id` (`message_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI工具调用审计表';

-- ----------------------------
-- 4. 用户反馈表：点赞/点踩/纠错，评估与迭代闭环（设计文档 ai_feedback）
-- ----------------------------
CREATE TABLE `t_ai_feedback`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `message_id`      bigint(20) NOT NULL COMMENT '被反馈的assistant消息ID',
    `conversation_id` bigint(20) DEFAULT NULL COMMENT '会话ID',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `feedback_type`   int(3) DEFAULT NULL COMMENT '类型：1-赞 2-踩 3-纠错',
    `comment`         varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文字反馈',
    `correction`      text COLLATE utf8mb4_unicode_ci COMMENT '用户给出的正确答案（纠错场景）',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT '0' COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY `idx_message_id` (`message_id`) USING BTREE,
    KEY `idx_type_time` (`feedback_type`,`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI用户反馈表';

-- ----------------------------
-- 5.知识文档表：规则知识库元数据与增量更新流水线
--    当前 MVP 知识来源为 classpath:/knowledge/*.md，向量在内存/Redis Stack；
--    做“文档变更→重新embedding→更新向量库”的增量流水线时启用本表
-- ----------------------------
CREATE TABLE `t_ai_knowledge_doc`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `source`      varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源标识（文件名/URL/规章编号）',
    `title`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文档标题',
    `content`     mediumtext COLLATE utf8mb4_unicode_ci COMMENT '文档原文',
    `chunk_count` int(11) DEFAULT NULL COMMENT '切分片段数',
    `version`     int(11) DEFAULT '1' COMMENT '版本号（每次更新+1）',
    `status`      int(3) DEFAULT '0' COMMENT '状态：0-待索引 1-已索引 2-已下线',
    `create_time` datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`    tinyint(1) DEFAULT '0' COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_version` (`source`,`version`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI知识文档表（可选）';
