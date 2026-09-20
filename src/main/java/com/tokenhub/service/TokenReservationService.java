package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.config.BillingProperties;
import com.tokenhub.entity.TokenWallet;
import com.tokenhub.mapper.TokenWalletMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class TokenReservationService {
 private final StringRedisTemplate redis; private final TokenWalletMapper wallets; private final BillingProperties config;
 private final DefaultRedisScript<Long> freeze=new DefaultRedisScript<>("local a=tonumber(redis.call('HGET',KEYS[1],'available')); local c=tonumber(redis.call('HGET',KEYS[1],'credit')); local x=tonumber(ARGV[1]); if a-x < -c then return 0 end; redis.call('HINCRBY',KEYS[1],'available',-x); redis.call('HINCRBY',KEYS[1],'frozen',x); redis.call('HSET',KEYS[2],'status','FROZEN','amount',x,'wallet',KEYS[1]); return 1",Long.class);
 private final DefaultRedisScript<Long> release=new DefaultRedisScript<>("if redis.call('HGET',KEYS[1],'status') ~= 'FROZEN' then return 0 end; local x=tonumber(redis.call('HGET',KEYS[1],'amount')); redis.call('HINCRBY',KEYS[2],'available',x); redis.call('HINCRBY',KEYS[2],'frozen',-x); redis.call('HSET',KEYS[1],'status','RELEASED'); return 1",Long.class);
 private final DefaultRedisScript<Long> settle=new DefaultRedisScript<>("if redis.call('HGET',KEYS[1],'status') ~= 'FROZEN' then return 0 end; local r=tonumber(redis.call('HGET',KEYS[1],'amount')); local u=tonumber(ARGV[1]); redis.call('HINCRBY',KEYS[2],'available',r-u); redis.call('HINCRBY',KEYS[2],'frozen',-r); redis.call('HSET',KEYS[1],'status','SETTLED'); return 1",Long.class);
 public TokenReservationService(StringRedisTemplate redis,TokenWalletMapper wallets,BillingProperties config){this.redis=redis;this.wallets=wallets;this.config=config;}
 public void freeze(long userId,String model,String requestId,long amount){String wallet=walletKey(userId,model);ensure(wallet,userId,model); Long ok=redis.execute(freeze,List.of(wallet,reservationKey(requestId)),Long.toString(amount)); if(ok==null||ok==0)throw new BusinessException("CREDIT_LIMIT_EXCEEDED","token credit limit exceeded",HttpStatus.PAYMENT_REQUIRED); TokenWallet w=lock(userId,model);wallets.setBalances(w.getId(),w.getAvailableToken()-amount,w.getFrozenToken()+amount);}
 public void release(long userId,String model,String requestId){Long ok=redis.execute(release,List.of(reservationKey(requestId),walletKey(userId,model)));if(ok!=null&&ok==1){TokenWallet w=lock(userId,model);long a=amount(requestId);wallets.setBalances(w.getId(),w.getAvailableToken()+a,w.getFrozenToken()-a);}}
 public void settleRedis(long userId,String model,String requestId,long used){Long ok=redis.execute(settle,List.of(reservationKey(requestId),walletKey(userId,model)),Long.toString(used));if(ok==null||ok==0)throw new IllegalStateException("reservation not frozen");}
 public long amount(String requestId){Object v=redis.opsForHash().get(reservationKey(requestId),"amount");return v==null?0:Long.parseLong(v.toString());}
 private void ensure(String key,long userId,String model){if(Boolean.TRUE.equals(redis.hasKey(key)))return; TokenWallet w=wallets.findByUserAndModel(userId,model);if(w==null){wallets.createIfAbsent(userId,model);w=wallets.findByUserAndModel(userId,model);} redis.opsForHash().put(key,"available",Long.toString(w.getAvailableToken()));redis.opsForHash().put(key,"frozen",Long.toString(w.getFrozenToken()));redis.opsForHash().put(key,"credit",Long.toString(config.getCreditLimit()));}
 public TokenWallet lock(long userId,String model){TokenWallet w=wallets.lockByUserAndModel(userId,model);if(w==null)throw new IllegalStateException("wallet missing");return w;}
 public static String walletKey(long userId,String model){return "wallet:"+userId+":"+model;} public static String reservationKey(String id){return "token:reservation:"+id;}
}
