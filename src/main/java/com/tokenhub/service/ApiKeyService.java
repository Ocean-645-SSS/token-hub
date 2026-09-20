package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.*;
import com.tokenhub.entity.ApiKey;
import com.tokenhub.mapper.ApiKeyMapper;
import com.tokenhub.security.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
@Service public class ApiKeyService {
 private final ApiKeyMapper keys; private final StringRedisTemplate redis;
 public ApiKeyService(ApiKeyMapper keys,StringRedisTemplate redis){this.keys=keys;this.redis=redis;}
 public ApiKeyResponse create(CreateApiKeyRequest request){ long userId=CurrentUser.get(); String secret=random(); ApiKey key=new ApiKey(); key.setUserId(userId); key.setName(request.name().trim()); key.setKeyPrefix(secret.substring(0,12)); key.setKeyHash(hash(secret)); key.setAllowedModels(String.join(",",new LinkedHashSet<>(request.allowedModels()))); keys.insert(key); return response(key,secret); }
 public List<ApiKeyResponse> list(){return keys.listByUser(CurrentUser.get()).stream().map(k->response(k,null)).toList();}
 public void revoke(long id){long userId=CurrentUser.get();ApiKey existing=keys.findByIdForUser(id,userId);if(existing==null||keys.revoke(id,userId)!=1) throw new BusinessException("API_KEY_NOT_FOUND","api key not found",HttpStatus.NOT_FOUND); redis.delete("apikey:"+existing.getKeyHash()); }
 public ApiKey authenticate(String raw){if(raw==null||raw.isBlank()) throw invalid(); String digest=hash(raw); String cached=redis.opsForValue().get("apikey:"+digest); ApiKey key=cached==null?keys.findByHash(digest):fromCache(cached); if(key==null||key.getStatus()!=1) throw invalid(); if(cached==null)redis.opsForValue().set("apikey:"+digest,cache(key)); keys.touch(key.getId()); return key;}
 private ApiKeyResponse response(ApiKey k,String raw){return new ApiKeyResponse(k.getId(),k.getName(),k.getKeyPrefix(),Arrays.stream(k.getAllowedModels().split(",")).filter(s->!s.isBlank()).toList(),k.getStatus(),k.getCreatedAt(),k.getLastUsedAt(),raw);}
 private static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return "th_"+Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 private static String cache(ApiKey k){return k.getId()+"|"+k.getUserId()+"|"+k.getAllowedModels();}
 private static ApiKey fromCache(String v){String[] p=v.split("\\|",3);if(p.length!=3)return null;try{ApiKey k=new ApiKey();k.setId(Long.parseLong(p[0]));k.setUserId(Long.parseLong(p[1]));k.setAllowedModels(p[2]);k.setStatus(1);return k;}catch(NumberFormatException e){return null;}}
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static BusinessException invalid(){return new BusinessException("INVALID_API_KEY","invalid api key",HttpStatus.UNAUTHORIZED);}
}
