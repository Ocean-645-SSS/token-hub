package com.tokenhub.mapper;
import com.tokenhub.entity.ModelRequest;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
public interface ModelRequestMapper {
 @Insert("insert into model_request(request_id,user_id,api_key_id,model_code,reserved_tokens,status) values(#{requestId},#{userId},#{apiKeyId},#{modelCode},#{reservedTokens},#{status})") @Options(useGeneratedKeys=true,keyProperty="id") int insert(ModelRequest request);
 @Select("select id,request_id,user_id,api_key_id,model_code,input_tokens,output_tokens,total_tokens,reserved_tokens,status,retry_count,provider_cost_status,result_reference,created_at,finished_at from model_request where request_id=#{requestId}") ModelRequest findByRequestId(@Param("requestId") String requestId);
 @Update("update model_request set input_tokens=#{inputTokens},output_tokens=#{outputTokens},total_tokens=#{totalTokens},retry_count=#{retryCount},provider_cost_status=#{providerCostStatus},result_reference=#{resultReference},status=#{status},finished_at=current_timestamp where request_id=#{requestId} and status='PROCESSING'") int finish(ModelRequest request);
 @Select("select id,request_id,user_id,api_key_id,model_code,input_tokens,output_tokens,total_tokens,reserved_tokens,status,retry_count,provider_cost_status,result_reference,created_at,finished_at from model_request where status='PROCESSING' and created_at < #{before}") List<ModelRequest> listExpired(@Param("before") LocalDateTime before);
}
