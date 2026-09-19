package com.tokenhub.controller;

import com.tokenhub.common.BusinessException;
import com.tokenhub.service.PackageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PackageControllerTest {
    @Test void missingPackageUsesBusinessErrorEnvelope() throws Exception {
        PackageService packageService = mock(PackageService.class);
        when(packageService.detail(99L)).thenThrow(new BusinessException("PACKAGE_NOT_FOUND", "package not found", HttpStatus.NOT_FOUND));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PackageController(packageService)).setControllerAdvice(new com.tokenhub.common.GlobalExceptionHandler()).build();
        mockMvc.perform(get("/api/packages/99")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PACKAGE_NOT_FOUND")).andExpect(jsonPath("$.data").doesNotExist());
    }
}
