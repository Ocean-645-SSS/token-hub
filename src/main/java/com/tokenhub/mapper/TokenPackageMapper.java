package com.tokenhub.mapper;
import com.tokenhub.entity.TokenPackage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
public interface TokenPackageMapper {
    @Select("select id,name,model_code,token_amount,price,description,suitable_scenario,stock,status from token_package where status=1 order by id limit #{limit} offset #{offset}") List<TokenPackage> list(@Param("limit") int limit, @Param("offset") long offset);
    @Select("select count(*) from token_package where status=1") long count();
    @Select("select id,name,model_code,token_amount,price,description,suitable_scenario,stock,status from token_package where status=1 order by id") List<TokenPackage> findAllActive();
    @Select("select id,name,model_code,token_amount,price,description,suitable_scenario,stock,status from token_package where id=#{id} and status=1") TokenPackage findById(@Param("id") long id);
    @Select("<script>select id,name,model_code,token_amount,price,description,suitable_scenario,stock,status from token_package where status=1 <if test='keyword != null and keyword != &quot;&quot;'>and (name like concat('%',#{keyword},'%') or description like concat('%',#{keyword},'%') or suitable_scenario like concat('%',#{keyword},'%'))</if> <if test='modelCode != null and modelCode != &quot;&quot;'>and model_code=#{modelCode}</if> order by id limit #{limit} offset #{offset}</script>") List<TokenPackage> search(@Param("keyword") String keyword, @Param("modelCode") String modelCode, @Param("limit") int limit, @Param("offset") long offset);
    @Select("<script>select count(*) from token_package where status=1 <if test='keyword != null and keyword != &quot;&quot;'>and (name like concat('%',#{keyword},'%') or description like concat('%',#{keyword},'%') or suitable_scenario like concat('%',#{keyword},'%'))</if> <if test='modelCode != null and modelCode != &quot;&quot;'>and model_code=#{modelCode}</if></script>") long searchCount(@Param("keyword") String keyword, @Param("modelCode") String modelCode);
    @Update("update token_package set stock = stock - 1 where id = #{id} and status = 1 and stock > 0") int decreaseStock(@Param("id") long id);
    @Update("update token_package set stock = stock + 1 where id = #{id}") int increaseStock(@Param("id") long id);
}
