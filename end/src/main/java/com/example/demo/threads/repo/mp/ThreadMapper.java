package com.example.demo.threads.repo.mp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.threads.entity.Thread;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ThreadMapper extends BaseMapper<Thread> {
}