package com.tokenhub.model;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.ModelProperties;
import com.tokenhub.dto.ChatCompletionRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.util.*;
@Component public class OpenAiCompatibleModelProvider implements ModelProvider {
 private final RestClient client; private final ObjectMapper json; private final ModelProperties properties;
 public OpenAiCompatibleModelProvider(ObjectMapper json,ModelProperties properties){this.json=json;this.properties=properties;SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(properties.getConnectTimeoutMs());f.setReadTimeout(properties.getReadTimeoutMs());this.client=RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(f).build();}
 @SuppressWarnings("unchecked") public ProviderResponse complete(ChatCompletionRequest request){ try { Map<String,Object> payload=new LinkedHashMap<>();payload.put("model",request.model());payload.put("messages",request.messages());payload.put("max_tokens",request.maxTokens()); String body=client.post().uri("/v1/chat/completions").headers(h->{if(!properties.getApiKey().isBlank())h.setBearerAuth(properties.getApiKey());}).contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class); Map<String,Object> result=json.readValue(body,new TypeReference<>(){}); Map<String,Object> usage=result.get("usage") instanceof Map<?,?> raw ? (Map<String,Object>)raw : Map.of(); return new ProviderResponse(result,number(usage.get("prompt_tokens")),number(usage.get("completion_tokens")),number(usage.get("total_tokens"))); } catch(HttpStatusCodeException e){int s=e.getStatusCode().value();throw new ModelProviderException("provider http failure",s==429||s==502||s==503||s==504,e);} catch(ResourceAccessException e){throw new ModelProviderException("provider network failure",true,e);} catch(Exception e){throw new ModelProviderException("provider response failure",false,e);} }
 private static Long number(Object v){return v instanceof Number n?n.longValue():null;}
}
