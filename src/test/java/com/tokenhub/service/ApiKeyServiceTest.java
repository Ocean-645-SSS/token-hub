package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.CreateApiKeyRequest;
import com.tokenhub.entity.ApiKey;
import com.tokenhub.mapper.ApiKeyMapper;
import com.tokenhub.security.CurrentUser;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class ApiKeyServiceTest {
 private final ApiKeyMapper keys=mock(ApiKeyMapper.class); private final StringRedisTemplate redis=mock(StringRedisTemplate.class,RETURNS_DEEP_STUBS); private final ApiKeyService service=new ApiKeyService(keys,redis);
 @AfterEach void clear(){CurrentUser.clear();}
 @Test void createOnlyPersistsHashAndReturnsSecretOnce(){CurrentUser.set(7L);doAnswer(i->{i.<ApiKey>getArgument(0).setId(9L);return 1;}).when(keys).insert(any());var response=service.create(new CreateApiKeyRequest("ci",List.of("gpt-4o-mini")));assertNotNull(response.key());verify(keys).insert(argThat(k->!k.getKeyHash().equals(response.key())&&k.getKeyPrefix().equals(response.key().substring(0,12))));}
 @Test void disabledOrUnknownKeyIsRejected(){assertEquals("INVALID_API_KEY",assertThrows(BusinessException.class,()->service.authenticate("th_missing")).code());}
}
