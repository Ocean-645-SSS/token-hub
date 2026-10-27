package com.tokenhub.mapper;

import com.tokenhub.entity.SeckillInventory;
import org.apache.ibatis.annotations.*;

public interface SeckillInventoryMapper {
    @Insert("insert ignore into seckill_inventory(activity_id,available_stock,version) select id,stock,1 from seckill_activity where id=#{id}")
    int initialize(@Param("id") long id);

    @Select("select activity_id,available_stock,version from seckill_inventory where activity_id=#{id}")
    SeckillInventory find(@Param("id") long id);

    @Update("update seckill_inventory set available_stock=available_stock-1,version=version+1 where activity_id=#{id} and available_stock>0")
    int reserve(@Param("id") long id);

    @Update("update seckill_inventory set available_stock=available_stock+1,version=version+1 where activity_id=#{id}")
    int release(@Param("id") long id);
}
