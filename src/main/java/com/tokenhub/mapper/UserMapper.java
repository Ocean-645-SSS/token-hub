package com.tokenhub.mapper;
import com.tokenhub.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
public interface UserMapper { @Select("select id, username, password_hash, status from `user` where username = #{username}") User findByUsername(@Param("username") String username); }
