package com.tokenhub.mapper;

import com.tokenhub.entity.SeckillActivity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface SeckillActivityMapper {
    @Select("select id,package_id,seckill_price,stock,start_time,end_time,status from seckill_activity where id=#{id}")
    SeckillActivity findById(@Param("id") long id);
    @Select("select id,package_id,seckill_price,stock,start_time,end_time,status from seckill_activity where status=1 and end_time >= current_timestamp")
    List<SeckillActivity> findWarmupCandidates();
}
