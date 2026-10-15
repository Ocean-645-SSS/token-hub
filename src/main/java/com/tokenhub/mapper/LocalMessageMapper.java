package com.tokenhub.mapper;

import com.tokenhub.entity.LocalMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface LocalMessageMapper {
    @Insert("insert into local_message(message_id,event_type,biz_id,topic,payload,status) values(#{messageId},#{eventType},#{bizId},#{topic},#{payload},0)")
    int insert(LocalMessage message);
    @Select("select count(*) from local_message where event_type=#{eventType} and biz_id=#{bizId} and status in (0,2)")
    int countPendingEvent(@Param("eventType") String eventType, @Param("bizId") String bizId);
    @Update("update local_message set status=1, updated_at=current_timestamp where event_type=#{eventType} and biz_id=#{bizId} and status in (0,2)")
    int markSent(@Param("eventType") String eventType, @Param("bizId") String bizId);
    @Select("select id,message_id,event_type,biz_id,topic,payload,status,retry_count from local_message where status in (0,2) and (next_retry_time is null or next_retry_time <= current_timestamp) order by id limit #{limit}") List<LocalMessage> findPending(@Param("limit") int limit);
    @Update("update local_message set status=3,updated_at=current_timestamp where id=#{id} and status in (0,2) and (next_retry_time is null or next_retry_time <= current_timestamp)") int claim(@Param("id") long id);
    @Update("update local_message set status=1, updated_at=current_timestamp where id=#{id} and status=3") int markSentClaimedById(@Param("id") long id);
    @Update("update local_message set status=2,retry_count=retry_count+1,next_retry_time=date_add(current_timestamp, interval #{delaySeconds} second),updated_at=current_timestamp where id=#{id} and status=3") int recordClaimedRetry(@Param("id") long id, @Param("delaySeconds") int delaySeconds);
    @Update("update local_message set status=2,retry_count=retry_count+1,next_retry_time=date_add(current_timestamp, interval #{delaySeconds} second),updated_at=current_timestamp where id=#{id} and status in (0,2)") int recordRetry(@Param("id") long id, @Param("delaySeconds") int delaySeconds);
    @Update("update local_message set status=2,retry_count=retry_count+1,next_retry_time=date_add(current_timestamp, interval #{retryDelaySeconds} second),updated_at=current_timestamp where status=3 and updated_at <= date_sub(current_timestamp, interval #{claimTimeoutSeconds} second)") int recoverExpiredClaims(@Param("claimTimeoutSeconds") int claimTimeoutSeconds, @Param("retryDelaySeconds") int retryDelaySeconds);
    @Select("select count(*) from local_message where status in (0,2) and (next_retry_time is null or next_retry_time <= current_timestamp)") long countPending();
}
