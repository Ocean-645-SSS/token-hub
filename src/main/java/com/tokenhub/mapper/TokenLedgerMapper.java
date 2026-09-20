package com.tokenhub.mapper;

import com.tokenhub.entity.TokenLedger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface TokenLedgerMapper {
    @Insert("insert ignore into token_ledger(user_id,model_code,biz_id,type,amount,balance_before,balance_after,remark) values(#{userId},#{modelCode},#{bizId},#{type},#{amount},#{balanceBefore},#{balanceAfter},#{remark})")
    int insertIgnore(TokenLedger ledger);
    @Select("select id,user_id,model_code,biz_id,type,amount,balance_before,balance_after,remark,created_at from token_ledger where user_id=#{userId} order by id desc limit #{limit} offset #{offset}")
    List<TokenLedger> listByUser(@Param("userId") long userId, @Param("limit") int limit, @Param("offset") long offset);
    @Select("select count(*) from token_ledger where user_id=#{userId}") long countByUser(@Param("userId") long userId);
}
