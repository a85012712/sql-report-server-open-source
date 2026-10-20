package com.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FormLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginPage_containsCsrf() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("_csrf")));
    }

    @Test
    void formLogin_success() throws Exception {
        mockMvc.perform(formLogin("/login").user("admin").password("AdminTest1"))
            .andExpect(authenticated().withUsername("admin"));
    }

    @Test
    void formLogin_badPassword_unauthenticated() throws Exception {
        mockMvc.perform(formLogin("/login").user("admin").password("wrong-password-xx"))
            .andExpect(unauthenticated());
    }

    @Test
    void postLogin_withoutCsrf_rejected() throws Exception {
        // 无 CSRF 应失败（4xx）
        mockMvc.perform(post("/login")
                .param("username", "admin")
                .param("password", "AdminTest1"))
            .andExpect(status().isForbidden());
    }
}
