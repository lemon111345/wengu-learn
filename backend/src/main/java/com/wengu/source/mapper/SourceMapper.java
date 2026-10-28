package com.wengu.source.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.source.entity.Source;
import org.apache.ibatis.annotations.Mapper;

/** 只有 source 模块使用。其他模块要读材料，调用 SourceService。 */
@Mapper
public interface SourceMapper extends BaseMapper<Source> {
}
