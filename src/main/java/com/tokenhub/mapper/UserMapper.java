package com.tokenhub.mapper;
import com.tokenhub.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
public interface UserMapper { @Select("select id, username, password_hash, status from `user` where username = #{username}") User findByUsername(@Param("username") String username); @Select("select id, username, password_hash, status from `user` where id=#{userId}") User findById(@Param("userId") long userId); @Update("update `user` set status=0 where id=#{userId}") int suspend(@Param("userId") long userId); }
