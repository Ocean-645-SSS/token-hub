package com.tokenhub.controller;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.ChatCompletionRequest;
import com.tokenhub.service.ChatCompletionService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/v1/chat") public class ChatController { private final ChatCompletionService service; public ChatController(ChatCompletionService service){this.service=service;} @PostMapping("/completions") public Map<String,Object> complete(@RequestHeader(value="Authorization",required=false)String authorization,@RequestHeader(value="Idempotency-Key",required=false)String id,@Valid @RequestBody ChatCompletionRequest request){return service.complete(authorization,id,request);} @ExceptionHandler(BusinessException.class) public ResponseEntity<Map<String,Object>> error(BusinessException e){return ResponseEntity.status(e.status()).body(Map.of("error",Map.of("code",e.code(),"message",e.getMessage())));} }
