package com.wengu.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.review.entity.ReviewLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ReviewLogMapper extends BaseMapper<ReviewLog> {

    /**
     * r 是某个知识点在 end 之前的最后一次评分（NOT EXISTS 排除了之后还有评分的）。
     * 它早于 start，说明 [start, end) 内没有评分；due_after 早于 end，说明这段时间内该复习。
     * due_after 为空的旧记录比较结果为假，不会查出来。
     */
    @Select("""
            SELECT r.kp_id FROM review_log r
            WHERE r.user_id = #{userId}
              AND r.reviewed_at < #{start}
              AND r.due_after < #{end}
              AND NOT EXISTS (
                  SELECT 1 FROM review_log later
                  WHERE later.user_id = r.user_id
                    AND later.kp_id = r.kp_id
                    AND later.reviewed_at > r.reviewed_at
                    AND later.reviewed_at < #{end})
            """)
    List<Long> selectMissedDueKpIds(@Param("userId") long userId, @Param("start") Instant start,
                                    @Param("end") Instant end);
}
