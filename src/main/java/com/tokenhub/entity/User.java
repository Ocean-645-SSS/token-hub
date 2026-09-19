package com.tokenhub.entity;
import lombok.Data;
@Data public class User { private Long id; private String username; private String passwordHash; private Integer status; }
