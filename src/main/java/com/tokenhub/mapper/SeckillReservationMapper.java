package com.tokenhub.mapper;

import com.tokenhub.entity.SeckillReservation;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface SeckillReservationMapper {
    @Insert("insert into seckill_reservation(request_id,user_id,activity_id,package_id,state) values(#{requestId},#{userId},#{activityId},#{packageId},'RESERVED')")
    int insert(SeckillReservation reservation);

    @Select("select * from seckill_reservation where request_id=#{id}")
    SeckillReservation find(@Param("id") String id);

    @Select("select * from seckill_reservation where request_id=#{id} for update")
    SeckillReservation lock(@Param("id") String id);

    @Update("update seckill_reservation set state=#{state},order_no=#{orderNo},updated_at=current_timestamp where request_id=#{id}")
    int transition(@Param("id") String id, @Param("state") String state, @Param("orderNo") String orderNo);

    @Select("select request_id from seckill_reservation where state='RESERVED' and updated_at < #{cutoff} order by updated_at limit 100")
    List<String> expired(@Param("cutoff") LocalDateTime cutoff);

    @Select("select request_id from seckill_reservation where state='COMPENSATION_PENDING' order by updated_at limit 100")
    List<String> pendingReleases();
}
