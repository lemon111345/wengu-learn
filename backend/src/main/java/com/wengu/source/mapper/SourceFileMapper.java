package com.wengu.source.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.source.entity.SourceFile;
import org.apache.ibatis.annotations.Mapper;

/** 只有 source 模块使用。其他模块要读源码文件，调用 SourceService。 */
@Mapper
public interface SourceFileMapper extends BaseMapper<SourceFile> {
}
