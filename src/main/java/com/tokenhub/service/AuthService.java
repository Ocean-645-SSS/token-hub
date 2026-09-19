package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.LoginRequest;
import com.tokenhub.dto.LoginResponse;
import com.tokenhub.entity.User;
import com.tokenhub.mapper.UserMapper;
import com.tokenhub.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
@Service public class AuthService {
    private final UserMapper userMapper; private final JwtService jwtService; private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public AuthService(UserMapper userMapper, JwtService jwtService) { this.userMapper = userMapper; this.jwtService = jwtService; }
    public LoginResponse login(LoginRequest request) { User user = userMapper.findByUsername(request.username()); if (user == null || user.getStatus() != 1 || !encoder.matches(request.password(), user.getPasswordHash())) throw new BusinessException("INVALID_CREDENTIALS", "invalid username or password", HttpStatus.UNAUTHORIZED); return new LoginResponse(jwtService.createAccessToken(user.getId(), user.getUsername()), "Bearer", 7200); }
}
