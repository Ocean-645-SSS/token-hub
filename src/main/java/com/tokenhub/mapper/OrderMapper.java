package com.tokenhub.mapper;

import com.tokenhub.common.OrderStatus;
import com.tokenhub.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

public interface OrderMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into orders(order_no,user_id,package_id,order_type,original_price,pay_price,token_amount,status) values(#{orderNo},#{userId},#{packageId},#{orderType},#{originalPrice},#{payPrice},#{tokenAmount},#{status})")
    int insert(Order order);
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into orders(order_no,user_id,package_id,order_type,seckill_activity_id,request_id,original_price,pay_price,token_amount,status) values(#{orderNo},#{userId},#{packageId},#{orderType},#{seckillActivityId},#{requestId},#{originalPrice},#{payPrice},#{tokenAmount},#{status})") int insertSeckill(Order order);
    @Select("select id,order_no,user_id,package_id,order_type,seckill_activity_id,request_id,original_price,pay_price,token_amount,status,created_at,paid_at,completed_at,expired_at,updated_at from orders where id=#{id}")
    Order findById(@Param("id") long id);
    @Select("select id,order_no,user_id,package_id,order_type,seckill_activity_id,request_id,original_price,pay_price,token_amount,status,created_at,paid_at,completed_at,expired_at,updated_at from orders where order_no=#{orderNo}")
    Order findByOrderNo(@Param("orderNo") String orderNo);
    @Select("select id,order_no,user_id,package_id,order_type,seckill_activity_id,request_id,original_price,pay_price,token_amount,status from orders where request_id=#{requestId}") Order findByRequestId(@Param("requestId") String requestId);
    @Update("update orders set status=#{to}, updated_at=current_timestamp where id=#{id} and user_id=#{userId} and status=#{from}")
    int transitionForUser(@Param("id") long id, @Param("userId") long userId, @Param("from") OrderStatus from, @Param("to") OrderStatus to);
    @Update("update orders set status=#{to}, paid_at=current_timestamp, updated_at=current_timestamp where id=#{id} and user_id=#{userId} and status=#{from}")
    int pay(@Param("id") long id, @Param("userId") long userId, @Param("from") OrderStatus from, @Param("to") OrderStatus to);
    @Update("update orders set status=#{to}, updated_at=current_timestamp where id=#{id} and status=#{from}")
    int transition(@Param("id") long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);
    @Update("update orders set status=#{to}, completed_at=current_timestamp, updated_at=current_timestamp where id=#{id} and status=#{from}")
    int complete(@Param("id") long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);
    @Update("update orders set status=#{to}, expired_at=current_timestamp, updated_at=current_timestamp where id=#{id} and status=#{from}") int timeout(@Param("id") long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);
}
