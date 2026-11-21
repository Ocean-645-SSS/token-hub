package com.tokenhub.mapper;
import com.tokenhub.dto.ApiKeyUsageResponse;
import com.tokenhub.entity.ApiKey;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface ApiKeyMapper {
 @Insert("insert into api_key(user_id,name,key_prefix,key_hash,allowed_models,status) values(#{userId},#{name},#{keyPrefix},#{keyHash},#{allowedModels},1)") @Options(useGeneratedKeys=true,keyProperty="id") int insert(ApiKey key);
 @Select("select id,user_id,name,key_prefix,key_hash,allowed_models,status,created_at,last_used_at from api_key where user_id=#{userId} order by id desc") List<ApiKey> listByUser(@Param("userId") long userId);
 @Select("select id,user_id,name,key_prefix,key_hash,allowed_models,status,created_at,last_used_at from api_key where key_hash=#{hash}") ApiKey findByHash(@Param("hash") String hash);
 @Select("select id,user_id,name,key_prefix,key_hash,allowed_models,status,created_at,last_used_at from api_key where id=#{id} and user_id=#{userId}") ApiKey findByIdForUser(@Param("id") long id,@Param("userId") long userId);
 @Update("update api_key set status=0 where id=#{id} and user_id=#{userId} and status=1") int revoke(@Param("id") long id,@Param("userId") long userId);
 @Update("update api_key set last_used_at=current_timestamp where id=#{id} and status=1") int touch(@Param("id") long id);

 @Select("""
     select k.id as apiKeyId, count(r.id) as requestCount,
            coalesce(sum(case when r.status = 'SUCCESS' then r.total_tokens else 0 end), 0) as settledTokens
     from api_key k
     left join model_request r on r.api_key_id = k.id and r.user_id = k.user_id
         and r.created_at >= timestampadd(DAY, -7, current_timestamp)
     where k.id = #{id} and k.user_id = #{userId}
     group by k.id
     """)
 ApiKeyUsageResponse usageLastSevenDays(@Param("id") long id, @Param("userId") long userId);
}
