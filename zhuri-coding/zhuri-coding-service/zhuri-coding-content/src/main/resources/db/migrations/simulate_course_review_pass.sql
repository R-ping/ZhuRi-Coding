-- 模拟课程审核通过：将课程 2088642484839063553 置为已发布(status=9)
-- 背景：课程已通过前端提交审核（status=1 待审核），此处模拟后台审批通过直接发布
UPDATE ap_course
SET status = 9,
    reason = NULL,
    published_at = NOW(),
    updated_time = NOW()
WHERE id = 2088642484839063553;

-- 校验：确认课程及其 12 个章节状态完整
