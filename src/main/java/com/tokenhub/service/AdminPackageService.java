package com.tokenhub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.BusinessException;
import com.tokenhub.config.AdminProperties;
import com.tokenhub.dto.CreatePackageRequest;
import com.tokenhub.dto.UpdatePackageRequest;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.entity.User;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.mapper.TokenPackageMapper;
import com.tokenhub.mapper.UserMapper;
import com.tokenhub.messaging.PackageIndexSyncMessage;
import com.tokenhub.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class AdminPackageService {
    private final TokenPackageMapper packages;
    private final LocalMessageMapper messages;
    private final UserMapper users;
    private final ObjectMapper json;
    private final Set<Long> adminUserIds;

    public AdminPackageService(TokenPackageMapper packages, LocalMessageMapper messages, UserMapper users,
                               ObjectMapper json, AdminProperties properties) {
        this.packages = packages;
        this.messages = messages;
        this.users = users;
        this.json = json;
        this.adminUserIds = properties.allowedUserIds();
    }

    @Transactional
    public TokenPackage create(CreatePackageRequest request) {
        requireAdmin();
        if (request.status() != null && request.status() != 0 && request.status() != 1) {
            throw invalidPackage();
        }
        TokenPackage item = new TokenPackage();
        item.setName(request.name());
        item.setModelCode(request.modelCode());
        item.setTokenAmount(request.tokenAmount());
        item.setPrice(request.price());
        item.setDescription(request.description());
        item.setSuitableScenario(request.suitableScenario());
        item.setStock(request.stock());
        item.setStatus(request.status() == null ? 1 : request.status());
        item.setCatalogVersion(1L);
        if (packages.insertCatalog(item) != 1 || item.getId() == null) {
            throw new IllegalStateException("package insert failed");
        }
        enqueueIndexSync(item.getId());
        return item;
    }

    @Transactional
    public TokenPackage update(long id, UpdatePackageRequest request) {
        requireAdmin();
        TokenPackage item = packages.findByIdForIndexSync(id);
        if (item == null) throw packageNotFound();
        item.setName(request.name());
        item.setModelCode(request.modelCode());
        item.setTokenAmount(request.tokenAmount());
        item.setPrice(request.price());
        item.setDescription(request.description());
        item.setSuitableScenario(request.suitableScenario());
        item.setStatus(request.status());
        if (packages.updateCatalog(item) != 1) throw packageNotFound();
        enqueueIndexSync(id);
        return packages.findByIdForIndexSync(id);
    }

    @Transactional
    public void deactivate(long id) {
        requireAdmin();
        if (packages.findByIdForIndexSync(id) == null) throw packageNotFound();
        if (packages.deactivate(id) != 1) throw packageNotFound();
        enqueueIndexSync(id);
    }

    private void requireAdmin() {
        Long userId = CurrentUser.get();
        User user = userId == null ? null : users.findById(userId);
        if (user == null || user.getStatus() != 1 || !adminUserIds.contains(userId)) {
            throw new BusinessException("FORBIDDEN", "administrator access required", HttpStatus.FORBIDDEN);
        }
    }

    private void enqueueIndexSync(long packageId) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        LocalMessage message = new LocalMessage();
        message.setMessageId(eventId);
        message.setEventType("PACKAGE_INDEX_SYNC");
        message.setBizId(eventId);
        message.setTopic("package.index");
        try {
            message.setPayload(json.writeValueAsString(new PackageIndexSyncMessage(packageId)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("package index event serialization failed", exception);
        }
        if (messages.insert(message) != 1) throw new IllegalStateException("package index event insert failed");
    }

    private BusinessException packageNotFound() {
        return new BusinessException("PACKAGE_NOT_FOUND", "package not found", HttpStatus.NOT_FOUND);
    }

    private BusinessException invalidPackage() {
        return new BusinessException("INVALID_PACKAGE", "package status must be 0 or 1", HttpStatus.BAD_REQUEST);
    }
}
