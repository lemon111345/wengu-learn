package com.wengu.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wengu.chat.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
