package com.tokenhub.mapper;

import com.tokenhub.entity.TokenWallet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface TokenWalletMapper {
    @Insert("insert ignore into token_wallet(user_id,model_code,available_token,frozen_token,credit_limit,version) values(#{userId},#{modelCode},0,0,5000,0)")
    int createIfAbsent(@Param("userId") long userId, @Param("modelCode") String modelCode);
    @Select("select id,user_id,model_code,available_token,frozen_token,credit_limit,version from token_wallet where user_id=#{userId} and model_code=#{modelCode} for update")
    TokenWallet lockByUserAndModel(@Param("userId") long userId, @Param("modelCode") String modelCode);
    @Select("select id,user_id,model_code,available_token,frozen_token,credit_limit,version from token_wallet where user_id=#{userId} and model_code=#{modelCode}")
    TokenWallet findByUserAndModel(@Param("userId") long userId, @Param("modelCode") String modelCode);
    @Select("select id,user_id,model_code,available_token,frozen_token,credit_limit,version from token_wallet where user_id=#{userId} order by model_code")
    List<TokenWallet> listByUser(@Param("userId") long userId);
    @Update("update token_wallet set available_token=available_token+#{amount}, version=version+1 where id=#{id}")
    int addAvailable(@Param("id") long id, @Param("amount") long amount);
    @Update("update token_wallet set available_token=#{available}, frozen_token=#{frozen}, version=version+1 where id=#{id}")
    int setBalances(@Param("id") long id, @Param("available") long available, @Param("frozen") long frozen);
}
